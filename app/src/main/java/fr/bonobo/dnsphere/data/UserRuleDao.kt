// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class UserRuleDao {

package fr.bonobo.dnsphere.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserRuleDao {

    // ── Lecture ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM user_rules ORDER BY createdAt DESC")
    fun getAllRules(): Flow<List<UserRule>>

    @Query("SELECT * FROM user_rules WHERE enabled = 1 ORDER BY createdAt DESC")
    suspend fun getEnabledRules(): List<UserRule>

    @Query("SELECT * FROM user_rules WHERE id = :id")
    suspend fun getById(id: Long): UserRule?

    // ── Écriture ─────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: UserRule): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<UserRule>)

    @Update
    suspend fun update(rule: UserRule)

    @Delete
    suspend fun delete(rule: UserRule)

    @Query("DELETE FROM user_rules WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE user_rules SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Query("DELETE FROM user_rules")
    suspend fun deleteAll()

    // ── Stats ─────────────────────────────────────────────────────────────────

    @Query("SELECT COUNT(*) FROM user_rules WHERE enabled = 1")
    suspend fun countEnabled(): Int

    @Query("SELECT COUNT(*) FROM user_rules WHERE action = 'BLOCK' AND enabled = 1")
    suspend fun countEnabledBlock(): Int

    @Query("SELECT COUNT(*) FROM user_rules WHERE action = 'ALLOW' AND enabled = 1")
    suspend fun countEnabledAllow(): Int
}