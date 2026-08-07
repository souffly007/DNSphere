package fr.bonobo.dnsphere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.BlockLog
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class LogsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: LogsAdapter
    private lateinit var database: AppDatabase
    private lateinit var emptyView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        database = AppDatabase.getInstance(this)

        supportActionBar?.apply {
            title = "📋 Logs de blocage"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.recyclerViewLogs)
        emptyView = findViewById(R.id.tvEmpty)

        adapter = LogsAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        observeLogs()

        findViewById<View>(R.id.fabClear).setOnClickListener {
            lifecycleScope.launch {
                database.blockLogDao().clearAll()
            }
        }
    }

    private fun observeLogs() {
        database.blockLogDao().getAllLogs().observe(this) { logs ->
            adapter.submitList(logs)
            emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class LogsAdapter : RecyclerView.Adapter<LogsAdapter.LogViewHolder>() {

        private var logs = listOf<BlockLog>()
        private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        fun submitList(newLogs: List<BlockLog>) {
            logs = newLogs
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log, parent, false)
            return LogViewHolder(view)
        }

        override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
            holder.bind(logs[position])
        }

        override fun getItemCount() = logs.size

        inner class LogViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvDomain: TextView = view.findViewById(R.id.tvDomain)
            private val tvType: TextView = view.findViewById(R.id.tvType)
            private val tvTime: TextView = view.findViewById(R.id.tvTime)
            private val tvIcon: TextView = view.findViewById(R.id.tvIcon)

            fun bind(log: BlockLog) {
                tvDomain.text = log.domain
                tvTime.text = dateFormat.format(Date(log.timestamp))

                when (log.type) {
                    "AD" -> {
                        tvType.text = "Publicité"
                        tvType.setTextColor(getColor(R.color.green))
                        tvIcon.text = "🚫"
                    }
                    "TRACKER" -> {
                        tvType.text = "Tracker"
                        tvType.setTextColor(getColor(R.color.orange))
                        tvIcon.text = "🔒"
                    }
                    "MALWARE" -> {
                        tvType.text = "Malware"
                        tvType.setTextColor(getColor(R.color.red))
                        tvIcon.text = "⚠️"
                    }
                    else -> {
                        tvType.text = log.type
                        tvType.setTextColor(getColor(R.color.gray))
                        tvIcon.text = "🔹"
                    }
                }
            }
        }
    }
}