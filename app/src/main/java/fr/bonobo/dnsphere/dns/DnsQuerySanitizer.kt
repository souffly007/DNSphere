// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.dns

import java.io.ByteArrayOutputStream

/**
 * Réduit ce que le résolveur (et les serveurs faisant autorité derrière lui)
 * peut apprendre de chaque requête :
 *
 *  - EDNS(0) Padding (RFC 7830 / RFC 8467) : la requête est complétée à un
 *    multiple de 128 octets, ce qui masque la longueur du nom demandé à un
 *    observateur du canal chiffré (DoH/DoT/DoQ/DoH3).
 *  - EDNS Client Subnet (RFC 7871) : une option ECS avec "source prefix-length"
 *    de 0 demande au résolveur de ne PAS transmettre le sous-réseau du client.
 *    Toute option ECS ou Padding déjà présente est remplacée.
 *
 * Les autres options EDNS et les bits (DO, version, taille UDP) d'une requête
 * qui contenait déjà un OPT sont conservés. Si la requête n'est pas dans une
 * forme simple et sûre à réécrire, elle est transmise inchangée.
 *
 * Si l'OPT a été ajouté par nous (le client n'avait pas EDNS), [Prepared.restore]
 * le retire de la réponse pour que l'appelant reçoive ce qu'il attend (RFC 6891 §7).
 */
object DnsQuerySanitizer {

    const val PAD_BLOCK = 128
    private const val EDNS_UDP_SIZE = 1232
    private const val OPT_ECS = 8
    private const val OPT_PADDING = 12

    class Prepared internal constructor(
        val query: ByteArray,
        private val optAdded: Boolean
    ) {
        fun restore(response: ByteArray): ByteArray =
            if (optAdded) stripOpt(response) else response
    }

    fun prepare(query: ByteArray, enabled: Boolean): Prepared {
        if (!enabled) return Prepared(query, false)
        return try {
            rebuild(query) ?: Prepared(query, false)
        } catch (_: Exception) {
            Prepared(query, false)
        }
    }

    private fun rebuild(q: ByteArray): Prepared? {
        if (q.size < 12) return null
        if ((q[2].toInt() and 0x80) != 0) return null // QR=1 : ce n'est pas une requête
        val qd = DnsWire.u16(q, 4)
        val an = DnsWire.u16(q, 6)
        val ns = DnsWire.u16(q, 8)
        val ar = DnsWire.u16(q, 10)
        if (qd != 1 || an != 0 || ns != 0 || ar > 1) return null

        val nameEnd = DnsWire.skipName(q, 12)
        if (nameEnd < 0) return null
        val qEnd = nameEnd + 4
        if (qEnd > q.size) return null

        var udpSize = EDNS_UDP_SIZE
        val extFlags = ByteArray(4) // ext-rcode, version, flags (DO...)
        val kept = ByteArrayOutputStream()
        val optAdded: Boolean

        if (ar == 0) {
            if (qEnd != q.size) return null
            optAdded = true
        } else {
            var p = qEnd
            if (p + 11 > q.size) return null
            if (q[p].toInt() != 0 || DnsWire.u16(q, p + 1) != DnsWire.TYPE_OPT) return null
            udpSize = DnsWire.u16(q, p + 3)
            System.arraycopy(q, p + 5, extFlags, 0, 4)
            val rdLen = DnsWire.u16(q, p + 9)
            p += 11
            val end = p + rdLen
            if (end != q.size) return null
            while (p < end) {
                if (p + 4 > end) return null
                val code = DnsWire.u16(q, p)
                val len = DnsWire.u16(q, p + 2)
                if (p + 4 + len > end) return null
                if (code != OPT_ECS && code != OPT_PADDING) kept.write(q, p, 4 + len)
                p += 4 + len
            }
            optAdded = false
        }

        // ECS : famille IPv4, source prefix-length 0, scope 0, aucune adresse.
        val ecs = byteArrayOf(0, OPT_ECS.toByte(), 0, 4, 0, 1, 0, 0)
        val keptBytes = kept.toByteArray()
        val baseLen = qEnd + 11 + ecs.size + keptBytes.size
        val padHeader = 4
        val pad = (PAD_BLOCK - ((baseLen + padHeader) % PAD_BLOCK)) % PAD_BLOCK
        val rdLenNew = ecs.size + keptBytes.size + padHeader + pad

        val out = ByteArray(qEnd + 11 + rdLenNew)
        System.arraycopy(q, 0, out, 0, qEnd)
        DnsWire.putU16(out, 10, 1) // ARCOUNT = 1
        var p = qEnd
        out[p] = 0 // nom racine
        DnsWire.putU16(out, p + 1, DnsWire.TYPE_OPT)
        DnsWire.putU16(out, p + 3, udpSize)
        System.arraycopy(extFlags, 0, out, p + 5, 4)
        DnsWire.putU16(out, p + 9, rdLenNew)
        p += 11
        System.arraycopy(ecs, 0, out, p, ecs.size); p += ecs.size
        System.arraycopy(keptBytes, 0, out, p, keptBytes.size); p += keptBytes.size
        DnsWire.putU16(out, p, OPT_PADDING)
        DnsWire.putU16(out, p + 2, pad) // le reste du tableau est déjà à zéro
        return Prepared(out, optAdded)
    }

    /** Retire l'enregistrement OPT de la section additionnelle d'une réponse. */
    internal fun stripOpt(r: ByteArray): ByteArray {
        try {
            if (r.size < 12) return r
            val qd = DnsWire.u16(r, 4)
            val an = DnsWire.u16(r, 6)
            val ns = DnsWire.u16(r, 8)
            val ar = DnsWire.u16(r, 10)
            var p = 12
            repeat(qd) {
                p = DnsWire.skipName(r, p)
                if (p < 0) return r
                p += 4
            }
            var optStart = -1
            var optEnd = -1
            for (i in 0 until an + ns + ar) {
                val start = p
                p = DnsWire.skipName(r, p)
                if (p < 0 || p + 10 > r.size) return r
                val type = DnsWire.u16(r, p)
                val end = p + 10 + DnsWire.u16(r, p + 8)
                if (end > r.size) return r
                if (i >= an + ns && type == DnsWire.TYPE_OPT && r[start].toInt() == 0 && optStart < 0) {
                    optStart = start
                    optEnd = end
                }
                p = end
            }
            if (optStart < 0) return r
            val out = ByteArray(r.size - (optEnd - optStart))
            System.arraycopy(r, 0, out, 0, optStart)
            System.arraycopy(r, optEnd, out, optStart, r.size - optEnd)
            DnsWire.putU16(out, 10, ar - 1)
            return out
        } catch (_: Exception) {
            return r
        }
    }
}
