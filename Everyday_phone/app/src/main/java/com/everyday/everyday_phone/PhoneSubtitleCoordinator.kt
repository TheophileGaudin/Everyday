package com.everyday.everyday_phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import androidx.core.content.ContextCompat
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleControlAction
import com.everyday.shared.sync.SubtitleOptions
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleStatusState
import com.everyday.shared.sync.SubtitleTranscript
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val SUBTITLE_SAMPLE_RATE = 16_000

class PhoneSubtitleCoordinator(
    private val context: Context,
    private val serverProvider: () -> RfcommServer?,
    private val projectionProvider: () -> MediaProjection?,
    private val requestProjectionPermission: () -> Unit,
    private val requestAudioPermission: () -> Unit,
    private val onCaptureStopped: () -> Unit
) {

    companion object {
        private const val SAMPLE_RATE = SUBTITLE_SAMPLE_RATE
        private const val PARTIAL_THROTTLE_MS = 250L
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)
    private val modelManager = SubtitleModelManager(context)

    private var pendingStartOptions: SubtitleOptions? = null
    private var currentOptions = SubtitleOptions()
    private var sessionId: String = ""
    private var nextSegmentId = 1L
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var transcriber: SubtitleTranscriber? = null
    private var lastPartialSentAtMs = 0L
    private var lastPartialText = ""
    @Volatile private var startRequestId = 0L

    fun onControl(control: SubtitleControl) {
        fileLog("Subtitle control received: action=${control.action.wireName}")
        when (control.action) {
            SubtitleControlAction.START -> start(control.options)
            SubtitleControlAction.STOP -> stop(sendStopped = true)
            SubtitleControlAction.SET_OPTIONS -> {
                currentOptions = control.options
                if (running.get()) {
                    sendStatus(SubtitleStatusState.LISTENING, "Options updated")
                }
            }
        }
    }

    fun onAudioPermissionResult(granted: Boolean) {
        if (!granted) {
            pendingStartOptions = null
            sendStatus(SubtitleStatusState.PERMISSION_NEEDED, "Audio recording permission denied")
            return
        }
        pendingStartOptions?.let { start(it) }
    }

    fun onProjectionPermissionResult(granted: Boolean) {
        if (!granted) {
            pendingStartOptions = null
            sendStatus(SubtitleStatusState.PERMISSION_NEEDED, "Playback capture permission denied")
            return
        }
        pendingStartOptions?.let { start(it) }
    }

    fun onMediaProjectionReady() {
        pendingStartOptions?.let { start(it) }
    }

    fun isActive(): Boolean = running.get()

    fun stop() {
        stop(sendStopped = true)
    }

    fun release() {
        stop(sendStopped = false)
        worker.shutdownNow()
    }

    private fun start(options: SubtitleOptions) {
        fileLog("Subtitle start requested")
        currentOptions = options.copy(
            languageTag = "en-US",
            phonePlaybackEnabled = true,
            microphoneEnabled = false,
            translationEnabled = false
        )

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            sendStatus(SubtitleStatusState.ERROR, "Playback capture requires Android 10 or newer")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fileLog("Subtitle start waiting for RECORD_AUDIO permission")
            pendingStartOptions = currentOptions
            sendStatus(SubtitleStatusState.PERMISSION_NEEDED, "Audio recording permission needed for playback capture")
            requestAudioPermission()
            return
        }

        val projection = projectionProvider()
        if (projection == null) {
            fileLog("Subtitle start waiting for MediaProjection permission")
            pendingStartOptions = currentOptions
            sendStatus(SubtitleStatusState.PERMISSION_NEEDED, "Playback capture permission needed")
            requestProjectionPermission()
            return
        }

        pendingStartOptions = null
        if (running.get()) {
            sendStatus(SubtitleStatusState.LISTENING, "Already listening", sessionId)
            return
        }

        sessionId = UUID.randomUUID().toString()
        val requestId = ++startRequestId
        nextSegmentId = 1L
        lastPartialSentAtMs = 0L
        lastPartialText = ""
        sendStatus(SubtitleStatusState.DOWNLOADING_MODEL, "Checking English model", sessionId)

        worker.execute {
            try {
                fileLog("Subtitle worker checking model")
                val modelResult = modelManager.ensureModel { progress ->
                    sendStatus(SubtitleStatusState.DOWNLOADING_MODEL, progress, sessionId)
                }
                if (requestId != startRequestId) return@execute
                if (!modelResult.success) {
                    fileLog("Subtitle model unavailable: ${modelResult.message}")
                    sendStatus(SubtitleStatusState.MODEL_MISSING, modelResult.message, sessionId)
                    onCaptureStopped()
                    return@execute
                }

                fileLog("Subtitle model ready at ${modelResult.modelDir.absolutePath}")
                val engineResult = SherpaOnnxSubtitleTranscriberFactory.create(
                    modelDir = modelResult.modelDir
                )
                if (requestId != startRequestId) return@execute
                val engine = engineResult.transcriber ?: run {
                    fileLog("Subtitle engine unavailable: ${engineResult.message}")
                    sendStatus(SubtitleStatusState.ERROR, engineResult.message, sessionId)
                    onCaptureStopped()
                    return@execute
                }

                fileLog("Subtitle engine created, starting audio capture")
                transcriber = engine
                startAudioCapture(projection, engine)
            } catch (t: Throwable) {
                fileLog("Subtitle worker failed: ${t.message}\n${t.stackTraceToString()}")
                sendStatus(SubtitleStatusState.ERROR, t.message ?: "Subtitle start failed", sessionId)
                stop(sendStopped = false)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(projection: MediaProjection, engine: SubtitleTranscriber) {
        if (!running.compareAndSet(false, true)) return
        fileLog("Subtitle audio capture setup")

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            fileLog("Subtitle audio capture buffer allocation failed: minBuffer=$minBuffer")
            sendStatus(SubtitleStatusState.ERROR, "Unable to allocate playback capture buffer", sessionId)
            onCaptureStopped()
            return
        }
        fileLog("Subtitle audio minBuffer=$minBuffer")

        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val record = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBuffer * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        audioRecord = record
        fileLog("Subtitle AudioRecord created, launching capture thread")
        audioThread = Thread {
            val buffer = ShortArray((minBuffer / 2).coerceAtLeast(1024))
            try {
                fileLog("Subtitle recognizer starting")
                sendStatus(SubtitleStatusState.DOWNLOADING_MODEL, "Loading recognizer", sessionId)
                engine.start()
                fileLog("Subtitle recognizer ready")
                fileLog("Subtitle AudioRecord starting")
                record.startRecording()
                fileLog("Subtitle AudioRecord state=${record.recordingState}")
                sendStatus(SubtitleStatusState.LISTENING, "Listening", sessionId)

                while (running.get() && !Thread.currentThread().isInterrupted) {
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        fileLog("Subtitle AudioRecord read failed: $read")
                        if (running.get()) {
                            sendStatus(SubtitleStatusState.ERROR, "Playback capture ended", sessionId)
                        }
                        break
                    }
                    if (read == 0) continue
                    val result = engine.acceptPcm16(buffer, read) ?: continue
                    maybeSendTranscript(result)
                }
            } catch (t: Throwable) {
                fileLog("Subtitle capture thread failed: ${t.message}\n${t.stackTraceToString()}")
                if (running.get()) {
                    sendStatus(SubtitleStatusState.ERROR, t.message ?: "Subtitle capture failed", sessionId)
                }
            } finally {
                fileLog("Subtitle capture thread stopping")
                runCatching { record.stop() }
                record.release()
                engine.stop()
                audioRecord = null
                transcriber = null
                running.set(false)
                onCaptureStopped()
            }
        }.apply {
            name = "PhoneSubtitleCapture"
            start()
        }
    }

    private fun maybeSendTranscript(result: SubtitleRecognitionResult) {
        val text = result.text.trim()
        if (text.isEmpty()) return

        val now = System.currentTimeMillis()
        if (!result.isFinal) {
            if (text == lastPartialText || now - lastPartialSentAtMs < PARTIAL_THROTTLE_MS) {
                return
            }
            lastPartialText = text
            lastPartialSentAtMs = now
        } else {
            lastPartialText = ""
        }

        serverProvider()?.sendSubtitleTranscript(
            SubtitleTranscript(
                sessionId = sessionId,
                segmentId = nextSegmentId++,
                text = text,
                isFinal = result.isFinal,
                timestampMs = now
            )
        )
    }

    private fun stop(sendStopped: Boolean) {
        pendingStartOptions = null
        startRequestId++
        running.set(false)
        val thread = audioThread
        if (thread != null && thread.isAlive) {
            fileLog("Subtitle stop requested; capture thread will release resources")
            thread.interrupt()
            runCatching { audioRecord?.stop() }
            if (sendStopped) {
                sendStatus(SubtitleStatusState.STOPPED, "Stopped", sessionId.takeIf { it.isNotBlank() })
            }
            return
        }

        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioThread = null
        audioRecord = null
        transcriber?.stop()
        transcriber = null
        if (sendStopped) {
            sendStatus(SubtitleStatusState.STOPPED, "Stopped", sessionId.takeIf { it.isNotBlank() })
        }
        onCaptureStopped()
    }

    private fun sendStatus(state: SubtitleStatusState, message: String? = null, activeSessionId: String? = sessionId.takeIf { it.isNotBlank() }) {
        serverProvider()?.sendSubtitleStatus(
            SubtitleStatus(
                state = state,
                message = message,
                sessionId = activeSessionId
            )
        )
    }
}

