package fr.bonobo.dnsphere

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

// On ajoute 'displayLabel' pour stocker la phrase complète
data class VpnStats(
    val adsBlocked: Int = 0,
    val trackersBlocked: Int = 0,
    val malwareBlocked: Int = 0,
    val shoppingBlocked: Int = 0,
    val isPaused: Boolean = false,
    val displayLabel: String = "" // <--- LE TIROIR POUR TA PHRASE
)

object StatsLiveData {
    private val _stats = MutableLiveData<VpnStats>()
    val vpnStats: LiveData<VpnStats> = _stats // On l'appelle vpnStats pour la cohérence

    fun updateStats(stats: VpnStats) {
        _stats.postValue(stats)
    }
}