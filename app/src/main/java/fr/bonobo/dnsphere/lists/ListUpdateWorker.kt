package fr.bonobo.dnsphere.lists

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import java.util.concurrent.TimeUnit

class ListUpdateWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "ListUpdateWorker"
        private const val WORK_NAME = "list_update_work"
        
        fun schedule(context: Context, intervalHours: Long = 24) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()
            
            val request = PeriodicWorkRequestBuilder<ListUpdateWorker>(
                intervalHours, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15, TimeUnit.MINUTES
                )
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            
            Log.d(TAG, "Mise à jour planifiée toutes les $intervalHours heures")
        }
        
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
        
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ListUpdateWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            
            WorkManager.getInstance(context).enqueue(request)
        }
    }
    
    override suspend fun doWork(): Result {
        Log.d(TAG, "Début de la mise à jour des listes...")
        
        return try {
            val downloader = ListDownloader(applicationContext)
            val results = downloader.updateAllEnabledLists()
            
            val success = results.values.count { it.isSuccess }
            val failed = results.values.count { it.isFailure }
            
            Log.d(TAG, "Mise à jour terminée: $success succès, $failed échecs")
            
            if (failed > success) {
                Result.retry()
            } else {
                Result.success()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Erreur lors de la mise à jour: ${e.message}")
            Result.retry()
        }
    }
}
