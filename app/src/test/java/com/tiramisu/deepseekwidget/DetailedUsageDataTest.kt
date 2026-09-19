package com.tiramisu.deepseekwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the pure aggregation / estimation / formatting logic that drives the
 * Detailed Usage widget. The joint Time × API-Key filter and the per-model ≈ estimation are the
 * riskiest parts, so both are exercised directly.
 */
class DetailedUsageDataTest {

    private val d1 = 1_000L
    private val d2 = 2_000L
    private val d3 = 3_000L
    private val flash = "deepseek-v4-flash"
    private val pro = "deepseek-v4-pro"

    /** Peak and off-peak rates are equal, so the day/peak weighting cannot skew the expected
     *  numbers — the estimate depends only on tokens × per-model price. */
    private fun flatPricing() = DeepSeekPricingSnapshot(
        offpeakFlash = Price(1.0, 2.0, 4.0),
        peakFlash = Price(1.0, 2.0, 4.0),
        offpeakPro = Price(2.0, 4.0, 8.0),
        peakPro = Price(2.0, 4.0, 8.0),
        offpeakVision = Price(1.0, 2.0, 4.0),
        peakVision = Price(1.0, 2.0, 4.0)
    )

    private fun sample() = DetailedUsageData(
        isAvailable = true,
        balance = "58.49",
        totalCost = "491.50",
        hasTotalCost = true,
        keys = listOf(KeyInfo("k1", "RanClaw"), KeyInfo("k2", "OpenCode")),
        records = listOf(
            UsageRecord("k1", flash, d1, 1.0, 10, 2_000_000, 500_000, 300_000),
            UsageRecord("k1", flash, d2, 2.0, 20, 1_000_000, 200_000, 100_000),
            UsageRecord("k1", pro, d3, 4.0, 40, 1_000_000, 1_000_000, 1_000_000),
            UsageRecord("k2", flash, d3, 8.0, 80, 500_000, 500_000, 200_000)
        ),
        days = listOf(d1, d2, d3),
        updatedAt = 42L
    )

    // ── Joint Time × API-Key filtering ─────────────────────────

