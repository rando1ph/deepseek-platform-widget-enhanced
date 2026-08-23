package com.tiramisu.deepseekwidget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * DeepSeek API 定价自动更新。
 *
 * 2026-08-13 起 DeepSeek 采用峰谷定价（北京时间周一至周五 9:00-12:00、14:00-18:00
 * 为高峰，其余（含周末）为闲时，闲时为高峰一半），2026-08-17 00:00（北京时间）生效。
 * 2026-08 平台新增多模态模型 deepseek-v4-flash-vision-exp，价格与 Flash 一致。
 *
 * 价格来源：官方定价页 https://api-docs.deepseek.com/zh-cn/quick_start/pricing/
 * 每次小组件刷新时检查缓存（>6h 重新抓取），官方改价后小组件自动跟随，无需发版。
 *
 * 内置默认值与官方公告一致，抓取失败时兜底。
 */
data class Price(
    val cacheHit: Double = 0.0,
    val cacheMiss: Double = 0.0,
    val output: Double = 0.0
)

data class DeepSeekPricingSnapshot(
    /** 峰谷定价生效时刻（毫秒，北京时间 00:00）；null = 未知 */
    val effectiveDateMillis: Long? = null,
    /** 峰谷生效前的平峰价格 */
    val legacyFlash: Price? = null,
    val legacyPro: Price? = null,
    /** 峰谷价格（元 / 百万 tokens） */
    val offpeakFlash: Price? = null,
    val peakFlash: Price? = null,
    val offpeakPro: Price? = null,
    val peakPro: Price? = null,
    val offpeakVision: Price? = null,
    val peakVision: Price? = null,
    val fetchedAt: Long = 0L
) {
    val hasPeakValley: Boolean get() = offpeakFlash != null && peakFlash != null

    fun isPeakValleyActive(now: Long): Boolean =
        hasPeakValley && (effectiveDateMillis == null || now >= effectiveDateMillis)

    /** 模型索引 0=Flash 1=Flash Vision Exp 2=Pro；vision 缺价格时回退 Flash。 */
    fun outputPrice(modelIndex: Int, peak: Boolean): Double? {
        val (off, pk) = when (modelIndex) {
            2 -> offpeakPro to peakPro
            1 -> (offpeakVision ?: offpeakFlash) to (peakVision ?: peakFlash)
            else -> offpeakFlash to peakFlash
        }
        return if (peak) pk?.output else off?.output
    }
}

object DeepSeekPricing {

    private const val PRICING_URL = "https://api-docs.deepseek.com/zh-cn/quick_start/pricing/"
    private const val CACHE_TTL_MILLIS = 6L * 3600 * 1000

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    /** 默认价格：2026-08-17 生效的峰谷定价（官方公告）；vision 与 flash 同价 */
    val DEFAULT: DeepSeekPricingSnapshot = DeepSeekPricingSnapshot(
        effectiveDateMillis = beijingMillis(2026, 8, 17),
        legacyFlash = Price(0.02, 1.0, 2.0),
        legacyPro = Price(0.025, 3.0, 6.0),
        offpeakFlash = Price(0.05, 1.5, 4.5),
        peakFlash = Price(0.10, 3.0, 9.0),
        offpeakPro = Price(0.15, 4.5, 13.5),
        peakPro = Price(0.30, 9.0, 27.0),
        offpeakVision = Price(0.05, 1.5, 4.5),
        peakVision = Price(0.10, 3.0, 9.0)
    )

    /** 高峰时段（北京时间周一至周五 9-12、14-18；含头不含尾） */
    fun isPeakHour(now: Long): Boolean {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        cal.timeInMillis = now
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        return dow in Calendar.MONDAY..Calendar.FRIDAY && (hour in 9..11 || hour in 14..17)
    }

    // ─── 抓取 + 解析 ────────────────────────────────────────────

