package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.adapters.AwaitingCommandUiDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

class AwaitingCommandUiDispatcherTest {
    @Test
    fun `UI thread action runs directly and returns its result`() {
        var actionCalls = 0
        val dispatcher = AwaitingCommandUiDispatcher(
            isUiThread = { true },
            postToUiThread = { error("UI-thread dispatch must not post") },
        )

        val result = dispatcher.dispatch {
            actionCalls++
            false
        }

        assertEquals(false, result)
        assertEquals(1, actionCalls)
    }

    @Test
    fun `posted action returns its completed result`() {
        val uiExecutor = Executors.newSingleThreadExecutor()
        try {
            val dispatcher = AwaitingCommandUiDispatcher(
                isUiThread = { false },
                postToUiThread = { action ->
                    uiExecutor.execute(action)
                    true
                },
                timeoutMs = 1_000L,
            )

            assertEquals(true, dispatcher.dispatch { true })
        } finally {
            uiExecutor.shutdownNow()
        }
    }

    @Test
    fun `rejected post returns no result and does not run action`() {
        var actionRan = false
        val dispatcher = AwaitingCommandUiDispatcher(
            isUiThread = { false },
            postToUiThread = { false },
            timeoutMs = 25L,
        )

        val result = dispatcher.dispatch {
            actionRan = true
            true
        }

        assertNull(result)
        assertFalse(actionRan)
    }

    @Test
    fun `timed out queued action is cancelled and cannot execute late`() {
        val queued = mutableListOf<Runnable>()
        var actionRan = false
        val dispatcher = AwaitingCommandUiDispatcher(
            isUiThread = { false },
            postToUiThread = queued::add,
            timeoutMs = 25L,
        )

        val result = dispatcher.dispatch {
            actionRan = true
            true
        }

        assertNull(result)
        assertEquals(1, queued.size)
        assertFalse(actionRan)

        queued.single().run()

        assertFalse(actionRan)
        assertTrue(queued.single().isDone())
    }

    private fun Runnable.isDone(): Boolean =
        (this as java.util.concurrent.Future<*>).isDone
}