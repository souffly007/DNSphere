package fr.bonobo.dnsphere.data

import android.content.Context

object ProviderPreferencesMigration {
    @Synchronized fun migrate(context: Context) {
        for (name in listOf("dnsphere_prefs", "vpn_prefs", "${context.packageName}_preferences")) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = prefs.edit()
            var changed = 0
            for (key in listOf("current_dns_provider", "doh_provider", "dns_server")) {
                val old = prefs.getString(key, null) ?: continue
                val replacement = ProviderMigration.normalize(old)
                if (replacement == "quad9" && replacement != old) {
                    editor.putString(key, replacement)
                    changed++
                }
            }
            if (changed > 0) {
                check(editor.commit()) { "Cannot persist DNS provider migration" }
            }
        }
    }
}
