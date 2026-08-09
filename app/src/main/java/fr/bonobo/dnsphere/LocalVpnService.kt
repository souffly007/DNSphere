package fr.bonobo.dnsphere

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.AppRuleType
import fr.bonobo.dnsphere.data.BlockLog
import fr.bonobo.dnsphere.dns.DohResolver
import fr.bonobo.dnsphere.dns.DnsResponseCache
import fr.bonobo.dnsphere.dns.KnownResolverIps
import fr.bonobo.dnsphere.network.Doh3Resolver
import fr.bonobo.dnsphere.network.DoqResolver
import fr.bonobo.dnsphere.network.DotResolver
import fr.bonobo.dnsphere.utils.PowerUtils
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START         = "fr.bonobo.dnsphere.START"
        const val ACTION_STOP          = "fr.bonobo.dnsphere.STOP"
        const val ACTION_UPDATE_CONFIG = "fr.bonobo.dnsphere.UPDATE_CONFIG"
        const val ACTION_PAUSE         = "fr.bonobo.dnsphere.PAUSE"
        const val ACTION_RESUME        = "fr.bonobo.dnsphere.RESUME"
        const val ACTION_SWITCH_DNS    = "fr.bonobo.dnsphere.SWITCH_DNS"

        const val EXTRA_PAUSE_DURATION = "pause_duration_ms"
        const val EXTRA_DNS_PROVIDER   = "dns_provider"

        const val NOTIFICATION_ID       = 1
        const val NOTIFICATION_ID_ALERT = 2
        const val CHANNEL_ID            = "vpn_channel"
        const val CHANNEL_ID_ALERT      = "vpn_alert_channel"
        const val DNS_SERVER_1          = "1.1.1.1"
        const val DNS_SERVER_2          = "8.8.8.8"

        // Nombre d'échecs de lecture consécutifs avant de considérer le tunnel comme mort
        // (cas où le fd est fermé/invalide sans passer par onRevoke ni ACTION_STOP)
        const val MAX_CONSECUTIVE_ERRORS = 20

        @Volatile var isRunning = false
        @Volatile var isPaused  = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null

    // Config de blocage
    private var blockAds      = true
    private var blockTrackers = true
    private var blockMalware  = true
    private var blockShopping = true

    // Config DNS
    private var useDoH  = false
    private var useDot  = false
    private var useDoQ  = false
    private var useDoH3 = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var blockListManager: BlockListManager
    private lateinit var parentalManager: ParentalManager
    private lateinit var database: AppDatabase
    private lateinit var dohResolver: DohResolver
    private lateinit var dotResolver: DotResolver
    private lateinit var doqResolver: DoqResolver
    private lateinit var doh3Resolver: Doh3Resolver
    private lateinit var appFilterManager: AppFilterManager

    private var pauseJob: Job? = null

    // Compteurs
    private var adsBlocked      = 0
    private var trackersBlocked = 0
    private var malwareBlocked  = 0
    private var shoppingBlocked = 0

    private val dnsProviders = listOf(
        "standard", "cloudflare", "quad9", "google", "adguard",
        "rethink-light", "rethink-recommended", "rethink-max",
        "cloudflare-doq", "adguard-doq",
        "cloudflare-doh3", "adguard-doh3"
    )

    // Cache DNS respectant le TTL réel des réponses (voir DnsResponseCache).
    private val dnsCache = DnsResponseCache()

    // =========================================================================
    // LIFECYCLE
    // =========================================================================

    override fun onCreate() {
        super.onCreate()
        blockListManager = BlockListManager(this)
        parentalManager  = ParentalManager(this)
        database         = AppDatabase.getInstance(this)
        dohResolver      = DohResolver.getInstance(this)
        dotResolver      = DotResolver()
        doqResolver      = DoqResolver(this)
        doh3Resolver     = Doh3Resolver(this)
        appFilterManager = AppFilterManager(this)

        loadSavedDnsConfig()
        createNotificationChannel()

        serviceScope.launch { appFilterManager.loadRules() }

        Log.d("DNSphere", "🚀 Service créé — DoH: ${dohResolver.getProviderName()}, DoQ: ${doqResolver.getServerName()}, DoH3: ${doh3Resolver.getProviderName()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("DNSphere", "📥 onStartCommand: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                loadConfigFromIntent(intent)
                startVpn()
            }
            ACTION_STOP -> {
                cancelPause()
                stopVpn()
            }
            ACTION_UPDATE_CONFIG -> {
                // Ne recharger la config depuis l'intent QUE s'il contient des extras.
                // Un intent vide (ex: envoyé par ListUpdateWorker après téléchargement)
                // recharge uniquement les listes sans écraser blockAds/blockTrackers/etc.
                if (intent.extras != null && intent.extras!!.size() > 0) {
                    loadConfigFromIntent(intent)
                }
                blockListManager.refresh()
                parentalManager.reload()
                serviceScope.launch { appFilterManager.loadRules() }
                updateNotification()
                Log.d("DNSphere", "🔄 Config rechargée à chaud")
            }
            ACTION_PAUSE -> {
                val duration = intent.getLongExtra(EXTRA_PAUSE_DURATION, 5 * 60 * 1000L)
                pauseVpn(duration)
            }
            ACTION_RESUME -> resumeVpn()
            ACTION_SWITCH_DNS -> {
                val provider = intent.getStringExtra(EXTRA_DNS_PROVIDER)
                Log.d("DNSphere", "📥 ACTION_SWITCH_DNS reçu: provider=$provider")
                if (provider != null) switchDnsProvider(provider)
                else Log.e("DNSphere", "❌ EXTRA_DNS_PROVIDER est null!")
            }
        }
        return START_STICKY
    }

    private fun loadConfigFromIntent(intent: Intent) {
        blockAds      = intent.getBooleanExtra("block_ads",      true)
        blockTrackers = intent.getBooleanExtra("block_trackers", true)
        blockMalware  = intent.getBooleanExtra("block_malware",  true)
        blockShopping = intent.getBooleanExtra("block_shopping", true)
        useDoH        = intent.getBooleanExtra("use_doh",        false)
        useDot        = intent.getBooleanExtra("use_dot",        false)
        useDoQ        = intent.getBooleanExtra("use_doq",        false)
        useDoH3       = intent.getBooleanExtra("use_doh3",       false)

        dohResolver.enabled = useDoH

        intent.getStringExtra("doh_provider")?.let { provider ->
            dohResolver.setProvider(provider)
            dotResolver.setServer(provider)
            doqResolver.setServer(provider)
            doh3Resolver.setProvider(provider)
        }
    }

    private fun loadSavedDnsConfig() {
        val prefs         = getSharedPreferences("dnsphere_prefs", MODE_PRIVATE)
        val savedProvider = prefs.getString("current_dns_provider", "standard") ?: "standard"
        useDoH  = prefs.getBoolean("use_doh",  false)
        useDot  = prefs.getBoolean("use_dot",  false)
        useDoQ  = prefs.getBoolean("use_doq",  false)
        useDoH3 = prefs.getBoolean("use_doh3", false)
        dohResolver.enabled = useDoH

        if (useDoH)  dohResolver.setProvider(savedProvider)
        if (useDoQ)  doqResolver.setServer(savedProvider.removeSuffix("-doq"))
        if (useDoH3) doh3Resolver.setProvider(savedProvider.removeSuffix("-doh3"))
    }

    // =========================================================================
    // CHANGEMENT DE DNS À CHAUD
    // =========================================================================

    private fun switchDnsProvider(provider: String) {
        when (provider.lowercase().trim()) {
            "standard", "off" -> {
                useDoH = false; useDot = false; useDoQ = false; useDoH3 = false
                dohResolver.enabled = false
            }
            "cloudflare" -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("cloudflare") }
            "quad9"      -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("quad9") }
            "google"     -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("google") }
            "adguard"    -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("adguard") }
            // RethinkDNS — 3 niveaux de protection (léger/recommandé/max, cf. rethinkdns.com/configure)
            "rethink-light"       -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("rethink-light") }
            "rethink-recommended" -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("rethink-recommended") }
            "rethink-max"         -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("rethink-max") }
            "cloudflare-doq"  -> { useDoH = false; useDot = false; useDoQ = true;  useDoH3 = false; dohResolver.enabled = false; doqResolver.setServer("cloudflare") }
            "adguard-doq"     -> { useDoH = false; useDot = false; useDoQ = true;  useDoH3 = false; dohResolver.enabled = false; doqResolver.setServer("adguard") }
            "cloudflare-doh3" -> { useDoH = false; useDot = false; useDoQ = false; useDoH3 = true;  dohResolver.enabled = false; doh3Resolver.setProvider("cloudflare") }
            "adguard-doh3"    -> { useDoH = false; useDot = false; useDoQ = false; useDoH3 = true;  dohResolver.enabled = false; doh3Resolver.setProvider("adguard") }
            "google-doh3"     -> { useDoH = false; useDot = false; useDoQ = false; useDoH3 = true;  dohResolver.enabled = false; doh3Resolver.setProvider("google") }
            else -> { Log.w("DNSphere", "⚠️ Provider inconnu: '$provider'"); return }
        }
        saveDnsConfig(provider)
        dnsCache.clear() // les réponses mises en cache peuvent différer d'un provider à l'autre
        updateNotification()
    }

    private fun saveDnsConfig(provider: String) {
        getSharedPreferences("dnsphere_prefs", MODE_PRIVATE).edit()
            .putString("current_dns_provider", provider.lowercase().trim())
            .putBoolean("use_doh",  useDoH)
            .putBoolean("use_dot",  useDot)
            .putBoolean("use_doq",  useDoQ)
            .putBoolean("use_doh3", useDoH3)
            .commit()
    }

    private fun getCurrentDnsProvider(): String = when {
        !useDoH && !useDot && !useDoQ && !useDoH3 -> "standard"
        useDot  -> "dot"
        useDoQ  -> "${doqResolver.getServerName().lowercase()}-doq"
        useDoH3 -> "${doh3Resolver.getProviderName().lowercase()}-doh3"
        useDoH  -> dohResolver.getProviderName().lowercase()
        else    -> "standard"
    }

    private fun getNextDnsProvider(): String {
        val currentIndex = dnsProviders.indexOf(getCurrentDnsProvider())
        return dnsProviders[if (currentIndex == -1) 0 else (currentIndex + 1) % dnsProviders.size]
    }

    private fun getCurrentDnsLabel(): String = when {
        !useDoH && !useDot && !useDoQ && !useDoH3 -> "DNS Standard"
        useDot  -> "DoT: ${dotResolver.dotServer}"
        useDoQ  -> "DoQ: ${doqResolver.getServerName()}"
        useDoH3 -> "DoH3: ${doh3Resolver.getProviderName()}"
        useDoH  -> "DoH: ${dohResolver.getProviderName()}"
        else    -> "DNS Standard"
    }

    private fun getShortDnsLabel(): String = when {
        !useDoH && !useDot && !useDoQ && !useDoH3 -> "Std"
        useDot  -> "DoT"
        useDoQ  -> when (doqResolver.getServerName().lowercase()) {
            "cloudflare" -> "DoQ-CF"; "adguard" -> "DoQ-AG"; "nextdns" -> "DoQ-ND"; else -> "DoQ"
        }
        useDoH3 -> when (doh3Resolver.getProviderName().lowercase()) {
            "cloudflare" -> "H3-CF"; "adguard" -> "H3-AG"; "google" -> "H3-Ggl"; else -> "H3"
        }
        useDoH  -> when (dohResolver.getProviderName().lowercase()) {
            "cloudflare" -> "CF"; "quad9" -> "Q9"; "google" -> "Ggl"; "adguard" -> "AG"
            else -> when {
                // RethinkDNS : code couleur repris de rethinkdns.com/configure
                dohResolver.getProviderName().contains("légère")     -> "RT 🟢"
                dohResolver.getProviderName().contains("recommandée") -> "RT 🟡"
                dohResolver.getProviderName().contains("maximale")    -> "RT 🔴"
                dohResolver.isRethinkDns()                             -> "RT"
                else -> dohResolver.getProviderName().take(3)
            }
        }
        else -> "Std"
    }

    // =========================================================================
    // MODE PAUSE
    // =========================================================================

    private fun pauseVpn(durationMs: Long) {
        if (!isRunning) return
        isPaused = true
        updateNotification()
        cancelPause()
        pauseJob = serviceScope.launch { delay(durationMs); resumeVpn() }
    }

    private fun resumeVpn() {
        if (!isRunning) return
        isPaused = false
        cancelPause()
        updateNotification()
    }

    private fun cancelPause() { pauseJob?.cancel(); pauseJob = null }

    // =========================================================================
    // DÉMARRAGE VPN
    // =========================================================================

    private fun startVpn() {
        if (isRunning) return

        startForeground(NOTIFICATION_ID, createNotification())

        try {
            val excludedApps = runBlocking {
                try { database.excludedAppDao().getAllPackageNames() }
                catch (e: Exception) { emptyList() }
            }

            val builder = Builder()
                .setSession("DNSphere Protection")
                .addAddress("10.0.0.2", 32)
                .addDnsServer(DNS_SERVER_1)
                .addDnsServer(DNS_SERVER_2)
                .setMtu(1500)
                .setBlocking(false)

            // Toutes les IPs de résolveurs publics connus sont routées dans le tunnel,
            // pour pouvoir intercepter (et rejeter explicitement) les tentatives de
            // DoH/DoT/DoQ en dur qui contournent le DNS système — voir
            // isKnownResolverBypass() et KnownResolverIps.
            KnownResolverIps.ALL.forEach { ip ->
                try { builder.addRoute(ip, 32) }
                catch (e: Exception) { Log.w("DNSphere", "Route impossible pour $ip") }
            }

            try { builder.addDisallowedApplication(packageName) }
            catch (e: Exception) { Log.w("DNSphere", "Cannot exclude own package") }

            excludedApps.forEach { pkg ->
                try { builder.addDisallowedApplication(pkg) }
                catch (e: Exception) { Log.w("DNSphere", "Cannot exclude $pkg") }
            }

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isRunning = true
                serviceScope.launch { handleDnsRequests() }
                serviceScope.launch { sendStatsUpdates() }
            } else {
                stopVpn()
            }

        } catch (e: Exception) {
            Log.e("DNSphere", "Erreur démarrage VPN", e)
            stopVpn()
        }
    }

    // =========================================================================
    // BOUCLE DNS — avec filtrage par app
    // =========================================================================

    private suspend fun handleDnsRequests() {
        val vpnFd        = vpnInterface?.fileDescriptor ?: return
        val inputStream  = FileInputStream(vpnFd)
        val outputStream = FileOutputStream(vpnFd)
        val packet       = ByteArray(32767)
        var consecutiveErrors = 0

        while (isRunning) {
            try {
                val length = inputStream.read(packet)
                consecutiveErrors = 0
                if (length > 0) {
                    val ipPacket = packet.copyOf(length)

                    if (isDnsPacket(ipPacket)) {
                        val dnsQuery = extractDnsQuery(ipPacket)

                        if (dnsQuery != null) {

                            // En pause → forward direct
                            if (isPaused) {
                                forwardDnsQuery(ipPacket)?.let { outputStream.write(it) }
                                delay(1); continue
                            }

                            // ── Filtrage par application ──────────────────────
                            val appRule = appFilterManager.getRuleForPacket(ipPacket)

                            when (appRule?.rule) {

                                AppRuleType.BLOCK_ALL -> {
                                    Log.d("DNSphere", "🚫 [APP:${appRule.appName}] $dnsQuery")
                                    incrementBlockCounter("AD")
                                    logBlock(dnsQuery, "APP_BLOCK")
                                    createBlockedDnsResponse(ipPacket)?.let { outputStream.write(it) }
                                    delay(1); continue
                                }

                                AppRuleType.ALLOW_ALL -> {
                                    Log.d("DNSphere", "✅ [APP:${appRule.appName}] bypass $dnsQuery")
                                    forwardDnsQuery(ipPacket)?.let { outputStream.write(it) }
                                    delay(1); continue
                                }

                                else -> { /* DEFAULT ou null → filtrage standard */ }
                            }
                            // ─────────────────────────────────────────────────

                            // SafeSearch enforcement (profil Enfants)
                            if (parentalManager.getConfig().pinEnabled &&
                                parentalManager.getConfig().blockAdult) {
                                val safeIp = SafeSearchEnforcer.getSafeIp(dnsQuery)
                                if (safeIp != null) {
                                    Log.d("DNSphere", "🔍 SafeSearch: $dnsQuery")
                                    createSafeSearchDnsResponse(ipPacket, safeIp)
                                        ?.let { outputStream.write(it) }
                                    delay(1); continue
                                }
                            }

                            // Filtrage DNS standard
                            val blockType = getBlockType(dnsQuery)

                            if (blockType != null) {
                                Log.d("DNSphere", "🚫 [$blockType] $dnsQuery")
                                incrementBlockCounter(blockType)
                                logBlock(dnsQuery, blockType)
                                createBlockedDnsResponse(ipPacket)?.let { outputStream.write(it) }
                            } else {
                                val qtype = extractQType(ipPacket)
                                val cached = dnsCache.get(dnsQuery, qtype)

                                if (cached != null) {
                                    // Cache hit : on rejoue la réponse, sans repartir vers l'amont.
                                    // L'ID de transaction du paquet caché appartient à une requête
                                    // précédente — il faut le réécrire avec celui de la requête actuelle,
                                    // sinon l'appelant rejettera la réponse (ID ne correspond pas).
                                    Log.d("DNSphere", "⚡ [CACHE] $dnsQuery (${dnsCache.stats()})")
                                    val rewritten = rewriteTransactionId(cached, ipPacket)
                                    outputStream.write(buildResponsePacket(ipPacket, rewritten))
                                } else {
                                    val response = when {
                                        useDot  -> forwardDnsQueryWithDoT(ipPacket)
                                        useDoQ  -> forwardDnsQueryWithDoQ(ipPacket)
                                        useDoH3 -> forwardDnsQueryWithDoH3(ipPacket)
                                        useDoH  -> forwardDnsQueryWithDoH(ipPacket)
                                        else    -> forwardDnsQuery(ipPacket)
                                    }
                                    response?.let {
                                        outputStream.write(it)
                                        cacheResponseIfPossible(dnsQuery, qtype, it)
                                    }
                                }
                            }
                        }
                    } else if (isKnownResolverBypass(ipPacket)) {
                        handleKnownResolverBypass(ipPacket, outputStream)
                    }
                }
                delay(1)
            } catch (e: Exception) {
                if (isRunning) {
                    consecutiveErrors++
                    Log.e("DNSphere", "Erreur paquet ($consecutiveErrors/$MAX_CONSECUTIVE_ERRORS)", e)

                    if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
                        // Le fd renvoie des erreurs en boucle : le tunnel est mort
                        // (ex: interface fermée côté système sans passer par onRevoke).
                        // On sort de la boucle plutôt que de tourner à vide indéfiniment.
                        Log.e("DNSphere", "🔴 Trop d'échecs de lecture consécutifs, tunnel considéré mort")
                        handleUnexpectedStop("read_failure")
                        break
                    }
                    delay(200) // évite de saturer le CPU en cas d'échecs répétés
                }
            }
        }
    }

    // =========================================================================
    // RESOLVERS
    // =========================================================================

    private suspend fun forwardDnsQueryWithDoT(p: ByteArray) = try {
        dotResolver.resolve(extractDnsPayload(p))?.let { buildResponsePacket(p, it) } ?: forwardDnsQuery(p)
    } catch (e: Exception) { forwardDnsQuery(p) }

    private suspend fun forwardDnsQueryWithDoQ(p: ByteArray) = try {
        doqResolver.resolve(extractDnsPayload(p))?.let { buildResponsePacket(p, it) } ?: forwardDnsQuery(p)
    } catch (e: Exception) { forwardDnsQuery(p) }

    private suspend fun forwardDnsQueryWithDoH3(p: ByteArray) = try {
        doh3Resolver.resolve(extractDnsPayload(p))?.let { buildResponsePacket(p, it) } ?: forwardDnsQuery(p)
    } catch (e: Exception) { forwardDnsQuery(p) }

    private suspend fun forwardDnsQueryWithDoH(p: ByteArray) = try {
        dohResolver.resolve(extractDnsPayload(p))?.let { buildResponsePacket(p, it) } ?: forwardDnsQuery(p)
    } catch (e: Exception) { forwardDnsQuery(p) }

    private fun forwardDnsQuery(originalPacket: ByteArray): ByteArray? {
        return try {
            val dnsQuery = extractDnsPayload(originalPacket)
            val socket   = DatagramSocket()
            protect(socket)
            socket.soTimeout = 5000
            val dnsServer = InetAddress.getByName(DNS_SERVER_1)
            socket.send(DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53))
            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            socket.close()
            buildResponsePacket(originalPacket, responseBuffer.copyOf(responsePacket.length))
        } catch (e: Exception) { null }
    }

    private fun extractDnsPayload(ipPacket: ByteArray): ByteArray {
        val ipHeaderLength = (ipPacket[0].toInt() and 0x0F) * 4
        return ipPacket.copyOfRange(ipHeaderLength + 8, ipPacket.size)
    }

    // =========================================================================
    // CACHE DNS — respecte le TTL réel des réponses (voir DnsResponseCache)
    // =========================================================================

    /** Lit le QTYPE (A=1, AAAA=28, etc.) de la question — nécessaire pour la clé de cache. */
    private fun extractQType(packet: ByteArray): Int {
        return try {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            var position = ipHeaderLength + 8 + 12
            while (position < packet.size) {
                val len = packet[position].toInt() and 0xFF
                if (len == 0) { position++; break }
                position += 1 + len
            }
            if (position + 1 >= packet.size) return 1 // par défaut : A
            ((packet[position].toInt() and 0xFF) shl 8) or (packet[position + 1].toInt() and 0xFF)
        } catch (e: Exception) { 1 }
    }

    /**
     * Une réponse mise en cache porte l'ID de transaction de la requête qui l'a
     * obtenue à l'origine — il faut le remplacer par celui de la requête actuelle,
     * sinon le client rejette la réponse (ID ne correspond pas à sa requête).
     */
    private fun rewriteTransactionId(cachedPayload: ByteArray, queryPacket: ByteArray): ByteArray {
        val queryPayload = extractDnsPayload(queryPacket)
        val rewritten = cachedPayload.copyOf()
        if (rewritten.size >= 2 && queryPayload.size >= 2) {
            rewritten[0] = queryPayload[0]
            rewritten[1] = queryPayload[1]
        }
        return rewritten
    }

    private fun cacheResponseIfPossible(domain: String, qtype: Int, responseIpPacket: ByteArray) {
        try {
            val dnsPayload = extractDnsPayload(responseIpPacket)
            val ttl = extractMinTtl(dnsPayload) ?: return
            dnsCache.put(domain, qtype, dnsPayload, ttl)
        } catch (e: Exception) {
            // Pas grave : on continue simplement sans mettre cette réponse en cache
        }
    }

    /** Avance au-delà d'un nom DNS (avec ou sans compression par pointeur) et retourne le nouvel offset. */
    private fun skipDnsName(data: ByteArray, offset: Int): Int {
        var pos = offset
        while (pos < data.size) {
            val len = data[pos].toInt() and 0xFF
            when {
                len == 0 -> return pos + 1
                (len and 0xC0) == 0xC0 -> return pos + 2 // pointeur de compression : toujours 2 octets
                else -> pos += 1 + len
            }
        }
        return pos
    }

    /**
     * Retourne le plus petit TTL (en secondes) parmi les enregistrements de la
     * section Answer, conformément à la RFC 1035 §3.2.1 — c'est ce TTL qui doit
     * gouverner la durée de mise en cache de la réponse entière.
     * Retourne null si la réponse ne contient pas de réponse exploitable
     * (erreur, NXDOMAIN, aucun enregistrement) : dans ce cas on ne cache pas.
     */
    private fun extractMinTtl(dnsPayload: ByteArray): Int? {
        return try {
            if (dnsPayload.size < 12) return null

            val rcode = dnsPayload[3].toInt() and 0x0F
            if (rcode != 0) return null // pas de negative caching ici

            val qdCount = ((dnsPayload[4].toInt() and 0xFF) shl 8) or (dnsPayload[5].toInt() and 0xFF)
            val anCount = ((dnsPayload[6].toInt() and 0xFF) shl 8) or (dnsPayload[7].toInt() and 0xFF)
            if (anCount == 0) return null

            var pos = 12
            repeat(qdCount) {
                pos = skipDnsName(dnsPayload, pos)
                pos += 4 // QTYPE + QCLASS
            }

            var minTtl = Int.MAX_VALUE
            repeat(anCount) {
                pos = skipDnsName(dnsPayload, pos)
                if (pos + 10 > dnsPayload.size) return null // paquet tronqué, pas fiable

                val ttl = ((dnsPayload[pos + 4].toInt() and 0xFF) shl 24) or
                        ((dnsPayload[pos + 5].toInt() and 0xFF) shl 16) or
                        ((dnsPayload[pos + 6].toInt() and 0xFF) shl 8) or
                        (dnsPayload[pos + 7].toInt() and 0xFF)
                val rdLength = ((dnsPayload[pos + 8].toInt() and 0xFF) shl 8) or (dnsPayload[pos + 9].toInt() and 0xFF)
                pos += 10 + rdLength

                if (ttl < minTtl) minTtl = ttl
            }
            if (minTtl == Int.MAX_VALUE) null else minTtl
        } catch (e: Exception) { null }
    }

    // =========================================================================
    // BLOCAGE
    // =========================================================================

    private fun getBlockType(hostname: String): String? {
        val result = blockListManager.classifyForFiltering(hostname)
        if (result.exempted) return null

        if (blockListManager.isDohBypass(hostname)) return "DOH_BYPASS"
        // SafeSearch : moteurs sans SafeSearch DNS bloqués entièrement en mode parental
        if (parentalManager.getConfig().pinEnabled &&
            SafeSearchEnforcer.isBlockedSearchEngine(hostname)) return "PARENTAL"
        if (parentalManager.shouldBlockNow(hostname)) return "PARENTAL"

        return when {
            result.forced                      -> "FORCE_BLOCKED"
            result.userBlocked                 -> "FORCE_BLOCKED"
            result.stun                        -> "WEBRTC_STUN"
            blockAds      && result.isAd       -> "AD"
            blockTrackers && result.isTracker  -> "TRACKER"
            blockMalware  && result.isMalware  -> "MALWARE"
            blockShopping && result.isShopping -> "SHOPPING"
            result.isExternal                  -> "EXTERNAL"
            else -> null
        }
    }

    private fun incrementBlockCounter(blockType: String) {
        when (blockType) {
            "AD"                               -> adsBlocked++
            "TRACKER"                          -> trackersBlocked++
            "MALWARE"                          -> malwareBlocked++
            "SHOPPING", "EXTERNAL", "PARENTAL", "DOH_BYPASS",
            "FORCE_BLOCKED", "WEBRTC_STUN",
            "APP_BLOCK"                        -> shoppingBlocked++
        }
    }

    private fun logBlock(hostname: String, type: String) {
        serviceScope.launch {
            try { database.blockLogDao().insert(BlockLog(domain = hostname, type = type)) }
            catch (e: Exception) { }
        }
    }

    // =========================================================================
    // PAQUETS IP / DNS
    // =========================================================================

    private fun isDnsPacket(packet: ByteArray): Boolean {
        if (packet.size < 28) return false
        if ((packet[0].toInt() shr 4) and 0x0F != 4) return false
        if (packet[9].toInt() and 0xFF != 17) return false
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)
        return destPort == 53
    }

    // =========================================================================
    // CONTOURNEMENT DoH/DoT/DoQ PAR IP EN DUR
    // =========================================================================
    // Certaines apps interrogent directement les IPs de résolveurs publics connus
    // (1.1.1.1, 8.8.8.8...) en DoH (TLS/443), DoT (TLS/853) ou DoQ (QUIC/UDP 443),
    // sans jamais passer par une requête DNS classique — invisible pour le filtrage
    // habituel basé sur le nom de domaine.
    //
    // On NE PEUT PAS déchiffrer et refiltrer ce trafic : ça nécessiterait de faire
    // un MITM TLS, donc de présenter un certificat de confiance à l'app cliente.
    // Sans root, seul un certificat "utilisateur" est installable, et depuis
    // Android 7 (API 24+) la config réseau par défaut des apps ignore les
    // certificats utilisateur — le handshake TLS échouerait de toute façon côté
    // app. Donc : rejet actif et assumé, pas de filtrage transparent.
    //
    // Toutes les IPs de KnownResolverIps sont routées dans le tunnel (voir
    // startVpn), donc tout paquet à destination de ces IPs qui n'est PAS du DNS
    // classique (port 53) passe ici.

    private val knownResolverIps: Set<String> = KnownResolverIps.ALL

    private fun getDestIp(packet: ByteArray): String =
        "${packet[16].toInt() and 0xFF}.${packet[17].toInt() and 0xFF}." +
                "${packet[18].toInt() and 0xFF}.${packet[19].toInt() and 0xFF}"

    private fun isKnownResolverBypass(packet: ByteArray): Boolean {
        if (packet.size < 20) return false
        if ((packet[0].toInt() shr 4) and 0x0F != 4) return false // IPv4 seulement
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 6 && protocol != 17) return false // TCP ou UDP seulement

        if (getDestIp(packet) !in knownResolverIps) return false

        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ipHeaderLength + 4) return false
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)

        // Port 53 = DNS classique, déjà géré par le pipeline de filtrage normal
        return destPort != 53
    }

    private fun handleKnownResolverBypass(packet: ByteArray, outputStream: FileOutputStream) {
        val protocol = packet[9].toInt() and 0xFF
        val destIp   = getDestIp(packet)

        incrementBlockCounter("DOH_BYPASS")
        logBlock("$destIp (IP en dur)", "DOH_BYPASS_IP")

        if (protocol == 6) {
            // TCP (DoH sur HTTPS, DoT) : on répond un RST explicite pour que
            // l'app échoue vite et retombe idéalement sur le DNS système,
            // plutôt qu'un timeout silencieux de plusieurs secondes.
            Log.d("DNSphere", "🚫 [DOH_BYPASS] TCP → $destIp (RST envoyé)")
            buildTcpRstPacket(packet)?.let { outputStream.write(it) }
        } else {
            // UDP (DoQ/DoH3 en QUIC) : pas de mécanisme de rejet actif fiable
            // en UDP sans complexité disproportionnée (ICMP port-unreachable) —
            // on droppe simplement, l'app finira par timeout et basculer.
            Log.d("DNSphere", "🚫 [DOH_BYPASS] UDP → $destIp (paquet ignoré)")
        }
    }

    private fun buildTcpRstPacket(originalPacket: ByteArray): ByteArray? {
        return try {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            if (originalPacket.size < ipHeaderLength + 20) return null

            val tcpHeaderLength = 20 // pas d'options dans notre réponse
            val totalLength     = ipHeaderLength + tcpHeaderLength
            val responsePacket  = ByteArray(totalLength)

            System.arraycopy(originalPacket, 0, responsePacket, 0, ipHeaderLength)
            responsePacket[0] = 0x45 // IPv4, IHL=5 (20 octets, pas d'options)
            responsePacket[8] = 64   // TTL
            responsePacket[9] = 6    // protocole TCP

            // Inversion IP source/destination
            System.arraycopy(originalPacket, 12, responsePacket, 16, 4)
            System.arraycopy(originalPacket, 16, responsePacket, 12, 4)

            responsePacket[2] = ((totalLength shr 8) and 0xFF).toByte()
            responsePacket[3] = (totalLength and 0xFF).toByte()

            // Ports inversés
            val origSrcPort = ((originalPacket[ipHeaderLength].toInt() and 0xFF) shl 8) or
                    (originalPacket[ipHeaderLength + 1].toInt() and 0xFF)
            val origDstPort = ((originalPacket[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                    (originalPacket[ipHeaderLength + 3].toInt() and 0xFF)

            responsePacket[ipHeaderLength]     = ((origDstPort shr 8) and 0xFF).toByte()
            responsePacket[ipHeaderLength + 1] = (origDstPort and 0xFF).toByte()
            responsePacket[ipHeaderLength + 2] = ((origSrcPort shr 8) and 0xFF).toByte()
            responsePacket[ipHeaderLength + 3] = (origSrcPort and 0xFF).toByte()

            // Numéros de séquence : RFC 793 §3.4 — un RST en réponse à un
            // segment avec ACK reprend ce numéro d'ACK comme SEQ ; sinon SEQ=0.
            val origDataOffset = (originalPacket[ipHeaderLength + 12].toInt() shr 4) and 0x0F
            val origFlags      = originalPacket[ipHeaderLength + 13].toInt() and 0xFF
            val ackFlagSet     = (origFlags and 0x10) != 0
            val synOrFin       = (origFlags and 0x03) != 0 // SYN ou FIN consomment 1 octet de séquence
            val origSeqNum     = readInt32(originalPacket, ipHeaderLength + 4)
            val origAckNum     = if (ackFlagSet) readInt32(originalPacket, ipHeaderLength + 8) else 0
            val origPayloadLen = maxOf(0, originalPacket.size - ipHeaderLength - origDataOffset * 4)

            val rstSeq = if (ackFlagSet) origAckNum else 0
            val rstAck = origSeqNum + origPayloadLen + (if (synOrFin) 1 else 0)

            writeInt32(responsePacket, ipHeaderLength + 4, rstSeq)
            writeInt32(responsePacket, ipHeaderLength + 8, rstAck)

            responsePacket[ipHeaderLength + 12] = 0x50.toByte() // data offset = 5, pas d'options
            responsePacket[ipHeaderLength + 13] = if (ackFlagSet) 0x14 else 0x04 // RST+ACK ou RST seul
            responsePacket[ipHeaderLength + 14] = 0 // window = 0
            responsePacket[ipHeaderLength + 15] = 0
            responsePacket[ipHeaderLength + 18] = 0 // urgent pointer
            responsePacket[ipHeaderLength + 19] = 0

            updateTcpChecksum(responsePacket, ipHeaderLength)
            updateIpChecksum(responsePacket)
            responsePacket
        } catch (e: Exception) {
            Log.w("DNSphere", "Impossible de construire le RST TCP", e)
            null
        }
    }

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)

    private fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset]     = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }

    /** Checksum TCP (RFC 793) : en-tête TCP + pseudo-en-tête IP (obligatoire, contrairement à l'UDP). */
    private fun updateTcpChecksum(packet: ByteArray, ipHeaderLength: Int) {
        val tcpLength = packet.size - ipHeaderLength
        packet[ipHeaderLength + 16] = 0
        packet[ipHeaderLength + 17] = 0

        var sum = 0L
        // Pseudo-en-tête : IP source, IP destination, zéro, protocole, longueur TCP
        for (i in 0 until 4 step 2) {
            sum += ((packet[12 + i].toInt() and 0xFF) shl 8) or (packet[12 + i + 1].toInt() and 0xFF)
        }
        for (i in 0 until 4 step 2) {
            sum += ((packet[16 + i].toInt() and 0xFF) shl 8) or (packet[16 + i + 1].toInt() and 0xFF)
        }
        sum += 6 // protocole TCP
        sum += tcpLength

        var i = ipHeaderLength
        while (i < packet.size - 1) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < packet.size) sum += (packet[i].toInt() and 0xFF) shl 8

        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.toInt().inv() and 0xFFFF
        packet[ipHeaderLength + 16] = ((checksum shr 8) and 0xFF).toByte()
        packet[ipHeaderLength + 17] = (checksum and 0xFF).toByte()
    }

    private fun extractDnsQuery(packet: ByteArray): String? {
        return try {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            var position       = ipHeaderLength + 8 + 12
            val parts          = mutableListOf<String>()
            while (position < packet.size) {
                val len = packet[position].toInt() and 0xFF
                if (len == 0) break
                position++
                if (position + len > packet.size) break
                parts.add(String(packet, position, len, Charsets.UTF_8))
                position += len
            }
            if (parts.isNotEmpty()) parts.joinToString(".").lowercase() else null
        } catch (e: Exception) { null }
    }

    /**
     * Crée une réponse DNS avec une IP SafeSearch spécifique.
     * Retourne une réponse A record valide au lieu de NXDOMAIN.
     *
     * Structure de la réponse DNS :
     * - Header (12 bytes) : copié depuis la query, flags modifiés
     * - Question : copiée depuis la query
     * - Answer : pointeur vers la question + type A + IP SafeSearch
     */
    private fun createSafeSearchDnsResponse(originalPacket: ByteArray, safeIp: ByteArray): ByteArray? {
        return try {
            val dnsQuery = extractDnsPayload(originalPacket)
            if (dnsQuery.size < 12) return null

            // Section Answer : pointeur vers QNAME (0xC00C = offset 12)
            val answerSection = byteArrayOf(
                0xC0.toByte(), 0x0C.toByte(), // Name: pointer to question (offset 12)
                0x00, 0x01,                    // Type: A
                0x00, 0x01,                    // Class: IN
                0x00, 0x00, 0x00, 0x78,        // TTL: 120 secondes
                0x00, 0x04,                    // RDLENGTH: 4 octets
                safeIp[0], safeIp[1], safeIp[2], safeIp[3]
            )

            // Construire la réponse = query + answer
            val dnsResponse = ByteArray(dnsQuery.size + answerSection.size)
            System.arraycopy(dnsQuery,      0, dnsResponse, 0,             dnsQuery.size)
            System.arraycopy(answerSection, 0, dnsResponse, dnsQuery.size, answerSection.size)

            // Flags : QR=1 (réponse), RD=1, RA=1, RCODE=0 (no error)
            dnsResponse[2] = 0x81.toByte()
            dnsResponse[3] = 0x80.toByte()
            // ANCOUNT = 1
            dnsResponse[6] = 0x00
            dnsResponse[7] = 0x01

            buildResponsePacket(originalPacket, dnsResponse)
        } catch (e: Exception) {
            null
        }
    }

    private fun createBlockedDnsResponse(originalPacket: ByteArray): ByteArray? {
        return try {
            val dnsResponse = extractDnsPayload(originalPacket).copyOf()
            dnsResponse[2]  = (dnsResponse[2].toInt() or 0x80).toByte()
            dnsResponse[3]  = (dnsResponse[3].toInt() or 0x03).toByte()
            buildResponsePacket(originalPacket, dnsResponse)
        } catch (e: Exception) { null }
    }

    private fun buildResponsePacket(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
        val totalLength    = ipHeaderLength + 8 + dnsResponse.size
        val responsePacket = ByteArray(totalLength)

        System.arraycopy(originalPacket, 0,  responsePacket, 0,  ipHeaderLength)
        System.arraycopy(originalPacket, 12, responsePacket, 16, 4)
        System.arraycopy(originalPacket, 16, responsePacket, 12, 4)

        responsePacket[2] = ((totalLength shr 8) and 0xFF).toByte()
        responsePacket[3] = (totalLength and 0xFF).toByte()

        val srcPort = ((originalPacket[ipHeaderLength].toInt() and 0xFF) shl 8) or
                (originalPacket[ipHeaderLength + 1].toInt() and 0xFF)
        val dstPort = ((originalPacket[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (originalPacket[ipHeaderLength + 3].toInt() and 0xFF)

        responsePacket[ipHeaderLength]     = ((dstPort shr 8) and 0xFF).toByte()
        responsePacket[ipHeaderLength + 1] = (dstPort and 0xFF).toByte()
        responsePacket[ipHeaderLength + 2] = ((srcPort shr 8) and 0xFF).toByte()
        responsePacket[ipHeaderLength + 3] = (srcPort and 0xFF).toByte()

        val udpLength = 8 + dnsResponse.size
        responsePacket[ipHeaderLength + 4] = ((udpLength shr 8) and 0xFF).toByte()
        responsePacket[ipHeaderLength + 5] = (udpLength and 0xFF).toByte()
        responsePacket[ipHeaderLength + 6] = 0
        responsePacket[ipHeaderLength + 7] = 0

        System.arraycopy(dnsResponse, 0, responsePacket, ipHeaderLength + 8, dnsResponse.size)
        updateIpChecksum(responsePacket)
        return responsePacket
    }

    private fun updateIpChecksum(packet: ByteArray) {
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        packet[10] = 0; packet[11] = 0
        var sum = 0
        for (i in 0 until ipHeaderLength step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv() and 0xFFFF
        packet[10] = ((checksum shr 8) and 0xFF).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }

    // =========================================================================
    // STATS + NOTIFICATIONS
    // =========================================================================

    private suspend fun sendStatsUpdates() {
        while (isRunning) {
            StatsLiveData.updateStats(VpnStats(
                adsBlocked      = adsBlocked,
                trackersBlocked = trackersBlocked,
                malwareBlocked  = malwareBlocked,
                shoppingBlocked = shoppingBlocked,
                isPaused        = isPaused
            ))
            updateNotification()
            delay(2000)
        }
    }

    private fun stopVpn() {
        isRunning = false; isPaused = false
        cancelPause()
        serviceScope.cancel()
        try { vpnInterface?.close() } catch (e: Exception) { }
        vpnInterface = null
        doqResolver.shutdown()
        doh3Resolver.shutdown()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // =========================================================================
    // NOTIFICATIONS
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(CHANNEL_ID, "DNSphere Protection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Protection DNS active"; setShowBadge(false)
            }
            manager.createNotificationChannel(channel)

            // Canal séparé, visible et sonore : pour prévenir l'utilisateur d'un arrêt
            // inattendu de la protection (contrairement au canal principal qui est silencieux).
            val alertChannel = NotificationChannel(
                CHANNEL_ID_ALERT, "Alertes DNSphere", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Prévient si la protection s'arrête de façon inattendue"
                enableVibration(true)
            }
            manager.createNotificationChannel(alertChannel)
        }
    }

    /**
     * Affiche une alerte visible (canal distinct, non silencieux) quand la protection
     * s'arrête sans que l'utilisateur l'ait demandé. N'est jamais déclenchée par
     * ACTION_STOP — uniquement par onRevoke() ou par des échecs de lecture répétés.
     */
    private fun notifyProtectionInterrupted() {
        try {
            val mainIntent = PendingIntent.getActivity(this, 3,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val likelyMiuiCause = PowerUtils.isMiuiOrHyperOs() &&
                    !PowerUtils.isIgnoringBatteryOptimizations(this)

            val contentText = if (likelyMiuiCause)
                "Le système (MIUI/HyperOS) a probablement arrêté la protection. Appuyez pour régler l'autostart et la batterie."
            else
                "Le filtrage DNS s'est arrêté de façon inattendue. Relance en cours…"

            val notification = NotificationCompat.Builder(this, CHANNEL_ID_ALERT)
                .setContentTitle("⚠️ Protection DNSphere interrompue")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentIntent(mainIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .build()

            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID_ALERT, notification)
        } catch (e: Exception) {
            Log.e("DNSphere", "Impossible d'afficher l'alerte d'interruption", e)
        }
    }

    /**
     * Point d'entrée unique pour tout arrêt NON désiré par l'utilisateur :
     * révocation système (onRevoke) ou tunnel mort détecté via échecs de lecture répétés.
     * Diffère de stopVpn() (utilisé pour ACTION_STOP, un arrêt volontaire).
     */
    private fun handleUnexpectedStop(reason: String) {
        val userWantsVpn = getSharedPreferences("dnsphere_prefs", MODE_PRIVATE)
            .getBoolean("vpn_should_be_running", false)

        Log.w("DNSphere", "🔴 Arrêt inattendu du VPN (raison: $reason) — protection voulue: $userWantsVpn")

        if (userWantsVpn) {
            notifyProtectionInterrupted()
            // Relance quasi immédiate (quelques secondes) au lieu d'attendre
            // le prochain passage périodique du watchdog (jusqu'à 15 min).
            WatchdogWorker.runOnceNow(applicationContext)
        }

        stopVpn()
    }

    /**
     * Appelé par le système quand la permission VPN est révoquée
     * (une autre app VPN prend la main, ou l'utilisateur révoque l'autorisation).
     * Sans cette surcharge, isRunning restait à true et le watchdog ne détectait
     * rien avant son prochain cycle de 15 minutes.
     */
    override fun onRevoke() {
        handleUnexpectedStop("revoked")
        super.onRevoke()
    }

    private fun createNotification(): Notification {
        val mainIntent = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val pauseResumeIntent = if (isPaused) {
            PendingIntent.getService(this, 1,
                Intent(this, LocalVpnService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 2,
                Intent(this, LocalVpnService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_PAUSE_DURATION, 5 * 60 * 1000L)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        val nextProvider    = getNextDnsProvider()
        val switchDnsIntent = PendingIntent.getService(
            this, 100 + dnsProviders.indexOf(nextProvider),
            Intent(this, LocalVpnService::class.java).apply {
                action = ACTION_SWITCH_DNS
                putExtra(EXTRA_DNS_PROVIDER, nextProvider)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)

        val totalBlocked  = adsBlocked + trackersBlocked + malwareBlocked + shoppingBlocked
        val shortDnsLabel = getShortDnsLabel()
        val title         = if (isPaused) "⏸️ DNSphere en pause" else "🛡️ DNSphere actif"
        val shortText     = if (isPaused) "Protection suspendue" else "$totalBlocked bloqués | ${getCurrentDnsLabel()}"
        val longText      = if (isPaused)
            "Protection suspendue temporairement\nAppuyez sur Reprendre pour réactiver"
        else
            "$totalBlocked bloqués (${adsBlocked} pubs, ${trackersBlocked} trackers, ${malwareBlocked} malwares)\n${getCurrentDnsLabel()}"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(shortText)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(mainIntent)
            .addAction(R.drawable.ic_pause, if (isPaused) "▶️ Reprendre" else "⏸️ Pause", pauseResumeIntent)
            .addAction(R.drawable.ic_shield, "DNS: $shortDnsLabel →", switchDnsIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(longText))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}