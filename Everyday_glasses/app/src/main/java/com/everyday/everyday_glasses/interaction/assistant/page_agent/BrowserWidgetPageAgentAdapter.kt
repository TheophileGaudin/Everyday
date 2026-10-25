package com.everyday.everyday_glasses.interaction.assistant.page_agent

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Small UI-thread bridge so the Page Agent worker never directly touches WebView state. */
interface PageAgentUiDispatcher {
    fun <T> call(action: () -> T): T
}

class AndroidPageAgentUiDispatcher(
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val timeoutMs: Long = 5_000L
) : PageAgentUiDispatcher {
    override fun <T> call(action: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return action()
        val future = CompletableFuture<T>()
        handler.post {
            try {
                future.complete(action())
            } catch (failure: Throwable) {
                future.completeExceptionally(failure)
            }
        }
        return future.get(timeoutMs, TimeUnit.MILLISECONDS)
    }
}

