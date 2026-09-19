package com.tiramisu.deepseekwidget

import java.util.Locale

/**
 * Data models + pure aggregation logic for the Detailed Usage widget.
 *
 * Unlike the legacy ModelData pipeline (which folded everything down to "per model"), the
 * Detailed widget keeps the API-Key × day dimension all the way to the display layer, so the
 * Time filter and the API-Key filter are pure render-time slices of a single fetched payload.
 *
 * Everything in this file is deliberately free of Android dependencies so it can be exercised
 * by JVM unit tests (see app/src/test).
 */

/** One API key as returned by the `by_api_key` usage endpoints. */
data class KeyInfo(
    /** Stable identity (tracking_id, falling back to sensitive_id / name). */
    val id: String,
    /** Human-readable, user-assigned key name (e.g. "RanClaw"). */
    val name: String
)

/** Per (API key, day) aggregated usage — the dimension the legacy pipeline discarded. */
data class UsageRecord(
    val keyId: String,
    /** Epoch seconds of local midnight (usage timezone) for this day. */
    val dayStart: Long,
    /** CNY. */
    val cost: Double,
    val requests: Long,
    /** cache-hit + cache-miss + response tokens (REQUEST excluded). */
    val tokens: Long
)

/** Result of applying the per-widget Time + API-Key filters over the fetched payload. */
data class FilteredStats(
    val cost: Double,
    val requests: Long,
    val tokens: Long,
    /** Daily cost across the selected window (ascending), for the trend chart. */
    val trend: List<Double>
)

/**
 * Full usage payload for the Detailed Usage widget.
 *
 * Always fetched over the widest supported window (30 days, day-aligned in the usage
 * timezone) with ALL API keys retained. `balance` / `totalCost` are account-level scalars
 * from `get_user_summary` and therefore independent of any Time / API-Key filter.
 */
