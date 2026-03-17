package fr.bonobo.dnsphere.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.*
import fr.bonobo.dnsphere.lists.KnownHostsLists
import fr.bonobo.dnsphere.lists.ListDownloader
import fr.bonobo.dnsphere.lists.ListUpdateWorker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExternalListsActivity : AppCompatActivity() {

    private lateinit var recyclerView:   RecyclerView
    private lateinit var adapter:        ExternalListAdapter
    private lateinit var progressBar:    LinearProgressIndicator
    private lateinit var tvTotalDomains: TextView
    private lateinit var tvActiveLists:  TextView
    private lateinit var btnUpdateAll:   MaterialButton
    private lateinit var btnAddList:     MaterialButton
    private lateinit var btnAddKnown:    MaterialButton   // ← NOUVEAU

    private lateinit var database:   AppDatabase
    private lateinit var downloader: ListDownloader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_external_lists)

        database   = AppDatabase.getInstance(this)
        downloader = ListDownloader(this)

        initViews()
        setupListeners()
        observeLists()
        ListUpdateWorker.schedule(this, intervalHours = 24)
    }

    private fun initViews() {
        recyclerView   = findViewById(R.id.recyclerView)
        progressBar    = findViewById(R.id.progressBar)
        tvTotalDomains = findViewById(R.id.tvTotalDomains)
        tvActiveLists  = findViewById(R.id.tvActiveLists)
        btnUpdateAll   = findViewById(R.id.btnUpdateAll)
        btnAddList     = findViewById(R.id.btnAddList)
        btnAddKnown    = findViewById(R.id.btnAddKnown)

        findViewById<View>(R.id.toolbar).setOnClickListener { finish() }

        adapter = ExternalListAdapter(
            onToggle = { list, enabled -> toggleList(list, enabled) },
            onUpdate = { list -> updateSingleList(list) },
            onDelete = { list -> confirmDeleteList(list) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
    }

    private fun setupListeners() {
        btnUpdateAll.setOnClickListener { updateAllLists() }
        btnAddList.setOnClickListener   { showAddListDialog() }
        btnAddKnown.setOnClickListener  { showKnownListsDialog() }  // ← NOUVEAU
    }

    private fun observeLists() {
        lifecycleScope.launch {
            database.externalListDao().getAllLists().collectLatest { lists ->
                adapter.submitList(lists)
                updateStats()
            }
        }
    }

    private fun updateStats() {
        lifecycleScope.launch {
            val totalDomains = database.externalListDao().getTotalEnabledDomains() ?: 0
            val activeLists  = database.externalListDao().getEnabledListCount()
            tvTotalDomains.text = formatNumber(totalDomains)
            tvActiveLists.text  = activeLists.toString()
        }
    }

    // =========================================================================
    // DIALOGUE LISTES CONNUES — NOUVEAU
    // =========================================================================

    private fun showKnownListsDialog() {
        val dialogView   = layoutInflater.inflate(R.layout.dialog_known_lists, null)
        val chipGroup    = dialogView.findViewById<ChipGroup>(R.id.chipGroupCategories)
        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.recyclerKnownLists)

        var selectedCategory: ListCategory? = null
        var filteredLists = KnownHostsLists.ALL

        val knownAdapter = KnownListAdapter { knownList ->
            addKnownList(knownList)
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = knownAdapter
        knownAdapter.submitList(filteredLists)

        // Chips de filtrage par catégorie
        val categories = listOf(null) + KnownHostsLists.BY_CATEGORY.keys.toList()
        categories.forEach { cat ->
            val chip = Chip(this).apply {
                text  = if (cat == null) "Tout" else "${categoryIcon(cat)} ${cat.name.lowercase().replaceFirstChar { it.uppercase() }}"
                isCheckable = true
                isChecked   = (cat == null)
                setOnClickListener {
                    selectedCategory = cat
                    filteredLists    = if (cat == null) KnownHostsLists.ALL
                    else KnownHostsLists.BY_CATEGORY[cat] ?: emptyList()
                    knownAdapter.submitList(filteredLists)
                }
            }
            chipGroup.addView(chip)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("📚 Listes connues")
            .setView(dialogView)
            .setNegativeButton("Fermer", null)
            .show()
    }

    private fun addKnownList(knownList: KnownHostsLists.KnownList) {
        lifecycleScope.launch {
            // Vérifier si déjà présente
            if (database.externalListDao().existsByUrl(knownList.url)) {
                Toast.makeText(this@ExternalListsActivity,
                    "\"${knownList.name}\" est déjà dans vos listes", Toast.LENGTH_SHORT).show()
                return@launch
            }

            progressBar.visibility = View.VISIBLE
            Toast.makeText(this@ExternalListsActivity,
                "⏳ Téléchargement de ${knownList.name}…", Toast.LENGTH_SHORT).show()

            val result = downloader.addCustomList(
                name        = knownList.name,
                url         = knownList.url,
                description = knownList.description,
                category    = knownList.category
            )

            result.onSuccess { list ->
                Toast.makeText(this@ExternalListsActivity,
                    "✅ ${knownList.name}: ${list.domainCount} domaines", Toast.LENGTH_LONG).show()
                notifyVpnReload()
            }.onFailure { error ->
                Toast.makeText(this@ExternalListsActivity,
                    "❌ ${knownList.name}: ${error.message}", Toast.LENGTH_LONG).show()
            }

            progressBar.visibility = View.GONE
            updateStats()
        }
    }

    private fun categoryIcon(cat: ListCategory) = when (cat) {
        ListCategory.ADS      -> "🛡️"
        ListCategory.TRACKERS -> "🔍"
        ListCategory.MALWARE  -> "🦠"
        ListCategory.PRIVACY  -> "🔒"
        ListCategory.SOCIAL   -> "📱"
        ListCategory.CUSTOM   -> "📝"
    }

    // =========================================================================
    // RESTE DES FONCTIONS EXISTANTES
    // =========================================================================

    private fun formatNumber(number: Int): String {
        return when {
            number >= 1_000_000 -> String.format("%.1fM", number / 1_000_000.0)
            number >= 1_000     -> String.format("%.1fK", number / 1_000.0)
            else                -> number.toString()
        }
    }

    private fun toggleList(list: ExternalList, enabled: Boolean) {
        lifecycleScope.launch {
            database.externalListDao().setListEnabled(list.id, enabled)
            if (enabled && list.domainCount == 0) updateSingleList(list)
            notifyVpnReload()
        }
    }

    private fun updateSingleList(list: ExternalList) {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            val result = downloader.updateList(list)
            result.onSuccess { count ->
                Toast.makeText(this@ExternalListsActivity,
                    "✅ ${list.name}: $count domaines", Toast.LENGTH_SHORT).show()
                notifyVpnReload()
            }.onFailure { error ->
                Toast.makeText(this@ExternalListsActivity,
                    "❌ ${list.name}: ${error.message}", Toast.LENGTH_SHORT).show()
            }
            progressBar.visibility = View.GONE
            updateStats()
        }
    }

    private fun updateAllLists() {
        lifecycleScope.launch {
            progressBar.visibility = View.VISIBLE
            btnUpdateAll.isEnabled = false
            btnUpdateAll.text      = "⏳ Mise à jour…"

            val results      = downloader.updateAllEnabledLists()
            val success      = results.values.count { it.isSuccess }
            val failed       = results.values.count { it.isFailure }
            val totalDomains = results.values.mapNotNull { it.getOrNull() }.sum()

            Toast.makeText(this@ExternalListsActivity,
                "✅ $success listes • $totalDomains domaines" +
                        if (failed > 0) " • ❌ $failed erreurs" else "",
                Toast.LENGTH_LONG).show()

            notifyVpnReload()
            progressBar.visibility = View.GONE
            btnUpdateAll.isEnabled = true
            btnUpdateAll.text      = "🔄 Mettre à jour"
            updateStats()
        }
    }

    private fun showAddListDialog() {
        val dialogView      = layoutInflater.inflate(R.layout.dialog_add_list, null)
        val etName          = dialogView.findViewById<EditText>(R.id.etName)
        val etUrl           = dialogView.findViewById<EditText>(R.id.etUrl)
        val etDescription   = dialogView.findViewById<EditText>(R.id.etDescription)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinnerCategory)

        val categories = ListCategory.values().map { it.name }
        spinnerCategory.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, categories)

        MaterialAlertDialogBuilder(this)
            .setTitle("➕ Ajouter une liste (URL)")
            .setView(dialogView)
            .setPositiveButton("Ajouter") { _, _ ->
                val name     = etName.text.toString().trim()
                val url      = etUrl.text.toString().trim()
                val desc     = etDescription.text.toString().trim()
                val category = ListCategory.valueOf(spinnerCategory.selectedItem.toString())

                if (name.isBlank() || url.isBlank()) {
                    Toast.makeText(this, "Nom et URL requis", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                lifecycleScope.launch {
                    progressBar.visibility = View.VISIBLE
                    val result = downloader.addCustomList(name, url, desc, category)
                    result.onSuccess { list ->
                        Toast.makeText(this@ExternalListsActivity,
                            "✅ Liste ajoutée: ${list.domainCount} domaines", Toast.LENGTH_SHORT).show()
                        notifyVpnReload()
                    }.onFailure { error ->
                        Toast.makeText(this@ExternalListsActivity,
                            "❌ Erreur: ${error.message}", Toast.LENGTH_SHORT).show()
                    }
                    progressBar.visibility = View.GONE
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun confirmDeleteList(list: ExternalList) {
        if (list.isBuiltIn) {
            Toast.makeText(this, "Impossible de supprimer une liste intégrée", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("🗑️ Supprimer la liste ?")
            .setMessage("Supprimer \"${list.name}\" et ses ${list.domainCount} domaines ?")
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch {
                    database.externalListDao().deleteList(list)
                    notifyVpnReload()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun notifyVpnReload() {
        if (LocalVpnService.isRunning) {
            startService(Intent(this, LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_UPDATE_CONFIG
            })
        }
    }

    // =========================================================================
    // ADAPTER LISTES CONNUES — NOUVEAU
    // =========================================================================

    inner class KnownListAdapter(
        private val onAdd: (KnownHostsLists.KnownList) -> Unit
    ) : RecyclerView.Adapter<KnownListAdapter.ViewHolder>() {

        private var items = listOf<KnownHostsLists.KnownList>()

        fun submitList(newItems: List<KnownHostsLists.KnownList>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_known_list, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvIcon:        TextView      = view.findViewById(R.id.tvIcon)
            private val tvName:        TextView      = view.findViewById(R.id.tvName)
            private val tvDescription: TextView      = view.findViewById(R.id.tvDescription)
            private val tvCount:       TextView      = view.findViewById(R.id.tvCount)
            private val btnAdd:        MaterialButton = view.findViewById(R.id.btnAdd)

            fun bind(item: KnownHostsLists.KnownList) {
                tvIcon.text        = item.icon
                tvName.text        = item.name
                tvDescription.text = item.description
                tvCount.text       = item.approxCount
                btnAdd.setOnClickListener { onAdd(item) }
            }
        }
    }

    // =========================================================================
    // ADAPTER LISTES EXISTANTES
    // =========================================================================

    inner class ExternalListAdapter(
        private val onToggle: (ExternalList, Boolean) -> Unit,
        private val onUpdate: (ExternalList) -> Unit,
        private val onDelete: (ExternalList) -> Unit
    ) : RecyclerView.Adapter<ExternalListAdapter.ViewHolder>() {

        private var lists = listOf<ExternalList>()

        fun submitList(newLists: List<ExternalList>) {
            lists = newLists; notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_external_list, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(lists[position])

        override fun getItemCount() = lists.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvIcon:        TextView       = view.findViewById(R.id.tvIcon)
            private val tvName:        TextView       = view.findViewById(R.id.tvName)
            private val tvDescription: TextView       = view.findViewById(R.id.tvDescription)
            private val tvDomainCount: TextView       = view.findViewById(R.id.tvDomainCount)
            private val tvLastUpdate:  TextView       = view.findViewById(R.id.tvLastUpdate)
            private val tvError:       TextView       = view.findViewById(R.id.tvError)
            private val switchEnabled: SwitchMaterial = view.findViewById(R.id.switchEnabled)

            fun bind(list: ExternalList) {
                tvIcon.text = categoryIcon(list.category)
                tvName.text        = list.name
                tvDescription.text = list.description
                tvDomainCount.text = when {
                    list.domainCount >= 1000 -> "${list.domainCount / 1000}K domaines"
                    list.domainCount > 0     -> "${list.domainCount} domaines"
                    else                     -> "Non téléchargé"
                }
                tvLastUpdate.text = if (list.lastUpdated > 0) {
                    DateUtils.getRelativeTimeSpanString(list.lastUpdated,
                        System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                } else "Jamais mis à jour"

                tvError.visibility = if (list.lastError != null) View.VISIBLE else View.GONE
                tvError.setOnClickListener {
                    Toast.makeText(itemView.context, list.lastError, Toast.LENGTH_LONG).show()
                }

                switchEnabled.setOnCheckedChangeListener(null)
                switchEnabled.isChecked = list.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked -> onToggle(list, isChecked) }

                itemView.setOnClickListener     { onUpdate(list) }
                itemView.setOnLongClickListener { onDelete(list); true }
            }
        }
    }
}