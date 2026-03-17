package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ScheduleDao {

    @Query("SELECT * FROM schedules ORDER BY startTimeMinutes ASC")
    fun getAllSchedules(): LiveData<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE isEnabled = 1 ORDER BY startTimeMinutes ASC")
    fun getEnabledSchedules(): LiveData<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE isEnabled = 1")
    suspend fun getEnabledSchedulesList(): List<Schedule>

    @Query("SELECT * FROM schedules WHERE id = :id")
    suspend fun getScheduleById(id: Long): Schedule?

    @Insert
    suspend fun insert(schedule: Schedule): Long

    @Update
    suspend fun update(schedule: Schedule)

    @Delete
    suspend fun delete(schedule: Schedule)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE schedules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)
}