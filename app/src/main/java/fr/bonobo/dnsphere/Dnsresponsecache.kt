// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.dns

/**
 * Cache DNS respectant le vrai TTL des réponses (RFC 1035 §3.2.1 : la durée de
 * cache = le plus petit TTL parmi les enregistrements de la section Answer).
 *
 * Ni négatif (les NXDOMAIN/erreurs ne sont pas mis en cache ici — pas de
 * negative caching pour rester simple), ni persistant (mémoire uniquement,
 * vidé au redémarrage du service : cohérent, les IPs changent et il n'y a
 * pas d'intérêt à survivre à un redémarrage pour un cache DNS).
 *
 * Borné en taille (LRU via LinkedHashMap en mode accessOrder) pour éviter
 * une croissance mémoire illimitée sur un appareil mobile.
 */
class DnsResponseCache(private val maxEntries: Int = 3000) {

    private data class Entry(val payload: ByteArray, val expiresAt: Long)

    private val cache = object : LinkedHashMap<String, Entry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > maxEntries
        }
    }

    companion object {
        // Plancher : évite un cache quasi inutile sur des TTL très courts (certains
        // CDN renvoient des TTL de 1s, ce qui annulerait presque tout le bénéfice).
        private const val MIN_TTL_SECONDS = 5L
        // Plafond : évite qu'un TTL mal configuré ou abusif fige une IP pendant des heures
        // (protection contre le "DNS pinning" trop agressif, notamment côté sécurité).
        private const val MAX_TTL_SECONDS = 3600L
    }

    private var hits = 0
    private var misses = 0

    @Synchronized
    fun get(domain: String, qtype: Int): ByteArray? {
        val key = "$domain|$qtype"
        val entry = cache[key]
        if (entry == null) { misses++; return null }
        if (System.currentTimeMillis() > entry.expiresAt) {
            cache.remove(key)
            misses++
            return null
        }
        hits++
        return entry.payload
    }

    @Synchronized
    fun put(domain: String, qtype: Int, dnsPayload: ByteArray, ttlSeconds: Int) {
        if (ttlSeconds <= 0) return // TTL=0 (ou parsing invalide) : ne pas cacher
        val clampedTtl = ttlSeconds.toLong().coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
        cache["$domain|$qtype"] = Entry(dnsPayload.copyOf(), System.currentTimeMillis() + clampedTtl * 1000)
    }

    /** Vide le cache — à appeler quand le provider DNS change (résultats potentiellement différents). */
    @Synchronized
    fun clear() = cache.clear()

    @Synchronized
    fun size(): Int = cache.size

    @Synchronized
    fun stats(): String {
        val total = hits + misses
        val hitRate = if (total > 0) (hits * 100 / total) else 0
        return "hits=$hits misses=$misses hitRate=$hitRate% size=${cache.size}/$maxEntries"
    }
}