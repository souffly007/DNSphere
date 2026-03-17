package fr.bonobo.dnsphere.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.Profile
import kotlinx.coroutines.launch

class ProfilesActivity : AppCompatActivity() {

    private lateinit var database: AppDatabase
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProfileAdapter

    // Active profile card
    private lateinit var cardActiveProfile: MaterialCardView
    private lateinit var tvActiveIcon: TextView
    private lateinit var tvActiveName: TextView
    private lateinit var tvActiveDescription: TextView

    private var selectedIcon = "🛡️"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profiles)

        database = AppDatabase.getInstance(this)

        setupToolbar()
        initViews()
        initDefaultProfilesIfNeeded()
        observeProfiles()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        cardActiveProfile = findViewById(R.id.cardActiveProfile)
        tvActiveIcon = findViewById(R.id.tvActiveIcon)
        tvActiveName = findViewById(R.id.tvActiveName)
        tvActiveDescription = findViewById(R.id.tvActiveDescription)

        recyclerView = findViewById(R.id.recyclerViewProfiles)
        adapter = ProfileAdapter(
            onProfileClick = { profile -> activateProfile(profile) },
            onMenuClick = { profile, view -> showProfileMenu(profile, view) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        findViewById<FloatingActionButton>(R.id.fabAdd).setOnClickListener {
            showAddDialog()
        }
    }

    private fun initDefaultProfilesIfNeeded() {
        lifecycleScope.launch {
            val count = database.profileDao().getCount()
            if (count == 0) {
                database.profileDao().insertAll(Profile.getDefaultProfiles())
            }
        }
    }

    private fun observeProfiles() {
        // Observer le profil actif
        database.profileDao().getActiveProfile().observe(this) { profile ->
            profile?.let { updateActiveProfileCard(it) }
        }

        // Observer tous les profils
        database.profileDao().getAllProfiles().observe(this) { profiles ->
            adapter.submitList(profiles)
        }
    }

    private fun updateActiveProfileCard(profile: Profile) {
        tvActiveIcon.text = profile.icon
        tvActiveName.text = profile.name
        tvActiveDescription.text = profile.getBlockingDescription()
    }

    private fun activateProfile(profile: Profile) {
        lifecycleScope.launch {
            database.profileDao().setActiveProfile(profile.id)
            Toast.makeText(
                this@ProfilesActivity,
                getString(R.string.profile_activated, profile.name),
                Toast.LENGTH_SHORT
            ).show()

            // Mettre à jour le service VPN si actif
            updateVpnService(profile)
        }
    }

    private fun updateVpnService(profile: Profile) {
        if (LocalVpnService.isRunning) {
            val intent = Intent(this, LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_UPDATE_CONFIG
                putExtra("block_ads", profile.blockAds)
                putExtra("block_trackers", profile.blockTrackers)
                putExtra("block_malware", profile.blockMalware)
                putExtra("block_social", profile.blockSocial)
                putExtra("block_adult", profile.blockAdult)
                putExtra("block_gambling", profile.blockGambling)
            }
            startService(intent)
        }

        // Sauvegarder aussi dans les prefs pour la prochaine fois
        getSharedPreferences("vpn_prefs", MODE_PRIVATE).edit().apply {
            putBoolean("block_ads", profile.blockAds)
            putBoolean("block_trackers", profile.blockTrackers)
            putBoolean("block_malware", profile.blockMalware)
            putBoolean("block_social", profile.blockSocial)
            putBoolean("block_adult", profile.blockAdult)
            putBoolean("block_gambling", profile.blockGambling)
            apply()
        }
    }

    private fun showProfileMenu(profile: Profile, anchor: View) {
        val popup = PopupMenu(this, anchor)

        if (!profile.isActive) {
            popup.menu.add(0, 1, 0, R.string.profile_activate)
        }

        if (!profile.isPreset) {
            popup.menu.add(0, 2, 0, R.string.common_edit)
            popup.menu.add(0, 3, 0, R.string.common_delete)
        } else {
            popup.menu.add(0, 4, 0, R.string.profile_duplicate)
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> activateProfile(profile)
                2 -> showEditDialog(profile)
                3 -> deleteProfile(profile)
                4 -> duplicateProfile(profile)
            }
            true
        }
        popup.show()
    }

    private fun showAddDialog() {
        showProfileDialog(null)
    }

    private fun showEditDialog(profile: Profile) {
        showProfileDialog(profile)
    }

    private fun showProfileDialog(existingProfile: Profile?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_profile, null)

        val etName = dialogView.findViewById<EditText>(R.id.etProfileName)
        val btnIcon1 = dialogView.findViewById<Button>(R.id.btnIcon1)
        val btnIcon2 = dialogView.findViewById<Button>(R.id.btnIcon2)
        val btnIcon3 = dialogView.findViewById<Button>(R.id.btnIcon3)
        val btnIcon4 = dialogView.findViewById<Button>(R.id.btnIcon4)
        val btnIcon5 = dialogView.findViewById<Button>(R.id.btnIcon5)
        val cbAds = dialogView.findViewById<CheckBox>(R.id.cbAds)
        val cbTrackers = dialogView.findViewById<CheckBox>(R.id.cbTrackers)
        val cbMalware = dialogView.findViewById<CheckBox>(R.id.cbMalware)
        val cbSocial = dialogView.findViewById<CheckBox>(R.id.cbSocial)
        val cbAdult = dialogView.findViewById<CheckBox>(R.id.cbAdult)
        val cbGambling = dialogView.findViewById<CheckBox>(R.id.cbGambling)

        selectedIcon = existingProfile?.icon ?: "🛡️"

        // Pré-remplir si modification
        existingProfile?.let { profile ->
            etName.setText(profile.name)
            cbAds.isChecked = profile.blockAds
            cbTrackers.isChecked = profile.blockTrackers
            cbMalware.isChecked = profile.blockMalware
            cbSocial.isChecked = profile.blockSocial
            cbAdult.isChecked = profile.blockAdult
            cbGambling.isChecked = profile.blockGambling
        }

        // Sélection d'icône
        val iconButtons = listOf(btnIcon1, btnIcon2, btnIcon3, btnIcon4, btnIcon5)
        val icons = listOf("🛡️", "💼", "🏠", "🎮", "🔒")

        iconButtons.forEachIndexed { index, button ->
            button.setOnClickListener {
                selectedIcon = icons[index]
                iconButtons.forEach { it.alpha = 0.5f }
                button.alpha = 1.0f
            }
        }

        // Mettre en évidence l'icône actuelle
        val currentIndex = icons.indexOf(selectedIcon)
        if (currentIndex >= 0) {
            iconButtons.forEach { it.alpha = 0.5f }
            iconButtons[currentIndex].alpha = 1.0f
        }

        val title = if (existingProfile == null) R.string.profile_add else R.string.profile_edit

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(dialogView)
            .setPositiveButton(R.string.common_save) { _, _ ->
                val name = etName.text?.toString()?.trim() ?: ""

                if (name.isEmpty()) {
                    Toast.makeText(this, R.string.profile_name_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val profile = Profile(
                    id = existingProfile?.id ?: 0,
                    name = name,
                    icon = selectedIcon,
                    blockAds = cbAds.isChecked,
                    blockTrackers = cbTrackers.isChecked,
                    blockMalware = cbMalware.isChecked,
                    blockSocial = cbSocial.isChecked,
                    blockAdult = cbAdult.isChecked,
                    blockGambling = cbGambling.isChecked,
                    isPreset = false,
                    isActive = existingProfile?.isActive ?: false
                )

                saveProfile(profile, existingProfile == null)
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun saveProfile(profile: Profile, isNew: Boolean) {
        lifecycleScope.launch {
            if (isNew) {
                database.profileDao().insert(profile)
                Toast.makeText(this@ProfilesActivity, R.string.profile_added, Toast.LENGTH_SHORT).show()
            } else {
                database.profileDao().update(profile)
                Toast.makeText(this@ProfilesActivity, R.string.profile_updated, Toast.LENGTH_SHORT).show()

                // Si c'est le profil actif, mettre à jour le VPN
                if (profile.isActive) {
                    updateVpnService(profile)
                }
            }
        }
    }

    private fun deleteProfile(profile: Profile) {
        if (profile.isPreset) {
            Toast.makeText(this, R.string.profile_cannot_delete_preset, Toast.LENGTH_SHORT).show()
            return
        }

        if (profile.isActive) {
            Toast.makeText(this, R.string.profile_cannot_delete_active, Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.profile_delete_title)
            .setMessage(getString(R.string.profile_delete_message, profile.name))
            .setPositiveButton(R.string.common_delete) { _, _ ->
                lifecycleScope.launch {
                    database.profileDao().delete(profile)
                    Toast.makeText(this@ProfilesActivity, R.string.profile_deleted, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    private fun duplicateProfile(profile: Profile) {
        val newProfile = profile.copy(
            id = 0,
            name = "${profile.name} (copie)",
            isPreset = false,
            isActive = false
        )

        lifecycleScope.launch {
            database.profileDao().insert(newProfile)
            Toast.makeText(this@ProfilesActivity, R.string.profile_duplicated, Toast.LENGTH_SHORT).show()
        }
    }

    // ==================== ADAPTER ====================

    inner class ProfileAdapter(
        private val onProfileClick: (Profile) -> Unit,
        private val onMenuClick: (Profile, View) -> Unit
    ) : RecyclerView.Adapter<ProfileAdapter.ViewHolder>() {

        private var profiles = listOf<Profile>()

        fun submitList(newList: List<Profile>) {
            profiles = newList
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_profile, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(profiles[position])
        }

        override fun getItemCount() = profiles.size

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvIcon: TextView = view.findViewById(R.id.tvIcon)
            private val tvName: TextView = view.findViewById(R.id.tvName)
            private val tvDescription: TextView = view.findViewById(R.id.tvDescription)
            private val tvActiveBadge: TextView = view.findViewById(R.id.tvActiveBadge)
            private val btnMenu: ImageButton = view.findViewById(R.id.btnMenu)

            // Indicateurs
            private val indicatorAds: TextView = view.findViewById(R.id.indicatorAds)
            private val indicatorTrackers: TextView = view.findViewById(R.id.indicatorTrackers)
            private val indicatorMalware: TextView = view.findViewById(R.id.indicatorMalware)
            private val indicatorSocial: TextView = view.findViewById(R.id.indicatorSocial)
            private val indicatorAdult: TextView = view.findViewById(R.id.indicatorAdult)
            private val indicatorGambling: TextView = view.findViewById(R.id.indicatorGambling)

            fun bind(profile: Profile) {
                tvIcon.text = profile.icon
                tvName.text = profile.name
                tvDescription.text = profile.getBlockingDescription()

                // Badge actif
                tvActiveBadge.visibility = if (profile.isActive) View.VISIBLE else View.GONE

                // Indicateurs de blocage
                indicatorAds.visibility = if (profile.blockAds) View.VISIBLE else View.GONE
                indicatorTrackers.visibility = if (profile.blockTrackers) View.VISIBLE else View.GONE
                indicatorMalware.visibility = if (profile.blockMalware) View.VISIBLE else View.GONE
                indicatorSocial.visibility = if (profile.blockSocial) View.VISIBLE else View.GONE
                indicatorAdult.visibility = if (profile.blockAdult) View.VISIBLE else View.GONE
                indicatorGambling.visibility = if (profile.blockGambling) View.VISIBLE else View.GONE

                itemView.setOnClickListener { onProfileClick(profile) }
                btnMenu.setOnClickListener { onMenuClick(profile, it) }
            }
        }
    }
}