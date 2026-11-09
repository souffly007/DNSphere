package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.dns.DnsQuerySanitizer
import fr.bonobo.dnsphere.dns.DohResolver
import fr.bonobo.dnsphere.dns.PrivacyPrefs
import fr.bonobo.dnsphere.network.Doh3Resolver
import fr.bonobo.dnsphere.network.DoqResolver
import fr.bonobo.dnsphere.network.DotResolver
import kotlinx.coroutines.CancellationException

/**
 * Choisit le transport DNS actif.
 *
 * - Les requêtes envoyées sur un transport chiffré passent par [DnsQuerySanitizer]
 *   (padding EDNS + ECS à 0) si l'option est active.
 * - Si HaGeZi ou le mode strict échoue, on renvoie SERVFAIL.
 */
class ResolverManager(
    private val packets: DnsPacketProcessor,
    private val responses: DnsResponseBuilder,
    private val doh: DohResolver,
    private val dot: DotResolver,
    private val doq: DoqResolver,
    private val doh3: Doh3Resolver,
    private val privacy: PrivacyPrefs,
    private val udpFallback: suspend (ByteArray) -> ByteArray?
) {
    suspend fun resolve(
        packet: ByteArray,
        useDot: Boolean,
        useDoQ: Boolean,
        useDoH3: Boolean,
        useDoH: Boolean
    ): ByteArray? = when {
        useDot -> resolveWith(packet) { dot.resolve(it) }
        useDoQ -> resolveWith(packet) { doq.resolve(it) }
        useDoH3 -> resolveWith(packet) { doh3.resolve(it) }
        useDoH -> resolveWith(
            packet,
            requireEncrypted = doh.requiresEncryptedTransport(),
            onFallback = { doh.noteStandardFallback() }
        ) { doh.resolve(it) }
        else -> udpFallback(packet)
    }

    private suspend fun resolveWith(
        packet: ByteArray,
        requireEncrypted: Boolean = false,
        onFallback: () -> Unit = {},
        resolver: suspend (ByteArray) -> ByteArray?
    ): ByteArray? {
        val prepared = DnsQuerySanitizer.prepare(
            packets.extractDnsPayload(packet),
            privacy.queryProtection
        )
        val answer = try {
            resolver(prepared.query)?.let { prepared.restore(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        return when {
            answer != null -> responses.buildResponsePacket(packet, answer)
            privacy.strictMode || requireEncrypted -> responses.createServFailResponse(packet)
            else -> udpFallback(packet)?.also { onFallback() }
        }
    }
}
