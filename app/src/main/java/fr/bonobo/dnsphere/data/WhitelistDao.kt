package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface WhitelistDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WhitelistItem)

    @Delete
    suspend fun delete(item: WhitelistItem)

    @Query("SELECT * FROM whitelist ORDER BY addedAt DESC")
    fun getAll(): LiveData<List<WhitelistItem>>

    @Query("SELECT EXISTS(SELECT 1 FROM whitelist WHERE domain = :domain)")
    suspend fun isWhitelisted(domain: String): Boolean

    @Query("SELECT * FROM whitelist")
    suspend fun getAllSync(): List<WhitelistItem>

    @Query("DELETE FROM whitelist WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)
}