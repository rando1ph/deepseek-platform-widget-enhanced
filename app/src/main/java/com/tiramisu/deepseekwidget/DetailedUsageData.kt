package com.tiramisu.deepseekwidget

import java.util.Locale

/**
 * Data models + pure aggregation logic for the Detailed Usage widget.
 *
 * Unlike the legacy ModelData pipeline (which folded everything down to "per model"), the
 * Detailed widget keeps the API-Key × Model × day dimensions all the way to the display layer,
 * so the Time filter and the API-Key filter are pure render-time slices of a single payload.
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

/**
 * Per (API key × model × day) usage — the dimensions the legacy pipeline discarded.
 *
 * Token fields come from the amount endpoint; [cost] is the OFFICIAL cost endpoint value for
 * this key×model×day slice (never a locally estimated figure).
 */
data class UsageRecord(
    val keyId: String,
    /** API model name, e.g. "deepseek-v4-flash" (kept so pricing can be applied per model). */
    val model: String,
    /** Epoch seconds of local midnight (usage timezone) for this day. */
    val dayStart: Long,
    /** Official CNY cost for this key×model×day. */
    val cost: Double,
    val requests: Long,
    val hitTokens: Long,
    val missTokens: Long,
    val outTokens: Long
) {
    /** Input = hit + miss; Total = hit + miss + output (REQUEST excluded). */
    val tokens: Long get() = hitTokens + missTokens + outTokens
}

/**
 * Result of applying the per-widget Time + API-Key filters over the fetched payload.
 *
 * [cost]/[requests]/[*-Tokens] are OFFICIAL aggregates. Everything named `estimated*` is a
 * local figure (see [BillingEstimate]) and is null when it cannot be computed.
 */
