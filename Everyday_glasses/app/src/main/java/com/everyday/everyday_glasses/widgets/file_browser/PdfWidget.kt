package com.everyday.everyday_glasses.widgets.file_browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import androidx.core.graphics.createBitmap
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.WidgetCaptureRestore
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.widgets._common.BaseWidget

/** Canvas-backed PDF reader. Only the visible page bitmap is retained. */
class PdfWidget(
    private val context: Context,
    val contentUri: Uri,
    val displayName: String,
    x: Float,
    y: Float,
    width: Float = 360f,
    height: Float = 260f,
    initialPage: Int = 0,
    initialZoom: Float = 1f,
) : BaseWidget(WidgetTypeId.PDF, x, y, width, height) {

    companion object {
        private const val CONTROL_WIDTH = 82f
        private const val CONTROL_BUTTON_HEIGHT = 42f
        private const val CONTROL_BUTTON_GAP = 8f
        private const val RENDER_VIEWPORT_WIDTH = 608
        private const val MAX_BITMAP_PIXELS = 4_000_000
        private val ZOOM_LEVELS = floatArrayOf(1f, 1.4f, 1.8f, 2.4f)
    }

    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderGeneration = AtomicInteger(0)
    private var descriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private var bitmap: Bitmap? = null
    private val bitmapSource = Rect()
    private val bitmapDestination = RectF()
    private var panY = 0f
    private var errorMessage: String? = null
    private var isLoading = true

    var pageCount: Int = 0
        private set
    var currentPage: Int = initialPage.coerceAtLeast(0)
        private set
    var zoom: Float = nearestZoom(initialZoom)
        private set

    override val minimizeLabel: String = "P"

    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // Draw-time night mode avoids retaining a second transformed page bitmap.
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -0.86f, 0f, 0f, 0f, 232f,
            0f, -0.86f, 0f, 0f, 232f,
            0f, 0f, -0.86f, 0f, 232f,
            0f, 0f, 0f, 1f, 0f,
        )))
    }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 12, 18, 30) }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(35, 64, 96) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 17f }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(190, 200, 215); textSize = 14f }
    private val centeredSecondaryPaint = Paint(secondaryPaint).apply { textAlign = Paint.Align.CENTER }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f
        textAlign = Paint.Align.CENTER
    }
    private val previousBounds = RectF()
    private val nextBounds = RectF()
    private val zoomOutBounds = RectF()
    private val zoomInBounds = RectF()

    init {
        backgroundPaint.color = Color.rgb(10, 14, 22)
        openDocument()
    }

    private fun openDocument() {
        worker.execute {
            runCatching {
                val openedDescriptor = context.contentResolver.openFileDescriptor(contentUri, "r")
                    ?: error("Unable to open PDF")
                descriptor = openedDescriptor
                val openedRenderer = PdfRenderer(openedDescriptor)
                renderer = openedRenderer
                pageCount = openedRenderer.pageCount
                currentPage = currentPage.coerceIn(0, (pageCount - 1).coerceAtLeast(0))
                if (pageCount == 0) error("PDF has no pages")
                renderCurrentPageOnWorker(renderGeneration.incrementAndGet())
            }.onFailure { error ->
                isLoading = false
                errorMessage = error.message ?: "Unable to open PDF"
                mainHandler.post { notifyTransientChange() }
            }
        }
    }

    private fun requestRender(resetPan: Boolean = true) {
        if (resetPan) panY = 0f
        isLoading = true
        errorMessage = null
        val generation = renderGeneration.incrementAndGet()
        worker.execute { renderCurrentPageOnWorker(generation) }
        notifyTransientChange()
    }

    private fun renderCurrentPageOnWorker(generation: Int) {
        val activeRenderer = renderer ?: return
        runCatching {
            activeRenderer.openPage(currentPage).use { page ->
                val scale = (RENDER_VIEWPORT_WIDTH.toFloat() / page.width.toFloat()) * zoom
                var targetWidth = (page.width * scale).roundToInt().coerceAtLeast(1)
                var targetHeight = (page.height * scale).roundToInt().coerceAtLeast(1)
                val pixelCount = targetWidth.toLong() * targetHeight.toLong()
                if (pixelCount > MAX_BITMAP_PIXELS) {
                    val reduction = sqrt(MAX_BITMAP_PIXELS.toDouble() / pixelCount.toDouble()).toFloat()
                    targetWidth = (targetWidth * reduction).roundToInt().coerceAtLeast(1)
                    targetHeight = (targetHeight * reduction).roundToInt().coerceAtLeast(1)
                }
                val rendered = createBitmap(targetWidth, targetHeight)
                rendered.eraseColor(Color.WHITE)
                page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                mainHandler.post {
                    if (generation != renderGeneration.get()) {
                        rendered.recycle()
                        return@post
                    }
                    bitmap?.recycle()
                    bitmap = rendered
                    isLoading = false
                    errorMessage = null
                    clampPan()
                    notifyTransientChange()
                }
            }
        }.onFailure { error ->
            mainHandler.post {
                if (generation == renderGeneration.get()) {
                    isLoading = false
                    errorMessage = error.message ?: "Unable to render page"
                    notifyTransientChange()
                }
            }
        }
    }

    override fun contentSignature(): List<Any?> = listOf(currentPage, zoom)

    override fun onContentTap(px: Float, py: Float): Boolean {
        if (baseHitTest(px, py) != BaseHitArea.CONTENT) return false
        handleContentTap(px, py)
        return true
    }

    fun handleContentTap(px: Float, py: Float) {
        if (!isFullscreen) {
            toggleFullscreen()
            return
        }

        when {
            previousBounds.contains(px, py) -> changePage(-1)
            nextBounds.contains(px, py) -> changePage(1)
            zoomOutBounds.contains(px, py) -> changeZoom(-1)
            zoomInBounds.contains(px, py) -> changeZoom(1)
        }
    }

    private fun changePage(delta: Int) {
        if (pageCount <= 0) return
        val newPage = (currentPage + delta).coerceIn(0, pageCount - 1)
        if (newPage == currentPage) return
        currentPage = newPage
        requestRender()
    }

    private fun changeZoom(delta: Int) {
        val index = ZOOM_LEVELS.indexOfFirst { it == zoom }.coerceAtLeast(0)
        val newZoom = ZOOM_LEVELS[(index + delta).coerceIn(0, ZOOM_LEVELS.lastIndex)]
        if (newZoom == zoom) return
        zoom = newZoom
        requestRender()
    }

    override fun onScroll(dy: Float) {
        if (!isFullscreen || bitmap == null) return
        panY += dy
        clampPan()
        notifyTransientChange()
    }

    private fun clampPan() {
        val viewportHeight = widgetHeight.coerceAtLeast(1f)
        val renderedHeight = bitmap?.height?.toFloat() ?: 0f
        panY = panY.coerceIn(0f, (renderedHeight - viewportHeight).coerceAtLeast(0f))
    }

    override fun minimize() {
        if (isFullscreen) toggleFullscreen() else super.minimize()
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }
        updateControlBounds()
        canvas.drawRect(widgetBounds, backgroundPaint)
        val viewport = if (isFullscreen) {
            RectF(widgetBounds.left + CONTROL_WIDTH, widgetBounds.top, widgetBounds.right, widgetBounds.bottom)
        } else {
            RectF(contentBounds)
        }
        drawPage(canvas, viewport)
        if (isFullscreen) drawControls(canvas) else drawPlaceholderLabel(canvas)

        if (shouldShowBorder()) {
            canvas.drawRect(widgetBounds, hoverBorderPaint)
            if (!isFullscreen) drawResizeHandle(canvas)
            if (shouldShowBorderButtons()) drawBorderButtons(canvas)
        }
    }

    private fun drawPage(canvas: Canvas, viewport: RectF) {
        val pageBitmap = bitmap
        if (pageBitmap == null) {
            val message = errorMessage ?: if (isLoading) "Loading PDF…" else "No page"
            canvas.drawText(message, viewport.left + 16f, viewport.centerY(), titlePaint)
            return
        }
        if (!isFullscreen) {
            canvas.drawBitmap(pageBitmap, null, viewport, pagePaint)
            return
        }

        val sourceWidth = viewport.width().roundToInt().coerceAtMost(pageBitmap.width)
        val sourceHeight = viewport.height().roundToInt().coerceAtMost(pageBitmap.height)
        val sourceLeft = ((pageBitmap.width - sourceWidth) / 2).coerceAtLeast(0)
        val sourceTop = panY.roundToInt().coerceIn(0, (pageBitmap.height - sourceHeight).coerceAtLeast(0))
        bitmapSource.set(sourceLeft, sourceTop, sourceLeft + sourceWidth, sourceTop + sourceHeight)

        val scale = minOf(viewport.width() / sourceWidth.toFloat(), viewport.height() / sourceHeight.toFloat())
        val drawWidth = sourceWidth * scale
        val drawHeight = sourceHeight * scale
        bitmapDestination.set(
            viewport.centerX() - drawWidth / 2f,
            viewport.centerY() - drawHeight / 2f,
            viewport.centerX() + drawWidth / 2f,
            viewport.centerY() + drawHeight / 2f,
        )
        canvas.drawBitmap(pageBitmap, bitmapSource, bitmapDestination, pagePaint)
    }

    private fun drawPlaceholderLabel(canvas: Canvas) {
        val top = contentBounds.bottom - 46f
        canvas.drawRect(contentBounds.left, top, contentBounds.right, contentBounds.bottom, panelPaint)
        canvas.drawText(ellipsize(displayName, contentBounds.width() - 20f), contentBounds.left + 10f, top + 20f, titlePaint)
        canvas.drawText(pageLabel(), contentBounds.left + 10f, top + 39f, secondaryPaint)
    }

    private fun drawControls(canvas: Canvas) {
        canvas.drawRect(
            widgetBounds.left,
            widgetBounds.top,
            widgetBounds.left + CONTROL_WIDTH,
            widgetBounds.bottom,
            panelPaint,
        )
        drawButton(canvas, previousBounds, "Prev")
        drawButton(canvas, nextBounds, "Next")
        drawButton(canvas, zoomOutBounds, "−")
        drawButton(canvas, zoomInBounds, "+")
        val centerX = widgetBounds.left + CONTROL_WIDTH / 2f
        val compactPageLabel = if (pageCount > 0) "${currentPage + 1}/$pageCount" else "PDF"
        canvas.drawText(compactPageLabel, centerX, previousBounds.top - 18f, centeredSecondaryPaint)
        canvas.drawText("${(zoom * 100).roundToInt()}%", centerX, zoomInBounds.bottom + 25f, centeredSecondaryPaint)
    }

    private fun drawButton(canvas: Canvas, bounds: RectF, label: String) {
        canvas.drawRoundRect(bounds, 7f, 7f, buttonPaint)
        val baseline = bounds.centerY() - (buttonTextPaint.ascent() + buttonTextPaint.descent()) / 2f
        canvas.drawText(label, bounds.centerX(), baseline, buttonTextPaint)
    }

    private fun updateControlBounds() {
        val groupHeight = CONTROL_BUTTON_HEIGHT * 4f + CONTROL_BUTTON_GAP * 3f
        val groupTop = widgetBounds.centerY() - groupHeight / 2f
        val left = widgetBounds.left + 10f
        val right = widgetBounds.left + CONTROL_WIDTH - 10f
        previousBounds.set(left, groupTop, right, groupTop + CONTROL_BUTTON_HEIGHT)
        nextBounds.set(left, previousBounds.bottom + CONTROL_BUTTON_GAP, right, previousBounds.bottom + CONTROL_BUTTON_GAP + CONTROL_BUTTON_HEIGHT)
        zoomOutBounds.set(left, nextBounds.bottom + CONTROL_BUTTON_GAP, right, nextBounds.bottom + CONTROL_BUTTON_GAP + CONTROL_BUTTON_HEIGHT)
        zoomInBounds.set(left, zoomOutBounds.bottom + CONTROL_BUTTON_GAP, right, zoomOutBounds.bottom + CONTROL_BUTTON_GAP + CONTROL_BUTTON_HEIGHT)
    }

    private fun pageLabel(): String = if (pageCount > 0) "Page ${currentPage + 1} / $pageCount" else "PDF"

    private fun ellipsize(value: String, maxWidth: Float): String {
        if (titlePaint.measureText(value) <= maxWidth) return value
        var end = value.length
        while (end > 1 && titlePaint.measureText(value.substring(0, end) + "…") > maxWidth) end--
        return value.substring(0, end) + "…"
    }

    override fun onDestroy() {
        renderGeneration.incrementAndGet()
        worker.execute {
            renderer?.close()
            renderer = null
            descriptor?.close()
            descriptor = null
        }
        worker.shutdown()
        bitmap?.recycle()
        bitmap = null
    }

    private fun nearestZoom(value: Float): Float = ZOOM_LEVELS.minByOrNull { abs(it - value) } ?: 1f
}
/** Puts PDF documents on the dashboard; a new one opens fullscreen. */
class PdfModule(
    private val services: DashboardServices,
    private val context: Context,
) {
    val lifecycle = WidgetLifecycleRegistration.multiple(
        WidgetTypeId.PDF, PdfWidget::class, DashboardPersistence.PdfWidgetState::class,
        capture = { services.captureRestore.getPdfWidgetState(it) },
        restore = ::restore,
    )

    fun open(uri: Uri, displayName: String) {
        val x = ((services.viewportWidth - 360f) / 2f).coerceAtLeast(0f)
        val y = ((services.viewportHeight - 260f) / 2f).coerceAtLeast(0f)
        val widget = PdfWidget(context, uri, displayName, x, y)
        services.runPersistentMutation {
            services.open(WidgetTypeId.PDF) { widget }
            widget.toggleFullscreen()
        }
    }

    /** A document that can't be read anymore is dropped from the dashboard. */
    private fun restore(state: DashboardPersistence.PdfWidgetState): PdfWidget? {
        val uri = WidgetCaptureRestore.readableUri(context, state.uri) ?: return null
        val widget = PdfWidget(
            context = context,
            contentUri = uri,
            displayName = state.displayName,
            x = state.x,
            y = state.y,
            width = state.width,
            height = state.height,
            initialPage = state.page,
            initialZoom = state.zoom
        )
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }
}