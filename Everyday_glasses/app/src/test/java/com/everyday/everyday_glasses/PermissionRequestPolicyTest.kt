package com.everyday.everyday_glasses

import android.Manifest
import android.os.Build
import com.everyday.everyday_glasses.helpers.PermissionRequestPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionRequestPolicyTest {

    @Test
    fun `startup policy never requests storage media or nearby wifi permissions`() {
        val requested = PermissionRequestPolicy.startupPermissions(
            Build.VERSION_CODES.TIRAMISU
        ) { false }

        assertTrue(requested.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertFalse(requested.contains(Manifest.permission.NEARBY_WIFI_DEVICES))
        assertFalse(requested.contains(Manifest.permission.READ_MEDIA_IMAGES))
        assertFalse(requested.contains(Manifest.permission.READ_MEDIA_VIDEO))
        assertFalse(requested.contains(Manifest.permission.READ_EXTERNAL_STORAGE))
    }

    @Test
    fun `nearby wifi is runtime gate only on android 13 and newer`() {
        assertEquals(
            Manifest.permission.NEARBY_WIFI_DEVICES,
            PermissionRequestPolicy.wifiDirectRuntimePermission(
                Build.VERSION_CODES.TIRAMISU
            )
        )
        assertEquals(
            null,
            PermissionRequestPolicy.wifiDirectRuntimePermission(Build.VERSION_CODES.S)
        )
    }

    @Test
    fun `startup policy dispatch inputs are independent`() {
        val requested = PermissionRequestPolicy.startupPermissions(
            Build.VERSION_CODES.TIRAMISU
        ) { it == Manifest.permission.BLUETOOTH_CONNECT }

        assertFalse(requested.contains(Manifest.permission.BLUETOOTH_CONNECT))
        assertTrue(requested.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(requested.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}