private data class SubtitleModelResult(
    val success: Boolean,
    val modelDir: File,
    val message: String
)

private class SubtitleModelManager(private val context: Context) {
    private companion object {
        private const val MODEL_NAME = "sherpa-onnx-streaming-zipformer-en-2023-06-26"
        private const val BASE_URL = "https://huggingface.co/csukuangfj/$MODEL_NAME/resolve/main"
    }

    private data class ModelFile(val name: String, val url: String, val minBytes: Long)

    private val modelDir = File(context.filesDir, "subtitle_models/$MODEL_NAME")
    private val files = listOf(
        ModelFile(
            "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "$BASE_URL/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            50L * 1024L * 1024L
        ),
        ModelFile(
            "decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            "$BASE_URL/decoder-epoch-99-avg-1-chunk-16-left-128.onnx",
            1L * 1024L * 1024L
        ),
        ModelFile(
            "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            "$BASE_URL/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
            200L * 1024L
        ),
        ModelFile("tokens.txt", "$BASE_URL/tokens.txt", 1024L)
    )

    fun ensureModel(onProgress: (String) -> Unit): SubtitleModelResult {
        modelDir.mkdirs()
        if (isReady()) {
            return SubtitleModelResult(true, modelDir, "Model ready")
        }

        return try {
            files.forEachIndexed { index, file ->
                val target = File(modelDir, file.name)
                if (!target.exists() || target.length() < file.minBytes) {
                    onProgress("Downloading English model ${index + 1}/${files.size}")
                    download(file.url, target)
                }
            }

            if (isReady()) {
                SubtitleModelResult(true, modelDir, "Model ready")
            } else {
                SubtitleModelResult(false, modelDir, "English subtitle model is incomplete")
            }
        } catch (e: Exception) {
            SubtitleModelResult(false, modelDir, e.message ?: "Unable to download English subtitle model")
        }
    }

