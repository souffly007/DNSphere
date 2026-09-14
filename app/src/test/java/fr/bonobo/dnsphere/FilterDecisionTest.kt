package fr.bonobo.dnsphere

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilterDecisionTest {

    @Test
    fun allowDecisionHasNoReason() {
        val decision = FilterDecision.allow()

        assertEquals(FilterAction.ALLOW, decision.action)
        assertNull(decision.reason)
    }

    @Test
    fun blockDecisionKeepsAnIdentifiableReason() {
        val decision = FilterDecision.block(BlockReason.PARENTAL)

        assertEquals(FilterAction.BLOCK, decision.action)
        assertEquals(BlockReason.PARENTAL, decision.reason)
        assertEquals("PARENTAL", decision.reason?.code)
    }
}
