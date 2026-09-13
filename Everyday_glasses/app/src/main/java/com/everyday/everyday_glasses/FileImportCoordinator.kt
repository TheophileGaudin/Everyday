package com.everyday.everyday_glasses

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** UI operations requested after file selection or import completes. */
interface FileImportCallbacks {
    fun showFileBrowser(
        filter: FileBrowserWidget.FileFilter,
        onFileSelected: (FileBrowserWidget.FileEntry) -> Unit
    )

    fun createImageWidget(path: String)
    fun createVideoWidget(uri: Uri, displayName: String)
    fun createPdfWidget(uri: Uri, displayName: String)
}

/**
 * Owns file selection, import classification, and managed image persistence.
 * Widget rendering and storage permission policy remain outside this class.
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
    }

    // ---------------------------------------------------------------------
    // Owned dependencies and lifecycle state
    // ---------------------------------------------------------------------

    private val appContext = activity.applicationContext
    private val contentResolver = appContext.contentResolver
    private val mainHandler = Handler(Looper.getMainLooper())
    private val filePicker = FilePicker(activity)

    @Volatile
    private var released = false

    // ---------------------------------------------------------------------
    // In-app file browser
    // ---------------------------------------------------------------------

    /**
     * Shows the existing in-app media browser. PermissionCoordinator must
     * complete its access flow before this is called.
     */
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
        Log.d(
            TAG,
            "File selected: ${entry.absolutePath}, uri: ${entry.uri}"
        )

        when {
            entry.extension == "pdf" ||
                    entry.mimeType == "application/pdf" -> {
                val uri = entry.uri ?: Uri.fromFile(File(entry.absolutePath))
                openPdf(uri, entry.name)
            }

            entry.extension in FileBrowserWidget.FileFilter.VIDEOS.extensions ||
                    entry.mimeType?.startsWith("video/") == true -> {
                val uri = entry.uri ?: Uri.fromFile(File(entry.absolutePath))
                openVideo(uri, entry.name)
            }

            entry.uri != null -> {
                importImage(entry.uri)
            }

            else -> {
                importImage(File(entry.absolutePath))
            }
        }
    }

    // ---------------------------------------------------------------------
    // Optional Android document picker
    // ---------------------------------------------------------------------

    /**
     * Preserves and exposes the existing FilePicker path. It is not currently
     * called by MainActivity, but can be used by a future system-picker action.
     */
    fun pickFile(fileType: FilePicker.FileType) {
        if (released) return

        filePicker.pickFile(
            fileType,
            object : FilePicker.OnFilePickedListener {
                override fun onFilePicked(
                    uri: Uri,
                    fileType: FilePicker.FileType
                ) {
                    when (fileType) {
                        FilePicker.FileType.IMAGE -> importImage(uri)
                        FilePicker.FileType.PDF -> openPdf(
                            uri,
                            displayName = "Document"
                        )
                    }
                }

                override fun onPickerCancelled() {
                    Log.d(TAG, "System file picker cancelled")
                }

                override fun onPickerError(message: String) {
                    Log.e(TAG, "System file picker error: $message")
                }
            }
        )
    }

    /** Returns true when FilePicker recognized the request code. */
    fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        if (released) return false
        return filePicker.handleActivityResult(requestCode, resultCode, data)
    }

    // ---------------------------------------------------------------------
    // Image imports
    // ---------------------------------------------------------------------

    /**
     * Copies a content URI into app-managed storage so ImageWidget receives a
     * durable ordinary file path.
     */
    fun importImage(uri: Uri) {
        submitImport("image URI $uri") {
            val mimeType = contentResolver.getType(uri)
            val extension = imageExtensionForMimeType(mimeType)
            val managedName =
                "image_${System.currentTimeMillis()}.$extension"

            val inputStream = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Unable to open image URI: $uri")

            val file = saveImageToManagedStorage(inputStream, managedName)
            Log.d(TAG, "Image saved to ${file.absolutePath}")

            postUi {
                callbacks.createImageWidget(file.absolutePath)
            }
        }
    }

    /**
     * Opens a directly accessible image file without copying it, preserving the
     * current FileBrowserWidget behavior.
     */
    fun importImage(file: File) {
        submitImport("image file ${file.absolutePath}") {
            Log.d(TAG, "Loading image from ${file.absolutePath}")
            postUi {
                callbacks.createImageWidget(file.absolutePath)
            }
        }
    }

    /** Saves an image received from the phone and opens an ImageWidget. */
    fun importReceivedImage(imageData: ByteArray, fileName: String) {
        submitImport("phone image $fileName") {
            val timestamp = System.currentTimeMillis()
            val safeFileName = fileName.replace(
                Regex("[^a-zA-Z0-9._-]"),
                "_"
            )
            val managedName = "${timestamp}_$safeFileName"
            val imageFile = saveImageToManagedStorage(imageData, managedName)

            Log.d(TAG, "Saved received image to ${imageFile.absolutePath}")

            postUi {
                callbacks.createImageWidget(imageFile.absolutePath)
            }
        }
    }

    private fun imageExtensionForMimeType(mimeType: String?): String =
        when (mimeType) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> "jpg"
        }

    // ---------------------------------------------------------------------
    // Video and PDF imports
    // ---------------------------------------------------------------------

    private fun openVideo(uri: Uri, displayName: String) {
        postUi {
            callbacks.createVideoWidget(uri, displayName)
        }
    }

    private fun openPdf(uri: Uri, displayName: String) {
        postUi {
            callbacks.createPdfWidget(uri, displayName)
        }
    }

    // ---------------------------------------------------------------------
    // Managed image persistence
    // ---------------------------------------------------------------------

    private fun saveImageToManagedStorage(
        inputStream: InputStream,
        fileName: String
    ): File {
        val outputFile = ImageWidgetStorage.createManagedFile(
            appContext,
            fileName
        )

        inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        return outputFile
    }

    private fun saveImageToManagedStorage(
        imageData: ByteArray,
        fileName: String
    ): File {
        val outputFile = ImageWidgetStorage.createManagedFile(
            appContext,
            fileName
        )
        outputFile.writeBytes(imageData)
        return outputFile
    }

    // ---------------------------------------------------------------------
    // Threading and cleanup
    // ---------------------------------------------------------------------

    private fun submitImport(label: String, action: () -> Unit) {
        if (released) return

        try {
            importExecutor.execute {
                if (released) return@execute

                runCatching(action)
                    .onFailure { error ->
                        Log.e(TAG, "Failed to import $label", error)
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
}