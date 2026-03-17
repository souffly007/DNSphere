package fr.bonobo.dnsphere

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class VpnStats(
    val adsBlocked: Int = 0,
    val trackersBlocked: Int = 0,
    val malwareBlocked: Int = 0,
    val shoppingBlocked: Int = 0,
    val isPaused: Boolean = false
)

object StatsLiveData {
    private val _stats = MutableLiveData<VpnStats>()
    val stats: LiveData<VpnStats> = _stats

    fun updateStats(stats: VpnStats) {
        _stats.postValue(stats)
    }
}