    private fun isReady(): Boolean = files.all { File(modelDir, it.name).length() >= it.minBytes }

    private fun download(url: String, target: File) {
        val temp = File(target.parentFile, "${target.name}.tmp")
        URL(url).openStream().use { input ->
            temp.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (target.exists()) target.delete()
        temp.renameTo(target)
    }
}

private data class SubtitleRecognitionResult(
    val text: String,
    val isFinal: Boolean
)

private interface SubtitleTranscriber {
    fun start()
    fun acceptPcm16(samples: ShortArray, size: Int): SubtitleRecognitionResult?
    fun stop()
}

private data class SubtitleTranscriberResult(
    val transcriber: SubtitleTranscriber?,
    val message: String
)

private object SherpaOnnxSubtitleTranscriberFactory {
    fun create(modelDir: File): SubtitleTranscriberResult {
        return try {
            SubtitleTranscriberResult(
                transcriber = SherpaOnnxTranscriber(modelDir),
                message = "Sherpa ONNX ready"
            )
        } catch (t: Throwable) {
            SubtitleTranscriberResult(
                transcriber = null,
                message = t.message ?: "Unable to initialize Sherpa ONNX"
            )
        }
    }
}

private class SherpaOnnxTranscriber(
    private val modelDir: File
) : SubtitleTranscriber {
    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var lastText = ""

    override fun start() {
        fileLog("Subtitle creating OnlineRecognizer from files")
        recognizer = OnlineRecognizer(null, createConfig())
        fileLog("Subtitle creating OnlineStream")
        stream = recognizer?.createStream("")
    }

    override fun acceptPcm16(samples: ShortArray, size: Int): SubtitleRecognitionResult? {
        val activeRecognizer = recognizer ?: return null
        val activeStream = stream ?: return null
        val floats = FloatArray(size)
        for (i in 0 until size) {
            floats[i] = samples[i] / 32768.0f
        }

        activeStream.acceptWaveform(floats, SUBTITLE_SAMPLE_RATE)

        while (activeRecognizer.isReady(activeStream)) {
            activeRecognizer.decode(activeStream)
        }

        val result = activeRecognizer.getResult(activeStream)
        val text = result.text
        val isFinal = activeRecognizer.isEndpoint(activeStream)

        if (isFinal) {
            activeRecognizer.reset(activeStream)
        }

        if (text.isBlank() || (!isFinal && text == lastText)) {
            return null
        }
        lastText = if (isFinal) "" else text
        return SubtitleRecognitionResult(text = text, isFinal = isFinal)
    }

    override fun stop() {
        runCatching { stream?.release() }
        runCatching { recognizer?.release() }
        stream = null
        recognizer = null
        lastText = ""
    }

    private fun createConfig(): OnlineRecognizerConfig =
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SUBTITLE_SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(modelDir, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx").absolutePath,
                    decoder = File(modelDir, "decoder-epoch-99-avg-1-chunk-16-left-128.onnx").absolutePath,
                    joiner = File(modelDir, "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx").absolutePath
                ),
                tokens = File(modelDir, "tokens.txt").absolutePath,
                numThreads = 2,
                provider = "cpu"
            ),
            enableEndpoint = true
        )
}
