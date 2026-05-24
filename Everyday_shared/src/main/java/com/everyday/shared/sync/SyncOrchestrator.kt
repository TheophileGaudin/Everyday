package com.everyday.shared.sync

interface SyncSnapshotCache {
    fun save(snapshot: SyncSnapshot)
    fun loadAll(): SyncSnapshot
    fun load(channels: Set<SyncChannel>): SyncSnapshot
}

class SyncOrchestrator(
    private val snapshotCache: SyncSnapshotCache,
    private val requestDispatcher: (SyncRequest) -> Unit,
    private val snapshotApplier: (SyncSnapshot, Boolean) -> Unit = { _, _ -> }
) {
    fun restoreCachedSnapshots(notify: Boolean = false): SyncSnapshot {
        val snapshot = snapshotCache.loadAll()
        snapshotApplier(snapshot, notify)
        return snapshot
    }

    fun refresh(
        channels: Set<SyncChannel> = SyncChannel.ALL,
        force: Boolean,
        reason: String,
        countryCode: String? = null,
        financeSymbol: String? = null,
        financeRange: String? = null,
        financeAssetType: FinanceAssetType? = null,
        financeChartType: FinanceChartType? = null,
        financeTileId: String? = null,
        financeLiveEnabled: Boolean? = null
    ): SyncRequest {
        return dispatch(
            SyncRequest(
                channels = channels,
                force = force,
                reason = reason,
                countryCode = countryCode,
                financeSymbol = financeSymbol,
                financeRange = financeRange,
                financeAssetType = financeAssetType,
                financeChartType = financeChartType,
                financeTileId = financeTileId,
                financeLiveEnabled = financeLiveEnabled
            )
        )
    }

    fun dispatch(request: SyncRequest): SyncRequest {
        requestDispatcher(request)
        return request
    }

    fun cacheAndApplySnapshot(snapshot: SyncSnapshot, notify: Boolean = true): SyncSnapshot {
        snapshotCache.save(snapshot)
        snapshotApplier(snapshot, notify)
        return snapshot
    }

    fun cachedSnapshot(channels: Set<SyncChannel> = SyncChannel.ALL): SyncSnapshot =
        snapshotCache.load(channels)
}
