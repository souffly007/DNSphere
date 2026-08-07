// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Profilescheduledao {
package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface ProfileScheduleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(schedule: ProfileSchedule): Long

    @Update
    suspend fun update(schedule: ProfileSchedule)

    @Delete
    suspend fun delete(schedule: ProfileSchedule)

    @Query("DELETE FROM profile_schedules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM profile_schedules ORDER BY startHour, startMinute")
    fun getAll(): LiveData<List<ProfileSchedule>>

    @Query("SELECT * FROM profile_schedules ORDER BY startHour, startMinute")
    suspend fun getAllSync(): List<ProfileSchedule>

    @Query("SELECT * FROM profile_schedules WHERE profileId = :profileId ORDER BY startHour, startMinute")
    fun getForProfile(profileId: Long): LiveData<List<ProfileSchedule>>

    @Query("SELECT * FROM profile_schedules WHERE profileId = :profileId ORDER BY startHour, startMinute")
    suspend fun getForProfileSync(profileId: Long): List<ProfileSchedule>

    @Query("SELECT * FROM profile_schedules WHERE enabled = 1 ORDER BY startHour, startMinute")
    suspend fun getAllEnabled(): List<ProfileSchedule>

    @Query("UPDATE profile_schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM profile_schedules WHERE profileId = :profileId")
    suspend fun deleteAllForProfile(profileId: Long)
}