package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface BlockLogDao {

    @Insert
    suspend fun insert(log: BlockLog)

    @Query("SELECT * FROM block_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): LiveData<List<BlockLog>>

    @Query("SELECT * FROM block_logs ORDER BY timestamp DESC")
    fun getAllLogs(): LiveData<List<BlockLog>>

    @Query("SELECT * FROM block_logs WHERE type = :type ORDER BY timestamp DESC")
    fun getLogsByType(type: String): LiveData<List<BlockLog>>

    @Query("SELECT COUNT(*) FROM block_logs WHERE type = 'AD'")
    fun getAdsBlockedCount(): LiveData<Int>

    @Query("SELECT COUNT(*) FROM block_logs WHERE type = 'TRACKER'")
    fun getTrackersBlockedCount(): LiveData<Int>

    @Query("DELETE FROM block_logs")
    suspend fun clearAll()
}