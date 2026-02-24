package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "whitelist")
data class WhitelistItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val addedAt: Long = System.currentTimeMillis(),
    val note: String? = null
)