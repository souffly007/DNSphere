package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.data.ProviderMigration
import fr.bonobo.dnsphere.data.DnsProviderCatalog
import fr.bonobo.dnsphere.dns.DohResponsePolicy
import org.junit.Assert.*
import org.junit.Test

class HageziTest {
    @Test fun quad9RemainsSelectableAndSavedUrlsAreCanonicalized() {
        assertEquals("quad9", ProviderMigration.normalize(" Quad9 "))
        assertEquals("quad9", ProviderMigration.normalize("https://dns.quad9.net/dns-query"))
        assertEquals("quad9", ProviderMigration.normalize("https://dns.quad9.net:5053/dns-query"))
        assertEquals("quad9-unfiltered", ProviderMigration.normalize("quad9-unfiltered"))
        for (id in listOf("mullvad", "dns4eu-child", "hagezi-security", "hagezi-full", "standard")) {
            assertEquals(id, ProviderMigration.normalize(id))
        }
        assertTrue(DnsProviderCatalog.ids.containsAll(listOf("quad9", "hagezi-security", "hagezi-full")))
        assertEquals("Quad9 (Hors France)", DnsProviderCatalog.labelFor("quad9"))
    }

    private val query = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        1, 97, 0, 0, 1, 0, 1)
    private fun answer(rcode: Int) = query.copyOf().also {
        it[2] = 0x81.toByte(); it[3] = (0x80 or rcode).toByte()
    }
    @Test fun normalFilteringAnswersDoNotCauseFailover() {
        for (rcode in listOf(0, 3, 5)) assertTrue(DohResponsePolicy.isUsable(query, answer(rcode)))
    }
    @Test fun serviceFailureAndMalformedAnswersCauseFailover() {
        assertFalse(DohResponsePolicy.isUsable(query, answer(2)))
        assertFalse(DohResponsePolicy.isUsable(query, byteArrayOf(1, 2)))
        assertFalse(DohResponsePolicy.isUsable(query, answer(0).also { it[0] = 99 }))
        assertFalse(DohResponsePolicy.isUsable(query, answer(0).also { it[13] = 98 }))
        assertFalse(DohResponsePolicy.isUsable(query, query))
    }
    @Test fun aaaaQuestionsAreAccepted() {
        val aaaa = query.copyOf().also { it[16] = 28 }
        val response = aaaa.copyOf().also { it[2] = 0x81.toByte() }
        assertTrue(DohResponsePolicy.isUsable(aaaa, response))
    }
}
