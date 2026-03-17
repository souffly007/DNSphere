package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String = "",

    // Heures (en minutes depuis minuit, ex: 8h30 = 510)
    val startTimeMinutes: Int = 480,  // 8h00 par défaut
    val endTimeMinutes: Int = 1080,   // 18h00 par défaut

    // Jours de la semaine (bitmask: Lun=1, Mar=2, Mer=4, Jeu=8, Ven=16, Sam=32, Dim=64)
    val daysOfWeek: Int = 31,  // Lun-Ven par défaut

    // Action: true = activer protection, false = désactiver
    val enableProtection: Boolean = true,

    // Actif ou non
    val isEnabled: Boolean = true,

    // Timestamp de création
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MONDAY = 1
        const val TUESDAY = 2
        const val WEDNESDAY = 4
        const val THURSDAY = 8
        const val FRIDAY = 16
        const val SATURDAY = 32
        const val SUNDAY = 64

        const val WEEKDAYS = MONDAY or TUESDAY or WEDNESDAY or THURSDAY or FRIDAY  // 31
        const val WEEKEND = SATURDAY or SUNDAY  // 96
        const val ALL_DAYS = WEEKDAYS or WEEKEND  // 127
    }

    fun isDayEnabled(day: Int): Boolean = (daysOfWeek and day) != 0

    fun getStartTimeFormatted(): String {
        val hours = startTimeMinutes / 60
        val minutes = startTimeMinutes % 60
        return String.format("%02d:%02d", hours, minutes)
    }

    fun getEndTimeFormatted(): String {
        val hours = endTimeMinutes / 60
        val minutes = endTimeMinutes % 60
        return String.format("%02d:%02d", hours, minutes)
    }

    fun getDaysFormatted(): String {
        val days = mutableListOf<String>()
        if (isDayEnabled(MONDAY)) days.add("Lun")
        if (isDayEnabled(TUESDAY)) days.add("Mar")
        if (isDayEnabled(WEDNESDAY)) days.add("Mer")
        if (isDayEnabled(THURSDAY)) days.add("Jeu")
        if (isDayEnabled(FRIDAY)) days.add("Ven")
        if (isDayEnabled(SATURDAY)) days.add("Sam")
        if (isDayEnabled(SUNDAY)) days.add("Dim")

        return when {
            daysOfWeek == ALL_DAYS -> "Tous les jours"
            daysOfWeek == WEEKDAYS -> "Lun-Ven"
            daysOfWeek == WEEKEND -> "Week-end"
            else -> days.joinToString(", ")
        }
    }
}