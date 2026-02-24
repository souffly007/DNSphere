package fr.bonobo.dnsphere.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.MainActivity
import fr.bonobo.dnsphere.R

class DnsphereWidget : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "fr.bonobo.dnsphere.widget.TOGGLE"

        fun updateWidget(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val widgetComponent = ComponentName(context, DnsphereWidget::class.java)
            val widgetIds = appWidgetManager.getAppWidgetIds(widgetComponent)

            widgetIds.forEach { widgetId ->
                updateAppWidget(context, appWidgetManager, widgetId)
            }
        }

        private fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_dnsphere)

            val isRunning = LocalVpnService.isRunning

            if (isRunning) {
                views.setTextViewText(R.id.tvWidgetStatus, "Protection Active")
                views.setTextColor(R.id.tvWidgetStatus, context.getColor(R.color.green))
                views.setTextViewText(R.id.btnWidgetToggle, "Désactiver")
            } else {
                views.setTextViewText(R.id.tvWidgetStatus, "Protection Inactive")
                views.setTextColor(R.id.tvWidgetStatus, context.getColor(R.color.red))
                views.setTextViewText(R.id.btnWidgetToggle, "Activer")
            }

            val prefs = context.getSharedPreferences("dnsphere_stats", Context.MODE_PRIVATE)
            views.setTextViewText(R.id.tvWidgetAds, prefs.getInt("ads_blocked", 0).toString())
            views.setTextViewText(R.id.tvWidgetTrackers, prefs.getInt("trackers_blocked", 0).toString())

            val toggleIntent = Intent(context, DnsphereWidget::class.java).apply {
                action = ACTION_TOGGLE
            }
            val togglePendingIntent = PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btnWidgetToggle, togglePendingIntent)

            val openIntent = Intent(context, MainActivity::class.java)
            val openPendingIntent = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetRoot, openPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        if (intent.action == ACTION_TOGGLE) {
            if (LocalVpnService.isRunning) {
                val serviceIntent = Intent(context, LocalVpnService::class.java).apply {
                    action = LocalVpnService.ACTION_STOP
                }
                context.startService(serviceIntent)
            } else {
                val mainIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("auto_start", true)
                }
                context.startActivity(mainIntent)
            }
            updateWidget(context)
        }
    }
}