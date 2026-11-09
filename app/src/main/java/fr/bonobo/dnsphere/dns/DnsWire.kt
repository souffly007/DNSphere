// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.dns

import java.net.InetAddress
import java.security.SecureRandom

/**
 * Utilitaires purs (sans Android) pour lire et construire des messages DNS
 * au format "wire". Partagés par [DnsQuerySanitizer] et [DohBootstrap].
 */
internal object DnsWire {

    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_OPT = 41

    private val random = SecureRandom()

    fun u16(b: ByteArray, p: Int): Int =
        ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)

    fun putU16(b: ByteArray, p: Int, v: Int) {
        b[p] = ((v shr 8) and 0xFF).toByte()
        b[p + 1] = (v and 0xFF).toByte()
    }

    /**
     * Retourne la position qui suit un nom DNS commençant en [start]
     * (gère les pointeurs de compression), ou -1 si le message est invalide.
     */
    fun skipName(b: ByteArray, start: Int): Int {
        var p = start
        while (p < b.size) {
            val len = b[p].toInt() and 0xFF
            when {
                len == 0 -> return p + 1
                (len and 0xC0) == 0xC0 -> return if (p + 2 <= b.size) p + 2 else -1
                (len and 0xC0) != 0 -> return -1
                else -> p += 1 + len
            }
        }
        return -1
    }

    /** Construit une requête (RD=1, sans EDNS) pour [host] avec un ID aléatoire. */
    fun buildQuery(host: String, type: Int): ByteArray {
        val labels = host.trimEnd('.').split('.')
        require(labels.isNotEmpty() && labels.all { it.length in 1..63 && it.all { c -> c.code < 128 } }) {
            "Nom d'hôte invalide: $host"
        }
        val nameLen = labels.sumOf { it.length + 1 } + 1
        val out = ByteArray(12 + nameLen + 4)
        val id = random.nextInt(0x10000)
        putU16(out, 0, id)
        out[2] = 0x01 // RD
        putU16(out, 4, 1) // QDCOUNT
        var p = 12
        for (label in labels) {
            out[p++] = label.length.toByte()
            for (c in label) out[p++] = c.code.toByte()
        }
        out[p++] = 0
        putU16(out, p, type)
        putU16(out, p + 2, 1) // IN
        return out
    }

    class Addresses(val list: List<InetAddress>, val minTtlSeconds: Long)

    /**
     * Extrait les enregistrements A/AAAA ([qtype]) d'une réponse. Retourne une
     * liste vide si l'ID ne correspond pas, si RCODE != 0 ou si le message est invalide.
     */
    fun parseAddresses(response: ByteArray, expectedId: Int, qtype: Int): Addresses {
        val empty = Addresses(emptyList(), 0)
        try {
            if (response.size < 12) return empty
            if (u16(response, 0) != expectedId) return empty
            if ((response[2].toInt() and 0x80) == 0) return empty // pas une réponse
            if ((response[3].toInt() and 0x0F) != 0) return empty // RCODE
            val qd = u16(response, 4)
            val an = u16(response, 6)
            var p = 12
            repeat(qd) {
                p = skipName(response, p)
                if (p < 0) return empty
                p += 4
            }
            val found = ArrayList<InetAddress>()
            var minTtl = Long.MAX_VALUE
            repeat(an) {
                p = skipName(response, p)
                if (p < 0 || p + 10 > response.size) return empty
                val type = u16(response, p)
                val ttl = ((u16(response, p + 4).toLong()) shl 16) or u16(response, p + 6).toLong()
                val rdLen = u16(response, p + 8)
                val rdStart = p + 10
                if (rdStart + rdLen > response.size) return empty
                val expected = if (qtype == TYPE_A) 4 else 16
                if (type == qtype && rdLen == expected) {
                    found.add(InetAddress.getByAddress(response.copyOfRange(rdStart, rdStart + rdLen)))
                    if (ttl < minTtl) minTtl = ttl
                }
                p = rdStart + rdLen
            }
            return Addresses(found, if (found.isEmpty()) 0 else minTtl)
        } catch (_: Exception) {
            return empty
        }
    }
}
