package fr.bonobo.dnsphere.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import fr.bonobo.dnsphere.R
import fr.bonobo.dnsphere.security.BiometricHelper
import fr.bonobo.dnsphere.security.BiometricType

class SecurityActivity : AppCompatActivity() {

    private lateinit var biometricHelper: BiometricHelper

    private lateinit var switchBiometricEnabled: SwitchMaterial
    private lateinit var switchProtectDisable: SwitchMaterial
    private lateinit var switchProtectSettings: SwitchMaterial
    private lateinit var switchProtectProfiles: SwitchMaterial

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_security)

        biometricHelper = BiometricHelper.getInstance(this)

        setupToolbar()
        initViews()
        checkBiometricAvailability()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }
    }

    private fun initViews() {
        switchBiometricEnabled = findViewById(R.id.switchBiometricEnabled)
        switchProtectDisable = findViewById(R.id.switchProtectDisable)
        switchProtectSettings = findViewById(R.id.switchProtectSettings)
        switchProtectProfiles = findViewById(R.id.switchProtectProfiles)
    }

    private fun checkBiometricAvailability() {
        when (biometricHelper.getBiometricType()) {
            BiometricType.NO_HARDWARE -> {
                Toast.makeText(this, R.string.bio_no_hardware, Toast.LENGTH_LONG).show()
                disableAllSwitches()
            }
            BiometricType.HARDWARE_UNAVAILABLE -> {
                Toast.makeText(this, R.string.bio_hardware_unavailable, Toast.LENGTH_LONG).show()
                disableAllSwitches()
            }
            BiometricType.NOT_ENROLLED -> {
                Toast.makeText(this, R.string.bio_not_enrolled, Toast.LENGTH_LONG).show()
                disableAllSwitches()
            }
            BiometricType.AVAILABLE -> {
                // Biométrie disponible, tout est ok
            }
            BiometricType.UNKNOWN -> {
                disableAllSwitches()
            }
        }
    }

    private fun disableAllSwitches() {
        switchBiometricEnabled.isEnabled = false
        switchProtectDisable.isEnabled = false
        switchProtectSettings.isEnabled = false
        switchProtectProfiles.isEnabled = false
    }

    private fun loadSettings() {
        switchBiometricEnabled.isChecked = biometricHelper.isBiometricEnabled
        switchProtectDisable.isChecked = biometricHelper.protectDisable
        switchProtectSettings.isChecked = biometricHelper.protectSettings
        switchProtectProfiles.isChecked = biometricHelper.protectProfiles

        updateProtectionSwitchesState()
    }

    private fun updateProtectionSwitchesState() {
        val enabled = switchBiometricEnabled.isChecked && biometricHelper.isBiometricAvailable()
        switchProtectDisable.isEnabled = enabled
        switchProtectSettings.isEnabled = enabled
        switchProtectProfiles.isEnabled = enabled

        if (!enabled) {
            switchProtectDisable.alpha = 0.5f
            switchProtectSettings.alpha = 0.5f
            switchProtectProfiles.alpha = 0.5f
        } else {
            switchProtectDisable.alpha = 1.0f
            switchProtectSettings.alpha = 1.0f
            switchProtectProfiles.alpha = 1.0f
        }
    }

    private fun setupListeners() {
        switchBiometricEnabled.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Demander authentification pour activer
                biometricHelper.authenticate(
                    activity = this,
                    title = getString(R.string.bio_enable_title),
                    subtitle = getString(R.string.bio_enable_subtitle),
                    onSuccess = {
                        biometricHelper.isBiometricEnabled = true
                        updateProtectionSwitchesState()
                        Toast.makeText(this, R.string.bio_enabled, Toast.LENGTH_SHORT).show()
                    },
                    onError = {
                        switchBiometricEnabled.isChecked = false
                    },
                    onCancel = {
                        switchBiometricEnabled.isChecked = false
                    }
                )
            } else {
                // Demander authentification pour désactiver
                biometricHelper.authenticate(
                    activity = this,
                    title = getString(R.string.bio_disable_title),
                    subtitle = getString(R.string.bio_disable_subtitle),
                    onSuccess = {
                        biometricHelper.isBiometricEnabled = false
                        updateProtectionSwitchesState()
                        Toast.makeText(this, R.string.bio_disabled, Toast.LENGTH_SHORT).show()
                    },
                    onError = {
                        switchBiometricEnabled.isChecked = true
                    },
                    onCancel = {
                        switchBiometricEnabled.isChecked = true
                    }
                )
            }
        }

        switchProtectDisable.setOnCheckedChangeListener { _, isChecked ->
            biometricHelper.protectDisable = isChecked
        }

        switchProtectSettings.setOnCheckedChangeListener { _, isChecked ->
            biometricHelper.protectSettings = isChecked
        }

        switchProtectProfiles.setOnCheckedChangeListener { _, isChecked ->
            biometricHelper.protectProfiles = isChecked
        }
    }
}