package com.tiramisu.deepseekwidget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager worker for periodic widget updates (every 30 min).
 *
 * Uses account Bearer Token (from email+password login) in preference to
 * legacy API Key. If neither is configured, skips the update silently.
 *
 * Updates both the compact [DeepSeekWidget] and the [DetailedUsageWidget]. Each is only
 * fetched when at least one of its instances is on the home screen, so we never add usage
 * requests that nothing consumes.
 */
class WidgetUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        // Try account token first, fall back to legacy API Key
        val token = DeepSeekWidget.getTokenFromAccount(applicationContext)
            ?: DeepSeekWidget.getApiKey(applicationContext)

        if (token.isNullOrBlank()) {
            return Result.success() // No auth configured yet
        }

        val context = applicationContext
        val manager = AppWidgetManager.getInstance(context)
        val hasCompact = manager.getAppWidgetIds(
            ComponentName(context, DeepSeekWidget::class.java)
        ).isNotEmpty()
        val hasDetailed = manager.getAppWidgetIds(
            ComponentName(context, DetailedUsageWidget::class.java)
        ).isNotEmpty()

        if (!hasCompact && !hasDetailed) {
            return Result.success()
        }

        return try {
            if (hasDetailed) {
                // Refresh the pricing table (TTL 6h) so the widget's ≈ estimates follow official
                // price changes. Never throws — falls back to the cached/default table.
                DeepSeekPricing.ensureFresh(context)
            }
            val client = DeepSeekApiClient(token, DeepSeekWidget.getUsageTimeZone(context))
            if (hasCompact) {
                DeepSeekWidget.updateWidgets(context, client.fetchAll())
            }
            if (hasDetailed) {
                DetailedUsageWidget.updateWidgets(context, client.fetchDetailedUsage())
            }
            Result.success()
        } catch (e: Exception) {
            if (hasCompact) {
                DeepSeekWidget.updateWidgets(context, WidgetDisplayData(error = e.message))
            }
            if (hasDetailed) {
                DetailedUsageWidget.updateWidgets(context, DetailedUsageData(error = e.message))
            }
            Result.retry()
        }
    }
}
