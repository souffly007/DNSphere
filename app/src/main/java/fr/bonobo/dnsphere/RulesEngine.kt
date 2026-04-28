// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class RulesEngine {

package fr.bonobo.dnsphere

import android.util.Log
import fr.bonobo.dnsphere.data.RuleAction
import fr.bonobo.dnsphere.data.RuleType
import fr.bonobo.dnsphere.data.UserRule

/**
 * RulesEngine — parsing et évaluation des règles utilisateur.
 *
 * ── Formats supportés ────────────────────────────────────────────────────────
 *
 * AdGuard / uBlock :
 *   ||ads.example.com^          → BLOCK  ads.example.com et tous ses sous-domaines
 *   @@||safe.example.com^       → ALLOW  (whitelist)
 *   ||*.tracker.net^            → BLOCK  avec wildcard (*) en préfixe de sous-domaine
 *   # commentaire               → ignoré
 *   ! commentaire               → ignoré
 *
 * Regex (préfixe `regex:`) :
 *   regex:.*\.ad\..*            → BLOCK
 *   regex:@@.*\.safe\..*        → ALLOW  (@@ devant regex: aussi)
 *
 * ── Priorité dans BlockListManager ──────────────────────────────────────────
 *   forceBlock > neverBlock > USER_ALLOW > USER_BLOCK > whitelist > listes
 */
object RulesEngine {

    private const val TAG = "RulesEngine"

    // ── Parsing ───────────────────────────────────────────────────────────────

    /**
     * Parse une ligne brute saisie par l'utilisateur.
     * Retourne null si la ligne est vide, un commentaire, ou invalide.
     */
    fun parseLine(raw: String): UserRule? {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) return null

