package fr.bonobo.dnsphere.utils

import java.net.URI

/** Validation des URLs utilisées pour télécharger des listes DNS. */
object SecureUrlValidator {

    fun isHttpsUrl(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                uri.userInfo == null
    } catch (_: Exception) {
        false
    }
}
