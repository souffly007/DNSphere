package fr.bonobo.dnsphere

import android.content.Context
import android.util.Log
import fr.bonobo.dnsphere.data.AppDatabase
import kotlinx.coroutines.runBlocking

class BlockListManager(private val context: Context) {

    companion object {
        private const val TAG = "BlockListManager"
    }

    private val adDomains = mutableSetOf<String>()
    private val trackerDomains = mutableSetOf<String>()
    private val malwareDomains = mutableSetOf<String>()
    private val shoppingDomains = mutableSetOf<String>()
    private val customDomains = mutableSetOf<String>()
    private val externalDomains = mutableSetOf<String>()
    private val whitelistedDomains = mutableSetOf<String>()

    // =========================================================================
    // 🛡️ DOMAINES CRITIQUES À NE JAMAIS BLOQUER
    // =========================================================================
    private val neverBlockDomains = setOf(
        // =====================================================
        // WHATSAPP (COMPLET - Messages, Médias, Appels Vidéo/Audio)
        // =====================================================
        "whatsapp.net",
        "whatsapp.com",
        "www.whatsapp.com",
        "wa.me",
        "cdn.whatsapp.net",
        "mmg.whatsapp.net",
        "media.whatsapp.net",
        "static.whatsapp.net",
        "web.whatsapp.com",
        "pps.whatsapp.net",
        "g.whatsapp.net",
        "v.whatsapp.net",
        "e.whatsapp.net",
        "scontent.whatsapp.net",
        "crashlogs.whatsapp.net",
        "dit.whatsapp.net",
        "graph.whatsapp.net",

        // WhatsApp CDN régionaux (médias, photos, vidéos)
        "media-ams2-1.cdn.whatsapp.net",
        "media-ams4-1.cdn.whatsapp.net",
        "media-cdg2-1.cdn.whatsapp.net",
        "media-cdg4-1.cdn.whatsapp.net",
        "media-cdg4-2.cdn.whatsapp.net",
        "media-cdt1-1.cdn.whatsapp.net",
        "media-cdt1-2.cdn.whatsapp.net",
        "media-fra3-1.cdn.whatsapp.net",
        "media-fra3-2.cdn.whatsapp.net",
        "media-fra5-1.cdn.whatsapp.net",
        "media-fra5-2.cdn.whatsapp.net",
        "media-frt3-1.cdn.whatsapp.net",
        "media-frt3-2.cdn.whatsapp.net",
        "media-lhr6-1.cdn.whatsapp.net",
        "media-lhr6-2.cdn.whatsapp.net",
        "media-lhr8-1.cdn.whatsapp.net",
        "media-lhr8-2.cdn.whatsapp.net",
        "media-mrs2-1.cdn.whatsapp.net",
        "media-mrs2-2.cdn.whatsapp.net",
        "media-mxp1-1.cdn.whatsapp.net",
        "media-mxp2-1.cdn.whatsapp.net",
        "media-mad1-1.cdn.whatsapp.net",
        "media-lis1-1.cdn.whatsapp.net",
        "media-bru2-1.cdn.whatsapp.net",
        "media-zrh1-1.cdn.whatsapp.net",
        "media-vie1-1.cdn.whatsapp.net",
        "media-prg1-1.cdn.whatsapp.net",
        "media-waw1-1.cdn.whatsapp.net",

        // WhatsApp Appels (TURN/STUN servers) - CRITIQUE pour vidéo
        "turn.whatsapp.net",
        "stun.whatsapp.net",
        "mmg-fna.whatsapp.net",
        "fna.whatsapp.net",
        "media.fna.whatsapp.net",

        // =====================================================
        // FACEBOOK / META (CRITIQUE pour WhatsApp)
        // =====================================================
        "facebook.com",
        "www.facebook.com",
        "m.facebook.com",
        "mobile.facebook.com",
        "touch.facebook.com",
        "fb.com",
        "fb.me",
        "fbcdn.net",
        "fbsbx.com",
        "facebook.net",
        "connect.facebook.net",
        "graph.facebook.com",
        "api.facebook.com",
        "b-api.facebook.com",
        "b-graph.facebook.com",
        "rupload.facebook.com",
        "upload.facebook.com",
        "streaming-graph.facebook.com",

        // Facebook MQTT (messages temps réel)
        "edge-mqtt.facebook.com",
        "mqtt.facebook.com",
        "mqtt-mini.facebook.com",
        "mqtt.c10r.facebook.com",
        "edge-mqtt-mini.facebook.com",

        // Facebook Edge/Chat (appels)
        "edge-chat.facebook.com",
        "edge-chat.messenger.com",

        // Facebook STUN/TURN (CRITIQUE pour appels vidéo)
        "edge-stun.facebook.com",
        "edge-turn.facebook.com",
        "stun.facebook.com",
        "turn.facebook.com",
        "stun.fbsbx.com",
        "turn.fbsbx.com",

        // Facebook CDN (médias)
        "fna.fbcdn.net",
        "scontent.fbcdn.net",
        "video.fbcdn.net",
        "external.fbcdn.net",
        "static.fbcdn.net",
        "scontent.xx.fbcdn.net",
        "video.xx.fbcdn.net",
        "external.xx.fbcdn.net",

        // Facebook CDN régionaux France/Europe (CRITIQUE)
        "scontent-cdg2-1.xx.fbcdn.net",
        "scontent-cdg4-1.xx.fbcdn.net",
        "scontent-cdg4-2.xx.fbcdn.net",
        "scontent-cdt1-1.xx.fbcdn.net",
        "scontent-fra3-1.xx.fbcdn.net",
        "scontent-fra3-2.xx.fbcdn.net",
        "scontent-fra5-1.xx.fbcdn.net",
        "scontent-fra5-2.xx.fbcdn.net",
        "scontent-mrs2-1.xx.fbcdn.net",
        "scontent-mrs2-2.xx.fbcdn.net",
        "scontent-frt3-1.xx.fbcdn.net",
        "scontent-frt3-2.xx.fbcdn.net",
        "scontent-lhr6-1.xx.fbcdn.net",
        "scontent-lhr8-1.xx.fbcdn.net",
        "scontent-ams2-1.xx.fbcdn.net",
        "scontent-ams4-1.xx.fbcdn.net",
        "scontent-bru2-1.xx.fbcdn.net",
        "scontent-mxp1-1.xx.fbcdn.net",
        "scontent-mxp2-1.xx.fbcdn.net",
        "scontent-mad1-1.xx.fbcdn.net",
        "scontent-lis1-1.xx.fbcdn.net",
        "scontent-zrh1-1.xx.fbcdn.net",
        "scontent-vie1-1.xx.fbcdn.net",

        // Facebook Video CDN régionaux (CRITIQUE pour appels vidéo)
        "video-cdg2-1.xx.fbcdn.net",
        "video-cdg4-1.xx.fbcdn.net",
        "video-cdg4-2.xx.fbcdn.net",
        "video-cdt1-1.xx.fbcdn.net",
        "video-fra3-1.xx.fbcdn.net",
        "video-fra3-2.xx.fbcdn.net",
        "video-fra5-1.xx.fbcdn.net",
        "video-fra5-2.xx.fbcdn.net",
        "video-mrs2-1.xx.fbcdn.net",
        "video-mrs2-2.xx.fbcdn.net",
        "video-frt3-1.xx.fbcdn.net",
        "video-frt3-2.xx.fbcdn.net",
        "video-lhr6-1.xx.fbcdn.net",
        "video-lhr8-1.xx.fbcdn.net",
        "video-ams2-1.xx.fbcdn.net",
        "video-ams4-1.xx.fbcdn.net",

        // Instagram CDN (même infrastructure que WhatsApp)
        "cdninstagram.com",
        "instagram.com",
        "www.instagram.com",
        "i.instagram.com",
        "scontent.cdninstagram.com",
        "scontent-cdg2-1.cdninstagram.com",
        "scontent-cdg4-1.cdninstagram.com",
        "scontent-fra3-1.cdninstagram.com",
        "scontent-fra5-1.cdninstagram.com",

        // =====================================================
        // TELEGRAM
        // =====================================================
        "telegram.org",
        "telegram.me",
        "t.me",
        "core.telegram.org",
        "api.telegram.org",
        "web.telegram.org",
        "desktop.telegram.org",
        "updates.telegram.org",
        "cdn.telegram.org",
        "cdn1.telegram.org",
        "cdn2.telegram.org",
        "cdn3.telegram.org",
        "cdn4.telegram.org",
        "cdn5.telegram.org",
        "telegram-cdn.org",
        "venus.web.telegram.org",
        "pluto.web.telegram.org",
        "flora.web.telegram.org",
        "vesta.web.telegram.org",
        "aurora.web.telegram.org",

        // =====================================================
        // SIGNAL
        // =====================================================
        "signal.org",
        "www.signal.org",
        "updates.signal.org",
        "textsecure-service.whispersystems.org",
        "storage.signal.org",
        "cdn.signal.org",
        "cdn2.signal.org",
        "contentproxy.signal.org",
        "api.directory.signal.org",
        "cdsi.signal.org",
        "chat.signal.org",
        "ud-chat.signal.org",
        "sfu.voip.signal.org",
        "turn1.voip.signal.org",
        "turn2.voip.signal.org",
        "turn3.voip.signal.org",

        // =====================================================
        // MESSENGER
        // =====================================================
        "messenger.com",
        "www.messenger.com",
        "m.me",
        "edge-chat.messenger.com",
        "external.messenger.com",
        "scontent.messenger.com",
        "video.messenger.com",
        "rupload.messenger.com",
        "msngr.com",
        "www.msngr.com",

        // =====================================================
        // DISCORD
        // =====================================================
        "discord.com",
        "www.discord.com",
        "discordapp.com",
        "discord.gg",
        "discord.media",
        "discordapp.net",
        "cdn.discordapp.com",
        "media.discordapp.net",
        "images-ext-1.discordapp.net",
        "images-ext-2.discordapp.net",
        "gateway.discord.gg",
        "status.discord.com",
        "dl.discordapp.net",
        "updates.discord.com",
        "latency.discord.media",
        "router.discordapp.net",

        // =====================================================
        // VIBER
        // =====================================================
        "viber.com",
        "www.viber.com",
        "vb.me",
        "dl.viber.com",
        "dl-media.viber.com",
        "share.viber.com",
        "api.viber.com",

        // =====================================================
        // SKYPE
        // =====================================================
        "skype.com",
        "www.skype.com",
        "login.skype.com",
        "apps.skype.com",
        "skypeassets.com",
        "trouter.skype.com",
        "edge.skype.com",
        "api.skype.com",

        // =====================================================
        // ZOOM
        // =====================================================
        "zoom.us",
        "zoom.com",
        "www.zoom.us",
        "zoomcdn.com",
        "log.zoom.us",
        "cdn.zoom.us",
        "us02web.zoom.us",
        "us03web.zoom.us",
        "us04web.zoom.us",
        "us05web.zoom.us",
        "eu01web.zoom.us",

        // =====================================================
        // MICROSOFT TEAMS
        // =====================================================
        "teams.microsoft.com",
        "teams.live.com",
        "statics.teams.cdn.office.net",
        "teams.cdn.office.net",

        // =====================================================
        // GOOGLE DUO / MEET
        // =====================================================
        "meet.google.com",
        "duo.google.com",
        "duo.googleapis.com",
        "instantmessaging-pa.googleapis.com",

        // =====================================================
        // FACETIME / IMESSAGE (Apple)
        // =====================================================
        "facetime.apple.com",
        "stun.apple.com",
        "turn.apple.com",

        // =====================================================
        // SERVICES GOOGLE ESSENTIELS
        // =====================================================
        "googleapis.com",
        "gstatic.com",
        "google.com",
        "google.fr",
        "google.de",
        "google.co.uk",
        "google.es",
        "google.it",
        "googleusercontent.com",
        "googlevideo.com",
        "youtube.com",
        "youtu.be",
        "ytimg.com",
        "ggpht.com",
        "play.google.com",
        "android.com",
        "gvt1.com",
        "gvt2.com",
        "gvt3.com",
        "1e100.net",
        "clients1.google.com",
        "clients2.google.com",
        "clients3.google.com",
        "clients4.google.com",
        "connectivitycheck.gstatic.com",
        "connectivitycheck.android.com",
        "android.clients.google.com",
        "accounts.google.com",
        "www.gstatic.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "maps.googleapis.com",
        "maps.google.com",
        "translate.googleapis.com",
        "translate.google.com",
        "firebaseinstallations.googleapis.com",
        "fcm.googleapis.com",
        "mtalk.google.com",
        "alt1-mtalk.google.com",
        "alt2-mtalk.google.com",
        "alt3-mtalk.google.com",
        "alt4-mtalk.google.com",
        "alt5-mtalk.google.com",
        "alt6-mtalk.google.com",
        "alt7-mtalk.google.com",
        "alt8-mtalk.google.com",

        // =====================================================
        // APPLE
        // =====================================================
        "apple.com",
        "www.apple.com",
        "icloud.com",
        "www.icloud.com",
        "apple-cloudkit.com",
        "mzstatic.com",
        "itunes.com",
        "itunes.apple.com",
        "apps.apple.com",
        "init.push.apple.com",
        "courier.push.apple.com",
        "mesu.apple.com",
        "captive.apple.com",

        // =====================================================
        // MICROSOFT
        // =====================================================
        "microsoft.com",
        "www.microsoft.com",
        "microsoftonline.com",
        "login.microsoftonline.com",
        "live.com",
        "login.live.com",
        "outlook.com",
        "outlook.live.com",
        "office.com",
        "office365.com",
        "windows.com",
        "windowsupdate.com",
        "xbox.com",
        "linkedin.com",
        "www.linkedin.com",
        "github.com",
        "www.github.com",
        "githubusercontent.com",
        "github.io",
        "azure.com",
        "bing.com",
        "msftconnecttest.com",
        "aka.ms",
        "onedrive.live.com",
        "sharepoint.com",

        // =====================================================
        // BANQUES FRANÇAISES
        // =====================================================
        "bnpparibas.com",
        "bnpparibas.fr",
        "mabanque.bnpparibas",
        "societegenerale.fr",
        "particuliers.societegenerale.fr",
        "credit-agricole.fr",
        "credit-agricole.com",
        "lcl.fr",
        "particuliers.lcl.fr",
        "labanquepostale.fr",
        "banquepostale.fr",
        "boursorama.com",
        "clients.boursorama.com",
        "boursobank.com",
        "fortuneo.fr",
        "mabanque.fortuneo.fr",
        "ing.fr",
        "secure.ing.fr",
        "revolut.com",
        "app.revolut.com",
        "n26.com",
        "app.n26.com",
        "hellobank.fr",
        "hsbc.fr",
        "cic.fr",
        "creditmutuel.fr",
        "caisse-epargne.fr",
        "banquepopulaire.fr",
        "bred.fr",
        "monabanq.com",
        "orangebank.fr",
        "nickel.eu",
        "qonto.com",
        "shine.fr",

        // =====================================================
        // PAIEMENT
        // =====================================================
        "paypal.com",
        "www.paypal.com",
        "paypalobjects.com",
        "stripe.com",
        "js.stripe.com",
        "api.stripe.com",
        "wise.com",
        "transferwise.com",
        "lydia-app.com",

        // =====================================================
        // E-COMMERCE
        // =====================================================
        "amazon.com",
        "amazon.fr",
        "amazon.de",
        "amazon.co.uk",
        "amazonaws.com",
        "images-amazon.com",
        "media-amazon.com",
        "ebay.com",
        "ebay.fr",
        "leboncoin.fr",
        "vinted.fr",
        "vinted.com",
        "fnac.com",
        "darty.com",
        "cdiscount.com",
        "boulanger.com",

        // =====================================================
        // STREAMING VIDÉO
        // =====================================================
        "netflix.com",
        "www.netflix.com",
        "nflxvideo.net",
        "nflximg.net",
        "nflxso.net",
        "disneyplus.com",
        "disney-plus.net",
        "dssott.com",
        "primevideo.com",
        "aiv-cdn.net",
        "canalplus.com",
        "mycanal.fr",
        "tf1.fr",
        "6play.fr",
        "france.tv",
        "arte.tv",
        "molotov.tv",
        "twitch.tv",
        "ttvnw.net",
        "jtvnw.net",
        "vimeo.com",
        "dailymotion.com",

        // =====================================================
        // STREAMING AUDIO
        // =====================================================
        "spotify.com",
        "scdn.co",
        "spotifycdn.com",
        "deezer.com",
        "dzcdn.net",
        "soundcloud.com",
        "sndcdn.com",
        "music.apple.com",
        "podcasts.google.com",
        "podcasts.apple.com",

        // =====================================================
        // RÉSEAUX SOCIAUX
        // =====================================================
        "twitter.com",
        "x.com",
        "twimg.com",
        "tiktok.com",
        "tiktokcdn.com",
        "snapchat.com",
        "snap.com",
        "sc-cdn.net",
        "reddit.com",
        "redditstatic.com",
        "redd.it",
        "pinterest.com",
        "pinimg.com",

        // =====================================================
        // EMAIL
        // =====================================================
        "gmail.com",
        "mail.google.com",
        "yahoo.com",
        "mail.yahoo.com",
        "protonmail.com",
        "proton.me",
        "tutanota.com",
        "outlook.com",
        "laposte.net",

        // =====================================================
        // CLOUD / STOCKAGE
        // =====================================================
        "dropbox.com",
        "dropboxusercontent.com",
        "onedrive.com",
        "drive.google.com",
        "docs.google.com",
        "box.com",
        "wetransfer.com",
        "mega.nz",
        "pcloud.com",

        // =====================================================
        // SERVICES PUBLICS FRANÇAIS
        // =====================================================
        "gouv.fr",
        "service-public.fr",
        "impots.gouv.fr",
        "ameli.fr",
        "caf.fr",
        "laposte.fr",
        "sncf.com",
        "oui.sncf",
        "ratp.fr",
        "edf.fr",
        "engie.fr",
        "pole-emploi.fr",
        "francetravail.fr",
        "doctolib.fr",

        // =====================================================
        // OPÉRATEURS TÉLÉCOM
        // =====================================================
        "free.fr",
        "orange.fr",
        "sfr.fr",
        "bouyguestelecom.fr",
        "sosh.fr",
        "red-by-sfr.fr",

        // =====================================================
        // SÉCURITÉ / VPN / DNS
        // =====================================================
        "cloudflare.com",
        "cloudflare-dns.com",
        "1.1.1.1",
        "one.one.one.one",
        "quad9.net",
        "opendns.com",
        "nextdns.io",
        "bitwarden.com",
        "1password.com",
        "lastpass.com",

        // =====================================================
        // CDN ESSENTIELS
        // =====================================================
        "akamaized.net",
        "akamai.net",
        "akamaihd.net",
        "cloudfront.net",
        "fastly.net",
        "jsdelivr.net",
        "unpkg.com",
        "cdnjs.cloudflare.com",
        "bootstrapcdn.com",

        // =====================================================
        // CAPTCHA
        // =====================================================
        "recaptcha.net",
        "www.recaptcha.net",
        "hcaptcha.com",
        "challenges.cloudflare.com",

        // =====================================================
        // JEUX VIDÉO
        // =====================================================
        "steampowered.com",
        "steamcommunity.com",
        "steamstatic.com",
        "epicgames.com",
        "ea.com",
        "origin.com",
        "ubisoft.com",
        "riotgames.com",
        "blizzard.com",
        "battle.net",
        "playstation.com",
        "nintendo.com",
        "xbox.com",
        "minecraft.net"
    )

