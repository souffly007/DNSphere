package fr.bonobo.dnsphere.dns

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class DohResolver private constructor(context: Context) {

    companion object {
        const val TAG = "DohResolver"

        private const val PREFS_NAME = "dnsphere_prefs"
        private const val KEY_PROVIDER = "current_dns_provider"
        private const val DEFAULT_PROVIDER = "cloudflare"

        data class DohProvider(
            val urlString: String,
            val hostname: String,      // SNI TLS et en-tête Host
            val ipAddress: String?     // IP de bootstrap (null = DNS système)
        )

        val PROVIDERS = mapOf(
            // ── Resolvers standards ────────────────────────────────────────
            "cloudflare"          to DohProvider("https://cloudflare-dns.com/dns-query", "cloudflare-dns.com", "1.1.1.1"),
            "google"              to DohProvider("https://dns.google/dns-query", "dns.google", "8.8.8.8"),
            "quad9"               to DohProvider("https://dns.quad9.net/dns-query", "dns.quad9.net", "9.9.9.9"),
            "adguard"             to DohProvider("https://dns.adguard.com/dns-query", "dns.adguard.com", "94.140.14.14"),

            // ── Mullvad DNS ────────────────────────────────────────────────
            "mullvad"             to DohProvider("https://dns.mullvad.net/dns-query", "dns.mullvad.net", "194.242.2.2"),
            "mullvad-adblock"     to DohProvider("https://adblock.dns.mullvad.net/dns-query", "adblock.dns.mullvad.net", "194.242.2.3"),
            "mullvad-base"        to DohProvider("https://base.dns.mullvad.net/dns-query", "base.dns.mullvad.net", "194.242.2.4"),
            "mullvad-extended"    to DohProvider("https://extended.dns.mullvad.net/dns-query", "extended.dns.mullvad.net", "194.242.2.5"),
            "mullvad-family"      to DohProvider("https://family.dns.mullvad.net/dns-query", "family.dns.mullvad.net", "194.242.2.6"),
            "mullvad-all"         to DohProvider("https://all.dns.mullvad.net/dns-query", "all.dns.mullvad.net", "194.242.2.9"),

            // ── JoinDNS4 / DNS4EU ──────────────────────────────────────────
            "dns4eu-protective"   to DohProvider("https://protective.joindns4.eu/dns-query", "protective.joindns4.eu", "86.54.11.1"),
            "dns4eu-child"        to DohProvider("https://child.joindns4.eu/dns-query", "child.joindns4.eu", "86.54.11.12"),
            "dns4eu-noads"        to DohProvider("https://noads.joindns4.eu/dns-query", "noads.joindns4.eu", "86.54.11.13"),
            "dns4eu-child-noads"  to DohProvider("https://child-noads.joindns4.eu/dns-query", "child-noads.joindns4.eu", "86.54.11.11"),
            "dns4eu-unfiltered"   to DohProvider("https://unfiltered.joindns4.eu/dns-query", "unfiltered.joindns4.eu", "86.54.11.100"),

            // ── RethinkDNS ─────────────────────────────────────────────────
            "rethink"             to DohProvider("https://sky.rethinkdns.com/dns-query", "sky.rethinkdns.com", null),
            "rethink-light"       to DohProvider("https://sky.rethinkdns.com/1:EAAQAAABAgA=", "sky.rethinkdns.com", null),
            "rethink-recommended" to DohProvider("https://sky.rethinkdns.com/1:YASAAQBwIAA=", "sky.rethinkdns.com", null),
            "rethink-max"         to DohProvider("https://sky.rethinkdns.com/1:YASCAQB4YgA=", "sky.rethinkdns.com", null)
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
    private var currentProvider: DohProvider = PROVIDERS[DEFAULT_PROVIDER]!!

    // ✅ Client OkHttp configuré pour HTTP/2 + ALPN nativement
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                // Si une IP de bootstrap est configurée pour le provider courant, on l'utilise directement
                val provider = currentProvider
                if (provider.ipAddress != null && hostname.equals(provider.hostname, ignoreCase = true)) {
                    Log.d(TAG, "🎯 Bootstrap DNS OK: $hostname -> ${provider.ipAddress}")
                    return listOf(InetAddress.getByName(provider.ipAddress))
                }
                // Sinon, fallback sur la résolution système normale
                return Dns.SYSTEM.lookup(hostname)
            }
        })
        .build()

    init {
        loadCurrentConfig()
    }

    private fun loadCurrentConfig() {
        val savedProvider = prefs.getString(KEY_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER
        currentProviderName = savedProvider
        currentProvider = PROVIDERS[savedProvider] ?: PROVIDERS[DEFAULT_PROVIDER]!!
        Log.d(TAG, "⚙️ Config chargée: $currentProviderName (IP: ${currentProvider.ipAddress ?: "DNS system"})")
    }

    private fun saveCurrentConfig() {
        prefs.edit().putString(KEY_PROVIDER, currentProviderName).commit()
        Log.d(TAG, "💾 Config sauvegardée: $currentProviderName")
    }

    fun setProvider(provider: String) {
        val normalizedProvider = provider.lowercase().trim()
        when {
            PROVIDERS.containsKey(normalizedProvider) -> {
                currentProviderName = normalizedProvider
                currentProvider = PROVIDERS[normalizedProvider]!!
                saveCurrentConfig()
                Log.d(TAG, "🔄 Provider changé: $normalizedProvider → ${currentProvider.urlString}")
            }
            provider.startsWith("https://") -> {
                val url = provider.trim()
                val host = url.toHttpUrlOrNull()?.host ?: ""
                currentProviderName = url
                currentProvider = DohProvider(url, host, null)
                saveCurrentConfig()
                Log.d(TAG, "🔄 Provider URL directe: $url")
            }
            else -> {
                Log.w(TAG, "⚠️ Provider inconnu ignoré: $provider")
            }
        }
    }

    fun getProviderName(): String {
        return when (currentProviderName) {
            "cloudflare"          -> "Cloudflare"
            "google"              -> "Google"
            "quad9"               -> "Quad9"
            "adguard"             -> "AdGuard"

            "mullvad"             -> "Mullvad"
            "mullvad-adblock"     -> "Mullvad – Anti-pub + trackers"
            "mullvad-base"        -> "Mullvad – Base"
            "mullvad-extended"    -> "Mullvad – Extended"
            "mullvad-family"      -> "Mullvad – Family"
            "mullvad-all"         -> "Mullvad – Protection maximale"

            "dns4eu-protective"   -> "JoinDNS4 – Protective"
            "dns4eu-child"        -> "JoinDNS4 – Child"
            "dns4eu-noads"        -> "JoinDNS4 – No Ads"
            "dns4eu-child-noads"  -> "JoinDNS4 – Child + No Ads"
            "dns4eu-unfiltered"   -> "JoinDNS4 – Unfiltered"

            "rethink"             -> "RethinkDNS"
            "rethink-light"       -> "RethinkDNS – Protection légère"
            "rethink-recommended" -> "RethinkDNS – Protection recommandée"
            "rethink-max"         -> "RethinkDNS – Protection maximale"

            else -> currentProviderName.replaceFirstChar { it.uppercase() }
        }
    }

    fun getProviderUrl(): String = currentProvider.urlString

    fun isRethinkDns(): Boolean = currentProvider.urlString.contains("rethinkdns.com")

    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        if (!enabled) {
            Log.d(TAG, "⏭️ DoH désactivé")
            return null
        }

        return withContext(Dispatchers.IO) {
            try {
                val provider = currentProvider
                Log.d(TAG, "🌐 Requête DoH vers ${getProviderName()} (${provider.hostname})")

                val base64Query = Base64.encodeToString(
                    dnsQuery,
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                )

                val requestUrl = "${provider.urlString}?dns=$base64Query"

                // ✅ Construction de la requête avec l'URL originale (OkHttp gère l'IP via son instance Dns)
                val request = Request.Builder()
                    .url(requestUrl)
                    .header("Accept", "application/dns-message")
                    .get()
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val bytes = response.body?.bytes()
                        if (bytes != null) {
                            Log.d(TAG, "✅ Réponse DoH OK de ${getProviderName()} (${bytes.size} bytes)")
                            return@withContext bytes
                        }
                    }
                    Log.w(TAG, "❌ Réponse DoH error: ${response.code} pour ${getProviderName()}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Échec DoH (${getProviderName()}): ${e.message}", e)
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