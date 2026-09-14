package fr.bonobo.dnsphere.data

/** Politique commune de rétention des journaux détaillés. */
object LogRetentionPolicy {
    const val DEFAULT_RETENTION_DAYS = 30L
    const val MIN_RETENTION_DAYS = 7L
    const val MAX_RETENTION_DAYS = 365L

    fun normalizeDays(value: Long): Long =
        value.coerceIn(MIN_RETENTION_DAYS, MAX_RETENTION_DAYS)

    fun cutoff(nowMillis: Long, retentionDays: Long = DEFAULT_RETENTION_DAYS): Long =
        nowMillis - normalizeDays(retentionDays) * 24L * 60L * 60L * 1000L
}