    private val database = AppDatabase.getInstance(context)

    init {
        loadDefaultLists()
        loadCustomLists()
        loadExternalLists()
        loadWhitelist()

        Log.d(TAG, "BlockListManager initialisé: ${getStats()}")
    }

    private fun loadDefaultLists() {
        loadAdDomains()
        loadTrackerDomains()
        loadMalwareDomains()
        loadShoppingDomains()
    }

    private fun loadAdDomains() {
        adDomains.addAll(listOf(
            // GOOGLE ADS
            "googleads.g.doubleclick.net",
            "pagead2.googlesyndication.com",
            "adservice.google.com",
            "adservice.google.fr",
            "adservice.google.de",
            "adservice.google.co.uk",
            "www.googleadservices.com",
            "googleadservices.com",
            "ad.doubleclick.net",
            "ads.google.com",
            "adclick.g.doubleclick.net",
            "tpc.googlesyndication.com",
            "partner.googleadservices.com",
            "pubads.g.doubleclick.net",
            "static.doubleclick.net",
            "g.doubleclick.net",
            "fwmrm.net",
            "s0.2mdn.net",

            // YOUTUBE ADS
            "ads.youtube.com",
            "ad.youtube.com",

            // TABOOLA
            "taboola.com",
            "cdn.taboola.com",
            "trc.taboola.com",
            "api.taboola.com",
            "taboolasyndication.com",

            // OUTBRAIN
            "outbrain.com",
            "widgets.outbrain.com",
            "log.outbrain.com",

            // AUTRES
            "revcontent.com",
            "mgid.com",
            "teads.tv",
            "sharethrough.com",
            "nativo.com",
            "triplelift.com",
            "primis.tech",
            "mediavine.com",
            "ezoic.net",
            "amazon-adsystem.com",
            "adroll.com",
            "adnxs.com",
            "adsrvr.org",
            "pubmatic.com",
            "rubiconproject.com",
            "openx.net",
            "smartadserver.com",
            "doubleverify.com",
            "moatads.com",

            // MOBILE ADS
            "admob.com",
            "unityads.unity3d.com",
            "applovin.com",
            "vungle.com",
            "chartboost.com",
            "ironsource.com",
            "adcolony.com",
            "tapjoy.com",
            "mintegral.com",

            // POPUPS
            "popads.net",
            "popcash.net",
            "propellerads.com",
            "exoclick.com"
        ))
    }

