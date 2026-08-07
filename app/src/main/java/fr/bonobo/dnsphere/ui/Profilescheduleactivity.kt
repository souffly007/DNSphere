// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of PhoneZen.
//
// PhoneZen is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License.class Profilescheduleactivity {
package fr.bonobo.dnsphere.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.bonobo.dnsphere.ProfileSchedulerWorker
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.Profile
import fr.bonobo.dnsphere.data.ProfileSchedule
import kotlinx.coroutines.launch

class ProfileScheduleActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ScheduleAdapter
    private lateinit var progressBar: View
    private lateinit var tvEmpty: TextView

    private var profiles = listOf<Profile>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile_schedule)

        database = AppDatabase.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        loadData()

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showAddScheduleDialog()
        }
    }

    private fun setupToolbar() {
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
    }

    private fun setupRecyclerView() {
        recyclerView = findViewById(R.id.recyclerViewSchedules)
        progressBar  = findViewById(R.id.progressBar)
        tvEmpty      = findViewById(R.id.tvEmpty)

        adapter = ScheduleAdapter(
            onToggle = { schedule, enabled -> toggleSchedule(schedule, enabled) },
            onDelete = { schedule -> deleteSchedule(schedule) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter       = adapter
    }

    private fun loadData() {
        lifecycleScope.launch {
            profiles = database.profileDao().getAllProfilesList()
        }

        database.profileScheduleDao().getAll().observe(this) { schedules ->
            adapter.submitList(schedules, profiles)
            tvEmpty.visibility      = if (schedules.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (schedules.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    // =========================================================================
    // DIALOG AJOUT CRÉNEAU
    // =========================================================================

    private fun showAddScheduleDialog() {
        if (profiles.isEmpty()) {
            Toast.makeText(this, "Aucun profil disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_schedule, null)

        val spinnerProfile = dialogView.findViewById<Spinner>(R.id.spinnerProfile)
        val etLabel        = dialogView.findViewById<EditText>(R.id.etLabel)
        val npStartHour    = dialogView.findViewById<NumberPicker>(R.id.npStartHour)
        val npStartMin     = dialogView.findViewById<NumberPicker>(R.id.npStartMinute)
        val npEndHour      = dialogView.findViewById<NumberPicker>(R.id.npEndHour)
        val npEndMin       = dialogView.findViewById<NumberPicker>(R.id.npEndMinute)
        val chipGroupDays  = dialogView.findViewById<ChipGroup>(R.id.chipGroupDays)

        // Spinner profils
        val profileNames = profiles.map { "${it.icon} ${it.name}" }
        spinnerProfile.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profileNames)

        // NumberPickers heures
        npStartHour.minValue = 0;  npStartHour.maxValue = 23
        npStartMin.minValue  = 0;  npStartMin.maxValue  = 59
        npEndHour.minValue   = 0;  npEndHour.maxValue   = 23
        npEndMin.minValue    = 0;  npEndMin.maxValue    = 59

        // Valeurs par défaut
        npStartHour.value = 8;  npStartMin.value  = 0
        npEndHour.value   = 18; npEndMin.value    = 0

        // Formater les valeurs avec zéro devant
        val formatter = NumberPicker.Formatter { String.format("%02d", it) }
        npStartHour.setFormatter(formatter); npStartMin.setFormatter(formatter)
        npEndHour.setFormatter(formatter);   npEndMin.setFormatter(formatter)

        MaterialAlertDialogBuilder(this)
            .setTitle("⏰ Nouveau créneau")
            .setView(dialogView)
            .setPositiveButton("Ajouter") { _, _ ->
                val selectedProfile = profiles[spinnerProfile.selectedItemPosition]
                val label           = etLabel.text?.toString()?.trim() ?: ""
                val activeDays      = getSelectedDays(chipGroupDays)

                if (activeDays == 0) {
                    Toast.makeText(this, "Sélectionne au moins un jour", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val schedule = ProfileSchedule(
                    profileId    = selectedProfile.id,
                    label        = label.ifEmpty { selectedProfile.name },
                    startHour    = npStartHour.value,
                    startMinute  = npStartMin.value,
                    endHour      = npEndHour.value,
                    endMinute    = npEndMin.value,
                    activeDays   = activeDays
                )

                lifecycleScope.launch {
                    database.profileScheduleDao().insert(schedule)
                    Toast.makeText(this@ProfileScheduleActivity, "Créneau ajouté", Toast.LENGTH_SHORT).show()
                    // Évaluer immédiatement
                    ProfileSchedulerWorker.evaluateNow(this@ProfileScheduleActivity)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun getSelectedDays(chipGroup: ChipGroup): Int {
        var days = 0
        for (i in 0 until chipGroup.childCount) {
            val chip = chipGroup.getChildAt(i) as? Chip ?: continue
            if (chip.isChecked) {
                days = days or when (i) {
                    0 -> ProfileSchedule.MON
                    1 -> ProfileSchedule.TUE
                    2 -> ProfileSchedule.WED
                    3 -> ProfileSchedule.THU
                    4 -> ProfileSchedule.FRI
                    5 -> ProfileSchedule.SAT
                    6 -> ProfileSchedule.SUN
                    else -> 0
                }
            }
        }
        return days
    }

    private fun toggleSchedule(schedule: ProfileSchedule, enabled: Boolean) {
        lifecycleScope.launch {
            database.profileScheduleDao().setEnabled(schedule.id, enabled)
            if (enabled) ProfileSchedulerWorker.evaluateNow(this@ProfileScheduleActivity)
        }
    }

    private fun deleteSchedule(schedule: ProfileSchedule) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Supprimer ce créneau ?")
            .setMessage("${schedule.label} — ${schedule.getTimeLabel()}")
            .setPositiveButton("Supprimer") { _, _ ->
                lifecycleScope.launch {
                    database.profileScheduleDao().delete(schedule)
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // =========================================================================
    // ADAPTER
    // =========================================================================

    inner class ScheduleAdapter(
        private val onToggle: (ProfileSchedule, Boolean) -> Unit,
        private val onDelete: (ProfileSchedule) -> Unit
    ) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

        private var schedules = listOf<ProfileSchedule>()
        private var profiles  = listOf<Profile>()

        fun submitList(newSchedules: List<ProfileSchedule>, newProfiles: List<Profile>) {
            schedules = newSchedules
            profiles  = newProfiles
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_schedule, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(schedules[position])
        }

        override fun getItemCount() = schedules.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvLabel:   TextView      = view.findViewById(R.id.tvLabel)
            private val tvProfile: TextView      = view.findViewById(R.id.tvProfile)
            private val tvTime:    TextView      = view.findViewById(R.id.tvTime)
            private val tvDays:    TextView      = view.findViewById(R.id.tvDays)
            private val switchEnabled: SwitchMaterial = view.findViewById(R.id.switchEnabled)
            private val btnDelete: ImageButton   = view.findViewById(R.id.btnDelete)

            fun bind(schedule: ProfileSchedule) {
                val profile = profiles.find { it.id == schedule.profileId }

                tvLabel.text   = schedule.label.ifEmpty { profile?.name ?: "—" }
                tvProfile.text = profile?.let { "${it.icon} ${it.name}" } ?: "Profil inconnu"
                tvTime.text    = schedule.getTimeLabel()
                tvDays.text    = schedule.getDaysLabel()

                switchEnabled.setOnCheckedChangeListener(null)
                switchEnabled.isChecked = schedule.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(schedule, isChecked)
                }

                btnDelete.setOnClickListener { onDelete(schedule) }

                // Indicateur visuel si actif maintenant
                itemView.alpha = if (schedule.enabled) 1f else 0.5f
            }
        }
    }
}