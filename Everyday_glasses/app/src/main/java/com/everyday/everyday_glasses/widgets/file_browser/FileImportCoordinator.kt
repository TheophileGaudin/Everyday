package com.everyday.everyday_glasses.widgets.file_browser

import android.app.Activity
import android.content.ContentResolver
import android.database.Cursor
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import com.everyday.everyday_glasses.persistence.ImageWidgetStorage
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** The user-visible outcome of an image import attempt. */
enum class FileImportError(val userMessage: String) {
    UNSUPPORTED_IMAGE("That file is not a supported image."),
    IMAGE_TOO_LARGE("That image is too large to display."),
    IMAGE_UNREADABLE("The image could not be read."),
    IMPORT_FAILED("The file could not be imported."),
}

/** UI operations requested after file selection or import completes. */
interface FileImportCallbacks {
    fun showFileBrowser(
        filter: FileBrowserWidget.FileFilter,
        onFileSelected: (FileBrowserWidget.FileEntry) -> Unit
    )

    fun createImageWidget(path: String)
    fun createVideoWidget(uri: Uri, displayName: String)
    fun createPdfWidget(uri: Uri, displayName: String)
    fun showFileImportError(error: FileImportError)
}

/**
 * Formats accepted by the image importer. Header detection is deliberately
 * independent of Android so it can be tested without a device.
 */
enum class ImageImportFormat(val extension: String) {
    JPEG("jpg"),
    PNG("png"),
    GIF("gif"),
    WEBP("webp"),
    BMP("bmp"),
}

object ImageImportClassifier {
    /** 32 MiB accommodates ordinary camera photos while bounding imports. */
    const val MAX_IMAGE_BYTES: Long = 32L * 1024L * 1024L

    fun classify(header: ByteArray): ImageImportFormat? {
        if (header.startsWith(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))) {
            return ImageImportFormat.JPEG
        }
        if (header.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))) {
            return ImageImportFormat.PNG
        }
        if (header.startsWith("GIF87a".encodeToByteArray()) ||
            header.startsWith("GIF89a".encodeToByteArray())
        ) {
            return ImageImportFormat.GIF
        }
        if (header.size >= 12 &&
            header.copyOfRange(0, 4).contentEquals("RIFF".encodeToByteArray()) &&
            header.copyOfRange(8, 12).contentEquals("WEBP".encodeToByteArray())
        ) {
            return ImageImportFormat.WEBP
        }
        if (header.startsWith("BM".encodeToByteArray())) {
            return ImageImportFormat.BMP
        }
        return null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/**
 * Owns file selection, import classification, and managed image persistence.
 * The in-app browser is the only supported picker path. Widget rendering and
 * storage permission policy remain outside this class.
 */
