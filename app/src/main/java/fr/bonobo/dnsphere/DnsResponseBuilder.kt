package fr.bonobo.dnsphere

/** Construction des réponses DNS IPv4/UDP générées par le VPN. */
class DnsResponseBuilder(private val packets: DnsPacketProcessor) {

    fun buildTcpRstPacket(originalPacket: ByteArray): ByteArray? = try {
        val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
        if (originalPacket.size < ipHeaderLength + 20) null else {
            val totalLength = ipHeaderLength + 20
            val response = ByteArray(totalLength)
            System.arraycopy(originalPacket, 0, response, 0, ipHeaderLength)
            response[0] = 0x45
            response[8] = 64
            response[9] = 6
            System.arraycopy(originalPacket, 12, response, 16, 4)
            System.arraycopy(originalPacket, 16, response, 12, 4)
            response[2] = (totalLength shr 8).toByte()
            response[3] = totalLength.toByte()
            val src = ((originalPacket[ipHeaderLength].toInt() and 0xFF) shl 8) or
                (originalPacket[ipHeaderLength + 1].toInt() and 0xFF)
            val dst = ((originalPacket[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                (originalPacket[ipHeaderLength + 3].toInt() and 0xFF)
            response[ipHeaderLength] = (dst shr 8).toByte()
            response[ipHeaderLength + 1] = dst.toByte()
            response[ipHeaderLength + 2] = (src shr 8).toByte()
            response[ipHeaderLength + 3] = src.toByte()
            val dataOffset = ((originalPacket[ipHeaderLength + 12].toInt() shr 4) and 0x0F) * 4
            val flags = originalPacket[ipHeaderLength + 13].toInt() and 0xFF
            val hasAck = (flags and 0x10) != 0
            val synFin = (flags and 0x03) != 0
            val seq = readInt32(originalPacket, ipHeaderLength + 4)
            val ack = if (hasAck) readInt32(originalPacket, ipHeaderLength + 8) else 0
            val payload = maxOf(0, originalPacket.size - ipHeaderLength - dataOffset)
            writeInt32(response, ipHeaderLength + 4, if (hasAck) ack else 0)
            writeInt32(response, ipHeaderLength + 8, seq + payload + if (synFin) 1 else 0)
            response[ipHeaderLength + 12] = 0x50
            response[ipHeaderLength + 13] = if (hasAck) 0x14 else 0x04
            updateTcpChecksum(response, ipHeaderLength)
            updateIpChecksum(response, ipHeaderLength)
            response
        }
    } catch (_: Exception) { null }

    private fun readInt32(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

    private fun writeInt32(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 24).toByte()
        data[offset + 1] = (value shr 16).toByte()
        data[offset + 2] = (value shr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun updateTcpChecksum(packet: ByteArray, ipHeaderLength: Int) {
        val tcpLength = packet.size - ipHeaderLength
        packet[ipHeaderLength + 16] = 0
        packet[ipHeaderLength + 17] = 0
        var sum = 6L + tcpLength
        for (i in 0 until 4 step 2) {
            sum += ((packet[12 + i].toInt() and 0xFF) shl 8) or (packet[12 + i + 1].toInt() and 0xFF)
            sum += ((packet[16 + i].toInt() and 0xFF) shl 8) or (packet[16 + i + 1].toInt() and 0xFF)
        }
        var i = ipHeaderLength
        while (i < packet.size - 1) { sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF); i += 2 }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.toInt().inv() and 0xFFFF
        packet[ipHeaderLength + 16] = (checksum shr 8).toByte()
        packet[ipHeaderLength + 17] = checksum.toByte()
    }

    fun createBlockedResponse(originalPacket: ByteArray): ByteArray? = try {
        val dnsResponse = packets.extractDnsPayload(originalPacket).copyOf()
        dnsResponse[2] = (dnsResponse[2].toInt() or 0x80).toByte()
        dnsResponse[3] = (dnsResponse[3].toInt() or 0x03).toByte()
        buildResponsePacket(originalPacket, dnsResponse)
    } catch (_: Exception) { null }

    fun createSafeSearchResponse(originalPacket: ByteArray, safeIp: ByteArray): ByteArray? = try {
        val dnsQuery = packets.extractDnsPayload(originalPacket)
        if (dnsQuery.size < 12 || safeIp.size < 4) {
            null
        } else {
            val answer = byteArrayOf(
                0xC0.toByte(), 0x0C.toByte(), 0x00, 0x01, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x78, 0x00, 0x04,
                safeIp[0], safeIp[1], safeIp[2], safeIp[3]
            )
            val response = ByteArray(dnsQuery.size + answer.size)
            System.arraycopy(dnsQuery, 0, response, 0, dnsQuery.size)
            System.arraycopy(answer, 0, response, dnsQuery.size, answer.size)
            response[2] = 0x81.toByte()
            response[3] = 0x80.toByte()
            response[6] = 0
            response[7] = 1
            buildResponsePacket(originalPacket, response)
        }
    } catch (_: Exception) { null }

    fun buildResponsePacket(originalPacket: ByteArray, dnsResponse: ByteArray): ByteArray {
        val ipHeaderLength = (originalPacket[0].toInt() and 0x0F) * 4
        val totalLength = ipHeaderLength + 8 + dnsResponse.size
        val packet = ByteArray(totalLength)
        System.arraycopy(originalPacket, 0, packet, 0, ipHeaderLength)
        System.arraycopy(originalPacket, 12, packet, 16, 4)
        System.arraycopy(originalPacket, 16, packet, 12, 4)
        packet[2] = (totalLength shr 8).toByte()
        packet[3] = totalLength.toByte()
        val srcPort = ((originalPacket[ipHeaderLength].toInt() and 0xFF) shl 8) or
            (originalPacket[ipHeaderLength + 1].toInt() and 0xFF)
        val dstPort = ((originalPacket[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
            (originalPacket[ipHeaderLength + 3].toInt() and 0xFF)
        packet[ipHeaderLength] = (dstPort shr 8).toByte()
        packet[ipHeaderLength + 1] = dstPort.toByte()
        packet[ipHeaderLength + 2] = (srcPort shr 8).toByte()
        packet[ipHeaderLength + 3] = srcPort.toByte()
        val udpLength = 8 + dnsResponse.size
        packet[ipHeaderLength + 4] = (udpLength shr 8).toByte()
        packet[ipHeaderLength + 5] = udpLength.toByte()
        packet[ipHeaderLength + 6] = 0
        packet[ipHeaderLength + 7] = 0
        System.arraycopy(dnsResponse, 0, packet, ipHeaderLength + 8, dnsResponse.size)
        updateIpChecksum(packet, ipHeaderLength)
        return packet
    }

    fun updateIpChecksum(packet: ByteArray, ipHeaderLength: Int = (packet[0].toInt() and 0x0F) * 4) {
        packet[10] = 0
        packet[11] = 0
        var sum = 0
        for (i in 0 until ipHeaderLength step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum shr 8).toByte()
        packet[11] = checksum.toByte()
    }
}
