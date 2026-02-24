package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ExcludedAppDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: ExcludedApp)

    @Delete
    suspend fun delete(app: ExcludedApp)

    @Query("SELECT * FROM excluded_apps ORDER BY appName")
    fun getAll(): LiveData<List<ExcludedApp>>

    @Query("SELECT * FROM excluded_apps")
    suspend fun getAllSync(): List<ExcludedApp>

    @Query("SELECT packageName FROM excluded_apps")
    suspend fun getAllPackageNames(): List<String>

    @Query("DELETE FROM excluded_apps WHERE packageName = :packageName")
    suspend fun deleteByPackage(packageName: String)

    @Query("SELECT EXISTS(SELECT 1 FROM excluded_apps WHERE packageName = :packageName)")
    suspend fun isExcluded(packageName: String): Boolean
}