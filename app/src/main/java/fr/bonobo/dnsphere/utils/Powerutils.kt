// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.utils

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

/**
 * Utilitaires pour contourner les restrictions agressives de gestion mémoire/batterie
 * des ROM constructeurs (MIUI/HyperOS en particulier), qui peuvent tuer LocalVpnService
 * en arrière-plan malgré son statut de foreground service.
 *
 * Cas observé (Poco X7 Pro / HyperOS) : l'ouverture de l'appareil photo déclenche
 * le gestionnaire mémoire MIUI, qui tue DNSphere s'il n'est pas dans les exceptions
 * "Démarrage automatique" / "Sans restriction batterie".
 */
object PowerUtils {

    private const val TAG = "PowerUtils"

    /**
     * Détecte MIUI ou HyperOS. Pas d'API officielle Android pour ça —
     * on lit les propriétés système exposées par ces ROM.
     */
    fun isMiuiOrHyperOs(): Boolean {
        return getSystemProperty("ro.miui.ui.version.name").isNotBlank() ||
                getSystemProperty("ro.mi.os.version.name").isNotBlank() ||
                Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)
    }

    fun isHyperOs(): Boolean = getSystemProperty("ro.mi.os.version.name").isNotBlank()

    private fun getSystemProperty(key: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", key))
            process.inputStream.bufferedReader().readLine()?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ouvre directement le dialogue système pour exempter l'app de l'optimisation
     * batterie. API AOSP standard, fonctionne sur Android stock ET MIUI/HyperOS.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppSettingsFallback(context)
        }
    }

    /**
     * Tente d'ouvrir l'écran "Démarrage automatique" du Security Center MIUI.
     * Pas d'API publique : on cible les activités connues, qui varient selon
     * les versions de la ROM. Best-effort — repli sur les réglages génériques
     * de l'app si aucune des cibles connues n'est trouvée.
     */
    fun openMiuiAutostartSettings(context: Context) {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.securitycenter.permission.AutoStartManagementActivity")
        )

        for (component in candidates) {
            try {
                context.startActivity(Intent().apply {
                    setComponent(component)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                return
            } catch (e: Exception) {
                Log.d(TAG, "Écran autostart MIUI introuvable via $component, essai suivant")
            }
        }

        Log.w(TAG, "Aucun écran autostart MIUI trouvé, repli vers réglages app")
        openAppSettingsFallback(context)
    }

    private fun openAppSettingsFallback(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Impossible d'ouvrir les réglages de l'application", e)
        }
    }
}