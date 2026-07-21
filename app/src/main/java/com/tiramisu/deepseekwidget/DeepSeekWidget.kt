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
        const val KEY_API_KEY = "api_key"
        const val ACTION_REFRESH = "com.tiramisu.deepseekwidget.ACTION_REFRESH"
        private const val ACTION_MODEL_TOGGLE = "com.tiramisu.deepseekwidget.ACTION_MODEL_TOGGLE"
        const val UPDATE_INTERVAL_MINUTES = 30L
        private const val DISPLAY_PREFS = "deepseek_widget_display"
        private const val KEY_CACHED = "cached_text"
        private const val KEY_CACHED_DATA = "cached_data"
        private const val KEY_MODEL_PRO = "model_pro"
        private const val BLUE = 0xFF4D6BFE.toInt()
        private const val BLUE_DARK = 0xFF5C78E8.toInt()
        private fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(DISPLAY_PREFS, Context.MODE_PRIVATE)
        fun getApiKey(c: Context): String? = try { val mk=MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(); EncryptedSharedPreferences.create(c,PREFS_NAME,mk,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM).getString(KEY_API_KEY,null) } catch (_:Exception){null}
        fun setApiKey(c: Context, key:String) { val mk=MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(); EncryptedSharedPreferences.create(c,PREFS_NAME,mk,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM).edit().putString(KEY_API_KEY,key).apply() }
        fun getTokenFromAccount(c:Context):String?=DeepSeekAccountManager(c).getValidToken()
        fun getAuthToken(c:Context):String?=getTokenFromAccount(c)?:getApiKey(c)
        private fun refreshPi(c:Context)=PendingIntent.getBroadcast(c,0,Intent(c,DeepSeekWidget::class.java).apply{action=ACTION_REFRESH},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun modelPi(c:Context)=PendingIntent.getBroadcast(c,1,Intent(c,DeepSeekWidget::class.java).apply{action=ACTION_MODEL_TOGGLE},PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        private fun serialize(d:WidgetDisplayData)=listOf(d.balance,d.todayCost,d.monthlyCost,d.updatedAt,d.flashData.totalTokens,d.flashData.cacheHitRate,d.flashData.cost,d.flashData.requests,d.proData.totalTokens,d.proData.cacheHitRate,d.proData.cost,d.proData.requests,d.error?:"").joinToString("|")
        private fun deserialize(raw:String?):WidgetDisplayData? {
            return try {
                val p = raw?.split("|") ?: return null
                if (p.size < 13) return null
                WidgetDisplayData(true, p[0], todayCost = p[1], monthlyCost = p[2], updatedAt = p[3].toLong(),
                    flashData = ModelData(p[4].toLong(), p[5], p[6], p[7].toLong()),
                    proData = ModelData(p[8].toLong(), p[9], p[10], p[11].toLong()),
                    error = p[12].ifBlank { null })
            } catch (_: Exception) { null }
        }
        private fun render(v:RemoteViews,d:WidgetDisplayData,display:String,pro:Boolean,status:String="") { v.setTextViewText(R.id.tv_title,"DeepSeek");v.setTextViewText(R.id.tv_updated,if(d.updatedAt>0)d.formattedUpdatedTime else "--:--");v.setTextViewText(R.id.tv_refresh_status,status);v.setTextViewText(R.id.tv_balance_value,display);v.setTextViewText(R.id.tv_today_value,d.formattedTodayCost);v.setTextViewText(R.id.tv_month_value,d.formattedMonthCost);v.setTextColor(R.id.tv_title,BLUE);v.setTextColor(R.id.tv_balance_value,BLUE_DARK);v.setTextColor(R.id.tv_today_value,BLUE_DARK);v.setTextColor(R.id.tv_month_value,BLUE_DARK);val m=if(pro)d.proData else d.flashData;v.setTextViewText(R.id.tv_model,if(pro)"Pro ›" else "Flash ›");v.setTextViewText(R.id.tv_tokens,WidgetDisplayData.formatTokenCount(m.totalTokens));v.setTextViewText(R.id.tv_requests,m.requests.toString());v.setTextViewText(R.id.tv_cache,if(m.cacheHitRate=="--")"--" else "${m.cacheHitRate}%");v.setTextViewText(R.id.tv_model_cost,"¥${m.cost}");v.setTextColor(R.id.tv_model_cost,0xFFD39B2C.toInt());val x=m.cacheHitRate.toDoubleOrNull();v.setTextColor(R.id.tv_cache,when{ x==null->0xFF687386.toInt();x>=90->0xFF198754.toInt();x>=80->0xFFE09F00.toInt();else->0xFFD93025.toInt()}) }
        fun updateWidgets(c:Context,d:WidgetDisplayData){val p=prefs(c);val display=if(d.error!=null)"⚠️ ${d.error}" else d.formattedBalance;p.edit().putString(KEY_CACHED,display).putString(KEY_CACHED_DATA,serialize(d)).apply();val mgr=AppWidgetManager.getInstance(c);for(id in mgr.getAppWidgetIds(ComponentName(c,DeepSeekWidget::class.java))){val v=RemoteViews(c.packageName,R.layout.widget_layout);render(v,d,display,p.getBoolean(KEY_MODEL_PRO,false));v.setOnClickPendingIntent(R.id.widget_container,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_summary_area,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_model_area,modelPi(c));mgr.updateAppWidget(id,v)}}
        fun triggerRefresh(c:Context){val mgr=AppWidgetManager.getInstance(c);val p=prefs(c);val d=deserialize(p.getString(KEY_CACHED_DATA,null));for(id in mgr.getAppWidgetIds(ComponentName(c,DeepSeekWidget::class.java))){val v=RemoteViews(c.packageName,R.layout.widget_layout);if(d!=null)render(v,d,p.getString(KEY_CACHED,d.formattedBalance)?:d.formattedBalance,p.getBoolean(KEY_MODEL_PRO,false),"刷新中");v.setOnClickPendingIntent(R.id.widget_container,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_summary_area,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_model_area,modelPi(c));mgr.updateAppWidget(id,v)};WorkManager.getInstance(c).enqueue(OneTimeWorkRequestBuilder<WidgetUpdateWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}
    }
    override fun onUpdate(c:Context,mgr:AppWidgetManager,ids:IntArray){schedulePeriodicUpdate(c);val p=prefs(c);for(id in ids){val v=RemoteViews(c.packageName,R.layout.widget_layout);val d=deserialize(p.getString(KEY_CACHED_DATA,null));if(d!=null)render(v,d,p.getString(KEY_CACHED,d.formattedBalance)?:d.formattedBalance,p.getBoolean(KEY_MODEL_PRO,false)) else v.setTextViewText(R.id.tv_balance_value,"⟳ 刷新中...");v.setOnClickPendingIntent(R.id.widget_container,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_summary_area,refreshPi(c));v.setOnClickPendingIntent(R.id.widget_model_area,modelPi(c));mgr.updateAppWidget(id,v)};if(getAuthToken(c)!=null)triggerRefresh(c)}
    override fun onReceive(c:Context,i:Intent){super.onReceive(c,i);if(i.action==ACTION_MODEL_TOGGLE){val p=prefs(c);p.edit().putBoolean(KEY_MODEL_PRO,!p.getBoolean(KEY_MODEL_PRO,false)).apply();deserialize(p.getString(KEY_CACHED_DATA,null))?.let{updateWidgets(c,it)};return};if(i.action==ACTION_REFRESH&&!getAuthToken(c).isNullOrBlank())triggerRefresh(c)}
    override fun onEnabled(c:Context){super.onEnabled(c);schedulePeriodicUpdate(c)}
    override fun onDisabled(c:Context){super.onDisabled(c);WorkManager.getInstance(c).cancelUniqueWork(WIDGET_UPDATE_WORK_NAME)}
    private fun schedulePeriodicUpdate(c:Context){WorkManager.getInstance(c).enqueueUniquePeriodicWork(WIDGET_UPDATE_WORK_NAME,ExistingPeriodicWorkPolicy.KEEP,PeriodicWorkRequestBuilder<WidgetUpdateWorker>(UPDATE_INTERVAL_MINUTES,TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())}
}
const val WIDGET_UPDATE_WORK_NAME="deepseek_widget_update"
