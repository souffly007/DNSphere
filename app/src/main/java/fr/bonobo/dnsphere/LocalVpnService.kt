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
import fr.bonobo.dnsphere.data.BlockLog
import fr.bonobo.dnsphere.dns.DohResolver
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

        const val EXTRA_PAUSE_DURATION = "pause_duration_ms"

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
    private var useDoH        = false
    private var useDot        = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var blockListManager: BlockListManager
    private lateinit var parentalManager: ParentalManager
    private lateinit var database: AppDatabase
    private lateinit var dohResolver: DohResolver
    private lateinit var dotResolver: DotResolver

    private var pauseJob: Job? = null

    // Compteurs
    private var adsBlocked      = 0
    private var trackersBlocked = 0
    private var malwareBlocked  = 0
    private var shoppingBlocked = 0

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
        createNotificationChannel()
        Log.d("DNSphere", "🚀 Service created - DoH: ${dohResolver.getProviderName()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                updateNotification()
                Log.d("DNSphere", "🔄 Config rechargée à chaud")
            }

            ACTION_PAUSE -> {
                val duration = intent.getLongExtra(EXTRA_PAUSE_DURATION, 5 * 60 * 1000L)
                pauseVpn(duration)
            }

            ACTION_RESUME -> {
                resumeVpn()
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

        dohResolver.enabled = useDoH

        intent.getStringExtra("doh_provider")?.let { provider ->
            dohResolver.setProvider(provider)
            dotResolver.setServer(provider)
            Log.d("DNSphere", "📡 Provider: $provider (DoH=$useDoH, DoT=$useDot)")
        }
    }

    // =========================================================================
    // MODE PAUSE
    // =========================================================================

    private fun pauseVpn(durationMs: Long) {
        if (!isRunning) return
        isPaused = true
        updateNotification()

        Log.d("DNSphere", "⏸️ VPN en pause pour ${durationMs / 1000}s")

        cancelPause()
        pauseJob = serviceScope.launch {
            delay(durationMs)
            resumeVpn()
        }
    }

    private fun resumeVpn() {
        if (!isRunning) return
        isPaused = false
        cancelPause()
        updateNotification()
        Log.d("DNSphere", "▶️ VPN repris")
    }

    private fun cancelPause() {
        pauseJob?.cancel()
        pauseJob = null
    }

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
                try {
                    builder.addDisallowedApplication(pkg)
                    Log.d("DNSphere", "✅ App exclue: $pkg")
                } catch (e: Exception) {
                    Log.w("DNSphere", "Cannot exclude $pkg: ${e.message}")
                }
            }

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isRunning = true
                Log.d("DNSphere", "✅ VPN démarré - Apps exclues: ${excludedApps.size}")

                serviceScope.launch { handleDnsRequests() }
                serviceScope.launch { sendStatsUpdates() }
            } else {
                Log.e("DNSphere", "VPN interface null")
                stopVpn()
            }

        } catch (e: Exception) {
            Log.e("DNSphere", "Erreur démarrage VPN", e)
            stopVpn()
        }
    }

    // =========================================================================
    // BOUCLE DNS
    // =========================================================================

    private suspend fun handleDnsRequests() {
        val vpnFd      = vpnInterface?.fileDescriptor ?: return
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

                            if (isPaused) {
                                val response = forwardDnsQuery(ipPacket)
                                if (response != null) outputStream.write(response)
                                delay(1)
                                continue
                            }

                            val blockType = getBlockType(dnsQuery)

                            if (blockType != null) {
                                Log.d("DNSphere", "🚫 [$blockType] $dnsQuery")
                                incrementBlockCounter(blockType)
                                logBlock(dnsQuery, blockType)
                                val blocked = createBlockedDnsResponse(ipPacket)
                                if (blocked != null) outputStream.write(blocked)
                            } else {
                                val response = when {
                                    useDot  -> forwardDnsQueryWithDoT(ipPacket)
                                    useDoH  -> forwardDnsQueryWithDoH(ipPacket)
                                    else    -> forwardDnsQuery(ipPacket)
                                }
                                if (response != null) outputStream.write(response)
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

    private suspend fun forwardDnsQueryWithDoT(originalPacket: ByteArray): ByteArray? {
        return try {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val dnsStart       = ipHeaderLength + 8
            val dnsQuery       = originalPacket.copyOfRange(dnsStart, originalPacket.size)

            Log.d("DNSphere", "🔐 DoT forward: ${dotResolver.dotServer}")
            val dnsResponse = dotResolver.resolve(dnsQuery)

            if (dnsResponse != null) buildResponsePacket(originalPacket, dnsResponse)
            else forwardDnsQuery(originalPacket)
        } catch (e: Exception) {
            Log.e("DNSphere", "DoT failed, fallback UDP", e)
            forwardDnsQuery(originalPacket)
        }
    }

    private suspend fun forwardDnsQueryWithDoH(originalPacket: ByteArray): ByteArray? {
        return try {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val dnsStart       = ipHeaderLength + 8
            val dnsQuery       = originalPacket.copyOfRange(dnsStart, originalPacket.size)

            Log.d("DNSphere", "🌐 DoH forward: ${dohResolver.getProviderName()}")
            val dnsResponse = dohResolver.resolve(dnsQuery)

            if (dnsResponse != null) buildResponsePacket(originalPacket, dnsResponse)
            else forwardDnsQuery(originalPacket)
        } catch (e: Exception) {
            Log.e("DNSphere", "DoH failed, fallback UDP", e)
            forwardDnsQuery(originalPacket)
        }
    }

    private fun forwardDnsQuery(originalPacket: ByteArray): ByteArray? {
        return try {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val dnsStart       = ipHeaderLength + 8
            val dnsQuery       = originalPacket.copyOfRange(dnsStart, originalPacket.size)

            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 5000

            val dnsServer = InetAddress.getByName(DNS_SERVER_1)
            socket.send(DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53))

            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            socket.close()

            buildResponsePacket(originalPacket, responseBuffer.copyOf(responsePacket.length))
        } catch (e: Exception) {
            Log.e("DNSphere", "UDP DNS failed", e)
            null
        }
    }

    private fun getBlockType(hostname: String): String? {
        if (blockListManager.isWhitelisted(hostname)) return null

        if (parentalManager.shouldBlockNow(hostname)) return "PARENTAL"

        return when {
            blockAds      && blockListManager.isAd(hostname)             -> "AD"
            blockTrackers && blockListManager.isTracker(hostname)        -> "TRACKER"
            blockMalware  && blockListManager.isMalware(hostname)        -> "MALWARE"
            blockShopping && blockListManager.isShopping(hostname)       -> "SHOPPING"
            blockListManager.isExternalBlocked(hostname)                  -> "EXTERNAL"
            else -> null
        }
    }

    private fun incrementBlockCounter(blockType: String) {
        when (blockType) {
            "AD"       -> adsBlocked++
            "TRACKER"  -> trackersBlocked++
            "MALWARE"  -> malwareBlocked++
            "SHOPPING", "EXTERNAL", "PARENTAL" -> adsBlocked++
        }
    }

    private fun logBlock(hostname: String, type: String) {
        serviceScope.launch {
            try { database.blockLogDao().insert(BlockLog(domain = hostname, type = type)) }
            catch (e: Exception) { Log.e("DNSphere", "Erreur log", e) }
        }
    }

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
            var position = ipHeaderLength + 8 + 12
            val parts = mutableListOf<String>()
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
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val dnsStart       = ipHeaderLength + 8
            val dnsResponse    = originalPacket.copyOfRange(dnsStart, originalPacket.size)
            dnsResponse[2] = (dnsResponse[2].toInt() or 0x80).toByte()
            dnsResponse[3] = (dnsResponse[3].toInt() or 0x03).toByte()
            buildResponsePacket(originalPacket, dnsResponse)
        } catch (e: Exception) { null }
    }

    private fun buildResponsePacket(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
        val totalLength    = ipHeaderLength + 8 + dnsResponse.size
        val responsePacket = ByteArray(totalLength)

        System.arraycopy(originalPacket, 0, responsePacket, 0, ipHeaderLength)
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
    // STATS + NOTIFICATIONS - ✅ MODIFIÉ : LiveData au lieu de LocalBroadcastManager
    // =========================================================================

    private suspend fun sendStatsUpdates() {
        while (isRunning) {
            // ✅ NOUVEAU : Utiliser LiveData
            StatsLiveData.updateStats(
                VpnStats(
                    adsBlocked = adsBlocked,
                    trackersBlocked = trackersBlocked,
                    malwareBlocked = malwareBlocked,
                    shoppingBlocked = shoppingBlocked,
                    isPaused = isPaused
                )
            )
            updateNotification()
            delay(1000)
        }
    }

    private fun stopVpn() {
        isRunning = false
        isPaused  = false
        cancelPause()
        serviceScope.cancel()
        try { vpnInterface?.close() } catch (e: Exception) { }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // =========================================================================
    // NOTIFICATIONS
    // =========================================================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "DNSphere Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Protection DNS active"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = if (isPaused) {
            PendingIntent.getService(
                this, 1,
                Intent(this, LocalVpnService::class.java).apply { action = ACTION_RESUME },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            PendingIntent.getService(
                this, 2,
                Intent(this, LocalVpnService::class.java).apply {
                    action = ACTION_PAUSE
                    putExtra(EXTRA_PAUSE_DURATION, 5 * 60 * 1000L)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val totalBlocked = adsBlocked + trackersBlocked + malwareBlocked + shoppingBlocked
        val dnsMode = when {
            useDot -> "DoT"
            useDoH -> "DoH: ${dohResolver.getProviderName()}"
            else   -> "DNS standard"
        }

        val title = if (isPaused) "⏸️ DNSphere en pause" else "🛡️ DNSphere actif"
        val text  = if (isPaused)
            "Protection suspendue temporairement"
        else
            "$totalBlocked bloqués (${adsBlocked} pubs, ${trackersBlocked} trackers) | $dnsMode"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_pause,
                if (isPaused) "▶️ Reprendre" else "⏸️ Pause 5min",
                pauseResumeIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}