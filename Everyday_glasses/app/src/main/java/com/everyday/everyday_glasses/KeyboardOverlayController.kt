package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Delegate that owns the GlassesKeyboardView instance, its visibility state,
 * the toggle-button bounds/paints, and all drawing and hit-testing helpers.
 *
 * Both [TextBoxWidget] and [BrowserWidget] compose this instead of duplicating
 * the same keyboard-overlay plumbing.
 */
class KeyboardOverlayController {

    companion object {
        const val BUTTON_WIDTH = 56f
        const val BUTTON_HEIGHT = 32f
    }

    // ---- state ----

    private var keyboard: GlassesKeyboardView? = null

    var isVisible = false
        private set

    /** Callback invoked when any key on the soft keyboard is pressed. */
    var onKeyPressed: ((String) -> Unit)? = null

    // ---- button bounds & paints ----

    val buttonBounds = RectF()

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4488AA")
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // ---- layout ----

    /** Position the toggle-button at the bottom-center edge of the widget. */
    fun updateButtonPosition(widgetCenterX: Float, widgetBottom: Float) {
        buttonBounds.set(
            widgetCenterX - BUTTON_WIDTH / 2,
            widgetBottom - BUTTON_HEIGHT / 2,
            widgetCenterX + BUTTON_WIDTH / 2,
            widgetBottom + BUTTON_HEIGHT / 2
        )
    }

    /**
     * Create (if needed) and position the keyboard inside the given content area.
     * Call this from the host widget's `updateBaseBounds()` and after showing.
     */
    fun updateLayout(areaWidth: Float, areaHeight: Float, areaLeft: Float, areaTop: Float) {
        if (!isVisible) return
        val kbHeight = (areaHeight * GlassesKeyboardView.HEIGHT_RATIO)
            .coerceAtLeast(GlassesKeyboardView.MIN_HEIGHT)
        if (keyboard == null) {
            keyboard = GlassesKeyboardView(areaWidth, kbHeight).apply {
                onKeyPressed = { key -> this@KeyboardOverlayController.onKeyPressed?.invoke(key) }
            }
        }
        keyboard?.updateDimensions(areaWidth, kbHeight)
        keyboard?.setOffset(areaLeft, areaTop + areaHeight - kbHeight)
    }

    // ---- visibility ----

    /** Toggle visibility. Returns the new visibility state. */
    fun toggle(): Boolean {
        isVisible = !isVisible
        if (!isVisible) keyboard?.clearHover()
        return isVisible
    }

    /** Show the keyboard. Returns `true` if the state actually changed. */
    fun show(): Boolean {
        if (isVisible) return false
        isVisible = true
        return true
    }

    /** Hide the keyboard. Returns `true` if the state actually changed. */
    fun hide(): Boolean {
        if (!isVisible) return false
        isVisible = false
        keyboard?.clearHover()
        return true
    }

    // ---- accessors ----

    /** Get the keyboard view for external use (only when visible). */
    fun getKeyboard(): GlassesKeyboardView? = if (isVisible) keyboard else null

    /** Keyboard height, or 0 when hidden. */
    fun getHeight(): Float = if (isVisible && keyboard != null) keyboard!!.getHeight() else 0f

    /** Get the keyboard bounds for hit-testing (only meaningful when visible). */
    fun getBounds(): RectF? = if (isVisible) keyboard?.getBounds() else null

    // ---- hit-testing helpers ----

    /** Does the point land on a keyboard key? */
    fun containsPoint(px: Float, py: Float): Boolean =
        isVisible && keyboard?.containsPoint(px, py) == true

    /** Forward hover coordinates to the keyboard. */
    fun updateHover(px: Float, py: Float) {
        if (isVisible) keyboard?.updateHover(px, py)
    }

    /** Forward a tap to the keyboard. */
    fun onTap(px: Float, py: Float) {
        if (isVisible) keyboard?.onTap(px, py)
    }

    // ---- drawing ----

    /** Draw the keyboard keys (call inside the widget's `draw()`). */
    fun draw(canvas: Canvas) {
        if (isVisible) keyboard?.draw(canvas)
    }

    /** Draw the toggle-button icon at the bottom edge. */
    fun drawButton(canvas: Canvas) {
        canvas.drawRoundRect(buttonBounds, 6f, 6f, buttonPaint)

        val cx = buttonBounds.centerX()
        val cy = buttonBounds.centerY()
        val iconWidth = BUTTON_WIDTH * 0.5f
        val iconHeight = BUTTON_HEIGHT * 0.4f

        // Keyboard outline
        val kbRect = RectF(
            cx - iconWidth / 2, cy - iconHeight / 2,
            cx + iconWidth / 2, cy + iconHeight / 2
        )
        canvas.drawRoundRect(kbRect, 3f, 3f, iconPaint)

        // Key indicators (3 small lines)
        val keySpacing = iconWidth / 4
        val keyLength = iconHeight * 0.3f
        for (i in -1..1) {
            val keyX = cx + i * keySpacing
            canvas.drawLine(keyX, cy - keyLength / 2, keyX, cy + keyLength / 2, iconPaint)
        }
    }
}
