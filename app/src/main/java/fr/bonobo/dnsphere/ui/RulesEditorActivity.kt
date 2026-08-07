// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class RulesEditorActivity {
package fr.bonobo.dnsphere.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.tabs.TabLayout
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.RulesEngine
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.RuleAction
import fr.bonobo.dnsphere.data.RuleType
import fr.bonobo.dnsphere.data.UserRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * RulesEditorActivity — éditeur de règles utilisateur.
 *
 * Onglet 0 : Éditeur texte brut (une règle par ligne, syntaxe AdGuard/regex)
 * Onglet 1 : Liste des règles avec activation/désactivation et suppression
 *
 * Layout attendu : activity_rules_editor.xml (voir ci-dessous)
 */
class RulesEditorActivity : AppCompatActivity() {

    private lateinit var tabLayout: TabLayout
    private lateinit var editorContainer: View
    private lateinit var listContainer: View

    // Onglet texte brut
    private lateinit var editRules: EditText
    private lateinit var btnApplyText: Button
    private lateinit var tvTextStats: TextView

    // Onglet liste
    private lateinit var recyclerView: RecyclerView
    private lateinit var btnAddRule: Button
    private lateinit var tvListStats: TextView

    private val db by lazy { AppDatabase.getInstance(this) }
    private val dao by lazy { db.userRuleDao() }

