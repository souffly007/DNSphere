package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.data.ProfileSchedule
import fr.bonobo.dnsphere.data.ProfileScheduleCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

class ProfileScheduleCalculatorTest {

    @Test
    fun findsTheNextBoundaryForAnOvernightSchedule() {
        val mondayNight = ProfileSchedule(
            profileId = 1,
            startHour = 22,
            startMinute = 0,
            endHour = 6,
            endMinute = 0,
            activeDays = ProfileSchedule.MON
        )
        val mondayAtNoon = fixedCalendar(Calendar.MONDAY, 12, 0)

        val next = ProfileScheduleCalculator.findNextBoundary(
            listOf(mondayNight),
            mondayAtNoon
        )

        val expected = (mondayAtNoon.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 22)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(expected, next)
    }

    @Test
    fun findsTheEndAfterMidnightWhenTheStartDayWasYesterday() {
        val mondayNight = ProfileSchedule(
            profileId = 1,
            startHour = 22,
            startMinute = 0,
            endHour = 6,
            endMinute = 0,
            activeDays = ProfileSchedule.MON
        )
        val tuesdayAtOne = fixedCalendar(Calendar.TUESDAY, 1, 0)

        val next = ProfileScheduleCalculator.findNextBoundary(
            listOf(mondayNight),
            tuesdayAtOne
        )

        val expected = (tuesdayAtOne.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 6)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        assertEquals(expected, next)
    }

    private fun fixedCalendar(dayOfWeek: Int, hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.SEPTEMBER)
            set(Calendar.DAY_OF_MONTH, 7) // lundi 7 septembre 2026
            while (get(Calendar.DAY_OF_WEEK) != dayOfWeek) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
}
