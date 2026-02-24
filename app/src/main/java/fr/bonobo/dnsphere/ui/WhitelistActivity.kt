package fr.bonobo.dnsphere.ui

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
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.WhitelistItem
import kotlinx.coroutines.launch

class WhitelistActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: WhitelistAdapter
    private lateinit var database: AppDatabase
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)

        database = AppDatabase.getInstance(this)

        supportActionBar?.apply {
            title = "✅ Whitelist"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.recyclerViewWhitelist)
        emptyView = findViewById(R.id.tvEmpty)

        adapter = WhitelistAdapter { item ->
            lifecycleScope.launch {
                database.whitelistDao().delete(item)
            }
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showAddDialog()
        }

        observeWhitelist()
    }

    private fun observeWhitelist() {
        database.whitelistDao().getAll().observe(this) { items ->
            adapter.submitList(items)
            emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun showAddDialog() {
        val editText = EditText(this).apply {
            hint = "exemple.com"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Ajouter un domaine")
            .setMessage("Ce domaine ne sera jamais bloqué")
            .setView(editText)
            .setPositiveButton("Ajouter") { _, _ ->
                val domain = editText.text.toString().trim().lowercase()
                if (domain.isNotEmpty()) {
                    lifecycleScope.launch {
                        database.whitelistDao().insert(WhitelistItem(domain = domain))
                    }
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class WhitelistAdapter(
        private val onDelete: (WhitelistItem) -> Unit
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

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvDomain: TextView = view.findViewById(R.id.tvDomain)
            private val btnDelete: View = view.findViewById(R.id.btnDelete)

            fun bind(item: WhitelistItem) {
                tvDomain.text = item.domain
                btnDelete.setOnClickListener { onDelete(item) }
            }
        }
    }
}