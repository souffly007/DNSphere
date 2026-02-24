package fr.bonobo.dnsphere

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fr.bonobo.dnsphere.utils.PrefsManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = PrefsManager(context)

            if (prefs.autoStart) {
                // Ouvrir MainActivity pour demander la permission VPN si nécessaire
                val startIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("auto_start", true)
                }
                context.startActivity(startIntent)
            }
        }
    }
}