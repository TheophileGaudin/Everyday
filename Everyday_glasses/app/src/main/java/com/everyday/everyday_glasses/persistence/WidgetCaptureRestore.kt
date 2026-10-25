package com.everyday.everyday_glasses.persistence

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.browser.BrowserWidget
import com.everyday.everyday_glasses.widgets.file_browser.ImageWidget
import com.everyday.everyday_glasses.widgets.file_browser.PdfWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import com.everyday.everyday_glasses.widgets.file_browser.VideoWidget
import java.io.File

/**
 * Captures saveable properties from live widgets and restores those properties from
 * [DashboardPersistence] descriptions.
 *
 *  The single-instance widget types capture and restore their own state, in their plugin; they
 *  use [extractCommonFields] and [restoreCommonFields] from here.
 *
 * This class does not read/write durable storage, encode/decode JSON, decide when saving occurs,
 * or own widget lifecycle registration and destruction.
 *
 * Some legacy bulk-restore methods still construct widgets. Move those construction paths into
 * focused factories as their widget types are refactored; do not expand that legacy role.
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

    // ==================== Common-field helpers ====================

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

    // ==================== Extract State ====================

    /**
     * Get a single text widget state for persistence.
     */
    fun getTextWidgetState(widget: TextBoxWidget): DashboardPersistence.TextWidgetState {
        val c = extractCommonFields(widget)
        return DashboardPersistence.TextWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            text = widget.text,
            html = widget.getHtmlContent(),
            fontSize = widget.textFontSize,
            isTextWrap = widget.isTextWrap,
            columnCount = widget.getColumnCount(),
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    /**
     * Get all image widget states for persistence.
     */
    fun getImageWidgetStates(widgets: List<ImageWidget>): List<DashboardPersistence.ImageWidgetState> {
        return widgets.mapNotNull { widget ->
            val imagePath = widget.getImagePath()
            if (imagePath.isBlank()) {
                null
            } else {
                val c = extractCommonFields(widget)
                DashboardPersistence.ImageWidgetState(
                    x = c.x, y = c.y, width = c.width, height = c.height,
                    imagePath = imagePath,
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }
        }
    }

    fun getImageWidgetState(widget: ImageWidget): DashboardPersistence.ImageWidgetState? =
        getImageWidgetStates(listOf(widget)).singleOrNull()

    fun getVideoWidgetStates(widgets: List<VideoWidget>): List<DashboardPersistence.VideoWidgetState> {
        return widgets.map { widget ->
            val c = extractCommonFields(widget)
            DashboardPersistence.VideoWidgetState(
                x = c.x,
                y = c.y,
                width = c.width,
                height = c.height,
                uri = widget.contentUri.toString(),
                displayName = widget.displayName,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX,
                savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth,
                savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned,
            )
        }
    }

    fun getVideoWidgetState(widget: VideoWidget): DashboardPersistence.VideoWidgetState =
        getVideoWidgetStates(listOf(widget)).single()

    fun getPdfWidgetStates(widgets: List<PdfWidget>): List<DashboardPersistence.PdfWidgetState> {
        return widgets.map { widget ->
            val c = extractCommonFields(widget)
            DashboardPersistence.PdfWidgetState(
                x = c.x,
                y = c.y,
                width = c.width,
                height = c.height,
                uri = widget.contentUri.toString(),
                displayName = widget.displayName,
                page = widget.currentPage,
                zoom = widget.zoom,
                isMinimized = c.isMinimized,
                isFullscreen = c.isFullscreen,
                savedMinX = c.savedMinX,
                savedMinY = c.savedMinY,
                savedMinWidth = c.savedMinWidth,
                savedMinHeight = c.savedMinHeight,
                isPinned = c.isPinned,
            )
        }
    }

    fun getPdfWidgetState(widget: PdfWidget): DashboardPersistence.PdfWidgetState =
        getPdfWidgetStates(listOf(widget)).single()


    /**
     * Get a single browser widget state for persistence.
     */
    fun getBrowserWidgetState(widget: BrowserWidget): DashboardPersistence.BrowserWidgetState? {
        if (!widget.shouldPersist) {
            return null
        }

        val c = extractCommonFields(widget)
        return DashboardPersistence.BrowserWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            url = widget.getUrl() ?: "https://www.google.com",
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    // ==================== Restore State ====================

    /**
     * Restore text widgets from saved states.
     * Uses HTML content if available for rich text, otherwise falls back to plain text.
     */
    fun restoreTextWidgets(
        states: List<DashboardPersistence.TextWidgetState>,
        screenWidth: Int,
        screenHeight: Int
    ): List<TextBoxWidget> {
        Log.d(TAG, "Restoring ${states.size} text widgets")

        return states.map { state ->
            TextBoxWidget(state.x, state.y, state.width, state.height).apply {
                restoreCommonFields(this, state, screenWidth, screenHeight) {
                    // Restore content: use HTML if available, otherwise plain text
                    if (!state.html.isNullOrEmpty()) {
                        setHtmlContent(state.html)
                    } else {
                        setTextContent(state.text)
                    }
                    setFontSize(state.fontSize)
                    setTextWrap(state.isTextWrap)
                    setColumns(state.columnCount)
                }
            }
        }
    }

    /**
     * Restore image widgets from saved states.
     *
     * Missing image files are skipped so one bad path does not block the rest.
     */
    fun restoreImageWidgets(
        states: List<DashboardPersistence.ImageWidgetState>,
        screenWidth: Int,
        screenHeight: Int
    ): List<ImageWidget> {
        Log.d(TAG, "Restoring ${states.size} image widgets")

        return states.mapNotNull { state ->
            val imageFile = File(state.imagePath)
            if (!imageFile.exists()) {
                Log.w(TAG, "Skipping missing image widget file: ${state.imagePath}")
                return@mapNotNull null
            }

            ImageWidget(state.x, state.y, state.imagePath).apply {
                restoreCommonFields(this, state, screenWidth, screenHeight)
            }
        }
    }

    /** Load the saved page of an already-created browser, then restore its common fields. */
    fun restoreBrowserWidgetState(
        widget: BrowserWidget,
        state: DashboardPersistence.BrowserWidgetState,
        screenWidth: Int,
        screenHeight: Int,
    ) {
        restoreCommonFields(widget, state, screenWidth, screenHeight) {
            widget.getWebView()?.loadUrl(state.url)
        }
    }

}