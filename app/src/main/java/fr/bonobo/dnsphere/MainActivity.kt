package fr.bonobo.dnsphere

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.security.BiometricHelper
import fr.bonobo.dnsphere.security.ProtectedAction
import fr.bonobo.dnsphere.ui.LogsActivity
import fr.bonobo.dnsphere.ui.SettingsActivity
import fr.bonobo.dnsphere.ui.WhitelistActivity
import fr.bonobo.dnsphere.widget.DnsphereWidget

class MainActivity : AppCompatActivity() {

    companion object {
        const val GITHUB_URL = "https://github.com/souffly007/DNSphere"
        const val DEVELOPER_URL = "https://github.com/souffly007"
        const val DEVELOPER_NAME = "souffly007"
    }

    private lateinit var btnToggle: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvAdsBlocked: TextView
    private lateinit var tvTrackersBlocked: TextView
    private lateinit var tvCreatedBy: TextView
    private lateinit var switchAds: SwitchMaterial
    private lateinit var switchTrackers: SwitchMaterial
    private lateinit var switchMalware: SwitchMaterial

    private lateinit var database: AppDatabase
    private lateinit var biometricHelper: BiometricHelper
    private var isVpnRunning = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        supportActionBar?.hide()

        database = AppDatabase.getInstance(this)
        biometricHelper = BiometricHelper.getInstance(this)

        initViews()
        setupListeners()
        loadPreferences()
        loadCurrentStats()
        observeStats() // ✅ NOUVEAU

        if (intent.getBooleanExtra("auto_start", false) && !LocalVpnService.isRunning) {
            requestVpnPermission()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvAdsBlocked = findViewById(R.id.tvAdsBlocked)
        tvTrackersBlocked = findViewById(R.id.tvTrackersBlocked)
        tvCreatedBy = findViewById(R.id.tvCreatedBy)
        switchAds = findViewById(R.id.switchAds)
        switchTrackers = findViewById(R.id.switchTrackers)
        switchMalware = findViewById(R.id.switchMalware)

        tvCreatedBy.text = getString(R.string.created_by, DEVELOPER_NAME)
    }

    // ✅ NOUVEAU : Observer les stats via LiveData
    private fun observeStats() {
        StatsLiveData.stats.observe(this) { stats ->
            tvAdsBlocked.text = stats.adsBlocked.toString()
            tvTrackersBlocked.text = stats.trackersBlocked.toString()
            saveStatsForWidget(stats.adsBlocked, stats.trackersBlocked)

            Log.d("MainActivity", "📊 Stats reçues: Pubs=${stats.adsBlocked}, Trackers=${stats.trackersBlocked}")
        }
    }

