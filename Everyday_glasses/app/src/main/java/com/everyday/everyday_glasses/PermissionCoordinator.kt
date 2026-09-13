package com.everyday.everyday_glasses

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat

/** Application reactions to permission outcomes. */
interface PermissionCoordinatorCallbacks {
    fun onBluetoothPermissionAvailable()
    fun onLocationPermissionAvailable()
    fun onLocationPermissionDenied()

    /**
     * Called after the file-browser permission route has completed. This
     * preserves the current behavior of opening the browser even when a
     * runtime media permission was denied so the widget can show its message.
     */
    fun onFileBrowserAccessHandled()

    fun onAssistantAudioPermissionResult(granted: Boolean)
}

/**
 * Owns Android permission checks, requests, special-access routing, and result
 * classification. Feature behavior remains in the supplied callbacks.
 */
class PermissionCoordinator(
    private val activity: Activity,
    private val callbacks: PermissionCoordinatorCallbacks
) {

    companion object {
        private const val TAG = "PermissionCoordinator"

        private const val REQUEST_STARTUP_PERMISSIONS = 1
        private const val REQUEST_STORAGE_PERMISSION = 2
        private const val REQUEST_ASSISTANT_AUDIO_PERMISSION = 3
    }

    private var openFileBrowserAfterAllFilesGrant = false
    private var released = false

    // ---------------------------------------------------------------------
    // Public permission queries
    // ---------------------------------------------------------------------

    fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            hasPermission(Manifest.permission.BLUETOOTH)
        }
    }

    fun hasAnyLocationPermission(): Boolean =
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) ||
                hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasMicrophonePermission(): Boolean =
        hasPermission(Manifest.permission.RECORD_AUDIO)

    /**
     * Preserves the current file-browser access policy.
     *
     * Android 11+ prefers MANAGE_EXTERNAL_STORAGE. Older Android versions use
     * their applicable runtime storage permission.
     */
    fun hasFileBrowserAccess(): Boolean {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(Manifest.permission.READ_MEDIA_IMAGES) &&
                    hasPermission(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        Log.d(
            TAG,
            "File-browser access check: granted=$granted, SDK=${Build.VERSION.SDK_INT}"
        )
        return granted
    }

    // ---------------------------------------------------------------------
    // Startup permission flow
    // ---------------------------------------------------------------------

    /**
     * Preserves MainActivity's current startup behavior:
     *
     * - If Bluetooth and location already exist, start the phone immediately
     *   and do not proactively request optional storage permission.
     * - Otherwise request the current combined batch of missing Bluetooth,
     *   location, and storage/media permissions.
     */
    fun handleStartupPermissions() {
        if (released) return

        if (hasBluetoothPermission() && hasAnyLocationPermission()) {
            callbacks.onBluetoothPermissionAvailable()
            return
        }

        val missingPermissions = buildStartupPermissionRequest()
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions.toTypedArray(),
                REQUEST_STARTUP_PERMISSIONS
            )
            return
        }

        // Handles a permission-state race between the checks above and request
        // construction without leaving startup stuck.
        dispatchCurrentStartupCapabilities()
    }

    private fun buildStartupPermissionRequest(): List<String> = buildList {
        addMissingBluetoothPermissions(this)
        addMissingLocationPermissions(this)
        addMissingStartupStoragePermissions(this)
    }

    private fun addMissingBluetoothPermissions(target: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                target += Manifest.permission.BLUETOOTH_CONNECT
            }
        } else if (!hasPermission(Manifest.permission.BLUETOOTH)) {
            target += Manifest.permission.BLUETOOTH
            target += Manifest.permission.BLUETOOTH_ADMIN
        }
    }

    private fun addMissingLocationPermissions(target: MutableList<String>) {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            target += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            target += Manifest.permission.ACCESS_COARSE_LOCATION
        }
    }

    private fun addMissingStartupStoragePermissions(target: MutableList<String>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                target += Manifest.permission.READ_MEDIA_IMAGES
            }
            if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) {
                target += Manifest.permission.READ_MEDIA_VIDEO
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                target += Manifest.permission.READ_EXTERNAL_STORAGE
            }
        }
    }

    private fun dispatchCurrentStartupCapabilities() {
        if (hasBluetoothPermission()) {
            callbacks.onBluetoothPermissionAvailable()
        }

        if (hasAnyLocationPermission()) {
            callbacks.onLocationPermissionAvailable()
        } else {
            callbacks.onLocationPermissionDenied()
        }
    }

    // ---------------------------------------------------------------------
    // File-browser permission flow
    // ---------------------------------------------------------------------

    /** Entry point for the normal "open file browser" action. */
    fun requestFileBrowserAccess() {
        if (released) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            requestAllFilesAccessForBrowser()
        } else if (hasFileBrowserAccess()) {
            callbacks.onFileBrowserAccessHandled()
        } else {
            requestStoragePermissions()
        }
    }

    /** Entry point for the widget's explicit storage-permission action. */
    fun requestStoragePermissions() {
        if (released) return

        val missingPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Preserved from current behavior even though notification
                // permission is not technically a storage permission.
                if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (!hasPermission(Manifest.permission.READ_MEDIA_IMAGES)) {
                    add(Manifest.permission.READ_MEDIA_IMAGES)
                }
                if (!hasPermission(Manifest.permission.READ_MEDIA_VIDEO)) {
                    add(Manifest.permission.READ_MEDIA_VIDEO)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (!hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                    add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
        }

        if (missingPermissions.isNotEmpty()) {
            Log.d(TAG, "Requesting storage permissions: $missingPermissions")
            ActivityCompat.requestPermissions(
                activity,
                missingPermissions.toTypedArray(),
                REQUEST_STORAGE_PERMISSION
            )
        } else {
            Log.d(TAG, "Storage permission already available")
            callbacks.onFileBrowserAccessHandled()
        }
    }

    private fun requestAllFilesAccessForBrowser() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            requestStoragePermissions()
            return
        }

        openFileBrowserAfterAllFilesGrant = true

        val appSettingsIntent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${activity.packageName}")
        )

        runCatching {
            activity.startActivity(appSettingsIntent)
        }.recoverCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            )
        }.onFailure { error ->
            openFileBrowserAfterAllFilesGrant = false
            Log.e(TAG, "Unable to open all-files access settings", error)
        }
    }

    /** Forward from MainActivity.onResume(). */
    fun onActivityResumed() {
        if (released || !openFileBrowserAfterAllFilesGrant) return

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
        ) {
            openFileBrowserAfterAllFilesGrant = false
            callbacks.onFileBrowserAccessHandled()
        }
    }

    // ---------------------------------------------------------------------
    // Assistant microphone permission
    // ---------------------------------------------------------------------

    fun requestMicrophonePermission() {
        if (released) return

        ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_ASSISTANT_AUDIO_PERMISSION
        )
    }

    // ---------------------------------------------------------------------
    // Runtime permission result routing
    // ---------------------------------------------------------------------

    /**
     * Returns true when this coordinator owns the supplied request code.
     */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ): Boolean {
        if (released) return false

        return when (requestCode) {
            REQUEST_STARTUP_PERMISSIONS -> {
                // Query current OS state instead of inferring capability from
                // one entry in a potentially mixed permission-result array.
                dispatchCurrentStartupCapabilities()
                true
            }

            REQUEST_STORAGE_PERMISSION -> {
                logStorageResults(permissions, grantResults)

                // Preserve current behavior: open the browser whether granted
                // or denied so the browser can present its own access message.
                callbacks.onFileBrowserAccessHandled()
                true
            }

            REQUEST_ASSISTANT_AUDIO_PERMISSION -> {
                callbacks.onAssistantAudioPermissionResult(
                    hasMicrophonePermission()
                )
                true
            }

            else -> false
        }
    }

    private fun logStorageResults(
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        permissions.forEachIndexed { index, permission ->
            if (permission !in storageRuntimePermissions()) return@forEachIndexed

            val granted =
                grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            Log.d(
                TAG,
                "Storage permission ${if (granted) "granted" else "denied"}: " +
                        permission
            )
        }
    }

    private fun storageRuntimePermissions(): Set<String> = setOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_EXTERNAL_STORAGE
    )

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(activity, permission) ==
                PackageManager.PERMISSION_GRANTED

    fun release() {
        released = true
        openFileBrowserAfterAllFilesGrant = false
    }
}