data class FilteredStats(
    val cost: Double,
    val requests: Long,
    val hitTokens: Long,
    val missTokens: Long,
    val outTokens: Long,
    /** Daily official cost across the selected window (ascending), for the trend chart. */
    val trend: List<Double>,
    /** Day-starts matching [trend] one-to-one, for date labels. */
    val trendDays: List<Long>,
    /** "93.2" or "--" when there is no input. */
    val hitRatePercent: String,
    /** miss/(hit+miss), derived so the pair always sums to exactly 100.0. */
    val missRatePercent: String,
    val estimatedHitCost: Double?,
    val estimatedMissCost: Double?,
    val estimatedOutCost: Double?,
    val estimatedCacheSaved: Double?
) {
    val tokens: Long get() = hitTokens + missTokens + outTokens
    val inputTokens: Long get() = hitTokens + missTokens
}

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
     *
     * When [pricing] is supplied, estimated billing composition is computed per model
     * (unknown models are skipped, never guessed). When null, estimates are null (→ "--").
     */
    fun statsFor(
        keyId: String,
        windowDays: Int,
        pricing: DeepSeekPricingSnapshot? = null
    ): FilteredStats {
        val window = days.takeLast(windowDays.coerceAtLeast(0))
        val windowSet = HashSet(window)

        var cost = 0.0
        var requests = 0L
        var hit = 0L
        var miss = 0L
        var out = 0L
        val trendMap = HashMap<Long, Double>()

        var estHit = 0.0
        var estMiss = 0.0
        var estOut = 0.0
        var saved = 0.0
        var pricedTokens = 0L

        for (r in records) {
            if (r.dayStart !in windowSet) continue
            if (keyId != ALL_KEYS && r.keyId != keyId) continue

            cost += r.cost
            requests += r.requests
            hit += r.hitTokens
            miss += r.missTokens
            out += r.outTokens
            trendMap[r.dayStart] = (trendMap[r.dayStart] ?: 0.0) + r.cost

            if (pricing != null && r.tokens > 0) {
                val price = BillingEstimate.priceForRecord(r.model, r.dayStart, pricing)
                if (price != null) {
                    estHit += BillingEstimate.estimatedCost(r.hitTokens, price.cacheHit)
                    estMiss += BillingEstimate.estimatedCost(r.missTokens, price.cacheMiss)
                    estOut += BillingEstimate.estimatedCost(r.outTokens, price.output)
                    // Cache saved = what the hit tokens would have cost at the miss rate, minus
                    // what they actually cost at the hit rate.
                    saved += BillingEstimate.estimatedCost(r.hitTokens, price.cacheMiss - price.cacheHit)
                    pricedTokens += r.tokens
                }
            }
        }

        val input = hit + miss
        val hitRate: String
        val missRate: String
        if (input > 0) {
            hitRate = "%.1f".format(Locale.US, hit * 100.0 / input)
            // Derive the complement from the ROUNDED hit rate so the pair always reads as 100.0
            // (independent rounding can otherwise show 93.2% + 6.9%).
            missRate = "%.1f".format(Locale.US, 100.0 - hitRate.toDouble())
        } else {
            hitRate = "--"
            missRate = "--"
        }

        val totalTokens = hit + miss + out
        // Zero usage → a genuine 0 estimate; some usage but nothing priceable → null ("--").
        val estimatesAvailable = pricing != null && (totalTokens == 0L || pricedTokens > 0L)

        return FilteredStats(
            cost = cost,
            requests = requests,
            hitTokens = hit,
            missTokens = miss,
            outTokens = out,
            trend = window.map { trendMap[it] ?: 0.0 },
            trendDays = window,
            hitRatePercent = hitRate,
            missRatePercent = missRate,
            estimatedHitCost = if (estimatesAvailable) estHit else null,
            estimatedMissCost = if (estimatesAvailable) estMiss else null,
            estimatedOutCost = if (estimatesAvailable) estOut else null,
            estimatedCacheSaved = if (estimatesAvailable) saved else null
        )
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
            listOf(
                it.keyId, it.model, it.dayStart.toString(), it.cost.toString(),
                it.requests.toString(), it.hitTokens.toString(), it.missTokens.toString(), it.outTokens.toString()
            ).joinToString(F_SEP)
        }
        return listOf(SCHEMA, meta, keysSection, daysSection, recordsSection).joinToString(S_SEP)
    }

    companion object {
        /** Sentinel id for the "All Keys" filter option. */
        const val ALL_KEYS = "__all__"

        /** Bumped when the record layout changes; older blobs are rejected on read. */
        private const val SCHEMA = "v2"

        private const val S_SEP = "\u0004"
        private const val F_SEP = "\u0002"
        private const val G_SEP = "\u0003"
        private const val REC_SEP = "\u0001"

        /** Returns null when the blob is missing / malformed / from an incompatible version. */
        fun deserialize(raw: String?): DetailedUsageData? {
            if (raw.isNullOrBlank()) return null
            return try {
                val sections = raw.split(S_SEP)
                if (sections.size < 5 || sections[0] != SCHEMA) return null

                val meta = sections[1].split(F_SEP)
                if (meta.size < 6) return null
                val isAvailable = meta[0].toBoolean()
                val balance = meta[1]
                val totalCost = meta[2]
                val hasTotalCost = meta[3].toBoolean()
                val updatedAt = meta[4].toLongOrNull() ?: 0L
                val error = meta[5].ifBlank { null }

                val keys = if (sections[2].isBlank()) emptyList() else sections[2].split(G_SEP).mapNotNull { entry ->
                    val p = entry.split(F_SEP)
                    if (p.size < 2) null else KeyInfo(p[0], p[1])
                }
                val days = if (sections[3].isBlank()) emptyList()
                else sections[3].split(G_SEP).mapNotNull { it.toLongOrNull() }
                val records = if (sections[4].isBlank()) emptyList() else sections[4].split(REC_SEP).mapNotNull { entry ->
                    val p = entry.split(F_SEP)
                    if (p.size < 8) null else UsageRecord(
                        keyId = p[0],
                        model = p[1],
                        dayStart = p[2].toLongOrNull() ?: return@mapNotNull null,
                        cost = p[3].toDoubleOrNull() ?: 0.0,
                        requests = p[4].toLongOrNull() ?: 0L,
                        hitTokens = p[5].toLongOrNull() ?: 0L,
                        missTokens = p[6].toLongOrNull() ?: 0L,
                        outTokens = p[7].toLongOrNull() ?: 0L
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

        /**
         * Estimated money, always prefixed with "≈" so it can never be mistaken for an official
         * figure. null → "--" (cannot be computed), never "¥0.00" unless it really is zero.
         */
        fun formatEstimate(amount: Double?): String =
            if (amount == null) "--" else "≈ ¥%.2f".format(Locale.US, amount)
    }
}
