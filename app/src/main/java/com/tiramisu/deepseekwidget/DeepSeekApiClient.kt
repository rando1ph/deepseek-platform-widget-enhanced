package com.tiramisu.deepseekwidget

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class DeepSeekApiClient(private val token: String) {

    companion object {
        private const val PLATFORM_BASE = "https://platform.deepseek.com"
        private const val SUMMARY_URL = "$PLATFORM_BASE/api/v0/users/get_user_summary"
        private const val COST_URL = "$PLATFORM_BASE/api/v0/usage/cost"
        private const val AMOUNT_URL = "$PLATFORM_BASE/api/v0/usage/amount"
        private const val TIMEOUT_SECONDS = 15L
    }

    private val gson = Gson()
    // DeepSeek usage day boundaries follow UTC. Using the device timezone makes
    // phones in GMT+8 query tomorrow's empty row between 00:00 and 08:00,
    // while an UTC emulator still displays the expected data.
    private val usageTimeZone = TimeZone.getTimeZone("UTC")
    private val cal = Calendar.getInstance(usageTimeZone)

    private val client = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private fun buildRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")
        .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
        .header("x-client-platform", "web")
        .header("x-client-version", "1.0.0")
        .header("x-app-version", "1.0.0")
        .build()

    fun fetchAll(): WidgetDisplayData {
        try {
            val summary = fetchSummary()
            val today = fetchTodayUsage()
            Log.d("DS_WIDGET", "summary=$summary, todayFlash=${today.flash}, todayPro=${today.pro}")
            return WidgetDisplayData(
                isAvailable = true,
                balance = summary.balance,
                totalAvailableTokens = summary.totalAvailableTokens,
                todayCost = today.todayCostTotal,
                monthlyCost = summary.monthlyCost,
                monthlyTokens = summary.monthlyTokens,
                flashData = today.flash,
                proData = today.pro,
                updatedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e("DS_API", "fetchAll failed", e)
            return WidgetDisplayData(error = e.message ?: "未知错误")
        }
    }

    // ─── get_user_summary ──────────────────────────────────────

    private data class SummaryResult(
        val balance: String = "0.00",
        val totalAvailableTokens: Long = 0,
        val monthlyCost: String = "0.00",
        val monthlyTokens: Long = 0
    )

    private fun fetchSummary(): SummaryResult {
        val body = execute(SUMMARY_URL)
        val resp = gson.fromJson(body, SummaryResponse::class.java)
        val biz = resp.data?.bizData ?: throw Exception("summary 数据为空")

        val balance = biz.normalWallets?.firstOrNull()?.let {
            "%.2f".format(it.balance?.toDoubleOrNull() ?: 0.0)
        } ?: "0.00"

        val totalAvailableTokens = biz.totalAvailableTokenEstimation?.toLongOrNull() ?: 0L

        val monthlyCost = biz.monthlyCosts?.firstOrNull()?.let {
            "%.2f".format(it.amount?.toDoubleOrNull() ?: 0.0)
        } ?: "0.00"

        val monthlyTokens = biz.monthlyTokenUsage?.toLongOrNull() ?: 0L

        return SummaryResult(balance, totalAvailableTokens, monthlyCost, monthlyTokens)
    }

    // ─── 今日用量 ──────────────────────────────────────────────

    private data class TodayUsageResult(
        val todayCostTotal: String = "0.00",
        val flash: ModelData = ModelData(),
        val pro: ModelData = ModelData()
    )

    private data class TokenBreakdown(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheHitTokens: Long = 0,
        val cacheMissTokens: Long = 0
    ) {
        val totalTokens: Long get() = inputTokens + outputTokens + cacheHitTokens + cacheMissTokens
        val cacheHitRate: String get() {
            val total = cacheHitTokens + cacheMissTokens
            return if (total > 0)
                "%.1f".format((cacheHitTokens.toDouble() / total) * 100)
            else "--"
        }
    }

    private fun fetchTodayUsage(): TodayUsageResult {
        val month = String.format("%02d", cal.get(Calendar.MONTH) + 1)
        val year = cal.get(Calendar.YEAR).toString()
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = usageTimeZone
        }.format(Date())

        var flashUsage = TokenBreakdown()
        var proUsage = TokenBreakdown()

        // usage/amount: token counts per model
        try {
            val body = execute("$AMOUNT_URL?month=$month&year=$year")
            val resp = gson.fromJson(body, UsageAmountResponse::class.java)
            val days = resp.data?.bizData?.days ?: emptyList()
            val today = days.find { it.date == todayStr }
            if (today != null) {
                for (md in today.data ?: emptyList()) {
                    var inTk = 0L; var outTk = 0L; var hitTk = 0L; var missTk = 0L
                    for (u in md.usage ?: emptyList()) {
                        val amt = u.amount?.toLongOrNull() ?: 0L
                        when (u.type) {
                            "PROMPT_TOKEN" -> inTk += amt
                            "PROMPT_CACHE_HIT_TOKEN" -> hitTk += amt
                            "PROMPT_CACHE_MISS_TOKEN" -> missTk += amt
                            "RESPONSE_TOKEN" -> outTk += amt
                        }
                    }
                    val breakdown = TokenBreakdown(inTk, outTk, hitTk, missTk)
                    when (md.model) {
                        "deepseek-v4-flash" -> flashUsage = breakdown
                        "deepseek-v4-pro" -> proUsage = breakdown
                    }
                }
            }
        } catch (e: Exception) { Log.w("DS_API", "amount failed", e) }

        // usage/cost: per-model cost amounts
        // The cost endpoint's daily amounts are in CNY (decimal)
        var flashCost = 0.0; var proCost = 0.0
        try {
            val body = execute("$COST_URL?month=$month&year=$year")
            val resp = gson.fromJson(body, UsageCostResponse::class.java)
            val entry = resp.data?.bizData?.firstOrNull()
            val todayDay = entry?.days?.find { it.date == todayStr }
            if (todayDay != null) {
                for (md in todayDay.data ?: emptyList()) {
                    var modelCost = 0.0
                    for (u in md.usage ?: emptyList()) {
                        if (u.type == "PROMPT_CACHE_MISS_TOKEN" || u.type == "RESPONSE_TOKEN") {
                            modelCost += u.amount?.toDoubleOrNull() ?: 0.0
                        }
                    }
                    when (md.model) {
                        "deepseek-v4-flash" -> flashCost += modelCost
                        "deepseek-v4-pro" -> proCost += modelCost
                    }
                }
            }
        } catch (e: Exception) { Log.w("DS_API", "cost failed", e) }

        val totalCost = flashCost + proCost

        return TodayUsageResult(
            todayCostTotal = "%.2f".format(totalCost),
            flash = ModelData(flashUsage.totalTokens, flashUsage.cacheHitRate, "%.2f".format(flashCost)),
            pro = ModelData(proUsage.totalTokens, proUsage.cacheHitRate, "%.2f".format(proCost))
        )
    }

    // ─── HTTP ─────────────────────────────────────────────────

    private fun execute(url: String): String {
        val request = buildRequest(url)
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw when (response.code) {
                401 -> Exception("登录已过期，请重新登录")
                else -> Exception("API ${response.code}: ${response.body?.string() ?: ""}")
            }
        }
        return response.body?.string() ?: throw Exception("空响应")
    }
}

