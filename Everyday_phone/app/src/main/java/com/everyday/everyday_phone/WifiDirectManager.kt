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
import java.security.SecureRandom

/**
 * Manages the phone's Wi-Fi Direct (P2P) group, the direct link screen mirroring uses: the phone
 * owns the group, and the glasses join it with its name and passphrase, sent over Bluetooth. No
 * router is involved, and no peer discovery or connection prompt is needed.
 */
class WifiDirectManager(private val context: Context) {

    /** The phone's group, as the glasses need it: where to connect, and how to join. */
    data class MirrorGroup(val ownerIp: String, val networkName: String, val passphrase: String)

    /** Receives Wi-Fi Direct availability, peer discovery, and group lifecycle events. */
    interface ConnectionListener {
        fun onGroupFormed(ownerIp: String) = Unit
        fun onGroupRemoved() = Unit
        fun onError(error: String) = Unit
        fun onStateChanged(enabled: Boolean) = Unit
        fun onPeersChanged(peers: List<WifiP2pDevice>) = Unit
    }

    companion object {
        private const val TAG = "WifiDirectManager"
        const val GROUP_OWNER_PORT = 5050  // Same port as regular streaming
        private const val PREFS = "mirror_wifi_direct"
        private const val KEY_NAME = "network_name"
        private const val KEY_PASSPHRASE = "passphrase"
        private const val GROUP_POLL_MS = 400L
        private const val GROUP_POLL_ATTEMPTS = 25
        private const val ALPHANUMERIC = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
    }

    /** True while the current group is one this manager created for mirroring, so it removes it again. */
    private var ownsMirrorGroup = false

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
    
    var connectionListener: ConnectionListener? = null

    fun initialize() {
        wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (wifiP2pManager == null) {
            Log.e(TAG, "WiFi Direct not supported on this device")
            connectionListener?.onError("WiFi Direct not supported")
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
                        handler.post { connectionListener?.onStateChanged(isWifiDirectEnabled) }
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
                            handler.post { connectionListener?.onGroupRemoved() }
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
                        handler.post { connectionListener?.onGroupFormed(it) }
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
            handler.post { connectionListener?.onPeersChanged(deviceList) }
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
                handler.post { connectionListener?.onError("Group creation failed: $reasonStr") }
            }
        })
    }

    /**
     * Makes sure the phone owns a Wi-Fi Direct group the glasses can join, then reports it; null when
     * it can't (Wi-Fi Direct off, permission missing, Android older than 10, or the phone is already
     * a member of another device's group). Reuses a group the phone already owns. Main thread.
     */
    @SuppressLint("MissingPermission")
    fun ensureMirrorGroup(onReady: (MirrorGroup?) -> Unit) {
        val manager = wifiP2pManager
        val ch = channel
        if (manager == null || ch == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            fileLog("Mirror group: Wi-Fi Direct unavailable")
            onReady(null)
            return
        }
        try {
            manager.requestGroupInfo(ch) { group ->
                when {
                    group != null && group.isGroupOwner -> {
                        fileLog("Mirror group: reusing ${group.networkName}")
                        // Ours from an earlier run of the app: remove it with the mirror, as if just created.
                        if (group.networkName == mirrorCredentials().first) ownsMirrorGroup = true
                        awaitMirrorGroup(onReady)
                    }
                    group != null -> {
                        fileLog("Mirror group: the phone is a client of ${group.networkName}; can't host")
                        onReady(null)
                    }
                    else -> createMirrorGroup(onReady)
                }
            }
        } catch (e: SecurityException) {
            fileLog("Mirror group: permission missing: ${e.message}")
            onReady(null)
        }
    }

    @SuppressLint("MissingPermission")
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun createMirrorGroup(onReady: (MirrorGroup?) -> Unit) {
        val (name, passphrase) = mirrorCredentials()
        val config = WifiP2pConfig.Builder()
            .setNetworkName(name)
            .setPassphrase(passphrase)
            .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
            .build()
        val ch = channel ?: return onReady(null)
        wifiP2pManager?.createGroup(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                fileLog("Mirror group: creating $name")
                ownsMirrorGroup = true
                awaitMirrorGroup(onReady)
            }

            override fun onFailure(reason: Int) {
                fileLog("Mirror group: creation failed ($reason)")
                onReady(null)
            }
        })
    }

    /** Polls until the group is up and the phone has its address on it. */
    @SuppressLint("MissingPermission")
    private fun awaitMirrorGroup(onReady: (MirrorGroup?) -> Unit, attempt: Int = 0) {
        wifiP2pManager?.requestGroupInfo(channel) { group ->
            val ip = getWifiDirectIp()
            val name = group?.networkName
            val passphrase = group?.passphrase
            if (group != null && group.isGroupOwner && ip != null && name != null && passphrase != null) {
                fileLog("Mirror group ready: $name at $ip")
                onReady(MirrorGroup(ip, name, passphrase))
            } else if (attempt >= GROUP_POLL_ATTEMPTS) {
                fileLog("Mirror group: not ready in time (group=${group != null}, ip=$ip)")
                onReady(null)
            } else {
                handler.postDelayed({ awaitMirrorGroup(onReady, attempt + 1) }, GROUP_POLL_MS)
            }
        } ?: onReady(null)
    }

    /** Removes the group if this manager created it for mirroring. */
    fun removeMirrorGroup() {
        if (!ownsMirrorGroup) return
        ownsMirrorGroup = false
        removeGroup()
    }

    /** The group's name and passphrase, made once and kept, so the glasses always join the same group. */
    private fun mirrorCredentials(): Pair<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedName = prefs.getString(KEY_NAME, null)
        val savedPassphrase = prefs.getString(KEY_PASSPHRASE, null)
        if (savedName != null && savedPassphrase != null) return savedName to savedPassphrase
        val random = SecureRandom()
        fun randomText(length: Int) = (1..length).map { ALPHANUMERIC[random.nextInt(ALPHANUMERIC.length)] }.joinToString("")
        // Wi-Fi Direct group names must start with "DIRECT-" and two characters.
        val name = "DIRECT-${randomText(2)}-Everyday"
        val passphrase = randomText(16)
        prefs.edit().putString(KEY_NAME, name).putString(KEY_PASSPHRASE, passphrase).apply()
        return name to passphrase
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
