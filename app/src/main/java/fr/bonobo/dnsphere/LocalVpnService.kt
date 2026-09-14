package fr.bonobo.dnsphere

import android.app.Notification
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.AppRuleType
import fr.bonobo.dnsphere.data.BlockLog
import fr.bonobo.dnsphere.data.DnsProviderCatalog
import fr.bonobo.dnsphere.data.WhitelistItem
import fr.bonobo.dnsphere.dns.DohResolver
import fr.bonobo.dnsphere.dns.DnsResponseCache
import fr.bonobo.dnsphere.dns.KnownResolverIps
import fr.bonobo.dnsphere.network.Doh3Resolver
import fr.bonobo.dnsphere.network.DoqResolver
import fr.bonobo.dnsphere.network.DotResolver
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
        const val ACTION_QUICK_WHITELIST = "fr.bonobo.dnsphere.QUICK_WHITELIST"

        const val EXTRA_PAUSE_DURATION = "pause_duration_ms"
        const val EXTRA_DNS_PROVIDER   = "dns_provider"
        const val EXTRA_WHITELIST_DOMAIN = "whitelist_domain"

        const val NOTIFICATION_ID       = 1
        const val NOTIFICATION_ID_ALERT = 2
        const val CHANNEL_ID            = "vpn_channel"
        const val CHANNEL_ID_ALERT      = "vpn_alert_channel"

        // ✅ CORRECTION #1: Map intelligent au lieu de constantes en dur
        // ⚠️ IMPORTANT: Mullvad & DNS4EU ne supportent QUE DoH/DoT — pas UDP/53!
        val DNS_SERVERS_FALLBACK = mapOf(
            "standard"            to listOf("1.1.1.1", "8.8.8.8"),                    // Cloudflare + Google
            "cloudflare"          to listOf("1.1.1.1", "1.0.0.1"),                    // Cloudflare
            "google"              to listOf("8.8.8.8", "8.8.4.4"),                    // Google
            "quad9"               to listOf("9.9.9.9", "149.112.112.112"),            // QUAD9
            "adguard"             to listOf("94.140.14.14", "94.140.15.15"),          // AdGuard

            // 🔴 Mullvad: UDP/53 n'est PAS supporté! Ces entrées restent vides.
            // Mullvad ne fonctionne QUE en DoH/DoT (voir forwardDnsQueryWithDoH)
            "mullvad"             to emptyList(),                                      // DoH uniquement
            "mullvad-adblock"     to emptyList(),                                      // DoH uniquement
            "mullvad-base"        to emptyList(),                                      // DoH uniquement
            "mullvad-extended"    to emptyList(),                                      // DoH uniquement
            "mullvad-family"      to emptyList(),                                      // DoH uniquement
            "mullvad-all"         to emptyList(),                                      // DoH uniquement

            // 🔴 DNS4EU: UDP/53 n'est PAS supporté! Ces entrées restent vides.
            // DNS4EU ne fonctionne QUE en DoH/DoT (voir forwardDnsQueryWithDoH)
            "dns4eu-protective"   to emptyList(),                                      // DoH uniquement
            "dns4eu-child"        to emptyList(),                                      // DoH uniquement
            "dns4eu-noads"        to emptyList(),                                      // DoH uniquement
            "dns4eu-child-noads"  to emptyList(),                                      // DoH uniquement
            "dns4eu-unfiltered"   to emptyList(),                                      // DoH uniquement
        )

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
    private var profileBlockAdult = false
    private var profileBlockGambling = false
    private var profileBlockSocial = false

    // Config DNS
    private var useDoH  = false
    private var useDot  = false
    private var useDoQ  = false
    private var useDoH3 = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var blockListManager: BlockListManager
    private lateinit var parentalManager: ParentalManager
    private lateinit var database: AppDatabase
    @Volatile private var lastBlockedDomain: String? = null
    private lateinit var dohResolver: DohResolver
    private lateinit var dotResolver: DotResolver
    private lateinit var doqResolver: DoqResolver
    private lateinit var doh3Resolver: Doh3Resolver
    private lateinit var appFilterManager: AppFilterManager
    private lateinit var filteringCoordinator: FilteringCoordinator
    private lateinit var filterEngine: FilterEngine
    private lateinit var notificationController: NotificationController

    private var pauseJob: Job? = null
    @Volatile private var rebuildingVpn = false
    private var dnsRequestJob: Job? = null

    private lateinit var statsController: StatsController
    private val dnsPacketProcessor = DnsPacketProcessor()
    private val dnsResponseBuilder = DnsResponseBuilder(dnsPacketProcessor)
    private lateinit var resolverManager: ResolverManager
    private lateinit var vpnTunnelManager: VpnTunnelManager

    private val dnsProviders = DnsProviderCatalog.ids

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
        vpnTunnelManager = VpnTunnelManager(this, database)
        statsController  = StatsController(
            database = database,
            scope = serviceScope,
            isRunning = { isRunning },
            isPaused = { isPaused },
            onStatsPublished = { updateNotification() }
        )
        dohResolver      = DohResolver.getInstance(this)
        dotResolver      = DotResolver()
        doqResolver      = DoqResolver(this)
        doh3Resolver     = Doh3Resolver(this)
        resolverManager  = ResolverManager(
            packets = dnsPacketProcessor,
            responses = dnsResponseBuilder,
            doh = dohResolver,
            dot = dotResolver,
            doq = doqResolver,
            doh3 = doh3Resolver,
            udpFallback = { packet -> forwardDnsQuery(packet) }
        )
        appFilterManager = AppFilterManager(this)
        filteringCoordinator = FilteringCoordinator(appFilterManager, parentalManager)
        filterEngine     = FilterEngine(blockListManager, parentalManager)

        notificationController = NotificationController(
            context = this,
            isPaused = { isPaused },
            counters = {
                val values = statsController.snapshot()
                NotificationController.BlockCounters(
                    ads = values.ads,
                    trackers = values.trackers,
                    malware = values.malware,
                    shopping = values.shopping,
                    other = values.other
                )
            },
            currentDnsLabel = { getCurrentDnsLabel() },
            shortDnsLabel = { getShortDnsLabel() },
            nextDnsProvider = { getNextDnsProvider() },
            nextDnsProviderIndex = { dnsProviders.indexOf(getNextDnsProvider()) },
            lastBlockedDomain = { lastBlockedDomain }
        )

        loadSavedDnsConfig()
        notificationController.createNotificationChannel()

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
            ACTION_QUICK_WHITELIST -> {
                val domain = intent.getStringExtra(EXTRA_WHITELIST_DOMAIN)
                if (domain != null) quickWhitelist(domain)
            }
        }
        return START_STICKY
    }

    private fun loadConfigFromIntent(intent: Intent) {
        blockAds      = intent.getBooleanExtra("block_ads",      true)
        blockTrackers = intent.getBooleanExtra("block_trackers", true)
        blockMalware  = intent.getBooleanExtra("block_malware",  true)
        blockShopping = intent.getBooleanExtra("block_shopping", true)
        profileBlockAdult = intent.getBooleanExtra("block_adult", false)
        profileBlockGambling = intent.getBooleanExtra("block_gambling", false)
        profileBlockSocial = intent.getBooleanExtra("block_social", false)
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
            // Mullvad DNS
            "mullvad"            -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("mullvad") }
            "mullvad-adblock"    -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("mullvad-adblock") }
            "mullvad-base"       -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("mullvad-base") }
            "mullvad-extended"   -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("mullvad-extended") }
            "mullvad-family"     -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("mullvad-family") }
            "mullvad-all"        -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("mullvad-all") }
            // DNS4EU
            "dns4eu-protective"  -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("dns4eu-protective") }
            "dns4eu-child"       -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("dns4eu-child") }
            "dns4eu-noads"       -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("dns4eu-noads") }
            "dns4eu-child-noads" -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("dns4eu-child-noads") }
            "dns4eu-unfiltered"  -> { useDoH = true; useDot = false; useDoQ = false; useDoH3 = false; dohResolver.enabled = true; dohResolver.setProvider("dns4eu-unfiltered") }
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
        useDoH  -> getSharedPreferences("dnsphere_prefs", MODE_PRIVATE)
            .getString("current_dns_provider", "cloudflare") ?: "cloudflare"
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
        useDoH  -> when (getCurrentDnsProvider()) {
            "cloudflare" -> "CF"; "quad9" -> "Q9"; "google" -> "Ggl"; "adguard" -> "AG"
            "mullvad" -> "MV"; "mullvad-adblock" -> "MV-A"; "mullvad-base" -> "MV-B"
            "mullvad-extended" -> "MV-E"; "mullvad-family" -> "MV-F"; "mullvad-all" -> "MV+"
            "dns4eu-protective" -> "EU"; "dns4eu-child" -> "EU-C"; "dns4eu-noads" -> "EU-A"
            "dns4eu-child-noads" -> "EU-CA"; "dns4eu-unfiltered" -> "EU-U"
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
        rebuildVpnInterface(includeResolverRoutes = false)
        updateNotification()
        cancelPause()
        pauseJob = serviceScope.launch { delay(durationMs); resumeVpn() }
    }

    private fun resumeVpn() {
        if (!isRunning) return
        isPaused = false
        rebuildVpnInterface(includeResolverRoutes = true)
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
            vpnInterface = vpnTunnelManager.establish(includeResolverRoutes = true)
            if (vpnInterface != null) {
                isRunning = true
                startDnsRequestLoop()
                statsController.startUpdates()
            } else stopVpn()

        } catch (e: Exception) {
            Log.e("DNSphere", "Erreur démarrage VPN", e)
            stopVpn()
        }
    }

    private fun rebuildVpnInterface(includeResolverRoutes: Boolean) {
        rebuildingVpn = true
        dnsRequestJob?.cancel()
        try { vpnInterface?.close() } catch (_: Exception) { }
        vpnInterface = null

        vpnInterface = vpnTunnelManager.establish(includeResolverRoutes)
        val established = vpnInterface != null
        rebuildingVpn = false
        if (established) {
            startDnsRequestLoop()
        } else {
            Log.e("DNSphere", "Impossible de reconstruire le tunnel VPN")
            stopVpn()
        }
    }

    private fun startDnsRequestLoop() {
        dnsRequestJob?.cancel()
        dnsRequestJob = serviceScope.launch { handleDnsRequests() }
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

                    if (dnsPacketProcessor.isDnsPacket(ipPacket)) {
                        val dnsQuery = dnsPacketProcessor.extractDnsQuery(ipPacket)

                        if (dnsQuery != null) {

                            // En pause → forward direct
                            if (isPaused) {
                                // La pause désactive le filtrage, mais conserve le
                                // transport DNS choisi par l'utilisateur. L'ancien
                                // code forçait l'UDP/53 et cassait Mullvad/DNS4EU
                                // ainsi que les configurations DoH/DoT/DoQ/DoH3.
                                forwardDnsQueryWithoutFiltering(ipPacket)
                                    ?.let { outputStream.write(it) }
                                delay(1); continue
                            }

                            when (val decision = filteringCoordinator.evaluate(
                                ipPacket,
                                dnsQuery,
                                profileBlockAdult
                            )) {
                                is FilteringCoordinator.Decision.BlockForApp -> {
                                    Log.d("DNSphere", "🚫 [APP:${decision.appName}] $dnsQuery")
                                    statsController.incrementBlockCounter("AD")
                                    statsController.logBlock(dnsQuery, "APP_BLOCK")
                                    dnsResponseBuilder.createBlockedResponse(ipPacket)?.let { outputStream.write(it) }
                                    delay(1); continue
                                }
                                is FilteringCoordinator.Decision.AllowForApp -> {
                                    Log.d("DNSphere", "✅ [APP:${decision.appName}] bypass $dnsQuery")
                                    forwardDnsQuery(ipPacket)?.let { outputStream.write(it) }
                                    delay(1); continue
                                }
                                is FilteringCoordinator.Decision.SafeSearchRedirect -> {
                                    Log.d("DNSphere", "🔍 SafeSearch: $dnsQuery")
                                    dnsResponseBuilder.createSafeSearchResponse(ipPacket, decision.ip)
                                        ?.let { outputStream.write(it) }
                                    delay(1); continue
                                }
                                is FilteringCoordinator.Decision.StandardBlock -> Unit
                                FilteringCoordinator.Decision.StandardFiltering -> Unit
                            }

                            // Filtrage DNS standard
                            val standardDecision = filteringCoordinator.evaluateStandard(
                                hostname = dnsQuery,
                                blockAds = blockAds,
                                blockTrackers = blockTrackers,
                                blockMalware = blockMalware,
                                blockShopping = blockShopping,
                                profileBlockAdult = profileBlockAdult,
                                profileBlockGambling = profileBlockGambling,
                                profileBlockSocial = profileBlockSocial,
                                filterEngine = filterEngine
                            )
                            val blockType = (standardDecision as? FilteringCoordinator.Decision.StandardBlock)
                                ?.reason?.code

                            if (blockType != null) {
                                Log.d("DNSphere", "🚫 [$blockType] $dnsQuery")
                                statsController.incrementBlockCounter(blockType)
                                statsController.logBlock(dnsQuery, blockType)
                                if (blockType != "PARENTAL") lastBlockedDomain = dnsQuery
                                dnsResponseBuilder.createBlockedResponse(ipPacket)?.let { outputStream.write(it) }
                            } else {
                                val qtype = dnsPacketProcessor.extractQType(ipPacket)
                                val cached = dnsCache.get(dnsQuery, qtype)

                                if (cached != null) {
                                    // Cache hit : on rejoue la réponse, sans repartir vers l'amont.
                                    // L'ID de transaction du paquet caché appartient à une requête
                                    // précédente — il faut le réécrire avec celui de la requête actuelle,
                                    // sinon l'appelant rejettera la réponse (ID ne correspond pas).
                                    Log.d("DNSphere", "⚡ [CACHE] $dnsQuery (${dnsCache.stats()})")
                                    val rewritten = dnsPacketProcessor.rewriteTransactionId(cached, ipPacket)
                                    outputStream.write(dnsResponseBuilder.buildResponsePacket(ipPacket, rewritten))
                                } else {
                                val response = resolverManager.resolve(
                                    ipPacket, useDot, useDoQ, useDoH3, useDoH
                                )
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
                if (isRunning && !rebuildingVpn) {
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

    /**
     * Résout une requête sans appliquer de blocage, en conservant le protocole
     * DNS actif. Utilisé uniquement pendant la pause de la protection.
     */
    private suspend fun forwardDnsQueryWithoutFiltering(packet: ByteArray): ByteArray? {
        return resolverManager.resolve(packet, useDot, useDoQ, useDoH3, useDoH)
    }

    // ✅ CORRECTION #2: forwardDnsQuery() — NE PAS utiliser pour Mullvad/DNS4EU!
    private fun forwardDnsQuery(originalPacket: ByteArray): ByteArray? {
        return try {
            val dnsQuery = dnsPacketProcessor.extractDnsPayload(originalPacket)
            val currentProvider = getCurrentDnsProvider()
            val dnsServers = DNS_SERVERS_FALLBACK[currentProvider] ?: DNS_SERVERS_FALLBACK["standard"]!!

            // 🚫 Mullvad/DNS4EU ne supportent PAS UDP/53 — listes vides intentionnellement
            if (dnsServers.isEmpty()) {
                Log.w("DNSphere", "⚠️ $currentProvider ne supporte pas UDP/53 — DoH obligatoire!")
                Log.w("DNSphere", "❌ Pas de fallback UDP possible pour $currentProvider")
                return null // Force le DoH à être utilisé, et échoue si DoH échoue aussi
            }

            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 5000

            Log.d("DNSphere", "📤 Forward DNS ($currentProvider): essai ${dnsServers[0]}")

            // Essayer chaque serveur DNS, fallback sur le suivant si erreur
            var lastException: Exception? = null
            for (dnsServerAddr in dnsServers) {
                try {
                    val dnsServer = InetAddress.getByName(dnsServerAddr)
                    socket.send(DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53))
                    val responseBuffer = ByteArray(512)
                    val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                    socket.receive(responsePacket)
                    socket.close()
                    Log.d("DNSphere", "✅ Réponse DNS reçue de $dnsServerAddr")
                    return dnsResponseBuilder.buildResponsePacket(originalPacket, responseBuffer.copyOf(responsePacket.length))
                } catch (e: Exception) {
                    lastException = e
                    Log.w("DNSphere", "⚠️ DNS $dnsServerAddr échoué ($currentProvider), essai suivant...")
                    continue
                }
            }

            // Tous les serveurs ont échoué
            socket.close()
            Log.e("DNSphere", "❌ Tous les serveurs DNS ont échoué pour $currentProvider", lastException)
            null
        } catch (e: Exception) {
            Log.e("DNSphere", "❌ Erreur fatale forwardDnsQuery", e)
            null
        }
    }

    // =========================================================================
    // CACHE DNS — respecte le TTL réel des réponses (voir DnsResponseCache)
    // =========================================================================

    /**
     * Une réponse mise en cache porte l'ID de transaction de la requête qui l'a
     * obtenue à l'origine — il faut le remplacer par celui de la requête actuelle,
     * sinon le client rejette la réponse (ID ne correspond pas à sa requête).
     */
    private fun cacheResponseIfPossible(domain: String, qtype: Int, responseIpPacket: ByteArray) {
        try {
            val dnsPayload = dnsPacketProcessor.extractDnsPayload(responseIpPacket)
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

    private fun quickWhitelist(domain: String) {
        serviceScope.launch {
            try {
                database.whitelistDao().insert(WhitelistItem(domain = domain))
                blockListManager.loadWhitelist()
                Log.d("DNSphere", "✅ [QUICK_WHITELIST] $domain")
                if (lastBlockedDomain == domain) lastBlockedDomain = null
                updateNotification()
            } catch (e: Exception) {
                Log.w("DNSphere", "Échec quick-whitelist pour $domain", e)
            }
        }
    }

    // =========================================================================
    // PAQUETS IP / DNS
    // =========================================================================

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

        statsController.incrementBlockCounter("DOH_BYPASS")
        statsController.logBlock("$destIp (IP en dur)", "DOH_BYPASS_IP")

        if (protocol == 6) {
            // TCP (DoH sur HTTPS, DoT) : on répond un RST explicite pour que
            // l'app échoue vite et retombe idéalement sur le DNS système,
            // plutôt qu'un timeout silencieux de plusieurs secondes.
            Log.d("DNSphere", "🚫 [DOH_BYPASS] TCP → $destIp (RST envoyé)")
            dnsResponseBuilder.buildTcpRstPacket(packet)?.let { outputStream.write(it) }
        } else {
            // UDP (DoQ/DoH3 en QUIC) : pas de mécanisme de rejet actif fiable
            // en UDP sans complexité disproportionnée (ICMP port-unreachable) —
            // on droppe simplement, l'app finira par timeout et basculer.
            Log.d("DNSphere", "🚫 [DOH_BYPASS] UDP → $destIp (paquet ignoré)")
        }
    }

    // =========================================================================
    // STATS + NOTIFICATIONS
    // =========================================================================

    private fun stopVpn() {
        isRunning = false; isPaused = false
        cancelPause()
        rebuildingVpn = false
        dnsRequestJob?.cancel()
        dnsRequestJob = null
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
        notificationController.createNotificationChannel()
    }

    /**
     * Affiche une alerte visible (canal distinct, non silencieux) quand la protection
     * s'arrête sans que l'utilisateur l'ait demandé. N'est jamais déclenchée par
     * ACTION_STOP — uniquement par onRevoke() ou par des échecs de lecture répétés.
     */
    private fun notifyProtectionInterrupted() {
        notificationController.notifyProtectionInterrupted()
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
        return notificationController.createNotification()
    }

    private fun updateNotification() {
        notificationController.updateNotification()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
