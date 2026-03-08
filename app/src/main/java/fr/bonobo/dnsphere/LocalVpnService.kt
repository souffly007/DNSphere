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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.BlockLog
import fr.bonobo.dnsphere.dns.DohResolver
import kotlinx.coroutines.*
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class LocalVpnService : VpnService() {

    companion object {
        const val ACTION_START = "fr.bonobo.dnsphere.START"
        const val ACTION_STOP = "fr.bonobo.dnsphere.STOP"
        const val ACTION_UPDATE_CONFIG = "fr.bonobo.dnsphere.UPDATE_CONFIG"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "vpn_channel"

        const val DNS_SERVER_1 = "1.1.1.1"
        const val DNS_SERVER_2 = "8.8.8.8"

        @Volatile
        var isRunning = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    private var blockAds = true
    private var blockTrackers = true
    private var blockMalware = true
    private var blockShopping = true
    private var useDoH = false

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var blockListManager: BlockListManager
    private lateinit var database: AppDatabase

    // ✅ CORRECTION : Initialisation tardive avec Context
    private lateinit var dohResolver: DohResolver

    private var adsBlocked = 0
    private var trackersBlocked = 0
    private var malwareBlocked = 0
    private var shoppingBlocked = 0

    override fun onCreate() {
        super.onCreate()
        blockListManager = BlockListManager(this)
        database = AppDatabase.getInstance(this)

        // ✅ CORRECTION : Initialiser DohResolver avec le Context
        dohResolver = DohResolver.getInstance(this)

        createNotificationChannel()

        // ✅ Debug : Afficher le provider actuel au démarrage
        Log.d("DNSphere", "🚀 Service created - Current DoH provider: ${dohResolver.getProviderName()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                blockAds = intent.getBooleanExtra("block_ads", true)
                blockTrackers = intent.getBooleanExtra("block_trackers", true)
                blockMalware = intent.getBooleanExtra("block_malware", true)
                blockShopping = intent.getBooleanExtra("block_shopping", true)
                useDoH = intent.getBooleanExtra("use_doh", false)

                dohResolver.enabled = useDoH

                // ✅ CORRECTION : Sauvegarder le provider reçu
                val provider = intent.getStringExtra("doh_provider")
                if (provider != null) {
                    dohResolver.setProvider(provider)
                    Log.d("DNSphere", "📡 DoH provider set from intent: $provider")
                } else {
                    // Si pas de provider dans l'intent, on utilise celui sauvegardé
                    Log.d("DNSphere", "📡 Using saved DoH provider: ${dohResolver.currentProvider}")
                }

                startVpn()
            }
            ACTION_STOP -> {
                stopVpn()
            }
            ACTION_UPDATE_CONFIG -> {
                blockAds = intent.getBooleanExtra("block_ads", true)
                blockTrackers = intent.getBooleanExtra("block_trackers", true)
                blockMalware = intent.getBooleanExtra("block_malware", true)
                blockShopping = intent.getBooleanExtra("block_shopping", true)
                useDoH = intent.getBooleanExtra("use_doh", false)
                dohResolver.enabled = useDoH

                // ✅ CORRECTION : Aussi mettre à jour le provider si présent
                val provider = intent.getStringExtra("doh_provider")
                if (provider != null) {
                    dohResolver.setProvider(provider)
                }

                blockListManager.refresh()
            }
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) return

        startForeground(NOTIFICATION_ID, createNotification())

        try {
            val excludedApps = runBlocking {
                try {
                    database.excludedAppDao().getAllPackageNames()
                } catch (e: Exception) {
                    emptyList()
                }
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

            try {
                builder.addDisallowedApplication(packageName)
                Log.d("DNSphere", "Excluded own package: $packageName")
            } catch (e: Exception) {
                Log.w("DNSphere", "Could not exclude own package: ${e.message}")
            }

            excludedApps.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                    Log.d("DNSphere", "Excluded app: $pkg")
                } catch (e: Exception) {
                    Log.w("DNSphere", "Could not exclude $pkg: ${e.message}")
                }
            }

            vpnInterface = builder.establish()

            if (vpnInterface != null) {
                isRunning = true

                // ✅ Log amélioré avec le vrai provider
                Log.d("DNSphere", "✅ VPN started - DoH: $useDoH, Provider: ${dohResolver.getProviderName()}, Excluded apps: ${excludedApps.size}")

                serviceScope.launch {
                    handleDnsRequests()
                }

                serviceScope.launch {
                    sendStatsUpdates()
                }
            } else {
                Log.e("DNSphere", "VPN interface is null")
                stopVpn()
            }

        } catch (e: Exception) {
            Log.e("DNSphere", "Error starting VPN", e)
            stopVpn()
        }
    }

    private suspend fun handleDnsRequests() {
        val vpnFileDescriptor = vpnInterface?.fileDescriptor ?: return
        val inputStream = FileInputStream(vpnFileDescriptor)
        val outputStream = FileOutputStream(vpnFileDescriptor)

        val packet = ByteArray(32767)

        while (isRunning) {
            try {
                val length = inputStream.read(packet)
                if (length > 0) {
                    val ipPacket = packet.copyOf(length)

                    if (isDnsPacket(ipPacket)) {
                        val dnsQuery = extractDnsQuery(ipPacket)

                        if (dnsQuery != null) {
                            Log.d("DNSphere", "DNS Query: $dnsQuery")

                            val blockType = getBlockType(dnsQuery)

                            if (blockType != null) {
                                Log.d("DNSphere", "BLOCKED [$blockType]: $dnsQuery")
                                incrementBlockCounter(blockType)
                                logBlock(dnsQuery, blockType)

                                val blockedResponse = createBlockedDnsResponse(ipPacket)
                                if (blockedResponse != null) {
                                    outputStream.write(blockedResponse)
                                }
                            } else {
                                val response = if (useDoH) {
                                    forwardDnsQueryWithDoH(ipPacket)
                                } else {
                                    forwardDnsQuery(ipPacket)
                                }

                                if (response != null) {
                                    outputStream.write(response)
                                }
                            }
                        }
                    }
                }

                delay(1)

            } catch (e: Exception) {
                if (isRunning) {
                    Log.e("DNSphere", "Error handling packet", e)
                }
            }
        }
    }

    private suspend fun forwardDnsQueryWithDoH(originalPacket: ByteArray): ByteArray? {
        try {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val dnsStart = ipHeaderLength + 8
            val dnsQuery = originalPacket.copyOfRange(dnsStart, originalPacket.size)

            // ✅ Log pour debug
            Log.d("DNSphere", "🌐 Forwarding via DoH: ${dohResolver.getProviderName()}")

            val dnsResponse = dohResolver.resolve(dnsQuery)

            return if (dnsResponse != null) {
                buildResponsePacket(originalPacket, dnsResponse)
            } else {
                forwardDnsQuery(originalPacket)
            }
        } catch (e: Exception) {
            Log.e("DNSphere", "DoH forward failed", e)
            return forwardDnsQuery(originalPacket)
        }
    }

    // ... Reste du code identique ...

    private fun isDnsPacket(packet: ByteArray): Boolean {
        if (packet.size < 28) return false
        val version = (packet[0].toInt() shr 4) and 0x0F
        if (version != 4) return false
        val protocol = packet[9].toInt() and 0xFF
        if (protocol != 17) return false
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (packet[ipHeaderLength + 3].toInt() and 0xFF)
        return destPort == 53
    }

    private fun extractDnsQuery(packet: ByteArray): String? {
        try {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            val udpHeaderLength = 8
            val dnsStart = ipHeaderLength + udpHeaderLength
            if (packet.size < dnsStart + 12) return null
            var position = dnsStart + 12
            val domainParts = mutableListOf<String>()
            while (position < packet.size) {
                val labelLength = packet[position].toInt() and 0xFF
                if (labelLength == 0) break
                position++
                if (position + labelLength > packet.size) break
                val label = String(packet, position, labelLength, Charsets.UTF_8)
                domainParts.add(label)
                position += labelLength
            }
            return if (domainParts.isNotEmpty()) {
                domainParts.joinToString(".").lowercase()
            } else null
        } catch (e: Exception) {
            Log.e("DNSphere", "Error extracting DNS query", e)
            return null
        }
    }

    private fun forwardDnsQuery(originalPacket: ByteArray): ByteArray? {
        try {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val dnsStart = ipHeaderLength + 8
            val dnsQuery = originalPacket.copyOfRange(dnsStart, originalPacket.size)
            val socket = DatagramSocket()
            protect(socket)
            socket.soTimeout = 5000
            val dnsServer = InetAddress.getByName(DNS_SERVER_1)
            val requestPacket = DatagramPacket(dnsQuery, dnsQuery.size, dnsServer, 53)
            socket.send(requestPacket)
            val responseBuffer = ByteArray(512)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            socket.close()
            val dnsResponse = responseBuffer.copyOf(responsePacket.length)
            return buildResponsePacket(originalPacket, dnsResponse)
        } catch (e: Exception) {
            Log.e("DNSphere", "Error forwarding DNS", e)
            return null
        }
    }

    private fun createBlockedDnsResponse(originalPacket: ByteArray): ByteArray? {
        try {
            val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
            val dnsStart = ipHeaderLength + 8
            val dnsQuery = originalPacket.copyOfRange(dnsStart, originalPacket.size)
            val dnsResponse = dnsQuery.copyOf()
            dnsResponse[2] = (dnsResponse[2].toInt() or 0x80).toByte()
            dnsResponse[3] = (dnsResponse[3].toInt() or 0x03).toByte()
            return buildResponsePacket(originalPacket, dnsResponse)
        } catch (e: Exception) {
            Log.e("DNSphere", "Error creating blocked response", e)
            return null
        }
    }

    private fun buildResponsePacket(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
        val totalLength = ipHeaderLength + 8 + dnsResponse.size
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
        responsePacket[ipHeaderLength] = ((dstPort shr 8) and 0xFF).toByte()
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
        packet[10] = 0
        packet[11] = 0
        var sum = 0
        for (i in 0 until ipHeaderLength step 2) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        packet[10] = ((checksum shr 8) and 0xFF).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }

    private fun getBlockType(hostname: String): String? {
        if (blockListManager.isWhitelisted(hostname)) {
            return null
        }
        return when {
            blockAds && blockListManager.isAd(hostname) -> "AD"
            blockTrackers && blockListManager.isTracker(hostname) -> "TRACKER"
            blockMalware && blockListManager.isMalware(hostname) -> "MALWARE"
            blockShopping && blockListManager.isShopping(hostname) -> "SHOPPING"
            blockListManager.isExternalBlocked(hostname) -> "EXTERNAL"
            else -> null
        }
    }

    private fun incrementBlockCounter(blockType: String) {
        when (blockType) {
            "AD" -> adsBlocked++
            "TRACKER" -> trackersBlocked++
            "MALWARE" -> malwareBlocked++
            "SHOPPING" -> shoppingBlocked++
            "EXTERNAL" -> adsBlocked++
        }
    }

    private fun logBlock(hostname: String, type: String) {
        serviceScope.launch {
            try {
                database.blockLogDao().insert(
                    BlockLog(domain = hostname, type = type)
                )
            } catch (e: Exception) {
                Log.e("DNSphere", "Error logging block", e)
            }
        }
    }

    private suspend fun sendStatsUpdates() {
        while (isRunning) {
            val intent = Intent("vpn_stats").apply {
                putExtra("ads_blocked", adsBlocked)
                putExtra("trackers_blocked", trackersBlocked)
                putExtra("malware_blocked", malwareBlocked)
                putExtra("shopping_blocked", shoppingBlocked)
            }
            LocalBroadcastManager.getInstance(this@LocalVpnService).sendBroadcast(intent)
            updateNotification()
            delay(1000)
        }
    }

    private fun stopVpn() {
        isRunning = false
        serviceScope.cancel()
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            Log.e("DNSphere", "Error closing VPN", e)
        }
        vpnInterface = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DNSphere Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Protection DNS active"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // ✅ Afficher le vrai provider DoH dans la notification
        val dohStatus = if (useDoH) " | DoH: ${dohResolver.getProviderName()}" else ""
        val totalBlocked = adsBlocked + trackersBlocked + malwareBlocked + shoppingBlocked

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🛡️ DNSphere actif")
            .setContentText("$totalBlocked bloqués (${adsBlocked} pubs, ${trackersBlocked} trackers)$dohStatus")
            .setSmallIcon(R.drawable.ic_shield)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}