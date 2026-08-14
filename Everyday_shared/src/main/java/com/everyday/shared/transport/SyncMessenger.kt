package com.everyday.shared.transport

import com.everyday.shared.sync.SyncError
import com.everyday.shared.sync.SyncRequest
import com.everyday.shared.sync.SyncSnapshot

interface SyncMessenger {
    fun isConnected(): Boolean
    fun sendSyncRequest(request: SyncRequest): Boolean
    fun sendSyncSnapshot(snapshot: SyncSnapshot): Boolean
    fun sendSyncError(error: SyncError): Boolean
}
