package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.interaction.assistant.AssistantToolCall
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionCodes
import com.everyday.everyday_glasses.interaction.assistant.AssistantToolExecutionContext
import com.everyday.everyday_glasses.interaction.assistant.AssistantUiContext
import com.everyday.everyday_glasses.interaction.assistant.AssistantWidgetContext
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileSemanticState
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileTarget
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileTargetMode
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileTargetResolver
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateRequest
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceTileUpdateResult
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.FinanceWidgetSemanticState
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.UpdateFinanceTileSemanticToolHandler
import com.everyday.everyday_glasses.interaction.assistant.widget_adapters.toAssistantSemanticFacts
import com.everyday.everyday_glasses.widgets.finance.FinanceWidget
import com.everyday.shared.sync.FinanceAssetType
import com.everyday.shared.sync.FinanceChartType
import com.everyday.shared.sync.FinanceDataProvider
import com.everyday.shared.sync.FinanceTimeRange
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class FinanceAssistantToolsTest {

    @Test
    fun `each finance target resolves against current visible and hidden tiles`() {
        val tiles = listOf(
            tile("hidden-first", slot = 0, visible = false, visibleOrder = null),
            tile("first-visible", slot = 4, visible = true, visibleOrder = 0),
            tile("top-left", slot = 2, visible = true, visibleOrder = 1),
            tile("selected-hidden", slot = 6, visible = false, visibleOrder = null)
        )
        val visibleIds = listOf("first-visible", "top-left")

        assertEquals(
            "hidden-first",
            FinanceTileTargetResolver.resolve(
                FinanceTileTarget.explicit("hidden-first"), tiles, visibleIds, "selected-hidden"
            )
        )
        assertEquals(
            "selected-hidden",
            FinanceTileTargetResolver.resolve(
                FinanceTileTarget.selected(), tiles, visibleIds, "selected-hidden"
            )
        )
        assertEquals(
            "first-visible",
            FinanceTileTargetResolver.resolve(
                FinanceTileTarget.firstVisible(), tiles, visibleIds, "selected-hidden"
            )
        )
        assertEquals(
            "top-left",
            FinanceTileTargetResolver.resolve(
                FinanceTileTarget.topLeftVisible(), tiles, visibleIds, "selected-hidden"
            )
        )
    }

    @Test
    fun `stable id targeting survives visible-page navigation and reordering`() {
        val tile = tile("stable-tile", slot = 7, visible = false, visibleOrder = null)
        val other = tile("other-tile", slot = 1, visible = true, visibleOrder = 0)
        val target = FinanceTileTarget.explicit("stable-tile")

        assertEquals(
            "stable-tile",
            FinanceTileTargetResolver.resolve(target, listOf(tile, other), listOf("other-tile"), null)
        )
        assertEquals(
            "stable-tile",
            FinanceTileTargetResolver.resolve(
                target,
                listOf(other, tile.copy(slot = 0, visible = true, visibleOrder = 0)),
                listOf("stable-tile", "other-tile"),
                null
            )
        )
    }

    @Test
    fun `semantic state identifies top-left and selected finance tiles`() {
        val state = FinanceWidgetSemanticState(
            widgetId = FinanceWidget.STABLE_ID,
            minimized = false,
            fullscreen = true,
            tiles = listOf(
                tile("tile-a", slot = 3, visible = true, visibleOrder = 1),
                tile("tile-b", slot = 0, visible = true, visibleOrder = 0, selected = true),
                tile("tile-c", slot = 5, visible = false, visibleOrder = null)
            ),
            visibleTileIds = listOf("tile-b", "tile-a"),
            selectedTileId = "tile-b",
            topLeftVisibleTileId = "tile-b"
        )

        val context = AssistantUiContext(
            screenWidth = 640,
            screenHeight = 480,
            layoutName = "Standard",
            focusedWidgetId = null,
            widgets = listOf(
                AssistantWidgetContext(
                    id = FinanceWidget.STABLE_ID,
                    left = 0,
                    top = 0,
                    right = 640,
                    bottom = 480,
                    zOrder = 1,
                    minimized = false,
                    fullscreen = true,
                    pinned = true,
                    facts = state.toAssistantSemanticFacts()
                )
            )
        )
        val prompt = context.toPromptText()
        assertTrue(prompt.contains("finance_top_left_visible_tile_id"))
        assertTrue(prompt.contains("tile-b"))
        assertTrue(prompt.contains("finance_selected_tile_id"))
        assertTrue(prompt.contains("chart_style"))
        assertTrue(prompt.contains("fullscreen"))
    }

    @Test
    fun `missing optional settings remain absent so the existing tile settings can be preserved`() {
        var requested: FinanceTileUpdateRequest? = null
        val handler = UpdateFinanceTileSemanticToolHandler(updateTile = { update ->
            requested = update
            CompletableFuture.completedFuture(
                FinanceTileUpdateResult(
                    applied = true,
                    message = "updated",
                    tileId = "tile-a"
                )
            )
        })

        val execution = handler.invoke(
            AssistantToolCall(
                id = "1",
                name = "update_finance_tile",
                arguments = JSONObject().put("instrument", "FTSE100")
            ),
            AssistantToolExecutionContext(emptyUiContext())
        ).get()

        assertTrue(execution.success)
        assertEquals(FinanceTileTargetMode.TOP_LEFT_VISIBLE, requested!!.target.mode)
        assertEquals("^FTSE", requested.instrument.symbol)
        assertEquals(null, requested.range)
        assertEquals(null, requested.chartType)
        assertEquals(null, requested.slot)
        assertEquals(null, requested.fullscreen)
    }

    @Test
    fun `unknown instrument is rejected before update and leaves the callback untouched`() {
        var updateCalled = false
        val handler = UpdateFinanceTileSemanticToolHandler(updateTile = {
            updateCalled = true
            CompletableFuture.completedFuture(FinanceTileUpdateResult(true, "unexpected"))
        })

        val arguments = JSONObject().put("instrument", "Unknown Fund")
        assertTrue(handler.validate(arguments).isFailure)
        val execution = handler.invoke(
            AssistantToolCall("2", "update_finance_tile", arguments),
            AssistantToolExecutionContext(emptyUiContext())
        ).get()

        assertFalse(execution.success)
        assertEquals(AssistantToolExecutionCodes.INVALID_ARGUMENTS, execution.code)
        assertFalse(updateCalled)
    }

    @Test
    fun `explicit stable tile id and optional range and style are parsed`() {
        var requested: FinanceTileUpdateRequest? = null
        val handler = UpdateFinanceTileSemanticToolHandler(updateTile = { update ->
            requested = update
            CompletableFuture.completedFuture(FinanceTileUpdateResult(true, "updated"))
        })

        val execution = handler.invoke(
            AssistantToolCall(
                "3",
                "update_finance_tile",
                JSONObject()
                    .put("instrument", "^FTSE")
                    .put("tile_id", "stable-tile")
                    .put("range", "1M")
                    .put("chart_style", "CANDLE")
                    .put("slot", 4)
                    .put("fullscreen", false)
            ),
            AssistantToolExecutionContext(emptyUiContext())
        ).get()

        assertTrue(execution.success)
        assertEquals(FinanceTileTargetMode.EXPLICIT, requested!!.target.mode)
        assertEquals("stable-tile", requested.target.tileId)
        assertEquals(FinanceDataProvider.resolveInstrument("FTSE"), requested.instrument)
        assertEquals(FinanceTimeRange.ONE_MONTH, requested.range)
        assertEquals(FinanceChartType.CANDLE, requested.chartType)
        assertEquals(4, requested.slot)
        assertEquals(false, requested.fullscreen)
    }

    private fun tile(
        id: String,
        slot: Int,
        visible: Boolean,
        visibleOrder: Int?,
        selected: Boolean = false
    ) = FinanceTileSemanticState(
        id = id,
        visible = visible,
        visibleOrder = visibleOrder,
        slot = slot,
        selected = selected,
        assetType = FinanceAssetType.INDEX,
        symbol = "^GSPC",
        displayName = "S&P 500",
        range = FinanceTimeRange.ONE_DAY,
        chartStyle = FinanceChartType.LINE
    )

    private fun emptyUiContext() = AssistantUiContext(
        screenWidth = 640,
        screenHeight = 480,
        layoutName = null,
        focusedWidgetId = null,
        widgets = emptyList()
    )
}
