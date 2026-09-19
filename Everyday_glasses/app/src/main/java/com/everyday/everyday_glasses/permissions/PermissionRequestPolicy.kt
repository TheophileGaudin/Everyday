package com.everyday.everyday_glasses.permissions

import android.Manifest
import android.os.Build
import androidx.annotation.RequiresApi

/** Pure permission-selection rules kept separate from Activity state. */
internal object PermissionRequestPolicy {

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun wifiDirectRuntimePermission(sdkInt: Int): String? =
        if (sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            null
        }

    @RequiresApi(Build.VERSION_CODES.S)
    fun startupPermissions(
        sdkInt: Int,
        hasPermission: (String) -> Boolean
    ): List<String> = buildList {
        if (sdkInt >= Build.VERSION_CODES.S) {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else if (!hasPermission(Manifest.permission.BLUETOOTH)) {
            // These are normal permissions on legacy Android, but retaining
            // them in the request list is harmless and keeps old devices
            // compatible with the existing Bluetooth setup.
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
        }

        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (!hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }
}
