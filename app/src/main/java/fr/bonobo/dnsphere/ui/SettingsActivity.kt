package fr.bonobo.dnsphere.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import fr.bonobo.dnsphere.R
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
            title = "⚙️ Paramètres"
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
            setPreferencesFromResource(R.xml.preferences, rootKey)

            database = AppDatabase.getInstance(requireContext())

            // DoH Switch
            findPreference<SwitchPreferenceCompat>("use_doh")?.setOnPreferenceChangeListener { _, newValue ->
                val enabled = newValue as Boolean
                Toast.makeText(
                    requireContext(),
                    if (enabled) "DoH activé - Redémarrez la protection" else "DoH désactivé",
                    Toast.LENGTH_SHORT
                ).show()
                true
            }

            // DoH Provider
            findPreference<ListPreference>("doh_provider")?.setOnPreferenceChangeListener { pref, newValue ->
                val provider = newValue as String
                (pref as ListPreference).summary = when (provider) {
                    "cloudflare" -> "Cloudflare (recommandé)"
                    "google" -> "Google"
                    "quad9" -> "Quad9 (sécurité)"
                    "adguard" -> "AdGuard (anti-pub)"
                    else -> provider
                }
                true
            }

            // Gérer les listes
            findPreference<Preference>("manage_lists")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), ListManagerActivity::class.java))
                true
            }

            // Exclure des apps
            findPreference<Preference>("excluded_apps")?.setOnPreferenceClickListener {
                startActivity(Intent(requireContext(), AppExcluderActivity::class.java))
                true
            }

            // Effacer les logs
            findPreference<Preference>("clear_logs")?.setOnPreferenceClickListener {
                AlertDialog.Builder(requireContext())
                    .setTitle("Effacer les logs")
                    .setMessage("Voulez-vous supprimer tous les logs de blocage ?")
                    .setPositiveButton("Effacer") { _, _ ->
                        lifecycleScope.launch {
                            database.blockLogDao().clearAll()
                            Toast.makeText(requireContext(), "Logs effacés", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .setNegativeButton("Annuler", null)
                    .show()
                true
            }

            // Statistiques des listes
            updateListStats()
        }

        private fun updateListStats() {
            lifecycleScope.launch {
                try {
                    val lists = database.customListDao().getEnabledLists()
                    val totalDomains = lists.sumOf { it.domainCount }

                    findPreference<Preference>("manage_lists")?.summary =
                        "${lists.size} listes actives • $totalDomains domaines"
                } catch (e: Exception) {
                    // Ignorer
                }
            }
        }
    }
}