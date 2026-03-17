package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entité Room — stocke la config complète du contrôle parental
 * Une seule ligne en base (id = 1 toujours)
 */
@Entity(tableName = "parental_control")
data class ParentalControl(
    @PrimaryKey val id: Int = 1,

    // --- Protection PIN ---
    val pinHash: String = "",           // SHA-256 du PIN (jamais en clair)
    val pinEnabled: Boolean = false,

    // --- Catégories bloquées ---
    val blockAdult: Boolean = false,
    val blockGaming: Boolean = false,
    val blockSocialMedia: Boolean = false,
    val blockStreaming: Boolean = false,
    val blockForums: Boolean = false,

    // --- Plages horaires ---
    val scheduleEnabled: Boolean = false,
    val allowedStartHour: Int = 8,      // ex: 8 = 08:00
    val allowedStartMinute: Int = 0,
    val allowedEndHour: Int = 21,       // ex: 21 = 21:00
    val allowedEndMinute: Int = 0,

    // --- Jours actifs (bitmask : 1=Lun, 2=Mar, 4=Mer, 8=Jeu, 16=Ven, 32=Sam, 64=Dim) ---
    val activeDays: Int = 127           // 127 = tous les jours
)
