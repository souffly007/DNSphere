package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.utils.SecureUrlValidator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureUrlValidatorTest {

    @Test
    fun acceptsHttpsUrlsWithQueryParameters() {
        assertTrue(
            SecureUrlValidator.isHttpsUrl(
                "https://example.org/list.txt?format=hosts"
            )
        )
    }

    @Test
    fun rejectsHttpAndCredentialUrls() {
        assertFalse(SecureUrlValidator.isHttpsUrl("http://example.org/list.txt"))
        assertFalse(SecureUrlValidator.isHttpsUrl("https://user:password@example.org/list.txt"))
        assertFalse(SecureUrlValidator.isHttpsUrl("not a url"))
    }
}
