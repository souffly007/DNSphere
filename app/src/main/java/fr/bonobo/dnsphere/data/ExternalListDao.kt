package fr.bonobo.dnsphere.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ExternalListDao {

    // ==================== LISTS ====================

    @Query("SELECT * FROM external_lists ORDER BY category, name")
    fun getAllLists(): Flow<List<ExternalList>>

    @Query("SELECT * FROM external_lists WHERE enabled = 1")
    suspend fun getEnabledLists(): List<ExternalList>

    @Query("SELECT * FROM external_lists WHERE id = :id")
    suspend fun getListById(id: Int): ExternalList?

    @Query("SELECT * FROM external_lists WHERE url = :url LIMIT 1")
    suspend fun getListByUrl(url: String): ExternalList?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: ExternalList): Long

    @Update
    suspend fun updateList(list: ExternalList)

    @Delete
    suspend fun deleteList(list: ExternalList)

    @Query("UPDATE external_lists SET enabled = :enabled WHERE id = :id")
    suspend fun setListEnabled(id: Int, enabled: Boolean)

    @Query("UPDATE external_lists SET domainCount = :count, lastUpdated = :timestamp, lastError = null WHERE id = :id")
    suspend fun updateListStats(id: Int, count: Int, timestamp: Long)

    @Query("UPDATE external_lists SET lastError = :error WHERE id = :id")
    suspend fun setListError(id: Int, error: String)

    /**
     * Retourne tous les domaines des listes activées d'une catégorie donnée.
     * Utilisé par ParentalManager pour charger les domaines PARENTAL en mémoire.
     */
    @Query("""
        SELECT d.domain FROM external_list_domains d
        INNER JOIN external_lists l ON d.listId = l.id
        WHERE l.category = :category AND l.enabled = 1
    """)
    suspend fun getDomainsByCategory(category: ListCategory): List<String>

    /**
     * Retourne toutes les URLs de listes enregistrées (pour éviter les doublons).
     */
    @Query("SELECT url FROM external_lists")
    suspend fun getAllUrls(): List<String>

    /**
     * Réactive toutes les listes d'une catégorie (au cas où elles auraient été désactivées).
     */
    @Query("UPDATE external_lists SET enabled = 1 WHERE category = :category")
    suspend fun enableByCategory(category: ListCategory)


    // ==================== DOMAINS ====================

    @Query("SELECT domain FROM external_list_domains WHERE listId IN (SELECT id FROM external_lists WHERE enabled = 1)")
    suspend fun getAllEnabledDomains(): List<String>

    @Query("SELECT COUNT(*) FROM external_list_domains WHERE listId IN (SELECT id FROM external_lists WHERE enabled = 1)")
    suspend fun getEnabledDomainCount(): Int

    @Query("DELETE FROM external_list_domains WHERE listId = :listId")
    suspend fun clearDomainsForList(listId: Int)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDomains(domains: List<ExternalListDomain>)

    @Query("SELECT EXISTS(SELECT 1 FROM external_list_domains WHERE domain = :domain AND listId IN (SELECT id FROM external_lists WHERE enabled = 1))")
    suspend fun isDomainBlocked(domain: String): Boolean

    // ==================== STATS ====================

    @Query("SELECT SUM(domainCount) FROM external_lists WHERE enabled = 1")
    suspend fun getTotalEnabledDomains(): Int?

    @Query("SELECT COUNT(*) FROM external_lists WHERE enabled = 1")
    suspend fun getEnabledListCount(): Int

    // ========== AJOUTÉ ==========
    @Query("SELECT * FROM external_lists")
    suspend fun getAllListsSync(): List<ExternalList>

    @Query("UPDATE external_lists SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabledByUrl(url: String, enabled: Boolean)

    @Query("SELECT EXISTS(SELECT 1 FROM external_lists WHERE url = :url)")
    suspend fun existsByUrl(url: String): Boolean

    @Query("SELECT * FROM external_list_domains WHERE listId = :listId")
    suspend fun getDomainsForList(listId: Int): List<ExternalListDomain>
}