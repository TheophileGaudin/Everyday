package com.everyday.everyday_glasses.widgets.map.backend

import com.everyday.shared.transport.RouteTransportCancellation
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportErrorCode
import com.everyday.shared.transport.RouteTransportRequest
import com.everyday.shared.transport.RouteTransportResult
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

interface GlassesRouteTransport {
    fun isConnected(): Boolean
    fun sendRouteRequest(request: RouteTransportRequest): Boolean
    fun sendRouteCancellation(cancellation: RouteTransportCancellation): Boolean
}

enum class GlassesRouteFallbackReason {
    NOT_CONNECTED,
    SEND_FAILURE,
    TIMEOUT,
    DISCONNECTED,
    PHONE_ERROR
}

/** Receives the terminal outcome of a phone-first route request. */
interface RouteResolutionListener {
    fun onRouteResolved(result: RouteTransportResult) = Unit
    fun onRouteAmbiguous(request: RouteTransportRequest, error: RouteTransportError) = Unit
    fun onRouteFallback(request: RouteTransportRequest, reason: GlassesRouteFallbackReason) = Unit
}

/**
 * Bounded phone-first request state machine for the glasses.  It deliberately does not calculate
 * routes itself: callers provide the existing glasses-local fallback and receive one callback when
 * the phone path becomes unusable.
 */
class GlassesPhoneRoutingCoordinator(
    private val transportProvider: () -> GlassesRouteTransport?,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "GlassesRouteTimeout").apply { isDaemon = true }
    }
) {
    var listener: RouteResolutionListener? = null

    private val lock = Any()
    private var active: ActiveRequest? = null
    private var released = false

    private data class ActiveRequest(
        val request: RouteTransportRequest,
        var timeout: ScheduledFuture<*>? = null
    )

    init {
        require(timeoutMs > 0L) { "Route timeout must be positive" }
    }

    /** Returns true only when the phone request was accepted by the connected transport. */
    fun request(request: RouteTransportRequest): Boolean {
        var obsoleteRequest: RouteTransportRequest? = null
        val endpoint = synchronized(lock) {
            if (released) return false
            active?.let { previous ->
                obsoleteRequest = previous.request
                previous.timeout?.cancel(false)
                active = null
            }
            transportProvider()?.takeIf { it.isConnected() }
        }
        obsoleteRequest?.let { previous ->
            runCatching {
                endpoint?.sendRouteCancellation(
                    RouteTransportCancellation(previous.requestId, System.currentTimeMillis(), "replaced")
                )
            }
        }
        if (endpoint == null) {
            listener?.onRouteFallback(request, GlassesRouteFallbackReason.NOT_CONNECTED)
            return false
        }

        val current = ActiveRequest(request)
        synchronized(lock) {
            if (released) return false
            active = current
        }
        val sent = runCatching { endpoint.sendRouteRequest(request) }.getOrDefault(false)
        if (!sent) {
            clearIfCurrent(current)
            listener?.onRouteFallback(request, GlassesRouteFallbackReason.SEND_FAILURE)
            return false
        }
        try {
            current.timeout = scheduler.schedule(
                { timeout(current) },
                timeoutMs,
                TimeUnit.MILLISECONDS
            )
        } catch (_: RuntimeException) {
            clearIfCurrent(current)
            listener?.onRouteFallback(request, GlassesRouteFallbackReason.SEND_FAILURE)
            return false
        }
        return true
    }

    fun onRouteResult(result: RouteTransportResult) {
        val deliver = synchronized(lock) {
            val current = active ?: return
            if (current.request.requestId != result.requestId) return
            current.timeout?.cancel(false)
            active = null
            true
        }
        if (deliver) listener?.onRouteResolved(result)
    }

    fun onRouteError(error: RouteTransportError) {
        val request = synchronized(lock) {
            val current = active ?: return
            if (current.request.requestId != error.requestId) return
            current.timeout?.cancel(false)
            active = null
            current.request
        }
        if (error.code == RouteTransportErrorCode.GEOCODING_AMBIGUOUS && error.choices.isNotEmpty()) {
            listener?.onRouteAmbiguous(request, error)
        } else {
            listener?.onRouteFallback(request, GlassesRouteFallbackReason.PHONE_ERROR)
        }
    }

    /** A disconnect fails the current request exactly once; late results are ignored. */
    fun onConnectionChanged(connected: Boolean) {
        if (connected) return
        val request = synchronized(lock) {
            val current = active ?: return
            current.timeout?.cancel(false)
            active = null
            current.request
        }
        listener?.onRouteFallback(request, GlassesRouteFallbackReason.DISCONNECTED)
    }

    /** Stops a request without starting local fallback, used by stop/replacement. */
    fun cancelActive(reason: String = "obsolete") {
        val request = synchronized(lock) {
            val current = active ?: return
            current.timeout?.cancel(false)
            active = null
            current.request
        }
        runCatching {
            transportProvider()?.takeIf { it.isConnected() }?.sendRouteCancellation(
                RouteTransportCancellation(request.requestId, System.currentTimeMillis(), reason)
            )
        }
    }

    fun hasActiveRequest(): Boolean = synchronized(lock) { active != null }

    fun release() {
        synchronized(lock) {
            released = true
            active?.timeout?.cancel(false)
            active = null
        }
        scheduler.shutdownNow()
    }

    private fun timeout(current: ActiveRequest) {
        val request = synchronized(lock) {
            if (active !== current) return
            active = null
            current.request
        }
        runCatching {
            transportProvider()?.takeIf { it.isConnected() }?.sendRouteCancellation(
                RouteTransportCancellation(request.requestId, System.currentTimeMillis(), "timeout")
            )
        }
        listener?.onRouteFallback(request, GlassesRouteFallbackReason.TIMEOUT)
    }

    private fun clearIfCurrent(current: ActiveRequest) {
        synchronized(lock) {
            if (active === current) {
                current.timeout?.cancel(false)
                active = null
            }
        }
    }

    companion object {
        // Covers phone geocoding plus one normal Overpass attempt. Cancellation now disconnects
        // active HTTP calls, so replacement/stop does not have to wait for this deadline.
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