class FileImportCoordinator(
    activity: Activity,
    private val callbacks: FileImportCallbacks,
    private val importExecutor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FileImport").apply { isDaemon = true }
        }
) {

    companion object {
        private const val TAG = "FileImportCoordinator"
        private const val HEADER_BYTES_TO_READ = 32
        private const val MAX_NAME_LENGTH = 80
        private const val DEFAULT_COPY_BUFFER_SIZE = 16 * 1024
        private const val MAX_IMAGE_DIMENSION = 8_192
        private const val MAX_IMAGE_PIXELS = 40_000_000L
    }

    private val appContext = activity.applicationContext
    private val contentResolver = appContext.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var released = false

    /** Shows the existing in-app media browser. */
    fun openFileBrowser() {
        if (released) return

        postUi {
            callbacks.showFileBrowser(
                filter = FileBrowserWidget.FileFilter.MEDIA,
                onFileSelected = ::importFileBrowserEntry
            )
        }
    }

    private fun importFileBrowserEntry(entry: FileBrowserWidget.FileEntry) {
        Log.d(TAG, "File selected: ${entry.absolutePath}, uri: ${entry.uri}")

        when {
            entry.extension.equals("pdf", ignoreCase = true) ||
                entry.mimeType == "application/pdf" -> {
                val uri = entry.uri ?: Uri.fromFile(File(entry.absolutePath))
                openPdf(uri, entry.name)
            }

            entry.extension.lowercase() in FileBrowserWidget.FileFilter.VIDEOS.extensions ||
                entry.mimeType?.startsWith("video/") == true -> {
                val uri = entry.uri ?: Uri.fromFile(File(entry.absolutePath))
                openVideo(uri, entry.name)
            }

            entry.uri != null -> importImage(entry.uri)
            else -> importImage(File(entry.absolutePath))
        }
    }

    // ---------------------------------------------------------------------
    // Image imports
    // ---------------------------------------------------------------------

    /** Copies a content URI into app-managed storage for a durable file path. */
    fun importImage(uri: Uri) {
        submitImport("image URI $uri") {
            val displayName = queryDisplayName(contentResolver, uri)
            val inputStream = contentResolver.openInputStream(uri)
                ?: throw ImportFailure(FileImportError.IMAGE_UNREADABLE)

            inputStream.use { input ->
                val file = saveImageToManagedStorage(input, displayName)
                Log.d(TAG, "Image saved to ${file.absolutePath}")
                postUi { callbacks.createImageWidget(file.absolutePath) }
            }
        }
    }

    /** Validates a directly accessible browser image without copying it. */
    fun importImage(file: File) {
        submitImport("image file ${file.absolutePath}") {
            validateImageFile(file)
            Log.d(TAG, "Loading image from ${file.absolutePath}")
            postUi { callbacks.createImageWidget(file.absolutePath) }
        }
    }

    /** Saves an image received from the phone and opens an ImageWidget. */
    fun importReceivedImage(imageData: ByteArray, fileName: String) {
        submitImport("phone image $fileName") {
            if (imageData.size.toLong() > ImageImportClassifier.MAX_IMAGE_BYTES) {
                throw ImportFailure(FileImportError.IMAGE_TOO_LARGE)
            }

            val imageFile = imageData.inputStream().use { input ->
                saveImageToManagedStorage(input, fileName)
            }
            Log.d(TAG, "Saved received image to ${imageFile.absolutePath}")
            postUi { callbacks.createImageWidget(imageFile.absolutePath) }
        }
    }

    private fun saveImageToManagedStorage(
        inputStream: InputStream,
        originalName: String?
    ): File {
        val header = readHeader(inputStream)
        val format = ImageImportClassifier.classify(header)
            ?: throw ImportFailure(FileImportError.UNSUPPORTED_IMAGE)
        val outputFile = createUniqueOutputFile(originalName, format)
        val temporaryFile = ImageWidgetStorage.createTemporaryFile(appContext)

        try {
            FileOutputStream(temporaryFile).use { output ->
                var bytesWritten = header.size.toLong()
                if (bytesWritten > ImageImportClassifier.MAX_IMAGE_BYTES) {
                    throw ImportFailure(FileImportError.IMAGE_TOO_LARGE)
                }
                output.write(header)
                val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
                while (true) {
                    val count = inputStream.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    bytesWritten += count
                    if (bytesWritten > ImageImportClassifier.MAX_IMAGE_BYTES) {
                        throw ImportFailure(FileImportError.IMAGE_TOO_LARGE)
                    }
                    output.write(buffer, 0, count)
                }
                output.flush()
                output.fd.sync()
            }
            validateImageDimensions(temporaryFile)
            return ImageWidgetStorage.commitTemporaryFile(appContext, temporaryFile, outputFile)
        } catch (error: Throwable) {
            temporaryFile.delete()
            throw error
        }
    }

    private fun validateImageFile(file: File) {
        if (!file.isFile || !file.canRead()) {
            throw ImportFailure(FileImportError.IMAGE_UNREADABLE)
        }
        if (file.length() > ImageImportClassifier.MAX_IMAGE_BYTES) {
            throw ImportFailure(FileImportError.IMAGE_TOO_LARGE)
        }
        FileInputStream(file).use { input ->
            if (ImageImportClassifier.classify(readHeader(input)) == null) {
                throw ImportFailure(FileImportError.UNSUPPORTED_IMAGE)
            }
        }
        validateImageDimensions(file)
    }

    private fun validateImageDimensions(file: File) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        if (width <= 0 || height <= 0) {
            throw ImportFailure(FileImportError.IMAGE_UNREADABLE)
        }
        if (
            width > MAX_IMAGE_DIMENSION ||
            height > MAX_IMAGE_DIMENSION ||
            width.toLong() * height.toLong() > MAX_IMAGE_PIXELS
        ) {
            throw ImportFailure(FileImportError.IMAGE_TOO_LARGE)
        }
    }

    private fun readHeader(inputStream: InputStream): ByteArray {
        val header = ByteArray(HEADER_BYTES_TO_READ)
        var offset = 0
        while (offset < header.size) {
            val count = inputStream.read(header, offset, header.size - offset)
            if (count < 0) break
            if (count == 0) continue
            offset += count
        }
        return header.copyOf(offset)
    }

    private fun createUniqueOutputFile(
        originalName: String?,
        format: ImageImportFormat
    ): File {
        val safeBaseName = sanitizeBaseName(originalName)
        val prefix = "${System.currentTimeMillis()}_$safeBaseName"
        var suffix = 0
        while (true) {
            val candidateName = if (suffix == 0) {
                "$prefix.${format.extension}"
            } else {
                "${prefix}_$suffix.${format.extension}"
            }
            val candidate = ImageWidgetStorage.createManagedFile(appContext, candidateName)
            if (!candidate.exists()) return candidate
            suffix++
        }
    }

    private fun sanitizeBaseName(originalName: String?): String {
        val source = originalName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.substringBeforeLast('.', missingDelimiterValue = "")
            .orEmpty()
        val sanitized = source
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .trim('.', '_', '-')
            .take(MAX_NAME_LENGTH)
        return sanitized.ifBlank { "image" }
    }

    // ---------------------------------------------------------------------
    // Video and PDF imports
    // ---------------------------------------------------------------------

    private fun openVideo(uri: Uri, displayName: String) {
        postUi { callbacks.createVideoWidget(uri, displayName) }
    }

    private fun openPdf(uri: Uri, displayName: String) {
        postUi { callbacks.createPdfWidget(uri, displayName) }
    }

    // ---------------------------------------------------------------------
    // Threading and cleanup
    // ---------------------------------------------------------------------

    private fun submitImport(label: String, action: () -> Unit) {
        if (released) return

        try {
            importExecutor.execute {
                if (released) return@execute
                try {
                    action()
                } catch (error: ImportFailure) {
                    Log.e(TAG, "Failed to import $label: ${error.error}", error)
                    if (!released) postUi { callbacks.showFileImportError(error.error) }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    Log.d(TAG, "Import interrupted: $label")
                } catch (error: Throwable) {
                    Log.e(TAG, "Failed to import $label", error)
                    if (!released) postUi { callbacks.showFileImportError(FileImportError.IMPORT_FAILED) }
                }
            }
        } catch (_: RejectedExecutionException) {
            Log.w(TAG, "Dropped import after executor shutdown: $label")
        }
    }

    private fun postUi(action: () -> Unit) {
        if (released) return

        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post {
                if (!released) action()
            }
        }
    }

    fun release() {
        if (released) return
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        importExecutor.shutdownNow()
    }

    private class ImportFailure(val error: FileImportError) : IOException(error.name)
}

private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
    return runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor: Cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()
}