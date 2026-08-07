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

    // ==================== AJOUTÉ : Pour StatsActivity ====================
    @Query("SELECT * FROM block_logs WHERE timestamp >= :startTime ORDER BY timestamp DESC")
    suspend fun getLogsSince(startTime: Long): List<BlockLog>

    // ==================== NOUVELLES REQUÊTES STATS ====================

    // Total bloqués
    @Query("SELECT COUNT(*) FROM block_logs WHERE blocked = 1")
    suspend fun getTotalBlocked(): Int

    // Total bloqués aujourd'hui
    @Query("SELECT COUNT(*) FROM block_logs WHERE blocked = 1 AND timestamp >= :startOfDay")
    suspend fun getBlockedToday(startOfDay: Long): Int

    // Total bloqués cette semaine
    @Query("SELECT COUNT(*) FROM block_logs WHERE blocked = 1 AND timestamp >= :startOfWeek")
    suspend fun getBlockedThisWeek(startOfWeek: Long): Int

    // Total bloqués ce mois
    @Query("SELECT COUNT(*) FROM block_logs WHERE blocked = 1 AND timestamp >= :startOfMonth")
    suspend fun getBlockedThisMonth(startOfMonth: Long): Int

    // Comptage par type (pour un intervalle de temps)
    @Query("SELECT COUNT(*) FROM block_logs WHERE type = :type AND blocked = 1 AND timestamp >= :since")
    suspend fun getCountByType(type: String, since: Long): Int

    // Comptage par type (tout le temps)
    @Query("SELECT COUNT(*) FROM block_logs WHERE type = :type AND blocked = 1")
    suspend fun getCountByTypeAllTime(type: String): Int

    // Top domaines bloqués (avec comptage)
    @Query("""
        SELECT domain, COUNT(*) as count 
        FROM block_logs 
        WHERE blocked = 1 AND timestamp >= :since
        GROUP BY domain 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    suspend fun getTopBlockedDomains(since: Long, limit: Int = 10): List<DomainCount>

    // Top domaines bloqués (tout le temps)
    @Query("""
        SELECT domain, COUNT(*) as count 
        FROM block_logs 
        WHERE blocked = 1
        GROUP BY domain 
        ORDER BY count DESC 
        LIMIT :limit
    """)
    suspend fun getTopBlockedDomainsAllTime(limit: Int = 10): List<DomainCount>

    // Blocages par jour (pour graphique)
    @Query("""
        SELECT 
            (timestamp / 86400000) as dayTimestamp,
            COUNT(*) as count
        FROM block_logs 
        WHERE blocked = 1 AND timestamp >= :since
        GROUP BY dayTimestamp
        ORDER BY dayTimestamp ASC
    """)
    suspend fun getBlocksPerDay(since: Long): List<DayCount>

    // Blocages par heure (pour graphique 24h)
    @Query("""
        SELECT 
            ((timestamp % 86400000) / 3600000) as hour,
            COUNT(*) as count
        FROM block_logs 
        WHERE blocked = 1 AND timestamp >= :since
        GROUP BY hour
        ORDER BY hour ASC
    """)
    suspend fun getBlocksPerHour(since: Long): List<HourCount>

    // Statistiques par type pour une période
    @Query("""
        SELECT type, COUNT(*) as count 
        FROM block_logs 
        WHERE blocked = 1 AND timestamp >= :since
        GROUP BY type
    """)
    suspend fun getStatsByType(since: Long): List<TypeCount>

    // Statistiques par type (tout le temps)
    @Query("""
        SELECT type, COUNT(*) as count 
        FROM block_logs 
        WHERE blocked = 1
        GROUP BY type
    """)
    suspend fun getStatsByTypeAllTime(): List<TypeCount>

    // CORRECTION ICI : "block_logs" au lieu de "block_log"
    @Query("DELETE FROM block_logs WHERE timestamp < :threshold")
    suspend fun deleteOldLogs(threshold: Long)
}

// Classes pour les résultats des requêtes
data class DomainCount(
    val domain: String,
    val count: Int
)

data class DayCount(
    val dayTimestamp: Long,
    val count: Int
)

data class HourCount(
    val hour: Int,
    val count: Int
)

data class TypeCount(
    val type: String,
    val count: Int
)