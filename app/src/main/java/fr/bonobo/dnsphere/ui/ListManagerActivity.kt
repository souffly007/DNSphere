package fr.bonobo.dnsphere.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.CustomList
import fr.bonobo.dnsphere.utils.ListImporter
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class ListManagerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ListAdapter
    private lateinit var database: AppDatabase
    private lateinit var listImporter: ListImporter
    private lateinit var emptyView: TextView
    private lateinit var progressBar: CircularProgressIndicator

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { importFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_list_manager)

        database = AppDatabase.getInstance(this)
        listImporter = ListImporter(this)

        supportActionBar?.apply {
            title = "📋 Gestionnaire de listes"
            setDisplayHomeAsUpEnabled(true)
        }

        setupViews()
        observeLists()
    }

    private fun setupViews() {
        recyclerView = findViewById(R.id.recyclerViewLists)
        emptyView = findViewById(R.id.tvEmpty)
        progressBar = findViewById(R.id.progressBar)

        adapter = ListAdapter(
            onToggle = { list, enabled ->
                lifecycleScope.launch {
                    database.customListDao().setListEnabled(list.id, enabled)
                }
            },
            onUpdate = { list ->
                updateList(list)
            },
            onDelete = { list ->
                confirmDelete(list)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showAddDialog()
        }
    }

    private fun observeLists() {
        database.customListDao().getAllLists().observe(this) { lists ->
            adapter.submitList(lists)
            emptyView.visibility = if (lists.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showAddDialog() {
        val options = arrayOf(
            "📥 Importer depuis une URL",
            "📁 Importer un fichier local",
            "⭐ Listes populaires"
        )

        AlertDialog.Builder(this)
            .setTitle("Ajouter une liste")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showUrlDialog()
                    1 -> filePickerLauncher.launch("text/*")
                    2 -> showPopularListsDialog()
                }
            }
            .show()
    }

    private fun showUrlDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_import_url, null)
        val etName = dialogView.findViewById<EditText>(R.id.etName)
        val etUrl = dialogView.findViewById<EditText>(R.id.etUrl)
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinnerType)

        // Setup spinner
        val types = arrayOf("Publicités", "Trackers", "Malware", "Personnalisé")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)

        AlertDialog.Builder(this)
            .setTitle("Importer depuis URL")
            .setView(dialogView)
            .setPositiveButton("Importer") { _, _ ->
                val name = etName.text.toString().trim()
                val url = etUrl.text.toString().trim()
                val type = when (spinnerType.selectedItemPosition) {
                    0 -> "ADS"
                    1 -> "TRACKERS"
                    2 -> "MALWARE"
                    else -> "CUSTOM"
                }

                if (name.isNotEmpty() && url.isNotEmpty()) {
                    importFromUrl(name, url, type)
                } else {
                    Toast.makeText(this, "Veuillez remplir tous les champs", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun showPopularListsDialog() {
        val lists = ListImporter.POPULAR_LISTS
        val items = lists.map { "${it.name}\n${it.description}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Listes populaires")
            .setItems(items) { _, which ->
                val selected = lists[which]
                importFromUrl(selected.name, selected.url, selected.type)
            }
            .show()
    }

    private fun importFromUrl(name: String, url: String, type: String) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = listImporter.importFromUrl(name, url, type) { lines, domains ->
                runOnUiThread {
                    // Optionnel: afficher la progression
                }
            }

            progressBar.visibility = View.GONE

            when (result) {
                is ListImporter.ImportResult.Success -> {
                    Toast.makeText(
                        this@ListManagerActivity,
                        "✅ ${result.domainCount} domaines importés",
                        Toast.LENGTH_LONG
                    ).show()
                }
                is ListImporter.ImportResult.Error -> {
                    Toast.makeText(
                        this@ListManagerActivity,
                        "❌ Erreur: ${result.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun importFromFile(uri: android.net.Uri) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_import_url, null)
        val etName = dialogView.findViewById<EditText>(R.id.etName)
        val etUrl = dialogView.findViewById<EditText>(R.id.etUrl)
        val spinnerType = dialogView.findViewById<Spinner>(R.id.spinnerType)

        etUrl.visibility = View.GONE
        etName.hint = "Nom de la liste"

        val types = arrayOf("Publicités", "Trackers", "Malware", "Personnalisé")
        spinnerType.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, types)

        AlertDialog.Builder(this)
            .setTitle("Importer le fichier")
            .setView(dialogView)
            .setPositiveButton("Importer") { _, _ ->
                val name = etName.text.toString().trim()
                val type = when (spinnerType.selectedItemPosition) {
                    0 -> "ADS"
                    1 -> "TRACKERS"
                    2 -> "MALWARE"
                    else -> "CUSTOM"
                }

                if (name.isNotEmpty()) {
                    progressBar.visibility = View.VISIBLE
                    lifecycleScope.launch {
                        val result = listImporter.importFromFile(name, uri, type)
                        progressBar.visibility = View.GONE

                        when (result) {
                            is ListImporter.ImportResult.Success -> {
                                Toast.makeText(
                                    this@ListManagerActivity,
                                    "✅ ${result.domainCount} domaines importés",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is ListImporter.ImportResult.Error -> {
                                Toast.makeText(
                                    this@ListManagerActivity,
                                    "❌ Erreur: ${result.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun updateList(list: CustomList) {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = listImporter.updateList(list)
            progressBar.visibility = View.GONE

            when (result) {
                is ListImporter.ImportResult.Success -> {
                    Toast.makeText(
                        this@ListManagerActivity,
                        "✅ Liste mise à jour (${result.domainCount} domaines)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is ListImporter.ImportResult.Error -> {
                    Toast.makeText(
                        this@ListManagerActivity,
                        "❌ Erreur: ${result.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun confirmDelete(list: CustomList) {
        AlertDialog.Builder(this)
            .setTitle("Supprimer la liste")
            .setMessage("Voulez-vous supprimer \"${list.name}\" ?")
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch {
                    database.customListDao().deleteDomainsForList(list.id)
                    database.customListDao().deleteList(list)
                    Toast.makeText(this@ListManagerActivity, "Liste supprimée", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // Adapter
    inner class ListAdapter(
        private val onToggle: (CustomList, Boolean) -> Unit,
        private val onUpdate: (CustomList) -> Unit,
        private val onDelete: (CustomList) -> Unit
    ) : RecyclerView.Adapter<ListAdapter.ViewHolder>() {

        private var lists = listOf<CustomList>()
        private val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())

        fun submitList(newLists: List<CustomList>) {
            lists = newLists
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_custom_list, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(lists[position])
        }

        override fun getItemCount() = lists.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvName: TextView = view.findViewById(R.id.tvName)
            private val tvInfo: TextView = view.findViewById(R.id.tvInfo)
            private val tvType: TextView = view.findViewById(R.id.tvType)
            private val switchEnabled: SwitchMaterial = view.findViewById(R.id.switchEnabled)
            private val btnUpdate: ImageButton = view.findViewById(R.id.btnUpdate)
            private val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)

            fun bind(list: CustomList) {
                tvName.text = list.name
                tvInfo.text = "${list.domainCount} domaines • ${dateFormat.format(Date(list.lastUpdated))}"

                tvType.text = when (list.type) {
                    "ADS" -> "🚫 Pubs"
                    "TRACKERS" -> "🔒 Trackers"
                    "MALWARE" -> "☠️ Malware"
                    else -> "📋 Custom"
                }

                switchEnabled.isChecked = list.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(list, isChecked)
                }

                btnUpdate.visibility = if (list.url != null) View.VISIBLE else View.GONE
                btnUpdate.setOnClickListener { onUpdate(list) }

                btnDelete.setOnClickListener { onDelete(list) }
            }
        }
    }
}