    private fun loadTrackerDomains() {
        trackerDomains.addAll(listOf(
            // GOOGLE ANALYTICS
            "google-analytics.com",
            "www.google-analytics.com",
            "ssl.google-analytics.com",
            "analytics.google.com",
            "www.googletagmanager.com",
            "googletagmanager.com",

            // AUTRES ANALYTICS
            "mixpanel.com",
            "amplitude.com",
            "segment.io",
            "segment.com",
            "heapanalytics.com",
            "pendo.io",

            // HEATMAPS
            "hotjar.com",
            "fullstory.com",
            "mouseflow.com",
            "crazyegg.com",
            "clarity.ms",
            "smartlook.com",
            "logrocket.com",

            // MOBILE ATTRIBUTION
            "appsflyer.com",
            "adjust.com",
            "branch.io",
            "kochava.com",
            "singular.net",

            // DMP
            "bluekai.com",
            "krxd.net",
            "demdex.net",
            "scorecardresearch.com",
            "quantserve.com",

            // FINGERPRINTING
            "fingerprintjs.com",
            "fpjs.io"
        ))
    }

    private fun loadMalwareDomains() {
        malwareDomains.addAll(listOf(
            "coinhive.com",
            "coin-hive.com",
            "authedmine.com",
            "crypto-loot.com",
            "cryptoloot.pro",
            "jsecoin.com",
            "monerominer.rocks",
            "webmine.cz",
            "minero.cc"
        ))
    }

