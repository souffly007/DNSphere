package fr.bonobo.dnsphere

import android.content.Context
import android.util.Log
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.ExternalList
import fr.bonobo.dnsphere.data.ListCategory
import fr.bonobo.dnsphere.data.ParentalControl
import fr.bonobo.dnsphere.lists.KnownHostsLists
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Calendar

class ParentalManager(private val context: Context) {

    companion object {
        private const val TAG = "ParentalManager"

        val ADULT_DOMAINS = setOf(
            "pornhub.com", "xvideos.com", "xnxx.com", "xhamster.com",
            "redtube.com", "youporn.com", "tube8.com", "xtube.com",
            "brazzers.com", "bangbros.com", "naughtyamerica.com",
            "adult-empire.com", "adultfriendfinder.com",
            "onlyfans.com", "fansly.com", "chaturbate.com",
            "livejasmin.com", "myfreecams.com", "cam4.com",
            "bongacams.com", "stripchat.com", "streamate.com"
        )

        val GAMING_DOMAINS = setOf(
            "steampowered.com", "store.steampowered.com",
            "epicgames.com", "fortnite.com",
            "riotgames.com", "leagueoflegends.com", "valorant.com",
            "blizzard.com", "battle.net", "overwatch.com",
            "ea.com", "origin.com", "eaplay.com",
            "minecraft.net", "mojang.com",
            "roblox.com", "robloxcdn.com",
            "xbox.com", "xboxlive.com",
            "playstation.com", "psn.com",
            "nintendo.com", "nintendoswitch.com",
            "twitch.tv", "twitchapps.com",
            "poki.com", "friv.com", "miniclip.com",
            "ign.com", "jeuxvideo.com", "gameblog.fr",
            "ubisoft.com", "bethesda.net",
            "genshin.hoyoverse.com", "hoyoverse.com",
            "rockstargames.com", "take2games.com"
        )

        val SOCIAL_DOMAINS = setOf(
            "tiktok.com", "vm.tiktok.com", "tiktokcdn.com",
            "snapchat.com", "snap.com", "sc-cdn.net",
            "twitter.com", "x.com", "twimg.com",
            "reddit.com", "redd.it", "redditstatic.com",
            "tumblr.com", "ask.fm",
            "pinterest.com", "pinimg.com",
            "linkedin.com", "lnkd.in",
            "twitch.tv",
            "9gag.com", "ifunny.co",
            "vk.com", "ok.ru",
            "bereal.com"
        )

        val STREAMING_DOMAINS = setOf(
            "netflix.com", "nflxvideo.net", "nflximg.net",
            "disneyplus.com", "disney-plus.net",
            "primevideo.com", "aiv-cdn.net",
            "hbomax.com", "max.com",
            "crunchyroll.com", "vrv.co",
            "twitch.tv", "ttvnw.net",
            "dailymotion.com", "vimeo.com",
            "youtube.com", "youtu.be", "ytimg.com",
            "molotov.tv", "salto.fr",
            "mycanal.fr", "canalplus.com",
            "tf1.fr", "tf1plus.fr",
            "6play.fr", "m6.fr",
            "france.tv", "arte.tv"
        )

        val FORUM_DOMAINS = setOf(
            "reddit.com", "redd.it",
            "4chan.org", "4channel.org",
            "jeuxvideo.com",
            "forum.hardware.fr",
            "developpez.com",
            "forumfr.com",
            "quora.com",
            "stackoverflow.com",
            "discord.com", "discordapp.com"
        )
    }

    private val dao      = AppDatabase.getInstance(context).parentalControlDao()
    private val database = AppDatabase.getInstance(context)
    private var config: ParentalControl = ParentalControl()

    // HashSet pour O(1) lookup — la clé c'est qu'on cherche dans le sens inverse
    // (est-ce que le hostname ou un de ses parents est dans le set ?)
    private val externalAdultDomains = HashSet<String>(1 shl 20) // pré-allouer ~1M buckets

    init {
        reload()
    }

    // =========================================================================
    // CHARGEMENT
    // =========================================================================

    fun reload() {
        runBlocking {
            config = dao.get() ?: ParentalControl()
            loadExternalParentalDomains()
            Log.d(TAG, "Config chargée — PIN: ${config.pinEnabled}, " +
                    "listes adultes externes: ${externalAdultDomains.size} domaines")
        }
    }

    private suspend fun loadExternalParentalDomains() {
        try {
            val domains = database.externalListDao().getDomainsByCategory(ListCategory.PARENTAL)
            externalAdultDomains.clear()
            externalAdultDomains.addAll(domains)
            Log.d(TAG, "Listes parental externes: ${externalAdultDomains.size} domaines chargés")
        } catch (e: Exception) {
            Log.e(TAG, "Erreur chargement listes parental: ${e.message}")
        }
    }

    fun observe(): Flow<ParentalControl?> = dao.observe()

    // =========================================================================
    // VÉRIFICATION
    // =========================================================================

