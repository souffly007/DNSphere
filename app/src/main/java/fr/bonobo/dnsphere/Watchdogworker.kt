// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Watchdogworker {
package fr.bonobo.dnsphere

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.*
import fr.bonobo.dnsphere.data.AppDatabase
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * Watchdog VPN — vérifie toutes les 15 minutes que le service est actif.
 * Si le VPN est mort et que l'utilisateur l'avait activé, il le relance automatiquement.
 */
class WatchdogWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG       = "WatchdogWorker"
        private const val WORK_NAME = "dnsphere_watchdog"

        /**
         * Démarre le watchdog en mode périodique.
         * À appeler une fois au démarrage de l'app (dans MainActivity.onCreate).
         */
        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 5, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP, // Ne pas remplacer si déjà en cours
                request
            )

            Log.d(TAG, "✅ Watchdog démarré (période: 15 min)")
        }

        /**
         * Arrête le watchdog (si l'utilisateur désactive définitivement la protection).
         */
        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "🛑 Watchdog arrêté")
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "🔍 Vérification VPN...")

        val prefs      = context.getSharedPreferences("dnsphere_prefs", Context.MODE_PRIVATE)
        val userWantsVpn = prefs.getBoolean("vpn_should_be_running", false)

        // Purge des stats de plus de 4 jours
        try {
            val lastPurge = prefs.getLong("last_stats_purge", 0L)
            val now       = System.currentTimeMillis()
            if (now - lastPurge > 24 * 60 * 60 * 1000L) {
                val fourDaysAgo = now - (4 * 24 * 60 * 60 * 1000L)

                // Obtenir le DAO depuis la base de données Room
                runBlocking {
                    val database = AppDatabase.getInstance(context)
                    database.blockLogDao().deleteOldLogs(fourDaysAgo)
                }

                prefs.edit().putLong("last_stats_purge", now).apply()
                Log.d(TAG, "🗑️ Stats purgées — logs > 4 jours supprimés")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur lors de la purge des stats: ${e.message}", e)
            // On continue l'exécution même si la purge échoue
        }

        if (!userWantsVpn) {
            Log.d(TAG, "ℹ️ VPN désactivé par l'utilisateur, pas de relance")
            return Result.success()
        }

        if (LocalVpnService.isRunning) {
            Log.d(TAG, "✅ VPN actif, rien à faire")
            return Result.success()
        }

        // VPN mort alors que l'utilisateur l'avait activé → relance
        Log.w(TAG, "⚠️ VPN inactif détecté ! Relance en cours...")

        return try {
            val vpnPrefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            val dnsPrefs = context.getSharedPreferences("dnsphere_prefs", Context.MODE_PRIVATE)

            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_START
                putExtra("block_ads",      vpnPrefs.getBoolean("block_ads",      true))
                putExtra("block_trackers", vpnPrefs.getBoolean("block_trackers", true))
                putExtra("block_malware",  vpnPrefs.getBoolean("block_malware",  true))
                putExtra("block_social",   vpnPrefs.getBoolean("block_social",   false))
                putExtra("block_adult",    vpnPrefs.getBoolean("block_adult",    false))
                putExtra("block_gambling", vpnPrefs.getBoolean("block_gambling", false))
                putExtra("use_doh",        dnsPrefs.getBoolean("use_doh",        false))
                putExtra("use_dot",        dnsPrefs.getBoolean("use_dot",        false))
                putExtra("use_doq",        dnsPrefs.getBoolean("use_doq",        false))
                putExtra("use_doh3",       dnsPrefs.getBoolean("use_doh3",       false))
                putExtra("doh_provider",   dnsPrefs.getString("current_dns_provider", "cloudflare"))
            }

            ContextCompat.startForegroundService(context, intent)
            Log.d(TAG, "✅ VPN relancé par le watchdog")
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Echec relance VPN: ${e.message}")
            Result.retry()
        }
    }
}