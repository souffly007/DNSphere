package fr.bonobo.dnsphere.data

/**
 * Données complètes des statistiques
 */
data class StatsData(
    val totalBlocked: Int = 0,
    val adsBlocked: Int = 0,
    val trackersBlocked: Int = 0,
    val malwareBlocked: Int = 0,
    val otherBlocked: Int = 0,
    val topDomains: List<DomainCount> = emptyList(),
    val blocksPerDay: List<DayCount> = emptyList(),
    val blocksPerHour: List<HourCount> = emptyList(),
    val statsByType: List<TypeCount> = emptyList()
)

/**
 * Période de temps pour les statistiques
 */
enum class StatsPeriod {
    TODAY,
    WEEK,
    MONTH,
    ALL_TIME
}