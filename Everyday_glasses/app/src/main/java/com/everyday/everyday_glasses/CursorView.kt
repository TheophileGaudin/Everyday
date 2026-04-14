package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import com.everyday.everyday_glasses.binocular.DisplayConfig

/**
 * Simple cursor view that can be positioned anywhere on screen.
 * Optimized for AR glasses display with aggressive invalidation.
 * Starts hidden and appears on first activity, then auto-hides after 5 seconds of inactivity.
 */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    companion object {
        private const val CURSOR_HIDE_DELAY_MS = 5000L  // 5 seconds
    }
    
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    
    private var cursorX: Float = 0f
    private var cursorY: Float = 0f
    
    private var cursorSize: Float = 12f
    
    // Screen bounds for clamping
    private var maxX: Float = 0f
    private var maxY: Float = 0f
    
    // Auto-hide cursor after inactivity
    private var cursorVisible = false  // Start hidden, appears on first activity
    private var forceHidden = false    // Force hidden by wake/sleep manager
    private var gentleRedrawEnabled = false
    private val handler = Handler(Looper.getMainLooper())
    private var hideRunnable: Runnable? = null
    
    // Callback for visibility changes
    var onVisibilityChanged: ((Boolean) -> Unit)? = null
    
    fun setCursorPosition(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, maxX)
        cursorY = y.coerceIn(0f, maxY)
        onActivity()
        triggerRedraw()
    }
    
    fun moveCursor(dx: Float, dy: Float) {
        cursorX = (cursorX + dx).coerceIn(0f, maxX)
        cursorY = (cursorY + dy).coerceIn(0f, maxY)
        onActivity()
        triggerRedraw()
    }
    
    /**
     * Call this whenever there's user activity (cursor movement, taps, etc.).
     * Resets the auto-hide timer.
     */
    fun onActivity() {
        // Show cursor if hidden
        if (!cursorVisible) {
            cursorVisible = true
            onVisibilityChanged?.invoke(true)
            triggerRedraw()
        }
        
        // Cancel existing hide timer
        hideRunnable?.let { handler.removeCallbacks(it) }
        
        // Schedule new hide timer
        hideRunnable = Runnable {
            cursorVisible = false
            onVisibilityChanged?.invoke(false)
            triggerRedraw()
        }
        handler.postDelayed(hideRunnable!!, CURSOR_HIDE_DELAY_MS)
    }
    
    fun getCursorX(): Float = cursorX
    fun getCursorY(): Float = cursorY
    fun isCursorVisible(): Boolean = cursorVisible && !forceHidden

    fun setGentleRedrawEnabled(enabled: Boolean) {
        if (gentleRedrawEnabled == enabled) return
        gentleRedrawEnabled = enabled
        invalidate()
    }
    
    /**
     * Force hide or show the cursor (used by wake/sleep manager).
     * When force hidden, the cursor won't be drawn even if cursorVisible is true.
     *
     * @param hidden Whether to force hide the cursor
     * @param useGentleRedraw If true, uses single invalidate() to avoid power spikes
     *                        during display state transitions. Default is false for
     *                        backwards compatibility.
     */
    fun setForceHidden(hidden: Boolean, useGentleRedraw: Boolean = false) {
        if (forceHidden != hidden) {
            forceHidden = hidden
            onVisibilityChanged?.invoke(isCursorVisible())
            if (useGentleRedraw) {
                // Single invalidation to avoid power spikes during state transitions
                invalidate()
            } else {
                triggerRedraw()
            }
        }
    }
    
    fun setCursorColor(color: Int) {
        paint.color = color
        triggerRedraw()
    }
    
    /**
     * Aggressively trigger redraw for AR glasses display.
     */
    private fun triggerRedraw() {
        if (gentleRedrawEnabled) {
            invalidate()
            (parent as? View)?.invalidate()
            return
        }

        // Multiple invalidation methods to ensure redraw on AR glasses
        invalidate()
        postInvalidate()
        postInvalidateOnAnimation()
        
        // Also invalidate parent
        (parent as? View)?.invalidate()
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val logicalWidth = if (w > DisplayConfig.EYE_WIDTH) DisplayConfig.EYE_WIDTH else w
        val logicalHeight = minOf(h, DisplayConfig.EYE_HEIGHT)
        maxX = logicalWidth.toFloat()
        maxY = logicalHeight.toFloat()
        
        // Center cursor initially
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = maxX / 2f
            cursorY = maxY / 2f
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Only draw cursor if visible and not force-hidden
        if (!cursorVisible || forceHidden) {
            return
        }

        drawCursorAt(canvas, cursorX, cursorY)

        if (width >= DisplayConfig.SCREEN_WIDTH) {
            drawCursorAt(canvas, cursorX + DisplayConfig.EYE_WIDTH, cursorY)
        }
    }

    private fun drawCursorAt(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, cursorSize, paint)
        canvas.drawCircle(x, y, cursorSize, outlinePaint)

        val lineLength = cursorSize * 1.5f
        canvas.drawLine(x - lineLength, y, x - cursorSize - 2, y, paint)
        canvas.drawLine(x + cursorSize + 2, y, x + lineLength, y, paint)
        canvas.drawLine(x, y - lineLength, x, y - cursorSize - 2, paint)
        canvas.drawLine(x, y + cursorSize + 2, x, y + lineLength, paint)
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Clean up handler callbacks
        hideRunnable?.let { handler.removeCallbacks(it) }
    }
}
