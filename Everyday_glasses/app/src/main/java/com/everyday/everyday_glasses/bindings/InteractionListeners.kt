package com.everyday.everyday_glasses.bindings

import android.net.wifi.p2p.WifiP2pDevice

interface CursorActivityObserver {
    fun onVisibilityChanged(visible: Boolean) = Unit
    fun onUserActivity() = Unit
}

interface TapGestureListener {
    fun onSingleTap(x: Float, y: Float) = Unit
    fun onDoubleTap(x: Float, y: Float) = Unit
    fun onTripleTap(x: Float, y: Float) = Unit
}

interface ThreeDofObserver {
    fun onModeChanged(enabled: Boolean) = Unit
    fun onOrientationChanged(offsetX: Float, offsetY: Float) = Unit
    fun onRollChanged(rollDegrees: Float) = Unit
    fun onTransitionProgress(progress: Float) = Unit
}

interface WakeRequestListener {
    fun onWakeRequested() = Unit
    fun onSleepRequested() = Unit
}

interface WifiDirectConnectionListener {
    fun onConnected(groupOwnerIp: String) = Unit
    fun onDisconnected() = Unit
    fun onPeersAvailable(peers: List<WifiP2pDevice>) = Unit
    fun onError(message: String) = Unit
}
