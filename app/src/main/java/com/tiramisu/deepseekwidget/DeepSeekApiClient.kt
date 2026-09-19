package com.tiramisu.deepseekwidget

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * DeepSeek open-platform API client.
 *
 * Uses the CURRENT platform endpoints (2026-08):
 *  - summary:        GET /api/v0/users/get_user_summary        (balance / available tokens)
 *  - usage amounts:  GET /api/v0/usage/by_api_key/amount       ?start=<epochSec>&end=<epochSec>&tz=<offsetSec>
 *  - usage costs:    GET /api/v0/usage/by_api_key/cost         ?start=<epochSec>&end=<epochSec>&tz=<offsetSec>
 *
 * The old endpoints `/api/v0/usage/amount?month=&year=` and `/api/v0/usage/cost?month=&year=`
 * were replaced by the platform (the web console now groups usage by API key and supports a
 * configurable billing/display timezone). Ranges are expressed in Unix epoch seconds and the
 * `tz` parameter carries the timezone offset in seconds (multiple of 900, -43200..50400),
 * matching the official web client.
 *
 * The timezone used for "today" / "this month" boundaries is passed in via constructor
 * (device timezone by default, or the user-selected override from the widget config).
 */
class DeepSeekApiClient(
    private val token: String,
    private val usageTimeZone: TimeZone = TimeZone.getDefault()
) {

    companion object {
        private const val PLATFORM_BASE = "https://platform.deepseek.com"
        private const val SUMMARY_URL = "$PLATFORM_BASE/api/v0/users/get_user_summary"
        private const val AMOUNT_URL = "$PLATFORM_BASE/api/v0/usage/by_api_key/amount"
        private const val COST_URL = "$PLATFORM_BASE/api/v0/usage/by_api_key/cost"
        private const val TIMEOUT_SECONDS = 15L

        // 2026-08 新上线多模态模型（价格与 Flash 一致）
        private const val MODEL_FLASH = "deepseek-v4-flash"
        private const val MODEL_VISION = "deepseek-v4-flash-vision-exp"
        private const val MODEL_PRO = "deepseek-v4-pro"
    }

    private val gson = Gson()
    private val tzOffsetMinutes = usageTimeZone.getOffset(System.currentTimeMillis()) / 60000

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
        .header("x-client-timezone-offset", tzOffsetMinutes.toString())
        .build()

    fun fetchAll(): WidgetDisplayData {
        try {
            val summary = fetchSummary()
            val month = fetchMonthUsage()
            Log.d("DS_WIDGET", "summary=$summary, monthFlash=${month.flash}, monthPro=${month.pro}, todayTotal=${month.todayCostTotal}")
            return WidgetDisplayData(
                isAvailable = true,
                balance = summary.balance,
                totalAvailableTokens = summary.totalAvailableTokens,
                todayCost = month.todayCostTotal,
                monthlyCost = month.monthCostTotal,
                monthlyTokens = month.monthTokens,
                flashData = month.flash,
                visionData = month.vision,
                proData = month.pro,
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
        /** Account-level historical cumulative cost (official "Total cost"), CNY. */
        val totalCost: String = "0.00",
        val hasTotalCost: Boolean = false
    )

    private fun fetchSummary(): SummaryResult {
        val body = execute(SUMMARY_URL)
        val resp = gson.fromJson(body, SummaryResponse::class.java)
        val biz = resp.data?.bizData ?: throw Exception("summary 数据为空")

        val balance = biz.normalWallets?.firstOrNull()?.let {
            "%.2f".format(it.balance?.toDoubleOrNull() ?: 0.0)
        } ?: "0.00"

        val totalAvailableTokens = biz.totalAvailableTokenEstimation?.toLongOrNull() ?: 0L

        // 2026-08 platform: historical cumulative cost lives in total_costs
        // (monthly_costs is deprecated). Kept account-level, filter-independent.
        val totalCostValue = biz.totalCosts?.firstOrNull()?.amount?.toDoubleOrNull()

        return SummaryResult(
            balance = balance,
            totalAvailableTokens = totalAvailableTokens,
            totalCost = totalCostValue?.let { "%.2f".format(it) } ?: "0.00",
            hasTotalCost = totalCostValue != null
        )
    }

    // ─── 本月 + 今日用量（新 by_api_key 端点，时区感知）──────────

    private data class TokenBreakdown(
        val inputTokens: Long = 0,
        val outputTokens: Long = 0,
        val cacheHitTokens: Long = 0,
        val cacheMissTokens: Long = 0,
        val requests: Long = 0
    ) {
        // 新端点只返回 HIT/MISS/RESPONSE/REQUEST；输入 = 命中 + 未命中
        val totalTokens: Long get() = inputTokens + outputTokens
        val cacheHitRate: String get() {
            val total = cacheHitTokens + cacheMissTokens
            return if (total > 0)
                "%.1f".format((cacheHitTokens.toDouble() / total) * 100)
            else "--"
        }

        fun add(u: UsageByKeyUsage): TokenBreakdown {
            val hit = u.cacheHitToken?.toLongOrNull() ?: 0L
            val miss = u.cacheMissToken?.toLongOrNull() ?: 0L
            val prompt = u.promptToken?.toLongOrNull() ?: 0L
            // 新端点输入 = HIT + MISS；若返回 PROMPT_TOKEN 但无细分，则用其作输入
            val input = if (hit + miss > 0) hit + miss else prompt
            return TokenBreakdown(
                inputTokens = this.inputTokens + input,
                outputTokens = this.outputTokens + (u.responseToken?.toLongOrNull() ?: 0L),
                cacheHitTokens = this.cacheHitTokens + hit,
                cacheMissTokens = this.cacheMissTokens + miss,
                requests = this.requests + (u.request?.toLongOrNull() ?: 0L)
            )
        }
    }

    private data class MonthUsageResult(
        val todayCostTotal: String = "0.00",
        val monthCostTotal: String = "0.00",
        val monthTokens: Long = 0,
        val flash: ModelData = ModelData(),
        val vision: ModelData = ModelData(),
        val pro: ModelData = ModelData()
    )

    private fun fetchMonthUsage(): MonthUsageResult {
        val now = Calendar.getInstance(usageTimeZone)
        val year = now.get(Calendar.YEAR)
        val month = now.get(Calendar.MONTH) + 1
        val day = now.get(Calendar.DAY_OF_MONTH)

        val tzOffsetSec = usageTimeZone.getOffset(System.currentTimeMillis()) / 1000
        val monthStart = midnightEpochSec(year, month, 1)
        val monthEnd = rollMonthEnd(year, month)
        val todayStart = midnightEpochSec(year, month, day)
        val todayEnd = rollDayEnd(year, month, day)

        // Aggregate per model: month-wide + today-only
        val monthTokensByModel = mutableMapOf<String, TokenBreakdown>()
        val monthCostByModel = mutableMapOf<String, Double>()
        val todayTokensByModel = mutableMapOf<String, TokenBreakdown>()
        val todayCostByModel = mutableMapOf<String, Double>()

        // usage/amount: token counts per model (all API keys summed)
        var amountError: String? = null
        var costError: String? = null
        var amountSeriesCount = 0
        var costSeriesCount = 0
        try {
            val body = execute("$AMOUNT_URL?start=$monthStart&end=$monthEnd&tz=$tzOffsetSec")
            Log.d("DS_AMOUNT", body.take(2000))
            val resp = gson.fromJson(body, UsageByKeyAmountResponse::class.java)
            if (resp.code != 0) throw Exception("code=${resp.code} ${resp.msg}")
            val data = resp.data ?: throw Exception("data 为空: ${body.take(200)}")
            if (data.bizCode != 0) throw Exception("biz_code=${data.bizCode} ${data.bizMsg}")
            val biz = data.bizData ?: throw Exception("biz_data 为空: ${body.take(200)}")
            val series = biz.series ?: emptyList()
            amountSeriesCount = series.size
            for (s in series) {
                val model = s.model ?: continue
                for (b in s.buckets ?: emptyList()) {
                    val t = b.time ?: continue
                    val u = b.usage ?: continue
                    if (t >= monthStart && t < monthEnd) {
                        val bd = monthTokensByModel.getOrPut(model) { TokenBreakdown() }
                        monthTokensByModel[model] = bd.add(u)
                        if (t >= todayStart && t < todayEnd) {
                            val td = todayTokensByModel.getOrPut(model) { TokenBreakdown() }
                            todayTokensByModel[model] = td.add(u)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DS_API", "amount failed", e)
            amountError = e.message ?: "未知错误"
        }

        // usage/cost: per-model cost amounts in CNY (all API keys summed)
        try {
            val body = execute("$COST_URL?start=$monthStart&end=$monthEnd&tz=$tzOffsetSec")
            Log.d("DS_COST", body.take(2000))
            val resp = gson.fromJson(body, UsageByKeyCostResponse::class.java)
            if (resp.code != 0) throw Exception("code=${resp.code} ${resp.msg}")
            val data = resp.data ?: throw Exception("data 为空: ${body.take(200)}")
            if (data.bizCode != 0) throw Exception("biz_code=${data.bizCode} ${data.bizMsg}")
            val biz = data.bizData ?: throw Exception("biz_data 为空: ${body.take(200)}")
            val entry = biz.data?.firstOrNull()
            costSeriesCount = entry?.series?.size ?: 0
            for (s in entry?.series ?: emptyList()) {
                val model = s.model ?: continue
                for (b in s.buckets ?: emptyList()) {
                    val t = b.time ?: continue
                    val cost = b.cost?.toDoubleOrNull() ?: 0.0
                    if (t >= monthStart && t < monthEnd) {
                        monthCostByModel[model] = (monthCostByModel[model] ?: 0.0) + cost
                        if (t >= todayStart && t < todayEnd) {
                            todayCostByModel[model] = (todayCostByModel[model] ?: 0.0) + cost
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("DS_API", "cost failed", e)
            costError = e.message ?: "未知错误"
        }

        // 出错时不再静默归零：把真实原因透传到 widget 上
        if (amountError != null && costError != null) {
            throw Exception("用量接口失败: amount[$amountError] cost[$costError]")
        }
        if (amountError == null && costError == null && amountSeriesCount == 0 && costSeriesCount == 0) {
            throw Exception("用量接口返回空数据(series 为空)，请反馈")
        }

        val monthTokensAll = monthTokensByModel.values.sumOf { it.totalTokens }
        val monthCostAll = monthCostByModel.values.sum()

        val flashToday = todayTokensByModel[MODEL_FLASH] ?: TokenBreakdown()
        val visionToday = todayTokensByModel[MODEL_VISION] ?: TokenBreakdown()
        val proToday = todayTokensByModel[MODEL_PRO] ?: TokenBreakdown()
        val flashMonthTk = monthTokensByModel[MODEL_FLASH]?.totalTokens ?: 0L
        val visionMonthTk = monthTokensByModel[MODEL_VISION]?.totalTokens ?: 0L
        val proMonthTk = monthTokensByModel[MODEL_PRO]?.totalTokens ?: 0L

        val todayCostTotal = (todayCostByModel[MODEL_FLASH] ?: 0.0) +
            (todayCostByModel[MODEL_VISION] ?: 0.0) +
            (todayCostByModel[MODEL_PRO] ?: 0.0)

        // Flash / Flash Vision Exp / Pro 卡片仍显示“今日”数据（与旧版行为一致）
        return MonthUsageResult(
            todayCostTotal = "%.2f".format(todayCostTotal),
            monthCostTotal = "%.2f".format(monthCostAll),
            monthTokens = monthTokensAll,
            flash = ModelData(
                totalTokens = flashToday.totalTokens,
                cacheHitRate = flashToday.cacheHitRate,
                cost = "%.2f".format(todayCostByModel[MODEL_FLASH] ?: 0.0),
                requests = flashToday.requests,
                monthlyTokens = flashMonthTk
            ),
            vision = ModelData(
                totalTokens = visionToday.totalTokens,
                cacheHitRate = visionToday.cacheHitRate,
                cost = "%.2f".format(todayCostByModel[MODEL_VISION] ?: 0.0),
                requests = visionToday.requests,
                monthlyTokens = visionMonthTk
            ),
            pro = ModelData(
                totalTokens = proToday.totalTokens,
                cacheHitRate = proToday.cacheHitRate,
                cost = "%.2f".format(todayCostByModel[MODEL_PRO] ?: 0.0),
                requests = proToday.requests,
                monthlyTokens = proMonthTk
            )
        )
    }

    // ─── 详细用量（保留 API Key × 天 维度）──────────────────────

    /**
     * Fetch the widest supported window (day-aligned in the usage timezone, default 30 days)
     * with ALL API keys kept as a first-class dimension.
     *
     * The Detailed Usage widget renders Time / API-Key filters purely from this payload, so
     * cycling a filter or toggling Total-Cost visibility never triggers a new request.
     *
     * Tokens = PROMPT_CACHE_HIT + PROMPT_CACHE_MISS + RESPONSE (REQUEST excluded), matching
     * the definition already used by the compact widget and the official usage page.
     */
    fun fetchDetailedUsage(windowDays: Int = 30): DetailedUsageData {
        return try {
            val summary = fetchSummary()
            val now = System.currentTimeMillis()
            val tzOffsetSec = usageTimeZone.getOffset(now) / 1000

            // Day-aligned window: [today-(N-1) .. today], each entry a local midnight.
            val dayList = ArrayList<Long>(windowDays)
            run {
                val c = Calendar.getInstance(usageTimeZone)
                c.timeInMillis = now
                c.set(Calendar.HOUR_OF_DAY, 0)
                c.set(Calendar.MINUTE, 0)
                c.set(Calendar.SECOND, 0)
                c.set(Calendar.MILLISECOND, 0)
                c.add(Calendar.DAY_OF_MONTH, -(windowDays - 1))
                repeat(windowDays) {
                    dayList.add(c.timeInMillis / 1000)
                    c.add(Calendar.DAY_OF_MONTH, 1)
                }
            }
            val windowStart = dayList.first()
            // Exclusive end = local midnight of tomorrow (last listed day + 1).
            val windowEnd = dayList.last() + 86_400L
            val daySet = HashSet(dayList)

            val keyOrder = LinkedHashMap<String, KeyInfo>()
            val costMap = HashMap<String, HashMap<Long, Double>>()
            val reqMap = HashMap<String, HashMap<Long, Long>>()
            val tokMap = HashMap<String, HashMap<Long, Long>>()

            var amountError: String? = null
            var costError: String? = null

            // ── amount: per (api_key, model, day) token / request counts ──
            try {
                val body = execute("$AMOUNT_URL?start=$windowStart&end=$windowEnd&tz=$tzOffsetSec")
                val resp = gson.fromJson(body, UsageByKeyAmountResponse::class.java)
                if (resp.code != 0) throw Exception("code=${resp.code} ${resp.msg}")
                val biz = resp.data?.bizData ?: throw Exception("biz_data 为空: ${body.take(200)}")
                for (s in biz.series ?: emptyList()) {
                    val key = parseKey(s.apiKey)
                    keyOrder[key.id] = key
                    for (b in s.buckets ?: emptyList()) {
                        val day = localDayStart(b.time ?: continue)
                        if (day !in daySet) continue
                        val u = b.usage ?: continue
                        val hit = u.cacheHitToken?.toLongOrNull() ?: 0L
                        val miss = u.cacheMissToken?.toLongOrNull() ?: 0L
                        val prompt = u.promptToken?.toLongOrNull() ?: 0L
                        val input = if (hit + miss > 0) hit + miss else prompt
                        val tokens = input + (u.responseToken?.toLongOrNull() ?: 0L)
                        val requests = u.request?.toLongOrNull() ?: 0L
                        tokMap.getOrPut(key.id) { HashMap() }.let { m -> m[day] = (m[day] ?: 0L) + tokens }
                        reqMap.getOrPut(key.id) { HashMap() }.let { m -> m[day] = (m[day] ?: 0L) + requests }
                    }
                }
            } catch (e: Exception) {
                Log.w("DS_API", "detailed amount failed", e)
                amountError = e.message ?: "未知错误"
            }

            // ── cost: per (api_key, model, day) CNY ──
            try {
                val body = execute("$COST_URL?start=$windowStart&end=$windowEnd&tz=$tzOffsetSec")
                val resp = gson.fromJson(body, UsageByKeyCostResponse::class.java)
                if (resp.code != 0) throw Exception("code=${resp.code} ${resp.msg}")
                val biz = resp.data?.bizData ?: throw Exception("biz_data 为空: ${body.take(200)}")
                val entry = biz.data?.firstOrNull()
                for (s in entry?.series ?: emptyList()) {
                    val key = parseKey(s.apiKey)
                    keyOrder[key.id] = key
                    for (b in s.buckets ?: emptyList()) {
                        val day = localDayStart(b.time ?: continue)
                        if (day !in daySet) continue
                        val cost = b.cost?.toDoubleOrNull() ?: 0.0
                        costMap.getOrPut(key.id) { HashMap() }.let { m -> m[day] = (m[day] ?: 0.0) + cost }
                    }
                }
            } catch (e: Exception) {
                Log.w("DS_API", "detailed cost failed", e)
                costError = e.message ?: "未知错误"
            }

            if (amountError != null && costError != null) {
                throw Exception("用量接口失败: amount[$amountError] cost[$costError]")
            }

            val keys = keyOrder.values.sortedBy { it.name.lowercase() }
            val records = ArrayList<UsageRecord>()
            for (k in keys) {
                val cm = costMap[k.id]
                val rm = reqMap[k.id]
                val tm = tokMap[k.id]
                if (cm == null && rm == null && tm == null) continue
                for (d in dayList) {
                    val c = cm?.get(d) ?: 0.0
                    val r = rm?.get(d) ?: 0L
                    val tk = tm?.get(d) ?: 0L
                    if (c == 0.0 && r == 0L && tk == 0L) continue
                    records.add(UsageRecord(k.id, d, c, r, tk))
                }
            }

            Log.d("DS_DETAIL", "keys=${keys.size}, records=${records.size}, days=${dayList.size}")
            DetailedUsageData(
                isAvailable = true,
                balance = summary.balance,
                totalCost = summary.totalCost,
                hasTotalCost = summary.hasTotalCost,
                keys = keys,
                records = records,
                days = dayList,
                updatedAt = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e("DS_API", "fetchDetailedUsage failed", e)
            DetailedUsageData(error = e.message ?: "未知错误")
        }
    }

    /** Extract {id, name} from the `api_key` metadata object of a series entry. */
    private fun parseKey(el: JsonElement?): KeyInfo {
        return try {
            val obj = el?.takeIf { it.isJsonObject }?.asJsonObject
            fun str(field: String): String? =
                obj?.get(field)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            val name = str("name")
            val trackingId = str("tracking_id")
            val sensitiveId = str("sensitive_id")
            val id = trackingId ?: sensitiveId ?: name ?: "unknown"
            KeyInfo(id = id, name = name ?: id)
        } catch (_: Exception) {
            KeyInfo(id = "unknown", name = "Unknown")
        }
    }

    /** Local midnight (usage timezone) of the day that contains the given epoch seconds. */
    private fun localDayStart(epochSec: Long): Long {
        val c = Calendar.getInstance(usageTimeZone)
        c.timeInMillis = epochSec * 1000
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis / 1000
    }

    // ─── 时间计算（基于用量时区）────────────────────────────────

    /** Local midnight (00:00 in usageTimeZone) as epoch seconds. */
    private fun midnightEpochSec(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance(usageTimeZone)
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return c.timeInMillis / 1000
    }

    /** First day of next month (exclusive end of the current month range). */
    private fun rollMonthEnd(year: Int, month: Int): Long {
        val c = Calendar.getInstance(usageTimeZone)
        c.clear()
        c.set(year, month - 1, 1, 0, 0, 0)
        c.add(Calendar.MONTH, 1)
        return c.timeInMillis / 1000
    }

    /** Day after the given day (exclusive end of the "today" range). */
    private fun rollDayEnd(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance(usageTimeZone)
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis / 1000
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
    // 2026-08 platform update: monthly_costs → total_costs (kept both for compatibility)
    @SerializedName("monthly_costs") val monthlyCosts: List<MonthlyCost>? = null,
    @SerializedName("total_costs") val totalCosts: List<MonthlyCost>? = null,
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

// ── usage/by_api_key/amount（2026-08 新端点）────────────────────

data class UsageByKeyAmountResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: UsageByKeyAmountData? = null
)

data class UsageByKeyAmountData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: UsageByKeyAmountBiz? = null
)

data class UsageByKeyAmountBiz(
    val start: Long? = null,
    val end: Long? = null,
    val bucket: Long? = null,
    val models: List<String>? = null,
    val series: List<UsageByKeySeries>? = null
)

data class UsageByKeySeries(
    // 实际是对象（含 tracking_id/name 等元信息），不是字符串；本应用只按 model 汇总，忽略内容
    @SerializedName("api_key") val apiKey: JsonElement? = null,
    val model: String? = null,
    val buckets: List<UsageBucket>? = null
)

data class UsageBucket(
    val time: Long? = null,
    val usage: UsageByKeyUsage? = null
)

data class UsageByKeyUsage(
    @SerializedName("PROMPT_TOKEN") val promptToken: String? = null,
    @SerializedName("PROMPT_CACHE_HIT_TOKEN") val cacheHitToken: String? = null,
    @SerializedName("PROMPT_CACHE_MISS_TOKEN") val cacheMissToken: String? = null,
    @SerializedName("RESPONSE_TOKEN") val responseToken: String? = null,
    @SerializedName("REQUEST") val request: String? = null
)


// ── usage/by_api_key/cost（2026-08 新端点）──────────────────────

data class UsageByKeyCostResponse(
    val code: Int = -1,
    val msg: String = "",
    val data: UsageByKeyCostData? = null
)

data class UsageByKeyCostData(
    @SerializedName("biz_code") val bizCode: Int = -1,
    @SerializedName("biz_msg") val bizMsg: String = "",
    @SerializedName("biz_data") val bizData: UsageByKeyCostBiz? = null
)

data class UsageByKeyCostBiz(
    val start: Long? = null,
    val end: Long? = null,
    val bucket: Long? = null,
    val models: List<String>? = null,
    val data: List<UsageCostCurrency>? = null
)

data class UsageCostCurrency(
    val currency: String? = null,
    val series: List<UsageByKeyCostSeries>? = null
)

data class UsageByKeyCostSeries(
    // 实际是对象（含 tracking_id/name 等元信息），不是字符串；本应用只按 model 汇总，忽略内容
    @SerializedName("api_key") val apiKey: JsonElement? = null,
    val model: String? = null,
    val buckets: List<CostBucket>? = null
)

data class CostBucket(
    val time: Long? = null,
    val cost: String? = null
)
