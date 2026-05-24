package com.everyday.shared.transport

import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot
import com.everyday.shared.sync.SpeedSnapshot

interface SyncMessenger {
    fun isConnected(): Boolean
    fun sendSyncRequest(request: SyncRequest): Boolean
    fun sendSyncSnapshot(snapshot: SyncSnapshot): Boolean
    fun sendSyncError(error: SyncError): Boolean
    fun sendSpeedSnapshot(snapshot: SpeedSnapshot): Boolean
}
