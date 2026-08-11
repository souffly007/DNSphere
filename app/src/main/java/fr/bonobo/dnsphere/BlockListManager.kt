package fr.bonobo.dnsphere

import android.content.Context
import android.util.Log
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.UserRule
import kotlinx.coroutines.runBlocking

class BlockListManager(private val context: Context) {

    companion object {
        private const val TAG = "BlockListManager"

        // =====================================================================
        // Endpoints DoH des navigateurs qui bypass le DNS VPN
        // Bloquer ces domaines force Chrome/Edge/Opera à revenir au DNS système
        // Firefox est géré séparément (canary domain : use-application-dns.net)
        // =====================================================================
        private val DOH_BYPASS_DOMAINS = setOf(
            // Chrome / Chromium
            "dns.google",
            "dns.google.com",
            "8888.google",
            "chrome.cloudflare-dns.com",
            "dns64.dns.google",
            // Edge
            "doh.xfinity.com",
            // Opera / Brave
            "doh.opendns.com",
            // Générique Cloudflare DoH
            "cloudflare-dns.com",
            "1dot1dot1dot1.cloudflare-dns.com",
            // NextDNS
            "dns.nextdns.io",
            // AdGuard DoH
            "dns.adguard.com",
            "dns-unfiltered.adguard.com",
            // Quad9
            "dns.quad9.net",
            "dns11.quad9.net",
            // Mullvad DNS (couvre adblock./base./extended./family./all.dns.mullvad.net)
            "dns.mullvad.net",
            // DNS4EU (couvre noads./child./child-noads./protective.joindns4.eu)
            "joindns4.eu",
            // Canary domain Firefox — si bloqué, Firefox désactive son DoH intégré
            "use-application-dns.net"
        )
    }

    // =========================================================================
    // HashSets — lookup O(1) au lieu de O(n)
    // On stocke les domaines racines ; matchesDomainFast() remonte les parents
    // =========================================================================
    private val adDomains       = HashSet<String>()
    private val trackerDomains  = HashSet<String>()
    private val malwareDomains  = HashSet<String>()
    private val shoppingDomains = HashSet<String>()
    private val customDomains   = HashSet<String>()
    private val externalDomains = HashSet<String>(1 shl 20) // ~1M buckets pour 500K entrées
    private val whitelistedDomains = HashSet<String>()
    private val forceBlockDomains  = HashSet<String>()
    private val stunDomains        = HashSet<String>()

    private val prefs = context.getSharedPreferences("dnsphere_settings", Context.MODE_PRIVATE)

    val isWebRtcProtectionEnabled: Boolean
        get() = prefs.getBoolean("webrtc_leak_protection", false)

    fun setWebRtcProtection(enabled: Boolean) {
        prefs.edit().putBoolean("webrtc_leak_protection", enabled).apply()
        loadStunList()
    }

    private var userRules: List<UserRule> = emptyList()