// ═══════════════════════════════════════════════════════════════
//  Response DTOs
// ═══════════════════════════════════════════════════════════════

data class SummaryResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: SummaryData? = null
)

data class SummaryData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: SummaryBizData? = null
)

data class SummaryBizData(
    @SerializedName("current_token") val currentToken: Long? = null,
    @SerializedName("monthly_usage") val monthlyUsage: String? = null,
    @SerializedName("total_usage") val totalUsage: Long? = null,
    @SerializedName("normal_wallets") val normalWallets: List<Wallet>? = null,
    @SerializedName("bonus_wallets") val bonusWallets: List<Wallet>? = null,
    @SerializedName("total_available_token_estimation") val totalAvailableTokenEstimation: String? = null,
    @SerializedName("monthly_costs") val monthlyCosts: List<MonthlyCost>? = null,
    @SerializedName("monthly_token_usage") val monthlyTokenUsage: String? = null
)

data class Wallet(
    val currency: String? = null,
    val balance: String? = null,
    @SerializedName("token_estimation") val tokenEstimation: String? = null
)

data class MonthlyCost(
    val currency: String? = null,
    val amount: String? = null
)

// ── usage/amount ────────────────────────────────────────────────

data class UsageAmountResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: UsageAmountData? = null
)

data class UsageAmountData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: UsageAmountBizData? = null
)

data class UsageAmountBizData(
    val total: List<ModelUsageList>? = null,
    val days: List<DayUsage>? = null
)

data class DayUsage(
    val date: String? = null,
    val data: List<ModelUsageList>? = null
)

data class ModelUsageList(
    val model: String? = null,
    val usage: List<UsageEntry>? = null
)

data class UsageEntry(
    val type: String? = null,
    val amount: String? = null
)

// ── usage/cost ──────────────────────────────────────────────────

data class UsageCostResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: UsageCostData? = null
)

data class UsageCostData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: List<CostEntry>? = null
)

data class CostEntry(
    val total: List<ModelUsageList>? = null,
    val days: List<DayUsage>? = null,
    val currency: String? = null
)
