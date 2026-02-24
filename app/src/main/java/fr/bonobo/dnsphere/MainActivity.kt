package fr.bonobo.dnsphere

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.ui.LogsActivity
import fr.bonobo.dnsphere.ui.SettingsActivity
import fr.bonobo.dnsphere.ui.WhitelistActivity
import fr.bonobo.dnsphere.widget.DnsphereWidget

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvAdsBlocked: TextView
    private lateinit var tvTrackersBlocked: TextView
    private lateinit var switchAds: SwitchMaterial
    private lateinit var switchTrackers: SwitchMaterial
    private lateinit var switchMalware: SwitchMaterial

    private lateinit var database: AppDatabase
    private var isVpnRunning = false

    private val statsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val adsBlocked = it.getIntExtra("ads_blocked", 0)
                val trackersBlocked = it.getIntExtra("trackers_blocked", 0)
                runOnUiThread {
                    tvAdsBlocked.text = adsBlocked.toString()
                    tvTrackersBlocked.text = trackersBlocked.toString()
                    saveStatsForWidget(adsBlocked, trackersBlocked)
                }
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            Toast.makeText(this, "Permission VPN refusée", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Cacher l'ActionBar pour un look plus moderne
        supportActionBar?.hide()

        database = AppDatabase.getInstance(this)

        initViews()
        setupListeners()
        loadPreferences()

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
        switchAds = findViewById(R.id.switchAds)
        switchTrackers = findViewById(R.id.switchTrackers)
        switchMalware = findViewById(R.id.switchMalware)
    }

    private fun setupListeners() {
        // Bouton principal ON/OFF
        btnToggle.setOnClickListener {
            if (isVpnRunning) {
                stopVpnService()
            } else {
                requestVpnPermission()
            }
        }

        // Switches
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

        // Card Stats -> Logs
        findViewById<View>(R.id.cardStats).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        // Boutons d'action rapide
        findViewById<MaterialButton>(R.id.btnLogs).setOnClickListener {
            startActivity(Intent(this, LogsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnWhitelist).setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
        }
        ContextCompat.startForegroundService(this, intent)
        updateUI(true)
        DnsphereWidget.updateWidget(this)
    }

    private fun stopVpnService() {
        val intent = Intent(this, LocalVpnService::class.java).apply {
            action = LocalVpnService.ACTION_STOP
        }
        startService(intent)
        updateUI(false)
        DnsphereWidget.updateWidget(this)
    }

    private fun updateUI(running: Boolean) {
        isVpnRunning = running

        if (running) {
            btnToggle.text = "ON"
            btnToggle.setBackgroundColor(getColor(R.color.green))
            tvStatus.text = "🛡️ Protection activée"
            tvStatus.setTextColor(getColor(R.color.green))
        } else {
            btnToggle.text = "OFF"
            btnToggle.setBackgroundColor(getColor(R.color.red))
            tvStatus.text = "Protection désactivée"
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

    private fun saveStatsForWidget(ads: Int, trackers: Int) {
        getSharedPreferences("dnsphere_stats", MODE_PRIVATE)
            .edit()
            .putInt("ads_blocked", ads)
            .putInt("trackers_blocked", trackers)
            .apply()
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(statsReceiver, IntentFilter("vpn_stats"))

        isVpnRunning = LocalVpnService.isRunning
        updateUI(isVpnRunning)
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(statsReceiver)
    }
}