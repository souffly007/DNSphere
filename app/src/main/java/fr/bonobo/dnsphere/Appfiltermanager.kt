// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Appfiltermanager {
package fr.bonobo.dnsphere

import android.content.Context
import android.util.Log
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.AppRule
import fr.bonobo.dnsphere.data.AppRuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.FileReader

/**
 * Filtre DNS par application.
 *
 * Principe :
 * 1. Extraire l'adresse IP source + port source du paquet UDP
 * 2. Chercher dans /proc/net/udp l'entrée correspondante → obtenir l'UID
 * 3. Chercher la règle AppRule pour cet UID
 * 4. Retourner l'action à appliquer
 */
class AppFilterManager(private val context: Context) {

    companion object {
        private const val TAG = "AppFilterManager"
        private const val PROC_UDP  = "/proc/net/udp"
        private const val PROC_UDP6 = "/proc/net/udp6"
    }

    private val database = AppDatabase.getInstance(context)

    // Cache des règles en mémoire — rechargé à chaque changement
    @Volatile private var rulesCache: Map<Int, AppRule> = emptyMap()

    /**
     * Charge toutes les règles en cache (à appeler au démarrage et après chaque modif).
     */
    suspend fun loadRules() {
        withContext(Dispatchers.IO) {
            val rules = database.appRuleDao().getAllSync()
            rulesCache = rules.associateBy { it.uid }
            Log.d(TAG, "✅ ${rules.size} règles chargées en cache")
        }
    }

    /**
     * Détermine l'action à appliquer pour un paquet DNS donné.
     *
     * @param ipPacket Le paquet IP complet
     * @return L'AppRule correspondante, ou null si aucune règle (comportement par défaut)
     */
    fun getRuleForPacket(ipPacket: ByteArray): AppRule? {
        return try {
            val srcIp   = extractSrcIp(ipPacket)
            val srcPort = extractSrcPort(ipPacket)
            val uid     = findUidInProc(srcIp, srcPort) ?: return null
            rulesCache[uid]
        } catch (e: Exception) {
            Log.w(TAG, "Erreur lecture règle: ${e.message}")
            null
        }
    }

    /**
     * Extrait l'IP source du paquet IP (octets 12-15)
     */
    private fun extractSrcIp(packet: ByteArray): String {
        val a = packet[12].toInt() and 0xFF
        val b = packet[13].toInt() and 0xFF
        val c = packet[14].toInt() and 0xFF
        val d = packet[15].toInt() and 0xFF
        return "$a.$b.$c.$d"
    }

    /**
     * Extrait le port source UDP (après l'en-tête IP)
     */
    private fun extractSrcPort(packet: ByteArray): Int {
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        return ((packet[ipHeaderLength].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 1].toInt() and 0xFF)
    }

    /**
     * Cherche l'UID dans /proc/net/udp en comparant IP:port source.
     *
     * Format de /proc/net/udp :
     * sl  local_address rem_address st tx_queue rx_queue tr tm->when retrnsmt uid ...
     * 0:  0F02000A:0035 00000000:0000 07 ...     uid=10123
     *
     * local_address = IP en little-endian hex : port en hex
     * ex: 0F02000A = 10.0.2.15, port 0035 = 53
     */
    private fun findUidInProc(srcIp: String, srcPort: Int): Int? {
        val hexAddr = ipToHexLE(srcIp)
        val hexPort = String.format("%04X", srcPort)
        val target  = "$hexAddr:$hexPort"

        return readProcUdp(PROC_UDP, target)
            ?: readProcUdp(PROC_UDP6, target)
    }

    private fun readProcUdp(path: String, target: String): Int? {
        return try {
            BufferedReader(FileReader(path)).use { reader ->
                reader.readLine() // Skip header
                reader.lineSequence().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 8) {
                        val localAddr = parts[1].uppercase()
                        if (localAddr == target) {
                            return parts[7].toIntOrNull()
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            null // Fichier non lisible sur certains devices
        }
    }

    /**
     * Convertit une IP en notation décimale vers hex little-endian.
     * ex: "10.0.2.15" → "0F02000A"
     */
    private fun ipToHexLE(ip: String): String {
        val parts = ip.split(".").map { it.toInt() }
        return String.format("%02X%02X%02X%02X",
            parts[3], parts[2], parts[1], parts[0]
        )
    }

    // =========================================================================
    // CRUD RÈGLES
    // =========================================================================

    suspend fun setRule(rule: AppRule) {
        withContext(Dispatchers.IO) {
            database.appRuleDao().insert(rule)
            loadRules() // Recharger le cache
            Log.d(TAG, "✅ Règle ajoutée: ${rule.appName} → ${rule.rule}")
        }
    }

    suspend fun removeRule(uid: Int) {
        withContext(Dispatchers.IO) {
            database.appRuleDao().deleteByUid(uid)
            loadRules()
            Log.d(TAG, "🗑️ Règle supprimée: uid=$uid")
        }
    }

    suspend fun getRuleForUid(uid: Int): AppRule? {
        return database.appRuleDao().getByUid(uid)
    }

    fun getRuleTypeLabel(type: AppRuleType): String = when (type) {
        AppRuleType.DEFAULT    -> "Par défaut"
        AppRuleType.BLOCK_ALL  -> "Tout bloquer"
        AppRuleType.ALLOW_ALL  -> "Tout autoriser"
        AppRuleType.CUSTOM_DNS -> "DNS personnalisé"
    }
}