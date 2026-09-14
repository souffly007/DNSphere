// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Profilescheduler {
package fr.bonobo.dnsphere

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.Profile
import fr.bonobo.dnsphere.data.ProfileSchedule
import fr.bonobo.dnsphere.data.ProfileScheduleCalculator
import fr.bonobo.dnsphere.lists.ListUpdateWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Moteur de planification des profils.
 * Déclenche une vérification au prochain début/fin de créneau.
 * Un worker périodique de 15 minutes reste conservé comme filet de sécurité.
 */
class ProfileSchedulerWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG       = "ProfileScheduler"
        private const val WORK_NAME = "dnsphere_profile_scheduler"
        private const val NEXT_WORK_NAME = "dnsphere_profile_scheduler_next_boundary"

        /** Prépare les listes parentales automatiquement pour les profils concernés. */
        suspend fun ensureProfileProtection(context: Context, profile: Profile) {
            if (!profile.blockAdult && !profile.blockGambling) return

            withContext(Dispatchers.IO) {
                try {
                    ParentalManager(context).installParentalDefaultLists()
                    ListUpdateWorker.runNow(context)
                    Log.d(TAG, "🛡️ Listes parentales préparées pour ${profile.name}")
                } catch (e: Exception) {
                    Log.e(TAG, "Impossible de préparer les listes parentales", e)
                }
            }
        }

        fun start(context: Context) {
            val request = PeriodicWorkRequestBuilder<ProfileSchedulerWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )

            // Évaluation immédiate : elle applique le créneau courant et
            // programme ensuite la prochaine frontière avec un délai précis.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                evaluateNow(context)
            }
            Log.d(TAG, "✅ ProfileScheduler démarré")
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(NEXT_WORK_NAME)
            Log.d(TAG, "🛑 ProfileScheduler arrêté")
        }

        /**
         * Évalue immédiatement quel profil doit être actif et le bascule si nécessaire.
         * À appeler aussi depuis LocalVpnService au démarrage.
         */
        suspend fun evaluateNow(context: Context) {
            withContext(Dispatchers.IO) {
                try {
                    val database  = AppDatabase.getInstance(context)
                    val schedules = database.profileScheduleDao().getAllEnabled()

                    if (schedules.isEmpty()) {
                        Log.d(TAG, "ℹ️ Aucune planification active")
                        scheduleNextBoundary(context, schedules)
                        return@withContext
                    }

                    // Trouver le créneau actif avec la priorité la plus haute
                    // (le plus récemment démarré en cas de chevauchement)
                    val activeSchedule = schedules
                        .filter { it.isActiveNow() }
                        .maxByOrNull { it.startHour * 60 + it.startMinute }

                    if (activeSchedule == null) {
                        Log.d(TAG, "ℹ️ Aucun créneau actif en ce moment")
                        scheduleNextBoundary(context, schedules)
                        return@withContext
                    }

                    // Vérifier si le profil cible est déjà actif
                    val currentActive = database.profileDao().getActiveProfileSync()
                    if (currentActive?.id == activeSchedule.profileId) {
                        Log.d(TAG, "✅ Profil déjà correct: ${currentActive.name}")
                        scheduleNextBoundary(context, schedules)
                        return@withContext
                    }

                    // Basculer sur le profil planifié
                    val targetProfile = database.profileDao().getProfileById(activeSchedule.profileId)
                    if (targetProfile != null) {
                        database.profileDao().setActiveProfile(targetProfile.id)
                        applyProfileToVpn(context, targetProfile)
                        ensureProfileProtection(context, targetProfile)
                        Log.d(TAG, "🔄 Profil basculé → ${targetProfile.name} (créneau: ${activeSchedule.getTimeLabel()})")
                    }

                    scheduleNextBoundary(context, schedules)

                } catch (e: Exception) {
                    Log.e(TAG, "❌ Erreur évaluation planification: ${e.message}")
                }
            }
        }

        private suspend fun scheduleNextBoundary(
            context: Context,
            schedules: List<ProfileSchedule>
        ) {
            val workManager = WorkManager.getInstance(context)
            val nextAt = ProfileScheduleCalculator.findNextBoundary(schedules)

            if (nextAt == null) {
                workManager.cancelUniqueWork(NEXT_WORK_NAME)
                return
            }

            val delay = (nextAt - System.currentTimeMillis()).coerceAtLeast(0L)
            val request = OneTimeWorkRequestBuilder<ProfileSchedulerWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .build()

            workManager.enqueueUniqueWork(
                NEXT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "⏰ Prochaine frontière planifiée dans ${delay / 1000}s")
        }

        private fun applyProfileToVpn(context: Context, profile: Profile) {
            if (!LocalVpnService.isRunning) return
            val intent = Intent(context, LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_UPDATE_CONFIG
                putExtra("block_ads",      profile.blockAds)
                putExtra("block_trackers", profile.blockTrackers)
                putExtra("block_malware",  profile.blockMalware)
                putExtra("block_social",   profile.blockSocial)
                putExtra("block_adult",    profile.blockAdult)
                putExtra("block_gambling", profile.blockGambling)
            }
            context.startService(intent)

            // Sauvegarder dans les prefs
            context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE).edit().apply {
                putBoolean("block_ads",      profile.blockAds)
                putBoolean("block_trackers", profile.blockTrackers)
                putBoolean("block_malware",  profile.blockMalware)
                putBoolean("block_social",   profile.blockSocial)
                putBoolean("block_adult",    profile.blockAdult)
                putBoolean("block_gambling", profile.blockGambling)
                apply()
            }
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "⏰ Vérification planification profils...")
        return try {
            runBlocking { evaluateNow(context) }
            Result.success()

        } catch (e: Exception) {
            Log.e(TAG, "❌ Erreur: ${e.message}")
            Result.retry()
        }
    }

    private fun <T> runBlocking(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
