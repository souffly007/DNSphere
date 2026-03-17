package fr.bonobo.dnsphere.lists

import fr.bonobo.dnsphere.data.ListFormat

object ListParser {

    // Regex pour valider un domaine
    private val DOMAIN_REGEX = Regex(
        "^([a-zA-Z0-9]([a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}$"
    )

    // Domaines à ignorer
    private val IGNORED_DOMAINS = setOf(
        "localhost",
        "localhost.localdomain",
        "local",
        "broadcasthost",
        "ip6-localhost",
        "ip6-loopback",
        "ip6-localnet",
        "ip6-mcastprefix",
        "ip6-allnodes",
        "ip6-allrouters",
        "0.0.0.0"
    )

    /**
     * Parse le contenu d'une liste selon son format
     */
    fun parse(content: String, format: ListFormat): List<String> {
        return when (format) {
            ListFormat.HOSTS -> parseHosts(content)
            ListFormat.ADBLOCK -> parseAdBlock(content)
            ListFormat.DOMAINS -> parseDomains(content)
            ListFormat.DNSMASQ -> parseDnsmasq(content)
        }
    }

    /**
     * Format HOSTS: "0.0.0.0 domain.com" ou "127.0.0.1 domain.com"
     */
    private fun parseHosts(content: String): List<String> {
        return content.lines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("!") }
            .mapNotNull { line ->
                // Enlever les commentaires en fin de ligne
                val cleanLine = line.split("#")[0].split("!")[0].trim()

                // Séparer par espaces ou tabs
                val parts = cleanLine.split(Regex("\\s+"))

                when {
                    parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> {
                        parts[1].lowercase()
                    }
                    parts.size == 1 && isValidDomain(parts[0]) -> {
                        parts[0].lowercase()
                    }
                    else -> null
                }
            }
            .filter { isValidDomain(it) && it !in IGNORED_DOMAINS }
            .distinct()
            .toList()
    }

    /**
     * Format ADBLOCK: "||domain.com^" ou "@@||domain.com^" (whitelist)
     */
    private fun parseAdBlock(content: String): List<String> {
        return content.lines()
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("!") && !it.startsWith("[") && !it.startsWith("@@") }
            .mapNotNull { line ->
                when {
                    // Format ||domain.com^
                    line.startsWith("||") && line.contains("^") -> {
                        val domain = line
                            .removePrefix("||")
                            .substringBefore("^")
                            .substringBefore("$")
                            .substringBefore("/")
                            .lowercase()
                        domain.takeIf { isValidDomain(it) }
                    }
                    // Format ||domain.com
                    line.startsWith("||") -> {
                        val domain = line
                            .removePrefix("||")
                            .substringBefore("$")
                            .substringBefore("/")
                            .lowercase()
                        domain.takeIf { isValidDomain(it) }
                    }
                    else -> null
                }
            }
            .filter { it !in IGNORED_DOMAINS }
            .distinct()
            .toList()
    }

    /**
     * Format DOMAINS: un domaine par ligne
     */
    private fun parseDomains(content: String): List<String> {
        return content.lines()
            .asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.split("#")[0].split("!")[0].trim() }
            .filter { isValidDomain(it) && it !in IGNORED_DOMAINS }
            .distinct()
            .toList()
    }

    /**
     * Format DNSMASQ: "address=/domain.com/0.0.0.0"
     */
    private fun parseDnsmasq(content: String): List<String> {
        return content.lines()
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("address=/") || it.startsWith("server=/") }
            .mapNotNull { line ->
                val parts = line.split("/")
                if (parts.size >= 2) {
                    parts[1].lowercase().takeIf { isValidDomain(it) }
                } else null
            }
            .filter { it !in IGNORED_DOMAINS }
            .distinct()
            .toList()
    }

    /**
     * Détecte automatiquement le format d'une liste
     */
    fun detectFormat(content: String): ListFormat {
        val lines = content.lines().take(100).filter {
            it.isNotBlank() && !it.startsWith("#") && !it.startsWith("!") && !it.startsWith("[")
        }

        return when {
            lines.any { it.startsWith("address=/") || it.startsWith("server=/") } -> ListFormat.DNSMASQ
            lines.any { it.startsWith("||") } -> ListFormat.ADBLOCK
            lines.any { it.startsWith("0.0.0.0 ") || it.startsWith("127.0.0.1 ") } -> ListFormat.HOSTS
            else -> ListFormat.DOMAINS
        }
    }

    /**
     * Valide qu'une chaîne est un domaine valide
     */
    private fun isValidDomain(domain: String): Boolean {
        if (domain.isBlank() || domain.length > 253) return false
        if (domain.contains("*")) return false  // Pas de wildcards
        if (domain.contains("/")) return false
        if (domain.startsWith(".") || domain.endsWith(".")) return false
        if (domain.startsWith("-") || domain.endsWith("-")) return false

        return DOMAIN_REGEX.matches(domain)
    }
}