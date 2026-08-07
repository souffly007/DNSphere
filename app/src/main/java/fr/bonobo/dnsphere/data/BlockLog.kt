package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "block_logs")
data class BlockLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val domain: String,
    val type: String,
    val timestamp: Long = System.currentTimeMillis(),
    val blocked: Boolean = true
)