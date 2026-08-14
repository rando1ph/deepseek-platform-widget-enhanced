package com.tiramisu.deepseekwidget

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * WorkManager worker for periodic widget updates (every 30 min).
 *
 * Uses account Bearer Token (from email+password login) in preference to
 * legacy API Key. If neither is configured, skips the update silently.
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

        return try {
            // 先确保官方定价缓存新鲜（失败静默，不影响主流程）
            DeepSeekPricing.ensureFresh(applicationContext)
            val client = DeepSeekApiClient(token, DeepSeekWidget.getUsageTimeZone(applicationContext))
            val data = client.fetchAll()
            DeepSeekWidget.updateWidgets(applicationContext, data)
            Result.success()
        } catch (e: Exception) {
            DeepSeekWidget.updateWidgets(
                applicationContext,
                WidgetDisplayData(error = e.message)
            )
            Result.retry()
        }
    }
}
