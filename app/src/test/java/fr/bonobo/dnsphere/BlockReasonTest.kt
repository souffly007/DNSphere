package fr.bonobo.dnsphere

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockReasonTest {

    @Test
    fun knownCodesKeepTheirMeaning() {
        assertEquals(BlockReason.ADS, BlockReason.fromCode("AD"))
        assertEquals(BlockReason.PARENTAL, BlockReason.fromCode("PARENTAL"))
        assertEquals(BlockReason.EXTERNAL, BlockReason.fromCode("EXTERNAL"))
        assertEquals(BlockReason.APP_BLOCK, BlockReason.fromCode("APP_BLOCK"))
    }

    @Test
    fun unknownCodesAreClassifiedAsOther() {
        assertEquals(BlockReason.OTHER, BlockReason.fromCode("future_reason"))
    }
}
