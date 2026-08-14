package com.everyday.shared.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FinanceDataProviderTest {
    @Test
    fun `ftse spoken aliases resolve through the finance catalogue`() {
        listOf("FTSE100", "FTSE 100", "FTSE", "^FTSE", "ftse 100").forEach { query ->
            val instrument = FinanceDataProvider.resolveInstrument(query)
            assertEquals("^FTSE", instrument?.symbol)
            assertEquals("FTSE 100", instrument?.displayName)
        }
    }

    @Test
    fun `catalogue resolves display names and rejects unknown instruments`() {
        assertEquals("^GSPC", FinanceDataProvider.resolveInstrument("S&P 500")?.symbol)
        assertEquals("BTCUSDT", FinanceDataProvider.resolveInstrument("BTC/USDT")?.symbol)
        assertNull(FinanceDataProvider.resolveInstrument("not-a-real-instrument"))
        assertNull(FinanceDataProvider.resolveInstrument("  "))
    }
}
