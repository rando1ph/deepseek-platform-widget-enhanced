package com.tiramisu.deepseekwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the pure aggregation / formatting logic that drives the Detailed Usage
 * widget. The joint Time × API-Key filter is the riskiest part, so it is exercised directly.
 */
class DetailedUsageDataTest {

    // day starts (ascending)
    private val d1 = 1_000L
    private val d2 = 2_000L
    private val d3 = 3_000L

    private fun sample(): DetailedUsageData = DetailedUsageData(
        isAvailable = true,
        balance = "58.49",
        totalCost = "491.50",
        hasTotalCost = true,
        keys = listOf(KeyInfo("k1", "RanClaw"), KeyInfo("k2", "OpenCode")),
        records = listOf(
            UsageRecord("k1", d1, 1.0, 10, 100),
            UsageRecord("k1", d2, 2.0, 20, 200),
            UsageRecord("k1", d3, 4.0, 40, 400),
            UsageRecord("k2", d3, 8.0, 80, 800)
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
        assertEquals(1500L, s.tokens)
        assertEquals(listOf(1.0, 2.0, 12.0), s.trend)
    }

    @Test
    fun allKeys_lastDay_onlyNewestBucket() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 1)
        assertEquals(12.0, s.cost, 1e-9)
        assertEquals(120L, s.requests)
        assertEquals(1200L, s.tokens)
        assertEquals(listOf(12.0), s.trend)
    }

    @Test
    fun singleKey_lastTwoDays_onlyThatKey() {
        val s = sample().statsFor("k1", 2)
        assertEquals(6.0, s.cost, 1e-9)        // 2.0 + 4.0
        assertEquals(60L, s.requests)
        assertEquals(600L, s.tokens)
        assertEquals(listOf(2.0, 4.0), s.trend)
    }

    @Test
    fun keyWithGaps_fillsMissingDaysWithZero() {
        val s = sample().statsFor("k2", 3)
        assertEquals(8.0, s.cost, 1e-9)
        assertEquals(listOf(0.0, 0.0, 8.0), s.trend)
    }

    @Test
    fun unknownKey_isEmptyNotCrash() {
        val s = sample().statsFor("does-not-exist", 3)
        assertEquals(0.0, s.cost, 1e-9)
        assertEquals(0L, s.requests)
        assertEquals(0L, s.tokens)
        assertEquals(listOf(0.0, 0.0, 0.0), s.trend)
    }

    @Test
    fun windowLargerThanData_isClampedNotCrash() {
        val s = sample().statsFor(DetailedUsageData.ALL_KEYS, 30)
        assertEquals(15.0, s.cost, 1e-9)
        assertEquals(3, s.trend.size)
    }

    @Test
    fun emptyData_isZero() {
        val s = DetailedUsageData().statsFor(DetailedUsageData.ALL_KEYS, 7)
        assertEquals(0.0, s.cost, 1e-9)
        assertEquals(0, s.trend.size)
    }

    // ── Key fallback ───────────────────────────────────────────

    @Test
    fun resolveKey_keepsExisting_andFallsBackForDeleted() {
        val data = sample()
        assertEquals("k1", data.resolveKey("k1"))
        assertEquals(DetailedUsageData.ALL_KEYS, data.resolveKey("deleted-key"))
        assertEquals(DetailedUsageData.ALL_KEYS, data.resolveKey(DetailedUsageData.ALL_KEYS))
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

    // ── Serialization ──────────────────────────────────────────

    @Test
    fun serialize_roundTrip_preservesEverything() {
        val original = sample()
        val restored = DetailedUsageData.deserialize(original.serialize())
        assertEquals(original, restored)
    }

    @Test
    fun deserialize_handlesMissingAndMalformed() {
        assertNull(DetailedUsageData.deserialize(null))
        assertNull(DetailedUsageData.deserialize(""))
        assertNull(DetailedUsageData.deserialize("garbage"))
    }

    @Test
    fun deserialize_errorState_roundTrips() {
        val err = DetailedUsageData(error = "登录已过期，请重新登录")
        val restored = DetailedUsageData.deserialize(err.serialize())
        assertEquals(err, restored)
    }
}