    private val neverBlockDomains = setOf(
        // WHATSAPP
        "whatsapp.net", "whatsapp.com", "www.whatsapp.com", "wa.me",
        "cdn.whatsapp.net", "mmg.whatsapp.net", "media.whatsapp.net",
        "static.whatsapp.net", "web.whatsapp.com", "pps.whatsapp.net",
        "g.whatsapp.net", "v.whatsapp.net", "e.whatsapp.net",
        "scontent.whatsapp.net", "crashlogs.whatsapp.net", "dit.whatsapp.net",
        "graph.whatsapp.net",
        "media-ams2-1.cdn.whatsapp.net", "media-ams4-1.cdn.whatsapp.net",
        "media-cdg2-1.cdn.whatsapp.net", "media-cdg4-1.cdn.whatsapp.net",
        "media-cdg4-2.cdn.whatsapp.net", "media-cdt1-1.cdn.whatsapp.net",
        "media-cdt1-2.cdn.whatsapp.net", "media-fra3-1.cdn.whatsapp.net",
        "media-fra3-2.cdn.whatsapp.net", "media-fra5-1.cdn.whatsapp.net",
        "media-fra5-2.cdn.whatsapp.net", "media-frt3-1.cdn.whatsapp.net",
        "media-frt3-2.cdn.whatsapp.net", "media-lhr6-1.cdn.whatsapp.net",
        "media-lhr6-2.cdn.whatsapp.net", "media-lhr8-1.cdn.whatsapp.net",
        "media-lhr8-2.cdn.whatsapp.net", "media-mrs2-1.cdn.whatsapp.net",
        "media-mrs2-2.cdn.whatsapp.net", "media-mxp1-1.cdn.whatsapp.net",
        "media-mxp2-1.cdn.whatsapp.net", "media-mad1-1.cdn.whatsapp.net",
        "media-lis1-1.cdn.whatsapp.net", "media-bru2-1.cdn.whatsapp.net",
        "media-zrh1-1.cdn.whatsapp.net", "media-vie1-1.cdn.whatsapp.net",
        "media-prg1-1.cdn.whatsapp.net", "media-waw1-1.cdn.whatsapp.net",
        "turn.whatsapp.net", "stun.whatsapp.net", "mmg-fna.whatsapp.net",
        "fna.whatsapp.net", "media.fna.whatsapp.net",
        // FACEBOOK / META
        "facebook.com", "www.facebook.com", "m.facebook.com",
        "mobile.facebook.com", "touch.facebook.com", "fb.com", "fb.me",
        "fbcdn.net", "fbsbx.com", "facebook.net", "connect.facebook.net",
        "graph.facebook.com", "api.facebook.com", "b-api.facebook.com",
        "b-graph.facebook.com", "rupload.facebook.com", "upload.facebook.com",
        "streaming-graph.facebook.com", "edge-mqtt.facebook.com",
        "mqtt.facebook.com", "mqtt-mini.facebook.com", "mqtt.c10r.facebook.com",
        "edge-mqtt-mini.facebook.com", "edge-chat.facebook.com",
        "edge-chat.messenger.com", "edge-stun.facebook.com",
        "edge-turn.facebook.com", "stun.facebook.com", "turn.facebook.com",
        "stun.fbsbx.com", "turn.fbsbx.com", "fna.fbcdn.net",
        "scontent.fbcdn.net", "video.fbcdn.net", "external.fbcdn.net",
        "static.fbcdn.net", "scontent.xx.fbcdn.net", "video.xx.fbcdn.net",
        "external.xx.fbcdn.net",
        "scontent-cdg2-1.xx.fbcdn.net", "scontent-cdg4-1.xx.fbcdn.net",
        "scontent-cdg4-2.xx.fbcdn.net", "scontent-cdt1-1.xx.fbcdn.net",
        "scontent-fra3-1.xx.fbcdn.net", "scontent-fra3-2.xx.fbcdn.net",
        "scontent-fra5-1.xx.fbcdn.net", "scontent-fra5-2.xx.fbcdn.net",
        "scontent-mrs2-1.xx.fbcdn.net", "scontent-mrs2-2.xx.fbcdn.net",
        "scontent-frt3-1.xx.fbcdn.net", "scontent-frt3-2.xx.fbcdn.net",
        "scontent-lhr6-1.xx.fbcdn.net", "scontent-lhr8-1.xx.fbcdn.net",
        "scontent-ams2-1.xx.fbcdn.net", "scontent-ams4-1.xx.fbcdn.net",
        "scontent-bru2-1.xx.fbcdn.net", "scontent-mxp1-1.xx.fbcdn.net",
        "scontent-mxp2-1.xx.fbcdn.net", "scontent-mad1-1.xx.fbcdn.net",
        "scontent-lis1-1.xx.fbcdn.net", "scontent-zrh1-1.xx.fbcdn.net",
        "scontent-vie1-1.xx.fbcdn.net",
        "video-cdg2-1.xx.fbcdn.net", "video-cdg4-1.xx.fbcdn.net",
        "video-cdg4-2.xx.fbcdn.net", "video-cdt1-1.xx.fbcdn.net",
        "video-fra3-1.xx.fbcdn.net", "video-fra3-2.xx.fbcdn.net",
        "video-fra5-1.xx.fbcdn.net", "video-fra5-2.xx.fbcdn.net",
        "video-mrs2-1.xx.fbcdn.net", "video-mrs2-2.xx.fbcdn.net",
        "video-frt3-1.xx.fbcdn.net", "video-frt3-2.xx.fbcdn.net",
        "video-lhr6-1.xx.fbcdn.net", "video-lhr8-1.xx.fbcdn.net",
        "video-ams2-1.xx.fbcdn.net", "video-ams4-1.xx.fbcdn.net",
        "cdninstagram.com", "instagram.com", "www.instagram.com",
        "i.instagram.com", "scontent.cdninstagram.com",
        "scontent-cdg2-1.cdninstagram.com", "scontent-cdg4-1.cdninstagram.com",
        "scontent-fra3-1.cdninstagram.com", "scontent-fra5-1.cdninstagram.com",
        // TELEGRAM
        "telegram.org", "telegram.me", "t.me", "core.telegram.org",
        "api.telegram.org", "web.telegram.org", "desktop.telegram.org",
        "updates.telegram.org", "cdn.telegram.org", "cdn1.telegram.org",
        "cdn2.telegram.org", "cdn3.telegram.org", "cdn4.telegram.org",
        "cdn5.telegram.org", "telegram-cdn.org", "venus.web.telegram.org",
        "pluto.web.telegram.org", "flora.web.telegram.org",
        "vesta.web.telegram.org", "aurora.web.telegram.org",
        // SIGNAL
        "signal.org", "www.signal.org", "updates.signal.org",
        "textsecure-service.whispersystems.org", "storage.signal.org",
        "cdn.signal.org", "cdn2.signal.org", "contentproxy.signal.org",
        "api.directory.signal.org", "cdsi.signal.org", "chat.signal.org",
        "ud-chat.signal.org", "sfu.voip.signal.org", "turn1.voip.signal.org",
        "turn2.voip.signal.org", "turn3.voip.signal.org",
        // MESSENGER
        "messenger.com", "www.messenger.com", "m.me",
        "external.messenger.com", "scontent.messenger.com",
        "video.messenger.com", "rupload.messenger.com",
        "msngr.com", "www.msngr.com",
        // DISCORD
        "discord.com", "www.discord.com", "discordapp.com", "discord.gg",
        "discord.media", "discordapp.net", "cdn.discordapp.com",
        "media.discordapp.net", "images-ext-1.discordapp.net",
        "images-ext-2.discordapp.net", "gateway.discord.gg",
        "status.discord.com", "dl.discordapp.net", "updates.discord.com",
        "latency.discord.media", "router.discordapp.net",
        // VIBER
        "viber.com", "www.viber.com", "vb.me", "dl.viber.com",
        "dl-media.viber.com", "share.viber.com", "api.viber.com",
        // SKYPE
        "skype.com", "www.skype.com", "login.skype.com", "apps.skype.com",
        "skypeassets.com", "trouter.skype.com", "edge.skype.com", "api.skype.com",
        // ZOOM
        "zoom.us", "zoom.com", "www.zoom.us", "zoomcdn.com", "log.zoom.us",
        "cdn.zoom.us", "us02web.zoom.us", "us03web.zoom.us",
        "us04web.zoom.us", "us05web.zoom.us", "eu01web.zoom.us",
        // TEAMS
        "teams.microsoft.com", "teams.live.com",
        "statics.teams.cdn.office.net", "teams.cdn.office.net",
        // GOOGLE MEET
        "meet.google.com", "duo.google.com", "duo.googleapis.com",
        "instantmessaging-pa.googleapis.com",
        // FACETIME
        "facetime.apple.com", "stun.apple.com", "turn.apple.com",
        // GOOGLE ESSENTIELS
        "googleapis.com", "gstatic.com", "google.com", "google.fr",
        "google.de", "google.co.uk", "google.es", "google.it",
        "googleusercontent.com", "googlevideo.com", "youtube.com",
        "youtu.be", "ytimg.com", "ggpht.com", "play.google.com",
        "android.com", "gvt1.com", "gvt2.com", "gvt3.com", "1e100.net",
        "clients1.google.com", "clients2.google.com", "clients3.google.com",
        "clients4.google.com", "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com", "android.clients.google.com",
        "accounts.google.com", "www.gstatic.com", "fonts.googleapis.com",
        "fonts.gstatic.com", "maps.googleapis.com", "maps.google.com",
        "translate.googleapis.com", "translate.google.com",
        "firebaseinstallations.googleapis.com", "fcm.googleapis.com",
        "mtalk.google.com", "alt1-mtalk.google.com", "alt2-mtalk.google.com",
        "alt3-mtalk.google.com", "alt4-mtalk.google.com",
        "alt5-mtalk.google.com", "alt6-mtalk.google.com",
        "alt7-mtalk.google.com", "alt8-mtalk.google.com",
        // APPLE
        "apple.com", "www.apple.com", "icloud.com", "www.icloud.com",
        "apple-cloudkit.com", "mzstatic.com", "itunes.com",
        "itunes.apple.com", "apps.apple.com", "init.push.apple.com",
        "courier.push.apple.com", "mesu.apple.com", "captive.apple.com",
        // MICROSOFT
        "microsoft.com", "www.microsoft.com", "microsoftonline.com",
        "login.microsoftonline.com", "live.com", "login.live.com",
        "outlook.com", "outlook.live.com", "office.com", "office365.com",
        "windows.com", "windowsupdate.com", "xbox.com", "linkedin.com",
        "www.linkedin.com", "github.com", "www.github.com",
        "githubusercontent.com", "github.io", "azure.com", "bing.com",
        "msftconnecttest.com", "aka.ms", "onedrive.live.com", "sharepoint.com",
        // BANQUES FRANÇAISES
        "bnpparibas.com", "bnpparibas.fr", "mabanque.bnpparibas",
        "societegenerale.fr", "particuliers.societegenerale.fr",
        "credit-agricole.fr", "credit-agricole.com", "lcl.fr",
        "particuliers.lcl.fr", "labanquepostale.fr", "banquepostale.fr",
        "boursorama.com", "clients.boursorama.com", "boursobank.com",
        "fortuneo.fr", "mabanque.fortuneo.fr", "ing.fr", "secure.ing.fr",
        "revolut.com", "app.revolut.com", "n26.com", "app.n26.com",
        "hellobank.fr", "hsbc.fr", "cic.fr", "creditmutuel.fr",
        "caisse-epargne.fr", "banquepopulaire.fr", "bred.fr",
        "monabanq.com", "orangebank.fr", "nickel.eu", "qonto.com", "shine.fr",
        // PAIEMENT
        "paypal.com", "www.paypal.com", "paypalobjects.com", "stripe.com",
        "js.stripe.com", "api.stripe.com", "wise.com", "transferwise.com",
        "lydia-app.com",
        // E-COMMERCE
        "amazon.com", "amazon.fr", "amazon.de", "amazon.co.uk",
        "amazonaws.com", "images-amazon.com", "media-amazon.com",
        "ebay.com", "ebay.fr", "leboncoin.fr", "vinted.fr", "vinted.com",
        "fnac.com", "darty.com", "cdiscount.com", "boulanger.com",
        // STREAMING VIDÉO
        "netflix.com", "www.netflix.com", "nflxvideo.net", "nflximg.net",
        "nflxso.net", "disneyplus.com", "disney-plus.net", "dssott.com",
        "primevideo.com", "aiv-cdn.net", "canalplus.com", "mycanal.fr",
        "tf1.fr", "6play.fr", "france.tv", "arte.tv", "molotov.tv",
        "twitch.tv", "ttvnw.net", "jtvnw.net", "vimeo.com", "dailymotion.com",
        // STREAMING AUDIO
        "spotify.com", "scdn.co", "spotifycdn.com", "deezer.com",
        "dzcdn.net", "soundcloud.com", "sndcdn.com", "music.apple.com",
        "podcasts.google.com", "podcasts.apple.com",
        // RÉSEAUX SOCIAUX
        "twitter.com", "x.com", "twimg.com", "tiktok.com", "tiktokcdn.com",
        "snapchat.com", "snap.com", "sc-cdn.net", "reddit.com",
        "redditstatic.com", "redd.it", "pinterest.com", "pinimg.com",
        // EMAIL
        "gmail.com", "mail.google.com", "yahoo.com", "mail.yahoo.com",
        "protonmail.com", "proton.me", "tutanota.com", "laposte.net",
        // CLOUD
        "dropbox.com", "dropboxusercontent.com", "onedrive.com",
        "drive.google.com", "docs.google.com", "box.com", "wetransfer.com",
        "mega.nz", "pcloud.com",
        // SERVICES PUBLICS FR
        "gouv.fr", "service-public.fr", "impots.gouv.fr", "ameli.fr",
        "caf.fr", "laposte.fr", "sncf.com", "oui.sncf", "ratp.fr",
        "edf.fr", "engie.fr", "pole-emploi.fr", "francetravail.fr",
        "doctolib.fr",
        // OPÉRATEURS
        "free.fr", "orange.fr", "sfr.fr", "bouyguestelecom.fr",
        "sosh.fr", "red-by-sfr.fr",
        // SÉCURITÉ / DNS
        "cloudflare.com", "cloudflare-dns.com", "1.1.1.1",
        "one.one.one.one", "quad9.net", "opendns.com", "nextdns.io",
        "bitwarden.com", "1password.com", "lastpass.com",
        // CDN
        "akamaized.net", "akamai.net", "akamaihd.net", "cloudfront.net",
        "fastly.net", "jsdelivr.net", "unpkg.com", "cdnjs.cloudflare.com",
        "bootstrapcdn.com",
        // CAPTCHA
        "recaptcha.net", "www.recaptcha.net", "hcaptcha.com",
        "challenges.cloudflare.com",
        // JEUX
        "steampowered.com", "steamcommunity.com", "steamstatic.com",
        "epicgames.com", "ea.com", "origin.com", "ubisoft.com",
        "riotgames.com", "blizzard.com", "battle.net", "playstation.com",
        "nintendo.com", "xbox.com", "minecraft.net"
    )

