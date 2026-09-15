package com.everyday.everyday_glasses.bindings

import com.everyday.everyday_glasses.controllers.GooglePhoneAuthStatus
import com.everyday.everyday_glasses.data_models.GoogleAccountSummary
import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleTranscript
import com.everyday.shared.transport.AssistantControl
import com.everyday.shared.transport.DestinationSearchError
import com.everyday.shared.transport.DestinationSearchResult
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportResult
import com.everyday.shared.transport.TransportMessage

/** Receives RFCOMM transport availability and human-readable status updates. */
interface TransportConnectionListener {
    fun onStatusChanged(status: String) = Unit
    fun onConnectionChanged(connected: Boolean) = Unit
}

/** Receives remote pointer, keyboard, and clipboard input from the paired phone. */
interface RemoteInputListener {
    fun onPointerReceived(pointer: TransportMessage.Pointer) = Unit
    fun onKeyReceived(key: String) = Unit
    fun onLongPressWithClipboard(clipboard: String?) = Unit
    fun onClipboardReceived(clipboard: String) = Unit
}

/** Receives non-input content and device-state messages from the paired phone. */
interface RemoteContentListener {
    fun onDeviceStatusReceived(status: TransportMessage.DeviceStatus) = Unit
    fun onImageReceived(imageData: ByteArray, fileName: String) = Unit
    fun onOpenBrowserRequested(address: String) = Unit
}

/** Receives phone screen-mirroring session state. */
interface MirrorSessionListener {
    fun onMirrorStateChanged(enabled: Boolean, ip: String?, isWifiDirect: Boolean) = Unit
}

/** Receives the paired phone's Google authorization state. */
interface RemoteAuthListener {
    fun onGoogleAuthStateReceived(
        status: GooglePhoneAuthStatus,
        account: GoogleAccountSummary?,
        detail: String?
    ) = Unit
}

/** Receives phone-originated data synchronization responses. */
interface SyncMessageListener {
    fun onSyncSnapshotReceived(snapshot: SyncSnapshot) = Unit
    fun onSyncErrorReceived(error: SyncError) = Unit
}

/** Receives phone-originated route calculation responses. */
interface RouteMessageListener {
    fun onRouteResultReceived(result: RouteTransportResult) = Unit
    fun onRouteErrorReceived(error: RouteTransportError) = Unit
}

/** Receives phone-originated destination search responses. */
interface DestinationSearchMessageListener {
    fun onDestinationSearchResultReceived(result: DestinationSearchResult) = Unit
    fun onDestinationSearchErrorReceived(error: DestinationSearchError) = Unit
}

/** Receives phone-originated subtitle status and transcript updates. */
interface SubtitleMessageListener {
    fun onSubtitleStatusReceived(status: SubtitleStatus) = Unit
    fun onSubtitleTranscriptReceived(transcript: SubtitleTranscript) = Unit
}

/** Receives phone-originated assistant control commands. */
interface AssistantControlListener {
    fun onAssistantControlReceived(control: AssistantControl) = Unit
}
