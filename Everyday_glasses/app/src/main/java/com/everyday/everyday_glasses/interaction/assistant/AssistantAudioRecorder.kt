package com.everyday.everyday_glasses.interaction.assistant

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File

class AssistantAudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    @Synchronized
    fun start(): Result<File> = runCatching {
        check(recorder == null) { "A recording is already active" }
        val file = File.createTempFile("assistant_", ".aac", context.cacheDir)
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioChannels(1)
            next.setAudioSamplingRate(16_000)
            next.setAudioEncodingBitRate(32_000)
            next.setOutputFile(file.absolutePath)
            next.prepare()
            next.start()
        } catch (failure: Throwable) {
            next.release()
            file.delete()
            throw failure
        }
        recorder = next
        output = file
        file
    }

    @Synchronized
    fun stop(): Result<File> = runCatching {
        val active = recorder ?: error("No recording is active")
        val file = output ?: error("No recording output")
        try {
            active.stop()
        } finally {
            active.release()
            recorder = null
            output = null
        }
        file
    }

    @Synchronized
    fun cancel() {
        val file = output
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        output = null
        file?.delete()
    }
}
