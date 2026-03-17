package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "external_lists")
data class ExternalList(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    val url: String,
    val description: String,
    val category: ListCategory,
    val format: ListFormat,
    val enabled: Boolean = true,
    val domainCount: Int = 0,
    val lastUpdated: Long = 0,
    val lastError: String? = null,
    val isBuiltIn: Boolean = false
)

enum class ListCategory {
    ADS,
    TRACKERS,
    MALWARE,
    PRIVACY,
    SOCIAL,
    CUSTOM
}

enum class ListFormat {
    HOSTS,
    ADBLOCK,
    DOMAINS,
    DNSMASQ
}
