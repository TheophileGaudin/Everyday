package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.bindings.WidgetChangeDispatcher
import org.junit.Assert.assertEquals
import org.junit.Test

class WidgetChangeDispatcherTest {
    @Test
    fun repaintDoesNotReachObservers() {
        val calls = mutableListOf<String>()
        val dispatcher = WidgetChangeDispatcher(
            repaint = { calls += "repaint" },
            transient = { calls += "transient" },
            persistent = { calls += "persistent" }
        )

        dispatcher.requestRepaint()

        assertEquals(listOf("repaint"), calls)
    }

    @Test
    fun transientIsOneRepaintAndOneTransientCallback() {
        val calls = mutableListOf<String>()
        val dispatcher = WidgetChangeDispatcher(
            repaint = { calls += "repaint" },
            transient = { calls += "transient" },
            persistent = { calls += "persistent" }
        )

        dispatcher.notifyTransientStateChanged()

        assertEquals(listOf("repaint", "transient"), calls)
    }

    @Test
    fun persistentIncludesTransientWorkAndOneSaveCallback() {
        val calls = mutableListOf<String>()
        val dispatcher = WidgetChangeDispatcher(
            repaint = { calls += "repaint" },
            transient = { calls += "transient" },
            persistent = { calls += "persistent" }
        )

        dispatcher.notifyPersistentStateChanged()

        assertEquals(listOf("repaint", "transient", "persistent"), calls)
    }
}
