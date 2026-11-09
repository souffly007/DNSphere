// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.network

import android.util.Log
import fr.bonobo.dnsphere.dns.DohBootstrap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS over TLS (DoT) Resolver — port 853.
 *
 * Par rapport à la version précédente :
 *  - le nom du certificat est VÉRIFIÉ (un socket TLS brut ne le fait pas sur Android) ;
 *  - la connexion TLS est réutilisée entre les requêtes (moins de poignées de main
 *    observables, moins de latence) avec une nouvelle tentative si le serveur l'a fermée ;
 *  - les résolveurs connus sont joints par IP de bootstrap (aucun DNS en clair) ;
 *    les autres sont résolus via [DohBootstrap] ;
 *  - aucun repli en clair : si DoT échoue, c'est l'appelant qui décide (voir ResolverManager).
 *
 * @param allowSystemBootstrap retourne faux en mode strict (pas de repli sur le DNS système).
 */
class DotResolver(
    private val allowSystemBootstrap: () -> Boolean = { true }
) {

    companion object {
        const val CLOUDFLARE_DOT = "1.1.1.1"
        const val GOOGLE_DOT     = "dns.google"
        const val ADGUARD_DOT    = "dns.adguard-dns.com"
        const val DOT_PORT       = 853
        private const val TAG    = "DotResolver"

        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val IDLE_CLOSE_MS = 8000L

        // IP de bootstrap des résolveurs connus (déjà utilisées ailleurs dans l'app).
        private val BOOTSTRAP: Map<String, List<String>> = mapOf(
            GOOGLE_DOT  to listOf("8.8.8.8", "8.8.4.4"),
            ADGUARD_DOT to listOf("94.140.14.14", "94.140.15.15")
        )
    }

    // Public pour que LocalVpnService puisse l'afficher dans les logs/notifs
    @Volatile
    var dotServer: String = CLOUDFLARE_DOT
        private set

    private val sslSocketFactory: SSLSocketFactory
    private val lock = Any()

    // État de la connexion réutilisée — uniquement accessible sous [lock].
    private var socket: SSLSocket? = null
    private var socketServer: String? = null
    private var lastUsedMs = 0L

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        sslSocketFactory = sslContext.socketFactory
    }

    fun setServer(server: String) {
        dotServer = when (server) {
            "cloudflare" -> CLOUDFLARE_DOT
            "google"     -> GOOGLE_DOT
            "adguard"    -> ADGUARD_DOT
            else         -> server   // hôte personnalisé
        }
        Log.d(TAG, "DoT server set: $dotServer")
        // La connexion ouverte vers l'ancien serveur est fermée au prochain appel de resolve().
    }

    fun getServerName(): String {
        return when (dotServer) {
            CLOUDFLARE_DOT -> "Cloudflare"
            GOOGLE_DOT     -> "Google"
            ADGUARD_DOT    -> "AdGuard"
            else           -> dotServer
        }
    }

    /** Résout une requête DNS via TLS (port 853). Retourne null en cas d'échec (pas de repli UDP ici). */
    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                var result: ByteArray? = null
                for (attempt in 0..1) {
                    try {
                        result = exchange(ensureConnected(), dnsQuery)
                        break
                    } catch (e: CancellationException) {
                        closeQuietly()
                        throw e
                    } catch (e: Exception) {
                        // Connexion périmée (fermée par le serveur) ou erreur réseau :
                        // on repart d'une connexion neuve, une seule fois.
                        closeQuietly()
                        Log.w(TAG, "DoT tentative ${attempt + 1} échouée: ${e.message}")
                    }
                }
                result
            }
        }
    }

    private fun exchange(ssl: SSLSocket, query: ByteArray): ByteArray {
        val output = DataOutputStream(ssl.outputStream)
        val input = DataInputStream(ssl.inputStream)

        // DNS over TLS : préfixe 2 octets pour la longueur du message
        output.writeShort(query.size)
        output.write(query)
        output.flush()

        val length = input.readUnsignedShort()
        if (length < 12) throw IOException("Réponse DoT trop courte ($length)")
        val response = ByteArray(length)
        input.readFully(response)

        // La réponse doit correspondre à notre requête (même ID de transaction).
        if (query.size >= 2 && (response[0] != query[0] || response[1] != query[1])) {
            throw IOException("ID de transaction DoT inattendu")
        }
        lastUsedMs = System.currentTimeMillis()
        return response
    }

    private fun ensureConnected(): SSLSocket {
        val current = socket
        val idle = System.currentTimeMillis() - lastUsedMs
        if (current != null && !current.isClosed && socketServer == dotServer && idle < IDLE_CLOSE_MS) {
            return current
        }
        closeQuietly()
        val server = dotServer
        val fresh = openSocket(server)
        socket = fresh
        socketServer = server
        lastUsedMs = System.currentTimeMillis()
        return fresh
    }

    private fun addressesFor(host: String): List<InetAddress> {
        BOOTSTRAP[host]?.let { ips -> return ips.map { InetAddress.getByName(it) } }
        // Littéral IP (ex. 1.1.1.1) : getByName ne fait aucune requête DNS.
        if (host.all { it.isDigit() || it == '.' } || host.contains(':')) {
            return listOf(InetAddress.getByName(host))
        }
        return DohBootstrap.lookup(host, allowSystemFallback = allowSystemBootstrap())
    }

    private fun openSocket(host: String): SSLSocket {
        var last: Exception? = null
        for (address in addressesFor(host)) {
            val raw = Socket()
            try {
                raw.connect(InetSocketAddress(address, DOT_PORT), CONNECT_TIMEOUT_MS)
                raw.soTimeout = READ_TIMEOUT_MS
                // Socket superposé : SNI = host, et vérification du certificat contre host.
                val ssl = sslSocketFactory.createSocket(raw, host, DOT_PORT, true) as SSLSocket
                val params = ssl.sslParameters
                params.endpointIdentificationAlgorithm = "HTTPS"
                ssl.sslParameters = params
                ssl.startHandshake()
                // Contrôle explicite en plus (certaines pile TLS ignorent le paramètre ci-dessus).
                if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) {
                    ssl.close()
                    throw SSLPeerUnverifiedException("Certificat DoT invalide pour $host")
                }
                return ssl
            } catch (e: Exception) {
                try { raw.close() } catch (_: Exception) { }
                last = e
            }
        }
        throw last ?: IOException("Aucune adresse pour $host")
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        socketServer = null
    }
}
