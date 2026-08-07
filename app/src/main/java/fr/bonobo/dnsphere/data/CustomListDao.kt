package fr.bonobo.dnsphere.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomListDao {

    @Query("SELECT * FROM custom_lists ORDER BY name")
    fun getAllLists(): Flow<List<CustomList>>

    @Query("SELECT * FROM custom_lists WHERE enabled = 1")
    suspend fun getEnabledLists(): List<CustomList>

    @Query("SELECT * FROM custom_lists WHERE id = :id")
    suspend fun getListById(id: Long): CustomList?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: CustomList): Long

    @Update
    suspend fun updateList(list: CustomList)

    @Delete
    suspend fun deleteList(list: CustomList)

    @Query("UPDATE custom_lists SET enabled = :enabled WHERE id = :id")
    suspend fun setListEnabled(id: Long, enabled: Boolean)

    @Query("UPDATE custom_lists SET domainCount = :count WHERE id = :id")
    suspend fun updateDomainCount(id: Long, count: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDomains(domains: List<CustomListDomain>)

    @Query("DELETE FROM custom_list_domains WHERE listId = :listId")
    suspend fun deleteDomainsForList(listId: Long)

    @Query("SELECT domain FROM custom_list_domains WHERE listId IN (SELECT id FROM custom_lists WHERE enabled = 1)")
    suspend fun getAllEnabledDomains(): List<String>

    // Retourne uniquement les noms de domaines (String)
    @Query("SELECT domain FROM custom_list_domains WHERE listId = :listId")
    suspend fun getDomainNamesForList(listId: Long): List<String>

    // Retourne les objets CustomListDomain complets
    @Query("SELECT * FROM custom_list_domains WHERE listId = :listId")
    suspend fun getDomainEntitiesForList(listId: Long): List<CustomListDomain>

    @Query("SELECT COUNT(*) FROM custom_list_domains WHERE listId = :listId")
    suspend fun getDomainCountForList(listId: Long): Int

    @Query("DELETE FROM custom_lists")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDomain(domain: CustomListDomain): Long
}