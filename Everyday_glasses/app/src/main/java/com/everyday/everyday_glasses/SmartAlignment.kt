package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlin.math.abs

/**
 * Hook injected into BaseWidget so the container can adjust drag/resize positions
 * for smart alignment. The widget passes the natural target position (already
 * clamped to the screen) and receives back the snapped position to apply.
 */
interface SnapProvider {
    fun snapMove(widget: BaseWidget, targetX: Float, targetY: Float): Pair<Float, Float>
    fun snapResize(widget: BaseWidget, targetWidth: Float, targetHeight: Float): Pair<Float, Float>
    fun onWidgetDragEnd(widget: BaseWidget)
}

/**
 * PowerPoint-style alignment helper. While a widget is being dragged or resized,
 * computes candidate alignment lines from screen edges/center and from every other
 * widget's edges/centers, snaps the active widget to the nearest candidate within
 * [snapTolerance], and exposes the active guide lines for the container to render.
 *
 * The whole feature is gated by [enabled]. When false, snapMove/snapResize are
 * pass-through and no guides are produced — drag behavior is byte-identical to
 * pre-feature behavior.
 */
class SmartAlignmentHelper(private val registry: WidgetRegistry) : SnapProvider {

    enum class GuideKind { EDGE, CENTER, SCREEN }
    data class Guide(val isVertical: Boolean, val position: Float, val kind: GuideKind)

    var enabled: Boolean = true
    var snapTolerance: Float = 6f
    var screenWidth: Float = 0f
    var screenHeight: Float = 0f

    private val activeGuides = mutableListOf<Guide>()

    fun getActiveGuides(): List<Guide> = activeGuides

    override fun snapMove(widget: BaseWidget, targetX: Float, targetY: Float): Pair<Float, Float> {
        if (!enabled || screenWidth <= 0f || screenHeight <= 0f) {
            activeGuides.clear()
            return targetX to targetY
        }
        activeGuides.clear()

        val (dx, vGuide) = computeSnap(
            isVertical = true,
            edges = listOf(
                targetX,
                targetX + widget.widgetWidth,
                targetX + widget.widgetWidth / 2f
            ),
            screenSize = screenWidth,
            excluded = widget
        )
        val (dy, hGuide) = computeSnap(
            isVertical = false,
            edges = listOf(
                targetY,
                targetY + widget.widgetHeight,
                targetY + widget.widgetHeight / 2f
            ),
            screenSize = screenHeight,
            excluded = widget
        )
        vGuide?.let { activeGuides.add(it) }
        hGuide?.let { activeGuides.add(it) }
        return (targetX + dx) to (targetY + dy)
    }

    override fun snapResize(widget: BaseWidget, targetWidth: Float, targetHeight: Float): Pair<Float, Float> {
        if (!enabled || screenWidth <= 0f || screenHeight <= 0f) {
            activeGuides.clear()
            return targetWidth to targetHeight
        }
        activeGuides.clear()

        val (dw, vGuide) = computeSnap(
            isVertical = true,
            edges = listOf(widget.x + targetWidth),
            screenSize = screenWidth,
            excluded = widget
        )
        val (dh, hGuide) = computeSnap(
            isVertical = false,
            edges = listOf(widget.y + targetHeight),
            screenSize = screenHeight,
            excluded = widget
        )
        vGuide?.let { activeGuides.add(it) }
        hGuide?.let { activeGuides.add(it) }
        return (targetWidth + dw) to (targetHeight + dh)
    }

    override fun onWidgetDragEnd(widget: BaseWidget) {
        activeGuides.clear()
    }

    private fun computeSnap(
        isVertical: Boolean,
        edges: List<Float>,
        screenSize: Float,
        excluded: BaseWidget
    ): Pair<Float, Guide?> {
        val candidates = mutableListOf<Pair<Float, GuideKind>>()
        candidates.add(0f to GuideKind.SCREEN)
        candidates.add(screenSize to GuideKind.SCREEN)
        candidates.add(screenSize / 2f to GuideKind.SCREEN)

        registry.forEachWidget { other ->
            if (other === excluded) return@forEachWidget
            if (other.isMinimized || other.isFullscreen) return@forEachWidget
            if (isVertical) {
                candidates.add(other.x to GuideKind.EDGE)
                candidates.add((other.x + other.widgetWidth) to GuideKind.EDGE)
                candidates.add((other.x + other.widgetWidth / 2f) to GuideKind.CENTER)
            } else {
                candidates.add(other.y to GuideKind.EDGE)
                candidates.add((other.y + other.widgetHeight) to GuideKind.EDGE)
                candidates.add((other.y + other.widgetHeight / 2f) to GuideKind.CENTER)
            }
        }

        var bestDelta = 0f
        var bestAbs = snapTolerance + 1f
        var bestPosition = 0f
        var bestKind: GuideKind? = null

        for (edge in edges) {
            for ((cand, kind) in candidates) {
                val delta = cand - edge
                val a = abs(delta)
                if (a <= snapTolerance && a < bestAbs) {
                    bestAbs = a
                    bestDelta = delta
                    bestPosition = cand
                    bestKind = kind
                }
            }
        }

        return if (bestKind != null) bestDelta to Guide(isVertical, bestPosition, bestKind!!)
        else 0f to null
    }
}

/**
 * Renders the active alignment guide lines as thin coloured strokes spanning the
 * screen. Drawn on top of widgets but below menus.
 */
class SmartAlignmentRenderer {

    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF66CC")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD24A")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#66CCFF")
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    fun draw(canvas: Canvas, guides: List<SmartAlignmentHelper.Guide>, screenWidth: Float, screenHeight: Float) {
        if (guides.isEmpty()) return
        for (g in guides) {
            val paint = when (g.kind) {
                SmartAlignmentHelper.GuideKind.EDGE -> edgePaint
                SmartAlignmentHelper.GuideKind.CENTER -> centerPaint
                SmartAlignmentHelper.GuideKind.SCREEN -> screenPaint
            }
            if (g.isVertical) {
                canvas.drawLine(g.position, 0f, g.position, screenHeight, paint)
            } else {
                canvas.drawLine(0f, g.position, screenWidth, g.position, paint)
            }
        }
    }
}
