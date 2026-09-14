package fr.bonobo.dnsphere.data

import java.util.Calendar

/**
 * Calcule la prochaine frontière de créneau (début ou fin).
 * Cette classe ne dépend ni d'Android ni de WorkManager afin d'être testable.
 */
object ProfileScheduleCalculator {

    fun findNextBoundary(
        schedules: List<ProfileSchedule>,
        now: Calendar = Calendar.getInstance()
    ): Long? {
        val nowMillis = now.timeInMillis
        val candidates = mutableListOf<Long>()

        // -1 couvre la fin d'un créneau commencé la veille ; 7 couvre toute
        // la semaine suivante, même si aujourd'hui aucun créneau n'est actif.
        for (dayOffset in -1..7) {
            val startDay = (now.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            val dayOfWeek = startDay.get(Calendar.DAY_OF_WEEK)
            schedules.filter { it.enabled && it.isActiveOnDay(dayOfWeek) }.forEach { schedule ->
                candidates += atTime(startDay, schedule.startHour, schedule.startMinute)

                val endDay = (startDay.clone() as Calendar).apply {
                    val start = schedule.startHour * 60 + schedule.startMinute
                    val end = schedule.endHour * 60 + schedule.endMinute
                    if (end < start) add(Calendar.DAY_OF_YEAR, 1)
                }
                candidates += atTime(endDay, schedule.endHour, schedule.endMinute)
            }
        }

        return candidates.filter { it > nowMillis }.minOrNull()
    }

    private fun atTime(day: Calendar, hour: Int, minute: Int): Long =
        (day.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
