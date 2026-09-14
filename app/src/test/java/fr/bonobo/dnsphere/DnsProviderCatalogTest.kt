package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.data.DnsProviderCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsProviderCatalogTest {

    @Test
    fun providerIdsAreUnique() {
        val ids = DnsProviderCatalog.ids
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun mullvadAndDns4EuAreAvailableInTheQuickCatalog() {
        assertTrue(DnsProviderCatalog.ids.contains("mullvad-adblock"))
        assertTrue(DnsProviderCatalog.ids.contains("dns4eu-protective"))
        assertTrue(DnsProviderCatalog.ids.contains("cloudflare-doh3"))
    }
}
