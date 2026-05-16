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

        const val EXTRA_PAUSE_DURATION = "pause_duration_ms"
        const val EXTRA_DNS_PROVIDER   = "dns_provider"

        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID      = "vpn_channel"
        const val DNS_SERVER_1    = "1.1.1.1"
        const val DNS_SERVER_2    = "8.8.8.8"

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
        "cloudflare-doq", "adguard-doq",
        "cloudflare-doh3", "adguard-doh3"
    )

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

        // Charger les règles par app en cache
        serviceScope.launch { appFilterManager.loadRules() }

        Log.d("DNSphere", "🚀 Service créé — DoH: ${dohResolver.getProviderName()}, DoQ: ${doqResolver.getServerName()}, DoH3: ${doh3Resolver.getProviderName()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("DNSphere", "📥 onStartCommand: action=${intent?.action}")

        // Android 14+ requirement: call startForeground() as soon as possible
        if (intent?.action == ACTION_START) {
            startVpnForeground()
        }

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
                loadConfigFromIntent(intent)
                blockListManager.refresh()
                parentalManager.reload()
                // Recharger les règles app à chaud
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
            "cloudflare-doq"  -> { useDoH = false; useDot = false; useDoQ = true;  useDoH3 = false; dohResolver.enabled = false; doqResolver.setServer("cloudflare") }
            "adguard-doq"     -> { useDoH = false; useDot = false; useDoQ = true;  useDoH3 = false; dohResolver.enabled = false; doqResolver.setServer("adguard") }
            "cloudflare-doh3" -> { useDoH = false; useDot = false; useDoQ = false; useDoH3 = true;  dohResolver.enabled = false; doh3Resolver.setProvider("cloudflare") }
            "adguard-doh3"    -> { useDoH = false; useDot = false; useDoQ = false; useDoH3 = true;  dohResolver.enabled = false; doh3Resolver.setProvider("adguard") }
            "google-doh3"     -> { useDoH = false; useDot = false; useDoQ = false; useDoH3 = true;  dohResolver.enabled = false; doh3Resolver.setProvider("google") }
            else -> { Log.w("DNSphere", "⚠️ Provider inconnu: '$provider'"); return }
        }
        saveDnsConfig(provider)
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
            else -> dohResolver.getProviderName().take(3)
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

    private fun startVpnForeground() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e is android.app.ForegroundServiceStartNotAllowedException) {
                Log.e("DNSphere", "❌ Foreground service start not allowed", e)
            } else {
                Log.e("DNSphere", "❌ Error starting foreground service", e)
            }
        }
    }

    private fun startVpn() {
        if (isRunning) return

        startVpnForeground()

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
                .addRoute(DNS_SERVER_1, 32)
                .addRoute(DNS_SERVER_2, 32)
                .addRoute("1.0.0.1", 32)
                .addRoute("8.8.4.4", 32)
                .addRoute("9.9.9.9", 32)
                .addRoute("149.112.112.112", 32)
                .setMtu(1500)
                .setBlocking(false)

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

        while (isRunning) {
            try {
                val length = inputStream.read(packet)
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
                                    // Bloquer toutes les requêtes de cette app
                                    Log.d("DNSphere", "🚫 [APP:${appRule.appName}] $dnsQuery")
                                    incrementBlockCounter("AD")
                                    logBlock(dnsQuery, "APP_BLOCK")
                                    createBlockedDnsResponse(ipPacket)?.let { outputStream.write(it) }
                                    delay(1); continue
                                }

                                AppRuleType.ALLOW_ALL -> {
                                    // Bypass complet — forward sans filtrage
                                    Log.d("DNSphere", "✅ [APP:${appRule.appName}] bypass $dnsQuery")
                                    forwardDnsQuery(ipPacket)?.let { outputStream.write(it) }
                                    delay(1); continue
                                }

                                else -> {
                                    // DEFAULT ou null → filtrage standard ci-dessous
                                }
                            }
                            // ─────────────────────────────────────────────────

                            // Filtrage DNS standard
                            val blockType = getBlockType(dnsQuery)

                            if (blockType != null) {
                                Log.d("DNSphere", "🚫 [$blockType] $dnsQuery")
                                incrementBlockCounter(blockType)
                                logBlock(dnsQuery, blockType)
                                createBlockedDnsResponse(ipPacket)?.let { outputStream.write(it) }
                            } else {
                                val response = when {
                                    useDot  -> forwardDnsQueryWithDoT(ipPacket)
                                    useDoQ  -> forwardDnsQueryWithDoQ(ipPacket)
                                    useDoH3 -> forwardDnsQueryWithDoH3(ipPacket)
                                    useDoH  -> forwardDnsQueryWithDoH(ipPacket)
                                    else    -> forwardDnsQuery(ipPacket)
                                }
                                response?.let { outputStream.write(it) }
                            }
                        }
                    }
                }
                delay(1)
            } catch (e: Exception) {
                if (isRunning) Log.e("DNSphere", "Erreur paquet", e)
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
    // BLOCAGE
    // =========================================================================

    private fun getBlockType(hostname: String): String? {
        if (blockListManager.isWhitelisted(hostname)) return null
        if (parentalManager.shouldBlockNow(hostname)) return "PARENTAL"
        return when {
            blockAds      && blockListManager.isAd(hostname)       -> "AD"
            blockTrackers && blockListManager.isTracker(hostname)  -> "TRACKER"
            blockMalware  && blockListManager.isMalware(hostname)  -> "MALWARE"
            blockShopping && blockListManager.isShopping(hostname) -> "SHOPPING"
            blockListManager.isExternalBlocked(hostname)           -> "EXTERNAL"
            else -> null
        }
    }

    private fun incrementBlockCounter(blockType: String) {
        when (blockType) {
            "AD"                               -> adsBlocked++
            "TRACKER"                          -> trackersBlocked++
            "MALWARE"                          -> malwareBlocked++
            "SHOPPING", "EXTERNAL", "PARENTAL",
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
            val channel = NotificationChannel(CHANNEL_ID, "DNSphere Protection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Protection DNS active"; setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
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
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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

    override fun onRevoke() {
        Log.w("DNSphere", "⚠️ VPN révoqué par le système ou une autre app")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}