    @Test
    fun allKeys_fullWindow_sumsEveryRecord() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 3)
        assertEquals(15.0, s.cost, 1e-9)
        assertEquals(150L, s.requests)
        assertEquals(4_500_000L, s.hitTokens)
        assertEquals(2_200_000L, s.missTokens)
        assertEquals(1_600_000L, s.outTokens)
        assertEquals(8_300_000L, s.tokens)
        assertEquals(listOf(1.0, 2.0, 12.0), s.trend)
        assertEquals(listOf(d1, d2, d3), s.trendDays)
    }

    @Test
    fun allKeys_lastDay_onlyNewestBucket() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 1)
        assertEquals(12.0, s.cost, 1e-9)
        assertEquals(120L, s.requests)
        assertEquals(1_500_000L, s.hitTokens)
        assertEquals(1_500_000L, s.missTokens)
        assertEquals(1_200_000L, s.outTokens)
        assertEquals(listOf(12.0), s.trend)
    }

    @Test
    fun singleKey_lastTwoDays_onlyThatKey() {
        val s = sample().statsFor("k1", 2)
        assertEquals(6.0, s.cost, 1e-9)
        assertEquals(60L, s.requests)
        assertEquals(2_000_000L, s.hitTokens)
        assertEquals(1_200_000L, s.missTokens)
        assertEquals(1_100_000L, s.outTokens)
        assertEquals(listOf(2.0, 4.0), s.trend)
    }

    @Test
    fun keyWithGaps_fillsMissingDaysWithZero() {
        val s = sample().statsFor("k2", 3)
        assertEquals(8.0, s.cost, 1e-9)
        assertEquals(500_000L, s.hitTokens)
        assertEquals(listOf(0.0, 0.0, 8.0), s.trend)
    }

    @Test
    fun unknownKey_isEmptyNotCrash() {
        val s = sample().statsFor("does-not-exist", 3)
        assertEquals(0.0, s.cost, 1e-9)
        assertEquals(0L, s.tokens)
        assertEquals(listOf(0.0, 0.0, 0.0), s.trend)
        assertEquals("--", s.hitRatePercent)
        assertEquals("--", s.missRatePercent)
    }

    @Test
    fun windowLargerThanData_isClamped() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 30)
        assertEquals(15.0, s.cost, 1e-9)
        assertEquals(3, s.trend.size)
    }

    @Test
    fun emptyData_isZero() {
        val s = DetailedUsageData().statsFor(DetailedUsageData.ALL_KEYS, 7)
        assertEquals(0.0, s.cost, 1e-9)
        assertEquals(0L, s.tokens)
        assertEquals(0, s.trend.size)
        assertEquals("--", s.hitRatePercent)
    }

    // ── Cache hit rate ─────────────────────────────────────────

    @Test
    fun hitRate_andMissRate_sumToExactly100() {
        val s = sample().statsFor("k1", 2)
        assertEquals("62.5", s.hitRatePercent)
        assertEquals("37.5", s.missRatePercent)
    }

    @Test
    fun zeroInput_ratesAreDashes() {
        val data = DetailedUsageData(
            days = listOf(d1),
            records = listOf(UsageRecord("k1", flash, d1, 1.0, 1, 0L, 0L, 100L))
        )
        val s = data.statsFor(DetailedUsageData.ALL_KEYS, 1)
        assertEquals(100L, s.tokens)
        assertEquals("--", s.hitRatePercent)
        assertEquals("--", s.missRatePercent)
    }

    // ── Estimated billing composition (per model) ──────────────

    @Test
    fun estimates_allKeys_fullWindow_arePerModel() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 3, flatPricing())
        // flash hit 3.5M @1.0 + pro hit 1M @2.0
        assertEquals(5.5, s.estimatedHitCost!!, 1e-6)
        // flash miss 1.2M @2.0 + pro miss 1M @4.0
        assertEquals(6.4, s.estimatedMissCost!!, 1e-6)
        // flash out 0.6M @4.0 + pro out 1M @8.0
        assertEquals(10.4, s.estimatedOutCost!!, 1e-6)
        // flash 3.5M×(2−1) + pro 1M×(4−2)
        assertEquals(5.5, s.estimatedCacheSaved!!, 1e-6)
    }

    @Test
    fun estimates_singleKey_lastTwoDays_arePerModel() {
        val s = sample().statsFor("k1", 2, flatPricing())
        assertEquals(3.0, s.estimatedHitCost!!, 1e-6)   // 1M@1.0 + 1M@2.0
        assertEquals(4.4, s.estimatedMissCost!!, 1e-6)  // 0.2M@2.0 + 1M@4.0
        assertEquals(8.4, s.estimatedOutCost!!, 1e-6)   // 0.1M@4.0 + 1M@8.0
        assertEquals(3.0, s.estimatedCacheSaved!!, 1e-6)
    }

    @Test
    fun estimates_nullPricing_areNull() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 3, null)
        assertNull(s.estimatedHitCost)
        assertNull(s.estimatedMissCost)
        assertNull(s.estimatedOutCost)
        assertNull(s.estimatedCacheSaved)
    }

    @Test
    fun estimates_unknownModel_tokensCountedButNotPriced() {
        val data = DetailedUsageData(
            days = listOf(d1),
            records = listOf(UsageRecord("k1", "deepseek-v9-mystery", d1, 1.0, 1, 1_000_000, 0L, 0L))
        )
        val s = data.statsFor(DetailedUsageData.ALL_KEYS, 1, flatPricing())
        assertEquals(1_000_000L, s.tokens)      // tokens still counted
        assertNull(s.estimatedHitCost)          // but never guessed
        assertNull(s.estimatedCacheSaved)
    }

    @Test
    fun estimates_missingPricingTable_areNull() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 3, DeepSeekPricingSnapshot())
        assertNull(s.estimatedHitCost)
        assertNull(s.estimatedCacheSaved)
    }

    @Test
    fun estimates_zeroUsage_isZeroNotNull() {
        val s = DetailedUsageData(days = listOf(d1)).statsFor(DetailedUsageData.ALL_KEYS, 1, flatPricing())
        assertEquals(0.0, s.estimatedHitCost!!, 1e-9)
        assertEquals(0.0, s.estimatedCacheSaved!!, 1e-9)
    }

    // ── Key fallback ───────────────────────────────────────────

    @Test
    fun resolveKey_keepsExisting_andFallsBackForDeleted() {
        val data = sample()
        assertEquals("k1", data.resolveKey("k1"))
        assertEquals(DetailedUsageData.ALL_KEYS, data.resolveKey("deleted-key"))
    }

    @Test
    fun keyLabel_usesRealName_withSafeFallback() {
        val data = sample()
        assertEquals("RanClaw", data.keyLabel("k1"))
        assertEquals("All Keys", data.keyLabel(DetailedUsageData.ALL_KEYS))
        assertEquals("All Keys", data.keyLabel("deleted-key"))
    }

    // ── Formatting ─────────────────────────────────────────────

    @Test
    fun formatTokens_matchesSpec() {
        assertEquals("999", DetailedUsageData.formatTokens(999L))
        assertEquals("1.23K", DetailedUsageData.formatTokens(1_234L))
        assertEquals("1.23M", DetailedUsageData.formatTokens(1_234_567L))
        assertEquals("255.5M", DetailedUsageData.formatTokens(255_545_105L))
        assertEquals("1.23B", DetailedUsageData.formatTokens(1_234_567_890L))
    }

    @Test
    fun formatRequests_groupsThousands() {
        assertEquals("1,512", DetailedUsageData.formatRequests(1_512L))
        assertEquals("0", DetailedUsageData.formatRequests(0L))
    }

    @Test
    fun formatMoney_twoDecimals() {
        assertEquals("¥64.47", DetailedUsageData.formatMoney(64.466))
        assertEquals("¥58.49", DetailedUsageData.formatMoney("58.49"))
    }

    @Test
    fun formatEstimate_alwaysMarkedAndNullSafe() {
        assertEquals("--", DetailedUsageData.formatEstimate(null))
        assertEquals("≈ ¥3.84", DetailedUsageData.formatEstimate(3.841))
        assertEquals("≈ ¥0.00", DetailedUsageData.formatEstimate(0.0))
    }

    // ── Serialization ──────────────────────────────────────────

    @Test
    fun serialize_roundTrip_preservesEverything() {
        val original = sample()
        assertEquals(original, DetailedUsageData.deserialize(original.serialize()))
    }

    @Test
    fun deserialize_rejectsMissingMalformedAndOldSchema() {
        assertNull(DetailedUsageData.deserialize(null))
        assertNull(DetailedUsageData.deserialize(""))
        assertNull(DetailedUsageData.deserialize("garbage"))
        // v1 blob (4 sections, no schema tag) must be rejected, not misread.
        assertNull(DetailedUsageData.deserialize("a\u0004b\u0004c\u0004d"))
    }

    @Test
    fun deserialize_errorState_roundTrips() {
        val err = DetailedUsageData(error = "登录已过期，请重新登录")
        assertEquals(err, DetailedUsageData.deserialize(err.serialize()))
    }
}
