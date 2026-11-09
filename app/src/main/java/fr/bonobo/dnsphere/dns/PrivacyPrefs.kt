// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.dns

import android.content.Context

/**
 * Réglages de confidentialité DNS, lus à chaque requête dans les préférences de
 * l'écran Paramètres ("vpn_prefs") : un changement est donc appliqué à chaud,
 * sans redémarrer le VPN.
 */
class PrivacyPrefs(context: Context) {

    companion object {
        const val PREFS_NAME = "vpn_prefs"
        const val KEY_STRICT = "dns_strict_mode"
        const val KEY_QUERY_PROTECTION = "dns_query_protection"
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Si vrai : jamais de repli vers un DNS non chiffré (UDP/53) ni vers le DNS système. */
    val strictMode: Boolean
        get() = prefs.getBoolean(KEY_STRICT, false)

    /** Padding EDNS + ECS à 0 sur les requêtes sortantes chiffrées. */
    val queryProtection: Boolean
        get() = prefs.getBoolean(KEY_QUERY_PROTECTION, true)
}
