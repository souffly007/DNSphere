package fr.bonobo.dnsphere.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.Schedule
import fr.bonobo.dnsphere.scheduler.ScheduleManager
import kotlinx.coroutines.launch

class ScheduleActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var scheduleManager: ScheduleManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var layoutEmpty: View
    private lateinit var adapter: ScheduleAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_schedule)

        database = AppDatabase.getInstance(this)
        scheduleManager = ScheduleManager.getInstance(this)

        setupToolbar()
        initViews()
        observeSchedules()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerViewSchedules)
        layoutEmpty = findViewById(R.id.layoutEmpty)

        adapter = ScheduleAdapter(
            onToggle = { schedule, enabled -> toggleSchedule(schedule, enabled) },
            onEdit = { schedule -> showEditDialog(schedule) },
            onDelete = { schedule -> deleteSchedule(schedule) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showAddDialog()
        }
    }

    private fun observeSchedules() {
        database.scheduleDao().getAllSchedules().observe(this) { schedules ->
            adapter.submitList(schedules)
            layoutEmpty.visibility = if (schedules.isEmpty()) View.VISIBLE else View.GONE
            recyclerView.visibility = if (schedules.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private fun showAddDialog() {
        showScheduleDialog(null)
    }

    private fun showEditDialog(schedule: Schedule) {
        showScheduleDialog(schedule)
    }

    private fun showScheduleDialog(existingSchedule: Schedule?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_schedule, null)

        val etName = dialogView.findViewById<EditText>(R.id.etScheduleName)
        val btnStartTime = dialogView.findViewById<Button>(R.id.btnStartTime)
        val btnEndTime = dialogView.findViewById<Button>(R.id.btnEndTime)
        val cbMon = dialogView.findViewById<CheckBox>(R.id.cbMon)
        val cbTue = dialogView.findViewById<CheckBox>(R.id.cbTue)
        val cbWed = dialogView.findViewById<CheckBox>(R.id.cbWed)
        val cbThu = dialogView.findViewById<CheckBox>(R.id.cbThu)
        val cbFri = dialogView.findViewById<CheckBox>(R.id.cbFri)
        val cbSat = dialogView.findViewById<CheckBox>(R.id.cbSat)
        val cbSun = dialogView.findViewById<CheckBox>(R.id.cbSun)
        val radioEnable = dialogView.findViewById<RadioButton>(R.id.radioEnable)
        val radioDisable = dialogView.findViewById<RadioButton>(R.id.radioDisable)

        var startMinutes = existingSchedule?.startTimeMinutes ?: 480  // 8:00
        var endMinutes = existingSchedule?.endTimeMinutes ?: 1080     // 18:00

        // Pré-remplir si modification
        existingSchedule?.let { schedule ->
            etName.setText(schedule.name)

            cbMon.isChecked = schedule.isDayEnabled(Schedule.MONDAY)
            cbTue.isChecked = schedule.isDayEnabled(Schedule.TUESDAY)
            cbWed.isChecked = schedule.isDayEnabled(Schedule.WEDNESDAY)
            cbThu.isChecked = schedule.isDayEnabled(Schedule.THURSDAY)
            cbFri.isChecked = schedule.isDayEnabled(Schedule.FRIDAY)
            cbSat.isChecked = schedule.isDayEnabled(Schedule.SATURDAY)
            cbSun.isChecked = schedule.isDayEnabled(Schedule.SUNDAY)

            startMinutes = schedule.startTimeMinutes
            endMinutes = schedule.endTimeMinutes

            if (schedule.enableProtection) {
                radioEnable.isChecked = true
            } else {
                radioDisable.isChecked = true
            }
        }

        // Afficher les heures
        btnStartTime.text = formatTime(startMinutes)
        btnEndTime.text = formatTime(endMinutes)

        btnStartTime.setOnClickListener {
            showTimePicker(startMinutes) { minutes ->
                startMinutes = minutes
                btnStartTime.text = formatTime(minutes)
            }
        }

        btnEndTime.setOnClickListener {
            showTimePicker(endMinutes) { minutes ->
                endMinutes = minutes
                btnEndTime.text = formatTime(minutes)
            }
        }

        val title = if (existingSchedule == null) R.string.schedule_add else R.string.schedule_edit

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""

                var daysOfWeek = 0
                if (cbMon.isChecked) daysOfWeek = daysOfWeek or Schedule.MONDAY
                if (cbTue.isChecked) daysOfWeek = daysOfWeek or Schedule.TUESDAY
                if (cbWed.isChecked) daysOfWeek = daysOfWeek or Schedule.WEDNESDAY
                if (cbThu.isChecked) daysOfWeek = daysOfWeek or Schedule.THURSDAY
                if (cbFri.isChecked) daysOfWeek = daysOfWeek or Schedule.FRIDAY
                if (cbSat.isChecked) daysOfWeek = daysOfWeek or Schedule.SATURDAY
                if (cbSun.isChecked) daysOfWeek = daysOfWeek or Schedule.SUNDAY

                if (daysOfWeek == 0) {
                    Toast.makeText(this, R.string.schedule_select_day, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val enableProtection = radioEnable.isChecked

                val schedule = Schedule(
                    id = existingSchedule?.id ?: 0,
                    name = name.ifEmpty { getString(R.string.schedule_default_name) },
                    startTimeMinutes = startMinutes,
                    endTimeMinutes = endMinutes,
                    daysOfWeek = daysOfWeek,
                    enableProtection = enableProtection,
                    isEnabled = existingSchedule?.isEnabled ?: true
                )

                saveSchedule(schedule, existingSchedule == null)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun showTimePicker(currentMinutes: Int, onTimeSelected: (Int) -> Unit) {
        val hours = currentMinutes / 60
        val minutes = currentMinutes % 60

        TimePickerDialog(this, { _, selectedHour, selectedMinute ->
            onTimeSelected(selectedHour * 60 + selectedMinute)
        }, hours, minutes, true).show()
    }

    private fun formatTime(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return String.format("%02d:%02d", h, m)
    }

    private fun saveSchedule(schedule: Schedule, isNew: Boolean) {
        lifecycleScope.launch {
            if (isNew) {
                database.scheduleDao().insert(schedule)
                Toast.makeText(this@ScheduleActivity, R.string.schedule_added, Toast.LENGTH_SHORT).show()
            } else {
                database.scheduleDao().update(schedule)
                Toast.makeText(this@ScheduleActivity, R.string.schedule_updated, Toast.LENGTH_SHORT).show()
            }
            scheduleManager.scheduleAll()
        }
    }

    private fun toggleSchedule(schedule: Schedule, enabled: Boolean) {
        lifecycleScope.launch {
            database.scheduleDao().setEnabled(schedule.id, enabled)
            scheduleManager.scheduleAll()
        }
    }

    private fun deleteSchedule(schedule: Schedule) {
        AlertDialog.Builder(this)
            .setTitle(R.string.schedule_delete_title)
            .setMessage(R.string.schedule_delete_message)
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    scheduleManager.cancelAlarms(schedule)
                    database.scheduleDao().delete(schedule)
                    Toast.makeText(this@ScheduleActivity, R.string.schedule_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    // ==================== ADAPTER ====================

    inner class ScheduleAdapter(
        private val onToggle: (Schedule, Boolean) -> Unit,
        private val onEdit: (Schedule) -> Unit,
        private val onDelete: (Schedule) -> Unit
    ) : RecyclerView.Adapter<ScheduleAdapter.ViewHolder>() {

        private var schedules = listOf<Schedule>()

        fun submitList(newList: List<Schedule>) {
            schedules = newList
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
            private val tvName: TextView = view.findViewById(R.id.tvScheduleName)
            private val tvTime: TextView = view.findViewById(R.id.tvScheduleTime)
            private val tvDays: TextView = view.findViewById(R.id.tvScheduleDays)
            private val tvAction: TextView = view.findViewById(R.id.tvScheduleAction)
            private val tvActionIcon: TextView = view.findViewById(R.id.tvActionIcon)
            private val switchEnabled: SwitchMaterial = view.findViewById(R.id.switchEnabled)
            private val btnEdit: MaterialButton = view.findViewById(R.id.btnEdit)
            private val btnDelete: MaterialButton = view.findViewById(R.id.btnDelete)

            fun bind(schedule: Schedule) {
                tvName.text = schedule.name.ifEmpty { getString(R.string.schedule_default_name) }
                tvTime.text = "${schedule.getStartTimeFormatted()} - ${schedule.getEndTimeFormatted()}"
                tvDays.text = schedule.getDaysFormatted()

                if (schedule.enableProtection) {
                    tvAction.text = getString(R.string.schedule_action_enable)
                    tvActionIcon.text = "🛡️"
                    tvAction.setTextColor(getColor(R.color.green))
                } else {
                    tvAction.text = getString(R.string.schedule_action_disable)
                    tvActionIcon.text = "⏸️"
                    tvAction.setTextColor(getColor(R.color.orange))
                }

                switchEnabled.setOnCheckedChangeListener(null)
                switchEnabled.isChecked = schedule.isEnabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggle(schedule, isChecked)
                }

                btnEdit.setOnClickListener { onEdit(schedule) }
                btnDelete.setOnClickListener { onDelete(schedule) }
            }
        }
    }
}