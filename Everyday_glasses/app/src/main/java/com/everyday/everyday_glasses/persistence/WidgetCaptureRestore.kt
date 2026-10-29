package com.everyday.everyday_glasses.persistence

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.everyday.everyday_glasses.widgets._common.BaseWidget

/**
 * The widget side of the fields every widget saves (position, size, minimized, fullscreen,
 * pinned): [extractCommonFields] reads them from a live widget, [restoreCommonFields] puts them
 * back. Each widget type captures and restores its own fields in its plugin, with these. Also
 * [readableUri], for the types that save a content URI.
 *
 * It does not read or write storage or JSON ([DashboardPersistence] does), decide when saving
 * occurs, or own widget lifecycle registration and destruction.
 */
class WidgetCaptureRestore {

    companion object {
        private const val TAG = "WidgetCaptureRestore"

        /** The saved content URI, if it can still be opened for reading; otherwise null. */
        fun readableUri(context: Context, uriString: String): Uri? {
            val uri = runCatching { uriString.toUri() }.getOrNull() ?: return null
            val readable = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
            }.getOrDefault(false)
            return uri.takeIf { readable }
        }
    }

    /**
     * Container for the common persistence fields extracted from a [BaseWidget].
     * Used as an intermediate representation when building widget state DTOs.
     */
    data class ExtractedCommonFields(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float,
        val isMinimized: Boolean,
        val isFullscreen: Boolean,
        val savedMinX: Float,
        val savedMinY: Float,
        val savedMinWidth: Float,
        val savedMinHeight: Float,
        val isPinned: Boolean
    )

    /** Extract the common persistence fields from any [BaseWidget]. */
    fun extractCommonFields(widget: BaseWidget): ExtractedCommonFields {
        val persistenceBounds = widget.getPersistenceBounds()
        return ExtractedCommonFields(
            x = persistenceBounds.left,
            y = persistenceBounds.top,
            width = persistenceBounds.width(),
            height = persistenceBounds.height(),
            isMinimized = widget.isMinimized,
            isFullscreen = widget.isFullscreen,
            savedMinX = widget.savedMinX,
            savedMinY = widget.savedMinY,
            savedMinWidth = widget.savedMinWidth,
            savedMinHeight = widget.savedMinHeight,
            isPinned = widget.isPinned
        )
    }

    /**
     * Restore the fields every widget saves, in this order:
     * 1. its size (a status bar keeps its own size, since its state has none) and its position,
     *    both clamped to the screen;
     * 2. [restoreOwnFields]: the fields only this widget type saves;
     * 3. pinned, then either minimized or fullscreen (a minimized widget is never fullscreen).
     *
     * Fullscreen is applied here, before the widget is registered; the lifecycle controller then
     * fits it to the viewport.
     */
    fun restoreCommonFields(
        widget: BaseWidget,
        state: DashboardPersistence.CommonWidgetFields,
        screenWidth: Int,
        screenHeight: Int,
        restoreOwnFields: () -> Unit = {}
    ) {
        if (state is DashboardPersistence.SizedWidgetFields) {
            widget.widgetWidth = state.width.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenWidth.toFloat())
            widget.widgetHeight = state.height.coerceIn(BaseWidget.MIN_WIDGET_SIZE, screenHeight.toFloat())
        }
        val adjustedX = state.x.coerceIn(0f, (screenWidth - widget.widgetWidth).coerceAtLeast(0f))
        val adjustedY = state.y.coerceIn(0f, (screenHeight - widget.widgetHeight).coerceAtLeast(0f))
        widget.setPosition(adjustedX, adjustedY)
        Log.d(
            TAG,
            "Restored ${widget.type.persistedId}: ($adjustedX, $adjustedY) ${widget.widgetWidth}x${widget.widgetHeight} " +
                    "pinned=${state.isPinned} minimized=${state.isMinimized} fullscreen=${state.isFullscreen}"
        )

        restoreOwnFields()

        widget.isPinned = state.isPinned
        if (state.isMinimized) {
            // Set the saved restore bounds first
            widget.setPosition(state.savedMinX, state.savedMinY)
            widget.widgetWidth = state.savedMinWidth
            widget.widgetHeight = state.savedMinHeight

            // Trigger minimize
            widget.toggleMinimize()

            // Position to the minimized location
            widget.setPosition(adjustedX, adjustedY)
        } else if (state.isFullscreen && !widget.isFullscreen) {
            widget.toggleFullscreen()
        }
    }
}