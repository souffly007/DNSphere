package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "custom_lists")
data class CustomList(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val url: String? = null,
    val type: String = "ADS", // ADS, TRACKERS, MALWARE, CUSTOM
    val enabled: Boolean = true,
    val domainCount: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)

@Entity(tableName = "custom_domains")
data class CustomDomain(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val listId: Long
)