    private val database = AppDatabase.getInstance(context)

    init {
        loadDefaultLists()
        loadCustomLists()
        loadExternalLists()
        loadWhitelist()
        loadForceBlockList()
        loadUserRules()
        loadStunList()
        Log.d(TAG, "BlockListManager initialisé: ${getStats()}")
    }

    // =========================================================================
    // CHARGEMENT
    // =========================================================================

    private fun loadDefaultLists() {
        loadAdDomains()
        loadTrackerDomains()
        loadMailTrackingDomains()
        loadMalwareDomains()
        loadShoppingDomains()
    }

    fun loadForceBlockList() {
        runBlocking {
            try {
                val items = database.whitelistDao().getAllForceBlocked()
                forceBlockDomains.clear()
                forceBlockDomains.addAll(items.map { it.domain })
            } catch (e: Exception) {
                Log.e(TAG, "Erreur chargement forceBlock: ${e.message}")
            }
        }
    }

    fun loadUserRules() {
        runBlocking {
            try {
                userRules = database.userRuleDao().getEnabledRules()
            } catch (e: Exception) {
                Log.e(TAG, "Erreur chargement user rules: ${e.message}")
            }
        }
    }

    fun loadExternalLists() {
        runBlocking {
            try {
                val domains = database.externalListDao().getAllEnabledDomains()
                externalDomains.clear()
                externalDomains.addAll(domains)
                Log.d(TAG, "Listes externes chargées: ${externalDomains.size} domaines")
            } catch (e: Exception) {
                Log.e(TAG, "Erreur chargement listes externes: ${e.message}")
            }
        }
    }

