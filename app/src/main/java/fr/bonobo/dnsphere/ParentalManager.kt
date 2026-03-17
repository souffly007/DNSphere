package fr.bonobo.dnsphere

import android.content.Context
import android.util.Log
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.ParentalControl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest
import java.util.Calendar

/**
 * ParentalManager — gestion du contrôle parental
 *
 * Intégration dans LocalVpnService :
 *   private lateinit var parentalManager: ParentalManager
 *   // dans onCreate() :
 *   parentalManager = ParentalManager(this)
 *   // dans shouldBlock() :
 *   if (parentalManager.shouldBlockNow(hostname)) return true
 */
class ParentalManager(private val context: Context) {

    companion object {
        private const val TAG = "ParentalManager"

        // =====================================================================
        // DOMAINES PAR CATÉGORIE
        // =====================================================================

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

    private val dao = AppDatabase.getInstance(context).parentalControlDao()
    private var config: ParentalControl = ParentalControl()

    init {
        reload()
    }

    // =========================================================================
    // CHARGEMENT
    // =========================================================================

    fun reload() {
        runBlocking {
            config = dao.get() ?: ParentalControl()
            Log.d(TAG, "Config chargée — PIN: ${config.pinEnabled}, Schedule: ${config.scheduleEnabled}")
        }
    }

    fun observe(): Flow<ParentalControl?> = dao.observe()

    // =========================================================================
    // VÉRIFICATION — appelée depuis LocalVpnService / BlockListManager
    // =========================================================================

    /**
     * Retourne true si le domaine doit être bloqué par le contrôle parental
     */
    fun shouldBlockNow(hostname: String): Boolean {
        if (!config.pinEnabled) return false  // contrôle parental désactivé

        // 1. Vérifier la plage horaire
        if (config.scheduleEnabled && !isCurrentlyAllowed()) {
            Log.d(TAG, "⏰ Bloqué hors plage horaire: $hostname")
            return true
        }

        // 2. Vérifier les catégories
        val domain = hostname.lowercase()
        return isBlockedByCategory(domain)
    }

    private fun isBlockedByCategory(domain: String): Boolean {
        if (config.blockAdult && matchesDomain(domain, ADULT_DOMAINS)) {
            Log.d(TAG, "🔞 Bloqué (adulte): $domain"); return true
        }
        if (config.blockGaming && matchesDomain(domain, GAMING_DOMAINS)) {
            Log.d(TAG, "🎮 Bloqué (jeux): $domain"); return true
        }
        if (config.blockSocialMedia && matchesDomain(domain, SOCIAL_DOMAINS)) {
            Log.d(TAG, "📱 Bloqué (réseaux sociaux): $domain"); return true
        }
        if (config.blockStreaming && matchesDomain(domain, STREAMING_DOMAINS)) {
            Log.d(TAG, "🎬 Bloqué (streaming): $domain"); return true
        }
        if (config.blockForums && matchesDomain(domain, FORUM_DOMAINS)) {
            Log.d(TAG, "💬 Bloqué (forums): $domain"); return true
        }
        return false
    }

    private fun matchesDomain(hostname: String, set: Set<String>): Boolean {
        return set.any { hostname == it || hostname.endsWith(".$it") }
    }

    // =========================================================================
    // PLAGE HORAIRE
    // =========================================================================

    fun isCurrentlyAllowed(): Boolean {
        val now = Calendar.getInstance()
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

        // Vérifier si le jour est actif
        if (config.activeDays and dayBit == 0) return true  // jour non contrôlé = autorisé

        val nowMinutes  = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val startMinutes = config.allowedStartHour * 60 + config.allowedStartMinute
        val endMinutes   = config.allowedEndHour   * 60 + config.allowedEndMinute

        return nowMinutes in startMinutes..endMinutes
    }

    // =========================================================================
    // GESTION PIN
    // =========================================================================

    fun hashPin(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun checkPin(pin: String): Boolean {
        return config.pinHash == hashPin(pin)
    }

    fun isPinEnabled(): Boolean = config.pinEnabled

    // =========================================================================
    // SAUVEGARDE (appelée depuis l'UI)
    // =========================================================================

    suspend fun saveConfig(newConfig: ParentalControl) {
        dao.save(newConfig)
        config = newConfig
        Log.d(TAG, "Config sauvegardée")
    }

    suspend fun enableWithPin(pin: String, cfg: ParentalControl) {
        val saved = cfg.copy(
            pinHash    = hashPin(pin),
            pinEnabled = true
        )
        saveConfig(saved)
    }

    suspend fun disable(pin: String): Boolean {
        if (!checkPin(pin)) return false
        saveConfig(config.copy(pinEnabled = false, pinHash = ""))
        return true
    }

    fun getConfig(): ParentalControl = config
}
