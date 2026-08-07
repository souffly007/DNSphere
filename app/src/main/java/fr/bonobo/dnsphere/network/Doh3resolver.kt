// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Doh3resolver {
package fr.bonobo.dnsphere.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UploadDataProvider
import org.chromium.net.UploadDataSink
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import fr.bonobo.dnsphere.network.ByteArrayUploadProvider

/**
 * DNS over HTTP/3 (DoH3) Resolver
 * Utilise Cronet avec HTTP/3 (QUIC) forcé.
 * Fallback automatique vers DoH classique (HTTP/2) si HTTP/3 indisponible.
 *
 * Différence avec DoqResolver :
 * - DoQ  = DNS wire format directement sur QUIC (port 853)
 * - DoH3 = DNS wire format dans HTTP/3 sur QUIC (port 443) — même endpoint que DoH
 */
class Doh3Resolver(private val context: Context) {

    companion object {
        private const val TAG = "Doh3Resolver"

        val PROVIDERS = mapOf(
            "cloudflare" to "https://cloudflare-dns.com/dns-query",
            "google"     to "https://dns.google/dns-query",
            "quad9"      to "https://dns.quad9.net/dns-query",
            "adguard"    to "https://dns.adguard.com/dns-query"
        )
    }

    private var currentProviderName = "cloudflare"
    private var currentUrl          = PROVIDERS["cloudflare"]!!

    private val executor = Executors.newCachedThreadPool()

    // Cronet avec HTTP/3 forcé — on hint les domaines connus pour QUIC
    private val cronetEngine: CronetEngine by lazy {
        CronetEngine.Builder(context)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            // Hints QUIC : indique à Cronet que ces serveurs supportent HTTP/3
            // sans attendre la négociation Alt-Svc du premier échange
            .addQuicHint("cloudflare-dns.com", 443, 443)
            .addQuicHint("dns.google",          443, 443)
            .addQuicHint("dns.quad9.net",        443, 443)
            .addQuicHint("dns.adguard.com",      443, 443)
            .build()
    }

    fun setProvider(provider: String) {
        val normalized = provider.lowercase().trim()
        if (PROVIDERS.containsKey(normalized)) {
            currentProviderName = normalized
            currentUrl          = PROVIDERS[normalized]!!
            Log.d(TAG, "🔄 Provider changé: $currentProviderName → $currentUrl")
        } else {
            Log.w(TAG, "⚠️ Provider inconnu ignoré: $provider")
        }
    }

    fun getProviderName(): String = when (currentProviderName) {
        "cloudflare" -> "Cloudflare"
        "google"     -> "Google"
        "quad9"      -> "Quad9"
        "adguard"    -> "AdGuard"
        else         -> currentProviderName.replaceFirstChar { it.uppercase() }
    }

    /**
     * Résout une requête DNS via DoH3 (HTTP/3 sur QUIC port 443).
     * Cronet tente HTTP/3 en premier grâce aux QUIC hints.
     * Si le réseau bloque QUIC, Cronet fallback automatiquement sur HTTP/2.
     */
    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                resolveViaHttp3(dnsQuery)
            } catch (e: Exception) {
                Log.w(TAG, "DoH3 échec (${e.message}), fallback DoH classique")
                resolveViaHttpsFallback(dnsQuery)
            }
        }
    }

    private suspend fun resolveViaHttp3(dnsQuery: ByteArray): ByteArray? {
        return suspendCancellableCoroutine { continuation ->
            val responseBuffer = mutableListOf<ByteArray>()

            val request = cronetEngine.newUrlRequestBuilder(
                currentUrl,
                object : UrlRequest.Callback() {

                    override fun onRedirectReceived(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                        newLocationUrl: String
                    ) {
                        request.followRedirect()
                    }

                    override fun onResponseStarted(
                        request: UrlRequest,
                        info: UrlResponseInfo
                    ) {
                        val protocol = info.negotiatedProtocol
                        Log.d(TAG, "📡 DoH3 réponse: HTTP ${info.httpStatusCode}, protocol: $protocol")
                        if (protocol != "h3" && protocol != "quic/1+spdy/3") {
                            Log.w(TAG, "⚠️ HTTP/3 non négocié, protocol=$protocol (réseau QUIC bloqué ?)")
                        }
                        request.read(ByteBuffer.allocateDirect(32768))
                    }

                    override fun onReadCompleted(
                        request: UrlRequest,
                        info: UrlResponseInfo,
                        byteBuffer: ByteBuffer
                    ) {
                        byteBuffer.flip()
                        val chunk = ByteArray(byteBuffer.remaining())
                        byteBuffer.get(chunk)
                        responseBuffer.add(chunk)
                        byteBuffer.clear()
                        request.read(byteBuffer)
                    }

                    override fun onSucceeded(
                        request: UrlRequest,
                        info: UrlResponseInfo
                    ) {
                        val protocol     = info.negotiatedProtocol
                        val totalBytes   = responseBuffer.sumOf { it.size }
                        val isHttp3      = protocol == "h3" || protocol.startsWith("quic")
                        val icon         = if (isHttp3) "✅" else "⚠️"
                        Log.d(TAG, "$icon DoH3 succès via $protocol ($totalBytes bytes)")

                        val fullResponse = responseBuffer.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                        continuation.resume(fullResponse)
                    }

                    override fun onFailed(
                        request: UrlRequest,
                        info: UrlResponseInfo?,
                        error: CronetException
                    ) {
                        Log.e(TAG, "❌ DoH3 échec: ${error.message}")
                        continuation.resume(null)
                    }

                    override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                        continuation.resume(null)
                    }
                },
                executor
            )
                .setHttpMethod("POST")
                .addHeader("Content-Type", "application/dns-message")
                .addHeader("Accept", "application/dns-message")
                // Force HTTP/3 via header expérimental Cronet
                .addHeader("alt-used", currentUrl.removePrefix("https://").substringBefore("/"))
                .setUploadDataProvider(ByteArrayUploadProvider(dnsQuery), executor)
                .build()

            continuation.invokeOnCancellation { request.cancel() }
            request.start()
        }
    }

    /**
     * Fallback DoH classique (HTTP/2 via HttpsURLConnection) si QUIC bloqué
     */
    private fun resolveViaHttpsFallback(dnsQuery: ByteArray): ByteArray? {
        return try {
            val url        = java.net.URL(currentUrl)
            val connection = url.openConnection() as javax.net.ssl.HttpsURLConnection
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                doOutput      = true
                connectTimeout = 5000
                readTimeout    = 5000
            }
            connection.outputStream.use { it.write(dnsQuery) }
            if (connection.responseCode == 200) {
                val result = connection.inputStream.use { it.readBytes() }
                Log.d(TAG, "✅ DoH fallback HTTP/2 OK (${result.size} bytes)")
                connection.disconnect()
                result
            } else {
                Log.w(TAG, "❌ DoH fallback error: ${connection.responseCode}")
                connection.disconnect()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ DoH fallback aussi échoué: ${e.message}")
            null
        }
    }

    fun shutdown() {
        try {
            executor.shutdown()
            cronetEngine.shutdown()
        } catch (e: Exception) {
            Log.w(TAG, "Shutdown: ${e.message}")
        }
    }
}