    fun loadCustomLists() {
        runBlocking {
            try {
                val domains = database.customListDao().getAllEnabledDomains()
                customDomains.clear()
                customDomains.addAll(domains)
            } catch (e: Exception) { }
        }
    }

    fun loadWhitelist() {
        runBlocking {
            try {
                val items = database.whitelistDao().getAllSync()
                whitelistedDomains.clear()
                whitelistedDomains.addAll(items.filter { !it.forceBlock }.map { it.domain })
            } catch (e: Exception) { }
        }
    }

    fun loadStunList() {
        stunDomains.clear()
        if (!isWebRtcProtectionEnabled) return
        stunDomains.addAll(listOf(
            "stun.l.google.com", "stun1.l.google.com", "stun2.l.google.com",
            "stun3.l.google.com", "stun4.l.google.com",
            "stun.cloudflare.com", "global.stun.twilio.com",
            "stun.xmpp.org", "meet-jit-si-turnrelay.jitsi.net",
            "stun.nextcloud.com", "stun.ekiga.net", "stun.ideasip.com",
            "stun.stunprotocol.org", "stun.voip.blackberry.com",
            "stun.freenode.net", "turn.anyfirewall.com",
            "turn.bistri.com", "numb.viagenie.ca"
        ))
    }

    // =========================================================================
    // LOOKUP O(1) — remonte les parents du hostname dans le HashSet
    //
    // Ancien code : set.any { hostname == it || hostname.endsWith(".$it") }
    //   → O(n) : itère tous les éléments du set
    //
    // Nouveau code : cherche hostname, puis "b.c" depuis "a.b.c", puis "c"
    //   → O(nombre de points) = O(2~4) quelle que soit la taille du set
    // =========================================================================
    private fun matchesDomain(hostname: String, set: Set<String>): Boolean {
        if (hostname in set) return true
        var dot = hostname.indexOf('.')
        while (dot != -1) {
            val parent = hostname.substring(dot + 1)
            if (parent in set) return true
            dot = hostname.indexOf('.', dot + 1)
        }
        return false
    }

