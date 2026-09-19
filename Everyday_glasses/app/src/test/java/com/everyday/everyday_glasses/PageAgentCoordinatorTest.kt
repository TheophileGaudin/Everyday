package com.everyday.everyday_glasses

import com.everyday.everyday_glasses.assistant.BrowserRoutingCoordinator
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentAction
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentActionCode
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentActionResult
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentActionType
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentAudio
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentBrowserAdapter
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentClock
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentConfirmationRequest
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentCoordinateMapper
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentCoordinator
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentFullscreenMode
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentModelTurn
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentPageSnapshot
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentPolicy
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentProvider
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentRunRequest
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentScreenshot
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentTargetBinding
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentTargetUnavailableException
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentTerminalState
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentTurnRequest
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentUpdate
import com.everyday.everyday_glasses.assistant.page_agent.PageAgentUpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PageAgentCoordinatorTest {
    @Test
    fun `multi step page actions use the same target and pass prior results`() {
        val clock = FakeClock()
        val provider = FakeProvider(
            mutableListOf(
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 25f, y = 10f)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TYPE, text = "query")),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.SCROLL, deltaY = 20f)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.BACK)),
                PageAgentModelTurn(
                    PageAgentAction(
                        PageAgentActionType.NAVIGATE,
                        url = "https://example.test"
                    )
                ),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.WAIT, waitMs = 10L)),
                PageAgentModelTurn(
                    PageAgentAction(
                        PageAgentActionType.FULLSCREEN,
                        fullscreenMode = PageAgentFullscreenMode.PAGE
                    )
                ),
                PageAgentModelTurn(
                    PageAgentAction(
                        PageAgentActionType.FINISH,
                        resultState = PageAgentTerminalState.SUCCESS,
                        message = "Finished"
                    )
                )
            )
        )
        val browser = FakeBrowserAdapter()
        val coordinator = PageAgentCoordinator({ "mock-key" }, provider, clock)

        val result = coordinator.run("Complete the page task", browser)

        assertEquals(PageAgentTerminalState.SUCCESS, result.state)
        assertEquals(PageAgentPolicy.DEFAULT_MODEL_ID, provider.modelIds.toSet().single())
        assertEquals(
            listOf("tap:50.0,20.0", "type:query", "scroll:0.0,20.0", "back", "navigate:https://example.test", "wait:10", "fullscreen:PAGE"),
            browser.actions
        )
        assertEquals(8, provider.requests.size)
        assertEquals(PageAgentActionType.TAP, provider.requests[1].priorActionResult?.action)
        assertEquals(PageAgentActionCode.SUCCESS, provider.requests[1].priorActionResult?.code)
        assertEquals("Complete the page task", provider.requests.last().originalTask)
        assertEquals(100, provider.requests.last().screenshot.width)
        assertEquals(50, provider.requests.last().screenshot.height)
        assertEquals(200, provider.requests.last().viewportWidth)
        assertEquals(100, provider.requests.last().viewportHeight)
        assertEquals(browser.binding, result.target)
    }

    @Test
    fun `coordinate mapper scales screenshot coordinates and adds widget chrome`() {
        val point = PageAgentCoordinateMapper.mapToViewport(25f, 10f, 100, 50, 200, 100).getOrThrow()
        val widgetPoint = PageAgentCoordinateMapper.addWidgetChromeOffset(point, 7f, 76f)

        assertEquals(50f, point.x, 0.001f)
        assertEquals(20f, point.y, 0.001f)
        assertEquals(57f, widgetPoint.x, 0.001f)
        assertEquals(96f, widgetPoint.y, 0.001f)
        assertTrue(PageAgentCoordinateMapper.mapToViewport(100f, 10f, 100, 50, 200, 100).isFailure)
        assertTrue(PageAgentCoordinateMapper.mapToViewport(-1f, 10f, 100, 50, 200, 100).isFailure)
    }

    @Test
    fun `coordinate-aware type focuses the field before replacing text`() {
        val provider = FakeProvider(mutableListOf(
            PageAgentModelTurn(
                PageAgentAction(
                    type = PageAgentActionType.TYPE,
                    x = 50f,
                    y = 25f,
                    text = "query"
                )
            ),
            PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))
        ))
        val browser = FakeBrowserAdapter()

        val result = PageAgentCoordinator({ "mock-key" }, provider).run("search", browser)

        assertEquals(PageAgentTerminalState.SUCCESS, result.state)
        assertEquals(listOf("tap:100.0,50.0", "type:query"), browser.actions)
    }

    @Test
    fun `fullscreen action supports page and video modes`() {
        val provider = FakeProvider(mutableListOf(
            PageAgentModelTurn(
                PageAgentAction(
                    PageAgentActionType.FULLSCREEN,
                    fullscreenMode = PageAgentFullscreenMode.VIDEO
                )
            ),
            PageAgentModelTurn(
                PageAgentAction(
                    PageAgentActionType.FULLSCREEN,
                    fullscreenMode = PageAgentFullscreenMode.PAGE
                )
            ),
            PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))
        ))
        val browser = FakeBrowserAdapter()

        val result = PageAgentCoordinator({ "mock-key" }, provider).run("fullscreen", browser)

        assertEquals(PageAgentTerminalState.SUCCESS, result.state)
        assertEquals(listOf("fullscreen:VIDEO", "fullscreen:PAGE"), browser.actions)
    }

    @Test
    fun `twenty model actions is a hard limit`() {
        val provider = FakeProvider(MutableList(25) {
            PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 1f, y = 1f))
        })
        val browser = FakeBrowserAdapter()
        val result = PageAgentCoordinator({ "mock-key" }, provider).run("tap repeatedly", browser)

        assertEquals(PageAgentTerminalState.TIMEOUT, result.state)
        assertEquals(20, result.actionCount)
        assertEquals(20, provider.requests.size)
        assertEquals(20, browser.actions.size)
    }

    @Test
    fun `ninety second monotonic limit prevents the pending action`() {
        val clock = FakeClock()
        val provider = FakeProvider(
            mutableListOf(
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 1f, y = 1f)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))
            )
        ) { clock.advance(90_000L) }
        val browser = FakeBrowserAdapter()

        val result = PageAgentCoordinator({ "mock-key" }, provider, clock).run("slow task", browser)

        assertEquals(PageAgentTerminalState.TIMEOUT, result.state)
        assertTrue(browser.actions.isEmpty())
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `cancellation ends a blocked run cleanly`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val provider = object : PageAgentProvider {
            override fun nextTurn(
                apiKey: String,
                modelId: String,
                request: PageAgentTurnRequest
            ): Result<PageAgentModelTurn> {
                started.countDown()
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Return a turn so the coordinator can observe cancellation and produce a result.
                }
                return Result.success(
                    PageAgentModelTurn(
                        PageAgentAction(
                            PageAgentActionType.TAP,
                            x = 1f,
                            y = 1f
                        )
                    )
                )
            }
        }
        val browser = FakeBrowserAdapter()
        val coordinator = PageAgentCoordinator({ "mock-key" }, provider)
        val handle = coordinator.start(PageAgentRunRequest("cancel me", browser))

        assertTrue(started.await(2, TimeUnit.SECONDS))
        handle.cancel()
        release.countDown()

        assertEquals(PageAgentTerminalState.CANCELLED, handle.result.get(2, TimeUnit.SECONDS).state)
        assertTrue(browser.actions.isEmpty())
        coordinator.close()
    }

    @Test
    fun `disappearing target is terminal and never switches adapters`() {
        val provider = FakeProvider(
            mutableListOf(
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 1f, y = 1f)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))
            )
        )
        val bound = FakeBrowserAdapter().apply { afterAction = { available = false } }
        val unrelated = FakeBrowserAdapter(
            PageAgentTargetBinding(
                "browser#other",
                "tab",
                "webview"
            )
        )

        val result = PageAgentCoordinator({ "mock-key" }, provider).run("use bound page", bound)

        assertEquals(PageAgentTerminalState.TARGET_UNAVAILABLE, result.state)
        assertEquals(listOf("tap:2.0,2.0"), bound.actions)
        assertTrue(unrelated.actions.isEmpty())
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun `target unavailable before first turn does not call provider`() {
        val provider = FakeProvider(mutableListOf(
            PageAgentModelTurn(
                PageAgentAction(
                    PageAgentActionType.FINISH
                )
            )
        ))
        val browser = FakeBrowserAdapter().apply { available = false }

        val result = PageAgentCoordinator({ "mock-key" }, provider).run("missing page", browser)

        assertEquals(PageAgentTerminalState.TARGET_UNAVAILABLE, result.state)
        assertTrue(provider.requests.isEmpty())
    }

    @Test
    fun `repeated invalid coordinates abort without delivering a tap`() {
        val provider = FakeProvider(MutableList(4) {
            PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 100f, y = 100f))
        })
        val browser = FakeBrowserAdapter()

        val result = PageAgentCoordinator({ "mock-key" }, provider).run("invalid taps", browser)

        assertEquals(PageAgentTerminalState.FAILURE, result.state)
        assertTrue(result.message.contains("repeated invalid"))
        assertEquals(3, result.actionCount)
        assertTrue(browser.actions.isEmpty())
    }

    @Test
    fun `provider failure is returned as a structured failure`() {
        val provider = object : PageAgentProvider {
            override fun nextTurn(apiKey: String, modelId: String, request: PageAgentTurnRequest): Result<PageAgentModelTurn> =
                Result.failure(IllegalStateException("mock provider down"))
        }

        val result = PageAgentCoordinator({ "mock-key" }, provider).run("fail", FakeBrowserAdapter())

        assertEquals(PageAgentTerminalState.FAILURE, result.state)
        assertEquals("mock provider down", result.message)
    }

    @Test
    fun `confirmation pauses before navigation and resumes only after approval`() {
        val confirmationSeen = CountDownLatch(1)
        val provider = FakeProvider(
            mutableListOf(
                PageAgentModelTurn(
                    PageAgentAction(
                        type = PageAgentActionType.NAVIGATE,
                        url = "https://example.test/submit",
                        requiresConfirmation = true,
                        confirmation = PageAgentConfirmationRequest(
                            PageAgentActionType.NAVIGATE,
                            "Open the requested page",
                            "This may submit or change page state"
                        )
                    )
                ),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH, message = "Done"))
            )
        )
        val browser = FakeBrowserAdapter()
        val updates = mutableListOf<PageAgentUpdate>()
        val handle = PageAgentCoordinator({ "mock-key" }, provider).start(
            PageAgentRunRequest("open it", browser) { update ->
                synchronized(updates) { updates += update }
                if (update.state == PageAgentUpdateState.CONFIRMATION_REQUIRED) confirmationSeen.countDown()
            }
        )

        assertTrue(confirmationSeen.await(2, TimeUnit.SECONDS))
        assertTrue(browser.actions.isEmpty())
        assertTrue(handle.approve())
        val result = handle.result.get(2, TimeUnit.SECONDS)

        assertEquals(PageAgentTerminalState.SUCCESS, result.state)
        assertEquals(listOf("navigate:https://example.test/submit"), browser.actions)
        assertTrue(updates.any { it.terminalState == PageAgentTerminalState.CONFIRMATION_REQUIRED })
    }

    @Test
    fun `confirmation rejection ends without performing pending action`() {
        val confirmationSeen = CountDownLatch(1)
        val provider = FakeProvider(mutableListOf(
            PageAgentModelTurn(
                PageAgentAction(
                    type = PageAgentActionType.TYPE,
                    text = "secret",
                    requiresConfirmation = true
                )
            )
        ))
        val browser = FakeBrowserAdapter()
        val handle = PageAgentCoordinator({ "mock-key" }, provider).start(
            PageAgentRunRequest("type it", browser) { update ->
                if (update.state == PageAgentUpdateState.CONFIRMATION_REQUIRED) confirmationSeen.countDown()
            }
        )

        assertTrue(confirmationSeen.await(2, TimeUnit.SECONDS))
        assertTrue(handle.reject())
        val result = handle.result.get(2, TimeUnit.SECONDS)

        assertEquals(PageAgentTerminalState.FAILURE, result.state)
        assertEquals(1, result.actionCount)
        assertTrue(browser.actions.isEmpty())
    }

    @Test
    fun `prompt injection warning is propagated and stops before action`() {
        val provider = FakeProvider(mutableListOf(
            PageAgentModelTurn(
                PageAgentAction(PageAgentActionType.FINISH),
                promptInjectionWarning = "The page tried to override the requested task"
            )
        ))
        val browser = FakeBrowserAdapter()

        val result = PageAgentCoordinator({ "mock-key" }, provider).run("safe task", browser)

        assertEquals(PageAgentTerminalState.PROMPT_INJECTION_WARNING, result.state)
        assertEquals("The page tried to override the requested task", result.promptInjectionWarning)
        assertTrue(browser.actions.isEmpty())
    }

    @Test
    fun `finish supports success and partial while handoff preserves its reason`() {
        val success = PageAgentCoordinator(
            { "mock-key" }, FakeProvider(
                mutableListOf(
                    PageAgentModelTurn(
                        PageAgentAction(
                            PageAgentActionType.FINISH,
                            resultState = PageAgentTerminalState.SUCCESS
                        )
                    )
                )
            )
        ).run("done", FakeBrowserAdapter())
        val partial = PageAgentCoordinator(
            { "mock-key" }, FakeProvider(
                mutableListOf(
                    PageAgentModelTurn(
                        PageAgentAction(
                            PageAgentActionType.FINISH,
                            resultState = PageAgentTerminalState.PARTIAL
                        )
                    )
                )
            )
        ).run("partial", FakeBrowserAdapter())
        val handoff = PageAgentCoordinator(
            { "mock-key" }, FakeProvider(
                mutableListOf(
                    PageAgentModelTurn(
                        PageAgentAction(
                            PageAgentActionType.HANDOFF,
                            reason = "This is an app-global request"
                        )
                    )
                )
            )
        ).run("handoff", FakeBrowserAdapter())

        assertEquals(PageAgentTerminalState.SUCCESS, success.state)
        assertEquals(PageAgentTerminalState.PARTIAL, partial.state)
        assertEquals(PageAgentTerminalState.HANDOFF_REQUESTED, handoff.state)
        assertEquals("This is an app-global request", handoff.message)
    }

    @Test
    fun `audio-classified video cannot finish before observed fullscreen succeeds`() {
        val prematureFinish = PageAgentModelTurn(
            action = PageAgentAction(PageAgentActionType.FINISH, message = "Search complete"),
            requiresVideoPlaybackCompletion = true
        )
        val result = PageAgentCoordinator(
            { "mock-key" },
            FakeProvider(MutableList(3) { prematureFinish })
        ).run(
            task = BrowserRoutingCoordinator.RECORDED_VOICE_TASK,
            browser = FakeBrowserAdapter(),
            audio = PageAgentAudio(byteArrayOf(1))
        )

        assertEquals(PageAgentTerminalState.FAILURE, result.state)
        assertTrue(result.message.contains("video playback", ignoreCase = true))
    }

    @Test
    fun `unresolved asynchronous approval is bounded by the run deadline`() {
        val provider = FakeProvider(mutableListOf(
            PageAgentModelTurn(
                PageAgentAction(
                    type = PageAgentActionType.NAVIGATE,
                    url = "https://example.test/sensitive",
                    requiresConfirmation = true
                )
            )
        ))
        val coordinator = PageAgentCoordinator(
            apiKeyProvider = { "mock-key" },
            provider = provider,
            maxRuntimeMs = 80L
        )
        try {
            val handle = coordinator.start(
                PageAgentRunRequest(
                    "open sensitive page",
                    FakeBrowserAdapter()
                )
            )
            val result = handle.result.get(1, TimeUnit.SECONDS)
            assertEquals(PageAgentTerminalState.FAILURE, result.state)
            assertTrue(result.actionResults.isEmpty())
        } finally {
            coordinator.close()
        }
    }

    private class FakeClock(var nowMs: Long = 0L) : PageAgentClock {
        override fun nanoTime(): Long = nowMs * 1_000_000L
        fun advance(amountMs: Long) { nowMs += amountMs }
    }

    private class FakeProvider(
        private val turns: MutableList<PageAgentModelTurn>,
        private val beforeReturn: ((PageAgentTurnRequest) -> Unit)? = null
    ) : PageAgentProvider {
        val requests = mutableListOf<PageAgentTurnRequest>()
        val modelIds = mutableListOf<String>()

        override fun nextTurn(
            apiKey: String,
            modelId: String,
            request: PageAgentTurnRequest
        ): Result<PageAgentModelTurn> {
            requests += request
            modelIds += modelId
            beforeReturn?.invoke(request)
            return turns.removeFirstOrNull()?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("No fake turn configured"))
        }
    }

    private class FakeBrowserAdapter(
        override val binding: PageAgentTargetBinding = PageAgentTargetBinding(
            "browser#bound",
            "tab-1",
            "webview-1"
        )
    ) : PageAgentBrowserAdapter {
        var available = true
        var afterAction: (() -> Unit)? = null
        val actions = mutableListOf<String>()

        override fun isAvailable(): Boolean = available

        override fun snapshot(): Result<PageAgentPageSnapshot> = if (!available) {
            Result.failure(PageAgentTargetUnavailableException("fake target missing"))
        } else {
            Result.success(
                PageAgentPageSnapshot(
                    screenshot = PageAgentScreenshot(byteArrayOf(1, 2, 3), 100, 50),
                    url = "https://example.test",
                    title = "Example",
                    viewportWidth = 200,
                    viewportHeight = 100
                )
            )
        }

        override fun tap(viewportX: Float, viewportY: Float): PageAgentActionResult {
            actions += "tap:$viewportX,$viewportY"
            afterAction?.invoke()
            return success(PageAgentActionType.TAP)
        }

        override fun replaceFocusedText(text: String): PageAgentActionResult {
            actions += "type:$text"
            afterAction?.invoke()
            return success(PageAgentActionType.TYPE)
        }

        override fun scroll(deltaX: Float, deltaY: Float): PageAgentActionResult {
            actions += "scroll:$deltaX,$deltaY"
            afterAction?.invoke()
            return success(PageAgentActionType.SCROLL)
        }

        override fun goBack(): PageAgentActionResult {
            actions += "back"
            afterAction?.invoke()
            return success(PageAgentActionType.BACK)
        }

        override fun navigate(url: String): PageAgentActionResult {
            actions += "navigate:$url"
            afterAction?.invoke()
            return success(PageAgentActionType.NAVIGATE)
        }

        override fun waitForPageChange(timeoutMs: Long, isCancelled: () -> Boolean): PageAgentActionResult {
            actions += "wait:$timeoutMs"
            afterAction?.invoke()
            return if (isCancelled()) {
                PageAgentActionResult(
                    PageAgentActionType.WAIT,
                    PageAgentActionCode.CANCELLED,
                    "cancelled"
                )
            } else success(PageAgentActionType.WAIT)
        }

        override fun requestFullscreen(mode: PageAgentFullscreenMode): PageAgentActionResult {
            actions += "fullscreen:$mode"
            afterAction?.invoke()
            return success(PageAgentActionType.FULLSCREEN)
        }

        private fun success(action: PageAgentActionType) =
            PageAgentActionResult(action, PageAgentActionCode.SUCCESS, "ok")
    }
}
