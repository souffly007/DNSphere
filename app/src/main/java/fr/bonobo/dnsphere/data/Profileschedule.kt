// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Profileschedule {
package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Créneau horaire planifié pour un profil.
 * Quand l'heure correspond, DNSphere bascule automatiquement sur ce profil.
 *
 * Les jours actifs sont encodés en bitmask :
 * Lundi=1, Mardi=2, Mercredi=4, Jeudi=8, Vendredi=16, Samedi=32, Dimanche=64
 * Ex: Lundi+Mardi = 3, Toute la semaine = 127
 */
@Entity(
    tableName = "profile_schedules",
    foreignKeys = [
        ForeignKey(
            entity = Profile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("profileId")]
)
data class ProfileSchedule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: Long,
    val label: String = "",           // Ex: "Nuit", "Journée travail"
    val startHour: Int,               // 0-23
    val startMinute: Int,             // 0-59
    val endHour: Int,                 // 0-23
    val endMinute: Int,               // 0-59
    val activeDays: Int = 127,        // Bitmask jours — 127 = tous les jours
    val enabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val MON = 1
        const val TUE = 2
        const val WED = 4
        const val THU = 8
        const val FRI = 16
        const val SAT = 32
        const val SUN = 64
        const val WEEKDAYS = MON or TUE or WED or THU or FRI  // 31
        const val WEEKEND  = SAT or SUN                        // 96
        const val ALL_DAYS = 127
    }

    /** Vérifie si ce créneau est actif pour un jour donné (Calendar.DAY_OF_WEEK) */
    fun isActiveOnDay(dayOfWeek: Int): Boolean {
        val bit = when (dayOfWeek) {
            java.util.Calendar.MONDAY    -> MON
            java.util.Calendar.TUESDAY   -> TUE
            java.util.Calendar.WEDNESDAY -> WED
            java.util.Calendar.THURSDAY  -> THU
            java.util.Calendar.FRIDAY    -> FRI
            java.util.Calendar.SATURDAY  -> SAT
            java.util.Calendar.SUNDAY    -> SUN
            else -> 0
        }
        return activeDays and bit != 0
    }

    /** Vérifie si l'heure actuelle est dans ce créneau */
    fun isActiveNow(): Boolean {
        if (!enabled) return false
        val cal     = java.util.Calendar.getInstance()
        val dayOk   = isActiveOnDay(cal.get(java.util.Calendar.DAY_OF_WEEK))
        if (!dayOk) return false

        val nowMin  = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val startMin = startHour * 60 + startMinute
        val endMin   = endHour   * 60 + endMinute

        return if (startMin <= endMin) {
            // Créneau normal : ex 08:00 → 18:00
            nowMin in startMin..endMin
        } else {
            // Créneau qui passe minuit : ex 22:00 → 06:00
            nowMin >= startMin || nowMin <= endMin
        }
    }

    fun getTimeLabel(): String =
        String.format("%02d:%02d → %02d:%02d", startHour, startMinute, endHour, endMinute)

    fun getDaysLabel(): String {
        if (activeDays == ALL_DAYS) return "Tous les jours"
        if (activeDays == WEEKDAYS) return "Lun–Ven"
        if (activeDays == WEEKEND)  return "Sam–Dim"
        val days = mutableListOf<String>()
        if (activeDays and MON != 0) days.add("Lun")
        if (activeDays and TUE != 0) days.add("Mar")
        if (activeDays and WED != 0) days.add("Mer")
        if (activeDays and THU != 0) days.add("Jeu")
        if (activeDays and FRI != 0) days.add("Ven")
        if (activeDays and SAT != 0) days.add("Sam")
        if (activeDays and SUN != 0) days.add("Dim")
        return days.joinToString(", ")
    }
}