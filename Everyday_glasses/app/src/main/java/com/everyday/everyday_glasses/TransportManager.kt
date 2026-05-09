package com.everyday.everyday_glasses

import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.transport.SyncMessenger

class TransportManager(
    private val rfcommClientProvider: () -> RfcommClient?
) : SyncMessenger {
    override fun isConnected(): Boolean =
        rfcommClientProvider()?.isConnected() == true

    override fun sendSyncRequest(request: SyncRequest): Boolean {
        val client = connectedClient() ?: return false
        client.requestSync(
            channels = request.channels,
            force = request.force,
            reason = request.reason,
            countryCode = request.countryCode,
            financeSymbol = request.financeSymbol,
            financeRange = request.financeRange
        )
        return true
    }

    override fun sendSyncSnapshot(snapshot: SyncSnapshot): Boolean = false

    override fun sendSyncError(error: SyncError): Boolean = false

    fun sendTextFieldFocus(focused: Boolean, fieldId: String? = null): Boolean {
        val client = connectedClient() ?: return false
        client.sendTextFieldFocus(focused, fieldId)
        return true
    }

    fun sendMirrorRequest(start: Boolean): Boolean {
        val client = connectedClient() ?: return false
        client.sendMirrorRequest(start)
        return true
    }

    fun sendSubtitleControl(control: SubtitleControl): Boolean {
        val client = connectedClient() ?: return false
        client.sendSubtitleControl(control)
        return true
    }

    fun requestClipboard(): Boolean {
        val client = connectedClient() ?: return false
        client.requestClipboard()
        return true
    }

    fun requestGooglePhoneAuthorization(): Boolean {
        val client = connectedClient() ?: return false
        client.requestGooglePhoneAuthorization()
        return true
    }

    fun disconnectGooglePhoneAuth(): Boolean {
        val client = connectedClient() ?: return false
        client.disconnectGooglePhoneAuth()
        return true
    }

    private fun connectedClient(): RfcommClient? =
        rfcommClientProvider()?.takeIf { it.isConnected() }
}
