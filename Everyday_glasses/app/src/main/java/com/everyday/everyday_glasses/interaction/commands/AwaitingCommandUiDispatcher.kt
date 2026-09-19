package com.everyday.everyday_glasses.interaction.commands

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Runs command work on the UI thread and returns only a completed result.
 *
 * Work which is still queued when the wait expires is cancelled so it cannot execute late.
 * Work which has already started is not interrupted because UI mutations cannot be rolled back safely.
 */
internal class AwaitingCommandUiDispatcher(
    private val isUiThread: () -> Boolean,
    private val postToUiThread: (Runnable) -> Boolean,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : CommandUiDispatcher {
    init {
        require(timeoutMs > 0L) { "Command dispatch timeout must be positive" }
    }

    override fun dispatch(action: () -> Boolean): Boolean? {
        if (isUiThread()) return action()

        val task = FutureTask<Boolean> { action() }
        if (!postToUiThread(task)) return null

        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            task.cancel(false)
            null
        } catch (_: InterruptedException) {
            task.cancel(false)
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
    }
}