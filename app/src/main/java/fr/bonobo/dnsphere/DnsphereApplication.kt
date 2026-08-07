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
    }

    companion object {
        lateinit var instance: DnsphereApplication
            private set
    }
}