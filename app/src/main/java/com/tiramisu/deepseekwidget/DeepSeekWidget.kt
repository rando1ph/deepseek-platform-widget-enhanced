package com.tiramisu.deepseekwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.work.*
import java.util.concurrent.TimeUnit

class DeepSeekWidget : AppWidgetProvider() {

    companion object {
        const val PREFS_NAME = "deepseek_widget_prefs"
        const val KEY_API_KEY = "***"
        const val ACTION_REFRESH = "com.tiramisu.deepseekwidget.ACTION_REFRESH"
        const val UPDATE_INTERVAL_MINUTES = 30L

        private const val DISPLAY_PREFS = "deepseek_widget_display"
        private const val KEY_CACHED = "cached_text"
        private const val KEY_HAS_CACHE = "has_cache"
        private const val KEY_CACHED_DATA = "cached_data"
        private const val KEY_MODEL_PRO = "model_pro"

        private fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(DISPLAY_PREFS, Context.MODE_PRIVATE)

        // ─── API Key ───────────────────────────────────────────

        fun getApiKey(context: Context): String? {
            return try {
                val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val p = EncryptedSharedPreferences.create(context, PREFS_NAME, mk,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
                p.getString(KEY_API_KEY, null)
            } catch (_: Exception) { null }
        }

        fun setApiKey(context: Context, apiKey: String) {
            val mk = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val p = EncryptedSharedPreferences.create(context, PREFS_NAME, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
            p.edit().putString(KEY_API_KEY, apiKey).apply()
        }

        // ─── Account auth ──────────────────────────────────────

        fun hasAccountCredentials(context: Context): Boolean =
            DeepSeekAccountManager(context).hasCredentials()

        fun getTokenFromAccount(context: Context): String? =
            DeepSeekAccountManager(context).getValidToken()

        fun getAuthToken(context: Context): String? =
            getTokenFromAccount(context) ?: getApiKey(context)

        // ─── Render ────────────────────────────────────────────

        fun updateWidgets(context: Context, data: WidgetDisplayData) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, DeepSeekWidget::class.java))

            val display = if (data.error != null) "⚠️ ${data.error}" else data.formattedBalance
            prefs(context).edit().putString(KEY_CACHED, display)
                .putString(KEY_CACHED_DATA, serialize(data)).putBoolean(KEY_HAS_CACHE, true).apply()

            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_layout)
                render(views, data, display, prefs(context).getBoolean(KEY_MODEL_PRO, false))

                // Click → refresh
                val pi = PendingIntent.getBroadcast(context, 0,
                    Intent(context, DeepSeekWidget::class.java).apply { action = ACTION_REFRESH },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                views.setOnClickPendingIntent(R.id.widget_container, pi)
                views.setOnClickPendingIntent(R.id.widget_summary_area, pi)
                views.setOnClickPendingIntent(R.id.widget_model_area, modelPendingIntent(context))

                mgr.updateAppWidget(id, views)
            }
        }

        private fun serialize(data: WidgetDisplayData): String = listOf(
            data.balance, data.todayCost, data.monthlyCost, data.updatedAt,
            data.flashData.totalTokens, data.flashData.cacheHitRate, data.flashData.cost,
            data.proData.totalTokens, data.proData.cacheHitRate, data.proData.cost,
            data.error ?: ""
        ).joinToString("|")

        private fun deserialize(raw: String?): WidgetDisplayData? {
            return try {
                val p = raw?.split("|") ?: return null
                if (p.size < 11) return null
                WidgetDisplayData(true, p[0], todayCost = p[1], monthlyCost = p[2], updatedAt = p[3].toLong(),
                    flashData = ModelData(p[4].toLong(), p[5], p[6]), proData = ModelData(p[7].toLong(), p[8], p[9]),
                    error = p[10].ifBlank { null })
            } catch (_: Exception) { null }
        }

        private fun render(views: RemoteViews, data: WidgetDisplayData, display: String, pro: Boolean) {
            views.setTextViewText(R.id.tv_balance, display)
            views.setTextViewText(R.id.tv_updated, if (data.updatedAt > 0) data.formattedUpdatedTime else "--:--")
            views.setTextViewText(R.id.tv_today_cost, "今日 ${data.formattedTodayCost}")
            views.setTextViewText(R.id.tv_month_cost, "本月 ${data.formattedMonthCost}")
            val model = if (pro) data.proData else data.flashData
            views.setTextViewText(R.id.tv_model, if (pro) "Pro ›" else "Flash ›")
            views.setTextViewText(R.id.tv_tokens, WidgetDisplayData.formatTokenCount(model.totalTokens))
            views.setTextViewText(R.id.tv_cache, if (model.cacheHitRate == "--") "--" else "${model.cacheHitRate}%")
            views.setTextViewText(R.id.tv_model_cost, "¥${model.cost}")
        }

        private fun modelPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 1, Intent(context, DeepSeekWidget::class.java).apply { action = ACTION_MODEL_TOGGLE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        private const val ACTION_MODEL_TOGGLE = "com.tiramisu.deepseekwidget.ACTION_MODEL_TOGGLE"

        fun triggerRefresh(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
            )
        }
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, appWidgetIds: IntArray) {
        schedulePeriodicUpdate(context)
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_layout)
            val cached = prefs(context).getString(KEY_CACHED, null)
            val cachedData = deserialize(prefs(context).getString(KEY_CACHED_DATA, null))
            if (cached != null && cachedData != null) {
                render(views, cachedData, cached, prefs(context).getBoolean(KEY_MODEL_PRO, false))
            } else {
                views.setTextViewText(R.id.tv_balance, "⟳ 刷新中...")
            }
            val pi = PendingIntent.getBroadcast(context, 0,
                Intent(context, DeepSeekWidget::class.java).apply { action = ACTION_REFRESH },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_container, pi)
            views.setOnClickPendingIntent(R.id.widget_summary_area, pi)
            views.setOnClickPendingIntent(R.id.widget_model_area, modelPendingIntent(context))
            mgr.updateAppWidget(id, views)
        }
        if (getAuthToken(context) != null) triggerRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_MODEL_TOGGLE) {
            val p = prefs(context)
            p.edit().putBoolean(KEY_MODEL_PRO, !p.getBoolean(KEY_MODEL_PRO, false)).apply()
            val data = deserialize(p.getString(KEY_CACHED_DATA, null))
            if (data != null) updateWidgets(context, data)
            return
        }
        if (intent.action != ACTION_REFRESH) return
        if (getAuthToken(context).isNullOrBlank()) return
        triggerRefresh(context)
    }

    override fun onEnabled(context: Context) { super.onEnabled(context); schedulePeriodicUpdate(context) }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WorkManager.getInstance(context).cancelUniqueWork(WIDGET_UPDATE_WORK_NAME)
    }

    private fun schedulePeriodicUpdate(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WIDGET_UPDATE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WidgetUpdateWorker>(UPDATE_INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10000L, TimeUnit.MILLISECONDS)
                .build()
        )
    }
}

const val WIDGET_UPDATE_WORK_NAME = "deepseek_widget_update"
