package fr.bonobo.dnsphere.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import fr.bonobo.dnsphere.BlockListManager
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.BuildConfig
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.LogRetentionPolicy
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = android.widget.FrameLayout(this).apply {
            id = android.R.id.content
        }
        setContentView(container)

        supportActionBar?.apply {
            title = getString(R.string.settings_toolbar_title)
            setDisplayHomeAsUpEnabled(true)
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {

        private lateinit var database: AppDatabase
        private lateinit var blockListManager: BlockListManager

        /**
         * DNSphere UI V2.5
         *
         * Gestion des marges de l'écran Paramètres.
         *
         * - La première section ne passe plus sous la barre "Paramètres".
         * - La dernière préférence (Licence) reste au-dessus de la
         *   barre de navigation Android.
         * - Les insets système sont pris en compte automatiquement.
         *
         * IMPORTANT : ne pas supprimer ce bloc lors des modifications
         * de la liste DNS dans preferences.xml / arrays.xml.
         */
        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            val recyclerView = listView ?: return

            recyclerView.clipToPadding = false

            val density = resources.displayMetrics.density

            fun dp(value: Int): Int =
                (value * density + 0.5f).toInt()

            // Marge de base voulue par le thème DNSphere.
            // 88dp en haut laisse suffisamment d'espace sous la toolbar.
            val baseTop = dp(88)

            // 32dp de confort en plus de la barre de navigation.
            val baseBottom = dp(32)

            ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { v, insets ->

                val systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                )

                /*
                 * Haut :
                 * On conserve une vraie marge visuelle sous la toolbar,
                 * tout en tenant compte du status bar si Android applique
                 * l'edge-to-edge.
                 */
                val topPadding = maxOf(
                    baseTop,
                    systemBars.top + dp(56)
                )

                /*
                 * Bas :
                 * On ajoute systématiquement la hauteur réelle de la
                 * navigation Android + 32dp de respiration.
                 *
                 * Cela empêche Licence / GPL-3.0-or-later de passer
                 * sous la barre de navigation.
                 */
                val bottomPadding = maxOf(
                    baseBottom,
                    systemBars.bottom + baseBottom
                )

                v.setPadding(
                    v.paddingLeft,
                    topPadding,
                    v.paddingRight,
                    bottomPadding
                )

                insets
            }

            // Applique immédiatement les insets si Android les a déjà calculés.
            ViewCompat.requestApplyInsets(recyclerView)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "vpn_prefs"
            setPreferencesFromResource(R.xml.preferences, rootKey)

            database         = AppDatabase.getInstance(requireContext())
            blockListManager = BlockListManager(requireContext())

            findPreference<Preference>("version")?.summary = BuildConfig.VERSION_NAME

            updateDohProviderSummary()

            // ==================== DNS ====================

            findPreference<SwitchPreferenceCompat>("use_doh")?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Toast.makeText(
                    requireContext(),
                    if (enabled) getString(R.string.doh_enabled) else getString(R.string.doh_disabled),
                    Toast.LENGTH_SHORT
                ).show()
                updateVpnServiceIfRunning()
                true
            }

            findPreference<ListPreference>("doh_provider")?.apply {
                summary = getProviderDisplayName(value ?: "cloudflare")

                setOnPreferenceChangeListener { pref, newValue ->
                    val provider = newValue as String
                    (pref as ListPreference).summary = getProviderDisplayName(provider)

                    Toast.makeText(
                        requireContext(),
                        getString(R.string.dns_changed, getProviderDisplayName(provider)),
                        Toast.LENGTH_SHORT
                    ).show()

                    updateVpnServiceIfRunning(overrideDohProvider = provider)
                    true
                }
            }

            // ==================== WEBRTC LEAK PROTECTION ====================

            findPreference<SwitchPreferenceCompat>("webrtc_leak_protection")?.apply {
                isChecked = blockListManager.isWebRtcProtectionEnabled

                setOnPreferenceChangeListener { _, newValue ->
                    val enabled = newValue as Boolean
                    blockListManager.setWebRtcProtection(enabled)
                    Toast.makeText(
                        requireContext(),
                        if (enabled) getString(R.string.webrtc_enabled)
                        else         getString(R.string.webrtc_disabled),
                        Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }

            // ==================== PROFILS ====================

            findPreference<Preference>("profiles")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ProfilesActivity::class.java))
                true
            }

            // ==================== SÉCURITÉ ====================

            findPreference<Preference>("security")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), SecurityActivity::class.java))
                true
            }

            // ==================== CONTRÔLE PARENTAL ====================

            findPreference<Preference>("parental_control")?.setOnPreferenceClickListener {
                requireActivity().supportFragmentManager
                    .beginTransaction()
                    .replace(android.R.id.content, ParentalControlFragment())
                    .addToBackStack(null)
                    .commit()
                true
            }

            // ==================== SAUVEGARDE ====================

            findPreference<Preference>("backup")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), BackupActivity::class.java))
                true
            }

            // ==================== LISTES ====================

            findPreference<Preference>("external_lists")?.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(requireContext(), ExternalListsActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Fonctionnalité non disponible", Toast.LENGTH_SHORT).show()
                }
                true
            }

            findPreference<Preference>("manage_lists")?.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(requireContext(), ListManagerActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Fonctionnalité non disponible", Toast.LENGTH_SHORT).show()
                }
                true
            }

            // ==================== RÈGLES PERSONNALISÉES ====================

            findPreference<Preference>("user_rules")?.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(requireContext(), RulesEditorActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Fonctionnalité non disponible", Toast.LENGTH_SHORT).show()
                }
                true
            }

            // ==================== APPLICATIONS ====================

            findPreference<Preference>("excluded_apps")?.setOnPreferenceClickListener {
                try {
                    startActivity(Intent(requireContext(), AppExcluderActivity::class.java))
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Fonctionnalité non disponible", Toast.LENGTH_SHORT).show()
                }
                true
            }

            // ==================== DONNÉES ====================

            findPreference<ListPreference>("stats_retention_days")?.apply {
                summary = getRetentionSummary(value)

                setOnPreferenceChangeListener { pref, newValue ->
                    val days = LogRetentionPolicy.normalizeDays((newValue as String).toLong())
                    (pref as ListPreference).summary = getRetentionSummary(days.toString())
                    true
                }
            }

            findPreference<Preference>("cleanup_old_logs")?.setOnPreferenceClickListener {
                val retentionDays = getSelectedRetentionDays()
                AlertDialog.Builder(requireContext())
                    .setTitle("Nettoyer les anciens journaux")
                    .setMessage("Supprimer les journaux datant de plus de $retentionDays jours ?")
                    .setPositiveButton("Nettoyer") { _, _ ->
                        lifecycleScope.launch {
                            try {
                                val cutoff = LogRetentionPolicy.cutoff(
                                    System.currentTimeMillis(),
                                    retentionDays
                                )
                                val dao = database.blockLogDao()
                                val count = dao.countOldLogs(cutoff)
                                dao.deleteOldLogs(cutoff)
                                Toast.makeText(
                                    requireContext(),
                                    if (count == 0) "Aucun ancien journal à supprimer"
                                    else "$count journal(aux) supprimé(s)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    requireContext(),
                                    "Impossible de nettoyer les journaux",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
                true
            }

            findPreference<Preference>("clear_logs")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.clear_logs_title)
                    .setMessage(R.string.clear_logs_message)
                    .setPositiveButton(R.string.dialog_clear) { _, _ ->
                        lifecycleScope.launch {
                            database.blockLogDao().clearAll()
                            Toast.makeText(requireContext(), getString(R.string.logs_cleared), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton(R.string.dialog_cancel, null)
                    .show()
                true
            }

            // ==================== À PROPOS ====================

            findPreference<Preference>("developer")?.setOnPreferenceClickListener {
                openUrl("https://github.com/souffly007")
                true
            }

            findPreference<Preference>("github")?.setOnPreferenceClickListener {
                openUrl("https://github.com/souffly007/DNSphere")
                true
            }

            updateListStats()
            updateProfileSummary()
            updateSecuritySummary()
            updateParentalSummary()
        }

        private fun updateVpnServiceIfRunning(overrideDohProvider: String? = null) {
            if (!LocalVpnService.isRunning) return

            val prefs = preferenceManager.sharedPreferences ?: return

            val intent = Intent(requireContext(), LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_UPDATE_CONFIG
                putExtra("block_ads",      prefs.getBoolean("block_ads",      true))
                putExtra("block_trackers", prefs.getBoolean("block_trackers", true))
                putExtra("block_malware",  prefs.getBoolean("block_malware",  true))
                putExtra("block_social",   prefs.getBoolean("block_social",   false))
                putExtra("block_adult",    prefs.getBoolean("block_adult",    false))
                putExtra("block_gambling", prefs.getBoolean("block_gambling", false))
                putExtra("use_doh",        prefs.getBoolean("use_doh",        false))
                putExtra("doh_provider", overrideDohProvider
                    ?: prefs.getString("doh_provider", "cloudflare")
                    ?: "cloudflare")
            }
            requireContext().startService(intent)
        }

        /**
         * Lit uniquement pinEnabled depuis la DB via coroutine — sans instancier
         * ParentalManager (qui chargeait 500K domaines sur la main thread → ANR).
         */
        private fun updateParentalSummary() {
            lifecycleScope.launch {
                try {
                    val config = database.parentalControlDao().get()
                    val pinEnabled = config?.pinEnabled == true
                    findPreference<Preference>("parental_control")?.summary = if (pinEnabled) {
                        "🔒 Activé"
                    } else {
                        "Bloquer des catégories et restreindre les horaires"
                    }
                } catch (e: Exception) { }
            }
        }

        private fun openUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(requireContext(), R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
            }
        }

        private fun getProviderDisplayName(provider: String): String {
            return when (provider) {
                "cloudflare"          -> getString(R.string.doh_provider_cloudflare)
                "google"              -> getString(R.string.doh_provider_google)
                "quad9"               -> getString(R.string.doh_provider_quad9)
                "adguard"             -> getString(R.string.doh_provider_adguard)
                "mullvad"             -> getString(R.string.doh_provider_mullvad)
                "mullvad-adblock"     -> getString(R.string.doh_provider_mullvad_adblock)
                "mullvad-base"        -> getString(R.string.doh_provider_mullvad_base)
                "mullvad-extended"    -> getString(R.string.doh_provider_mullvad_extended)
                "mullvad-family"      -> getString(R.string.doh_provider_mullvad_family)
                "mullvad-all"         -> getString(R.string.doh_provider_mullvad_all)
                "dns4eu-protective"   -> getString(R.string.doh_provider_dns4eu_protective)
                "dns4eu-child"        -> getString(R.string.doh_provider_dns4eu_child)
                "dns4eu-noads"        -> getString(R.string.doh_provider_dns4eu_noads)
                "dns4eu-child-noads"  -> getString(R.string.doh_provider_dns4eu_child_noads)
                "dns4eu-unfiltered"   -> getString(R.string.doh_provider_dns4eu_unfiltered)
                "rethink"             -> getString(R.string.doh_provider_rethink)
                "rethink-light"       -> getString(R.string.doh_provider_rethink_light)
                "rethink-recommended" -> getString(R.string.doh_provider_rethink_recommended)
                "rethink-max"         -> getString(R.string.doh_provider_rethink_max)
                else                  -> provider
            }
        }

        private fun updateDohProviderSummary() {
            findPreference<ListPreference>("doh_provider")?.let { pref ->
                pref.summary = getProviderDisplayName(pref.value ?: "cloudflare")
            }
        }

        private fun getSelectedRetentionDays(): Long {
            val value = findPreference<ListPreference>("stats_retention_days")?.value
                ?: LogRetentionPolicy.DEFAULT_RETENTION_DAYS.toString()
            return LogRetentionPolicy.normalizeDays(value.toLongOrNull()
                ?: LogRetentionPolicy.DEFAULT_RETENTION_DAYS.toLong())
        }

        private fun getRetentionSummary(value: String): String {
            return "Conserver les journaux détaillés pendant ${getSelectedRetentionDaysForSummary(value)} jours"
        }

        private fun getSelectedRetentionDaysForSummary(value: String): Long {
            return LogRetentionPolicy.normalizeDays(value.toLongOrNull()
                ?: LogRetentionPolicy.DEFAULT_RETENTION_DAYS.toLong())
        }

        override fun onResume() {
            super.onResume()
            updateListStats()
            updateDohProviderSummary()
            updateProfileSummary()
            updateSecuritySummary()
            updateParentalSummary()
            findPreference<SwitchPreferenceCompat>("webrtc_leak_protection")?.isChecked =
                blockListManager.isWebRtcProtectionEnabled
        }

        private fun updateProfileSummary() {
            lifecycleScope.launch {
                try {
                    database.profileDao().getActiveProfile().observe(viewLifecycleOwner) { profile ->
                        findPreference<Preference>("profiles")?.summary = if (profile != null) {
                            "${profile.icon} ${profile.name}"
                        } else {
                            getString(R.string.pref_profiles_summary)
                        }
                    }
                } catch (e: Exception) { }
            }
        }

        private fun updateSecuritySummary() {
            try {
                val biometricHelper = fr.bonobo.dnsphere.security.BiometricHelper.getInstance(requireContext())
                findPreference<Preference>("security")?.summary = if (biometricHelper.isBiometricEnabled) {
                    getString(R.string.bio_enabled)
                } else {
                    getString(R.string.pref_security_summary)
                }
            } catch (e: Exception) { }
        }

        private fun updateListStats() {
            lifecycleScope.launch {
                try {
                    val customLists   = database.customListDao().getEnabledLists()
                    val customDomains = customLists.sumOf { it.domainCount }
                    findPreference<Preference>("manage_lists")?.summary =
                        getString(R.string.lists_summary, customLists.size, customDomains.toString())

                    val externalListCount = database.externalListDao().getEnabledListCount()
                    val externalDomains   = database.externalListDao().getTotalEnabledDomains() ?: 0
                    findPreference<Preference>("external_lists")?.summary =
                        getString(R.string.lists_summary, externalListCount, formatNumber(externalDomains))
                } catch (e: Exception) { }
            }
        }

        private fun formatNumber(number: Int): String {
            return when {
                number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
                number >= 1_000     -> String.format("%.1fK", number / 1_000.0)
                else                -> number.toString()
            }
        }
    }
}
