package fr.bonobo.dnsphere

/**
 * Utilitaires purs de lecture des paquets DNS IPv4/UDP du tunnel VPN.
 * Aucun état Android, réseau ou filtrage n'est utilisé ici.
 */
class DnsPacketProcessor {

    fun isDnsPacket(packet: ByteArray): Boolean {
        if (packet.size < 28) return false
        if ((packet[0].toInt() shr 4) and 0x0F != 4) return false
        if (packet[9].toInt() and 0xFF != 17) return false
        val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
        if (ipHeaderLength + 4 > packet.size) return false
        val destPort = ((packet[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
            (packet[ipHeaderLength + 3].toInt() and 0xFF)
        return destPort == 53
    }

    fun extractDnsPayload(ipPacket: ByteArray): ByteArray {
        val ipHeaderLength = (ipPacket[0].toInt() and 0x0F) * 4
        return ipPacket.copyOfRange(ipHeaderLength + 8, ipPacket.size)
    }

    /** Lit le QTYPE (A=1, AAAA=28, etc.) de la question DNS. */
    fun extractQType(packet: ByteArray): Int {
        return try {
            val ipHeaderLength = (packet[0].toInt() and 0x0F) * 4
            var position = ipHeaderLength + 8 + 12
            while (position < packet.size) {
                val len = packet[position].toInt() and 0xFF
                if (len == 0) { position++; break }
                position += 1 + len
            }
            if (position + 1 >= packet.size) return 1
            ((packet[position].toInt() and 0xFF) shl 8) or
                (packet[position + 1].toInt() and 0xFF)
        } catch (_: Exception) { 1 }
    }

    fun extractDnsQuery(packet: ByteArray): String? {
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
        } catch (_: Exception) { null }
    }

    fun rewriteTransactionId(cachedPayload: ByteArray, queryPacket: ByteArray): ByteArray {
        val queryPayload = extractDnsPayload(queryPacket)
        val rewritten = cachedPayload.copyOf()
        if (rewritten.size >= 2 && queryPayload.size >= 2) {
            rewritten[0] = queryPayload[0]
            rewritten[1] = queryPayload[1]
        }
        return rewritten
    }
}
