package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * ThreeDofOverlay - Visual overlay for 3DOF mode.
 *
 * Renders:
 * 1. Dashed rectangle showing the "home" content area boundaries
 * 2. Visual indicator showing current 3DOF mode state
 * 3. Direction indicators showing which way content is offset
 *
 * The dashed rectangle represents where content normally appears in head-locked mode.
 * When in 3DOF mode, this boundary becomes visible to help users understand
 * that they're looking away from the default view area.
 */
class ThreeDofOverlay {

    companion object {
        private const val TAG = "ThreeDofOverlay"

        // Dashed line styling
        private const val DASH_LENGTH = 12f
        private const val GAP_LENGTH = 8f
        private const val STROKE_WIDTH = 2f

        // Boundary margin from screen edge
        private const val BOUNDARY_MARGIN = 8f

        // Corner radius for rounded boundary
        private const val CORNER_RADIUS = 12f

        // Mode indicator dimensions
        private const val INDICATOR_SIZE = 24f
        private const val INDICATOR_MARGIN = 12f
    }

    // ==================== State ====================

    private var is3DofEnabled = false
    private var screenWidth = 640f
    private var screenHeight = 480f

    // Current content offset (set by ThreeDofManager)
    private var contentOffsetX = 0f
    private var contentOffsetY = 0f

    // Animation state for mode transition
    private var transitionProgress = 1f  // 0 = transitioning, 1 = complete

    // Pre-computed bounds
    private val boundaryRect = RectF()
    private val indicatorRect = RectF()

    // ==================== Paints ====================

    private val dashedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH
        color = Color.parseColor("#66AAAAFF")  // Light blue, semi-transparent
        pathEffect = DashPathEffect(floatArrayOf(DASH_LENGTH, GAP_LENGTH), 0f)
    }

    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = STROKE_WIDTH + 1f
        color = Color.parseColor("#AAAAFF")  // Brighter blue when active
        pathEffect = DashPathEffect(floatArrayOf(DASH_LENGTH, GAP_LENGTH), 0f)
    }

    private val indicatorIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#88AAAAFF")
    }

    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#AAAAFF")
        strokeCap = Paint.Cap.ROUND
    }

    // ==================== Public API ====================

    /**
     * Set screen dimensions for layout calculations.
     */
    fun setScreenDimensions(width: Float, height: Float) {
        screenWidth = width
        screenHeight = height
        updateBounds()
    }

    /**
     * Set 3DOF mode enabled state.
     */
    fun set3DofEnabled(enabled: Boolean) {
        is3DofEnabled = enabled
        transitionProgress = 0f
    }

    /**
     * Update the current content offset from ThreeDofManager.
     */
    fun setContentOffset(offsetX: Float, offsetY: Float) {
        contentOffsetX = offsetX
        contentOffsetY = offsetY
    }

    /**
     * Set transition animation progress (0.0 to 1.0).
     */
    fun setTransitionProgress(progress: Float) {
        transitionProgress = progress.coerceIn(0f, 1f)
    }

    /**
     * Check if the overlay should be drawn.
     */
    fun isVisible(): Boolean = is3DofEnabled || transitionProgress < 1f

    /**
     * Draw the 3DOF overlay.
     * @param canvas The canvas to draw on
     */
    fun draw(canvas: Canvas) {
        if (!isVisible()) return

        // Calculate alpha based on transition
        val alpha = if (is3DofEnabled) {
            (transitionProgress * 255).toInt().coerceIn(0, 255)
        } else {
            ((1f - transitionProgress) * 255).toInt().coerceIn(0, 255)
        }

        if (alpha == 0) return

        // Draw the dashed boundary rectangle (shows where head-locked area is)
        drawBoundaryRectangle(canvas, alpha)
    }

    // ==================== Drawing Methods ====================

    /**
     * Draw the dashed boundary rectangle showing the "home" content area.
     * Note: Canvas is already translated by the 3DOF offset in WidgetContainer,
     * so we draw at the normal boundary position (it will move with content).
     */
    private fun drawBoundaryRectangle(canvas: Canvas, alpha: Int) {
        val paint = if (is3DofEnabled) activeBorderPaint else dashedBorderPaint
        paint.alpha = alpha

        // Draw at normal position - canvas translation handles the movement
        canvas.drawRoundRect(boundaryRect, CORNER_RADIUS, CORNER_RADIUS, paint)

        // Draw subtle corner markers for better visibility
        drawCornerMarkers(canvas, boundaryRect, alpha)
    }

    /**
     * Draw corner markers on the boundary rectangle.
     */
    private fun drawCornerMarkers(canvas: Canvas, rect: RectF, alpha: Int) {
        val markerLength = 20f
        val markerPaint = Paint(indicatorIconPaint).apply {
            this.alpha = alpha
            strokeWidth = 3f
        }

        // Top-left corner
        canvas.drawLine(rect.left, rect.top + markerLength, rect.left, rect.top, markerPaint)
        canvas.drawLine(rect.left, rect.top, rect.left + markerLength, rect.top, markerPaint)

        // Top-right corner
        canvas.drawLine(rect.right - markerLength, rect.top, rect.right, rect.top, markerPaint)
        canvas.drawLine(rect.right, rect.top, rect.right, rect.top + markerLength, markerPaint)

        // Bottom-left corner
        canvas.drawLine(rect.left, rect.bottom - markerLength, rect.left, rect.bottom, markerPaint)
        canvas.drawLine(rect.left, rect.bottom, rect.left + markerLength, rect.bottom, markerPaint)

        // Bottom-right corner
        canvas.drawLine(rect.right - markerLength, rect.bottom, rect.right, rect.bottom, markerPaint)
        canvas.drawLine(rect.right, rect.bottom - markerLength, rect.right, rect.bottom, markerPaint)
    }

    // ==================== Layout ====================

    private fun updateBounds() {
        // Boundary rectangle with margin from screen edges
        boundaryRect.set(
            BOUNDARY_MARGIN,
            BOUNDARY_MARGIN,
            screenWidth - BOUNDARY_MARGIN,
            screenHeight - BOUNDARY_MARGIN
        )

        // Mode indicator in top-right corner
        indicatorRect.set(
            screenWidth - INDICATOR_SIZE - INDICATOR_MARGIN,
            INDICATOR_MARGIN,
            screenWidth - INDICATOR_MARGIN,
            INDICATOR_SIZE + INDICATOR_MARGIN
        )
    }
}