    // =========================================================================
    // VÉRIFICATION
    // =========================================================================

    fun isForceBlocked(hostname: String): Boolean {
        val domain = hostname.lowercase()
        return matchesDomain(domain, forceBlockDomains)
    }

    fun isStunBlocked(hostname: String): Boolean {
        if (!isWebRtcProtectionEnabled) return false
        return matchesDomain(hostname.lowercase(), stunDomains)
    }

    /**
     * Retourne true si le hostname est un endpoint DoH de navigateur.
     * Bloquer ces domaines empêche Chrome/Edge/Brave de bypasser le DNS VPN.
     */
    fun isDohBypass(hostname: String): Boolean {
        return matchesDomain(hostname.lowercase(), DOH_BYPASS_DOMAINS)
    }

    fun isWhitelisted(hostname: String): Boolean {
        val domain = hostname.lowercase()
        if (isForceBlocked(domain)) return false
        if (matchesDomain(domain, neverBlockDomains)) return true
        val userResult = RulesEngine.evaluate(domain, userRules)
        if (userResult == RulesEngine.MatchResult.ALLOW) return true
        if (userResult == RulesEngine.MatchResult.BLOCK) return false
        return matchesDomain(domain, whitelistedDomains)
    }

    fun isAd(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return matchesDomain(domain, adDomains) || matchesDomain(domain, customDomains)
    }

