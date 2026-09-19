package com.tiramisu.deepseekwidget

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Local cost estimation for the Detailed Usage widget's CACHE / BILLING block.
 *
 * The DeepSeek cost API only returns an official *total* cost per (api_key × model × day).
 * It does NOT split that total into cache-hit / cache-miss / output components. Everything
 * computed here is therefore an ESTIMATE derived from the project's existing pricing table
 * ([DeepSeekPricingSnapshot], incl. peak/valley pricing) — it must never be shown as, or mixed
 * with, the official cost value.
 *
 * Pure Kotlin (no Android) so it is unit-testable.
 */
object BillingEstimate {

    private val BEIJING: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** Peak windows (Beijing): 9:00-12:00 (3h) + 14:00-18:00 (4h) = 7h on weekdays. */
    private const val PEAK_HOURS_PER_WEEKDAY = 7.0
    private const val HOURS_PER_DAY = 24.0
    private const val MILLION = 1_000_000.0

    /**
     * Map an API model name to the pricing table index (0=Flash, 1=Vision Exp, 2=Pro).
     * Returns null for unknown models — callers must then skip pricing (tokens are still
     * counted), never guess.
     */
    fun modelIndexFor(modelName: String): Int? {
        val n = modelName.lowercase(Locale.US)
        return when {
            n.contains("vision") -> 1
            n.contains("pro") -> 2
            n.contains("flash") -> 0
            else -> null
        }
    }

    /** Fraction of a day charged at the peak rate. Weekdays 7/24, weekends 0. */
    fun peakFractionForDay(dayStartSec: Long): Double {
        val c = Calendar.getInstance(BEIJING)
        c.timeInMillis = dayStartSec * 1000L
        val dayOfWeek = c.get(Calendar.DAY_OF_WEEK)
        return if (dayOfWeek in Calendar.MONDAY..Calendar.FRIDAY) {
            PEAK_HOURS_PER_WEEKDAY / HOURS_PER_DAY
        } else {
            0.0
        }
    }

    /**
     * Effective per-million price for a model on one day.
     *
     * Days before peak/valley pricing took effect (and tables without peak/valley data) use the
     * legacy flat price. Otherwise the day is charged as a duration-weighted blend of the
     * off-peak and peak rates — day-granular buckets cannot reveal the real intra-day mix.
     */
    fun priceForDay(modelIndex: Int, dayStartSec: Long, pricing: DeepSeekPricingSnapshot): Price? {
        val effectiveMs = pricing.effectiveDateMillis
        val beforePeakValley = effectiveMs != null && dayStartSec * 1000L < effectiveMs
        if (!pricing.hasPeakValley || beforePeakValley) {
            return when (modelIndex) {
                2 -> pricing.legacyPro
                else -> pricing.legacyFlash
            }
        }
        val off = pricing.priceFor(modelIndex, peak = false) ?: return null
        val peak = pricing.priceFor(modelIndex, peak = true) ?: return null
        val f = peakFractionForDay(dayStartSec)
        return Price(
            cacheHit = off.cacheHit * (1 - f) + peak.cacheHit * f,
            cacheMiss = off.cacheMiss * (1 - f) + peak.cacheMiss * f,
            output = off.output * (1 - f) + peak.output * f
        )
    }

    /** Convenience: price for a record's model name; null when the model is unknown. */
    fun priceForRecord(modelName: String, dayStartSec: Long, pricing: DeepSeekPricingSnapshot): Price? {
        val index = modelIndexFor(modelName) ?: return null
        return priceForDay(index, dayStartSec, pricing)
    }

    /** Estimated CNY for a token count at a per-million price. */
    fun estimatedCost(tokens: Long, pricePerMillion: Double): Double =
        tokens / MILLION * pricePerMillion
}
