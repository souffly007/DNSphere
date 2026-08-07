// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class UserRule {

package fr.bonobo.dnsphere.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Règle utilisateur personnalisée.
 *
 * Deux formats supportés :
 *  - ADGUARD : syntaxe ||domaine^  ou  @@||domaine^  (allow)
 *  - REGEX   : expression régulière Java/Kotlin
 *
 * L'action est dérivée automatiquement à l'insertion par RulesEngine,
 * mais stockée ici pour un accès direct sans re-parsing.
 */
@Entity(tableName = "user_rules")
data class UserRule(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Texte brut de la règle tel que saisi par l'utilisateur */
    val pattern: String,

    /** ADGUARD ou REGEX */
    val type: RuleType,

    /** BLOCK ou ALLOW */
    val action: RuleAction,

    /** Règle active ou suspendue */
    val enabled: Boolean = true,

    /** Optionnel : commentaire affiché dans la liste */
    val comment: String = "",

    val createdAt: Long = System.currentTimeMillis()
)

enum class RuleType { ADGUARD, REGEX }
enum class RuleAction { BLOCK, ALLOW }