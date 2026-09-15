package com.everyday.everyday_glasses.controllers

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
import com.everyday.everyday_glasses.helpers.PermissionRequestPolicy

/** Application reactions to permission outcomes. */
interface PermissionCoordinatorCallbacks {
    fun onBluetoothPermissionAvailable()
    fun onLocationPermissionAvailable()
    fun onLocationPermissionDenied()

    /** Called when Wi‑Fi Direct may be used (or already was available). */
    fun onWifiDirectPermissionAvailable() {}

    /** Called when the user declined the Wi‑Fi Direct permission request. */
    fun onWifiDirectPermissionDenied() {}

    /**
     * Called after the file-browser permission route has completed. This
     * preserves the current behavior of opening the browser after a runtime
     * storage result so the widget can show its own access message.
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
        private const val REQUEST_WIFI_DIRECT_PERMISSION = 4
    }

    private var openFileBrowserAfterAllFilesGrant = false
    private var wifiDirectPermissionRequestInFlight = false
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

    /**
     * Returns whether the runtime permission required by Wi‑Fi Direct is
     * currently available. Android 12 and below use location permission for
     * peer discovery; Android 13+ uses NEARBY_WIFI_DEVICES.
     */
    fun hasWifiDirectPermission(): Boolean =
        PermissionRequestPolicy.wifiDirectRuntimePermission(Build.VERSION.SDK_INT)
            ?.let(::hasPermission)
            ?: hasAnyLocationPermission()

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
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else true

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
     * Requests only the permissions needed to start the phone connection and
     * location pipeline. File/media access and Wi‑Fi Direct are feature-level
     * permissions and are requested when those features are invoked.
     */
    fun handleStartupPermissions() {
        if (released) return

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

    private fun buildStartupPermissionRequest(): List<String> =
        PermissionRequestPolicy.startupPermissions(Build.VERSION.SDK_INT, ::hasPermission)

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

    /**
     * Requests Wi‑Fi Direct's runtime permission without coupling it to app
     * startup. The callback is delivered immediately when already granted.
     */
    fun requestWifiDirectPermission() {
        if (released || wifiDirectPermissionRequestInFlight) return

        if (hasWifiDirectPermission()) {
            callbacks.onWifiDirectPermissionAvailable()
            return
        }

        val permission = PermissionRequestPolicy.wifiDirectRuntimePermission(
            Build.VERSION.SDK_INT
        )

        // On Android 12 and below location is the Wi‑Fi Direct gate and is
        // already part of the independent startup capability flow.
        if (permission == null) {
            callbacks.onWifiDirectPermissionDenied()
            return
        }

        wifiDirectPermissionRequestInFlight = true
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(permission),
            REQUEST_WIFI_DIRECT_PERMISSION
        )
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (hasFileBrowserAccess()) {
                callbacks.onFileBrowserAccessHandled()
            } else {
                requestAllFilesAccessForBrowser()
            }
            return
        }

        val missingPermissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                !hasPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            ) {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
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

        if (openFileBrowserAfterAllFilesGrant) return

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

        // A settings activity returning to the foreground is the only result
        // signal available with the legacy lifecycle API. Consume the pending
        // request on every return, including Back/denial, so a later unrelated
        // grant cannot unexpectedly open the browser.
        val accessGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                Environment.isExternalStorageManager()
        openFileBrowserAfterAllFilesGrant = false
        if (accessGranted) {
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

            REQUEST_WIFI_DIRECT_PERMISSION -> {
                wifiDirectPermissionRequestInFlight = false
                if (hasWifiDirectPermission()) {
                    callbacks.onWifiDirectPermissionAvailable()
                } else {
                    callbacks.onWifiDirectPermissionDenied()
                }
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
            if (permission != Manifest.permission.READ_EXTERNAL_STORAGE) {
                return@forEachIndexed
            }

            val granted =
                grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            Log.d(
                TAG,
                "Storage permission ${if (granted) "granted" else "denied"}: " +
                        permission
            )
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ActivityCompat.checkSelfPermission(activity, permission) ==
                PackageManager.PERMISSION_GRANTED

    fun release() {
        released = true
        openFileBrowserAfterAllFilesGrant = false
        wifiDirectPermissionRequestInFlight = false
    }
}
