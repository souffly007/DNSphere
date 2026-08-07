package fr.bonobo.dnsphere.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.WhitelistItem
import kotlinx.coroutines.launch

class WhitelistActivity : AppCompatActivity() {

    private lateinit var recyclerView:      RecyclerView
    private lateinit var recyclerForceBlock: RecyclerView
    private lateinit var adapter:           WhitelistAdapter
    private lateinit var forceBlockAdapter: WhitelistAdapter
    private lateinit var database:          AppDatabase
    private lateinit var emptyView:         TextView
    private lateinit var emptyForceView:    TextView
    private lateinit var tabLayout:         TabLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)

        database = AppDatabase.getInstance(this)

        supportActionBar?.apply {
            title = "✅ Whitelist & 🚫 Blocage forcé"
            setDisplayHomeAsUpEnabled(true)
        }

        tabLayout         = findViewById(R.id.tabLayout)
        recyclerView      = findViewById(R.id.recyclerViewWhitelist)
        recyclerForceBlock = findViewById(R.id.recyclerViewForceBlock)
        emptyView         = findViewById(R.id.tvEmpty)
        emptyForceView    = findViewById(R.id.tvEmptyForce)

        // Adapter whitelist normale
        adapter = WhitelistAdapter(
            onDelete = { item ->
                lifecycleScope.launch {
                    database.whitelistDao().delete(item)
                    notifyVpnReload()
                }
            },
            isForceBlock = false
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Adapter blocage forcé
        forceBlockAdapter = WhitelistAdapter(
            onDelete = { item ->
                lifecycleScope.launch {
                    database.whitelistDao().delete(item)
                    notifyVpnReload()
                }
            },
            isForceBlock = true
        )
        recyclerForceBlock.layoutManager = LinearLayoutManager(this)
        recyclerForceBlock.adapter = forceBlockAdapter

        // Tabs : Whitelist / Blocage forcé
        tabLayout.addTab(tabLayout.newTab().setText("✅ Whitelist"))
        tabLayout.addTab(tabLayout.newTab().setText("🚫 Blocage forcé"))
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                val showForce = tab.position == 1
                recyclerView.visibility       = if (!showForce) View.VISIBLE else View.GONE
                emptyView.visibility          = if (!showForce && adapter.itemCount == 0) View.VISIBLE else View.GONE
                recyclerForceBlock.visibility = if (showForce) View.VISIBLE else View.GONE
                emptyForceView.visibility     = if (showForce && forceBlockAdapter.itemCount == 0) View.VISIBLE else View.GONE
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            val isForceTab = tabLayout.selectedTabPosition == 1
            showAddDialog(forceBlock = isForceTab)
        }

        observeWhitelist()
        observeForceBlock()
    }

    private fun observeWhitelist() {
        database.whitelistDao().getAll().observe(this) { items ->
            adapter.submitList(items)
            if (tabLayout.selectedTabPosition == 0) {
                emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun observeForceBlock() {
        database.whitelistDao().getAllForceBlockedLive().observe(this) { items ->
            forceBlockAdapter.submitList(items)
            if (tabLayout.selectedTabPosition == 1) {
                emptyForceView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showAddDialog(forceBlock: Boolean) {
        val editText = EditText(this).apply {
            hint = "exemple.com ou login.spotify.com"
            setPadding(50, 30, 50, 30)
        }

        val title   = if (forceBlock) "🚫 Ajouter un blocage forcé" else "✅ Ajouter à la whitelist"
        val message = if (forceBlock)
            "Ce domaine sera TOUJOURS bloqué, même s'il est dans la liste protégée."
        else
            "Ce domaine ne sera jamais bloqué."

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setView(editText)
            .setPositiveButton("Ajouter") { _, _ ->
                val domain = editText.text.toString().trim().lowercase()
                if (domain.isNotEmpty()) {
                    lifecycleScope.launch {
                        database.whitelistDao().insert(
                            WhitelistItem(domain = domain, forceBlock = forceBlock)
                        )
                        notifyVpnReload()
                    }
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // =========================================================================
    // ADAPTER (commun whitelist + forceBlock)
    // =========================================================================
    inner class WhitelistAdapter(
        private val onDelete: (WhitelistItem) -> Unit,
        private val isForceBlock: Boolean
    ) : RecyclerView.Adapter<WhitelistAdapter.ViewHolder>() {

        private var items = listOf<WhitelistItem>()

        fun submitList(newItems: List<WhitelistItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_whitelist, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvDomain: TextView = view.findViewById(R.id.tvDomain)
            private val btnDelete: View    = view.findViewById(R.id.btnDelete)

            fun bind(item: WhitelistItem) {
                tvDomain.text      = item.domain
                tvDomain.setTextColor(
                    if (isForceBlock)
                        resources.getColor(android.R.color.holo_red_light, null)
                    else
                        resources.getColor(android.R.color.white, null)
                )
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}