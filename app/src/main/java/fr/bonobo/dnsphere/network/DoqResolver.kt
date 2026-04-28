// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class DoqResolver {

package fr.bonobo.dnsphere.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import fr.bonobo.dnsphere.network.ByteArrayUploadProvider

/**
 * DNS over QUIC (DoQ) Resolver — port 853
 * Utilise Cronet comme moteur QUIC.
 * Fallback automatique vers UDP en cas d'échec.
 */
class DoqResolver(private val context: Context) {

    companion object {
        const val CLOUDFLARE_DOQ = "cloudflare-dns.com"
        const val ADGUARD_DOQ    = "dns.adguard.com"
        const val NEXTDNS_DOQ    = "dns.nextdns.io"
        const val DOQ_PORT       = 853
        private const val TAG    = "DoqResolver"
    }

    var doqServer: String = CLOUDFLARE_DOQ
        private set

    private val executor = Executors.newCachedThreadPool()

    // Cronet engine — initialisé une seule fois
    private val cronetEngine: CronetEngine by lazy {
        CronetEngine.Builder(context)
            .enableQuic(true)
            .enableHttp2(true)
            .enableBrotli(true)
            .build()
    }

    fun setServer(server: String) {
        doqServer = when (server) {
            "cloudflare" -> CLOUDFLARE_DOQ
            "adguard"    -> ADGUARD_DOQ
            "nextdns"    -> NEXTDNS_DOQ
            else         -> server
        }
        Log.d(TAG, "DoQ server set: $doqServer")
    }

    fun getServerName(): String = when (doqServer) {
        CLOUDFLARE_DOQ -> "Cloudflare"
        ADGUARD_DOQ    -> "AdGuard"
        NEXTDNS_DOQ    -> "NextDNS"
        else           -> doqServer
    }

    /**
     * Résout une requête DNS via DoQ (QUIC port 853).
     * DoQ = DNS wire format encapsulé dans un stream QUIC.
     * On passe par DoH avec Cronet (HTTP/3) comme transport QUIC —
     * Cloudflare/AdGuard exposent les deux sur le même endpoint.
     *
     * Fallback automatique vers UDP si échec.
     */
    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                resolveViaQuic(dnsQuery)
            } catch (e: Exception) {
                Log.w(TAG, "DoQ échoué (${e.message}), fallback UDP")
                resolveViaUdpFallback(dnsQuery)
            }
        }
    }

    /**
     * Résolution via QUIC en utilisant l'endpoint DoH de Cronet
     * avec forçage HTTP/3 (QUIC). Cronet négocie QUIC automatiquement
     * quand enableQuic(true) est actif et que le serveur le supporte.
     */
    private suspend fun resolveViaQuic(dnsQuery: ByteArray): ByteArray? {
        val url = "https://$doqServer/dns-query"

        return suspendCancellableCoroutine { continuation ->
            val responseBuffer = mutableListOf<ByteArray>()

            val request = cronetEngine.newUrlRequestBuilder(
                url,
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
                        Log.d(TAG, "DoQ réponse: HTTP ${info.httpStatusCode}, protocol: ${info.negotiatedProtocol}")
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
                        val protocol = info.negotiatedProtocol
                        Log.d(TAG, "✅ DoQ succès via $protocol (${responseBuffer.sumOf { it.size }} bytes)")

                        val fullResponse = responseBuffer.fold(ByteArray(0)) { acc, chunk -> acc + chunk }
                        continuation.resume(fullResponse)
                    }

                    override fun onFailed(
                        request: UrlRequest,
                        info: UrlResponseInfo?,
                        error: CronetException
                    ) {
                        Log.e(TAG, "DoQ échec: ${error.message}")
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
                .setUploadDataProvider(ByteArrayUploadProvider(dnsQuery), executor)
                .build()

            continuation.invokeOnCancellation { request.cancel() }
            request.start()
        }
    }

    /**
     * Fallback UDP classique si QUIC indisponible
     */
    private fun resolveViaUdpFallback(dnsQuery: ByteArray): ByteArray? {
        return try {
            val socket = DatagramSocket()
            socket.soTimeout = 5000
            val server = InetAddress.getByName("1.1.1.1")
            socket.send(DatagramPacket(dnsQuery, dnsQuery.size, server, 53))
            val buf = ByteArray(512)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            socket.close()
            buf.copyOf(response.length)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback UDP aussi échoué: ${e.message}")
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