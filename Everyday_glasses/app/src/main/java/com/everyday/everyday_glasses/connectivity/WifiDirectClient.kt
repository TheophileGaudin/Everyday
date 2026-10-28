package com.everyday.everyday_glasses.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Handles Wi-Fi Direct connection on the glasses side.
 * Discovers and connects to the phone's Wi-Fi Direct group.
 */
class WifiDirectClient(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectClient"
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var initialized = false
    private var released = false

    private val handler = Handler(Looper.getMainLooper())

    var isWifiDirectEnabled = false
        private set
    var isConnected = false
        private set
    var groupOwnerAddress: String? = null
        private set

    // Callbacks
    var connectionListener: WifiDirectConnectionListener? = null

    /** Exposes the platform gate so the owner can request it before discovery. */
    fun hasRequiredRuntimePermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            null
        }
        return if (permission != null) {
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        } else {
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    fun initialize() {
        if (initialized || released) return

        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "WiFi Direct not supported")
            connectionListener?.onError("WiFi Direct not supported")
            return
        }

        initialized = true
        channel = wifiP2pManager?.initialize(context, context.mainLooper) {
            Log.d(TAG, "Channel disconnected")
        }

        registerReceiver()
        Log.d(TAG, "WiFi Direct client initialized")
    }

    private fun registerReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (released) return

                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        isWifiDirectEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        Log.d(TAG, "WiFi P2P enabled: $isWifiDirectEnabled")
                        connectionListener?.onStateChanged(isWifiDirectEnabled)
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers()
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val p2pInfo: WifiP2pInfo? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(
                                    WifiP2pManager.EXTRA_WIFI_P2P_INFO,
                                    WifiP2pInfo::class.java
                                )
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(
                                    WifiP2pManager.EXTRA_WIFI_P2P_INFO
                                )
                            }

                        if (p2pInfo?.groupFormed == true) {
                            requestConnectionInfo()
                        } else {
                            Log.d(TAG, "Disconnected from WiFi Direct")
                            isConnected = false
                            groupOwnerAddress = null
                            handler.post { connectionListener?.onDisconnected() }
                        }
                    }
                }
            }
        }

        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, intentFilter)
        }
    }

    /** Starts discovery when the caller has granted the platform's P2P gate. */
    @SuppressLint("MissingPermission") // Guarded by canUseWifiDirect().
    fun discoverPeers(): Boolean {
        if (!canUseWifiDirect()) return false

        Log.d(TAG, "Starting peer discovery...")

        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
            }
        })
        return true
    }

    @SuppressLint("MissingPermission") // Guarded by canUseWifiDirect().
    private fun requestPeers() {
        if (!canUseWifiDirect()) return

        wifiP2pManager?.requestPeers(channel) { peers ->
            val deviceList = peers?.deviceList?.toList() ?: emptyList()
            Log.d(TAG, "Found ${deviceList.size} peers")
            handler.post { connectionListener?.onPeersAvailable(deviceList) }
        }
    }

    private fun requestConnectionInfo() {
        if (!canUseWifiDirect()) return

        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            if (info?.groupFormed == true) {
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                isConnected = true
                Log.d(TAG, "Connected! Group owner: $groupOwnerAddress")

                groupOwnerAddress?.let { ip ->
                    handler.post { connectionListener?.onConnected(ip) }
                }
            }
        }
    }

    /**
     * Joins the phone's Wi-Fi Direct group by its name and passphrase (sent over Bluetooth), with no
     * peer discovery and no prompt on either device. Leaves any other group first. The result
     * arrives as [WifiDirectConnectionListener.onConnected] or [WifiDirectConnectionListener.onError].
     * Needs Android 10.
     */
    @SuppressLint("MissingPermission") // Guarded by canUseWifiDirect().
    fun joinGroup(networkName: String, passphrase: String): Boolean {
        if (!canUseWifiDirect()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            .build()
        Log.d(TAG, "Joining group $networkName")
        val connect = {
            wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "Join initiated")
                }

                override fun onFailure(reason: Int) {
                    Log.e(TAG, "Join failed: $reason")
                    handler.post { connectionListener?.onError("Join failed ($reason)") }
                }
            })
        }
        // A stale group (an earlier session, another app's) would make the join fail as busy.
        wifiP2pManager?.requestGroupInfo(channel) { group ->
            if (released) return@requestGroupInfo
            if (group == null) {
                connect()
            } else if (group.networkName == networkName && !group.isGroupOwner) {
                Log.d(TAG, "Already in group $networkName")
                requestConnectionInfo()
            } else {
                Log.d(TAG, "Leaving group ${group.networkName} first")
                wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { connect() }
                    override fun onFailure(reason: Int) { connect() }
                })
            }
        }
        return true
    }

    /**
     * Connect to a specific Wi-Fi Direct device (the phone).
     */
    @SuppressLint("MissingPermission") // Guarded by canUseWifiDirect().
    fun connectToDevice(device: WifiP2pDevice): Boolean {
        if (!canUseWifiDirect()) return false

        Log.d(TAG, "Connecting to: ${device.deviceName} (${device.deviceAddress})")

        val config = WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            groupOwnerIntent = 0  // Prefer to be client (phone is owner)
        }

        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Connection initiated")
            }

            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                    WifiP2pManager.BUSY -> "System busy"
                    WifiP2pManager.ERROR -> "Internal error"
                    else -> "Unknown ($reason)"
                }
                Log.e(TAG, "Connection failed: $reasonStr")
                handler.post { connectionListener?.onError("Connection failed: $reasonStr") }
            }
        })
        return true
    }

    fun disconnect(): Boolean {
        if (!canUseWifiDirect()) return false
        wifiP2pManager?.removeGroup(channel, null)
        return true
    }

    fun release() {
        if (released) return

        // Avoid a normal discovery callback after the owning Activity has
        // gone away. stopPeerDiscovery itself is a privileged operation, so
        // only attempt it while its runtime gate is still available.
        if (canUseWifiDirect()) {
            runCatching { wifiP2pManager?.stopPeerDiscovery(channel, null) }
                .onFailure { Log.w(TAG, "Error stopping peer discovery", it) }
        }
        released = true
        handler.removeCallbacksAndMessages(null)

        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        receiver = null
        connectionListener = null
        channel = null
        wifiP2pManager = null
        initialized = false
        isConnected = false
        groupOwnerAddress = null
    }

    /** Returns false and reports a recoverable error instead of risking a SecurityException. */
    private fun canUseWifiDirect(): Boolean {
        if (released) return false
        if (wifiP2pManager == null || channel == null) return false

        val granted = hasRequiredRuntimePermission()

        if (!granted) {
            Log.w(TAG, "Wi-Fi Direct operation skipped: runtime permission is missing")
            handler.post {
                if (!released) connectionListener?.onError("Wi-Fi Direct permission required")
            }
        }
        return granted
    }
}

interface WifiDirectConnectionListener {
    /** The glasses' Wi-Fi (and so Wi-Fi Direct) was switched on or off. */
    fun onStateChanged(enabled: Boolean) = Unit
    fun onConnected(groupOwnerIp: String) = Unit
    fun onDisconnected() = Unit
    fun onPeersAvailable(peers: List<WifiP2pDevice>) = Unit
    fun onError(message: String) = Unit
}