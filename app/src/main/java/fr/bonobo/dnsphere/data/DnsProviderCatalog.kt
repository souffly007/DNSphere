package fr.bonobo.dnsphere.data

/**
 * Catalogue unique des fournisseurs proposés par DNSphere.
 * Les identifiants sont conservés pour rester compatibles avec les
 * préférences et les notifications existantes.
 */
data class DnsProviderInfo(
    val id: String,
    val label: String,
    val description: String = ""
)

object DnsProviderCatalog {

    val providers: List<DnsProviderInfo> = listOf(
        DnsProviderInfo("standard", "🌐 DNS Standard"),
        DnsProviderInfo("cloudflare", "🔵 Cloudflare (DoH)"),
        DnsProviderInfo("hagezi-security", "HaGeZi Sécurité", "Phishing, sites malveillants, arnaques"),
        DnsProviderInfo("hagezi-full", "HaGeZi Protection complète", "Anti-pubs, traqueurs"),
        DnsProviderInfo("quad9", "Quad9 (Hors France)"),
        DnsProviderInfo("google", "🔴 Google (DoH)"),
        DnsProviderInfo("adguard", "🛡️ AdGuard (DoH)"),

        DnsProviderInfo("mullvad", "🟣 Mullvad (DoH)"),
        DnsProviderInfo("mullvad-adblock", "🟣 Mullvad · Anti-pub"),
        DnsProviderInfo("mullvad-base", "🟣 Mullvad · Base"),
        DnsProviderInfo("mullvad-extended", "🟣 Mullvad · Extended"),
        DnsProviderInfo("mullvad-family", "🟣 Mullvad · Family"),
        DnsProviderInfo("mullvad-all", "🟣 Mullvad · Protection maximale"),

        DnsProviderInfo("dns4eu-protective", "🇪🇺 DNS4EU · Protective"),
        DnsProviderInfo("dns4eu-child", "🇪🇺 DNS4EU · Child"),
        DnsProviderInfo("dns4eu-noads", "🇪🇺 DNS4EU · No Ads"),
        DnsProviderInfo("dns4eu-child-noads", "🇪🇺 DNS4EU · Child + No Ads"),
        DnsProviderInfo("dns4eu-unfiltered", "🇪🇺 DNS4EU · Unfiltered"),

        DnsProviderInfo("rethink-light", "🟢 RethinkDNS · Léger"),
        DnsProviderInfo("rethink-recommended", "🟡 RethinkDNS · Recommandé"),
        DnsProviderInfo("rethink-max", "🔴 RethinkDNS · Maximum"),

        DnsProviderInfo("cloudflare-doq", "🔵 Cloudflare (DoH/QUIC auto)"),
        DnsProviderInfo("adguard-doq", "🛡️ AdGuard (DoH/QUIC auto)"),
        DnsProviderInfo("cloudflare-doh3", "🔵 Cloudflare (DoH3)"),
        DnsProviderInfo("adguard-doh3", "🛡️ AdGuard (DoH3)"),
        DnsProviderInfo("google-doh3", "🔴 Google (DoH3)")
    )

    val ids: List<String>
        get() = providers.map { it.id }

    fun labelFor(id: String): String =
        providers.firstOrNull { it.id == id.lowercase().trim() }?.label
            ?: id
}
