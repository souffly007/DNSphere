package fr.bonobo.dnsphere

import fr.bonobo.dnsphere.data.LogRetentionPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class LogRetentionPolicyTest {

    @Test
    fun defaultRetentionIsThirtyDays() {
        val now = 30L * 24L * 60L * 60L * 1000L
        assertEquals(0L, LogRetentionPolicy.cutoff(now))
    }

    @Test
    fun retentionIsClampedToSafeBounds() {
        assertEquals(
            LogRetentionPolicy.MIN_RETENTION_DAYS,
            LogRetentionPolicy.normalizeDays(1)
        )
        assertEquals(
            LogRetentionPolicy.MAX_RETENTION_DAYS,
            LogRetentionPolicy.normalizeDays(9999)
        )
    }
}