    fun isTracker(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        return matchesDomain(hostname.lowercase(), trackerDomains)
    }

    fun isMalware(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        return matchesDomain(hostname.lowercase(), malwareDomains)
    }

    fun isShopping(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        return matchesDomain(hostname.lowercase(), shoppingDomains)
    }

    fun isExternalBlocked(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        return matchesDomain(hostname.lowercase(), externalDomains)
    }

    fun shouldBlock(hostname: String): Boolean {
        if (isForceBlocked(hostname)) return true
        val domain = hostname.lowercase()
        if (matchesDomain(domain, neverBlockDomains)) return false
        if (isStunBlocked(hostname)) return true
        val userResult = RulesEngine.evaluate(domain, userRules)
        if (userResult == RulesEngine.MatchResult.ALLOW) return false
        if (userResult == RulesEngine.MatchResult.BLOCK) return true
        if (isWhitelisted(hostname)) return false
        return isAd(hostname) || isTracker(hostname) ||
                isMalware(hostname) || isShopping(hostname) ||
                isExternalBlocked(hostname)
    }

    fun getBlockType(hostname: String): BlockType {
        if (isForceBlocked(hostname)) return BlockType.FORCE_BLOCKED
        val domain = hostname.lowercase()
        if (isStunBlocked(hostname)) return BlockType.WEBRTC_STUN
        val userResult = RulesEngine.evaluate(domain, userRules)
        if (userResult == RulesEngine.MatchResult.BLOCK) return BlockType.USER_BLOCKED
        if (userResult == RulesEngine.MatchResult.ALLOW) return BlockType.USER_ALLOWED
        if (isWhitelisted(hostname)) return BlockType.WHITELISTED
        return when {
            matchesDomain(domain, adDomains)       -> BlockType.AD
            matchesDomain(domain, trackerDomains)  -> BlockType.TRACKER
            matchesDomain(domain, malwareDomains)  -> BlockType.MALWARE
            matchesDomain(domain, shoppingDomains) -> BlockType.SHOPPING
            matchesDomain(domain, externalDomains) -> BlockType.EXTERNAL
            matchesDomain(domain, customDomains)   -> BlockType.CUSTOM
            else -> BlockType.NONE
        }
    }

    /**
     * Résultat consolidé d'une classification pour le chemin critique (une requête
     * DNS par appel). Les booléens de catégorie restent indépendants (pas de
     * "premier qui matche gagne") pour préserver le comportement historique :
     * un domaine présent dans plusieurs listes reste détectable même si le
     * toggle utilisateur d'une des catégories est désactivé.
     */
    data class FilterResult(
        val exempted: Boolean = false,    // whitelist/neverBlock/règle ALLOW — prioritaire sur tout le reste
        val forced: Boolean = false,      // Force Block — prioritaire, non désactivable
        val userBlocked: Boolean = false, // Règle utilisateur explicite (Rules Editor)
        val stun: Boolean = false,        // WebRTC STUN/TURN
        val isAd: Boolean = false,
        val isTracker: Boolean = false,
        val isMalware: Boolean = false,
        val isShopping: Boolean = false,
        val isExternal: Boolean = false
    )

