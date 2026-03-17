package fr.bonobo.dnsphere.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import fr.bonobo.dnsphere.LocalVpnService

class ScheduleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScheduleReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val scheduleId = intent.getLongExtra(ScheduleManager.EXTRA_SCHEDULE_ID, -1)
        val enableProtection = intent.getBooleanExtra(ScheduleManager.EXTRA_ENABLE_PROTECTION, true)

        Log.d(TAG, "Received: action=$action, scheduleId=$scheduleId, enable=$enableProtection")

        when (action) {
            ScheduleManager.ACTION_SCHEDULE_START,
            ScheduleManager.ACTION_SCHEDULE_END -> {
                handleScheduleAction(context, enableProtection)

                // Replanifier pour le prochain jour
                ScheduleManager.getInstance(context).scheduleAll()
            }

            Intent.ACTION_BOOT_COMPLETED -> {
                // Replanifier toutes les alarmes au démarrage
                ScheduleManager.getInstance(context).scheduleAll()
            }
        }
    }

    private fun handleScheduleAction(context: Context, enableProtection: Boolean) {
        val isCurrentlyRunning = LocalVpnService.isRunning

        if (enableProtection && !isCurrentlyRunning) {
            // Activer la protection
            val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)

            val serviceIntent = Intent(context, LocalVpnService::class.java).apply {
                this.action = LocalVpnService.ACTION_START
                putExtra("block_ads", prefs.getBoolean("block_ads", true))
                putExtra("block_trackers", prefs.getBoolean("block_trackers", true))
                putExtra("block_malware", prefs.getBoolean("block_malware", true))
                putExtra("use_doh", prefs.getBoolean("use_doh", false))
                putExtra("doh_provider", prefs.getString("doh_provider", "cloudflare"))
                putExtra("from_schedule", true)
            }

            try {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "Protection started by schedule")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start protection", e)
            }

        } else if (!enableProtection && isCurrentlyRunning) {
            // Désactiver la protection
            val serviceIntent = Intent(context, LocalVpnService::class.java).apply {
                this.action = LocalVpnService.ACTION_STOP
            }
            context.startService(serviceIntent)
            Log.d(TAG, "Protection stopped by schedule")
        }
    }
}