data class DetailedUsageData(
    val isAvailable: Boolean = false,
    val balance: String = "0.00",
    val totalCost: String = "0.00",
    /** Whether the account-level total cost could be read from the API. */
    val hasTotalCost: Boolean = false,
    /** All keys present in the window, sorted by name. */
    val keys: List<KeyInfo> = emptyList(),
    val records: List<UsageRecord> = emptyList(),
    /** Ordered day-starts (ascending) covering the fetched window. */
    val days: List<Long> = emptyList(),
    val updatedAt: Long = 0L,
    val error: String? = null
) {

    /**
     * Apply BOTH filters at once: keep records inside the last [windowDays] days AND for the
     * selected key ([ALL_KEYS] means every key). Missing days simply contribute zero — no
     * bucket/model/key is required to exist.
     */
    fun statsFor(keyId: String, windowDays: Int): FilteredStats {
        val window = days.takeLast(windowDays.coerceAtLeast(0))
        val windowSet = HashSet(window)
        var cost = 0.0
        var requests = 0L
        var tokens = 0L
        val trendMap = HashMap<Long, Double>()
        for (r in records) {
            if (r.dayStart !in windowSet) continue
            if (keyId != ALL_KEYS && r.keyId != keyId) continue
            cost += r.cost
            requests += r.requests
            tokens += r.tokens
            trendMap[r.dayStart] = (trendMap[r.dayStart] ?: 0.0) + r.cost
        }
        return FilteredStats(cost, requests, tokens, window.map { trendMap[it] ?: 0.0 })
    }

    /** Safe fallback: a key that no longer exists (e.g. deleted) resolves to "All Keys". */
    fun resolveKey(stored: String): String =
        if (stored == ALL_KEYS || keys.any { it.id == stored }) stored else ALL_KEYS

    /** Display name for a filter value. */
    fun keyLabel(keyId: String): String =
        if (keyId == ALL_KEYS) "All Keys"
        else keys.firstOrNull { it.id == keyId }?.name ?: "All Keys"

    // ─── Persistence (control-char separated; no external deps) ───

    fun serialize(): String {
        val meta = listOf(
            isAvailable.toString(), balance, totalCost, hasTotalCost.toString(),
            updatedAt.toString(), error ?: ""
        ).joinToString(F_SEP)
        val keysSection = keys.joinToString(G_SEP) { it.id + F_SEP + it.name }
        val daysSection = days.joinToString(G_SEP)
        val recordsSection = records.joinToString(REC_SEP) {
            listOf(it.keyId, it.dayStart.toString(), it.cost.toString(), it.requests.toString(), it.tokens.toString())
                .joinToString(F_SEP)
        }
        return listOf(meta, keysSection, daysSection, recordsSection).joinToString(S_SEP)
    }

    companion object {
        /** Sentinel id for the "All Keys" filter option. */
        const val ALL_KEYS = "__all__"

        private const val S_SEP = "\u0004"
        private const val F_SEP = "\u0002"
        private const val G_SEP = "\u0003"
        private const val REC_SEP = "\u0001"

        /** Returns null when the blob is missing / malformed / from an incompatible version. */
        fun deserialize(raw: String?): DetailedUsageData? {
            if (raw.isNullOrBlank()) return null
            return try {
                val sections = raw.split(S_SEP)
                if (sections.size < 4) return null

                val meta = sections[0].split(F_SEP)
                if (meta.size < 6) return null
                val isAvailable = meta[0].toBoolean()
                val balance = meta[1]
                val totalCost = meta[2]
                val hasTotalCost = meta[3].toBoolean()
                val updatedAt = meta[4].toLongOrNull() ?: 0L
                val error = meta[5].ifBlank { null }

                val keys = if (sections[1].isBlank()) emptyList() else sections[1].split(G_SEP).mapNotNull { entry ->
                    val p = entry.split(F_SEP)
                    if (p.size < 2) null else KeyInfo(p[0], p[1])
                }
                val days = if (sections[2].isBlank()) emptyList()
                else sections[2].split(G_SEP).mapNotNull { it.toLongOrNull() }
                val records = if (sections[3].isBlank()) emptyList() else sections[3].split(REC_SEP).mapNotNull { entry ->
                    val p = entry.split(F_SEP)
                    if (p.size < 5) null else UsageRecord(
                        keyId = p[0],
                        dayStart = p[1].toLongOrNull() ?: return@mapNotNull null,
                        cost = p[2].toDoubleOrNull() ?: 0.0,
                        requests = p[3].toLongOrNull() ?: 0L,
                        tokens = p[4].toLongOrNull() ?: 0L
                    )
                }
                DetailedUsageData(isAvailable, balance, totalCost, hasTotalCost, keys, records, days, updatedAt, error)
            } catch (_: Exception) {
                null
            }
        }

        /**
         * 1234 → "1.23K", 1234567 → "1.23M", 255545105 → "255.5M", 1234567890 → "1.23B".
         * Uses 2 decimals below 100 units and 1 decimal at/above, keeping the string compact.
         */
        fun formatTokens(count: Long): String = when {
            count >= 1_000_000_000L -> unit(count / 1_000_000_000.0, "B")
            count >= 1_000_000L -> unit(count / 1_000_000.0, "M")
            count >= 1_000L -> unit(count / 1_000.0, "K")
            else -> count.toString()
        }

        private fun unit(value: Double, suffix: String): String =
            if (value >= 100.0) "%.1f%s".format(Locale.US, value, suffix)
            else "%.2f%s".format(Locale.US, value, suffix)

        /** Requests keep their exact value when they fit: 1512 → "1,512". */
        fun formatRequests(count: Long): String = String.format(Locale.US, "%,d", count)

        /** Money from an already-formatted string ("58.49") → "¥58.49". */
        fun formatMoney(amount: String): String = "¥$amount"

        /** Money from a double → "¥58.49". */
        fun formatMoney(amount: Double): String = "¥%.2f".format(Locale.US, amount)
    }
}