    /** 从官方定价页抓取并解析；失败返回 null（调用方保留旧缓存/默认值）。 */
    fun fetch(): DeepSeekPricingSnapshot? {
        return try {
            val request = Request.Builder()
                .url(PRICING_URL)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Accept", "text/html")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val html = resp.body?.string() ?: return null
                parse(html)?.copy(fetchedAt = System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.w("DS_PRICING", "fetch failed", e)
            null
        }
    }

    /** 解析官方定价页 HTML（Docusaurus 服务端渲染，结构稳定）。 */
    fun parse(html: String): DeepSeekPricingSnapshot? {
        // 去标签，转成 | 分隔的 token 流
        val text = html
            .replace(Regex("<[^>]+>"), "|")
            .replace(Regex("\\s+"), "")
        val tokens = text.split("|").map { it.trim() }.filter { it.isNotEmpty() }

        fun num(s: String?): Double? =
            s?.removeSuffix("元")?.trim()?.toDoubleOrNull()

        // ── 峰谷价格表 ──
        // 2026-08 新版页面：按价目分行，行内列为 flash / pro / flash-vision-exp：
        //   （缓存命中）空闲时段 a b c 高峰时段 d e f
        //   （缓存未命中）空闲时段 g h i 高峰时段 j k l
        //   百万tokens输出 空闲时段 m n o 高峰时段 p q r
        // 旧版页面：按模型分行（deepseek-v4-flash/pro 最后一次出现是价格表），作回退。
        var offpeakFlash: Price? = null; var peakFlash: Price? = null
        var offpeakPro: Price? = null; var peakPro: Price? = null
        var offpeakVision: Price? = null; var peakVision: Price? = null

        fun collect(from: Int): Pair<List<Double>, List<Double>>? {
            val off = mutableListOf<Double>()
            val peak = mutableListOf<Double>()
            var j = from
            if (tokens.getOrNull(j) == "空闲时段") {
                j++
                while (j < tokens.size) {
                    val n = num(tokens[j]) ?: break
                    off.add(n); j++
                }
            }
            if (tokens.getOrNull(j) == "高峰时段") {
                j++
                while (j < tokens.size) {
                    val n = num(tokens[j]) ?: break
                    peak.add(n); j++
                }
            }
            return if (off.isEmpty() || peak.isEmpty()) null else off to peak
        }

        fun col(values: List<Double>, i: Int): Double? = values.getOrNull(i)

        val hitIdx = tokens.indexOf("（缓存命中）")
        val missIdx = tokens.indexOf("（缓存未命中）")
        val outIdx = tokens.indexOf("百万tokens输出")
        if (hitIdx >= 0 && missIdx >= 0 && outIdx >= 0) {
            val hit = collect(hitIdx + 1)
            val miss = collect(missIdx + 1)
            val out = collect(outIdx + 1)
            if (hit != null && miss != null && out != null) {
                offpeakFlash = Price(col(hit.first, 0) ?: 0.0, col(miss.first, 0) ?: 0.0, col(out.first, 0) ?: 0.0)
                offpeakPro = Price(col(hit.first, 1) ?: 0.0, col(miss.first, 1) ?: 0.0, col(out.first, 1) ?: 0.0)
                peakFlash = Price(col(hit.second, 0) ?: 0.0, col(miss.second, 0) ?: 0.0, col(out.second, 0) ?: 0.0)
                peakPro = Price(col(hit.second, 1) ?: 0.0, col(miss.second, 1) ?: 0.0, col(out.second, 1) ?: 0.0)
                if (hit.first.size >= 3) {
                    offpeakVision = Price(col(hit.first, 2) ?: 0.0, col(miss.first, 2) ?: 0.0, col(out.first, 2) ?: 0.0)
                    peakVision = Price(col(hit.second, 2) ?: 0.0, col(miss.second, 2) ?: 0.0, col(out.second, 2) ?: 0.0)
                }
            }
        }

        // 旧版按模型分行的表格回退（flash/pro）
        if (offpeakFlash == null) run {
            val i = tokens.lastIndexOf("deepseek-v4-flash")
            if (i >= 0 && i + 11 < tokens.size) {
                // [flash, 空闲时段, a, b, c, 高峰时段, d, e, f, pro, 空闲时段, g, h, i, 高峰时段, j, k, l]
                val off = if (tokens.getOrNull(i + 1) == "空闲时段") Price(
                    num(tokens.getOrNull(i + 2)) ?: 0.0,
                    num(tokens.getOrNull(i + 3)) ?: 0.0,
                    num(tokens.getOrNull(i + 4)) ?: 0.0
                ) else null
                val peak = if (tokens.getOrNull(i + 5) == "高峰时段") Price(
                    num(tokens.getOrNull(i + 6)) ?: 0.0,
                    num(tokens.getOrNull(i + 7)) ?: 0.0,
                    num(tokens.getOrNull(i + 8)) ?: 0.0
                ) else null
                if (off != null && peak != null) {
                    offpeakFlash = off; peakFlash = peak
                }
                val j = tokens.lastIndexOf("deepseek-v4-pro")
                if (j >= 0 && j + 11 < tokens.size) {
                    val off2 = if (tokens.getOrNull(j + 1) == "空闲时段") Price(
                        num(tokens.getOrNull(j + 2)) ?: 0.0,
                        num(tokens.getOrNull(j + 3)) ?: 0.0,
                        num(tokens.getOrNull(j + 4)) ?: 0.0
                    ) else null
                    val peak2 = if (tokens.getOrNull(j + 5) == "高峰时段") Price(
                        num(tokens.getOrNull(j + 6)) ?: 0.0,
                        num(tokens.getOrNull(j + 7)) ?: 0.0,
                        num(tokens.getOrNull(j + 8)) ?: 0.0
                    ) else null
                    if (off2 != null && peak2 != null) {
                        offpeakPro = off2; peakPro = peak2
                    }
                }
            }
        }

        // ── 生效前的平峰价格（主表第一组数值） ──
        var legacyFlash: Price? = null; var legacyPro: Price? = null
        run {
            fun firstValues(label: String): List<Double>? {
                val i = tokens.indexOf(label)
                if (i < 0 || i + 2 >= tokens.size) return null
                val a = num(tokens.getOrNull(i + 1)) ?: return null
                val b = num(tokens.getOrNull(i + 2)) ?: return null
                return listOf(a, b)
            }
            val hit = firstValues("百万tokens输入（缓存命中）")
            val miss = firstValues("百万tokens输入（缓存未命中）")
            val out = firstValues("百万tokens输出")
            if (hit != null && miss != null && out != null) {
                legacyFlash = Price(hit[0], miss[0], out[0])
                legacyPro = Price(hit[1], miss[1], out[1])
            }
        }

        // ── 生效时间（北京时间） ──
        var effectiveDateMillis: Long? = null
        run {
            val m = Regex("新价格将于北京时间(\\d{4})年(\\d{1,2})月(\\d{1,2})日")
                .find(text)
            if (m != null) {
                val y = m.groupValues[1].toIntOrNull()
                val mo = m.groupValues[2].toIntOrNull()
                val d = m.groupValues[3].toIntOrNull()
                if (y != null && mo != null && d != null) {
                    effectiveDateMillis = beijingMillis(y, mo, d)
                }
            }
        }

        val parsed = DeepSeekPricingSnapshot(
            effectiveDateMillis = effectiveDateMillis,
            legacyFlash = legacyFlash,
            legacyPro = legacyPro,
            offpeakFlash = offpeakFlash,
            peakFlash = peakFlash,
            offpeakPro = offpeakPro,
            peakPro = peakPro,
            offpeakVision = offpeakVision,
            peakVision = peakVision
        )
        // 什么表都没解析出来 → 视为失败
        return if (parsed.hasPeakValley || legacyFlash != null) parsed else null
    }

    private fun beijingMillis(year: Int, month: Int, day: Int): Long {
        val c = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
        c.clear()
        c.set(year, month - 1, day, 0, 0, 0)
        return c.timeInMillis
    }

    // ─── 状态行（小组件顶部状态栏显示） ──────────────────────────

    /**
     * 生成价格状态行：
     *  - 峰谷已生效 → "Flash 高峰 ¥9/M" / "Pro 闲时 ¥13.5/M" / "Vision Exp 高峰 ¥9/M"
     *  - 峰谷未生效（已公告） → "8-17起 峰谷定价"
     *  - 仅有平峰价 → "现价 ¥2/M"
     */
    fun statusLine(pricing: DeepSeekPricingSnapshot, modelIndex: Int, now: Long): String {
        val label = when (modelIndex) {
            2 -> "Pro"
            1 -> "Vision Exp"
            else -> "Flash"
        }
        if (pricing.isPeakValleyActive(now)) {
            val price = pricing.outputPrice(modelIndex, isPeakHour(now))
            if (price != null) {
                val period = if (isPeakHour(now)) "高峰" else "闲时"
                return "$label $period ¥${fmt(price)}/M"
            }
        } else if (pricing.effectiveDateMillis != null && now < pricing.effectiveDateMillis) {
            val sdf = SimpleDateFormat("M-d", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("Asia/Shanghai")
            }
            return "${sdf.format(java.util.Date(pricing.effectiveDateMillis))}起 峰谷定价"
        }
        val legacy = when (modelIndex) {
            2 -> pricing.legacyPro
            else -> pricing.legacyFlash
        }
        if (legacy != null) return "现价 ¥${fmt(legacy.output)}/M"
        return ""
    }

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) "%.0f".format(v) else "%.1f".format(v)

    // ─── 缓存（SharedPreferences） ──────────────────────────────

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(DeepSeekWidget.DISPLAY_PREFS, Context.MODE_PRIVATE)

    private const val KEY_PRICING = "pricing_cache"

    fun loadCached(c: Context): DeepSeekPricingSnapshot = try {
        gson.fromJson(prefs(c).getString(KEY_PRICING, null), DeepSeekPricingSnapshot::class.java)
            ?: DEFAULT
    } catch (_: Exception) {
        DEFAULT
    }

    private fun save(c: Context, p: DeepSeekPricingSnapshot) {
        prefs(c).edit().putString(KEY_PRICING, gson.toJson(p)).apply()
    }

    /** 缓存超过 6h 时重新抓取；任何失败都保留旧值，绝不抛出。 */
    fun ensureFresh(c: Context): DeepSeekPricingSnapshot {
        val cached = loadCached(c)
        if (cached.fetchedAt > 0 && System.currentTimeMillis() - cached.fetchedAt < CACHE_TTL_MILLIS) {
            return cached
        }
        val fresh = fetch() ?: return cached
        save(c, fresh)
        return fresh
    }
}
