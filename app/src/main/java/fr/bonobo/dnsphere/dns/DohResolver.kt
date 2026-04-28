package fr.bonobo.dnsphere.dns

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class DohResolver private constructor(context: Context) {

    companion object {
        const val TAG = "DohResolver"

        private const val PREFS_NAME = "dnsphere_prefs"
        private const val KEY_PROVIDER = "current_dns_provider"
        private const val DEFAULT_PROVIDER = "cloudflare"

        val PROVIDERS = mapOf(
            "cloudflare" to "https://cloudflare-dns.com/dns-query",
            "google" to "https://dns.google/dns-query",
            "quad9" to "https://dns.quad9.net/dns-query",
            "adguard" to "https://dns.adguard.com/dns-query"
        )

        @Volatile
        private var instance: DohResolver? = null

        fun getInstance(context: Context): DohResolver {
            return instance ?: synchronized(this) {
                instance ?: DohResolver(context.applicationContext).also { instance = it }
            }
        }
    }

    var enabled = true
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var currentProviderName: String = DEFAULT_PROVIDER
    private var currentUrl: String = PROVIDERS[DEFAULT_PROVIDER]!!

    init {
        loadCurrentConfig()
    }

    private fun loadCurrentConfig() {
        val savedProvider = prefs.getString(KEY_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        currentProviderName = savedProvider
        currentUrl = PROVIDERS[savedProvider] ?: PROVIDERS[DEFAULT_PROVIDER]!!
        Log.d(TAG, "⚙️ Config chargée: $currentProviderName ($currentUrl)")
    }

    private fun saveCurrentConfig() {
        prefs.edit().putString(KEY_PROVIDER, currentProviderName).commit()
        Log.d(TAG, "💾 Config sauvegardée: $currentProviderName")
    }

    fun setProvider(provider: String) {
        val normalizedProvider = provider.lowercase().trim()
        if (PROVIDERS.containsKey(normalizedProvider)) {
            currentProviderName = normalizedProvider
            currentUrl = PROVIDERS[normalizedProvider]!!
            saveCurrentConfig()
            Log.d(TAG, "🔄 Provider changé: $normalizedProvider -> $currentUrl")
        } else {
            Log.w(TAG, "⚠️ Provider inconnu ignoré: $provider")
        }
    }

    fun getProviderName(): String {
        return when (currentProviderName) {
            "cloudflare" -> "Cloudflare"
            "google" -> "Google"
            "quad9" -> "Quad9"
            "adguard" -> "AdGuard"
            else -> currentProviderName.replaceFirstChar { it.uppercase() }
        }
    }

    fun getProviderUrl(): String {
        return currentUrl
    }

    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        if (!enabled) {
            Log.d(TAG, "⏭️ DoH désactivé")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "🌐 Requête DoH vers $currentProviderName ($currentUrl)")

                // Encoder la requête DNS en base64 URL-safe
                val base64Query = Base64.encodeToString(
                    dnsQuery,
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )

                // Construire l'URL avec le paramètre dns
                val requestUrl = "$currentUrl?dns=$base64Query"

                val connection = URL(requestUrl).openConnection() as HttpsURLConnection

                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/dns-message")
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

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
                    Log.d(TAG, "✅ Réponse DoH OK (${response.size} bytes)")
                    return@withContext response
                }

                Log.w(TAG, "❌ Réponse DoH error: ${connection.responseCode}")
                connection.disconnect()
                null
            } catch (e: Exception) {
                Log.e(TAG, "❌ Échec DoH: ${e.message}", e)
                null
            }
        }
    }

    fun createBlockedResponse(originalQuery: ByteArray): ByteArray {
        val response = originalQuery.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[3] = (response[3].toInt() and 0xF0 or 0x03).toByte()
        return response
    }

    fun createNullRouteResponse(originalQuery: ByteArray): ByteArray {
        return createBlockedResponse(originalQuery)
    }
}