package com.tiramisu.deepseekwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full Dashboard widget — the readability-first, page-filling companion to [DetailedUsageWidget].
 *
 * Shows exactly the same information (account block, Time/API-Key filters, official cost /
 * requests / tokens, CACHE / BILLING with ≈ estimates) at a much larger type scale, and
 * deliberately looser section spacing. No charts.
 *
 * Reuse, not duplication, of the data path:
 *  - same payload type [DetailedUsageData] (built by `DeepSeekApiClient.fetchDetailedUsage`)
 *  - pushed by the same [WidgetUpdateWorker] (one fetch serves both Detailed and Full)
 *  - same [DeepSeekPricing] table for the ≈ estimates
 * Only this widget's view scaffolding/state keys are its own, so the two existing widgets stay
 * untouched.
 */
class FullUsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val data = loadData(context)
        val pricing = loadPricing(context)
        for (id in ids) {
            val view = RemoteViews(context.packageName, R.layout.widget_full_layout)
            if (data != null) {
                render(context, view, id, data, "", pricing)
            } else {
                renderPlaceholder(context, view, id)
            }
            mgr.updateAppWidget(id, view)
        }
        if (DeepSeekWidget.getAuthToken(context) != null) triggerRefresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return
        when (intent.action) {
            ACTION_REFRESH -> {
                if (!DeepSeekWidget.getAuthToken(context).isNullOrBlank()) triggerRefresh(context)
            }
            ACTION_CYCLE_TIME -> {
                setRangeIndex(context, id, getRangeIndex(context, id) + 1)
                rerenderAll(context)
            }
            ACTION_CYCLE_KEY -> {
                cycleKey(context, id)
                rerenderAll(context)
            }
            ACTION_TOGGLE_TOTAL -> {
                setTotalVisible(context, id, !isTotalVisible(context, id))
                rerenderAll(context)
            }
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val editor = prefs(context).edit()
        for (id in appWidgetIds) {
            editor.remove(rangeKey(id)).remove(keyKey(id)).remove(visKey(id))
        }
        editor.apply()
    }

    // ─── Rendering ─────────────────────────────────────────────

    private fun render(
        context: Context,
        view: RemoteViews,
        id: Int,
        data: DetailedUsageData,
        status: String,
        pricing: DeepSeekPricingSnapshot
    ) {
        val ok = data.isAvailable && data.error == null

        view.setTextViewText(R.id.tv_f_title, "DeepSeek Usage")
        view.setTextViewText(
            R.id.tv_f_updated,
            if (data.updatedAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.updatedAt)) else "--:--"
        )
        val statusText = if (data.error != null) "⚠ ${data.error}" else status
        view.setTextViewText(R.id.tv_f_status, statusText)
        // Collapse the row entirely when there is nothing to say.
        view.setViewVisibility(R.id.tv_f_status, if (statusText.isEmpty()) View.GONE else View.VISIBLE)

        // Account-level block (filter-independent)
        view.setTextViewText(R.id.tv_f_balance, if (ok) DetailedUsageData.formatMoney(data.balance) else "--")

        val totalVisible = isTotalVisible(context, id)
        val totalText = when {
            !totalVisible -> "¥••••••"
            ok && data.hasTotalCost -> DetailedUsageData.formatMoney(data.totalCost)
            else -> "¥--"
        }
        view.setTextViewText(R.id.tv_f_total, totalText)
        view.setImageViewResource(
            R.id.iv_f_eye,
            if (totalVisible) R.drawable.ic_visibility_off_dark else R.drawable.ic_visibility_dark
        )

        // Filters (resolved against current data so a deleted key safely falls back)
        val range = RANGES[getRangeIndex(context, id)]
        val keyId = data.resolveKey(getKeyId(context, id))
        view.setTextViewText(R.id.tv_f_time, range.label)
        view.setTextViewText(R.id.tv_f_key, data.keyLabel(keyId))

        // Filtered metrics — Time + API-Key applied jointly (official values)
        val stats = data.statsFor(keyId, range.days, pricing)
        view.setTextViewText(R.id.tv_f_cost, if (ok) DetailedUsageData.formatMoney(stats.cost) else "--")
        view.setTextViewText(R.id.tv_f_requests, if (ok) DetailedUsageData.formatRequests(stats.requests) else "--")
        view.setTextViewText(R.id.tv_f_tokens, if (ok) DetailedUsageData.formatTokens(stats.tokens) else "--")

        // CACHE / BILLING — official token split; ≈ estimated cost composition
        view.setTextViewText(R.id.tv_f_hit_tok, tokenText(ok, stats.hitTokens))
        view.setTextViewText(R.id.tv_f_hit_rate, rateText(ok, stats.hitRatePercent))
        view.setTextViewText(R.id.tv_f_hit_cost, estimateText(ok, stats.estimatedHitCost))
        view.setTextViewText(R.id.tv_f_miss_tok, tokenText(ok, stats.missTokens))
        view.setTextViewText(R.id.tv_f_miss_rate, rateText(ok, stats.missRatePercent))
        view.setTextViewText(R.id.tv_f_miss_cost, estimateText(ok, stats.estimatedMissCost))
        view.setTextViewText(R.id.tv_f_out_tok, tokenText(ok, stats.outTokens))
        view.setTextViewText(R.id.tv_f_out_cost, estimateText(ok, stats.estimatedOutCost))
        view.setTextViewText(R.id.tv_f_saved, estimateText(ok, stats.estimatedCacheSaved))

        // Clicks
        view.setOnClickPendingIntent(R.id.tv_f_title, openPi(context, id))
        view.setOnClickPendingIntent(R.id.iv_f_refresh, broadcastPi(context, ACTION_REFRESH, id, SLOT_REFRESH))
        view.setOnClickPendingIntent(R.id.row_f_time, broadcastPi(context, ACTION_CYCLE_TIME, id, SLOT_TIME))
        view.setOnClickPendingIntent(R.id.row_f_key, broadcastPi(context, ACTION_CYCLE_KEY, id, SLOT_KEY))
        view.setOnClickPendingIntent(R.id.iv_f_eye, broadcastPi(context, ACTION_TOGGLE_TOTAL, id, SLOT_EYE))
    }

    private fun renderPlaceholder(context: Context, view: RemoteViews, id: Int) {
        view.setTextViewText(R.id.tv_f_title, "DeepSeek Usage")
        view.setTextViewText(R.id.tv_f_updated, "--:--")
        view.setTextViewText(R.id.tv_f_status, "⟳ 加载中…")
        view.setViewVisibility(R.id.tv_f_status, View.VISIBLE)
        view.setTextViewText(R.id.tv_f_balance, "¥--")
        view.setTextViewText(R.id.tv_f_total, "¥••••••")
        view.setImageViewResource(R.id.iv_f_eye, R.drawable.ic_visibility_dark)
        view.setTextViewText(R.id.tv_f_time, RANGES[getRangeIndex(context, id)].label)
        view.setTextViewText(R.id.tv_f_key, "All Keys")
        view.setTextViewText(R.id.tv_f_cost, "¥--")
        view.setTextViewText(R.id.tv_f_requests, "--")
        view.setTextViewText(R.id.tv_f_tokens, "--")
        view.setTextViewText(R.id.tv_f_hit_tok, "--")
        view.setTextViewText(R.id.tv_f_hit_rate, "--")
        view.setTextViewText(R.id.tv_f_hit_cost, "--")
        view.setTextViewText(R.id.tv_f_miss_tok, "--")
        view.setTextViewText(R.id.tv_f_miss_rate, "--")
        view.setTextViewText(R.id.tv_f_miss_cost, "--")
        view.setTextViewText(R.id.tv_f_out_tok, "--")
        view.setTextViewText(R.id.tv_f_out_cost, "--")
        view.setTextViewText(R.id.tv_f_saved, "--")
        view.setOnClickPendingIntent(R.id.tv_f_title, openPi(context, id))
        view.setOnClickPendingIntent(R.id.iv_f_refresh, broadcastPi(context, ACTION_REFRESH, id, SLOT_REFRESH))
        view.setOnClickPendingIntent(R.id.row_f_time, broadcastPi(context, ACTION_CYCLE_TIME, id, SLOT_TIME))
        view.setOnClickPendingIntent(R.id.row_f_key, broadcastPi(context, ACTION_CYCLE_KEY, id, SLOT_KEY))
        view.setOnClickPendingIntent(R.id.iv_f_eye, broadcastPi(context, ACTION_TOGGLE_TOTAL, id, SLOT_EYE))
    }

    private fun tokenText(ok: Boolean, value: Long): String =
        if (ok) DetailedUsageData.formatTokens(value) else "--"

    private fun rateText(ok: Boolean, value: String): String =
        if (!ok || value == "--") "--" else "$value%"

    private fun estimateText(ok: Boolean, value: Double?): String =
        if (ok) DetailedUsageData.formatEstimate(value) else "--"

    // ─── Update entry points ───────────────────────────────────

    private fun renderAll(context: Context, data: DetailedUsageData) {
        saveData(context, data)
        val pricing = loadPricing(context)
        val mgr = AppWidgetManager.getInstance(context)
        for (id in mgr.getAppWidgetIds(ComponentName(context, FullUsageWidget::class.java))) {
            val view = RemoteViews(context.packageName, R.layout.widget_full_layout)
            render(context, view, id, data, "", pricing)
            mgr.updateAppWidget(id, view)
        }
    }

    private fun rerenderAll(context: Context) {
        val data = loadData(context) ?: return
        val pricing = loadPricing(context)
        val mgr = AppWidgetManager.getInstance(context)
        for (id in mgr.getAppWidgetIds(ComponentName(context, FullUsageWidget::class.java))) {
            val view = RemoteViews(context.packageName, R.layout.widget_full_layout)
            render(context, view, id, data, "", pricing)
            mgr.updateAppWidget(id, view)
        }
    }

    private fun triggerRefresh(context: Context) {
        val data = loadData(context)
        if (data != null) {
            val pricing = loadPricing(context)
            val mgr = AppWidgetManager.getInstance(context)
            for (id in mgr.getAppWidgetIds(ComponentName(context, FullUsageWidget::class.java))) {
                val view = RemoteViews(context.packageName, R.layout.widget_full_layout)
                render(context, view, id, data, "刷新中…", pricing)
                mgr.updateAppWidget(id, view)
            }
        }
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    private fun loadPricing(context: Context): DeepSeekPricingSnapshot =
        try {
            DeepSeekPricing.loadCached(context)
        } catch (_: Exception) {
            DeepSeekPricing.DEFAULT
        }

    // ─── PendingIntents ────────────────────────────────────────

    private fun broadcastPi(context: Context, action: String, id: Int, slot: Int): PendingIntent {
        val intent = Intent(context, FullUsageWidget::class.java).apply {
            this.action = action
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        }
        return PendingIntent.getBroadcast(
            context, id * 10 + slot, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openPi(context: Context, id: Int): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(USAGE_URL))
        return PendingIntent.getActivity(
            context, id * 10 + SLOT_OPEN, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ─── Per-widget state (independent from the other widgets) ─

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(DeepSeekWidget.DISPLAY_PREFS, Context.MODE_PRIVATE)

    private fun rangeKey(id: Int) = "full_range_$id"
    private fun keyKey(id: Int) = "full_key_$id"
    private fun visKey(id: Int) = "full_vis_$id"

    private fun getRangeIndex(c: Context, id: Int): Int =
        prefs(c).getInt(rangeKey(id), DEFAULT_RANGE_INDEX).coerceIn(0, RANGES.size - 1)

    private fun setRangeIndex(c: Context, id: Int, v: Int) =
        prefs(c).edit().putInt(rangeKey(id), ((v % RANGES.size) + RANGES.size) % RANGES.size).apply()

    private fun getKeyId(c: Context, id: Int): String =
        prefs(c).getString(keyKey(id), DetailedUsageData.ALL_KEYS) ?: DetailedUsageData.ALL_KEYS

    private fun setKeyId(c: Context, id: Int, v: String) =
        prefs(c).edit().putString(keyKey(id), v).apply()

    /** Default hidden — the safe default is always "hidden". */
    private fun isTotalVisible(c: Context, id: Int): Boolean = prefs(c).getBoolean(visKey(id), false)

    private fun setTotalVisible(c: Context, id: Int, v: Boolean) =
        prefs(c).edit().putBoolean(visKey(id), v).apply()

    private fun cycleKey(c: Context, id: Int) {
        val data = loadData(c)
        val options = ArrayList<String>()
        options.add(DetailedUsageData.ALL_KEYS)
        data?.keys?.forEach { options.add(it.id) }
        val current = options.indexOf(getKeyId(c, id)).let { if (it < 0) 0 else it }
        setKeyId(c, id, options[(current + 1) % options.size])
    }

    // ─── Persistence ───────────────────────────────────────────

    private fun loadData(c: Context): DetailedUsageData? =
        DetailedUsageData.deserialize(prefs(c).getString(KEY_FULL_DATA, null))

    private fun saveData(c: Context, d: DetailedUsageData) =
        prefs(c).edit().putString(KEY_FULL_DATA, d.serialize()).apply()

    // ─── Constants ─────────────────────────────────────────────

    private data class TimeRangeOption(val label: String, val days: Int)

    companion object {
        /** Called by [WidgetUpdateWorker] to push freshly fetched data to every instance. */
        fun updateWidgets(context: Context, data: DetailedUsageData) {
            FullUsageWidget().renderAll(context, data)
        }

        private const val ACTION_REFRESH = "com.tiramisu.deepseekwidget.FULL_REFRESH"
        private const val ACTION_CYCLE_TIME = "com.tiramisu.deepseekwidget.FULL_CYCLE_TIME"
        private const val ACTION_CYCLE_KEY = "com.tiramisu.deepseekwidget.FULL_CYCLE_KEY"
        private const val ACTION_TOGGLE_TOTAL = "com.tiramisu.deepseekwidget.FULL_TOGGLE_TOTAL"

        private const val SLOT_REFRESH = 0
        private const val SLOT_TIME = 1
        private const val SLOT_KEY = 2
        private const val SLOT_EYE = 3
        private const val SLOT_OPEN = 4

        private const val KEY_FULL_DATA = "full_data"
        private const val USAGE_URL = "https://platform.deepseek.com/usage"

        /** Today → 7d → 30d (cycle order); 7d is the default. */
        private val RANGES = listOf(
            TimeRangeOption("Today", 1),
            TimeRangeOption("Last 7 days", 7),
            TimeRangeOption("Last 30 days", 30)
        )
        private const val DEFAULT_RANGE_INDEX = 1
    }
}
