package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.ui.widgets.common.PresentationBounds
import com.everyday.everyday_glasses.ui.widgets.common.PresentationCancellable
import com.everyday.everyday_glasses.ui.widgets.common.PresentationController
import com.everyday.everyday_glasses.ui.widgets.common.PresentationExpiryPolicy
import com.everyday.everyday_glasses.ui.widgets.common.PresentationLifecycleDelegate
import com.everyday.everyday_glasses.ui.widgets.common.PresentationMode
import com.everyday.everyday_glasses.ui.widgets.common.PresentationProgress
import com.everyday.everyday_glasses.ui.widgets.common.PresentationScheduler
import com.everyday.everyday_glasses.ui.widgets.common.PresentationSession
import com.everyday.everyday_glasses.ui.widgets.common.PresentationSnapshot
import com.everyday.everyday_glasses.ui.widgets.common.PresentationSpec
import com.everyday.everyday_glasses.interaction.user_input.UserInputActivity
import com.everyday.everyday_glasses.interaction.user_input.UserInputKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresentationLifecycleTest {
    private class FakeScheduler : PresentationScheduler {
        private data class Task(
            val delayMs: Long,
            val action: () -> Unit,
            var cancelled: Boolean = false
        )

        private val tasks = mutableListOf<Task>()

        override fun schedule(delayMs: Long, task: () -> Unit): PresentationCancellable {
            val scheduled = Task(delayMs, task)
            tasks += scheduled
            return PresentationCancellable { scheduled.cancelled = true }
        }

        fun runNext() {
            val next = tasks.firstOrNull { !it.cancelled } ?: return
            next.cancelled = true
            next.action()
        }
    }

    private val snapshot = PresentationSnapshot(
        existedBeforePresentation = true,
        visible = true,
        bounds = PresentationBounds(12f, 18f, 212f, 138f),
        fullscreen = false,
        focused = true,
        zOrder = 7,
        windowedBounds = PresentationBounds(12f, 18f, 212f, 138f),
        minimized = false,
        wasFullscreenBeforeMinimize = false,
        pinned = false
    )

    @Test
    fun `duration validation accepts exact boundaries and rejects values outside them`() {
        PresentationSpec(PresentationMode.WINDOWED, 5, PresentationExpiryPolicy.CLOSE)
        PresentationSpec(
            PresentationMode.FULLSCREEN,
            120,
            PresentationExpiryPolicy.RESTORE_PREVIOUS
        )

        assertFails {
            PresentationSpec(
                PresentationMode.WINDOWED,
                4,
                PresentationExpiryPolicy.CLOSE
            )
        }
        assertFails {
            PresentationSpec(
                PresentationMode.WINDOWED,
                121,
                PresentationExpiryPolicy.CLOSE
            )
        }
        assertFails {
            PresentationSpec(
                PresentationMode.WINDOWED,
                0,
                PresentationExpiryPolicy.CLOSE
            )
        }
    }

    @Test
    fun `all expiry policies work for existing and newly created targets`() {
        for (policy in PresentationExpiryPolicy.entries) {
            val existing = expiryHarness(policy, snapshot.copy(existedBeforePresentation = true))
            existing.expire()
            when (policy) {
                PresentationExpiryPolicy.RESTORE_PREVIOUS -> assertEquals("restored", existing.state)
                PresentationExpiryPolicy.LEAVE_WINDOWED -> assertEquals("windowed", existing.state)
                PresentationExpiryPolicy.CLOSE -> assertEquals("closed", existing.state)
            }

            val created = expiryHarness(policy, snapshot.copy(existedBeforePresentation = false))
            created.expire()
            when (policy) {
                PresentationExpiryPolicy.RESTORE_PREVIOUS -> assertEquals("closed", created.state)
                PresentationExpiryPolicy.LEAVE_WINDOWED -> assertEquals("windowed", created.state)
                PresentationExpiryPolicy.CLOSE -> assertEquals("closed", created.state)
            }
        }
    }

    @Test
    fun `snapshot preserves bounds mode focus visibility and z order`() {
        val scheduler = FakeScheduler()
        val controller = PresentationController(
            scheduler = scheduler,
            nowElapsedMs = { 0L }
        )

        val session = controller.start(
            "text#0",
            PresentationSpec(
                PresentationMode.FULLSCREEN,
                10,
                PresentationExpiryPolicy.RESTORE_PREVIOUS
            )
        ) { snapshot }

        assertNotNull(session)
        assertEquals(snapshot, session!!.snapshot)
        assertEquals(10, session.originalDurationSeconds)
        assertEquals(7, session.snapshot.zOrder)
        assertEquals(PresentationBounds(12f, 18f, 212f, 138f), session.snapshot.windowedBounds)
        assertTrue(session.snapshot.visible)
        assertTrue(session.snapshot.focused)
        assertFalse(session.snapshot.fullscreen)
    }

    @Test
    fun `replacement restores before the new snapshot is captured`() {
        val scheduler = FakeScheduler()
        var state = "old-presented"
        val order = mutableListOf<String>()
        val controller = PresentationController(
            scheduler = scheduler,
            nowElapsedMs = { 0L },
            delegate = object : PresentationLifecycleDelegate {
                override fun onPresentationStarted(session: PresentationSession) {
                    order += "start:${session.targetId}"
                }

                override fun onRestorePrevious(session: PresentationSession) {
                    order += "restore:${session.targetId}"
                    state = "old-restored"
                }
            }
        )

        controller.start("old",
            PresentationSpec(PresentationMode.FULLSCREEN, 10, PresentationExpiryPolicy.CLOSE)
        ) {
            PresentationSnapshot(true, true, snapshot.bounds, true, false, 1)
        }
        controller.start("new",
            PresentationSpec(PresentationMode.WINDOWED, 10, PresentationExpiryPolicy.CLOSE)
        ) {
            order += "snapshot:$state"
            PresentationSnapshot(true, true, snapshot.bounds, false, false, 2)
        }

        assertEquals(listOf("start:old", "restore:old", "snapshot:old-restored", "start:new"), order)
        assertEquals("new", controller.activeSession?.targetId)
    }

    @Test
    fun `dismissal and target removal clear progress and make stale timers harmless`() {
        val scheduler = FakeScheduler()
        val progress = mutableListOf<PresentationProgress?>()
        val controller = PresentationController(
            scheduler = scheduler,
            nowElapsedMs = { 0L },
            delegate = object : PresentationLifecycleDelegate {
                override fun onProgressChanged(value: PresentationProgress?) {
                    progress += value
                }
            }
        )
        val session = controller.start("text#0",
            PresentationSpec(PresentationMode.WINDOWED, 10, PresentationExpiryPolicy.CLOSE)
        ) {
            snapshot
        }!!
        assertTrue(controller.dismiss())
        assertNull(controller.activeSession)
        assertNull(progress.last())
        assertFalse(controller.onTimerFired(session.token, 10_000L))

        controller.start("text#1",
            PresentationSpec(PresentationMode.WINDOWED, 10, PresentationExpiryPolicy.CLOSE)
        ) {
            snapshot
        }
        assertTrue(controller.onTargetRemoved("text#1"))
        assertNull(controller.activeSession)
        assertFalse(controller.onTimerFired(session.token + 1, 10_000L))
    }

    @Test
    fun `progress uses the original duration and disappears before expiry policy runs`() {
        val scheduler = FakeScheduler()
        val progress = mutableListOf<PresentationProgress?>()
        val order = mutableListOf<String>()
        val controller = PresentationController(
            scheduler = scheduler,
            nowElapsedMs = { 0L },
            delegate = object : PresentationLifecycleDelegate {
                override fun onProgressChanged(value: PresentationProgress?) {
                    progress += value
                    if (value == null) order += "progress-cleared"
                }

                override fun onLeaveWindowed(session: PresentationSession) {
                    order += "expiry-applied"
                }
            }
        )
        val session = controller.start(
            "text#0",
            PresentationSpec(
                PresentationMode.FULLSCREEN,
                10,
                PresentationExpiryPolicy.LEAVE_WINDOWED
            )
        ) { snapshot }!!

        controller.onTimerFired(session.token, 2_500L)
        assertEquals(0.25f, progress.filterNotNull().last().fraction, 0.001f)
        assertEquals(10_000L, progress.filterNotNull().last().durationMs)

        controller.onTimerFired(session.token, 10_000L)
        assertEquals(listOf("progress-cleared", "expiry-applied"), order)
        assertNull(controller.activeSession)
    }

    @Test
    fun `user cancellation happens before normal delivery and leaves current state untouched`() {
        val scheduler = FakeScheduler()
        var currentState = "fullscreen"
        val controller = PresentationController(
            scheduler = scheduler,
            nowElapsedMs = { 0L }
        )
        var delivered = false
        val input = UserInputActivity {
            controller.cancelForUserInput()
            delivered = true
        }
        controller.start("map",
            PresentationSpec(PresentationMode.FULLSCREEN, 10, PresentationExpiryPolicy.CLOSE)
        ) {
            snapshot.copy(fullscreen = false)
        }

        input.onUserInput(UserInputKind.PHONE_DOWN)

        assertTrue(delivered)
        assertEquals("fullscreen", currentState)
        assertNull(controller.activeSession)
        assertFalse(controller.onTimerFired(1L, 10_000L))
    }

    private data class ExpiryHarness(
        var state: String,
        val expire: () -> Unit
    )

    private fun expiryHarness(
        policy: PresentationExpiryPolicy,
        targetSnapshot: PresentationSnapshot
    ): ExpiryHarness {
        val scheduler = FakeScheduler()
        var state = "presented"
        val controller = PresentationController(
            scheduler = scheduler,
            nowElapsedMs = { 0L },
            delegate = object : PresentationLifecycleDelegate {
                override fun onPresentationStarted(session: PresentationSession) {
                    state = "presented"
                }

                override fun onRestorePrevious(session: PresentationSession) {
                    state = if (session.snapshot.existedBeforePresentation) "restored" else "closed"
                }

                override fun onLeaveWindowed(session: PresentationSession) {
                    state = "windowed"
                }

                override fun onClose(targetId: String) {
                    state = "closed"
                }
            }
        )
        controller.start("target", PresentationSpec(PresentationMode.FULLSCREEN, 5, policy)) {
            targetSnapshot
        }
        lateinit var harness: ExpiryHarness
        harness = ExpiryHarness(state) {
            controller.onTimerFired(controller.activeSession!!.token, 5_000L)
            harness.state = state
        }
        return harness
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected validation to fail")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
