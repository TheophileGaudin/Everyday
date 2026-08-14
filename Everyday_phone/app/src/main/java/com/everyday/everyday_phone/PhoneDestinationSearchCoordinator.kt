package com.everyday.everyday_phone

import android.util.Log
import com.everyday.shared.transport.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

interface PhoneDestinationSearchTransport {
    fun isConnected(): Boolean
    fun sendDestinationSearchResult(result: DestinationSearchResult): Boolean
    fun sendDestinationSearchError(error: DestinationSearchError): Boolean
}

class PhoneDestinationSearchCoordinator(
    private val transportProvider: () -> PhoneDestinationSearchTransport?,
    private val provider: DestinationSearchProvider = MapboxDestinationSearchProvider(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "PhoneDestinationSearch").apply { isDaemon = true }
    },
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class Active(val request: DestinationSearchRequest, @Volatile var future: Future<*>? = null)
    private val lock = Any()
    private var active: Active? = null
    private var connected = false
    private var released = false

    fun onConnectionChanged(value: Boolean) = synchronized(lock) {
        connected = value
        if (!value) cancelLocked()
    }

    fun onSearchRequest(request: DestinationSearchRequest) {
        val work = synchronized(lock) {
            if (released || transportProvider()?.isConnected() != true) return
            if (active?.request?.requestId == request.requestId) return
            cancelLocked(); connected = true
            Active(request).also { active = it }
        }
        work.future = executor.submit {
            val startedAt = nowMs()
            val outcome = provider.search(request)
            val transport = synchronized(lock) {
                if (released || !connected || active !== work) return@submit
                active = null; transportProvider()
            } ?: return@submit
            Log.i("DestinationSearch", "provider=mapbox class=${DestinationQueryClassifier.classify(request.query).kind.name.lowercase()} latencyMs=${nowMs() - startedAt} status=${if (outcome.isSuccess) "success" else "failure"} count=${outcome.getOrNull()?.candidates?.size ?: 0}")
            outcome.fold(onSuccess = { response ->
                if (response.candidates.isNotEmpty()) transport.sendDestinationSearchResult(
                    DestinationSearchResult(request.requestId, request.query, response.candidates, response.attribution, nowMs())
                ) else transport.sendDestinationSearchError(DestinationSearchError(
                    request.requestId,
                    if (response.outsideWalkingRange) DestinationSearchErrorCode.OUTSIDE_WALKING_RANGE else DestinationSearchErrorCode.NO_RESULTS,
                    if (response.outsideWalkingRange) "Matches were found outside the supported routing range" else "No destination was found",
                    false, nowMs()
                ))
            }, onFailure = { failure ->
                val typed = failure as? DestinationSearchException
                transport.sendDestinationSearchError(DestinationSearchError(
                    request.requestId, typed?.code ?: DestinationSearchErrorCode.INTERNAL,
                    (failure.message ?: "Destination search failed").take(512), typed?.retryable ?: true, nowMs()
                ))
            })
        }
    }

    fun onSearchCancellation(value: DestinationSearchCancellation) = synchronized(lock) {
        if (active?.request?.requestId == value.requestId) cancelLocked()
    }

    fun release() { synchronized(lock) { released = true; cancelLocked() }; executor.shutdownNow() }

    private fun cancelLocked() {
        active?.future?.cancel(true)
        (provider as? CancellableDestinationSearchProvider)?.cancelActive()
        active = null
    }
}
