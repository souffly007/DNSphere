// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Apprule {
package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Règle DNS appliquée à une application spécifique.
 * Identifiée par son UID Android (stable, contrairement au packageName).
 */
@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey
    val uid: Int,
    val packageName: String,
    val appName: String,
    val rule: AppRuleType,
    val customDns: String = "",   // Utilisé si rule == CUSTOM_DNS
    val createdAt: Long = System.currentTimeMillis()
)

enum class AppRuleType {
    /** Comportement normal — filtrage standard DNSphere */
    DEFAULT,
    /** Tout bloquer pour cette app */
    BLOCK_ALL,
    /** Laisser passer sans aucun filtrage */
    ALLOW_ALL,
    /** Utiliser un resolver DNS spécifique pour cette app */
    CUSTOM_DNS
}