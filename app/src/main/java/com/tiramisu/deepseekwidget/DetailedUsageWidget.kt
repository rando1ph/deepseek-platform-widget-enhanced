package com.tiramisu.deepseekwidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.net.Uri
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detailed Usage widget — a denser, dark-only companion to the compact DeepSeekWidget.
 *
 * Information architecture mirrors the official DeepSeek Platform Usage page:
 *   BALANCE / TOTAL COST (account-level, eyes-toggle on Total Cost)
 *   TIME + API KEY (per-widget filters)
 *   COST / REQUESTS / TOKENS (filtered)
 *   COST TREND (daily bar chart, filtered)
 *
 * The whole payload is fetched once over a 30-day window with all API keys (see
 * [DeepSeekApiClient.fetchDetailedUsage]); every filter is then a render-time slice, so
 * cycling a filter never hits the network.
 */
class DetailedUsageWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        val data = loadData(context)
        for (id in ids) {
            val view = RemoteViews(context.packageName, R.layout.widget_detailed_layout)
            if (data != null) {
                render(context, view, id, data, "")
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

    private fun render(context: Context, view: RemoteViews, id: Int, data: DetailedUsageData, status: String) {
        val ok = data.isAvailable && data.error == null

        // Header
        view.setTextViewText(R.id.tv_d_title, "DeepSeek Usage")
        view.setTextViewText(
            R.id.tv_d_updated,
            if (data.updatedAt > 0) SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(data.updatedAt)) else "--:--"
        )
        view.setTextViewText(R.id.tv_d_status, if (data.error != null) "⚠ ${data.error}" else status)

        // Account-level block
        view.setTextViewText(R.id.tv_d_balance, if (ok) DetailedUsageData.formatMoney(data.balance) else "--")

        val totalVisible = isTotalVisible(context, id)
        val totalText = when {
            !totalVisible -> "¥••••••"
            ok && data.hasTotalCost -> DetailedUsageData.formatMoney(data.totalCost)
            else -> "¥--"
        }
        view.setTextViewText(R.id.tv_d_total, totalText)
        view.setImageViewResource(
            R.id.iv_d_eye,
            if (totalVisible) R.drawable.ic_visibility_off_dark else R.drawable.ic_visibility_dark
        )

        // Filters (resolved against current data so a deleted key safely falls back)
        val range = RANGES[getRangeIndex(context, id)]
        val keyId = data.resolveKey(getKeyId(context, id))
        view.setTextViewText(R.id.tv_d_time, range.label)
        view.setTextViewText(R.id.tv_d_key, data.keyLabel(keyId))

        // Filtered metrics (Time + API-Key applied jointly)
        val stats = data.statsFor(keyId, range.days)
        view.setTextViewText(R.id.tv_d_cost, if (ok) DetailedUsageData.formatMoney(stats.cost) else "--")
        view.setTextViewText(R.id.tv_d_requests, if (ok) DetailedUsageData.formatRequests(stats.requests) else "--")
        view.setTextViewText(R.id.tv_d_tokens, if (ok) DetailedUsageData.formatTokens(stats.tokens) else "--")
        view.setImageViewBitmap(R.id.iv_d_trend, buildTrendBitmap(if (ok) stats.trend else emptyList()))

        // Clicks
        view.setOnClickPendingIntent(R.id.tv_d_title, openPi(context, id))
        view.setOnClickPendingIntent(R.id.iv_d_refresh, broadcastPi(context, ACTION_REFRESH, id, SLOT_REFRESH))
        view.setOnClickPendingIntent(R.id.row_d_time, broadcastPi(context, ACTION_CYCLE_TIME, id, SLOT_TIME))
        view.setOnClickPendingIntent(R.id.row_d_key, broadcastPi(context, ACTION_CYCLE_KEY, id, SLOT_KEY))
        view.setOnClickPendingIntent(R.id.iv_d_eye, broadcastPi(context, ACTION_TOGGLE_TOTAL, id, SLOT_EYE))
    }

    private fun renderPlaceholder(context: Context, view: RemoteViews, id: Int) {
        view.setTextViewText(R.id.tv_d_title, "DeepSeek Usage")
        view.setTextViewText(R.id.tv_d_updated, "--:--")
        view.setTextViewText(R.id.tv_d_status, "⟳ 加载中…")
        view.setTextViewText(R.id.tv_d_balance, "¥--")
        view.setTextViewText(R.id.tv_d_total, "¥••••••")
        view.setImageViewResource(R.id.iv_d_eye, R.drawable.ic_visibility_dark)
        view.setTextViewText(R.id.tv_d_time, RANGES[getRangeIndex(context, id)].label)
        view.setTextViewText(R.id.tv_d_key, "All Keys")
        view.setTextViewText(R.id.tv_d_cost, "¥--")
        view.setTextViewText(R.id.tv_d_requests, "--")
        view.setTextViewText(R.id.tv_d_tokens, "--")
        view.setImageViewBitmap(R.id.iv_d_trend, buildTrendBitmap(emptyList()))
        view.setOnClickPendingIntent(R.id.tv_d_title, openPi(context, id))
        view.setOnClickPendingIntent(R.id.iv_d_refresh, broadcastPi(context, ACTION_REFRESH, id, SLOT_REFRESH))
        view.setOnClickPendingIntent(R.id.row_d_time, broadcastPi(context, ACTION_CYCLE_TIME, id, SLOT_TIME))
        view.setOnClickPendingIntent(R.id.row_d_key, broadcastPi(context, ACTION_CYCLE_KEY, id, SLOT_KEY))
        view.setOnClickPendingIntent(R.id.iv_d_eye, broadcastPi(context, ACTION_TOGGLE_TOTAL, id, SLOT_EYE))
    }

    // ─── Trend bitmap ──────────────────────────────────────────

    private fun buildTrendBitmap(values: List<Double>): Bitmap {
        val width = 600
        val height = 200
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        if (values.isEmpty()) return bmp

        val max = values.maxOrNull() ?: 0.0
        val n = values.size
        val slot = width.toFloat() / n
        val gap = (slot * 0.30f).coerceAtLeast(1.5f)
        val barW = (slot - gap).coerceAtLeast(1f)
        val baseline = height.toFloat()
        val radius = (barW * 0.35f).coerceAtMost(6f)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val accent = 0xFF4D6BFE.toInt()
        val dim = 0xFF33406E.toInt()
        for (i in 0 until n) {
            val v = values[i]
            val ratio = if (max > 0.0) (v / max).toFloat().coerceIn(0f, 1f) else 0f
            val barH = if (v > 0.0) (ratio * (height - 10f)).coerceAtLeast(8f) else 4f
            val left = i * slot + gap / 2f
            paint.color = if (v > 0.0) accent else dim
            canvas.drawRoundRect(RectF(left, baseline - barH, left + barW, baseline), radius, radius, paint)
        }
        return bmp
    }

    // ─── Update entry points ───────────────────────────────────

    private fun renderAll(context: Context, data: DetailedUsageData) {
        saveData(context, data)
        val mgr = AppWidgetManager.getInstance(context)
        for (id in mgr.getAppWidgetIds(ComponentName(context, DetailedUsageWidget::class.java))) {
            val view = RemoteViews(context.packageName, R.layout.widget_detailed_layout)
            render(context, view, id, data, "")
            mgr.updateAppWidget(id, view)
        }
    }

    private fun rerenderAll(context: Context) {
        val data = loadData(context) ?: return
        val mgr = AppWidgetManager.getInstance(context)
        for (id in mgr.getAppWidgetIds(ComponentName(context, DetailedUsageWidget::class.java))) {
            val view = RemoteViews(context.packageName, R.layout.widget_detailed_layout)
            render(context, view, id, data, "")
            mgr.updateAppWidget(id, view)
        }
    }

    private fun triggerRefresh(context: Context) {
        val data = loadData(context)
        if (data != null) {
            val mgr = AppWidgetManager.getInstance(context)
            for (id in mgr.getAppWidgetIds(ComponentName(context, DetailedUsageWidget::class.java))) {
                val view = RemoteViews(context.packageName, R.layout.widget_detailed_layout)
                render(context, view, id, data, "刷新中…")
                mgr.updateAppWidget(id, view)
            }
        }
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<WidgetUpdateWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        )
    }

    // ─── PendingIntents ────────────────────────────────────────

    private fun broadcastPi(context: Context, action: String, id: Int, slot: Int): PendingIntent {
        val intent = Intent(context, DetailedUsageWidget::class.java).apply {
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

    // ─── Per-widget state ──────────────────────────────────────

    private fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences(DeepSeekWidget.DISPLAY_PREFS, Context.MODE_PRIVATE)

    private fun rangeKey(id: Int) = "detailed_range_$id"
    private fun keyKey(id: Int) = "detailed_key_$id"
    private fun visKey(id: Int) = "detailed_vis_$id"

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
        DetailedUsageData.deserialize(prefs(c).getString(KEY_DETAILED_DATA, null))

    private fun saveData(c: Context, d: DetailedUsageData) =
        prefs(c).edit().putString(KEY_DETAILED_DATA, d.serialize()).apply()

    // ─── Constants ─────────────────────────────────────────────

    private data class TimeRangeOption(val label: String, val days: Int)

    companion object {
        /** Called by [WidgetUpdateWorker] to push freshly fetched data to every instance. */
        fun updateWidgets(context: Context, data: DetailedUsageData) {
            DetailedUsageWidget().renderAll(context, data)
        }

        private const val ACTION_REFRESH = "com.tiramisu.deepseekwidget.DETAILED_REFRESH"
        private const val ACTION_CYCLE_TIME = "com.tiramisu.deepseekwidget.DETAILED_CYCLE_TIME"
        private const val ACTION_CYCLE_KEY = "com.tiramisu.deepseekwidget.DETAILED_CYCLE_KEY"
        private const val ACTION_TOGGLE_TOTAL = "com.tiramisu.deepseekwidget.DETAILED_TOGGLE_TOTAL"

        private const val SLOT_REFRESH = 0
        private const val SLOT_TIME = 1
        private const val SLOT_KEY = 2
        private const val SLOT_EYE = 3
        private const val SLOT_OPEN = 4

        private const val KEY_DETAILED_DATA = "detailed_data"
        private const val USAGE_URL = "https://platform.deepseek.com/usage"

        /** 24h → 7d → 30d (cycle order); 7d is the default. */
        private val RANGES = listOf(
            TimeRangeOption("Today", 1),
            TimeRangeOption("Last 7 days", 7),
            TimeRangeOption("Last 30 days", 30)
        )
        private const val DEFAULT_RANGE_INDEX = 1
    }
}
