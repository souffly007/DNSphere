package fr.bonobo.dnsphere.ui

import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import fr.bonobo.dnsphere.LocalVpnService
import fr.bonobo.dnsphere.ParentalManager
import fr.bonobo.dnsphere.R
import kotlinx.coroutines.launch

class ParentalControlFragment : Fragment() {

    private lateinit var parentalManager: ParentalManager

    // Views
    private lateinit var switchEnabled: Switch
    private lateinit var layoutConfig: LinearLayout

    // Catégories
    private lateinit var switchAdult: Switch
    private lateinit var switchGaming: Switch
    private lateinit var switchSocial: Switch
    private lateinit var switchStreaming: Switch
    private lateinit var switchForums: Switch

    // Plage horaire
    private lateinit var switchSchedule: Switch
    private lateinit var layoutSchedule: LinearLayout
    private lateinit var npStartHour: NumberPicker
    private lateinit var npStartMinute: NumberPicker
    private lateinit var npEndHour: NumberPicker
    private lateinit var npEndMinute: NumberPicker

    // Jours
    private lateinit var cbMonday: CheckBox
    private lateinit var cbTuesday: CheckBox
    private lateinit var cbWednesday: CheckBox
    private lateinit var cbThursday: CheckBox
    private lateinit var cbFriday: CheckBox
    private lateinit var cbSaturday: CheckBox
    private lateinit var cbSunday: CheckBox

    private lateinit var btnSave: Button

