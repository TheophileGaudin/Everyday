package com.everyday.everyday_glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BrowserRoutingTest {
    @Test
    fun `fullscreen voice route sends raw audio to page agent without main assistant call`() {
        val audioBytes = byteArrayOf(0, 1, 2, 3, 4, 5)
        val audioFile = tempAudio(audioBytes)
        val provider = FakePageProvider(mutableListOf(PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))))
        val browser = FakeBrowserAdapter()
        val host = FakeRoutingHost(fullscreen = browser)
        val pageAgent = PageAgentCoordinator({ "page-key" }, provider)
        val routing = BrowserRoutingCoordinator(pageAgent, host, ImmediatePageAgentUiDispatcher())
        var mainAssistantCalls = 0

        try {
            val start = routing.routeRecordedAudio(audioFile, onComplete = { result ->
                assertEquals(PageAgentTerminalState.SUCCESS, result.state)
            })

            assertNotNull(start)
            start!!.handle.result.get(2, TimeUnit.SECONDS)
            assertEquals(0, mainAssistantCalls)
            assertEquals(1, provider.requests.size)
            assertTrue(provider.requests.single().audio?.bytes.contentEquals(audioBytes) == true)
            assertEquals(BrowserRoutingCoordinator.RECORDED_VOICE_TASK, provider.requests.single().originalTask)
        } finally {
            routing.close()
            audioFile.delete()
        }
    }

    @Test
    fun `page handoff is a single main-assistant fallback with unchanged audio`() {
        val audioBytes = byteArrayOf(9, 8, 7, 6)
        val audioFile = tempAudio(audioBytes)
        val provider = FakePageProvider(
            mutableListOf(
                PageAgentModelTurn(
                    PageAgentAction(PageAgentActionType.HANDOFF, reason = "The request is app-global")
                )
            )
        )
        val routing = BrowserRoutingCoordinator(
            PageAgentCoordinator({ "page-key" }, provider),
            FakeRoutingHost(fullscreen = FakeBrowserAdapter()),
            ImmediatePageAgentUiDispatcher()
        )
        var mainAssistantCalls = 0
        var fallbackBytes: ByteArray? = null
        val completion = CountDownLatch(1)

        try {
            val start = routing.routeRecordedAudio(audioFile, onComplete = { result ->
                if (BrowserRoutingCoordinator.shouldFallbackToMain(result)) {
                    mainAssistantCalls++
                    fallbackBytes = audioFile.readBytes()
                }
                completion.countDown()
            })
            assertNotNull(start)
            start!!.handle.result.get(2, TimeUnit.SECONDS)
            assertTrue(completion.await(2, TimeUnit.SECONDS))

            assertEquals(1, mainAssistantCalls)
            assertTrue(fallbackBytes!!.contentEquals(audioBytes))
            assertTrue(audioFile.exists())
        } finally {
            routing.close()
            audioFile.delete()
        }
    }

    @Test
    fun `target missing before page-agent execution falls back but disappearance after action is terminal`() {
        val beforeHost = FakeRoutingHost(fullscreen = FakeBrowserAdapter().apply { available = false })
        val beforeRouting = BrowserRoutingCoordinator(
            PageAgentCoordinator({ "page-key" }, FakePageProvider(mutableListOf())),
            beforeHost,
            ImmediatePageAgentUiDispatcher()
        )
        val beforeFile = tempAudio(byteArrayOf(1))
        try {
            val started = beforeRouting.routeRecordedAudio(beforeFile, onComplete = {})
            assertEquals(null, started)
            assertTrue(beforeHost.createCalls == 0)
        } finally {
            beforeRouting.close()
            beforeFile.delete()
        }

        val afterBrowser = FakeBrowserAdapter().apply { afterActionMakesUnavailable = true }
        val afterRouting = BrowserRoutingCoordinator(
            PageAgentCoordinator(
                { "page-key" },
                FakePageProvider(
                    mutableListOf(
                        PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 1f, y = 1f)),
                        PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))
                    )
                )
            ),
            FakeRoutingHost(fullscreen = afterBrowser),
            ImmediatePageAgentUiDispatcher()
        )
        val afterFile = tempAudio(byteArrayOf(2))
        try {
            val start = afterRouting.routeRecordedAudio(afterFile, onComplete = { result ->
                assertEquals(PageAgentTerminalState.TARGET_UNAVAILABLE, result.state)
                assertFalse(BrowserRoutingCoordinator.shouldFallbackToMain(result))
            })
            assertNotNull(start)
            assertEquals(PageAgentTerminalState.TARGET_UNAVAILABLE, start!!.handle.result.get(2, TimeUnit.SECONDS).state)
        } finally {
            afterRouting.close()
            afterFile.delete()
        }
    }

    @Test
    fun `run_browser_task uses an existing browser and creates one only when absent`() {
        val existingProvider = FakePageProvider(mutableListOf(PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))))
        val existing = FakeBrowserAdapter(PageAgentTargetBinding("browser#existing", "tab", "webview"))
        val existingHost = FakeRoutingHost(existing = existing)
        val existingRouting = BrowserRoutingCoordinator(
            PageAgentCoordinator({ "page-key" }, existingProvider),
            existingHost,
            ImmediatePageAgentUiDispatcher()
        )
        try {
            val existingResult = existingRouting.runBrowserTask("open the requested page").get(2, TimeUnit.SECONDS)
            assertTrue(existingResult.success)
            assertEquals(0, existingHost.createCalls)
        } finally {
            existingRouting.close()
        }

        val createdProvider = FakePageProvider(
            mutableListOf(
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FULLSCREEN, fullscreenMode = PageAgentFullscreenMode.VIDEO)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))
            )
        )
        val created = FakeBrowserAdapter(PageAgentTargetBinding("browser#created", "tab", "webview"))
        val createdHost = FakeRoutingHost(create = created)
        val createdRouting = BrowserRoutingCoordinator(
            PageAgentCoordinator({ "page-key" }, createdProvider),
            createdHost,
            ImmediatePageAgentUiDispatcher()
        )
        try {
            val createdResult = createdRouting.runBrowserTask("play the requested online video").get(2, TimeUnit.SECONDS)
            assertTrue(createdResult.success)
            assertEquals(1, createdHost.createCalls)
            assertEquals("play the requested online video", createdProvider.requests.first().originalTask)
        } finally {
            createdRouting.close()
        }
    }

    @Test
    fun `simulated youtube task searches corrects selects plays and requests video fullscreen`() {
        val provider = FakePageProvider(
            mutableListOf(
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 10f, y = 10f)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TYPE, text = "better query")),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH, message = "Search field filled")),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 20f, y = 20f)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.WAIT, waitMs = 1L)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.TAP, x = 30f, y = 30f)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FULLSCREEN, fullscreenMode = PageAgentFullscreenMode.VIDEO)),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH, message = "Playing fullscreen"))
            )
        )
        val browser = FakeBrowserAdapter()
        val routing = BrowserRoutingCoordinator(
            PageAgentCoordinator({ "page-key" }, provider),
            FakeRoutingHost(existing = browser),
            ImmediatePageAgentUiDispatcher()
        )
        try {
            val result = routing.runBrowserTask("play the online video fullscreen").get(2, TimeUnit.SECONDS)
            assertTrue(result.success)
            assertEquals(
                listOf("tap:10.0,10.0", "type:better query", "tap:20.0,20.0", "wait:1", "tap:30.0,30.0", "fullscreen:VIDEO"),
                browser.actions
            )
            assertTrue(provider.requests.size > 1)
        } finally {
            routing.close()
        }
    }

    @Test
    fun `successful browser tool completes without a main-assistant summary turn`() {
        val pageProvider = FakePageProvider(
            mutableListOf(
                PageAgentModelTurn(
                    PageAgentAction(
                        PageAgentActionType.FULLSCREEN,
                        fullscreenMode = PageAgentFullscreenMode.VIDEO
                    )
                ),
                PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH, message = "Playing"))
            )
        )
        val routing = BrowserRoutingCoordinator(
            PageAgentCoordinator({ "page-key" }, pageProvider),
            FakeRoutingHost(existing = FakeBrowserAdapter()),
            ImmediatePageAgentUiDispatcher()
        )
        var mainContinuationCalls = 0
        val mainProvider = object : VoiceAssistantProvider {
            override fun listModels(apiKey: String): Result<List<AssistantModel>> = Result.success(emptyList())
            override fun probeAudioTools(apiKey: String, modelId: String): Result<Unit> = Result.success(Unit)
            override fun startInteraction(
                apiKey: String,
                modelId: String,
                audioFile: File,
                tools: List<AssistantToolDefinition>,
                uiContext: AssistantUiContext
            ): Result<AssistantProviderTurn> = error("startInteraction is not needed for this loop test")

            override fun continueInteraction(
                apiKey: String,
                modelId: String,
                previousInteractionId: String,
                tools: List<AssistantToolDefinition>,
                results: List<Pair<AssistantToolCall, AssistantToolExecution>>
            ): Result<AssistantProviderTurn> {
                mainContinuationCalls++
                return Result.success(AssistantProviderTurn(previousInteractionId, emptyList()))
            }

            override fun deleteUploadedFile(apiKey: String, name: String) = Unit
        }
        val catalog = AssistantToolCatalog(listOf(RunBrowserTaskSemanticToolHandler(routing)))
        val context = AssistantToolExecutionContext(
            uiContext = AssistantUiContext(640, 480, null, null, emptyList())
        )
        try {
            val outcome = AssistantToolLoop(
                provider = mainProvider,
                apiKey = "main-key",
                modelId = "main-model",
                refreshCatalog = { catalog },
                context = context
            ).run(
                initialCatalog = catalog,
                initialTurn = AssistantProviderTurn(
                    interactionId = "main-interaction",
                    calls = listOf(
                        AssistantToolCall(
                            id = "browser-call",
                            name = "run_browser_task",
                            arguments = JSONObject().put("task", "play the requested video")
                        )
                    )
                )
            )

            assertTrue(outcome.anySuccess)
            assertEquals(0, mainContinuationCalls)
        } finally {
            routing.close()
        }
    }

    @Test
    fun `video policy distinguishes internet defaults from local playback cues`() {
        assertTrue(BrowserTaskPolicy.defaultsToInternetVideo("play a video about rain"))
        assertEquals("YouTube", BrowserTaskPolicy.defaultVideoSite("watch the requested clip"))
        assertTrue(BrowserTaskPolicy.isLocalPlaybackRequest("play the downloaded file C:\\Movies\\rain.mp4"))
        assertFalse(BrowserTaskPolicy.defaultsToInternetVideo("play the downloaded file C:\\Movies\\rain.mp4"))
    }

    private fun tempAudio(bytes: ByteArray): File = File.createTempFile("browser-routing-", ".aac").also {
        it.writeBytes(bytes)
    }

    private class ImmediatePageAgentUiDispatcher : PageAgentUiDispatcher {
        override fun <T> call(action: () -> T): T = action()
    }

    private class FakeRoutingHost(
        private val fullscreen: PageAgentBrowserAdapter? = null,
        private val existing: PageAgentBrowserAdapter? = null,
        private val create: PageAgentBrowserAdapter? = null
    ) : BrowserRoutingHost {
        var createCalls = 0

        override fun bindFullscreenBrowser(): PageAgentBrowserAdapter? = fullscreen

        override fun bindBrowser(widgetId: String?): PageAgentBrowserAdapter? = existing

        override fun createBrowser(): PageAgentBrowserAdapter? {
            createCalls++
            return create
        }
    }

    private class FakePageProvider(
        private val turns: MutableList<PageAgentModelTurn>
    ) : PageAgentProvider {
        val requests = mutableListOf<PageAgentTurnRequest>()

        override fun nextTurn(
            apiKey: String,
            modelId: String,
            request: PageAgentTurnRequest
        ): Result<PageAgentModelTurn> {
            requests += request
            return Result.success(
                if (turns.isNotEmpty()) turns.removeAt(0)
                else PageAgentModelTurn(PageAgentAction(PageAgentActionType.FINISH))
            )
        }
    }

    private class FakeBrowserAdapter(
        override val binding: PageAgentTargetBinding = PageAgentTargetBinding("browser#bound", "tab", "webview")
    ) : PageAgentBrowserAdapter {
        var available = true
        var afterActionMakesUnavailable = false
        val actions = mutableListOf<String>()

        override fun isAvailable(): Boolean = available

        override fun snapshot(): Result<PageAgentPageSnapshot> = if (available) {
            Result.success(
                PageAgentPageSnapshot(
                    screenshot = PageAgentScreenshot(byteArrayOf(1), 100, 50),
                    url = "https://www.youtube.com/results",
                    title = "YouTube",
                    viewportWidth = 100,
                    viewportHeight = 50
                )
            )
        } else {
            Result.failure(PageAgentTargetUnavailableException("fake target missing"))
        }

        override fun tap(viewportX: Float, viewportY: Float): PageAgentActionResult {
            actions += "tap:$viewportX,$viewportY"
            if (afterActionMakesUnavailable) available = false
            return success(PageAgentActionType.TAP)
        }

        override fun replaceFocusedText(text: String): PageAgentActionResult {
            actions += "type:$text"
            return success(PageAgentActionType.TYPE)
        }

        override fun scroll(deltaX: Float, deltaY: Float): PageAgentActionResult {
            actions += "scroll:$deltaX,$deltaY"
            return success(PageAgentActionType.SCROLL)
        }

        override fun goBack(): PageAgentActionResult {
            actions += "back"
            return success(PageAgentActionType.BACK)
        }

        override fun navigate(url: String): PageAgentActionResult {
            actions += "navigate:$url"
            return success(PageAgentActionType.NAVIGATE)
        }

        override fun waitForPageChange(timeoutMs: Long, isCancelled: () -> Boolean): PageAgentActionResult {
            actions += "wait:$timeoutMs"
            return if (isCancelled()) {
                PageAgentActionResult(PageAgentActionType.WAIT, PageAgentActionCode.CANCELLED, "cancelled")
            } else {
                success(PageAgentActionType.WAIT)
            }
        }

        override fun requestFullscreen(mode: PageAgentFullscreenMode): PageAgentActionResult {
            actions += "fullscreen:$mode"
            return success(PageAgentActionType.FULLSCREEN)
        }

        private fun success(action: PageAgentActionType) =
            PageAgentActionResult(action, PageAgentActionCode.SUCCESS, "ok")
    }
}
