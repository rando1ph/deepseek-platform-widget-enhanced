package com.tiramisu.deepseekwidget

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data models for DeepSeek API responses.
 */

data class ModelData(
    val totalTokens: Long = 0,
    val cacheHitRate: String = "--",
    val cost: String = "0.00",
    val requests: Long = 0
)

data class WidgetDisplayData(
    val isAvailable: Boolean = false,
    val balance: String = "0.00",
    val totalAvailableTokens: Long = 0,
    val todayCost: String = "0.00",
    val monthlyCost: String = "0.00",
    val monthlyTokens: Long = 0,
    val flashData: ModelData = ModelData(),
    val proData: ModelData = ModelData(),
    val updatedAt: Long = 0L,
    val error: String? = null
) {
    val formattedBalance: String get() = "¥$balance"

    val formattedTodayCost: String
        get() = if (todayCost == "0.00") "¥0" else "¥$todayCost"

    val formattedMonthCost: String
        get() = if (monthlyCost == "0.00") "¥0" else "¥$monthlyCost"

    val formattedMonthTokens: String
        get() = formatTokenCount(monthlyTokens)

    val formattedAvailableTokens: String
        get() = formatTokenCount(totalAvailableTokens)

    val formattedUpdatedTime: String
        get() {
            val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
            return sdf.format(Date(updatedAt))
        }

    companion object {
        fun formatTokenCount(count: Long): String {
            return when {
                count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
                count >= 1_000 -> "%.1fK".format(count / 1_000.0)
                else -> count.toString()
            }
        }
    }
}
