package fr.bonobo.dnsphere.security

import android.content.Context
import android.content.SharedPreferences
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import fr.bonobo.dnsphere.R

class BiometricHelper(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "biometric_prefs"
        private const val KEY_BIOMETRIC_ENABLED = "biometric_enabled"
        private const val KEY_PROTECT_DISABLE = "protect_disable"
        private const val KEY_PROTECT_SETTINGS = "protect_settings"
        private const val KEY_PROTECT_PROFILES = "protect_profiles"

        @Volatile
        private var INSTANCE: BiometricHelper? = null

        fun getInstance(context: Context): BiometricHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: BiometricHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Vérifie si le téléphone supporte la biométrie
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }

    /**
     * Retourne le type de biométrie disponible
     */
    fun getBiometricType(): BiometricType {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)) {
            BiometricManager.BIOMETRIC_SUCCESS -> BiometricType.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> BiometricType.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> BiometricType.HARDWARE_UNAVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> BiometricType.NOT_ENROLLED
            else -> BiometricType.UNKNOWN
        }
    }

    // ==================== PRÉFÉRENCES ====================

    var isBiometricEnabled: Boolean
        get() = prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, value).apply()

    var protectDisable: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_DISABLE, true)
        set(value) = prefs.edit().putBoolean(KEY_PROTECT_DISABLE, value).apply()

    var protectSettings: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_SETTINGS, false)
        set(value) = prefs.edit().putBoolean(KEY_PROTECT_SETTINGS, value).apply()

    var protectProfiles: Boolean
        get() = prefs.getBoolean(KEY_PROTECT_PROFILES, false)
        set(value) = prefs.edit().putBoolean(KEY_PROTECT_PROFILES, value).apply()

    /**
     * Vérifie si l'authentification est requise pour une action
     */
    fun isAuthRequired(action: ProtectedAction): Boolean {
        if (!isBiometricEnabled || !isBiometricAvailable()) return false

        return when (action) {
            ProtectedAction.DISABLE_VPN -> protectDisable
            ProtectedAction.ACCESS_SETTINGS -> protectSettings
            ProtectedAction.MODIFY_PROFILES -> protectProfiles
        }
    }

    /**
     * Affiche le prompt biométrique
     */
    fun authenticate(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val executor = ContextCompat.getMainExecutor(context)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                onSuccess()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                    errorCode == BiometricPrompt.ERROR_CANCELED) {
                    onCancel()
                } else {
                    onError(errString.toString())
                }
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                // Ne rien faire, l'utilisateur peut réessayer
            }
        }

        val biometricPrompt = BiometricPrompt(activity, executor, callback)

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(context.getString(R.string.common_cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Authentifie pour une action spécifique
     */
    fun authenticateForAction(
        activity: FragmentActivity,
        action: ProtectedAction,
        onSuccess: () -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val title = when (action) {
            ProtectedAction.DISABLE_VPN -> context.getString(R.string.bio_auth_disable_title)
            ProtectedAction.ACCESS_SETTINGS -> context.getString(R.string.bio_auth_settings_title)
            ProtectedAction.MODIFY_PROFILES -> context.getString(R.string.bio_auth_profiles_title)
        }

        val subtitle = context.getString(R.string.bio_auth_subtitle)

        authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = onSuccess,
            onError = { /* Ignorer les erreurs, l'utilisateur peut réessayer */ },
            onCancel = onCancel
        )
    }
}

enum class BiometricType {
    AVAILABLE,
    NO_HARDWARE,
    HARDWARE_UNAVAILABLE,
    NOT_ENROLLED,
    UNKNOWN
}

enum class ProtectedAction {
    DISABLE_VPN,
    ACCESS_SETTINGS,
    MODIFY_PROFILES
}