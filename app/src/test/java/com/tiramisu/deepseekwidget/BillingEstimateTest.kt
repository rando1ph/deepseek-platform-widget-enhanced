package com.tiramisu.deepseekwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the local (≈) cost estimator.
 *
 * Date anchors (Beijing midnight, verified): Mon 2026-08-17 = 1786896000,
 * Sat 2026-08-15 = 1786723200, Sun 2026-08-16 = 1786809600.
 */
class BillingEstimateTest {

    private val monAfterEffective = 1_786_896_000L // Mon 2026-08-17 00:00 Beijing
    private val satBeforeEffective = 1_786_723_200L // Sat 2026-08-15
    private val sunBeforeEffective = 1_786_809_600L // Sun 2026-08-16

    /** Mirrors DeepSeekPricing.DEFAULT without touching the Android-coupled singleton. */
    private fun pricing() = DeepSeekPricingSnapshot(
        effectiveDateMillis = monAfterEffective * 1000L,
        legacyFlash = Price(0.02, 1.0, 2.0),
        legacyPro = Price(0.025, 3.0, 6.0),
        offpeakFlash = Price(0.05, 1.5, 4.5),
        peakFlash = Price(0.10, 3.0, 9.0),
        offpeakPro = Price(0.15, 4.5, 13.5),
        peakPro = Price(0.30, 9.0, 27.0),
        offpeakVision = Price(0.07, 1.7, 4.7),
        peakVision = Price(0.14, 3.4, 9.4)
    )

    @Test
    fun modelIndexFor_mapsKnownModelsAndRejectsUnknown() {
        assertEquals(0, BillingEstimate.modelIndexFor("deepseek-v4-flash"))
        assertEquals(1, BillingEstimate.modelIndexFor("deepseek-v4-flash-vision-exp"))
        assertEquals(2, BillingEstimate.modelIndexFor("deepseek-v4-pro"))
        assertNull(BillingEstimate.modelIndexFor("deepseek-v9-mystery"))
    }

    @Test
    fun peakFraction_isSevenOver24OnWeekdays_andZeroAtWeekend() {
        assertEquals(7.0 / 24.0, BillingEstimate.peakFractionForDay(monAfterEffective), 1e-9)
        assertEquals(0.0, BillingEstimate.peakFractionForDay(satBeforeEffective), 1e-9)
        assertEquals(0.0, BillingEstimate.peakFractionForDay(sunBeforeEffective), 1e-9)
    }

    @Test
    fun priceForDay_beforePeakValley_usesLegacyFlatPrice() {
        assertEquals(Price(0.02, 1.0, 2.0), BillingEstimate.priceForDay(0, sunBeforeEffective, pricing()))
        assertEquals(Price(0.025, 3.0, 6.0), BillingEstimate.priceForDay(2, sunBeforeEffective, pricing()))
    }

    @Test
    fun priceForDay_afterPeakValley_blendsPeakAndOffPeakByDuration() {
        // 17/24 hours off-peak + 7/24 hours peak on a weekday.
        val flash = BillingEstimate.priceForDay(0, monAfterEffective, pricing())!!
        assertEquals(0.06458333, flash.cacheHit, 1e-6)
        assertEquals(1.9375, flash.cacheMiss, 1e-6)
        assertEquals(5.8125, flash.output, 1e-6)

        val pro = BillingEstimate.priceForDay(2, monAfterEffective, pricing())!!
        assertEquals(0.19375, pro.cacheHit, 1e-6)
        assertEquals(5.8125, pro.cacheMiss, 1e-6)
        assertEquals(17.4375, pro.output, 1e-6)
    }

    @Test
    fun priceForRecord_unknownModelIsNull_knownModelsResolveToTheirOwnPrices() {
        assertNull(BillingEstimate.priceForRecord("gpt-mystery", monAfterEffective, pricing()))

        // Vision Exp (index 1) must use its OWN rates, not flash's (they differ in this table).
        val vision = BillingEstimate.priceForRecord("deepseek-v4-flash-vision-exp", monAfterEffective, pricing())!!
        assertEquals(0.090416667, vision.cacheHit, 1e-6)
        assertEquals(2.195833333, vision.cacheMiss, 1e-6)
        assertEquals(6.070833333, vision.output, 1e-6)

        val flash = BillingEstimate.priceForRecord("deepseek-v4-flash", monAfterEffective, pricing())!!
        assertEquals(0.064583333, flash.cacheHit, 1e-6)
    }

    @Test
    fun priceForRecord_beforePeakValley_fallsBackToLegacyPrice() {
        // 2026-08-16 is before the peak/valley effective date → legacy flat price.
        assertEquals(
            Price(0.02, 1.0, 2.0),
            BillingEstimate.priceForRecord("deepseek-v4-flash", sunBeforeEffective, pricing())
        )
    }

    @Test
    fun estimatedCost_scalesPerMillionTokens() {
        assertEquals(3.0, BillingEstimate.estimatedCost(2_000_000L, 1.5), 1e-9)
        assertEquals(1.0, BillingEstimate.estimatedCost(1_000_000L, 1.0), 1e-9)
        assertEquals(0.0, BillingEstimate.estimatedCost(0L, 12.0), 1e-9)
    }
}
