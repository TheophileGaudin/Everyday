package com.everyday.everyday_glasses.interaction.assistant

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The glasses' camera, for the assistant: a look at what the wearer faces (sent to Gemini, not
 * kept), and photos or videos kept in the glasses' gallery and copied to the phone when it is
 * connected. The glasses light their camera indicator by themselves while it is on. Every call
 * blocks until done, so they run on the assistant's worker thread, one at a time.
 */
class AssistantCamera(
    context: Context,
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    /** Copies a kept file to the phone's gallery; false when the phone isn't connected. */
    private val sendToPhone: (file: File, fileName: String, mimeType: String) -> Boolean,
    /** Switches the glasses' screen on, as a touch would: Android lends the camera only to an app on screen. */
    private val wakeScreen: () -> Unit,
    /** Shows a short message on the glasses ("Taking photo…", "Photo taken") for so many milliseconds. */
    private val showNotice: (message: String, durationMs: Long) -> Unit,
) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)
    private val captureLock = Any()

    /** A photo or video kept in the gallery; [file] is a working copy, valid until the next one. */
    data class KeptMedia(val file: File, val fileName: String, val mimeType: String, val copiedToPhone: Boolean)

    /** The assistant's camera tools: a look, a photo, a video. */
    fun tools(): List<AssistantSemanticToolHandler> = listOf(LookTool(), TakePhotoTool(), RecordVideoTool())

    // ==================== Captures ====================

    /** One photo of what the wearer faces, reduced for Gemini to look at; nothing is kept. */
    fun look(): AssistantScreenshot = synchronized(captureLock) {
        showNotice("Looking…", NOTICE_WHILE_CAPTURING_MS)
        try {
            reducedForGemini(captureJpeg())
        } finally {
            showNotice("Photo seen", NOTICE_DONE_MS)
        }
    }

    /** A full-resolution photo, kept in DCIM/Everyday, plus a reduced copy if Gemini should see it. */
    fun takePhoto(): Pair<KeptMedia, ByteArray> = synchronized(captureLock) {
        showNotice("Taking photo…", NOTICE_WHILE_CAPTURING_MS)
        val result = runCatching {
            val jpeg = captureJpeg()
            val name = "IMG_${timestamp()}.jpg"
            val file = workingFile(name).apply { writeBytes(jpeg) }
            keep(file, name, "image/jpeg", "DCIM/Everyday", MediaStore.Images.Media.EXTERNAL_CONTENT_URI) to jpeg
        }
        showNotice(if (result.isSuccess) "Photo taken" else "Photo failed", NOTICE_DONE_MS)
        result.getOrThrow()
    }

    /** A video of [seconds] with sound, 720p at a size Gemini can also receive through the phone. */
    fun recordVideo(seconds: Int): KeptMedia = synchronized(captureLock) {
        showNotice("Recording ${seconds} s…", seconds * 1_000L + NOTICE_WHILE_CAPTURING_MS)
        val result = runCatching { recordVideoNow(seconds) }
        showNotice(if (result.isSuccess) "Video saved" else "Video failed", NOTICE_DONE_MS)
        result.getOrThrow()
    }

    private fun recordVideoNow(seconds: Int): KeptMedia {
        val name = "VID_${timestamp()}.mp4"
        val file = workingFile(name)
        withCamera { device, handler, characteristics ->
            val size = videoSize(characteristics)
            val recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(appContext) else {
                @Suppress("DEPRECATION") MediaRecorder()
            }).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoEncodingBitRate(VIDEO_BIT_RATE)
                setVideoFrameRate(VIDEO_FRAME_RATE)
                setVideoSize(size.width, size.height)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOrientationHint(characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                prepare()
            }
            try {
                val session = openSession(device, listOf(recorder.surface), handler)
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(recorder.surface)
                }.build()
                session.setRepeatingRequest(request, null, handler)
                recorder.start()
                Log.d(TAG, "Recording ${seconds}s at ${size.width}x${size.height}")
                Thread.sleep(seconds * 1_000L)
                recorder.stop()
                runCatching { session.stopRepeating() }
                session.close()
            } finally {
                recorder.release()
            }
        }
        return keep(file, name, "video/mp4", "Movies/Everyday", MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
    }

    // ==================== Camera2 ====================

    /** A still photo: the camera first runs briefly so exposure and focus settle, then shoots. */
    private fun captureJpeg(): ByteArray = withCamera { device, handler, characteristics ->
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException("The camera has no output sizes")
        val size = map.getOutputSizes(ImageFormat.JPEG).maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("The camera can't take JPEG photos")
        val still = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
        val preview = ImageReader.newInstance(PREVIEW_WIDTH, PREVIEW_HEIGHT, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, handler)
        }
        try {
            val session = openSession(device, listOf(preview.surface, still.surface), handler)
            val settled = CompletableFuture<Unit>()
            val previewRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(preview.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }.build()
            session.setRepeatingRequest(previewRequest, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val ae = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
                        ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED) settled.complete(Unit)
                }
            }, handler)
            Thread.sleep(MIN_WARM_UP_MS)
            runCatching { settled.get(MAX_WARM_UP_MS, TimeUnit.MILLISECONDS) }

            val jpeg = CompletableFuture<ByteArray>()
            still.setOnImageAvailableListener({ reader ->
                reader.acquireLatestImage()?.use { image ->
                    val buffer = image.planes[0].buffer
                    jpeg.complete(ByteArray(buffer.remaining()).also { buffer.get(it) })
                }
            }, handler)
            val stillRequest = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(still.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.JPEG_ORIENTATION, characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
                set(CaptureRequest.JPEG_QUALITY, 92.toByte())
            }.build()
            session.stopRepeating()
            session.capture(stillRequest, null, handler)
            jpeg.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS).also { session.close() }
        } finally {
            preview.close()
            still.close()
        }
    }

    /** Opens the main camera (the back camera with the most pixels), runs [block], then closes it. */
    @SuppressLint("MissingPermission")
    private fun <T> withCamera(block: (CameraDevice, Handler, CameraCharacteristics) -> T): T {
        if (!hasPermission()) {
            requestPermission()
            throw IllegalStateException("Camera access is needed: allow it on the glasses, then ask again")
        }
        ensureScreenOn()
        val id = cameraManager.cameraIdList.filter { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT
        }.maxByOrNull { id ->
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(ImageFormat.JPEG)?.maxOfOrNull { it.width.toLong() * it.height } ?: 0L
        } ?: throw IllegalStateException("The glasses have no camera available")
        val characteristics = cameraManager.getCameraCharacteristics(id)
        val thread = HandlerThread("AssistantCamera").apply { start() }
        val handler = Handler(thread.looper)
        val opened = CompletableFuture<CameraDevice>()
        cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                opened.complete(camera)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                opened.completeExceptionally(IllegalStateException("The camera is in use by another app"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                camera.close()
                opened.completeExceptionally(IllegalStateException("The camera failed to open ($error)"))
            }
        }, handler)
        val device = opened.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        try {
            return block(device, handler, characteristics)
        } finally {
            device.close()
            thread.quitSafely()
        }
    }

    /** With the screen off (glasses set down, or masked a while) the camera is refused: switch it on first. */
    private fun ensureScreenOn() {
        val power = appContext.getSystemService(android.os.PowerManager::class.java)
        if (power.isInteractive) return
        Log.d(TAG, "Switching the screen on for the camera")
        wakeScreen()
        val deadline = System.currentTimeMillis() + SCREEN_ON_TIMEOUT_MS
        while (!power.isInteractive && System.currentTimeMillis() < deadline) Thread.sleep(100)
        // The app comes back to the front just after the screen: only then is the camera lent to it.
        Thread.sleep(SCREEN_ON_SETTLE_MS)
    }

    private fun openSession(device: CameraDevice, surfaces: List<Surface>, handler: Handler): CameraCaptureSession {
        val ready = CompletableFuture<CameraCaptureSession>()
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                ready.complete(session)
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                ready.completeExceptionally(IllegalStateException("The camera could not be configured"))
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.createCaptureSession(SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                surfaces.map(::OutputConfiguration),
                { handler.post(it) },
                callback
            ))
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, callback, handler)
        }
        return ready.get(CAPTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** 1280x720 when the camera records it, else the largest size up to that width. */
    private fun videoSize(characteristics: CameraCharacteristics): Size {
        val sizes = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java).orEmpty()
        return sizes.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: sizes.filter { it.width <= 1280 }.maxByOrNull { it.width.toLong() * it.height }
            ?: throw IllegalStateException("The camera can't record video")
    }

    // ==================== Files ====================

    /** Puts [file] in the glasses' gallery under [relativePath], then copies it to the phone if connected. */
    private fun keep(file: File, fileName: String, mimeType: String, relativePath: String, collection: android.net.Uri): KeptMedia {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IllegalStateException("The gallery refused the file")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IllegalStateException("The gallery file isn't writable")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        val copied = sendToPhone(file, fileName, mimeType)
        Log.d(TAG, "Kept $fileName (${file.length()} B), copied to the phone: $copied")
        return KeptMedia(file, fileName, mimeType, copied)
    }

    /** A working copy in the cache; the previous ones go, so only the latest photo and video stay. */
    private fun workingFile(name: String): File {
        val dir = File(appContext.cacheDir, "camera").apply { mkdirs() }
        dir.listFiles()?.filter { it.extension == name.substringAfterLast('.') }?.forEach { it.delete() }
        return File(dir, name)
    }

    private fun reducedForGemini(jpeg: ByteArray): AssistantScreenshot {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= GEMINI_IMAGE_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("The photo could not be read")
        // The camera tags its photos to be turned upright (gallery apps do); decoding ignores that, so turn it here.
        val degrees = when (ExifInterface(jpeg.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val scale = (GEMINI_IMAGE_SIDE.toFloat() / maxOf(decoded.width, decoded.height)).coerceAtMost(1f)
        val matrix = Matrix().apply { postScale(scale, scale); postRotate(degrees) }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
        val bytes = ByteArrayOutputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
        return AssistantScreenshot(bytes, bitmap.width, bitmap.height, "image/jpeg").also { bitmap.recycle() }
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // ==================== The tools ====================

    private inner class LookTool : AssistantSemanticToolHandler {
        override val definition = AssistantToolDefinition(
            name = "look_through_camera",
            description = "Take one photo with the glasses' camera of what the user is facing and see it, to answer " +
                "what they are looking at: identify, read, describe or explain it. The photo is not kept: when the " +
                "user asks to take or keep a picture, use take_photo instead. Then answer with present_text.",
            parameters = JSONObject().put("type", "object").put("properties", JSONObject())
        )

        override fun validate(arguments: JSONObject): Result<Unit> = Result.success(Unit)

        override fun execute(arguments: JSONObject, context: AssistantToolExecutionContext): CompletableFuture<AssistantToolExecution> =
            assistantCompletedFuture(AssistantToolExecution(
                true, AssistantToolExecutionCodes.SUCCESS, "Photo of what the user is facing",
                screenshots = listOf(look()), groundingEvidence = true
            ))
    }

    private inner class TakePhotoTool : AssistantSemanticToolHandler {
        override val definition = AssistantToolDefinition(
            name = "take_photo",
            description = "Take a photo with the glasses' camera and keep it in the gallery (copied to the phone when " +
                "connected), when the user asks to take a picture. Set describe to also see it, when they ask about it.",
            parameters = JSONObject().put("type", "object").put("properties", JSONObject()
                .put("describe", JSONObject().put("type", "boolean")))
        )

        override fun validate(arguments: JSONObject): Result<Unit> = Result.success(Unit)

        override fun execute(arguments: JSONObject, context: AssistantToolExecutionContext): CompletableFuture<AssistantToolExecution> {
            val (kept, jpeg) = takePhoto()
            val describe = arguments.optBoolean("describe", false)
            return assistantCompletedFuture(AssistantToolExecution(
                true, AssistantToolExecutionCodes.SUCCESS,
                "Photo ${kept.fileName} kept on the glasses" + if (kept.copiedToPhone) " and being copied to the phone" else "",
                screenshots = if (describe) listOf(reducedForGemini(jpeg)) else emptyList(),
                groundingEvidence = true,
                completesRequest = !describe
            ))
        }
    }

    private inner class RecordVideoTool : AssistantSemanticToolHandler {
        override val definition = AssistantToolDefinition(
            name = "record_video",
            description = "Record a video with sound with the glasses' camera and keep it in the gallery (copied to the " +
                "phone when connected), when the user asks to film or record. duration_seconds defaults to 10. Set " +
                "describe to also watch it, when they ask about what was filmed.",
            parameters = JSONObject().put("type", "object").put("properties", JSONObject()
                .put("duration_seconds", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", MAX_VIDEO_SECONDS))
                .put("describe", JSONObject().put("type", "boolean")))
        )

        override fun validate(arguments: JSONObject): Result<Unit> = runCatching {
            if (arguments.has("duration_seconds")) {
                require(arguments.optInt("duration_seconds", -1) in 1..MAX_VIDEO_SECONDS) {
                    "duration_seconds must be 1 to $MAX_VIDEO_SECONDS"
                }
            }
        }

        override fun execute(arguments: JSONObject, context: AssistantToolExecutionContext): CompletableFuture<AssistantToolExecution> {
            val kept = recordVideo(arguments.optInt("duration_seconds", DEFAULT_VIDEO_SECONDS))
            val describe = arguments.optBoolean("describe", false)
            return assistantCompletedFuture(AssistantToolExecution(
                true, AssistantToolExecutionCodes.SUCCESS,
                "Video ${kept.fileName} kept on the glasses" + if (kept.copiedToPhone) " and being copied to the phone" else "",
                videos = if (describe) listOf(AssistantVideo(kept.file)) else emptyList(),
                groundingEvidence = true,
                completesRequest = !describe
            ))
        }
    }

    companion object {
        private const val TAG = "AssistantCamera"
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480
        private const val MIN_WARM_UP_MS = 300L
        private const val MAX_WARM_UP_MS = 1_500L
        private const val CAPTURE_TIMEOUT_MS = 6_000L
        private const val SCREEN_ON_TIMEOUT_MS = 3_000L
        private const val SCREEN_ON_SETTLE_MS = 800L
        /** Shown until the capture replaces it, in case it never does. */
        private const val NOTICE_WHILE_CAPTURING_MS = 10_000L
        private const val NOTICE_DONE_MS = 2_000L
        /** The longest side of a photo Gemini looks at: detailed enough to read signs, small to send. */
        private const val GEMINI_IMAGE_SIDE = 1024
        /** About 3 MB for 10 s: small enough for Gemini to receive through the phone. */
        private const val VIDEO_BIT_RATE = 2_500_000
        private const val VIDEO_FRAME_RATE = 30
        private const val DEFAULT_VIDEO_SECONDS = 10
        private const val MAX_VIDEO_SECONDS = 60
    }
}
