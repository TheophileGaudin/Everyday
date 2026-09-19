package com.everyday.everyday_glasses.map

import com.everyday.shared.transport.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface GlassesDestinationSearchTransport {
    fun isConnected(): Boolean
    fun sendDestinationSearchRequest(request: DestinationSearchRequest): Boolean
    fun sendDestinationSearchCancellation(cancellation: DestinationSearchCancellation): Boolean
}

enum class DestinationSearchFallbackReason { NOT_CONNECTED, SEND_FAILURE, HEDGE, PHONE_ERROR }

/** Receives results and fallback milestones for a phone-backed destination search. */
interface DestinationSearchListener {
    fun onDestinationSearchResult(result: DestinationSearchResult) = Unit
    fun onDestinationSearchFallback(
        request: DestinationSearchRequest,
        reason: DestinationSearchFallbackReason
    ) = Unit
    fun onDestinationSearchFailure(
        request: DestinationSearchRequest,
        error: DestinationSearchError?
    ) = Unit
}

class GlassesDestinationSearchCoordinator(
    private val transportProvider: () -> GlassesDestinationSearchTransport?,
    private val hedgeMs: Long = 2_500L,
    private val deadlineMs: Long = 8_000L,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DestinationSearchDeadline").apply { isDaemon = true }
    }
) {
    var listener: DestinationSearchListener? = null
    private data class Active(val request: DestinationSearchRequest, var hedge: ScheduledFuture<*>? = null,
        var deadline: ScheduledFuture<*>? = null, var fallbackStarted: Boolean = false,
        var lastError: DestinationSearchError? = null)
    private val lock = Any()
    private var active: Active? = null
    private var released = false

    fun request(request: DestinationSearchRequest) {
        cancelActive("replaced")
        val endpoint = transportProvider()?.takeIf { it.isConnected() }
        val item = Active(request)
        synchronized(lock) { if (released) return; active = item }
        if (endpoint == null) startFallback(item, DestinationSearchFallbackReason.NOT_CONNECTED)
        else if (!endpoint.sendDestinationSearchRequest(request)) startFallback(item, DestinationSearchFallbackReason.SEND_FAILURE)
        else item.hedge = scheduler.schedule({ startFallback(item, DestinationSearchFallbackReason.HEDGE) }, hedgeMs, TimeUnit.MILLISECONDS)
        item.deadline = scheduler.schedule({
            val error = synchronized(lock) {
                if (active !== item) return@schedule
                clearLocked(item); active = null; item.lastError
            }
            listener?.onDestinationSearchFailure(item.request, error)
        }, deadlineMs, TimeUnit.MILLISECONDS)
    }

    fun onResult(result: DestinationSearchResult) {
        val accepted = synchronized(lock) {
            val item = active ?: return
            if (item.request.requestId != result.requestId) return
            clearLocked(item); active = null; true
        }
        if (accepted) listener?.onDestinationSearchResult(result)
    }

    fun onError(error: DestinationSearchError) {
        val item = synchronized(lock) {
            val value = active ?: return
            if (value.request.requestId != error.requestId) return
            value.lastError = error; value
        }
        startFallback(item, DestinationSearchFallbackReason.PHONE_ERROR)
    }

    fun acceptFallback(requestId: String): Boolean {
        val request = synchronized(lock) {
            val item = active ?: return false
            if (item.request.requestId != requestId) return false
            clearLocked(item); active = null; item.request
        }
        transportProvider()?.takeIf { it.isConnected() }?.sendDestinationSearchCancellation(
            DestinationSearchCancellation(request.requestId, System.currentTimeMillis(), "fallback_won"))
        return true
    }

    fun onConnectionChanged(connected: Boolean) {
        if (connected) return
        synchronized(lock) { active }?.let { startFallback(it, DestinationSearchFallbackReason.NOT_CONNECTED) }
    }

    fun cancelActive(reason: String) {
        val request = synchronized(lock) {
            val item = active ?: return
            clearLocked(item); active = null; item.request
        }
        transportProvider()?.takeIf { it.isConnected() }?.sendDestinationSearchCancellation(
            DestinationSearchCancellation(request.requestId, System.currentTimeMillis(), reason))
    }

    fun release() { synchronized(lock) { released = true }; cancelActive("released"); scheduler.shutdownNow() }

    private fun startFallback(item: Active, reason: DestinationSearchFallbackReason) {
        val should = synchronized(lock) {
            if (active !== item || item.fallbackStarted) false else { item.fallbackStarted = true; true }
        }
        if (should) listener?.onDestinationSearchFallback(item.request, reason)
    }
    private fun clearLocked(item: Active) { item.hedge?.cancel(false); item.deadline?.cancel(false) }
}
