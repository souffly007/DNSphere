package fr.bonobo.dnsphere.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.BuildConfig
import fr.bonobo.dnsphere.data.AppDatabase
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

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = "vpn_prefs"
            setPreferencesFromResource(R.xml.preferences, rootKey)

            database = AppDatabase.getInstance(requireContext())

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

            // ==================== MODE PLANIFIÉ - SUPPRIMÉ ====================
            // La fonctionnalité de planning a été retirée

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
            // updateScheduleSummary() - SUPPRIMÉ
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

        private fun updateParentalSummary() {
            try {
                val parentalManager = fr.bonobo.dnsphere.ParentalManager(requireContext())
                findPreference<Preference>("parental_control")?.summary = if (parentalManager.isPinEnabled()) {
                    "🔒 Activé"
                } else {
                    "Bloquer des catégories et restreindre les horaires"
                }
            } catch (e: Exception) { }
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
                "cloudflare" -> getString(R.string.doh_provider_cloudflare)
                "google"     -> getString(R.string.doh_provider_google)
                "quad9"      -> getString(R.string.doh_provider_quad9)
                "adguard"    -> getString(R.string.doh_provider_adguard)
                else         -> provider
            }
        }

        private fun updateDohProviderSummary() {
            findPreference<ListPreference>("doh_provider")?.let { pref ->
                pref.summary = getProviderDisplayName(pref.value ?: "cloudflare")
            }
        }

        override fun onResume() {
            super.onResume()
            updateListStats()
            updateDohProviderSummary()
            // updateScheduleSummary() - SUPPRIMÉ
            updateProfileSummary()
            updateSecuritySummary()
            updateParentalSummary()
        }

        // Méthode updateScheduleSummary() ENTIÈREMENT SUPPRIMÉE

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