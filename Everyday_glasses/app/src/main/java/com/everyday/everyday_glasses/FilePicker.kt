package com.everyday.everyday_glasses

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast

/**
 * Handles file picking operations with proper error handling for devices
 * that may not have a document picker installed (like AR glasses).
 *
 * Extensible to support different file types (images, PDFs, etc.)
 */
class FilePicker(private val activity: Activity) {

    companion object {
        private const val TAG = "FilePicker"

        // Base request code - file type offset is added to this
        private const val REQUEST_CODE_BASE = 1000
    }

    /**
     * Supported file types for picking
     */
    enum class FileType(val mimeType: String, val requestCodeOffset: Int) {
        IMAGE("image/*", 0),
        PDF("application/pdf", 1),
        // Add more file types as needed
        // VIDEO("video/*", 2),
        // AUDIO("audio/*", 3),
        // DOCUMENT("application/*", 4),
    }

    /**
     * Result callback for when a file is picked
     */
    interface OnFilePickedListener {
        fun onFilePicked(uri: Uri, fileType: FileType)
        fun onPickerCancelled()
        fun onPickerError(message: String)
    }

    private var listener: OnFilePickedListener? = null

    /**
     * Launch the file picker for a specific file type
     */
    fun pickFile(fileType: FileType, listener: OnFilePickedListener) {
        this.listener = listener
        val requestCode = REQUEST_CODE_BASE + fileType.requestCodeOffset

        // Try ACTION_OPEN_DOCUMENT first (more modern, persistent permissions)
        val openDocIntent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = fileType.mimeType
        }

        if (tryStartActivityForResult(openDocIntent, requestCode)) {
            Log.d(TAG, "Launched ACTION_OPEN_DOCUMENT for ${fileType.name}")
            return
        }

        // Fallback to ACTION_GET_CONTENT (broader compatibility)
        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = fileType.mimeType
        }

        if (tryStartActivityForResult(getContentIntent, requestCode)) {
            Log.d(TAG, "Launched ACTION_GET_CONTENT for ${fileType.name}")
            return
        }

        // No file picker available
        val errorMsg = "No file picker app available on this device"
        Log.e(TAG, errorMsg)
        listener.onPickerError(errorMsg)
        showToast(errorMsg)
    }

    /**
     * Attempts to start an activity for result, returning true if successful
     */
    private fun tryStartActivityForResult(intent: Intent, requestCode: Int): Boolean {
        return try {
            // Check if any activity can handle this intent
            val resolveInfo = activity.packageManager.resolveActivity(intent, 0)
            if (resolveInfo != null) {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, requestCode)
                true
            } else {
                Log.d(TAG, "No activity found to handle intent: ${intent.action}")
                false
            }
        } catch (e: ActivityNotFoundException) {
            Log.d(TAG, "ActivityNotFoundException for intent: ${intent.action}")
            false
        }
    }

    /**
     * Handle activity result - call this from Activity.onActivityResult
     * Returns true if this result was handled by FilePicker
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        // Check if this is one of our request codes
        val offset = requestCode - REQUEST_CODE_BASE
        val fileType = FileType.entries.find { it.requestCodeOffset == offset } ?: return false

        when (resultCode) {
            Activity.RESULT_OK -> {
                data?.data?.let { uri ->
                    Log.d(TAG, "File picked: $uri (type: ${fileType.name})")
                    listener?.onFilePicked(uri, fileType)
                } ?: run {
                    Log.e(TAG, "File picker returned OK but no data")
                    listener?.onPickerError("No file selected")
                }
            }
            Activity.RESULT_CANCELED -> {
                Log.d(TAG, "File picker cancelled")
                listener?.onPickerCancelled()
            }
            else -> {
                Log.e(TAG, "Unknown result code: $resultCode")
                listener?.onPickerError("Unknown error")
            }
        }

        return true
    }

    /**
     * Get the request code for a specific file type
     */
    fun getRequestCode(fileType: FileType): Int = REQUEST_CODE_BASE + fileType.requestCodeOffset

    /**
     * Check if a request code belongs to FilePicker
     */
    fun isFilePickerRequestCode(requestCode: Int): Boolean {
        val offset = requestCode - REQUEST_CODE_BASE
        return FileType.entries.any { it.requestCodeOffset == offset }
    }

    private fun showToast(message: String) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
