package fr.bonobo.dnsphere.dns

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class DohResolver {

    companion object {
        const val TAG = "DohResolver"

        // Serveurs DoH
        val PROVIDERS = mapOf(
            "cloudflare" to "https://cloudflare-dns.com/dns-query",
            "google" to "https://dns.google/dns-query",
            "quad9" to "https://dns.quad9.net:5053/dns-query",
            "adguard" to "https://dns.adguard.com/dns-query"
        )
    }

    var currentProvider = "cloudflare"
    var enabled = true

    /**
     * Résoudre une requête DNS via DoH (POST avec wire format)
     */
    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        if (!enabled) return null

        return withContext(Dispatchers.IO) {
            try {
                val url = PROVIDERS[currentProvider] ?: PROVIDERS["cloudflare"]!!
                val connection = URL(url).openConnection() as HttpsURLConnection

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/dns-message")
                    setRequestProperty("Accept", "application/dns-message")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                // Envoyer la requête
                connection.outputStream.use { output ->
                    output.write(dnsQuery)
                    output.flush()
                }

                // Lire la réponse
                if (connection.responseCode == HttpsURLConnection.HTTP_OK) {
                    val response = connection.inputStream.use { input ->
                        val buffer = ByteArrayOutputStream()
                        val data = ByteArray(4096)
                        var bytesRead: Int
                        while (input.read(data).also { bytesRead = it } != -1) {
                            buffer.write(data, 0, bytesRead)
                        }
                        buffer.toByteArray()
                    }
                    connection.disconnect()
                    return@withContext response
                }

                connection.disconnect()
                null
            } catch (e: Exception) {
                Log.e(TAG, "DoH resolution failed", e)
                null
            }
        }
    }

    /**
     * Créer une réponse NXDOMAIN pour bloquer un domaine
     */
    fun createBlockedResponse(originalQuery: ByteArray): ByteArray {
        val response = originalQuery.copyOf()

        // Set QR bit = 1 (response)
        response[2] = (response[2].toInt() or 0x80).toByte()

        // Set AA bit = 1 (authoritative)
        response[2] = (response[2].toInt() or 0x04).toByte()

        // Set RCODE = 3 (NXDOMAIN)
        response[3] = (response[3].toInt() and 0xF0 or 0x03).toByte()

        return response
    }

    /**
     * Créer une réponse avec IP 0.0.0.0 (null routing)
     */
    fun createNullRouteResponse(originalQuery: ByteArray): ByteArray {
        // Pour simplifier, on utilise NXDOMAIN
        return createBlockedResponse(originalQuery)
    }

    fun setProvider(provider: String) {
        if (PROVIDERS.containsKey(provider)) {
            currentProvider = provider
            Log.d(TAG, "DoH provider set to: $provider")
        }
    }

    fun getProviderName(): String {
        return when (currentProvider) {
            "cloudflare" -> "Cloudflare"
            "google" -> "Google"
            "quad9" -> "Quad9"
            "adguard" -> "AdGuard"
            else -> currentProvider
        }
    }
}