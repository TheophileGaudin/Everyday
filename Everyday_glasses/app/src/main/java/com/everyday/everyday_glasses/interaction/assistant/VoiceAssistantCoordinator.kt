package com.everyday.everyday_glasses.interaction.assistant

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.everyday.everyday_glasses.interaction.assistant.page_agent.BrowserRoutingCoordinator
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentResult
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentTerminalState
import com.everyday.everyday_glasses.interaction.assistant.page_agent.PageAgentUpdateState
import com.everyday.everyday_glasses.interaction.assistant.AssistantTextPresentationPolicy
import com.everyday.everyday_glasses.interaction.assistant.AssistantTextPresentationRequest
import com.everyday.everyday_glasses.interaction.commands.AppCommandResult
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
        /** How long an error stays shown before the next step (and the idle state). */
        private const val ERROR_DISPLAY_MS = 2_000L
        private const val MAX_REASON_CHARS = 120
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

    private val localTimeout = Runnable { activeSession?.let { stop(it) } }
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
            activeSession?.let { stop(it) }
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
            AssistantControlAction.STOP -> stop(control.sessionId)
            AssistantControlAction.CANCEL -> cancel(control.sessionId, null)
        }
    }

    /** The phone connected: a key that couldn't be checked for lack of internet is checked now, through it if needed. */
    fun onPhoneConnected() {
        if (!checkWhenOnline) return
        configuration?.let { validateAndResolve(it.apiKey, persistKey = false) }
    }

    /** True when the stored key couldn't be checked because there was no internet. */
    private var checkWhenOnline = false

    /**
     * Without internet (the glasses offline and the phone not connected yet), a stored key stays
     * stored and shown as such, and is checked again later; a key just pasted can't be checked, so
     * it isn't saved.
     */
    private fun onValidationFailed(failure: Throwable, pastedKey: Boolean) {
        val offline = failure is java.io.IOException
        checkWhenOnline = offline
        val stored = configuration
        if (offline && stored != null) {
            stateObserver.onAssistantConfigurationChanged(stored, models, "Gemini key stored. No internet yet")
        }
        when {
            offline && pastedKey -> fail("No internet: the key could not be checked. ${shortReason(failure)}")
            offline && stored != null -> {
                state = AssistantSessionState.IDLE
                emitStatus("Gemini key stored. No internet yet")
            }
            else -> fail("Gemini validation failed. ${shortReason(failure)}")
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
            val available = provider.listModels(apiKey).getOrElse { failure ->
                Log.w(TAG, "Gemini validation failed: ${failure.message}")
                main.post { if (token == generation) onValidationFailed(failure, persistKey) }
                return@execute
            }
            val prior = configuration
            val mode = prior?.selectionMode ?: AssistantModelSelectionMode.AUTO_RECOMMENDED
            Log.d(TAG, "Gemini models (${available.size}): ${available.joinToString { it.id }}; mode=$mode, saved=${prior?.modelId}")
            // A model is usable when Google lists it for this key and it passes a short audio-and-tools
            // check. A retired model fails the check, and Google's answer may name its replacement.
            var suggestedReplacement: String? = null
            fun usable(id: String): Boolean = available.any { it.id == id } && (
                preferences.isCapabilityProbed(id) || provider.probeAudioTools(apiKey, id)
                    .onSuccess { preferences.markCapabilityProbed(id) }
                    .onFailure { failure ->
                        Log.w(TAG, "Model $id failed its check: ${failure.message}")
                        suggestedReplacement = suggestedReplacement ?: replacementNamedIn(failure)
                    }
                    .isSuccess
            )
            // The user's model is kept while Google lists it and it works. Once it's retired, the user
            // keeps a model of the same kind: the replacement Google names, else the newest of its family
            // that works. With no choice of the user's, or none of its kind left, the recommended model.
            val userModel = prior?.modelId?.takeIf { mode == AssistantModelSelectionMode.USER_SELECTED }
            val userChoice = userModel?.takeIf(::usable)
            val equivalent = if (userModel != null && userChoice == null) {
                (listOfNotNull(suggestedReplacement) + AssistantModelPolicy.sameFamilyNewestFirst(available, userModel).map { it.id })
                    .distinct()
                    .firstOrNull { it != userModel && usable(it) }
            } else null
            val selected = userChoice ?: equivalent
                ?: AssistantModelPolicy.recommendedCandidates(available).firstOrNull { usable(it.id) }?.id
            val selectedMode = if (userChoice != null || equivalent != null) mode else AssistantModelSelectionMode.AUTO_RECOMMENDED
            val replacedChoice = userModel?.takeIf { userChoice == null }
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
                val next = AssistantConfiguration(apiKey, selected, selectedMode)
                val saved = if (persistKey) preferences.save(next) else preferences.saveModel(selected, selectedMode)
                if (!saved && persistKey) {
                    fail("Secure key storage unavailable")
                    return@post
                }
                configuration = next
                checkWhenOnline = false
                state = AssistantSessionState.IDLE
                val ready = replacedChoice?.let { "Gemini ready: $it is unavailable, using $selected" } ?: "Gemini ready"
                if (replacedChoice != null) Log.w(TAG, ready)
                stateObserver.onAssistantConfigurationChanged(next, models, ready)
                if (!hasMicrophonePermission()) requestMicrophonePermission()
                emitStatus(if (hasMicrophonePermission()) ready else "Microphone permission required")
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

    private fun stop(sessionId: String) {
        if (!matches(sessionId, activeOrigin)) {
            Log.w(TAG, "Ignoring stop session=$sessionId active=$activeSession state=${state.wireName}")
            return
        }
        main.removeCallbacks(localTimeout)
        main.removeCallbacks(keepaliveWatchdog)

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
                val modelGone = isModelUnavailable(rootCause)
                main.post {
                    if (token != generation) return@post
                    if (modelGone) {
                        fail("Model ${config.modelId} unavailable, choosing another. ${shortReason(rootCause)}", origin, sessionId)
                        replaceRetiredModel(config)
                    } else {
                        fail("Gemini request failed. ${shortReason(rootCause)}", origin, sessionId)
                    }
                }
            } finally {
                uploadedFileName?.let { provider.deleteUploadedFile(config.apiKey, it) }
                file.delete()
            }
        }
    }

    /**
     * Google retired the model (it answers 404 for it): forget that it passed its check and validate
     * again, which picks its newest equivalent (or the recommended model).
     */
    private fun replaceRetiredModel(config: AssistantConfiguration) {
        preferences.forgetCapabilityProbe(config.modelId)
        main.postDelayed({ configuration?.let { validateAndResolve(it.apiKey, persistKey = false) } }, ERROR_DISPLAY_MS)
    }

    /** The model Google names as the replacement in a "no longer available" answer, if any. */
    private fun replacementNamedIn(failure: Throwable): String? =
        Regex("""use models/([A-Za-z0-9._-]+)""").find(failure.message.orEmpty())?.groupValues?.get(1)

    private fun isModelUnavailable(failure: Throwable): Boolean {
        val message = failure.message.orEmpty()
        return message.contains("(404)") && message.contains("model", ignoreCase = true)
    }

    /** A short clue for the phone's toast: "HTTP 404: This model …", or the failure's message. */
    private fun shortReason(failure: Throwable): String {
        val message = failure.message ?: failure.javaClass.simpleName
        val http = Regex("""\((\d{3})\)(?::\s*(.*))?""").find(message)
        val reason = if (http != null) "HTTP ${http.groupValues[1]}: ${http.groupValues[2]}".trimEnd(' ', ':') else message
        return reason.take(MAX_REASON_CHARS)
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
        }, ERROR_DISPLAY_MS)
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
