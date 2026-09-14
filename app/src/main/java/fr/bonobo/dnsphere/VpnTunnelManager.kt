package fr.bonobo.dnsphere

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.dns.KnownResolverIps
import kotlinx.coroutines.runBlocking

/** Création de l'interface VPN et de ses routes, sans traiter les paquets. */
class VpnTunnelManager(
    private val service: VpnService,
    private val database: AppDatabase
) {
    fun establish(includeResolverRoutes: Boolean): ParcelFileDescriptor? {
        val excludedApps = runBlocking {
            try { database.excludedAppDao().getAllPackageNames() }
            catch (_: Exception) { emptyList() }
        }

        val builder = service.Builder()
            .setSession("DNSphere Protection")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .setMtu(1500)
            .setBlocking(false)

        if (includeResolverRoutes) {
            KnownResolverIps.ALL.forEach { ip ->
                try { builder.addRoute(ip, 32) }
                catch (e: Exception) { Log.w(TAG, "Route impossible pour $ip", e) }
            }
        }

        try { builder.addDisallowedApplication(service.packageName) }
        catch (e: Exception) { Log.w(TAG, "Impossible d'exclure DNSphere", e) }

        excludedApps.forEach { pkg ->
            try { builder.addDisallowedApplication(pkg) }
            catch (e: Exception) { Log.w(TAG, "Impossible d'exclure $pkg", e) }
        }

        return try {
            builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur de création de l'interface VPN", e)
            null
        }
    }

    companion object { private const val TAG = "VpnTunnelManager" }
}
