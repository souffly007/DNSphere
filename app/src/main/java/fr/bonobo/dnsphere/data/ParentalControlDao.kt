package fr.bonobo.dnsphere.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ParentalControlDao {

    @Query("SELECT * FROM parental_control WHERE id = 1")
    fun observe(): Flow<ParentalControl?>          // observe les changements en temps réel

    @Query("SELECT * FROM parental_control WHERE id = 1")
    suspend fun get(): ParentalControl?            // lecture ponctuelle (coroutine)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: ParentalControl)

    @Query("DELETE FROM parental_control")
    suspend fun reset()
}
