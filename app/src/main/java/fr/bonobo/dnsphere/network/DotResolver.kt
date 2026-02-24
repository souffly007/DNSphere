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
 * DNS over TLS (DoT) Resolver
 * Port 853
 */
class DotResolver {

    companion object {
        const val CLOUDFLARE_DOT = "1.1.1.1"
        const val GOOGLE_DOT = "dns.google"
        const val QUAD9_DOT = "dns.quad9.net"
        const val DOT_PORT = 853

        private const val TAG = "DotResolver"
    }

    private var dotServer = CLOUDFLARE_DOT
    private val sslSocketFactory: SSLSocketFactory

    init {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, null, null)
        sslSocketFactory = sslContext.socketFactory
    }

    fun setServer(server: String) {
        dotServer = when (server) {
            "cloudflare" -> CLOUDFLARE_DOT
            "google" -> GOOGLE_DOT
            "quad9" -> QUAD9_DOT
            else -> server
        }
    }

    /**
     * Résout une requête DNS via DoT
     */
    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            var socket: SSLSocket? = null
            try {
                socket = sslSocketFactory.createSocket(dotServer, DOT_PORT) as SSLSocket
                socket.soTimeout = 5000

                val output = DataOutputStream(socket.outputStream)
                val input = DataInputStream(socket.inputStream)

                // DNS over TLS utilise un préfixe de 2 bytes pour la longueur
                output.writeShort(dnsQuery.size)
                output.write(dnsQuery)
                output.flush()

                // Lire la réponse
                val responseLength = input.readUnsignedShort()
                val response = ByteArray(responseLength)
                input.readFully(response)

                response

            } catch (e: Exception) {
                Log.e(TAG, "DoT error", e)
                null
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }
}