    private var allRules: List<UserRule> = emptyList()
    private lateinit var adapter: RuleAdapter

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rules_editor)

        supportActionBar?.apply {
            title = getString(R.string.rules_editor_title)
            setDisplayHomeAsUpEnabled(true)
        }

        bindViews()
        setupTabs()
        setupList()
        setupTextEditor()
        observeRules()
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_rules_editor, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all -> { confirmClearAll(); true }
            R.id.action_help      -> { showHelp(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Binding ───────────────────────────────────────────────────────────────

    private fun bindViews() {
        tabLayout       = findViewById(R.id.tabLayout)
        editorContainer = findViewById(R.id.containerTextEditor)
        listContainer   = findViewById(R.id.containerList)

        editRules    = findViewById(R.id.editRules)
        btnApplyText = findViewById(R.id.btnApplyText)
        tvTextStats  = findViewById(R.id.tvTextStats)

        recyclerView = findViewById(R.id.recyclerRules)
        btnAddRule   = findViewById(R.id.btnAddRule)
        tvListStats  = findViewById(R.id.tvListStats)
    }

    // ── Onglets ───────────────────────────────────────────────────────────────

    private fun setupTabs() {
        tabLayout.addTab(tabLayout.newTab().setText(R.string.rules_tab_text))
        tabLayout.addTab(tabLayout.newTab().setText(R.string.rules_tab_list))

        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                editorContainer.visibility = if (tab.position == 0) View.VISIBLE else View.GONE
                listContainer.visibility   = if (tab.position == 1) View.VISIBLE else View.GONE
                if (tab.position == 0) syncTextFromDb()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    // ── Onglet texte brut ─────────────────────────────────────────────────────

    private fun setupTextEditor() {
        editRules.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateTextStats(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnApplyText.setOnClickListener { applyTextRules() }
    }

    private fun updateTextStats(text: String) {
        val result = RulesEngine.parseTextBlock(text)
        val block = result.rules.count { it.action == RuleAction.BLOCK }
        val allow = result.rules.count { it.action == RuleAction.ALLOW }
        tvTextStats.text = getString(R.string.rules_text_stats, block, allow, result.ignoredLines)
    }

    private fun syncTextFromDb() {
        lifecycleScope.launch {
            val rules = withContext(Dispatchers.IO) { dao.getEnabledRules() }
            editRules.setText(RulesEngine.rulesToText(rules))
        }
    }

    private fun applyTextRules() {
        val text = editRules.text.toString()
        val result = RulesEngine.parseTextBlock(text)

        if (result.rules.isEmpty() && text.isNotBlank()) {
            Toast.makeText(this, R.string.rules_no_valid_rules, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.rules_apply_confirm_title)
            .setMessage(getString(R.string.rules_apply_confirm_msg,
                result.rules.size, result.ignoredLines))
            .setPositiveButton(R.string.rules_apply_btn) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    dao.deleteAll()
                    dao.insertAll(result.rules)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@RulesEditorActivity,
                            getString(R.string.rules_applied, result.rules.size),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ── Onglet liste ──────────────────────────────────────────────────────────

    private fun setupList() {
        adapter = RuleAdapter(
            onToggle = { rule -> toggleRule(rule) },
            onDelete = { rule -> confirmDelete(rule) },
            onEdit   = { rule -> showEditDialog(rule) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Swipe pour supprimer
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val rule = adapter.getRuleAt(viewHolder.bindingAdapterPosition)
                confirmDelete(rule)
            }
        }).attachToRecyclerView(recyclerView)

        btnAddRule.setOnClickListener { showAddDialog() }
    }

    private fun observeRules() {
        lifecycleScope.launch {
            dao.getAllRules().collectLatest { rules ->
                allRules = rules
                adapter.submitList(rules)
                val block = rules.count { it.action == RuleAction.BLOCK && it.enabled }
                val allow = rules.count { it.action == RuleAction.ALLOW && it.enabled }
                tvListStats.text = getString(R.string.rules_list_stats, block, allow, rules.size)
            }
        }
    }

    private fun toggleRule(rule: UserRule) {
        lifecycleScope.launch(Dispatchers.IO) {
            dao.setEnabled(rule.id, !rule.enabled)
        }
    }

    private fun confirmDelete(rule: UserRule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.rules_delete_title)
            .setMessage(getString(R.string.rules_delete_msg, rule.pattern))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { dao.delete(rule) }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ── Dialogue ajout / édition ──────────────────────────────────────────────

    private fun showAddDialog() = showRuleDialog(null)
    private fun showEditDialog(rule: UserRule) = showRuleDialog(rule)

    private fun showRuleDialog(existing: UserRule?) {
        val view = layoutInflater.inflate(R.layout.dialog_add_rule, null)
        val editPattern  = view.findViewById<EditText>(R.id.editPattern)
        val spinnerType  = view.findViewById<Spinner>(R.id.spinnerType)
        val spinnerAction= view.findViewById<Spinner>(R.id.spinnerAction)
        val editComment  = view.findViewById<EditText>(R.id.editComment)
        val tvPreview    = view.findViewById<TextView>(R.id.tvPreview)

        // Peupler les spinners
        ArrayAdapter.createFromResource(this, R.array.rule_types,
            android.R.layout.simple_spinner_item).also { a ->
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerType.adapter = a
        }
        ArrayAdapter.createFromResource(this, R.array.rule_actions,
            android.R.layout.simple_spinner_item).also { a ->
            a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinnerAction.adapter = a
        }

        // Pré-remplir si édition
        existing?.let {
            editPattern.setText(it.pattern)
            spinnerType.setSelection(it.type.ordinal)
            spinnerAction.setSelection(it.action.ordinal)
            editComment.setText(it.comment)
        }

        // Prévisualisation en temps réel
        val watcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updatePreview(editPattern, spinnerType, spinnerAction, tvPreview) }
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        }
        editPattern.addTextChangedListener(watcher)
        val selWatcher = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) =
                updatePreview(editPattern, spinnerType, spinnerAction, tvPreview)
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        spinnerType.onItemSelectedListener = selWatcher
        spinnerAction.onItemSelectedListener = selWatcher

        AlertDialog.Builder(this)
            .setTitle(if (existing == null) R.string.rules_add_title else R.string.rules_edit_title)
            .setView(view)
            .setPositiveButton(if (existing == null) R.string.dialog_add else R.string.common_save) { _, _ ->
                val pattern = editPattern.text.toString().trim()
                if (pattern.isEmpty()) return@setPositiveButton
                val type    = RuleType.values()[spinnerType.selectedItemPosition]
                val action  = RuleAction.values()[spinnerAction.selectedItemPosition]
                val comment = editComment.text.toString().trim()
                val rule = (existing ?: UserRule(pattern = "", type = type, action = action))
                    .copy(pattern = pattern, type = type, action = action, comment = comment)
                lifecycleScope.launch(Dispatchers.IO) { dao.insert(rule) }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun updatePreview(
        editPattern: EditText,
        spinnerType: Spinner,
        spinnerAction: Spinner,
        tvPreview: TextView
    ) {
        val pattern = editPattern.text.toString().trim()
        val type    = RuleType.values()[spinnerType.selectedItemPosition]
        val action  = RuleAction.values()[spinnerAction.selectedItemPosition]
        if (pattern.isEmpty()) { tvPreview.text = ""; return }

        val preview = when (type) {
            RuleType.ADGUARD -> buildString {
                if (action == RuleAction.ALLOW) append("@@")
                append("||$pattern^")
            }
            RuleType.REGEX -> buildString {
                if (action == RuleAction.ALLOW) append("@@")
                append("regex:$pattern")
            }
        }
        tvPreview.text = preview
    }

    // ── Autres actions ────────────────────────────────────────────────────────

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.rules_clear_title)
            .setMessage(R.string.rules_clear_msg)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) { dao.deleteAll() }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showHelp() {
        AlertDialog.Builder(this)
            .setTitle(R.string.rules_help_title)
            .setMessage(R.string.rules_help_content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ── Adapter RecyclerView ──────────────────────────────────────────────────

    inner class RuleAdapter(
        private val onToggle: (UserRule) -> Unit,
        private val onDelete: (UserRule) -> Unit,
        private val onEdit:   (UserRule) -> Unit
    ) : RecyclerView.Adapter<RuleAdapter.ViewHolder>() {

        private var items: List<UserRule> = emptyList()

        fun submitList(list: List<UserRule>) {
            items = list
            notifyDataSetChanged()
        }

        fun getRuleAt(position: Int) = items[position]

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvPattern  : TextView  = view.findViewById(R.id.tvPattern)
            val tvMeta     : TextView  = view.findViewById(R.id.tvMeta)
            val tvComment  : TextView  = view.findViewById(R.id.tvComment)
            val switchEnabled: android.widget.Switch = view.findViewById(R.id.switchEnabled)
            val btnEdit    : ImageButton = view.findViewById(R.id.btnEdit)
            val btnDelete  : ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_user_rule, parent, false)
            return ViewHolder(v)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val rule = items[position]

            // Pattern formaté
            holder.tvPattern.text = when (rule.type) {
                RuleType.ADGUARD -> if (rule.action == RuleAction.ALLOW) "@@||${rule.pattern}^"
                else "||${rule.pattern}^"
                RuleType.REGEX   -> if (rule.action == RuleAction.ALLOW) "@@regex:${rule.pattern}"
                else "regex:${rule.pattern}"
            }

            // Couleur selon action
            val color = if (rule.action == RuleAction.BLOCK)
                android.R.color.holo_red_dark else android.R.color.holo_green_dark
            holder.tvPattern.setTextColor(holder.itemView.context.getColor(color))

            // Meta : type + action
            holder.tvMeta.text = "${rule.type.name} · ${rule.action.name}"

            // Commentaire
            holder.tvComment.visibility = if (rule.comment.isBlank()) View.GONE else View.VISIBLE
            holder.tvComment.text = rule.comment

            holder.switchEnabled.isChecked = rule.enabled
            holder.switchEnabled.setOnCheckedChangeListener(null)
            holder.switchEnabled.setOnCheckedChangeListener { _, _ -> onToggle(rule) }

            holder.btnEdit.setOnClickListener   { onEdit(rule) }
            holder.btnDelete.setOnClickListener { onDelete(rule) }
        }
    }
}