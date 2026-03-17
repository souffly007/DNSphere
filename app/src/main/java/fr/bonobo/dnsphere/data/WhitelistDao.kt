package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface WhitelistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WhitelistItem)

    @Delete
    suspend fun delete(item: WhitelistItem)

    @Query("SELECT * FROM whitelist WHERE forceBlock = 0 ORDER BY addedAt DESC")
    fun getAll(): LiveData<List<WhitelistItem>>

    // ← NOUVEAU : observer les forceBlock séparément
    @Query("SELECT * FROM whitelist WHERE forceBlock = 1 ORDER BY addedAt DESC")
    fun getAllForceBlockedLive(): LiveData<List<WhitelistItem>>

    @Query("SELECT EXISTS(SELECT 1 FROM whitelist WHERE domain = :domain AND forceBlock = 0)")
    suspend fun isWhitelisted(domain: String): Boolean

    @Query("SELECT * FROM whitelist WHERE forceBlock = 0")
    suspend fun getAllSync(): List<WhitelistItem>

    // ← NOUVEAU : récupérer tous les forceBlock pour BlockListManager
    @Query("SELECT * FROM whitelist WHERE forceBlock = 1")
    suspend fun getAllForceBlocked(): List<WhitelistItem>

    @Query("DELETE FROM whitelist WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)

    @Query("DELETE FROM whitelist")
    suspend fun deleteAll()

    @Query("DELETE FROM whitelist WHERE forceBlock = 1")
    suspend fun deleteAllForceBlocked()
}