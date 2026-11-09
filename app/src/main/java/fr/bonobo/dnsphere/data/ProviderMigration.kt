package fr.bonobo.dnsphere.data

/** Canonicalize saved Quad9 DoH URLs to the selectable provider identifier. */
object ProviderMigration {
    fun normalize(value: String): String = when (value.lowercase().trim()) {
        "https://dns.quad9.net/dns-query", "https://dns.quad9.net:5053/dns-query" -> "quad9"
        else -> value.lowercase().trim()
    }
}