    /**
     * Version consolidée de la classification pour LocalVpnService (un appel par
     * requête DNS). Le whitelist/force-block/règles utilisateur/STUN sont vérifiés
     * UNE SEULE fois, au lieu d'être re-testés à chaque catégorie — isAd()/isTracker()/...
     * appellent chacun isWhitelisted() individuellement, ce qui coûtait jusqu'à 6
     * vérifications redondantes par requête pour un domaine non filtré (le cas le
     * plus fréquent).
     *
     * Corrige aussi un bug : Force Block, les règles utilisateur "bloquer", et le
     * blocage STUN par domaine n'étaient jamais réellement appliqués dans le chemin
     * de requête réel (LocalVpnService), qui ne passait que par isWhitelisted() —
     * lequel ne fait qu'exempter un domaine, jamais le bloquer explicitement.
     */
    fun classifyForFiltering(hostname: String): FilterResult {
        val domain = hostname.lowercase()

        if (matchesDomain(domain, forceBlockDomains)) return FilterResult(forced = true)
        if (matchesDomain(domain, neverBlockDomains)) return FilterResult(exempted = true)

        val userResult = RulesEngine.evaluate(domain, userRules)
        if (userResult == RulesEngine.MatchResult.BLOCK) return FilterResult(userBlocked = true)
        if (userResult == RulesEngine.MatchResult.ALLOW) return FilterResult(exempted = true)

        if (matchesDomain(domain, whitelistedDomains)) return FilterResult(exempted = true)

        if (isWebRtcProtectionEnabled && matchesDomain(domain, stunDomains)) {
            return FilterResult(stun = true)
        }

        return FilterResult(
            isAd       = matchesDomain(domain, adDomains) || matchesDomain(domain, customDomains),
            isTracker  = matchesDomain(domain, trackerDomains),
            isMalware  = matchesDomain(domain, malwareDomains),
            isShopping = matchesDomain(domain, shoppingDomains),
            isExternal = matchesDomain(domain, externalDomains)
        )
    }

    // =========================================================================
    // REFRESH & STATS
    // =========================================================================

    fun refresh() {
        loadCustomLists()
        loadExternalLists()
        loadWhitelist()
        loadForceBlockList()
        loadUserRules()
        loadStunList()
        Log.d(TAG, "BlockListManager rafraîchi: ${getStats()}")
    }

    fun getStats(): Stats = Stats(
        builtInAds      = adDomains.size,
        builtInTrackers = trackerDomains.size,
        builtInMalware  = malwareDomains.size,
        builtInShopping = shoppingDomains.size,
        externalDomains = externalDomains.size,
        customDomains   = customDomains.size,
        whitelisted     = whitelistedDomains.size,
        forceBlocked    = forceBlockDomains.size,
        userRules       = userRules.size,
        neverBlocked    = neverBlockDomains.size,
        stunDomains     = stunDomains.size
    )

    data class Stats(
        val builtInAds: Int, val builtInTrackers: Int,
        val builtInMalware: Int, val builtInShopping: Int,
        val externalDomains: Int, val customDomains: Int,
        val whitelisted: Int, val forceBlocked: Int,
        val userRules: Int, val neverBlocked: Int,
        val stunDomains: Int = 0
    ) {
        val totalBuiltIn: Int get() = builtInAds + builtInTrackers + builtInMalware + builtInShopping
        val total: Int get() = totalBuiltIn + externalDomains + customDomains
        override fun toString() =
            "Stats(builtIn=$totalBuiltIn, external=$externalDomains, custom=$customDomains, " +
                    "whitelist=$whitelisted, forceBlocked=$forceBlocked, userRules=$userRules, " +
                    "neverBlocked=$neverBlocked, stunDomains=$stunDomains, TOTAL=$total)"
    }

    enum class BlockType {
        NONE, AD, TRACKER, MALWARE, SHOPPING, EXTERNAL, CUSTOM,
        WHITELISTED, FORCE_BLOCKED, USER_BLOCKED, USER_ALLOWED, WEBRTC_STUN
    }

    // =========================================================================
    // LISTES BUILT-IN
    // =========================================================================

