package fr.bonobo.dnsphere.lists

import fr.bonobo.dnsphere.data.ListCategory
import fr.bonobo.dnsphere.data.ListFormat

/**
 * Catalogue des listes hosts populaires prédéfinies.
 * Affichées dans le dialogue "Ajouter une liste connue" de ExternalListsActivity.
 */
object KnownHostsLists {

    data class KnownList(
        val name:        String,
        val url:         String,
        val description: String,
        val category:    ListCategory,
        val format:      ListFormat,
        val icon:        String,
        val approxCount: String   // indication du nombre de domaines
    )

    val ALL = listOf(

        // =====================================================================
        // PUBS & TRACKERS
        // =====================================================================
        KnownList(
            name        = "StevenBlack Unified",
            url         = "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts",
            description = "La référence. Pubs + trackers unifiés.",
            category    = ListCategory.ADS,
            format      = ListFormat.HOSTS,
            icon        = "🛡️",
            approxCount = "~170K domaines"
        ),
        KnownList(
            name        = "StevenBlack + Fakenews",
            url         = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/fakenews/hosts",
            description = "Pubs + trackers + sites de désinformation.",
            category    = ListCategory.ADS,
            format      = ListFormat.HOSTS,
            icon        = "🛡️",
            approxCount = "~175K domaines"
        ),
        KnownList(
            name        = "StevenBlack + Gambling",
            url         = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/gambling/hosts",
            description = "Pubs + trackers + sites de jeux d'argent.",
            category    = ListCategory.ADS,
            format      = ListFormat.HOSTS,
            icon        = "🎲",
            approxCount = "~185K domaines"
        ),
        KnownList(
            name        = "StevenBlack + Porn",
            url         = "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn/hosts",
            description = "Pubs + trackers + contenus adultes.",
            category    = ListCategory.ADS,
            format      = ListFormat.HOSTS,
            icon        = "🔞",
            approxCount = "~330K domaines"
        ),
        KnownList(
            name        = "AdGuard DNS filter",
            url         = "https://adguardteam.github.io/AdGuardSDNSFilter/Filters/filter.txt",
            description = "Filtre DNS officiel AdGuard.",
            category    = ListCategory.ADS,
            format      = ListFormat.ADBLOCK,
            icon        = "🛡️",
            approxCount = "~50K domaines"
        ),
        KnownList(
            name        = "uBlock Origin filters",
            url         = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters/filters.txt",
            description = "Filtres uBlock Origin — pubs et trackers.",
            category    = ListCategory.ADS,
            format      = ListFormat.ADBLOCK,
            icon        = "🧱",
            approxCount = "~20K règles"
        ),
        KnownList(
            name        = "EasyList",
            url         = "https://easylist.to/easylist/easylist.txt",
            description = "La liste AdBlock la plus populaire.",
            category    = ListCategory.ADS,
            format      = ListFormat.ADBLOCK,
            icon        = "📋",
            approxCount = "~90K règles"
        ),
        KnownList(
            name        = "EasyPrivacy",
            url         = "https://easylist.to/easylist/easyprivacy.txt",
            description = "Trackers et scripts de collecte de données.",
            category    = ListCategory.TRACKERS,
            format      = ListFormat.ADBLOCK,
            icon        = "🔍",
            approxCount = "~30K règles"
        ),

        // =====================================================================
        // TRACKERS
        // =====================================================================
        KnownList(
            name        = "HaGeZi Multi PRO",
            url         = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt",
            description = "Liste HaGeZi très complète — pubs, trackers, malwares.",
            category    = ListCategory.TRACKERS,
            format      = ListFormat.ADBLOCK,
            icon        = "🔒",
            approxCount = "~800K domaines"
        ),
        KnownList(
            name        = "HaGeZi Multi NORMAL",
            url         = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt",
            description = "Version normale HaGeZi — bon équilibre.",
            category    = ListCategory.TRACKERS,
            format      = ListFormat.ADBLOCK,
            icon        = "🔒",
            approxCount = "~500K domaines"
        ),
        KnownList(
            name        = "OISD Full",
            url         = "https://dbl.oisd.nl/full/",
            description = "Liste OISD complète — pubs et trackers.",
            category    = ListCategory.TRACKERS,
            format      = ListFormat.ADBLOCK,
            icon        = "🔍",
            approxCount = "~1.2M domaines"
        ),
        KnownList(
            name        = "OISD Basic",
            url         = "https://dbl.oisd.nl/basic/",
            description = "Version allégée OISD — moins de faux positifs.",
            category    = ListCategory.TRACKERS,
            format      = ListFormat.ADBLOCK,
            icon        = "🔍",
            approxCount = "~150K domaines"
        ),

        // =====================================================================
        // MALWARE / SÉCURITÉ
        // =====================================================================
        KnownList(
            name        = "Malware Domain List",
            url         = "https://www.malwaredomainlist.com/hostslist/hosts.txt",
            description = "Domaines de distribution de malwares.",
            category    = ListCategory.MALWARE,
            format      = ListFormat.HOSTS,
            icon        = "🦠",
            approxCount = "~1K domaines"
        ),
        KnownList(
            name        = "URLhaus Malware",
            url         = "https://urlhaus.abuse.ch/downloads/hostfile/",
            description = "Liste abuse.ch — distribution de malwares actifs.",
            category    = ListCategory.MALWARE,
            format      = ListFormat.HOSTS,
            icon        = "🦠",
            approxCount = "~4K domaines"
        ),
        KnownList(
            name        = "Phishing Army",
            url         = "https://phishing.army/download/phishing_army_blocklist.txt",
            description = "Sites de phishing actifs.",
            category    = ListCategory.MALWARE,
            format      = ListFormat.DOMAINS,
            icon        = "🎣",
            approxCount = "~20K domaines"
        ),
        KnownList(
            name        = "HaGeZi Threat Intelligence",
            url         = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.txt",
            description = "Ransomware, C2, cryptomining, phishing.",
            category    = ListCategory.MALWARE,
            format      = ListFormat.ADBLOCK,
            icon        = "⚠️",
            approxCount = "~900K domaines"
        ),

        // =====================================================================
        // VIE PRIVÉE
        // =====================================================================
        KnownList(
            name        = "NoTrack Blocklist",
            url         = "https://gitlab.com/quidsup/notrack-blocklists/raw/master/notrack-blocklist.txt",
            description = "Trackers, analytics, services de surveillance.",
            category    = ListCategory.PRIVACY,
            format      = ListFormat.DOMAINS,
            icon        = "👁️",
            approxCount = "~10K domaines"
        ),
        KnownList(
            name        = "WindowsSpyBlocker",
            url         = "https://raw.githubusercontent.com/crazy-max/WindowsSpyBlocker/master/data/hosts/spy.txt",
            description = "Télémétrie et espionnage Windows/Microsoft.",
            category    = ListCategory.PRIVACY,
            format      = ListFormat.HOSTS,
            icon        = "🪟",
            approxCount = "~200 domaines"
        ),

        // =====================================================================
        // RÉSEAUX SOCIAUX
        // =====================================================================
        KnownList(
            name        = "StevenBlack Social",
            url         = "https://raw.githubusercontent.com/StevenBlack/hosts/master/extensions/social/hosts",
            description = "Boutons sociaux et trackers Facebook, Twitter, etc.",
            category    = ListCategory.SOCIAL,
            format      = ListFormat.HOSTS,
            icon        = "📱",
            approxCount = "~4K domaines"
        ),

        // =====================================================================
        // FRANCE
        // =====================================================================
        KnownList(
            name        = "Liste FR Publicités",
            url         = "https://raw.githubusercontent.com/nichobi/french-adblock-filter/master/french_adblock.txt",
            description = "Publicités spécifiques aux sites français.",
            category    = ListCategory.ADS,
            format      = ListFormat.ADBLOCK,
            icon        = "🇫🇷",
            approxCount = "~500 règles"
        )
    )

    // Grouper par catégorie pour l'affichage
    val BY_CATEGORY: Map<ListCategory, List<KnownList>> = ALL.groupBy { it.category }
}