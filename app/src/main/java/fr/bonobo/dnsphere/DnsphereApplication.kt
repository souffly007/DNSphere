package fr.bonobo.dnsphere

import android.app.Application
import fr.bonobo.dnsphere.data.AppDatabase

class DnsphereApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Le planificateur est initialisé dès le lancement du processus afin
        // que les créneaux restent actifs même si l'utilisateur n'ouvre pas
        // directement l'écran des profils.
        ProfileSchedulerWorker.start(this)
    }

    companion object {
        lateinit var instance: DnsphereApplication
            private set
    }
}
