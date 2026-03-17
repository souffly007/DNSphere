package fr.bonobo.dnsphere.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.Schedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*

class ScheduleManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val database = AppDatabase.getInstance(context)

    companion object {
        const val ACTION_SCHEDULE_START = "fr.bonobo.dnsphere.SCHEDULE_START"
        const val ACTION_SCHEDULE_END = "fr.bonobo.dnsphere.SCHEDULE_END"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_ENABLE_PROTECTION = "enable_protection"

        @Volatile
        private var INSTANCE: ScheduleManager? = null

        fun getInstance(context: Context): ScheduleManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ScheduleManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Planifie toutes les alarmes pour les schedules actifs
     */
    fun scheduleAll() {
        CoroutineScope(Dispatchers.IO).launch {
            val schedules = database.scheduleDao().getEnabledSchedulesList()
            schedules.forEach { schedule ->
                scheduleAlarms(schedule)
            }
        }
    }

    /**
     * Planifie les alarmes pour un schedule spécifique
     */
    fun scheduleAlarms(schedule: Schedule) {
        if (!schedule.isEnabled) {
            cancelAlarms(schedule)
            return
        }

        // Convertir Calendar.DAY_OF_WEEK en notre format
        val dayMapping = mapOf(
            Calendar.MONDAY to Schedule.MONDAY,
            Calendar.TUESDAY to Schedule.TUESDAY,
            Calendar.WEDNESDAY to Schedule.WEDNESDAY,
            Calendar.THURSDAY to Schedule.THURSDAY,
            Calendar.FRIDAY to Schedule.FRIDAY,
            Calendar.SATURDAY to Schedule.SATURDAY,
            Calendar.SUNDAY to Schedule.SUNDAY
        )

        // Trouver le prochain jour/heure de déclenchement
        for (i in 0..6) {
            val checkDay = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, i)
            }
            val dayOfWeek = checkDay.get(Calendar.DAY_OF_WEEK)
            val scheduleDayBit = dayMapping[dayOfWeek] ?: continue

            if (schedule.isDayEnabled(scheduleDayBit)) {
                // Planifier alarme de début
                val startTime = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, i)
                    set(Calendar.HOUR_OF_DAY, schedule.startTimeMinutes / 60)
                    set(Calendar.MINUTE, schedule.startTimeMinutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                // Si l'heure est déjà passée aujourd'hui, passer au jour suivant
                if (startTime.timeInMillis > System.currentTimeMillis()) {
                    setAlarm(
                        schedule.id,
                        startTime.timeInMillis,
                        ACTION_SCHEDULE_START,
                        schedule.enableProtection
                    )
                    break
                }

                // Planifier alarme de fin
                val endTime = Calendar.getInstance().apply {
                    add(Calendar.DAY_OF_YEAR, i)
                    set(Calendar.HOUR_OF_DAY, schedule.endTimeMinutes / 60)
                    set(Calendar.MINUTE, schedule.endTimeMinutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                if (endTime.timeInMillis > System.currentTimeMillis()) {
                    setAlarm(
                        schedule.id,
                        endTime.timeInMillis,
                        ACTION_SCHEDULE_END,
                        !schedule.enableProtection  // Inverser l'action à la fin
                    )
                }
            }
        }
    }

    private fun setAlarm(scheduleId: Long, triggerTime: Long, action: String, enableProtection: Boolean) {
        val intent = Intent(context, ScheduleReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(EXTRA_ENABLE_PROTECTION, enableProtection)
        }

        val requestCode = (scheduleId * 10 + if (action == ACTION_SCHEDULE_START) 1 else 2).toInt()

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } catch (e: SecurityException) {
            // Fallback pour les appareils sans permission SCHEDULE_EXACT_ALARM
            alarmManager.set(
                AlarmManager.RTC_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }
    }

    /**
     * Annule les alarmes pour un schedule
     */
    fun cancelAlarms(schedule: Schedule) {
        val startIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_SCHEDULE_START
        }
        val endIntent = Intent(context, ScheduleReceiver::class.java).apply {
            action = ACTION_SCHEDULE_END
        }

        val startRequestCode = (schedule.id * 10 + 1).toInt()
        val endRequestCode = (schedule.id * 10 + 2).toInt()

        val startPendingIntent = PendingIntent.getBroadcast(
            context, startRequestCode, startIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        val endPendingIntent = PendingIntent.getBroadcast(
            context, endRequestCode, endIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        startPendingIntent?.let { alarmManager.cancel(it) }
        endPendingIntent?.let { alarmManager.cancel(it) }
    }

    /**
     * Vérifie si on est actuellement dans une période planifiée
     */
    suspend fun isInScheduledPeriod(): Pair<Boolean, Boolean?> {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val currentDayOfWeek = now.get(Calendar.DAY_OF_WEEK)

        val dayMapping = mapOf(
            Calendar.MONDAY to Schedule.MONDAY,
            Calendar.TUESDAY to Schedule.TUESDAY,
            Calendar.WEDNESDAY to Schedule.WEDNESDAY,
            Calendar.THURSDAY to Schedule.THURSDAY,
            Calendar.FRIDAY to Schedule.FRIDAY,
            Calendar.SATURDAY to Schedule.SATURDAY,
            Calendar.SUNDAY to Schedule.SUNDAY
        )

        val currentDayBit = dayMapping[currentDayOfWeek] ?: return Pair(false, null)

        val schedules = database.scheduleDao().getEnabledSchedulesList()

        for (schedule in schedules) {
            if (schedule.isDayEnabled(currentDayBit)) {
                if (currentMinutes >= schedule.startTimeMinutes &&
                    currentMinutes < schedule.endTimeMinutes) {
                    return Pair(true, schedule.enableProtection)
                }
            }
        }

        return Pair(false, null)
    }
}