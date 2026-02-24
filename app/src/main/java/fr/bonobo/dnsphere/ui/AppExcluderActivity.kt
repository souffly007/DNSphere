package fr.bonobo.dnsphere.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.checkbox.MaterialCheckBox
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.ExcludedApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppExcluderActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppAdapter
    private lateinit var database: AppDatabase
    private lateinit var progressBar: View

    private var excludedPackages = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_excluder)

        database = AppDatabase.getInstance(this)

        supportActionBar?.apply {
            title = "📱 Applications exclues"
            setDisplayHomeAsUpEnabled(true)
        }

        recyclerView = findViewById(R.id.recyclerViewApps)
        progressBar = findViewById(R.id.progressBar)

        adapter = AppAdapter { appInfo, isExcluded ->
            toggleAppExclusion(appInfo, isExcluded)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadApps()
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Charger les apps exclues
            excludedPackages = database.excludedAppDao().getAllPackageNames().toMutableSet()

            // Charger toutes les apps installées
            val apps = withContext(Dispatchers.IO) {
                val pm = packageManager
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 } // Exclure les apps système
                    .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            }

            adapter.submitList(apps, excludedPackages)
            progressBar.visibility = View.GONE
        }
    }

    private fun toggleAppExclusion(appInfo: ApplicationInfo, exclude: Boolean) {
        lifecycleScope.launch {
            if (exclude) {
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                database.excludedAppDao().insert(
                    ExcludedApp(packageName = appInfo.packageName, appName = appName)
                )
                excludedPackages.add(appInfo.packageName)
            } else {
                database.excludedAppDao().deleteByPackage(appInfo.packageName)
                excludedPackages.remove(appInfo.packageName)
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    inner class AppAdapter(
        private val onToggle: (ApplicationInfo, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

        private var apps = listOf<ApplicationInfo>()
        private var excluded = setOf<String>()

        fun submitList(newApps: List<ApplicationInfo>, excludedApps: Set<String>) {
            apps = newApps
            excluded = excludedApps
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(apps[position])
        }

        override fun getItemCount() = apps.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val ivIcon: ImageView = view.findViewById(R.id.ivIcon)
            private val tvName: TextView = view.findViewById(R.id.tvName)
            private val tvPackage: TextView = view.findViewById(R.id.tvPackage)
            private val checkbox: MaterialCheckBox = view.findViewById(R.id.checkbox)

            fun bind(appInfo: ApplicationInfo) {
                val pm = packageManager
                ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
                tvName.text = pm.getApplicationLabel(appInfo)
                tvPackage.text = appInfo.packageName

                checkbox.isChecked = excluded.contains(appInfo.packageName)

                checkbox.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(appInfo, isChecked)
                }

                itemView.setOnClickListener {
                    checkbox.isChecked = !checkbox.isChecked
                }
            }
        }
    }
}