    private fun loadAdDomains() {
        adDomains.addAll(listOf(
            "googleads.g.doubleclick.net", "pagead2.googlesyndication.com",
            "adservice.google.com", "adservice.google.fr", "adservice.google.de",
            "adservice.google.co.uk", "www.googleadservices.com",
            "googleadservices.com", "ad.doubleclick.net", "ads.google.com",
            "adclick.g.doubleclick.net", "tpc.googlesyndication.com",
            "partner.googleadservices.com", "pubads.g.doubleclick.net",
            "static.doubleclick.net", "g.doubleclick.net", "fwmrm.net",
            "s0.2mdn.net", "ads.youtube.com", "ad.youtube.com",
            "taboola.com", "cdn.taboola.com", "trc.taboola.com",
            "api.taboola.com", "taboolasyndication.com",
            "outbrain.com", "widgets.outbrain.com", "log.outbrain.com",
            "revcontent.com", "mgid.com", "teads.tv", "sharethrough.com",
            "nativo.com", "triplelift.com", "primis.tech", "mediavine.com",
            "ezoic.net", "amazon-adsystem.com", "adroll.com", "adnxs.com",
            "adsrvr.org", "pubmatic.com", "rubiconproject.com", "openx.net",
            "smartadserver.com", "doubleverify.com", "moatads.com",
            "admob.com", "unityads.unity3d.com", "applovin.com", "vungle.com",
            "chartboost.com", "ironsource.com", "adcolony.com", "tapjoy.com",
            "mintegral.com", "popads.net", "popcash.net",
            "propellerads.com", "exoclick.com"
        ))
    }

    private fun loadTrackerDomains() {
        trackerDomains.addAll(listOf(
            "google-analytics.com", "www.google-analytics.com",
            "ssl.google-analytics.com", "analytics.google.com",
            "www.googletagmanager.com", "googletagmanager.com",
            "mixpanel.com", "amplitude.com", "segment.io", "segment.com",
            "heapanalytics.com", "pendo.io", "hotjar.com", "fullstory.com",
            "mouseflow.com", "crazyegg.com", "clarity.ms", "smartlook.com",
            "logrocket.com", "appsflyer.com", "adjust.com", "branch.io",
            "kochava.com", "singular.net", "bluekai.com", "krxd.net",
            "demdex.net", "scorecardresearch.com", "quantserve.com",
            "fingerprintjs.com", "fpjs.io"
        ))
    }

    /**
     * Pixels de tracking email — inefficaces contre Gmail (proxy googleusercontent.com
     * côté serveur Google, hors de portée du VPN local), mais actifs pour les clients
     * mail qui chargent les images directement depuis le device : K-9 Mail, FairEmail,
     * Outlook, Thunderbird, Aqua Mail, etc.
     */
    private fun loadMailTrackingDomains() {
        trackerDomains.addAll(listOf(
            // Mailchimp
            "click.mailchimp.com", "list-manage.com",
            "open.mailchimp.com", "tracking.mailchimp.com",
            // Outils de prospection commerciale (pixel + click-tracking)
            "t.yesware.com", "app.yesware.com",
            "mailfoogae.appspot.com",           // Streak
            "t.sidekickopen.com",               // HubSpot Sidekick/Sales
            "clicks.hubspot.com",
            "mailtrack.io",
            "mailstat.us",                      // Boomerang
            "tracking.cirrusinsight.com",
            "bl-1.com",                         // Bananatag / Marigold Relay
            // Emailing / ESP
            "ct.sendgrid.net",
            "pixel.mailgun.org",
            "links.iterable.com",
            "track.customer.io",
            "open.convertkit-mail.com",
            "clicks.beehiiv.com"
        ))
    }

    private fun loadMalwareDomains() {
        malwareDomains.addAll(listOf(
            "coinhive.com", "coin-hive.com", "authedmine.com",
            "crypto-loot.com", "cryptoloot.pro", "jsecoin.com",
            "monerominer.rocks", "webmine.cz", "minero.cc"
        ))
    }

    private fun loadShoppingDomains() {
        shoppingDomains.addAll(listOf(
            "api-ads.temu.com", "ads.temu.com", "tracking.temu.com",
            "analytics.temu.com", "pixel.temu.com",
            "criteo.com", "criteo.net", "dis.criteo.com", "static.criteo.net",
            "api-ads.shein.com", "ads.shein.com", "tracking.shein.com",
            "tracking.aliexpress.com", "click.aliexpress.com", "aeustrack.com",
            "rtbhouse.com", "tracking.shopee.com", "tracking.wish.com"
        ))
    }
}