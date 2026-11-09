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
            val ipAddress: String?,    // IP littérale de bootstrap
            val ipv6Address: String? = null,
            val fallback: DohProvider? = null,
            val secondaryIpAddress: String? = null
        )

        val PROVIDERS = mapOf(
            // ── Resolvers standards ────────────────────────────────────────
            "cloudflare"          to DohProvider("https://cloudflare-dns.com/dns-query", "cloudflare-dns.com", "1.1.1.1", secondaryIpAddress = "1.0.0.1"),
            "google"              to DohProvider("https://dns.google/dns-query", "dns.google", "8.8.8.8", secondaryIpAddress = "8.8.4.4"),
            "hagezi-security" to DohProvider("https://ctif.hagezi.org/dns-query", "ctif.hagezi.org", "162.55.58.40", "2a01:4f8:1c19:6c19::1"),
            "hagezi-full" to DohProvider("https://root.hagezi.org/dns-query", "root.hagezi.org", "188.34.161.210", "2a01:4f8:c17:1c66::1",
                DohProvider("https://wurzn.hagezi.org/dns-query", "wurzn.hagezi.org", "159.69.155.94", "2a01:4f8:1c1c:d363::1")),
            "quad9"               to DohProvider("https://dns.quad9.net/dns-query", "dns.quad9.net", "9.9.9.9", secondaryIpAddress = "149.112.112.112"),
            "adguard"             to DohProvider("https://dns.adguard-dns.com/dns-query", "dns.adguard-dns.com", "94.140.14.14", secondaryIpAddress = "94.140.15.15"),

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
    private val privacy = PrivacyPrefs(context)
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    @Volatile private var currentProviderName: String = DEFAULT_PROVIDER
    @Volatile private var currentProvider: DohProvider = PROVIDERS[DEFAULT_PROVIDER]!!

    // ✅ Client OkHttp configuré pour HTTP/2 + ALPN nativement
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                // Si une IP de bootstrap est configurée pour le provider courant, on l'utilise directement
                val provider = PROVIDERS.values.asSequence()
                    .flatMap { sequenceOf(it, it.fallback).filterNotNull() }
                    .firstOrNull { it.hostname.equals(hostname, ignoreCase = true) }
                    ?: currentProvider
                if (provider.ipAddress != null && hostname.equals(provider.hostname, ignoreCase = true)) {
                    Log.d(TAG, "🎯 Bootstrap DNS OK: $hostname -> ${provider.ipAddress}")
                    return listOfNotNull(provider.ipAddress, provider.secondaryIpAddress, provider.ipv6Address)
                        .map { InetAddress.getByName(it) }
                }
                // Sinon (Rethink, URL personnalisée) : bootstrap en DoH vers une IP littérale,
                // pour ne pas envoyer le nom du résolveur en clair via le DNS système.
                // Le repli système n'est autorisé qu'en dehors du mode strict.
                return DohBootstrap.lookup(hostname, allowSystemFallback = !privacy.strictMode)
            }
        })
        .build()

    init {
        loadCurrentConfig()
    }

    private fun loadCurrentConfig() {
        val savedProvider = fr.bonobo.dnsphere.data.ProviderMigration.normalize(prefs.getString(KEY_PROVIDER, DEFAULT_PROVIDER) ?: DEFAULT_PROVIDER)
        currentProviderName = savedProvider
        currentProvider = PROVIDERS[savedProvider] ?: PROVIDERS[DEFAULT_PROVIDER]!!
        Log.d(TAG, "⚙️ Config chargée: $currentProviderName (IP: ${currentProvider.ipAddress ?: "DNS system"})")
    }

    private fun saveCurrentConfig() {
        prefs.edit().putString(KEY_PROVIDER, currentProviderName).commit()
        Log.d(TAG, "💾 Config sauvegardée: $currentProviderName")
    }

    fun setProvider(provider: String) {
        val normalizedProvider = fr.bonobo.dnsphere.data.ProviderMigration.normalize(provider)
        when {
            PROVIDERS.containsKey(normalizedProvider) -> {
                currentProviderName = normalizedProvider
                currentProvider = PROVIDERS[normalizedProvider]!!
                retryAfterMs = 0L
                primaryRetryAfterMs = 0L
                status("En attente d’une réponse DNS")
                saveCurrentConfig()
                Log.d(TAG, "🔄 Provider changé: $normalizedProvider → ${currentProvider.urlString}")
            }
            provider.startsWith("https://") -> {
                val url = provider.trim()
                val host = url.toHttpUrlOrNull()?.host ?: ""
                currentProviderName = url
                currentProvider = DohProvider(url, host, null)
                retryAfterMs = 0L
                primaryRetryAfterMs = 0L
                status("En attente d’une réponse DNS")
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
            "hagezi-security"     -> "HaGeZi Sécurité"
            "hagezi-full"         -> "HaGeZi Protection complète"
            "quad9"               -> "Quad9 (Hors France)"
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

    @Volatile private var retryAfterMs = 0L
    @Volatile private var primaryRetryAfterMs = 0L
    @Volatile var transportStatus: String = "En attente d’une réponse DNS"
        private set
    var onTransportStatusChanged: (() -> Unit)? = null

    fun requiresEncryptedTransport(): Boolean = currentProviderName.startsWith("hagezi-") || currentProviderName == "quad9"

    private fun status(value: String) {
        if (transportStatus != value) {
            transportStatus = value
            onTransportStatusChanged?.invoke()
        }
    }

    fun noteStandardFallback() {
        status("Repli DNS standard actif (non chiffré)")
    }

    suspend fun resolve(dnsQuery: ByteArray): ByteArray? {
        if (!enabled) return null
        return withContext(Dispatchers.IO) {
            // Snapshot: a provider change must never redirect an in-flight query to another policy.
            val provider = currentProvider
            if (System.currentTimeMillis() < retryAfterMs && currentProvider === provider) {
                return@withContext null
            }
            for (endpoint in listOfNotNull(provider, provider.fallback)) {
                if (endpoint === provider && provider.fallback != null &&
                    System.currentTimeMillis() < primaryRetryAfterMs) continue
                try {
                    val encoded = Base64.encodeToString(dnsQuery,
                        Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                    val request = Request.Builder().url("${endpoint.urlString}?dns=$encoded")
                        .header("Accept", "application/dns-message").get().build()
                    okHttpClient.newCall(request).execute().use { response ->
                        val body = response.body
                        if (response.isSuccessful &&
                            body?.contentType()?.let { it.type == "application" && it.subtype == "dns-message" } == true &&
                            body.contentLength() <= 65535) {
                            val bytes = body.byteStream().readBytesLimited()
                            if (DohResponsePolicy.isUsable(dnsQuery, bytes)) {
                                if (currentProvider === provider) {
                                    retryAfterMs = 0L
                                    if (endpoint === provider) primaryRetryAfterMs = 0L
                                    status("Connecté : ${endpoint.hostname}")
                                }
                                return@withContext bytes
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Transport/service failure only. Never record the queried name or payload.
                }
                if (endpoint === provider && provider.fallback != null && currentProvider === provider) {
                    primaryRetryAfterMs = System.currentTimeMillis() + 30_000L
                }
            }
            if (currentProvider === provider) {
                retryAfterMs = System.currentTimeMillis() + 1_000L
                status("DNS indisponible — nouvelle tentative en cours")
            }
            null
        }
    }

    private fun java.io.InputStream.readBytesLimited(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val n = read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > 65535) throw java.io.IOException("DNS response too large")
            out.write(buffer, 0, n)
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