    fun shouldBlockNow(hostname: String): Boolean {
        if (!config.pinEnabled) return false

        if (config.scheduleEnabled && !isCurrentlyAllowed()) {
            Log.d(TAG, "⏰ Bloqué hors plage horaire: $hostname")
            return true
        }

        val domain = hostname.lowercase()
        return isBlockedByCategory(domain)
    }

    private fun isBlockedByCategory(domain: String): Boolean {
        if (config.blockAdult) {
            if (matchesDomainFast(domain, ADULT_DOMAINS)) {
                Log.d(TAG, "🔞 Bloqué statique (adulte): $domain"); return true
            }
            if (externalAdultDomains.isNotEmpty() && matchesDomainFast(domain, externalAdultDomains)) {
                Log.d(TAG, "🔞 Bloqué liste externe (adulte): $domain"); return true
            }
        }
        if (config.blockGaming && matchesDomainFast(domain, GAMING_DOMAINS)) {
            Log.d(TAG, "🎮 Bloqué (jeux): $domain"); return true
        }
        if (config.blockSocialMedia && matchesDomainFast(domain, SOCIAL_DOMAINS)) {
            Log.d(TAG, "📱 Bloqué (réseaux sociaux): $domain"); return true
        }
        if (config.blockStreaming && matchesDomainFast(domain, STREAMING_DOMAINS)) {
            Log.d(TAG, "🎬 Bloqué (streaming): $domain"); return true
        }
        if (config.blockForums && matchesDomainFast(domain, FORUM_DOMAINS)) {
            Log.d(TAG, "💬 Bloqué (forums): $domain"); return true
        }
        return false
    }

    /**
     * Lookup O(nombre de points dans hostname) au lieu de O(taille du set).
     *
     * Au lieu d'itérer tous les éléments du set pour voir si hostname.endsWith(".$it"),
     * on extrait les parents successifs du hostname et on les cherche dans le HashSet en O(1).
     *
     * ex: "www.pornhub.com"
     *   → cherche "www.pornhub.com" → non
     *   → cherche "pornhub.com"     → OUI ✓
     */
    private fun matchesDomainFast(hostname: String, set: Set<String>): Boolean {
        // Match exact
        if (hostname in set) return true
        // Match sous-domaines : extraire chaque parent successif
        var dot = hostname.indexOf('.')
        while (dot != -1) {
            val parent = hostname.substring(dot + 1)
            if (parent in set) return true
            dot = hostname.indexOf('.', dot + 1)
        }
        return false
    }

    // =========================================================================
    // PLAGE HORAIRE
    // =========================================================================

    fun isCurrentlyAllowed(): Boolean {
        val now    = Calendar.getInstance()
        val dayBit = when (now.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY    -> 1
            Calendar.TUESDAY   -> 2
            Calendar.WEDNESDAY -> 4
            Calendar.THURSDAY  -> 8
            Calendar.FRIDAY    -> 16
            Calendar.SATURDAY  -> 32
            Calendar.SUNDAY    -> 64
            else               -> 0
        }

        if (config.activeDays and dayBit == 0) return true

        val nowMinutes   = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = config.allowedStartHour * 60 + config.allowedStartMinute
        val endMinutes   = config.allowedEndHour   * 60 + config.allowedEndMinute

        return nowMinutes in startMinutes..endMinutes
    }

    // =========================================================================
    // LISTES EXTERNES PARENTAL
    // =========================================================================

    suspend fun installParentalDefaultLists() {
        val existingUrls = database.externalListDao().getAllUrls().toSet()

        val toInstall = KnownHostsLists.PARENTAL_DEFAULTS.filter { it.url !in existingUrls }

        if (toInstall.isEmpty()) {
            database.externalListDao().enableByCategory(ListCategory.PARENTAL)
            loadExternalParentalDomains()
            return
        }

        toInstall.forEach { known ->
            database.externalListDao().insertList(ExternalList(
                name        = known.name,
                url         = known.url,
                description = known.description,
                category    = known.category,
                format      = known.format,
                enabled     = true,
                isBuiltIn   = true
            ))
            Log.d(TAG, "Liste parental installée: ${known.name}")
        }
    }

    fun externalAdultDomainsCount(): Int = externalAdultDomains.size

    // =========================================================================
    // PIN
    // =========================================================================

    fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun checkPin(pin: String): Boolean = config.pinHash == hashPin(pin)
    fun isPinEnabled(): Boolean = config.pinEnabled

    // =========================================================================
    // SAUVEGARDE
    // =========================================================================

    suspend fun saveConfig(newConfig: ParentalControl) {
        dao.save(newConfig)
        config = newConfig
        loadExternalParentalDomains()
        Log.d(TAG, "Config sauvegardée")
    }

    suspend fun enableWithPin(pin: String, cfg: ParentalControl) {
        saveConfig(cfg.copy(pinHash = hashPin(pin), pinEnabled = true))
        installParentalDefaultLists()
    }

    suspend fun disable(pin: String): Boolean {
        if (!checkPin(pin)) return false
        saveConfig(config.copy(pinEnabled = false, pinHash = ""))
        return true
    }

    fun getConfig(): ParentalControl = config
}