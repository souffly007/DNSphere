package fr.bonobo.dnsphere.network

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * DNS over HTTPS (DoH) Resolver
 * Supporte Cloudflare, Google, Quad9
 */
class DohResolver {

    companion object {
        const val CLOUDFLARE_DOH = "https://cloudflare-dns.com/dns-query"
        const val GOOGLE_DOH = "https://dns.google/dns-query"
        const val QUAD9_DOH = "https://dns.quad9.net:5053/dns-query"

        private const val TAG = "DohResolver"
    }

    private var dohServer = CLOUDFLARE_DOH

    fun setServer(server: String) {
        dohServer = when (server) {
            "cloudflare" -> CLOUDFLARE_DOH
            "google" -> GOOGLE_DOH
            "quad9" -> QUAD9_DOH
            else -> server
        }
    }

    /**
     * Résout un nom de domaine via DoH
     * @param dnsQuery La requête DNS en bytes (format wire)
     * @return La réponse DNS en bytes
     */
    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        return withContext(Dispatchers.IO) {
            try {
                // Méthode POST (recommandée)
                resolvePost(dnsQuery)
            } catch (e: Exception) {
                Log.e(TAG, "DoH POST failed, trying GET", e)
                try {
                    // Fallback sur GET
                    resolveGet(dnsQuery)
                } catch (e2: Exception) {
                    Log.e(TAG, "DoH GET also failed", e2)
                    null
                }
            }
        }
    }

    private fun resolvePost(dnsQuery: ByteArray): ByteArray? {
        val url = URL(dohServer)
        val connection = url.openConnection() as HttpsURLConnection

        return try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
                doOutput = true
                connectTimeout = 5000
                readTimeout = 5000
            }

            // Envoyer la requête
            connection.outputStream.use { it.write(dnsQuery) }

            // Lire la réponse
            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val data = ByteArray(1024)
                    var bytesRead: Int
                    while (input.read(data).also { bytesRead = it } != -1) {
                        buffer.write(data, 0, bytesRead)
                    }
                    buffer.toByteArray()
                }
            } else {
                Log.e(TAG, "DoH error: ${connection.responseCode}")
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun resolveGet(dnsQuery: ByteArray): ByteArray? {
        // Encoder en base64url
        val encoded = Base64.encodeToString(dnsQuery, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val url = URL("$dohServer?dns=$encoded")
        val connection = url.openConnection() as HttpsURLConnection

        return try {
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/dns-message")
                connectTimeout = 5000
                readTimeout = 5000
            }

            if (connection.responseCode == 200) {
                connection.inputStream.use { input ->
                    val buffer = ByteArrayOutputStream()
                    val data = ByteArray(1024)
                    var bytesRead: Int
                    while (input.read(data).also { bytesRead = it } != -1) {
                        buffer.write(data, 0, bytesRead)
                    }
                    buffer.toByteArray()
                }
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Construit une requête DNS pour un domaine
     */
    fun buildDnsQuery(domain: String, type: Int = 1): ByteArray {
        val output = ByteArrayOutputStream()

        // Transaction ID (random)
        val transactionId = (Math.random() * 65535).toInt()
        output.write((transactionId shr 8) and 0xFF)
        output.write(transactionId and 0xFF)

        // Flags: Standard query
        output.write(0x01) // QR=0, Opcode=0, AA=0, TC=0, RD=1
        output.write(0x00) // RA=0, Z=0, RCODE=0

        // Questions: 1
        output.write(0x00)
        output.write(0x01)

        // Answer RRs: 0
        output.write(0x00)
        output.write(0x00)

        // Authority RRs: 0
        output.write(0x00)
        output.write(0x00)

        // Additional RRs: 0
        output.write(0x00)
        output.write(0x00)

        // Domain name
        domain.split(".").forEach { label ->
            output.write(label.length)
            output.write(label.toByteArray())
        }
        output.write(0x00) // Null terminator

        // Type (A = 1, AAAA = 28)
        output.write((type shr 8) and 0xFF)
        output.write(type and 0xFF)

        // Class (IN = 1)
        output.write(0x00)
        output.write(0x01)

        return output.toByteArray()
    }
}