    private fun loadShoppingDomains() {
        shoppingDomains.addAll(listOf(
            // TEMU
            "api-ads.temu.com",
            "ads.temu.com",
            "tracking.temu.com",
            "analytics.temu.com",
            "pixel.temu.com",

            // CRITEO
            "criteo.com",
            "criteo.net",
            "dis.criteo.com",
            "static.criteo.net",

            // SHEIN
            "api-ads.shein.com",
            "ads.shein.com",
            "tracking.shein.com",

            // ALIEXPRESS TRACKING
            "tracking.aliexpress.com",
            "click.aliexpress.com",
            "aeustrack.com",

            // AUTRES
            "rtbhouse.com",
            "tracking.shopee.com",
            "tracking.wish.com"
        ))
    }

    // =========================================================================
    // CHARGEMENT DES LISTES
    // =========================================================================

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
            } catch (e: Exception) {
                // Ignorer
            }
        }
    }

    fun loadWhitelist() {
        runBlocking {
            try {
                val items = database.whitelistDao().getAllSync()
                whitelistedDomains.clear()
                whitelistedDomains.addAll(items.map { it.domain })
            } catch (e: Exception) {
                // Ignorer
            }
        }
    }

    // =========================================================================
    // VÉRIFICATION DES DOMAINES
    // =========================================================================

    fun isWhitelisted(hostname: String): Boolean {
        val domain = hostname.lowercase()

        // Vérifier d'abord les domaines critiques (jamais bloqués)
        if (neverBlockDomains.any { domain == it || domain.endsWith(".$it") }) {
            return true
        }

        // Vérifier la whitelist utilisateur
        return whitelistedDomains.any {
            domain == it || domain.endsWith(".$it")
        }
    }

    fun isAd(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return adDomains.any { domain == it || domain.endsWith(".$it") } ||
                customDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun isTracker(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return trackerDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun isMalware(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return malwareDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun isShopping(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return shoppingDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun isExternalBlocked(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false
        val domain = hostname.lowercase()
        return externalDomains.any { domain == it || domain.endsWith(".$it") }
    }

    fun shouldBlock(hostname: String): Boolean {
        if (isWhitelisted(hostname)) return false

        return isAd(hostname) ||
                isTracker(hostname) ||
                isMalware(hostname) ||
                isShopping(hostname) ||
                isExternalBlocked(hostname)
    }

    fun getBlockType(hostname: String): BlockType {
        if (isWhitelisted(hostname)) return BlockType.WHITELISTED
        val domain = hostname.lowercase()

        return when {
            adDomains.any { domain == it || domain.endsWith(".$it") } -> BlockType.AD
            trackerDomains.any { domain == it || domain.endsWith(".$it") } -> BlockType.TRACKER
            malwareDomains.any { domain == it || domain.endsWith(".$it") } -> BlockType.MALWARE
            shoppingDomains.any { domain == it || domain.endsWith(".$it") } -> BlockType.SHOPPING
            externalDomains.any { domain == it || domain.endsWith(".$it") } -> BlockType.EXTERNAL
            customDomains.any { domain == it || domain.endsWith(".$it") } -> BlockType.CUSTOM
            else -> BlockType.NONE
        }
    }

    // =========================================================================
    // REFRESH & STATS
    // =========================================================================

    fun refresh() {
        loadCustomLists()
        loadExternalLists()
        loadWhitelist()
        Log.d(TAG, "BlockListManager rafraîchi: ${getStats()}")
    }

    fun getStats(): Stats {
        return Stats(
            builtInAds = adDomains.size,
            builtInTrackers = trackerDomains.size,
            builtInMalware = malwareDomains.size,
            builtInShopping = shoppingDomains.size,
            externalDomains = externalDomains.size,
            customDomains = customDomains.size,
            whitelisted = whitelistedDomains.size,
            neverBlocked = neverBlockDomains.size
        )
    }

    data class Stats(
        val builtInAds: Int,
        val builtInTrackers: Int,
        val builtInMalware: Int,
        val builtInShopping: Int,
        val externalDomains: Int,
        val customDomains: Int,
        val whitelisted: Int,
        val neverBlocked: Int
    ) {
        val totalBuiltIn: Int get() = builtInAds + builtInTrackers + builtInMalware + builtInShopping
        val total: Int get() = totalBuiltIn + externalDomains + customDomains

        override fun toString(): String {
            return "Stats(builtIn=$totalBuiltIn, external=$externalDomains, custom=$customDomains, whitelist=$whitelisted, neverBlocked=$neverBlocked, TOTAL=$total)"
        }
    }

    enum class BlockType {
        NONE,
        AD,
        TRACKER,
        MALWARE,
        SHOPPING,
        EXTERNAL,
        CUSTOM,
        WHITELISTED
    }
}