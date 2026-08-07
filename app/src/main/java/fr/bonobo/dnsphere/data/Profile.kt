package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class Profile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val name: String,
    val icon: String = "🛡️",

    // Options de blocage
    val blockAds: Boolean = true,
    val blockTrackers: Boolean = true,
    val blockMalware: Boolean = true,
    val blockSocial: Boolean = false,
    val blockAdult: Boolean = false,
    val blockGambling: Boolean = false,

    // Est-ce un profil prédéfini (non supprimable)
    val isPreset: Boolean = false,

    // Est-ce le profil actif
    val isActive: Boolean = false,

    // Timestamp de création
    val createdAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Profils prédéfinis
        fun getDefaultProfiles(): List<Profile> = listOf(
            Profile(
                id = 1,
                name = "Standard",
                icon = "🛡️",
                blockAds = true,
                blockTrackers = true,
                blockMalware = true,
                blockSocial = false,
                blockAdult = false,
                blockGambling = false,
                isPreset = true,
                isActive = true
            ),
            Profile(
                id = 2,
                name = "Travail",
                icon = "💼",
                blockAds = true,
                blockTrackers = true,
                blockMalware = true,
                blockSocial = true,
                blockAdult = true,
                blockGambling = true,
                isPreset = true
            ),
            Profile(
                id = 3,
                name = "Maison",
                icon = "🏠",
                blockAds = true,
                blockTrackers = false,
                blockMalware = true,
                blockSocial = false,
                blockAdult = false,
                blockGambling = false,
                isPreset = true
            ),
            Profile(
                id = 4,
                name = "Gaming",
                icon = "🎮",
                blockAds = false,
                blockTrackers = false,
                blockMalware = true,
                blockSocial = false,
                blockAdult = false,
                blockGambling = false,
                isPreset = true
            ),
            Profile(
                id = 5,
                name = "Enfants",
                icon = "👶",
                blockAds = true,
                blockTrackers = true,
                blockMalware = true,
                blockSocial = true,
                blockAdult = true,
                blockGambling = true,
                isPreset = true
            )
        )
    }

    fun getBlockingDescription(): String {
        val items = mutableListOf<String>()
        if (blockAds) items.add("Pubs")
        if (blockTrackers) items.add("Trackers")
        if (blockMalware) items.add("Malware")
        if (blockSocial) items.add("Réseaux sociaux")
        if (blockAdult) items.add("Adulte")
        if (blockGambling) items.add("Paris")

        return if (items.isEmpty()) "Aucun blocage" else items.joinToString(" • ")
    }

    fun getBlockingCount(): Int {
        var count = 0
        if (blockAds) count++
        if (blockTrackers) count++
        if (blockMalware) count++
        if (blockSocial) count++
        if (blockAdult) count++
        if (blockGambling) count++
        return count
    }
}