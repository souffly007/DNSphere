package fr.bonobo.dnsphere.data

import java.util.*

class StatsRepository(private val blockLogDao: BlockLogDao) {

    // 1. Fonction de nettoyage (à appeler pour remettre à zéro ce qui a plus de 7 jours)
    suspend fun clearOldStats() {
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000)
        blockLogDao.deleteOldLogs(sevenDaysAgo)
    }

    suspend fun getStats(period: StatsPeriod): StatsData {
        val since = getStartTimestamp(period)

        return if (period == StatsPeriod.ALL_TIME) {
            StatsData(
                totalBlocked = blockLogDao.getTotalBlocked(),
                adsBlocked = blockLogDao.getCountByTypeAllTime("AD"),
                trackersBlocked = blockLogDao.getCountByTypeAllTime("TRACKER"),
                malwareBlocked = blockLogDao.getCountByTypeAllTime("MALWARE"),
                otherBlocked = blockLogDao.getCountByTypeAllTime("OTHER") +
                        blockLogDao.getCountByTypeAllTime("CUSTOM"),
                topDomains = blockLogDao.getTopBlockedDomainsAllTime(10),
                blocksPerDay = blockLogDao.getBlocksPerDay(getStartTimestamp(StatsPeriod.MONTH)),
                blocksPerHour = blockLogDao.getBlocksPerHour(getStartTimestamp(StatsPeriod.TODAY)),
                statsByType = blockLogDao.getStatsByTypeAllTime()
            )
        } else {
            StatsData(
                totalBlocked = when (period) {
                    StatsPeriod.TODAY -> blockLogDao.getBlockedToday(since)
                    StatsPeriod.WEEK -> blockLogDao.getBlockedThisWeek(since)
                    StatsPeriod.MONTH -> blockLogDao.getBlockedThisMonth(since)
                    else -> blockLogDao.getTotalBlocked()
                },
                adsBlocked = blockLogDao.getCountByType("AD", since),
                trackersBlocked = blockLogDao.getCountByType("TRACKER", since),
                malwareBlocked = blockLogDao.getCountByType("MALWARE", since),
                otherBlocked = blockLogDao.getCountByType("OTHER", since) +
                        blockLogDao.getCountByType("CUSTOM", since),
                topDomains = blockLogDao.getTopBlockedDomains(since, 10),
                blocksPerDay = blockLogDao.getBlocksPerDay(since),
                blocksPerHour = blockLogDao.getBlocksPerHour(getStartTimestamp(StatsPeriod.TODAY)),
                statsByType = blockLogDao.getStatsByType(since)
            )
        }
    }

    private fun getStartTimestamp(period: StatsPeriod): Long {
        val calendar = Calendar.getInstance()

        return when (period) {
            StatsPeriod.TODAY -> {
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            StatsPeriod.WEEK -> {
                // Utilisation des 7 jours glissants
                calendar.add(Calendar.DAY_OF_YEAR, -7)
                calendar.timeInMillis
            }
            StatsPeriod.MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                calendar.timeInMillis
            }
            StatsPeriod.ALL_TIME -> 0L
        }
    }
}