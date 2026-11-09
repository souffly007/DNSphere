package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.dns.DnsQuerySanitizer
import fr.bonobo.dnsphere.dns.DnsWire
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsQuerySanitizerTest {

    private fun query(host: String = "example.com", type: Int = 1): ByteArray =
        DnsWire.buildQuery(host, type)

    /** Requête avec un OPT : udp=4096, flag DO, options données (code,len,data). */
    private fun queryWithOpt(options: ByteArray): ByteArray {
        val base = query()
        val opt = ByteArray(11 + options.size)
        DnsWire.putU16(opt, 1, 41)
        DnsWire.putU16(opt, 3, 4096)
        opt[7] = 0x80.toByte() // DO bit
        DnsWire.putU16(opt, 9, options.size)
        System.arraycopy(options, 0, opt, 11, options.size)
        val out = base + opt
        DnsWire.putU16(out, 10, 1)
        return out
    }

    private fun containsEcsZero(q: ByteArray): Boolean {
        val needle = byteArrayOf(0, 8, 0, 4, 0, 1, 0, 0)
        for (i in 0..q.size - needle.size) {
            if ((needle.indices).all { q[i + it] == needle[it] }) return true
        }
        return false
    }

    @Test
    fun paddedToMultipleOfBlock() {
        for (host in listOf("a.fr", "example.com", "very-long-subdomain.tracking.example.co.uk")) {
            val p = DnsQuerySanitizer.prepare(query(host), true)
            assertEquals(0, p.query.size % DnsQuerySanitizer.PAD_BLOCK)
            assertEquals(1, DnsWire.u16(p.query, 10))
            assertTrue(containsEcsZero(p.query))
        }
    }

    @Test
    fun differentNamesGiveSameLengthWithinBlock() {
        val a = DnsQuerySanitizer.prepare(query("a.fr"), true).query.size
        val b = DnsQuerySanitizer.prepare(query("abcdefghij.example.org"), true).query.size
        assertEquals(a, b)
    }

    @Test
    fun disabledLeavesQueryUntouched() {
        val q = query()
        assertSame(q, DnsQuerySanitizer.prepare(q, false).query)
    }

    @Test
    fun existingEcsAndPaddingAreReplacedAndOthersKept() {
        val ecsReal = byteArrayOf(0, 8, 0, 7, 0, 1, 24, 0, 192.toByte(), 168.toByte(), 1)
        val padding = byteArrayOf(0, 12, 0, 3, 0, 0, 0)
        val other = byteArrayOf(0, 10, 0, 2, 7, 7) // option inconnue conservée
        val q = queryWithOpt(ecsReal + other + padding)
        val p = DnsQuerySanitizer.prepare(q, true)
        assertEquals(0, p.query.size % DnsQuerySanitizer.PAD_BLOCK)
        assertTrue(containsEcsZero(p.query))
        // l'ancienne adresse client a disparu
        val s = p.query.joinToString(",")
        assertFalse(s.contains("24,0,-64,-88,1"))
        // l'option inconnue est conservée
        assertTrue(s.contains("0,10,0,2,7,7"))
        // DO et taille UDP conservés
        val optPos = 12 + (DnsWire.skipName(p.query, 12) - 12) + 4
        assertEquals(4096, DnsWire.u16(p.query, optPos + 3))
        assertEquals(0x80, p.query[optPos + 7].toInt() and 0xFF)
    }

    @Test
    fun malformedQueryIsPassedThrough() {
        val q = query()
        val truncated = q.copyOf(q.size - 2)
        assertSame(truncated, DnsQuerySanitizer.prepare(truncated, true).query)
        val tooShort = ByteArray(5)
        assertSame(tooShort, DnsQuerySanitizer.prepare(tooShort, true).query)
    }

    @Test
    fun optAddedByUsIsStrippedFromResponse() {
        val p = DnsQuerySanitizer.prepare(query(), true)
        // Réponse : en-tête + question + 1 réponse A + OPT
        val q = query()
        val qEnd = q.size
        val answer = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 93, 184.toByte(), 216.toByte(), 34)
        val opt = byteArrayOf(0, 0, 41, 4, 0xD0.toByte(), 0, 0, 0, 0, 0, 4, 0, 12, 0, 0)
        val resp = q + answer + opt
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte()
        DnsWire.putU16(resp, 6, 1); DnsWire.putU16(resp, 10, 1)
        val restored = p.restore(resp)
        assertEquals(resp.size - opt.size, restored.size)
        assertEquals(0, DnsWire.u16(restored, 10))
        assertEquals(1, DnsWire.u16(restored, 6))
        assertArrayEquals(resp.copyOf(qEnd + answer.size).also { DnsWire.putU16(it, 10, 0) }, restored)
    }

    @Test
    fun responseKeptWhenClientAlreadyHadOpt() {
        val p = DnsQuerySanitizer.prepare(queryWithOpt(ByteArray(0)), true)
        val resp = ByteArray(40) { 1 }
        assertSame(resp, p.restore(resp))
    }

    @Test
    fun parseAddressesReadsAandAAAA() {
        val id = 0x1234
        val q = DnsWire.buildQuery("dns.example", 1)
        DnsWire.putU16(q, 0, id)
        val answer = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 1, 0x2C, 0, 4, 10, 1, 2, 3)
        val resp = q + answer
        resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte(); DnsWire.putU16(resp, 6, 1)
        val r = DnsWire.parseAddresses(resp, id, DnsWire.TYPE_A)
        assertEquals(1, r.list.size)
        assertEquals("10.1.2.3", r.list[0].hostAddress)
        assertEquals(300L, r.minTtlSeconds)
        assertTrue(DnsWire.parseAddresses(resp, id + 1, DnsWire.TYPE_A).list.isEmpty())
    }
}
