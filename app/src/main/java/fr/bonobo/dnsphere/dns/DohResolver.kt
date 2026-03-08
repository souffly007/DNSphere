// DohResolver.kt - CORRECTION
package fr.bonobo.dnsphere.dns

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class DohResolver private constructor() {

    companion object {
        const val TAG = "DohResolver"

        // ✅ CORRECTION : Utiliser les MÊMES prefs que MainActivity
        private const val PREFS_NAME = "vpn_prefs"  // <-- Changé !
        private const val KEY_PROVIDER = "doh_provider"
        private const val DEFAULT_PROVIDER = "cloudflare"

        val PROVIDERS = mapOf(
            "cloudflare" to "https://cloudflare-dns.com/dns-query",
            "google" to "https://dns.google/dns-query",
            "quad9" to "https://dns.quad9.net:5053/dns-query",
            "adguard" to "https://dns.adguard.com/dns-query"
        )

        @Volatile
        private var instance: DohResolver? = null
        private var prefs: SharedPreferences? = null

        fun getInstance(context: Context): DohResolver {
            if (prefs == null) {
                // ✅ Utiliser MODE_PRIVATE et le même nom de prefs
                prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return instance ?: synchronized(this) {
                instance ?: DohResolver().also { instance = it }
            }
        }

        fun getInstance(): DohResolver {
            return instance ?: DohResolver().also { instance = it }
        }
    }

    var enabled = true

    var currentProvider: String
        get() {
            val provider = prefs?.getString(KEY_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
            Log.d(TAG, "📖 Reading provider from prefs: $provider")
            return provider
        }
        set(value) {
            if (PROVIDERS.containsKey(value)) {
                prefs?.edit()?.putString(KEY_PROVIDER, value)?.commit()
                Log.d(TAG, "💾 Saved provider to prefs: $value")
            } else {
                Log.w(TAG, "⚠️ Unknown provider ignored: $value")
            }
        }

    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        if (!enabled) return null

        return withContext(Dispatchers.IO) {
            try {
                val providerKey = currentProvider
                val url = PROVIDERS[providerKey] ?: PROVIDERS[DEFAULT_PROVIDER]!!

                Log.d(TAG, "🌐 DoH Request: $providerKey -> $url")

                val connection = URL(url).openConnection() as HttpsURLConnection

                connection.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/dns-message")
                    setRequestProperty("Accept", "application/dns-message")
                    doOutput = true
                    connectTimeout = 5000
                    readTimeout = 5000
                }

                connection.outputStream.use { output ->
                    output.write(dnsQuery)
                    output.flush()
                }

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
                    Log.d(TAG, "✅ DoH Response OK from $providerKey")
                    return@withContext response
                }

                connection.disconnect()
                null
            } catch (e: Exception) {
                Log.e(TAG, "❌ DoH failed", e)
                null
            }
        }
    }

    fun createBlockedResponse(originalQuery: ByteArray): ByteArray {
        val response = originalQuery.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte()
        response[2] = (response[2].toInt() or 0x04).toByte()
        response[3] = (response[3].toInt() and 0xF0 or 0x03).toByte()
        return response
    }

    fun createNullRouteResponse(originalQuery: ByteArray): ByteArray {
        return createBlockedResponse(originalQuery)
    }

    fun setProvider(provider: String) {
        currentProvider = provider
    }

    fun getProviderName(): String {
        return when (currentProvider) {
            "cloudflare" -> "Cloudflare"
            "google" -> "Google"
            "quad9" -> "Quad9"
            "adguard" -> "AdGuard"
            else -> currentProvider.replaceFirstChar { it.uppercase() }
        }
    }

    fun getProviderUrl(): String {
        return PROVIDERS[currentProvider] ?: PROVIDERS[DEFAULT_PROVIDER]!!
    }
}