package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.data.AppDatabase
import fr.bonobo.dnsphere.data.BlockLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Gestion des compteurs, des journaux et de la publication des statistiques. */
class StatsController(
    private val database: AppDatabase,
    private val scope: CoroutineScope,
    private val isRunning: () -> Boolean,
    private val isPaused: () -> Boolean,
    private val onStatsPublished: () -> Unit
) {

    data class Counters(
        val ads: Int = 0,
        val trackers: Int = 0,
        val malware: Int = 0,
        val shopping: Int = 0,
        val other: Int = 0
    )

    @Volatile private var counters = Counters()

    fun snapshot(): Counters = counters

    fun incrementBlockCounter(blockType: String) {
        val current = counters
        counters = when (BlockReason.fromCode(blockType)) {
            BlockReason.ADS -> current.copy(ads = current.ads + 1)
            BlockReason.TRACKER -> current.copy(trackers = current.trackers + 1)
            BlockReason.MALWARE -> current.copy(malware = current.malware + 1)
            BlockReason.SHOPPING -> current.copy(shopping = current.shopping + 1)
            else -> current.copy(other = current.other + 1)
        }
    }

    fun logBlock(hostname: String, type: String) {
        scope.launch {
            try {
                database.blockLogDao().insert(BlockLog(domain = hostname, type = type))
            } catch (_: Exception) { }
        }
    }

    fun startUpdates() {
        scope.launch {
            while (isRunning()) {
                val values = counters
                StatsLiveData.updateStats(
                    VpnStats(
                        adsBlocked = values.ads,
                        trackersBlocked = values.trackers,
                        malwareBlocked = values.malware,
                        shoppingBlocked = values.shopping,
                        otherBlocked = values.other,
                        isPaused = isPaused()
                    )
                )
                onStatsPublished()
                delay(2000)
            }
        }
    }
}
