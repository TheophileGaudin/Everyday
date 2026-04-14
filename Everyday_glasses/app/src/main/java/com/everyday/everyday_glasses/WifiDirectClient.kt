package com.everyday.everyday_glasses

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Handles WiFi Direct connection on the glasses side.
 * Discovers and connects to the phone's WiFi Direct group.
 */
class WifiDirectClient(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectClient"
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val handler = Handler(Looper.getMainLooper())

    var isWifiDirectEnabled = false
        private set
    var isConnected = false
        private set
    var groupOwnerAddress: String? = null
        private set

    // Callbacks
    var onConnected: ((groupOwnerIp: String) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPeersAvailable: ((List<WifiP2pDevice>) -> Unit)? = null

    fun initialize() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "WiFi Direct not supported")
            onError?.invoke("WiFi Direct not supported")
            return
        }

        channel = wifiP2pManager?.initialize(context, context.mainLooper) {
            Log.d(TAG, "Channel disconnected")
        }

        registerReceiver()
        Log.d(TAG, "WiFi Direct client initialized")
    }

    private fun registerReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        isWifiDirectEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        Log.d(TAG, "WiFi P2P enabled: $isWifiDirectEnabled")
                    }

                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers()
                    }

                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, android.net.NetworkInfo::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }

                        if (networkInfo?.isConnected == true) {
                            requestConnectionInfo()
                        } else {
                            Log.d(TAG, "Disconnected from WiFi Direct")
                            isConnected = false
                            groupOwnerAddress = null
                            handler.post { onDisconnected?.invoke() }
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

    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        Log.d(TAG, "Starting peer discovery...")

        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started")
            }

            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        wifiP2pManager?.requestPeers(channel) { peers ->
            val deviceList = peers?.deviceList?.toList() ?: emptyList()
            Log.d(TAG, "Found ${deviceList.size} peers")
            handler.post { onPeersAvailable?.invoke(deviceList) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            if (info?.groupFormed == true) {
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                isConnected = true
                Log.d(TAG, "Connected! Group owner: $groupOwnerAddress")

                groupOwnerAddress?.let { ip ->
                    handler.post { onConnected?.invoke(ip) }
                }
            }
        }
    }

    /**
     * Connect to a specific WiFi Direct device (the phone).
     */
    @SuppressLint("MissingPermission")
    fun connectToDevice(device: WifiP2pDevice) {
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
                handler.post { onError?.invoke("Connection failed: $reasonStr") }
            }
        })
    }

    /**
     * Check if an IP address is in the WiFi Direct range.
     */
    fun isWifiDirectIp(ip: String): Boolean {
        return ip.startsWith("192.168.49.")
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        wifiP2pManager?.removeGroup(channel, null)
    }

    @SuppressLint("MissingPermission")
    fun stopDiscovery() {
        wifiP2pManager?.stopPeerDiscovery(channel, null)
    }

    fun release() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        receiver = null
        stopDiscovery()
    }
}