        return when {
            // Regex explicite : regex:pattern  ou  @@regex:pattern
            line.startsWith("@@regex:") -> {
                val pattern = line.removePrefix("@@regex:")
                if (!isValidRegex(pattern)) {
                    Log.w(TAG, "Regex invalide ignorée : $pattern")
                    return null
                }
                UserRule(pattern = pattern, type = RuleType.REGEX, action = RuleAction.ALLOW)
            }
            line.startsWith("regex:") -> {
                val pattern = line.removePrefix("regex:")
                if (!isValidRegex(pattern)) {
                    Log.w(TAG, "Regex invalide ignorée : $pattern")
                    return null
                }
                UserRule(pattern = pattern, type = RuleType.REGEX, action = RuleAction.BLOCK)
            }

            // AdGuard ALLOW : @@||domaine^
            line.startsWith("@@||") -> {
                val domain = extractAdguardDomain(line.removePrefix("@@"))
                    ?: return null
                UserRule(pattern = domain, type = RuleType.ADGUARD, action = RuleAction.ALLOW)
            }

            // AdGuard BLOCK : ||domaine^
            line.startsWith("||") -> {
                val domain = extractAdguardDomain(line) ?: return null
                UserRule(pattern = domain, type = RuleType.ADGUARD, action = RuleAction.BLOCK)
            }

            // Domaine brut : on l'accepte comme ADGUARD BLOCK implicite
            // ex: "ads.example.com" → identique à ||ads.example.com^
            isPlainDomain(line) -> {
                UserRule(pattern = line.lowercase(), type = RuleType.ADGUARD, action = RuleAction.BLOCK)
            }

            else -> {
                Log.w(TAG, "Ligne non reconnue ignorée : $line")
                null
            }
        }
    }

    /**
     * Parse un bloc de texte multi-lignes (éditeur brut).
     * Retourne la liste des règles valides et le nombre de lignes ignorées.
     */
    fun parseTextBlock(text: String): ParseResult {
        val rules = mutableListOf<UserRule>()
        var ignored = 0
        text.lines().forEach { line ->
            val rule = parseLine(line)
            if (rule != null) rules.add(rule) else if (line.trim().isNotEmpty() && !line.trim().startsWith("#") && !line.trim().startsWith("!")) ignored++
        }
        return ParseResult(rules, ignored)
    }

    data class ParseResult(val rules: List<UserRule>, val ignoredLines: Int)

    // ── Sérialisation (pour l'onglet texte brut) ──────────────────────────────

    /**
     * Convertit une liste de règles en texte brut pour l'éditeur.
     */
    fun rulesToText(rules: List<UserRule>): String {
        return rules.joinToString("\n") { rule ->
            buildString {
                if (rule.comment.isNotBlank()) append("# ${rule.comment}\n")
                when (rule.type) {
                    RuleType.ADGUARD -> {
                        if (rule.action == RuleAction.ALLOW) append("@@")
                        append("||${rule.pattern}^")
                    }
                    RuleType.REGEX -> {
                        if (rule.action == RuleAction.ALLOW) append("@@")
                        append("regex:${rule.pattern}")
                    }
                }
            }
        }
    }

    // ── Matching ──────────────────────────────────────────────────────────────

    /**
     * Résultat de l'évaluation d'un hostname contre les règles utilisateur.
     */
    enum class MatchResult { ALLOW, BLOCK, NO_MATCH }

    /**
     * Évalue un hostname contre une liste de règles activées.
     *
     * Les règles ALLOW ont priorité sur les règles BLOCK si elles matchent.
     * On évalue dans l'ordre : d'abord tous les ALLOW, ensuite tous les BLOCK.
     */
    fun evaluate(hostname: String, rules: List<UserRule>): MatchResult {
        val domain = hostname.lowercase()
        val enabled = rules.filter { it.enabled }

        // 1. Une règle ALLOW qui match → autoriser immédiatement
        for (rule in enabled.filter { it.action == RuleAction.ALLOW }) {
            if (matches(domain, rule)) return MatchResult.ALLOW
        }

        // 2. Une règle BLOCK qui match → bloquer
        for (rule in enabled.filter { it.action == RuleAction.BLOCK }) {
            if (matches(domain, rule)) return MatchResult.BLOCK
        }

        return MatchResult.NO_MATCH
    }

    // ── Helpers privés ────────────────────────────────────────────────────────

    private fun matches(domain: String, rule: UserRule): Boolean {
        return when (rule.type) {
            RuleType.ADGUARD -> matchesAdguard(domain, rule.pattern)
            RuleType.REGEX   -> matchesRegex(domain, rule.pattern)
        }
    }

    /**
     * Match AdGuard :
     *  - pattern exact  : "ads.example.com"  → match ads.example.com
     *  - sous-domaine   : "example.com"       → match *.example.com
     *  - wildcard *     : "*.tracker.net"     → match tout sous-domaine de tracker.net
     */
    private fun matchesAdguard(domain: String, pattern: String): Boolean {
        val p = pattern.lowercase().trimEnd('.', '^')
        return when {
            p.startsWith("*.") -> {
                val base = p.removePrefix("*.")
                domain.endsWith(".$base")
            }
            else -> domain == p || domain.endsWith(".$p")
        }
    }

    private fun matchesRegex(domain: String, pattern: String): Boolean {
        return try {
            Regex(pattern).containsMatchIn(domain)
        } catch (e: Exception) {
            false
        }
    }

    private fun extractAdguardDomain(line: String): String? {
        // ||domaine^  ou  ||domaine^$options (on ignore les options)
        val raw = line.removePrefix("||").substringBefore("^").substringBefore("$").trim()
        if (raw.isEmpty()) return null
        return raw.lowercase()
    }

    private fun isPlainDomain(line: String): Boolean {
        return Regex("^[a-zA-Z0-9*][a-zA-Z0-9.*\\-]*\\.[a-zA-Z]{2,}$").matches(line)
    }

    private fun isValidRegex(pattern: String): Boolean {
        return try {
            Regex(pattern); true
        } catch (e: Exception) {
            false
        }
    }
}