    // =========================================================================
    // FLAG ANTI-BOUCLE — empêche le listener de se déclencher
    // quand on change isChecked programmatiquement
    // =========================================================================
    private var isUpdatingSwitch = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_parental_control, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        parentalManager = ParentalManager(requireContext())
        bindViews(view)
        setupNumberPickers()
        loadCurrentConfig()
        setupListeners()
    }

    private fun bindViews(view: View) {
        switchEnabled   = view.findViewById(R.id.switchParentalEnabled)
        layoutConfig    = view.findViewById(R.id.layoutParentalConfig)
        switchAdult     = view.findViewById(R.id.switchBlockAdult)
        switchGaming    = view.findViewById(R.id.switchBlockGaming)
        switchSocial    = view.findViewById(R.id.switchBlockSocial)
        switchStreaming  = view.findViewById(R.id.switchBlockStreaming)
        switchForums    = view.findViewById(R.id.switchBlockForums)
        switchSchedule  = view.findViewById(R.id.switchScheduleEnabled)
        layoutSchedule  = view.findViewById(R.id.layoutSchedule)
        npStartHour     = view.findViewById(R.id.npStartHour)
        npStartMinute   = view.findViewById(R.id.npStartMinute)
        npEndHour       = view.findViewById(R.id.npEndHour)
        npEndMinute     = view.findViewById(R.id.npEndMinute)
        cbMonday        = view.findViewById(R.id.cbMonday)
        cbTuesday       = view.findViewById(R.id.cbTuesday)
        cbWednesday     = view.findViewById(R.id.cbWednesday)
        cbThursday      = view.findViewById(R.id.cbThursday)
        cbFriday        = view.findViewById(R.id.cbFriday)
        cbSaturday      = view.findViewById(R.id.cbSaturday)
        cbSunday        = view.findViewById(R.id.cbSunday)
        btnSave         = view.findViewById(R.id.btnSaveParental)
    }

    private fun setupNumberPickers() {
        npStartHour.minValue   = 0;  npStartHour.maxValue   = 23
        npEndHour.minValue     = 0;  npEndHour.maxValue     = 23
        npStartMinute.minValue = 0;  npStartMinute.maxValue = 59
        npEndMinute.minValue   = 0;  npEndMinute.maxValue   = 59
        val minutes = Array(60) { "%02d".format(it) }
        npStartMinute.displayedValues = minutes
        npEndMinute.displayedValues   = minutes
    }

    private fun loadCurrentConfig() {
        val cfg = parentalManager.getConfig()

        // Mettre à jour le switch sans déclencher le listener
        setSwitchSilently(switchEnabled, cfg.pinEnabled)
        layoutConfig.visibility = if (cfg.pinEnabled) View.VISIBLE else View.GONE

        switchAdult.isChecked     = cfg.blockAdult
        switchGaming.isChecked    = cfg.blockGaming
        switchSocial.isChecked    = cfg.blockSocialMedia
        switchStreaming.isChecked = cfg.blockStreaming
        switchForums.isChecked    = cfg.blockForums

        switchSchedule.isChecked  = cfg.scheduleEnabled
        layoutSchedule.visibility = if (cfg.scheduleEnabled) View.VISIBLE else View.GONE

        npStartHour.value   = cfg.allowedStartHour
        npStartMinute.value = cfg.allowedStartMinute
        npEndHour.value     = cfg.allowedEndHour
        npEndMinute.value   = cfg.allowedEndMinute

        cbMonday.isChecked    = cfg.activeDays and 1  != 0
        cbTuesday.isChecked   = cfg.activeDays and 2  != 0
        cbWednesday.isChecked = cfg.activeDays and 4  != 0
        cbThursday.isChecked  = cfg.activeDays and 8  != 0
        cbFriday.isChecked    = cfg.activeDays and 16 != 0
        cbSaturday.isChecked  = cfg.activeDays and 32 != 0
        cbSunday.isChecked    = cfg.activeDays and 64 != 0
    }

    // =========================================================================
    // Change isChecked sans déclencher le OnCheckedChangeListener
    // =========================================================================
    private fun setSwitchSilently(switch: Switch, checked: Boolean) {
        isUpdatingSwitch = true
        switch.isChecked = checked
        isUpdatingSwitch = false
    }

    private fun setupListeners() {

        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            // Ignorer si c'est un changement programmatique
            if (isUpdatingSwitch) return@setOnCheckedChangeListener

            if (isChecked) {
                showSetPinDialog()
            } else {
                showDisableDialog()
            }
        }

        switchSchedule.setOnCheckedChangeListener { _, isChecked ->
            layoutSchedule.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnSave.setOnClickListener {
            if (parentalManager.isPinEnabled()) {
                showPinConfirmDialog { saveConfig() }
            } else {
                saveConfig()
            }
        }
    }

    // =========================================================================
    // SAUVEGARDE
    // =========================================================================

    private fun saveConfig() {
        val current = parentalManager.getConfig()
        val newConfig = current.copy(
            blockAdult         = switchAdult.isChecked,
            blockGaming        = switchGaming.isChecked,
            blockSocialMedia   = switchSocial.isChecked,
            blockStreaming     = switchStreaming.isChecked,
            blockForums        = switchForums.isChecked,
            scheduleEnabled    = switchSchedule.isChecked,
            allowedStartHour   = npStartHour.value,
            allowedStartMinute = npStartMinute.value,
            allowedEndHour     = npEndHour.value,
            allowedEndMinute   = npEndMinute.value,
            activeDays         = buildActiveDays()
        )
        lifecycleScope.launch {
            parentalManager.saveConfig(newConfig)
            notifyVpnReload()
            Toast.makeText(requireContext(), "✅ Contrôle parental mis à jour", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildActiveDays(): Int {
        var days = 0
        if (cbMonday.isChecked)    days = days or 1
        if (cbTuesday.isChecked)   days = days or 2
        if (cbWednesday.isChecked) days = days or 4
        if (cbThursday.isChecked)  days = days or 8
        if (cbFriday.isChecked)    days = days or 16
        if (cbSaturday.isChecked)  days = days or 32
        if (cbSunday.isChecked)    days = days or 64
        return days
    }

    private fun notifyVpnReload() {
        val intent = android.content.Intent(LocalVpnService.ACTION_UPDATE_CONFIG)
        requireContext().sendBroadcast(intent)
    }

    // =========================================================================
    // DIALOGS PIN
    // =========================================================================

    private fun showSetPinDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint      = "4 à 6 chiffres"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("🔒 Définir un code PIN")
            .setMessage("Ce code protégera l'accès au contrôle parental.")
            .setView(input)
            .setPositiveButton("Valider") { _, _ ->
                val pin = input.text.toString()
                if (pin.length < 4) {
                    Toast.makeText(requireContext(), "PIN trop court (min 4 chiffres)", Toast.LENGTH_SHORT).show()
                    setSwitchSilently(switchEnabled, false)  // ← sans déclencher le listener
                } else {
                    showConfirmPinDialog(pin)
                }
            }
            .setNegativeButton("Annuler") { _, _ ->
                setSwitchSilently(switchEnabled, false)      // ← sans déclencher le listener
            }
            .setCancelable(false)
            .show()
    }

    private fun showConfirmPinDialog(pin: String) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint      = "Confirmez le PIN"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("🔒 Confirmer le PIN")
            .setView(input)
            .setPositiveButton("Confirmer") { _, _ ->
                if (input.text.toString() == pin) {
                    lifecycleScope.launch {
                        parentalManager.enableWithPin(pin, parentalManager.getConfig())
                        layoutConfig.visibility = View.VISIBLE
                        Toast.makeText(requireContext(), "✅ PIN défini, contrôle parental activé", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(requireContext(), "❌ Les PINs ne correspondent pas", Toast.LENGTH_SHORT).show()
                    setSwitchSilently(switchEnabled, false)  // ← sans déclencher le listener
                }
            }
            .setNegativeButton("Annuler") { _, _ ->
                setSwitchSilently(switchEnabled, false)      // ← sans déclencher le listener
            }
            .setCancelable(false)
            .show()
    }

    private fun showDisableDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint      = "Entrez votre PIN"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("🔓 Désactiver le contrôle parental")
            .setMessage("Entrez votre PIN pour confirmer.")
            .setView(input)
            .setPositiveButton("Confirmer") { _, _ ->
                lifecycleScope.launch {
                    val ok = parentalManager.disable(input.text.toString())
                    if (ok) {
                        layoutConfig.visibility = View.GONE
                        Toast.makeText(requireContext(), "Contrôle parental désactivé", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "❌ PIN incorrect", Toast.LENGTH_SHORT).show()
                        setSwitchSilently(switchEnabled, true)   // ← sans déclencher le listener
                    }
                }
            }
            .setNegativeButton("Annuler") { _, _ ->
                setSwitchSilently(switchEnabled, true)           // ← sans déclencher le listener
            }
            .setCancelable(false)
            .show()
    }

    private fun showPinConfirmDialog(onSuccess: () -> Unit) {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint      = "Entrez votre PIN"
        }

        AlertDialog.Builder(requireContext())
            .setTitle("🔒 PIN requis")
            .setMessage("Entrez votre PIN pour modifier la configuration.")
            .setView(input)
            .setPositiveButton("Valider") { _, _ ->
                if (parentalManager.checkPin(input.text.toString())) {
                    onSuccess()
                } else {
                    Toast.makeText(requireContext(), "❌ PIN incorrect", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }
}