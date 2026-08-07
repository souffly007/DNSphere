package fr.bonobo.dnsphere.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import fr.bonobo.dnsphere.BackupManager
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.R
import kotlinx.coroutines.launch

class BackupActivity : AppCompatActivity() {

    private lateinit var backupManager: BackupManager
    private lateinit var btnExport: MaterialButton
    private lateinit var btnImport: MaterialButton
    private lateinit var progressBar: CircularProgressIndicator
    private lateinit var tvStatus: TextView

    // Launcher pour sélectionner un fichier ZIP à importer
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> doImport(uri) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)

        backupManager = BackupManager(this)

        supportActionBar?.apply {
            title = "💾 Sauvegarde & Restauration"
            setDisplayHomeAsUpEnabled(true)
        }

        btnExport   = findViewById(R.id.btnExport)
        btnImport   = findViewById(R.id.btnImport)
        progressBar = findViewById(R.id.progressBar)
        tvStatus    = findViewById(R.id.tvStatus)

        btnExport.setOnClickListener { doExport() }
        btnImport.setOnClickListener { pickFile() }
    }

    // =========================================================================
    // EXPORT
    // =========================================================================

    private fun doExport() {
        setLoading(true, "Export en cours…")

        lifecycleScope.launch {
            val result = backupManager.exportBackup()

            result.onSuccess { uri ->
                setLoading(false)
                // Partager via ShareSheet Android
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "DNSphere - Sauvegarde listes")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Enregistrer la sauvegarde"))
                tvStatus.text = "✅ Sauvegarde prête à partager"
            }.onFailure { error ->
                setLoading(false)
                tvStatus.text = "❌ Erreur : ${error.message}"
                Toast.makeText(this@BackupActivity, "Erreur export : ${error.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // =========================================================================
    // IMPORT
    // =========================================================================

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            // Accepter aussi les fichiers sans type MIME reconnu
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream", "*/*"))
        }
        importLauncher.launch(intent)
    }

    private fun doImport(uri: android.net.Uri) {
        // Demander mode fusion ou remplacement
        AlertDialog.Builder(this)
            .setTitle("📥 Mode de restauration")
            .setMessage("Comment importer cette sauvegarde ?")
            .setPositiveButton("🔀 Fusionner") { _, _ ->
                runImport(uri, mergeMode = true)
            }
            .setNegativeButton("🔄 Remplacer tout") { _, _ ->
                confirmReplace(uri)
            }
            .setNeutralButton("Annuler", null)
            .show()
    }

    private fun confirmReplace(uri: android.net.Uri) {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Remplacer toutes les listes ?")
            .setMessage("Toutes vos listes personnalisées et la whitelist seront effacées et remplacées par la sauvegarde.")
            .setPositiveButton("Remplacer") { _, _ ->
                runImport(uri, mergeMode = false)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun runImport(uri: android.net.Uri, mergeMode: Boolean) {
        val modeLabel = if (mergeMode) "fusion" else "remplacement"
        setLoading(true, "Import ($modeLabel) en cours…")

        lifecycleScope.launch {
            val result = backupManager.importBackup(uri, mergeMode)

            result.onSuccess { importResult ->
                setLoading(false)

                val msg = buildString {
                    append("✅ Import terminé !\n\n")
                    append("• Whitelist : ${importResult.whitelistImported} domaines\n")
                    append("• Listes custom : ${importResult.customListsImported} listes (${importResult.customDomainsImported} domaines)\n")
                    append("• Listes externes : ${importResult.externalListsImported} listes\n")
                    if (importResult.settingsRestored) append("• Paramètres restaurés\n")
                    if (importResult.errors.isNotEmpty()) {
                        append("\n⚠️ Avertissements :\n")
                        importResult.errors.forEach { append("• $it\n") }
                    }
                }

                tvStatus.text = "✅ Import terminé"
                AlertDialog.Builder(this@BackupActivity)
                    .setTitle("Import terminé")
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .show()

                // Recharger le VPN à chaud si actif
                if (LocalVpnService.isRunning) {
                    startService(Intent(this@BackupActivity, LocalVpnService::class.java).apply {
                        action = LocalVpnService.ACTION_UPDATE_CONFIG
                    })
                }

            }.onFailure { error ->
                setLoading(false)
                tvStatus.text = "❌ Erreur : ${error.message}"
                AlertDialog.Builder(this@BackupActivity)
                    .setTitle("❌ Erreur d'import")
                    .setMessage(error.message)
                    .setPositiveButton("OK", null)
                    .show()
            }
        }
    }

    private fun setLoading(loading: Boolean, message: String = "") {
        btnExport.isEnabled   = !loading
        btnImport.isEnabled   = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        if (message.isNotEmpty()) tvStatus.text = message
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}