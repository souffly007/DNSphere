package fr.bonobo.dnsphere.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.data.DnsProviderCatalog

/**
 * Activity légère pour sélectionner un DNS provider via popup
 * Appelée depuis la notification pour ouvrir un menu avec tous les DNS disponibles
 */
class QuickDNSSelectorActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val labels = DnsProviderCatalog.providers.map { it.label }.toTypedArray()
        val providers = DnsProviderCatalog.ids

        // Créer et afficher le dialog
        AlertDialog.Builder(this)
            .setTitle("🌐 Sélectionner un DNS")
            .setItems(labels) { _, which ->
                val selectedProvider = providers[which]
                // Envoyer l'Intent au service pour switcher de DNS
                switchDns(selectedProvider)
                // Fermer l'activity
                finish()
            }
            .setOnCancelListener {
                // Si l'utilisateur annule, fermer sans rien faire
                finish()
            }
            .show()
    }

    /**
     * Envoyer l'Intent ACTION_SWITCH_DNS au LocalVpnService
     */
    private fun switchDns(provider: String) {
        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_SWITCH_DNS
            putExtra(LocalVpnService.EXTRA_DNS_PROVIDER, provider)
        }
        startService(intent)
    }
}
