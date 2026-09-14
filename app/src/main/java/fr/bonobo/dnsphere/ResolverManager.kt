package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.dns.DohResolver
import fr.bonobo.dnsphere.network.Doh3Resolver
import fr.bonobo.dnsphere.network.DoqResolver
import fr.bonobo.dnsphere.network.DotResolver

/** Choisit le transport DNS actif et conserve le fallback UDP existant. */
class ResolverManager(
    private val packets: DnsPacketProcessor,
    private val responses: DnsResponseBuilder,
    private val doh: DohResolver,
    private val dot: DotResolver,
    private val doq: DoqResolver,
    private val doh3: Doh3Resolver,
    private val udpFallback: suspend (ByteArray) -> ByteArray?
) {
    suspend fun resolve(
        packet: ByteArray,
        useDot: Boolean,
        useDoQ: Boolean,
        useDoH3: Boolean,
        useDoH: Boolean
    ): ByteArray? = when {
        useDot -> resolveWith(packet) { dot.resolve(packets.extractDnsPayload(packet)) }
        useDoQ -> resolveWith(packet) { doq.resolve(packets.extractDnsPayload(packet)) }
        useDoH3 -> resolveWith(packet) { doh3.resolve(packets.extractDnsPayload(packet)) }
        useDoH -> resolveWith(packet) { doh.resolve(packets.extractDnsPayload(packet)) }
        else -> udpFallback(packet)
    }

    private suspend fun resolveWith(
        packet: ByteArray,
        resolver: suspend () -> ByteArray?
    ): ByteArray? = try {
        resolver()?.let { responses.buildResponsePacket(packet, it) } ?: udpFallback(packet)
    } catch (_: Exception) {
        udpFallback(packet)
    }
}
