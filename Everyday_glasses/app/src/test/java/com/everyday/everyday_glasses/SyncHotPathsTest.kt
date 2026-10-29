package com.everyday.everyday_glasses

import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDashboardTileConfig
import com.everyday.shared.sync.FinanceSnapshot
import com.everyday.shared.sync.FinanceTileSnapshot
import com.everyday.shared.sync.SyncChannel
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.transport.TransportDecodeResult
import com.everyday.shared.transport.TransportMessage
import com.everyday.shared.transport.TransportProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.json.JSONObject

class SyncHotPathsTest {
    @Test
    fun `finance snapshot contains only canonical dashboard tiles and rejects an empty dashboard`() {
        val primary = financeTile("primary", "AAPL", 101f, 1_000L)
        val secondary = financeTile("secondary", "MSFT", 202f, 2_000L)

        val snapshot = FinanceSnapshot.fromTiles(listOf(primary, secondary))!!

        assertEquals(listOf(primary, secondary), snapshot.tiles)
        assertNull(FinanceSnapshot.fromTiles(emptyList()))

        val payload = JSONObject(TransportProtocol.encode(
            TransportMessage.SyncSnapshotMessage(SyncSnapshot(finance = snapshot))
        )).getJSONObject("payload").getJSONObject("finance")
        assertEquals(setOf("tiles"), payload.keys().asSequence().toSet())
    }

    @Test
    fun `sync request encoding retains every supported field`() {
        val request = SyncRequest(
            channels = setOf(SyncChannel.NEWS, SyncChannel.FINANCE, SyncChannel.MAP),
            force = true,
            reason = "characterization",
            requestedAtMs = 123456L,
            countryCode = "GB",
            financeTiles = listOf(
                FinanceDashboardTileConfig(
                    id = "tile-1",
                    assetType = FinanceAssetType.INDEX,
                    symbol = "AAPL",
                    range = "1mo",
                    chartType = FinanceChartType.CANDLE,
                    slot = 3
                )
            ),
            financeRefreshTileIds = listOf("tile-1", "tile-2"),
            financeSymbol = "AAPL",
            financeRange = "1mo",
            financeAssetType = FinanceAssetType.INDEX,
            financeChartType = FinanceChartType.CANDLE,
            financeTileId = "tile-1",
            financeLiveEnabled = true,
            mapLat = 51.5074,
            mapLon = -0.1278,
            mapPoiMode = "DEFAULT",
            mapPoiCategories = listOf("hospital", "pharmacy"),
            mapPoiRadiusM = 2_000,
            mapPoiMaxResults = 8,
            mapPoiImportantCategories = listOf("station", "hospital"),
            mapPoiImportantRadiiM = mapOf("station" to 8_000, "hospital" to 1_200),
            mapPoiNormalCategories = listOf("cafe", "restaurant"),
            mapPoiNormalMaxResults = 4,
            mapPoiShowClosest = true,
            mapPoiClosestRadiusM = 160
        )

        val decoded = TransportProtocol.decode(
            TransportProtocol.encode(TransportMessage.SyncRequestMessage(request))
        ) as TransportDecodeResult.Success
        assertEquals(request, (decoded.message as TransportMessage.SyncRequestMessage).request)
    }

    private fun financeTile(id: String, symbol: String, value: Float, fetchedAtMs: Long) =
        FinanceTileSnapshot(
            id = id,
            assetType = FinanceAssetType.INDEX,
            symbol = symbol,
            displayName = "$symbol Inc.",
            country = "US",
            range = "1d",
            rangeLabel = "1D",
            chartType = FinanceChartType.LINE,
            points = listOf(value - 1f, value),
            percentChange = 1.25f,
            fetchedAtMs = fetchedAtMs,
            staleAfterMs = 60_000L
        )
}
