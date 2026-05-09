package com.everyday.everyday_glasses

/**
 * Shared loading/error display state for widgets backed by async snapshots.
 *
 * Widgets still own their domain data and caching rules; this only centralizes
 * the repeated "loading vs error vs empty" state and display text.
 */
class WidgetDataState(
    private val loadingText: String,
    private val emptyText: String
) {
    var isLoading: Boolean = false
        private set

    var error: String? = null
        private set

    fun startLoading(clearError: Boolean) {
        isLoading = true
        if (clearError) {
            error = null
        }
    }

    fun setLoadingIfEmpty(hasRenderableContent: Boolean) {
        isLoading = !hasRenderableContent
        if (!hasRenderableContent) {
            error = null
        }
    }

    fun markLoaded() {
        isLoading = false
        error = null
    }

    fun markError(message: String, showError: Boolean = true) {
        isLoading = false
        if (showError) {
            error = message
        }
    }

    fun setIdleError(message: String?) {
        isLoading = false
        error = message
    }

    fun clearError() {
        error = null
    }

    fun displayText(): String {
        return when {
            isLoading -> loadingText
            !error.isNullOrEmpty() -> error ?: emptyText
            else -> emptyText
        }
    }
}