    private fun setupListeners() {
        btnToggle.setOnClickListener {
            if (isVpnRunning) {
                stopVpnService()
            } else {
                requestVpnPermission()
            }
        }

        switchAds.setOnCheckedChangeListener { _, isChecked ->
            savePreference("block_ads", isChecked)
            updateServiceConfig()
        }

        switchTrackers.setOnCheckedChangeListener { _, isChecked ->
            savePreference("block_trackers", isChecked)
            updateServiceConfig()
        }

        switchMalware.setOnCheckedChangeListener { _, isChecked ->
            savePreference("block_malware", isChecked)
            updateServiceConfig()
        }

        findViewById<View>(R.id.cardStats).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnLogs).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnWhitelist).setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            openSettings()
        }

        findViewById<TextView>(R.id.tvGitHub)?.setOnClickListener {
            openUrl(GITHUB_URL)
        }

        tvCreatedBy.setOnClickListener {
            openUrl(DEVELOPER_URL)
        }
    }

    private fun openSettings() {
        if (biometricHelper.isAuthRequired(ProtectedAction.ACCESS_SETTINGS)) {
            biometricHelper.authenticateForAction(
                activity = this,
                action = ProtectedAction.ACCESS_SETTINGS,
                onSuccess = {
                    startActivity(Intent(this, SettingsActivity::class.java))
                },
                onCancel = { }
            )
        } else {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.cannot_open_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestVpnPermission() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)

        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_START
            putExtra("block_ads", prefs.getBoolean("block_ads", true))
            putExtra("block_trackers", prefs.getBoolean("block_trackers", true))
            putExtra("block_malware", prefs.getBoolean("block_malware", true))
            putExtra("block_social", prefs.getBoolean("block_social", false))
            putExtra("block_adult", prefs.getBoolean("block_adult", false))
            putExtra("block_gambling", prefs.getBoolean("block_gambling", false))
            putExtra("use_doh", prefs.getBoolean("use_doh", false))
            putExtra("doh_provider", prefs.getString("doh_provider", "cloudflare"))
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI(true)
        DnsphereWidget.updateWidget(this)

        Toast.makeText(this, R.string.protection_started, Toast.LENGTH_SHORT).show()
    }

    private fun stopVpnService() {
        if (biometricHelper.isAuthRequired(ProtectedAction.DISABLE_VPN)) {
            biometricHelper.authenticateForAction(
                activity = this,
                action = ProtectedAction.DISABLE_VPN,
                onSuccess = {
                    doStopVpnService()
                },
                onCancel = { }
            )
        } else {
            doStopVpnService()
        }
    }

    private fun doStopVpnService() {
        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_STOP
        }
        startService(intent)
        updateUI(false)
        DnsphereWidget.updateWidget(this)

        Toast.makeText(this, R.string.protection_stopped, Toast.LENGTH_SHORT).show()
    }

    private fun updateUI(running: Boolean) {
        isVpnRunning = running

        if (running) {
            btnToggle.text = getString(R.string.status_on)
            btnToggle.setBackgroundColor(getColor(R.color.green))
            tvStatus.text = getString(R.string.protection_enabled)
            tvStatus.setTextColor(getColor(R.color.green))
        } else {
            btnToggle.text = getString(R.string.status_off)
            btnToggle.setBackgroundColor(getColor(R.color.red))
            tvStatus.text = getString(R.string.protection_disabled)
            tvStatus.setTextColor(getColor(R.color.gray))
        }
    }

    private fun updateServiceConfig() {
        if (isVpnRunning) {
            val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            val intent = Intent(this, LocalVpnService::class.java).apply {
                action = LocalVpnService.ACTION_UPDATE_CONFIG
                putExtra("block_ads", prefs.getBoolean("block_ads", true))
                putExtra("block_trackers", prefs.getBoolean("block_trackers", true))
                putExtra("block_malware", prefs.getBoolean("block_malware", true))
                putExtra("block_social", prefs.getBoolean("block_social", false))
                putExtra("block_adult", prefs.getBoolean("block_adult", false))
                putExtra("block_gambling", prefs.getBoolean("block_gambling", false))
                putExtra("use_doh", prefs.getBoolean("use_doh", false))
                putExtra("doh_provider", prefs.getString("doh_provider", "cloudflare"))
            }
            startService(intent)
        }
    }

    private fun savePreference(key: String, value: Boolean) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean(key, value)
            .apply()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        switchAds.isChecked = prefs.getBoolean("block_ads", true)
        switchTrackers.isChecked = prefs.getBoolean("block_trackers", true)
        switchMalware.isChecked = prefs.getBoolean("block_malware", true)
    }

    private fun loadCurrentStats() {
        val prefs = getSharedPreferences("dnsphere_stats", MODE_PRIVATE)
        val adsBlocked = prefs.getInt("ads_blocked", 0)
        val trackersBlocked = prefs.getInt("trackers_blocked", 0)

        tvAdsBlocked.text = adsBlocked.toString()
        tvTrackersBlocked.text = trackersBlocked.toString()

        Log.d("MainActivity", "📊 Stats chargées: Pubs=$adsBlocked, Trackers=$trackersBlocked")
    }

    private fun saveStatsForWidget(ads: Int, trackers: Int) {
        getSharedPreferences("dnsphere_stats", MODE_PRIVATE)
            .edit()
            .putInt("ads_blocked", ads)
            .putInt("trackers_blocked", trackers)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        loadCurrentStats()
        isVpnRunning = LocalVpnService.isRunning
        updateUI(isVpnRunning)
        loadPreferences()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let {
            if (it.getBooleanExtra("auto_start", false) && !LocalVpnService.isRunning) {
                requestVpnPermission()
            }
        }
    }
}