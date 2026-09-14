package fr.bonobo.dnsphere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import fr.bonobo.dnsphere.utils.PowerUtils

/**
 * Construit et met à jour les notifications du VPN.
 *
 * Le contrôleur ne possède pas l'état du VPN : il le reçoit via des fonctions
 * fournies par LocalVpnService. Cela permet une extraction progressive sans
 * modifier le moteur DNS ou le filtrage.
 */
class NotificationController(
    private val context: Context,
    private val isPaused: () -> Boolean,
    private val counters: () -> BlockCounters,
    private val currentDnsLabel: () -> String,
    private val shortDnsLabel: () -> String,
    private val nextDnsProvider: () -> String,
    private val nextDnsProviderIndex: () -> Int,
    private val lastBlockedDomain: () -> String?,
) {

    data class BlockCounters(
        val ads: Int,
        val trackers: Int,
        val malware: Int,
        val shopping: Int,
        val other: Int
    )

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                LocalVpnService.CHANNEL_ID,
                "DNSphere Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Protection DNS active"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)

            val alertChannel = NotificationChannel(
                LocalVpnService.CHANNEL_ID_ALERT,
                "Alertes DNSphere",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prévient si la protection s'arrête de façon inattendue"
                enableVibration(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    fun notifyProtectionInterrupted() {
        try {
            val mainIntent = PendingIntent.getActivity(
                context,
                3,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val likelyMiuiCause = PowerUtils.isMiuiOrHyperOs() &&
                !PowerUtils.isIgnoringBatteryOptimizations(context)

            val contentText = if (likelyMiuiCause) {
                "Le système (MIUI/HyperOS) a probablement arrêté la protection. Appuyez pour régler l'autostart et la batterie."
            } else {
                "Le filtrage DNS s'est arrêté de façon inattendue. Relance en cours…"
            }

            val notification = NotificationCompat.Builder(
                context,
                LocalVpnService.CHANNEL_ID_ALERT
            )
                .setContentTitle("⚠️ Protection DNSphere interrompue")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(mainIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build()

            context.getSystemService(NotificationManager::class.java)
                .notify(LocalVpnService.NOTIFICATION_ID_ALERT, notification)
        } catch (e: Exception) {
            Log.e("DNSphere", "Impossible d'afficher l'alerte d'interruption", e)
        }
    }

    fun createNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = if (isPaused()) {
            PendingIntent.getService(
                context,
                1,
                Intent(context, LocalVpnService::class.java).apply {
                    action = LocalVpnService.ACTION_RESUME
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                context,
                2,
                Intent(context, LocalVpnService::class.java).apply {
                    action = LocalVpnService.ACTION_PAUSE
                    putExtra(LocalVpnService.EXTRA_PAUSE_DURATION, 5 * 60 * 1000L)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val nextProvider = nextDnsProvider()
        val switchDnsIntent = PendingIntent.getService(
            context,
            100 + nextDnsProviderIndex(),
            Intent(context, LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_SWITCH_DNS
                putExtra(LocalVpnService.EXTRA_DNS_PROVIDER, nextProvider)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val values = counters()
        val totalBlocked = values.ads + values.trackers + values.malware +
            values.shopping + values.other
        val paused = isPaused()
        val title = if (paused) "⏸️ DNSphere en pause" else "🛡️ DNSphere actif"
        val shortText = if (paused) {
            "Protection suspendue"
        } else {
            "$totalBlocked bloqués | ${currentDnsLabel()}"
        }
        val longText = if (paused) {
            "Protection suspendue temporairement\nAppuyez sur Reprendre pour réactiver"
        } else {
            "$totalBlocked bloqués (${values.ads} pubs, ${values.trackers} trackers, ${values.malware} malwares)\n${currentDnsLabel()}"
        }

        val whitelistIntent = lastBlockedDomain()?.let { domain ->
            PendingIntent.getService(
                context,
                200,
                Intent(context, LocalVpnService::class.java).apply {
                    action = LocalVpnService.ACTION_QUICK_WHITELIST
                    putExtra(LocalVpnService.EXTRA_WHITELIST_DOMAIN, domain)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(context, LocalVpnService.CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(shortText)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(mainIntent)
            .addAction(
                R.drawable.ic_pause,
                if (paused) "▶️ Reprendre" else "⏸️ Pause",
                pauseResumeIntent
            )
            .addAction(
                R.drawable.ic_shield,
                "DNS: ${shortDnsLabel()} →",
                switchDnsIntent
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(longText))
            .setOngoing(true)
            .setSilent(true)

        if (whitelistIntent != null) {
            builder.addAction(
                R.drawable.ic_whitelist,
                "✅ Whitelister ${lastBlockedDomain()}",
                whitelistIntent
            )
        }

        return builder.build()
    }

    fun updateNotification() {
        try {
            context.getSystemService(NotificationManager::class.java)
                .notify(LocalVpnService.NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.w("DNSphere", "Impossible de mettre à jour la notification", e)
        }
    }
}
