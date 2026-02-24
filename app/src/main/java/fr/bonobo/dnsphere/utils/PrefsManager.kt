package fr.bonobo.dnsphere.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

class PrefsManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("dnsphere_prefs", Context.MODE_PRIVATE)

    companion object {
        // DNS Settings
        const val KEY_DNS_MODE = "dns_mode"  // "standard", "doh", "dot"
        const val KEY_DNS_SERVER = "dns_server"  // "cloudflare", "google", "quad9", "custom"
        const val KEY_CUSTOM_DNS = "custom_dns"

        // Blocking
        const val KEY_BLOCK_ADS = "block_ads"
        const val KEY_BLOCK_TRACKERS = "block_trackers"
        const val KEY_BLOCK_MALWARE = "block_malware"

        // Features
        const val KEY_LOGGING_ENABLED = "logging_enabled"
        const val KEY_NOTIFICATION_ON_BLOCK = "notification_on_block"
        const val KEY_AUTO_START = "auto_start"

        // Stats
        const val KEY_TOTAL_ADS_BLOCKED = "total_ads_blocked"
        const val KEY_TOTAL_TRACKERS_BLOCKED = "total_trackers_blocked"
        const val KEY_VPN_STARTED_AT = "vpn_started_at"

        // Lists
        const val KEY_CUSTOM_BLOCKLIST_URL = "custom_blocklist_url"
        const val KEY_LAST_LIST_UPDATE = "last_list_update"
    }

    // DNS Mode
    var dnsMode: String
        get() = prefs.getString(KEY_DNS_MODE, "doh") ?: "doh"
        set(value) = prefs.edit { putString(KEY_DNS_MODE, value) }

    var dnsServer: String
        get() = prefs.getString(KEY_DNS_SERVER, "cloudflare") ?: "cloudflare"
        set(value) = prefs.edit { putString(KEY_DNS_SERVER, value) }

    var customDns: String
        get() = prefs.getString(KEY_CUSTOM_DNS, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_DNS, value) }

    // Blocking
    var blockAds: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_ADS, true)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_ADS, value) }

    var blockTrackers: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_TRACKERS, true)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_TRACKERS, value) }

    var blockMalware: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_MALWARE, true)
        set(value) = prefs.edit { putBoolean(KEY_BLOCK_MALWARE, value) }

    // Features
    var loggingEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOGGING_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_LOGGING_ENABLED, value) }

    var notificationOnBlock: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATION_ON_BLOCK, false)
        set(value) = prefs.edit { putBoolean(KEY_NOTIFICATION_ON_BLOCK, value) }

    var autoStart: Boolean
        get() = prefs.getBoolean(KEY_AUTO_START, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_START, value) }

    // Stats
    var totalAdsBlocked: Int
        get() = prefs.getInt(KEY_TOTAL_ADS_BLOCKED, 0)
        set(value) = prefs.edit { putInt(KEY_TOTAL_ADS_BLOCKED, value) }

    var totalTrackersBlocked: Int
        get() = prefs.getInt(KEY_TOTAL_TRACKERS_BLOCKED, 0)
        set(value) = prefs.edit { putInt(KEY_TOTAL_TRACKERS_BLOCKED, value) }

    var vpnStartedAt: Long
        get() = prefs.getLong(KEY_VPN_STARTED_AT, 0)
        set(value) = prefs.edit { putLong(KEY_VPN_STARTED_AT, value) }

    // Custom lists
    var customBlocklistUrl: String
        get() = prefs.getString(KEY_CUSTOM_BLOCKLIST_URL, "") ?: ""
        set(value) = prefs.edit { putString(KEY_CUSTOM_BLOCKLIST_URL, value) }

    var lastListUpdate: Long
        get() = prefs.getLong(KEY_LAST_LIST_UPDATE, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_LIST_UPDATE, value) }

    fun incrementAdsBlocked() {
        totalAdsBlocked++
    }

    fun incrementTrackersBlocked() {
        totalTrackersBlocked++
    }
}