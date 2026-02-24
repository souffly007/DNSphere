package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface CustomListDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: CustomList): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDomains(domains: List<CustomDomain>)

    @Update
    suspend fun updateList(list: CustomList)

    @Delete
    suspend fun deleteList(list: CustomList)

    @Query("DELETE FROM custom_domains WHERE listId = :listId")
    suspend fun deleteDomainsForList(listId: Long)

    @Query("SELECT * FROM custom_lists ORDER BY name")
    fun getAllLists(): LiveData<List<CustomList>>

    @Query("SELECT * FROM custom_lists WHERE enabled = 1")
    suspend fun getEnabledLists(): List<CustomList>

    @Query("SELECT domain FROM custom_domains WHERE listId IN (SELECT id FROM custom_lists WHERE enabled = 1)")
    suspend fun getAllEnabledDomains(): List<String>

    @Query("SELECT domain FROM custom_domains WHERE listId = :listId")
    suspend fun getDomainsForList(listId: Long): List<String>

    @Query("SELECT COUNT(*) FROM custom_domains WHERE listId = :listId")
    suspend fun getDomainCountForList(listId: Long): Int

    @Query("UPDATE custom_lists SET enabled = :enabled WHERE id = :listId")
    suspend fun setListEnabled(listId: Long, enabled: Boolean)
}