package com.everyday.everyday_glasses.agent_tools

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everyday.everyday_glasses.controllers.AppCommandResult
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.transport.AssistantControl
import com.everyday.shared.transport.AssistantControlAction
import com.everyday.shared.transport.AssistantSessionOrigin
import com.everyday.shared.transport.AssistantSessionState
import com.everyday.shared.transport.AssistantStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.abs

/** Receives assistant status and configuration changes from [VoiceAssistantCoordinator]. */
interface AssistantStateObserver {
    fun onAssistantStatusChanged(status: AssistantStatus) = Unit
    fun onAssistantConfigurationChanged(
        configuration: AssistantConfiguration?,
        models: List<AssistantModel>,
        detail: String
    ) = Unit
}

class VoiceAssistantCoordinator(
    private val preferences: AssistantPreferencesStore,
    private val provider: VoiceAssistantProvider,
    private val recorder: AssistantAudioRecorder,
    private val hasMicrophonePermission: () -> Boolean,
    private val requestMicrophonePermission: () -> Unit,
    private val toolCatalog: () -> AssistantToolCatalog,
    private val captureUiContext: () -> AssistantUiContext,
    private val stateObserver: AssistantStateObserver,
    private val captureUiContextOnDemand: () -> CompletableFuture<AssistantUiContext> = {
        assistantCompletedFuture(captureUiContext())
    },
    private val requestScreenshot: () -> CompletableFuture<AssistantScreenshot> = {
        assistantFailedFuture(UnsupportedOperationException("Screenshot capture is unavailable"))
    },
    /** Kept for source compatibility; new routing uses registered catalog handlers. */
    @Suppress("UNUSED_PARAMETER")
    private val executeCommand: ((AppCommandInvocation) -> AppCommandResult)? = null,
    private val presentText: (AssistantTextPresentationRequest) -> CompletableFuture<AssistantToolExecution> = {
        assistantCompletedFuture(
            AssistantToolExecution(
                false,
                AssistantToolExecutionCodes.EXECUTION_FAILED,
                "Text presentation is unavailable"
            )
        )
    },
    private val browserRouting: BrowserRoutingCoordinator? = null
) {
    @Suppress("UNUSED_PARAMETER")
    constructor(
        preferences: AssistantPreferencesStore,
        provider: VoiceAssistantProvider,
        recorder: AssistantAudioRecorder,
        hasMicrophonePermission: () -> Boolean,
        requestMicrophonePermission: () -> Unit,
        toolCatalog: () -> AssistantToolCatalog,
        captureUiContext: () -> AssistantUiContext,
        executeCommand: (AppCommandInvocation) -> AppCommandResult,
        stateObserver: AssistantStateObserver
    ) : this(
        preferences,
        provider,
        recorder,
        hasMicrophonePermission,
        requestMicrophonePermission,
        toolCatalog,
        captureUiContext,
        stateObserver,
        { assistantCompletedFuture(captureUiContext()) },
        { assistantFailedFuture(UnsupportedOperationException("Screenshot capture is unavailable")) },
        executeCommand
    )

    companion object {
        private const val TAG = "VoiceAssistant"
        private const val LOCAL_LIMIT_MS = 60_000L
        private const val KEEPALIVE_TIMEOUT_MS = 7_000L
        private const val MIN_AUDIO_BYTES = 700L
        private const val MAX_ROUNDS = AssistantToolLoop.DEFAULT_MAX_ROUNDS
        private const val MAX_COMMANDS = AssistantToolLoop.DEFAULT_MAX_CALLS
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "VoiceAssistant") }
    @Volatile private var configuration = preferences.load()
    @Volatile private var models: List<AssistantModel> = emptyList()
    private var state = if (configuration == null) AssistantSessionState.UNCONFIGURED else AssistantSessionState.IDLE
    private var activeSession: String? = null
    private var activeOrigin: AssistantSessionOrigin? = null
    private var lastKeepalive = 0L
    private var generation = 0

    private val localTimeout = Runnable { activeSession?.let { stop(it, submit = true) } }
    private val keepaliveWatchdog = object : Runnable {
        override fun run() {
            if (activeOrigin != AssistantSessionOrigin.PHONE || state != AssistantSessionState.LISTENING) return
            if (System.currentTimeMillis() - lastKeepalive > KEEPALIVE_TIMEOUT_MS) {
                cancel(activeSession, "Phone keepalive missed")
            } else main.postDelayed(this, 2_000L)
        }
    }

    fun initialize() {
        emitStatus()
        configuration?.let { validateAndResolve(it.apiKey, persistKey = false) }
    }

    fun configureApiKey(apiKey: String) {
        val normalized = apiKey.trim()
        if (normalized.isBlank()) return
        cancel(activeSession, null)
        validateAndResolve(normalized, persistKey = true)
    }

    fun removeApiKey() {
        generation++
        cancel(activeSession, null)
        preferences.clearApiKey()
        configuration = null
        models = emptyList()
        state = AssistantSessionState.UNCONFIGURED
        stateObserver.onAssistantConfigurationChanged(null, emptyList(), "Gemini is off")
        emitStatus("Gemini is off")
    }

    fun selectModel(modelId: String?) {
        val current = configuration ?: return
        val mode = if (modelId == null) AssistantModelSelectionMode.AUTO_RECOMMENDED else AssistantModelSelectionMode.USER_SELECTED
        val selected = modelId ?: AssistantModelPolicy.chooseRecommended(models)?.id ?: current.modelId
        val next = current.copy(modelId = selected, selectionMode = mode)
        preferences.saveModel(selected, mode)
        configuration = next
        validateAndResolve(next.apiKey, persistKey = false)
    }

    fun toggleLocal() {
        if (state == AssistantSessionState.LISTENING && activeOrigin == AssistantSessionOrigin.GLASSES) {
            activeSession?.let { stop(it, submit = true) }
        } else start(UUID.randomUUID().toString(), AssistantSessionOrigin.GLASSES)
    }

    fun handleControl(control: AssistantControl) {
        if (abs(System.currentTimeMillis() - control.timestampMs) > 30_000L) {
            if (control.action != AssistantControlAction.KEEPALIVE) {
                Log.w(TAG, "Ignoring stale ${control.action.wireName} session=${control.sessionId}")
            }
            return
        }
        if (control.action != AssistantControlAction.KEEPALIVE) {
            Log.d(TAG, "RX ${control.action.wireName} session=${control.sessionId} state=${state.wireName}")
        }
        when (control.action) {
            AssistantControlAction.START -> start(control.sessionId, AssistantSessionOrigin.PHONE)
            AssistantControlAction.KEEPALIVE -> if (matches(control.sessionId, AssistantSessionOrigin.PHONE)) lastKeepalive = System.currentTimeMillis()
            AssistantControlAction.STOP -> stop(control.sessionId, submit = true)
            AssistantControlAction.CANCEL -> cancel(control.sessionId, null)
        }
    }

    fun onPhoneDisconnected() {
        if (activeOrigin == AssistantSessionOrigin.PHONE) cancel(activeSession, "Phone disconnected")
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) emitStatus("Gemini ready") else fail("Microphone permission denied")
    }

    fun currentStatus(): AssistantStatus = status()

    fun release() {
        generation++
        cancel(activeSession, null)
        worker.shutdownNow()
    }

    private fun validateAndResolve(apiKey: String, persistKey: Boolean) {
        val token = ++generation
        state = AssistantSessionState.PROCESSING
        emitStatus("Validating Gemini…")
        worker.execute {
            val available = provider.listModels(apiKey).getOrElse {
                main.post { if (token == generation) fail("Gemini validation failed") }
                return@execute
            }
            val prior = configuration
            val mode = prior?.selectionMode ?: AssistantModelSelectionMode.AUTO_RECOMMENDED
            val selected = if (mode == AssistantModelSelectionMode.USER_SELECTED) {
                prior?.modelId?.takeIf { id ->
                    available.any { it.id == id } && (
                        preferences.isCapabilityProbed(id) || provider.probeAudioTools(apiKey, id).onSuccess {
                            preferences.markCapabilityProbed(id)
                        }.isSuccess
                    )
                }
            } else {
                AssistantModelPolicy.recommendedCandidates(available).firstOrNull { candidate ->
                    preferences.isCapabilityProbed(candidate.id) ||
                        provider.probeAudioTools(apiKey, candidate.id).onSuccess {
                            preferences.markCapabilityProbed(candidate.id)
                        }.isSuccess
                }?.id
            }
            main.post {
                if (token != generation) return@post
                models = available
                if (selected == null) {
                    state = AssistantSessionState.ERROR
                    stateObserver.onAssistantConfigurationChanged(
                        configuration,
                        models,
                        "Select a compatible Gemini model"
                    )
                    emitStatus("No compatible model")
                    return@post
                }
                val next = AssistantConfiguration(apiKey, selected, mode)
                val saved = if (persistKey) preferences.save(next) else preferences.saveModel(selected, mode)
                if (!saved && persistKey) {
                    fail("Secure key storage unavailable")
                    return@post
                }
                configuration = next
                state = AssistantSessionState.IDLE
                stateObserver.onAssistantConfigurationChanged(next, models, "Gemini ready")
                if (!hasMicrophonePermission()) requestMicrophonePermission()
                emitStatus(if (hasMicrophonePermission()) "Gemini ready" else "Microphone permission required")
            }
        }
    }

    private fun start(sessionId: String, origin: AssistantSessionOrigin) {
        if (state == AssistantSessionState.LISTENING || state == AssistantSessionState.PROCESSING) return
        if (configuration == null || !preferences.isSecureStorageAvailable) {
            fail("Gemini is not configured", origin, sessionId)
            return
        }
        if (!hasMicrophonePermission()) {
            requestMicrophonePermission()
            fail("Microphone permission required", origin, sessionId)
            return
        }
        recorder.start().onFailure { failure ->
            Log.e(TAG, "Could not start microphone session=$sessionId", failure)
            fail("Could not start microphone", origin, sessionId)
        }.onSuccess {
            activeSession = sessionId
            activeOrigin = origin
            state = AssistantSessionState.LISTENING
            lastKeepalive = System.currentTimeMillis()
            if (origin == AssistantSessionOrigin.GLASSES) main.postDelayed(localTimeout, LOCAL_LIMIT_MS)
            else main.postDelayed(keepaliveWatchdog, 2_000L)
            emitStatus("Listening")
        }
    }

    private fun stop(sessionId: String, submit: Boolean) {
        if (!matches(sessionId, activeOrigin)) {
            Log.w(TAG, "Ignoring stop session=$sessionId active=$activeSession state=${state.wireName}")
            return
        }
        main.removeCallbacks(localTimeout)
        main.removeCallbacks(keepaliveWatchdog)
        if (!submit) {
            cancel(sessionId, null)
            return
        }
        val origin = activeOrigin
        val id = activeSession
        val file = recorder.stop().getOrElse { failure ->
            Log.e(TAG, "Recording stop failed session=$id", failure)
            clearSession()
            fail("Recording failed", origin, id)
            return
        }
        clearSession(keepIdentity = true)
        if (file.length() < MIN_AUDIO_BYTES) {
            file.delete()
            clearSession()
            fail("No speech recorded", origin, id)
            return
        }
        state = AssistantSessionState.PROCESSING
        emitStatus("Processing")

        val routeToken = generation
        val route = browserRouting?.routeRecordedAudio(
            audioFile = file,
            onUpdate = { update ->
                if (update.state == PageAgentUpdateState.CONFIRMATION_REQUIRED) {
                    main.post {
                        if (routeToken == generation) {
                            emitStatus("Browser action needs approval", origin, id)
                        }
                    }
                }
            },
            onComplete = { result ->
                handlePageAgentResult(file, origin, id, routeToken, result)
            }
        )
        if (route != null) return

        process(file, origin, id, captureUiContext())
    }

    /** Finish a direct page route, or hand the unchanged recording to the main assistant once. */
    private fun handlePageAgentResult(
        file: File,
        origin: AssistantSessionOrigin?,
        sessionId: String?,
        routeToken: Int,
        result: PageAgentResult
    ) {
        main.post {
            if (routeToken != generation) {
                file.delete()
                return@post
            }

            when (result.state) {
                PageAgentTerminalState.HANDOFF_REQUESTED -> {
                    // The original File is passed directly to process; no transcription or
                    // rewritten prompt is inserted between the Page Agent and main assistant.
                    state = AssistantSessionState.PROCESSING
                    emitStatus("Processing", origin, sessionId)
                    process(file, origin, sessionId, captureUiContext())
                }
                PageAgentTerminalState.TARGET_UNAVAILABLE -> {
                    if (result.actionCount == 0) {
                        // The bound browser vanished before the Page Agent could perform a page
                        // action. Retry the main assistant once, with the same recording.
                        state = AssistantSessionState.PROCESSING
                        emitStatus("Processing", origin, sessionId)
                        process(file, origin, sessionId, captureUiContext())
                    } else {
                        file.delete()
                        state = AssistantSessionState.ERROR
                        emitStatus(result.message.take(240), origin, sessionId)
                        scheduleIdle()
                    }
                }
                PageAgentTerminalState.SUCCESS,
                PageAgentTerminalState.PARTIAL -> {
                    file.delete()
                    state = AssistantSessionState.SUCCESS
                    emitStatus(
                        if (result.state == PageAgentTerminalState.SUCCESS) "Done" else "Partially completed",
                        origin,
                        sessionId
                    )
                    scheduleIdle()
                }
                else -> {
                    file.delete()
                    state = AssistantSessionState.ERROR
                    emitStatus(result.message.take(240), origin, sessionId)
                    scheduleIdle()
                }
            }
        }
    }

    private fun process(
        file: File,
        origin: AssistantSessionOrigin?,
        sessionId: String?,
        uiContext: AssistantUiContext
    ) {
        val config = configuration ?: run { file.delete(); fail("Gemini is not configured"); return }
        val token = generation
        worker.execute {
            var uploadedFileName: String? = null
            try {
                val catalog = toolCatalog()
                val turn = provider.startInteraction(
                    config.apiKey,
                    config.modelId,
                    file,
                    catalog.declarations(),
                    uiContext
                ).getOrThrow()
                uploadedFileName = turn.uploadedFileName
                val loop = AssistantToolLoop(
                    provider = provider,
                    apiKey = config.apiKey,
                    modelId = config.modelId,
                    refreshCatalog = toolCatalog,
                    context = AssistantToolExecutionContext(
                        uiContext = uiContext,
                        captureUiContext = captureUiContextOnDemand,
                        requestScreenshot = requestScreenshot,
                        presentText = presentText
                    ),
                    shouldContinue = { token == generation },
                    maxRounds = MAX_ROUNDS,
                    maxCalls = MAX_COMMANDS
                )
                val outcome = loop.run(catalog, turn)
                val presentationResult = outcome.answer?.let { answer ->
                    runCatching {
                        presentText(AssistantTextPresentationPolicy.forAnswer(answer)).get()
                    }.getOrElse { failure ->
                        AssistantToolExecution(
                            false,
                            AssistantToolExecutionCodes.EXECUTION_FAILED,
                            failure.message ?: "Text presentation failed"
                        )
                    }
                }
                main.post {
                    if (token != generation) return@post
                    val answerPresented = presentationResult?.success == true
                    val completed = outcome.anySuccess || answerPresented
                    state = if (completed) AssistantSessionState.SUCCESS else AssistantSessionState.ERROR
                    emitStatus(
                        when {
                            answerPresented -> "Done"
                            presentationResult != null -> "Could not show answer"
                            outcome.anySuccess -> "Done"
                            outcome.searchFailed -> "Search unavailable"
                            outcome.ungroundedAnswerRejected -> "Answer could not be verified"
                            outcome.recognized -> "Command failed"
                            else -> "No command recognized"
                        }, origin, sessionId
                    )
                    scheduleIdle()
                }
            } catch (failure: Throwable) {
                val rootCause = generateSequence(failure) { it.cause }.last()
                Log.e(
                    TAG,
                    "Gemini processing failed session=$sessionId model=${config.modelId}: " +
                        (rootCause.message ?: rootCause.javaClass.simpleName),
                    failure
                )
                main.post { if (token == generation) fail("Gemini request failed", origin, sessionId) }
            } finally {
                uploadedFileName?.let { provider.deleteUploadedFile(config.apiKey, it) }
                file.delete()
            }
        }
    }

    private fun cancel(sessionId: String?, detail: String?) {
        if (sessionId != null && activeSession != null && sessionId != activeSession) return
        if (state == AssistantSessionState.PROCESSING && activeSession != null) generation++
        if (state == AssistantSessionState.PROCESSING) browserRouting?.cancelActiveRuns()
        main.removeCallbacks(localTimeout)
        main.removeCallbacks(keepaliveWatchdog)
        recorder.cancel()
        val origin = activeOrigin
        val id = activeSession
        clearSession()
        state = if (configuration == null) AssistantSessionState.UNCONFIGURED else AssistantSessionState.IDLE
        if (detail != null) emitStatus(detail, origin, id) else emitStatus()
    }

    private fun fail(detail: String, origin: AssistantSessionOrigin? = activeOrigin, sessionId: String? = activeSession) {
        state = AssistantSessionState.ERROR
        emitStatus(detail, origin, sessionId)
        scheduleIdle()
    }

    private fun scheduleIdle() {
        main.postDelayed({
            if (state == AssistantSessionState.ERROR || state == AssistantSessionState.SUCCESS) {
                state = if (configuration == null) AssistantSessionState.UNCONFIGURED else AssistantSessionState.IDLE
                clearSession()
                emitStatus()
            }
        }, 2_000L)
    }

    private fun matches(sessionId: String, origin: AssistantSessionOrigin?): Boolean =
        state == AssistantSessionState.LISTENING && activeSession == sessionId && activeOrigin == origin

    private fun clearSession(keepIdentity: Boolean = false) {
        if (!keepIdentity) {
            activeSession = null
            activeOrigin = null
        }
    }

    private fun status(detail: String? = null, origin: AssistantSessionOrigin? = activeOrigin, id: String? = activeSession) = AssistantStatus(
        configured = configuration != null,
        ready = configuration != null && preferences.isSecureStorageAvailable && hasMicrophonePermission() && state != AssistantSessionState.ERROR,
        state = state,
        sessionId = id,
        origin = origin,
        detail = detail,
        timestampMs = System.currentTimeMillis()
    )

    private fun emitStatus(detail: String? = null, origin: AssistantSessionOrigin? = activeOrigin, id: String? = activeSession) {
        Log.d(
            TAG,
            "TX status state=${state.wireName} session=${id ?: "-"} " +
                "origin=${origin?.wireName ?: "-"} detail=${detail ?: "-"}"
        )
        stateObserver.onAssistantStatusChanged(status(detail, origin, id))
    }
}
