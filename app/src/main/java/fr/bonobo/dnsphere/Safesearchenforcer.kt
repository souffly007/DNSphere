package fr.bonobo.dnsphere

/**
 * SafeSearchEnforcer — redirection DNS vers les IPs SafeSearch
 *
 * Principe : au lieu de bloquer google.com, on retourne l'IP de
 * forcesafesearch.google.com. Google active alors le filtre strict
 * côté serveur, sans que l'enfant puisse le désactiver.
 *
 * Idem pour YouTube (restrict.youtube.com) et Bing (strict.bing.com).
 *
 * DuckDuckGo et Brave Search n'ont pas d'équivalent DNS → bloqués entièrement.
 */
object SafeSearchEnforcer {

    // =========================================================================
    // IPs SafeSearch officielles
    // =========================================================================

    // Google : forcesafesearch.google.com
    private val IP_GOOGLE    = byteArrayOf(216.toByte(), 239.toByte(), 38, 120.toByte())

    // YouTube : restrict.youtube.com
    private val IP_YOUTUBE   = byteArrayOf(216.toByte(), 239.toByte(), 38, 119.toByte())

    // Bing : strict.bing.com
    private val IP_BING      = byteArrayOf(204.toByte(), 79, 197.toByte(), 220.toByte())

    // =========================================================================
    // Domaines → IP SafeSearch
    // =========================================================================

    private val SAFESEARCH_MAP: Map<String, ByteArray> = buildMap {

        // Google — tous les TLDs courants
        val googleTlds = listOf(
            "google.com", "google.fr", "google.de", "google.co.uk",
            "google.es", "google.it", "google.be", "google.ch",
            "google.ca", "google.pt", "google.nl", "google.pl",
            "google.at", "google.se", "google.no", "google.dk",
            "google.fi", "google.ie", "google.gr", "google.ro",
            "google.hu", "google.cz", "google.sk", "google.hr",
            "google.rs", "google.bg", "google.lt", "google.lv",
            "google.ee", "google.lu", "google.si", "google.com.au",
            "google.co.nz", "google.co.jp", "google.co.in",
            "google.com.br", "google.com.mx", "google.com.ar"
        )
        googleTlds.forEach { domain ->
            put(domain, IP_GOOGLE)
            put("www.$domain", IP_GOOGLE)
            put("m.$domain", IP_GOOGLE)
        }

        // YouTube
        listOf("youtube.com", "www.youtube.com", "m.youtube.com",
            "youtu.be", "music.youtube.com").forEach {
            put(it, IP_YOUTUBE)
        }

        // Bing
        listOf("bing.com", "www.bing.com").forEach {
            put(it, IP_BING)
        }
    }

    /**
     * Domaines bloqués entièrement en mode parental
     * (pas de SafeSearch DNS disponible pour ces moteurs).
     */
    val BLOCKED_SEARCH_ENGINES = setOf(
        // Brave Search — pas de SafeSearch DNS
        "search.brave.com",
        // DuckDuckGo — pas de SafeSearch DNS
        "duckduckgo.com", "www.duckduckgo.com", "ddg.gg",
        // Startpage — relaye Google mais pas de SafeSearch DNS
        "startpage.com", "www.startpage.com",
        // Qwant
        "qwant.com", "www.qwant.com"
    )

    /**
     * Retourne l'IP SafeSearch si le domaine est un moteur de recherche
     * supportant le forced SafeSearch, null sinon.
     */
    fun getSafeIp(hostname: String): ByteArray? {
        return SAFESEARCH_MAP[hostname.lowercase()]
    }

    /**
     * Retourne true si le domaine est un moteur de recherche à bloquer
     * entièrement en mode parental (pas de SafeSearch DNS disponible).
     */
    fun isBlockedSearchEngine(hostname: String): Boolean {
        val domain = hostname.lowercase()
        return BLOCKED_SEARCH_ENGINES.any { domain == it || domain.endsWith(".$it") }
    }
}