package fr.bonobo.dnsphere

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import fr.bonobo.dnsphere.utils.PrefsManager

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // On écoute le boot ET la mise à jour de l'application
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d("DNSphere", "🚀 Boot ou Mise à jour détectée")

            val prefs = PrefsManager(context)

            // 1. RE-PLANIFICATION SUPPRIMÉE - Les fonctionnalités de planning ont été retirées

            // 2. AUTO-START (Ton code existant)
            if (prefs.autoStart) {
                val startIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("auto_start", true)
                }
                context.startActivity(startIntent)
            }
        }
    }
}