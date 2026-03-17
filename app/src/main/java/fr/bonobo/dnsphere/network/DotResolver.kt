package fr.bonobo.dnsphere.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * DNS over TLS (DoT) Resolver — port 853
 * Branché dans LocalVpnService via use_dot = true
 */
class DotResolver {

    companion object {
        const val CLOUDFLARE_DOT = "1.1.1.1"
        const val GOOGLE_DOT     = "dns.google"
        const val QUAD9_DOT      = "dns.quad9.net"
        const val ADGUARD_DOT    = "dns.adguard.com"
        const val DOT_PORT       = 853
        private const val TAG    = "DotResolver"
    }

    // Public pour que LocalVpnService puisse l'afficher dans les logs/notifs
    var dotServer: String = CLOUDFLARE_DOT
        private set

    private val sslSocketFactory: SSLSocketFactory

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        sslSocketFactory = sslContext.socketFactory
    }

    fun setServer(server: String) {
        dotServer = when (server) {
            "cloudflare" -> CLOUDFLARE_DOT
            "google"     -> GOOGLE_DOT
            "quad9"      -> QUAD9_DOT
            "adguard"    -> ADGUARD_DOT
            else         -> server   // URL custom
        }
        Log.d(TAG, "DoT server set: $dotServer")
    }

    fun getServerName(): String {
        return when (dotServer) {
            CLOUDFLARE_DOT -> "Cloudflare"
            GOOGLE_DOT     -> "Google"
            QUAD9_DOT      -> "Quad9"
            ADGUARD_DOT    -> "AdGuard"
            else           -> dotServer
        }
    }

    /**
     * Résout une requête DNS via TLS (port 853)
     * Fallback automatique vers UDP si échec
     */
    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            var socket: SSLSocket? = null
            try {
                socket = sslSocketFactory.createSocket(dotServer, DOT_PORT) as SSLSocket
                socket.soTimeout = 5000

                val output = DataOutputStream(socket.outputStream)
                val input  = DataInputStream(socket.inputStream)

                // DNS over TLS : préfixe 2 bytes pour la longueur du message
                output.writeShort(dnsQuery.size)
                output.write(dnsQuery)
                output.flush()

                // Lire la réponse avec son préfixe de longueur
                val responseLength = input.readUnsignedShort()
                val response       = ByteArray(responseLength)
                input.readFully(response)

                Log.d(TAG, "✅ DoT resolved, response: ${response.size} bytes")
                response

            } catch (e: Exception) {
                Log.e(TAG, "DoT error: ${e.message}")
                null
            } finally {
                try { socket?.close() } catch (e: Exception) { }
            }
        }
    }
}