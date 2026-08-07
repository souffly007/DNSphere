// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Appruledao {

package fr.bonobo.dnsphere.data

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface AppRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: AppRule)

    @Delete
    suspend fun delete(rule: AppRule)

    @Query("DELETE FROM app_rules WHERE uid = :uid")
    suspend fun deleteByUid(uid: Int)

    @Query("SELECT * FROM app_rules ORDER BY appName")
    fun getAll(): LiveData<List<AppRule>>

    @Query("SELECT * FROM app_rules")
    suspend fun getAllSync(): List<AppRule>

    @Query("SELECT * FROM app_rules WHERE uid = :uid LIMIT 1")
    suspend fun getByUid(uid: Int): AppRule?

    @Query("SELECT * FROM app_rules WHERE packageName = :packageName LIMIT 1")
    suspend fun getByPackage(packageName: String): AppRule?

    @Query("SELECT EXISTS(SELECT 1 FROM app_rules WHERE uid = :uid)")
    suspend fun hasRule(uid: Int): Boolean
}
