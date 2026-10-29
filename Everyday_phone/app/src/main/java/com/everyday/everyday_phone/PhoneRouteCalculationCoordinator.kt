package com.everyday.everyday_phone

import com.everyday.shared.transport.RouteTransportCancellation
import com.everyday.shared.transport.RouteTransportError
import com.everyday.shared.transport.RouteTransportErrorCode
import com.everyday.shared.transport.RouteTransportRequest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Owns the phone endpoint lifecycle for route requests.  It has no Android/UI dependency: the
 * RFCOMM server supplies the transport and this coordinator only returns route data or errors.
 */
class PhoneRouteCalculationCoordinator(
    private val transportProvider: () -> PhoneRouteTransport?,
    private val calculator: PhoneRouteCalculator = PhoneRouteCalculationService(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PhoneRouteCalculation").apply { isDaemon = true }
    }
) {
    private val lock = Any()
    private var connected = false
    private var connectionEpoch = 0L
    private var active: ActiveRequest? = null
    private var released = false

    private data class ActiveRequest(
        val request: RouteTransportRequest,
        val epoch: Long,
        @Volatile var future: Future<*>? = null
    )

    fun onConnectionChanged(isConnected: Boolean) {
        synchronized(lock) {
            if (connected == isConnected) return
            connected = isConnected
            connectionEpoch++
            if (active != null) {
                active?.future?.cancel(true)
                cancelCalculation()
            }
            active = null
        }
    }

    fun onRouteRequest(request: RouteTransportRequest) {
        val work = synchronized(lock) {
            if (released) return
            val endpoint = transportProvider() ?: return
            if (!endpoint.isConnected()) return
            // The RFCOMM reader can receive the first route line before the main-thread
            // connection callback is delivered.  The endpoint's connected state is authoritative
            // for accepting that first request; the callback still advances the epoch on loss.
            if (!connected) {
                connected = true
                connectionEpoch++
            }
            if (active?.request?.requestId == request.requestId) return
            if (active != null) {
                active?.future?.cancel(true)
                cancelCalculation()
            }
            ActiveRequest(request, connectionEpoch).also { active = it }
        }

        try {
            work.future = executor.submit {
                val calculation = try {
                    calculator.calculate(work.request)
                } catch (failure: Throwable) {
                    PhoneRouteCalculation.Failure(
                        RouteTransportError(
                            requestId = work.request.requestId,
                            code = RouteTransportErrorCode.INTERNAL,
                            message = (failure.message ?: "Phone route calculation failed").take(512),
                            retryable = true,
                            timestampMs = System.currentTimeMillis()
                        )
                    )
                }
                sendIfCurrent(work, calculation)
            }
        } catch (_: RuntimeException) {
            synchronized(lock) {
                if (active === work) active = null
            }
        }
    }

    fun onRouteCancellation(cancellation: RouteTransportCancellation) {
        synchronized(lock) {
            if (active?.request?.requestId != cancellation.requestId) return
            active?.future?.cancel(true)
            cancelCalculation()
            active = null
        }
    }

    fun release() {
        synchronized(lock) {
            released = true
            connected = false
            connectionEpoch++
            if (active != null) {
                active?.future?.cancel(true)
                cancelCalculation()
            }
            active = null
        }
        executor.shutdownNow()
    }

    private fun cancelCalculation() {
        (calculator as? CancellablePhoneRouteCalculator)?.cancelActive()
    }

    private fun sendIfCurrent(work: ActiveRequest, calculation: PhoneRouteCalculation) {
        val endpoint = synchronized(lock) {
            if (released || !connected || connectionEpoch != work.epoch || active !== work) return
            active = null
            transportProvider()
        } ?: return
        if (!endpoint.isConnected()) return
        runCatching {
            when (calculation) {
                is PhoneRouteCalculation.Success -> endpoint.sendRouteResult(calculation.result)
                is PhoneRouteCalculation.Failure -> endpoint.sendRouteError(calculation.error)
            }
        }
    }
}
