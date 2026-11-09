// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.dns

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Résout le nom d'un résolveur DoH (ex. sky.rethinkdns.com) SANS passer par le DNS
 * système. DNSphere étant exclu de son propre tunnel VPN, une résolution système
 * partirait en clair sur le réseau local et révélerait quel résolveur est utilisé.
 *
 * La requête est envoyée en DoH vers des IP littérales dont le certificat porte
 * l'IP en SAN (Cloudflare 1.1.1.1) : aucune résolution préalable.
 * Ces deux services voient donc le NOM du résolveur, jamais les sites visités.
 */
object DohBootstrap {

    private const val TAG = "DohBootstrap"
    private val ENDPOINTS = listOf("https://1.1.1.1/dns-query")
    private val DNS_MESSAGE = "application/dns-message".toMediaType()

    private const val MIN_TTL_S = 60L
    private const val MAX_TTL_S = 600L

    private class Entry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    // Les hôtes sont des IP littérales : pas de Dns personnalisé nécessaire.
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    /**
     * @param allowSystemFallback si vrai et que le bootstrap chiffré échoue, on
     * retombe sur le DNS système (fuite possible du nom du résolveur). En mode strict : faux.
     */
    @Throws(UnknownHostException::class)
    fun lookup(host: String, allowSystemFallback: Boolean): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache[host]?.let { if (it.expiresAtMs > now) return it.addresses }

        for (type in intArrayOf(DnsWire.TYPE_A, DnsWire.TYPE_AAAA)) {
            val result = queryAny(host, type)
            if (result != null && result.list.isNotEmpty()) {
                val ttl = result.minTtlSeconds.coerceIn(MIN_TTL_S, MAX_TTL_S)
                cache[host] = Entry(result.list, now + ttl * 1000)
                return result.list
            }
        }

        if (allowSystemFallback) {
            Log.w(TAG, "Bootstrap chiffré impossible pour $host — repli DNS système")
            return okhttp3.Dns.SYSTEM.lookup(host)
        }
        throw UnknownHostException("Bootstrap DoH impossible pour $host (mode strict)")
    }

    private fun queryAny(host: String, type: Int): DnsWire.Addresses? {
        val query = try {
            DnsWire.buildQuery(host, type)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val id = DnsWire.u16(query, 0)
        for (endpoint in ENDPOINTS) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("Accept", "application/dns-message")
                    .post(query.toRequestBody(DNS_MESSAGE))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            val parsed = DnsWire.parseAddresses(bytes, id, type)
                            if (parsed.list.isNotEmpty()) return parsed
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bootstrap via $endpoint échoué: ${e.message}")
            }
        }
        return null
    }
}
