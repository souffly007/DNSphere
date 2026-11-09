// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Appsactivity {
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import fr.bonobo.dnsphere.AppFilterManager
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.AppRule
import fr.bonobo.dnsphere.data.AppRuleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppRuleAdapter
    private lateinit var database: AppDatabase
    private lateinit var appFilterManager: AppFilterManager
    private lateinit var progressBar: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_apps)

        database         = AppDatabase.getInstance(this)
        appFilterManager = AppFilterManager(this)

        setupToolbar()
        setupRecyclerView()
        loadApps()
    }

    private fun setupToolbar() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerViewApps)
        progressBar  = findViewById(R.id.progressBar)

        adapter = AppRuleAdapter { appInfo, currentRule ->
            showRuleDialog(appInfo, currentRule)
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter       = adapter
    }

    private fun loadApps() {
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) {
                database.appRuleDao().getAllSync().associateBy { it.packageName }
            }

            val apps = withContext(Dispatchers.IO) {
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
                    .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 }
                    .sortedBy { packageManager.getApplicationLabel(it).toString().lowercase() }
            }

            adapter.submitList(apps, rules)
            progressBar.visibility = View.GONE
        }
    }

    /**
     * Dialog de sélection de règle pour une app
     */
    private fun showRuleDialog(appInfo: ApplicationInfo, currentRule: AppRule?) {
        val appName = packageManager.getApplicationLabel(appInfo).toString()
        val options = arrayOf(
            "⚙️ Par défaut (filtrage standard)",
            "🚫 Tout bloquer",
            "✅ Tout autoriser (bypass)",
            "🔒 Supprimer la règle"
        )

        val currentIndex = when (currentRule?.rule) {
            AppRuleType.DEFAULT   -> 0
            AppRuleType.BLOCK_ALL -> 1
            AppRuleType.ALLOW_ALL -> 2
            null                  -> -1
            else                  -> 0
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(appName)
            .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> applyRule(appInfo, appName, AppRuleType.DEFAULT)
                    1 -> applyRule(appInfo, appName, AppRuleType.BLOCK_ALL)
                    2 -> applyRule(appInfo, appName, AppRuleType.ALLOW_ALL)
                    3 -> removeRule(appInfo)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun applyRule(appInfo: ApplicationInfo, appName: String, type: AppRuleType) {
        lifecycleScope.launch {
            val uid  = appInfo.uid
            val rule = AppRule(
                uid         = uid,
                packageName = appInfo.packageName,
                appName     = appName,
                rule        = type
            )
            appFilterManager.setRule(rule)
            loadApps() // Rafraîchir la liste
        }
    }

    private fun removeRule(appInfo: ApplicationInfo) {
        lifecycleScope.launch {
            appFilterManager.removeRule(appInfo.uid)
            loadApps()
        }
    }

    // =========================================================================
    // ADAPTER
    // =========================================================================

    inner class AppRuleAdapter(
        private val onAppClick: (ApplicationInfo, AppRule?) -> Unit
    ) : RecyclerView.Adapter<AppRuleAdapter.ViewHolder>() {

        private var apps  = listOf<ApplicationInfo>()
        private var rules = mapOf<String, AppRule>()

        fun submitList(newApps: List<ApplicationInfo>, newRules: Map<String, AppRule>) {
            apps  = newApps
            rules = newRules
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_rule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(apps[position])
        }

        override fun getItemCount() = apps.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val ivIcon:   ImageView = view.findViewById(R.id.ivIcon)
            private val tvName:   TextView  = view.findViewById(R.id.tvName)
            private val tvPackage: TextView = view.findViewById(R.id.tvPackage)
            private val tvRule:   TextView  = view.findViewById(R.id.tvRule)

            fun bind(appInfo: ApplicationInfo) {
                val pm      = packageManager
                val rule    = rules[appInfo.packageName]

                ivIcon.setImageDrawable(pm.getApplicationIcon(appInfo))
                tvName.text    = pm.getApplicationLabel(appInfo)
                tvPackage.text = appInfo.packageName

                // Badge de règle
                when (rule?.rule) {
                    AppRuleType.DEFAULT    -> {
                        tvRule.text = "⚙️ Par défaut"
                        tvRule.setBackgroundResource(R.drawable.badge_gray)
                    }
                    AppRuleType.BLOCK_ALL  -> {
                        tvRule.text = "🚫 Bloqué"
                        tvRule.setBackgroundResource(R.drawable.badge_red)
                    }
                    AppRuleType.ALLOW_ALL  -> {
                        tvRule.text = "✅ Autorisé"
                        tvRule.setBackgroundResource(R.drawable.badge_green)
                    }
                    AppRuleType.CUSTOM_DNS -> {
                        tvRule.text = "🔒 DNS custom"
                        tvRule.setBackgroundResource(R.drawable.badge_orange)
                    }
                    null -> {
                        tvRule.text = ""
                        tvRule.background = null
                    }
                }

                itemView.setOnClickListener { onAppClick(appInfo, rule) }
            }
        }
    }
}