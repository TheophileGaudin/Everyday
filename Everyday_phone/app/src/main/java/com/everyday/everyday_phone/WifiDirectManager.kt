package com.everyday.everyday_phone

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
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manages WiFi Direct (P2P) connections as fallback when devices
 * aren't on the same WiFi network.
 */
class WifiDirectManager(private val context: Context) {

    companion object {
        private const val TAG = "WifiDirectManager"
        const val GROUP_OWNER_PORT = 5050  // Same port as regular streaming
    }

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    
    private val handler = Handler(Looper.getMainLooper())
    
    // State
    var isGroupOwner = false
        private set
    var groupOwnerAddress: String? = null
        private set
    var isWifiDirectEnabled = false
        private set
    var isGroupFormed = false
        private set
    
    // Callbacks
    var onGroupFormed: ((ownerIp: String) -> Unit)? = null
    var onGroupRemoved: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChanged: ((enabled: Boolean) -> Unit)? = null
    var onPeersChanged: ((List<WifiP2pDevice>) -> Unit)? = null

    fun initialize() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "WiFi Direct not supported on this device")
            onError?.invoke("WiFi Direct not supported")
            return
        }
        
        channel = wifiP2pManager?.initialize(context, context.mainLooper) { 
            Log.d(TAG, "Channel disconnected")
        }
        
        registerReceiver()
        Log.d(TAG, "WiFi Direct manager initialized")
    }

    private fun registerReceiver() {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        isWifiDirectEnabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        Log.d(TAG, "WiFi P2P state: $isWifiDirectEnabled")
                        handler.post { onStateChanged?.invoke(isWifiDirectEnabled) }
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
                            Log.d(TAG, "WiFi P2P disconnected")
                            isGroupFormed = false
                            isGroupOwner = false
                            groupOwnerAddress = null
                            handler.post { onGroupRemoved?.invoke() }
                        }
                    }
                    
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        requestPeers()
                    }
                    
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        }
                        Log.d(TAG, "This device: ${device?.deviceName}, status=${device?.status}")
                    }
                }
            }
        }
        
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        
        context.registerReceiver(receiver, intentFilter)
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo() {
        wifiP2pManager?.requestConnectionInfo(channel) { info ->
            if (info != null) {
                isGroupFormed = info.groupFormed
                isGroupOwner = info.isGroupOwner
                groupOwnerAddress = info.groupOwnerAddress?.hostAddress
                
                Log.d(TAG, "Connection info: groupFormed=$isGroupFormed, isOwner=$isGroupOwner, ownerAddr=$groupOwnerAddress")
                
                if (isGroupFormed && isGroupOwner) {
                    // We're the group owner - get our P2P interface IP
                    val p2pIp = getWifiDirectIp()
                    Log.d(TAG, "Group formed, our P2P IP: $p2pIp")
                    p2pIp?.let { 
                        handler.post { onGroupFormed?.invoke(it) }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers() {
        wifiP2pManager?.requestPeers(channel) { peers ->
            val deviceList = peers?.deviceList?.toList() ?: emptyList()
            Log.d(TAG, "Peers found: ${deviceList.size}")
            deviceList.forEach { device ->
                Log.d(TAG, "  - ${device.deviceName} (${device.deviceAddress})")
            }
            handler.post { onPeersChanged?.invoke(deviceList) }
        }
    }

    /**
     * Create a WiFi Direct group where this device is the Group Owner.
     * This makes the phone act like an access point that glasses can connect to.
     */
    @SuppressLint("MissingPermission")
    fun createGroup() {
        Log.d(TAG, "Creating WiFi Direct group...")
        
        wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group creation initiated")
                fileLog("WiFi Direct group creation initiated")
            }
            
            override fun onFailure(reason: Int) {
                val reasonStr = when (reason) {
                    WifiP2pManager.P2P_UNSUPPORTED -> "P2P unsupported"
                    WifiP2pManager.BUSY -> "System busy"
                    WifiP2pManager.ERROR -> "Internal error"
                    else -> "Unknown ($reason)"
                }
                Log.e(TAG, "Group creation failed: $reasonStr")
                fileLog("WiFi Direct group creation failed: $reasonStr")
                handler.post { onError?.invoke("Group creation failed: $reasonStr") }
            }
        })
    }

    /**
     * Remove the current WiFi Direct group.
     */
    @SuppressLint("MissingPermission")
    fun removeGroup() {
        Log.d(TAG, "Removing WiFi Direct group...")
        
        wifiP2pManager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Group removed")
                isGroupFormed = false
                isGroupOwner = false
                groupOwnerAddress = null
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Failed to remove group: $reason")
            }
        })
    }

    /**
     * Start peer discovery to find nearby WiFi Direct devices.
     */
    @SuppressLint("MissingPermission")
    fun discoverPeers() {
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.d(TAG, "Peer discovery started")
            }
            
            override fun onFailure(reason: Int) {
                Log.e(TAG, "Peer discovery failed: $reason")
            }
        })
    }

    /**
     * Get the IP address of the WiFi Direct interface (p2p-wlan0-x or similar).
     */
    private fun getWifiDirectIp(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val name = networkInterface.name
                
                // WiFi Direct interfaces are typically named p2p-wlan0-X or p2p0
                if (name.startsWith("p2p") || name.contains("p2p")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            val ip = address.hostAddress
                            Log.d(TAG, "Found P2P IP: $ip on $name")
                            return ip
                        }
                    }
                }
            }
            
            // Fallback: check for 192.168.49.x range (typical WiFi Direct)
            val interfaces2 = NetworkInterface.getNetworkInterfaces()
            while (interfaces2.hasMoreElements()) {
                val networkInterface = interfaces2.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val ip = address.hostAddress
                        if (ip?.startsWith("192.168.49.") == true) {
                            Log.d(TAG, "Found P2P IP (by range): $ip on ${networkInterface.name}")
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi Direct IP", e)
        }
        return null
    }

    fun release() {
        try {
            receiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering receiver", e)
        }
        receiver = null
        
        removeGroup()
        channel = null
        wifiP2pManager = null
    }
}
