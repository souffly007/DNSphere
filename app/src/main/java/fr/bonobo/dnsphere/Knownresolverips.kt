// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2025-2026 Franck R-F (souffly007)
// This file is part of DNSphere.
package fr.bonobo.dnsphere.dns

/**
 * IPs des résolveurs DNS publics connus, utilisées en dur par certaines apps
 * pour du DoH/DoT/DoQ — contournant ainsi le DNS système et le filtrage DNSphere.
 *
 * Ces IPs sont routées dans le tunnel VPN (voir LocalVpnService.startVpn) afin
 * que tout trafic vers elles soit intercepté. DNSphere ne peut pas déchiffrer le
 * TLS de ce trafic (le contournement TLS nécessiterait un certificat de confiance
 * système, incompatible avec une app "no root" — voir commentaire dans
 * LocalVpnService.isKnownResolverBypass), donc ce trafic est activement rejeté
 * plutôt que filtré, pour forcer un fallback vers le DNS système classique
 * (qui, lui, passe par le pipeline de filtrage normal).
 *
 * Liste non exhaustive, à étendre au besoin — même logique que les listes
 * known-hosts déjà utilisées ailleurs dans l'app.
 */
object KnownResolverIps {
    val ALL: Set<String> = setOf(
        // Cloudflare
        "1.1.1.1", "1.0.0.1",
        // Google
        "8.8.8.8", "8.8.4.4",
        // Quad9
        "9.9.9.9", "149.112.112.112", "9.9.9.11", "149.112.112.11",
        // OpenDNS (Cisco)
        "208.67.222.222", "208.67.220.220",
        // AdGuard DNS
        "94.140.14.14", "94.140.15.15",
        // CleanBrowsing
        "185.228.168.9", "185.228.169.9",
        // Mullvad DNS
        "194.242.2.2", "194.242.2.3", "194.242.2.4",
        "194.242.2.5", "194.242.2.6", "194.242.2.9",
        // DNS4EU
        "86.54.11.1", "86.54.11.201", "86.54.11.12", "86.54.11.212",
        "86.54.11.13", "86.54.11.213", "86.54.11.11", "86.54.11.211"
    )
}