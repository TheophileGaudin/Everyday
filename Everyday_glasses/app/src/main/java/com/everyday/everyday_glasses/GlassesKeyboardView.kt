package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * A soft keyboard for the glasses app, styled like a typical US English Android keyboard.
 *
 * Features:
 * - QWERTY letter layout with shift support
 * - Numbers and common symbols mode
 * - Additional symbols mode
 * - Space bar, backspace, enter
 * - Visual feedback on key press
 *
 * This is a drawable component that can be integrated into widgets like TextBoxWidget
 * and BrowserWidget. It renders at the bottom of the widget and compresses the
 * available content area.
 */
class GlassesKeyboardView(
    private var keyboardWidth: Float,
    private var keyboardHeight: Float
) {
    companion object {
        // Keyboard takes 40% of widget height when visible
        const val HEIGHT_RATIO = 0.40f

        // Minimum keyboard height to ensure usability
        const val MIN_HEIGHT = 150f
    }

    var onKeyPressed: ((String) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private val smallTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }

    private val keyRects = mutableListOf<KeyInfo>()
    private var pressedKey: String? = null
    private var highlightedKey: String? = null

    // Keyboard modes
    private enum class KeyboardMode { LETTERS, NUMBERS, SYMBOLS }
    private var currentMode = KeyboardMode.LETTERS
    private var isShiftActive = false
    private var isCapsLock = false

    // Key data class
    private data class KeyInfo(
        val rect: RectF,
        val key: String,
        val display: String,
        val weight: Float = 1f,
        val isSpecial: Boolean = false
    )

    // Keyboard layouts
    private val lettersRows = listOf(
        listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
        listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
        listOf("SHIFT", "z", "x", "c", "v", "b", "n", "m", "BACKSPACE"),
        listOf("123", ",", "SPACE", ".", "ENTER")
    )

    private val numbersRows = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("@", "#", "$", "_", "&", "-", "+", "(", ")", "/"),
        listOf("=/<", "!", "\"", "'", ":", ";", "?", "BACKSPACE"),
        listOf("ABC", ",", "SPACE", ".", "ENTER")
    )

    private val symbolsRows = listOf(
        listOf("~", "`", "|", "^", "*", "=", "[", "]", "{", "}"),
        listOf("€", "£", "¥", "₩", "%", "\\", "<", ">", "•", "°"),
        listOf("123", "©", "®", "™", "¶", "§", "…", "BACKSPACE"),
        listOf("ABC", ",", "SPACE", ".", "ENTER")
    )

    // Special key weights (how many units wide)
    private val keyWeights = mapOf(
        "SHIFT" to 1.5f,
        "BACKSPACE" to 1.5f,
        "123" to 1.3f,
        "ABC" to 1.3f,
        "=/<" to 1.3f,
        "SPACE" to 4f,
        "ENTER" to 1.5f
    )

    // Colors
    private val keyColor = Color.parseColor("#404050")
    private val keyPressedColor = Color.parseColor("#6060AA")
    private val keyHighlightColor = Color.parseColor("#5555AA")
    private val specialKeyColor = Color.parseColor("#303040")
    private val spaceKeyColor = Color.parseColor("#505060")
    private val shiftActiveColor = Color.parseColor("#5070AA")
    private val backgroundColor = Color.parseColor("#202030")

    // Position offset (set by the widget when drawing)
    private var offsetX = 0f
    private var offsetY = 0f

    /**
     * Update the keyboard dimensions. Call this when the widget resizes.
     */
    fun updateDimensions(width: Float, height: Float) {
        keyboardWidth = width
        keyboardHeight = height.coerceAtLeast(MIN_HEIGHT)
        calculateKeyRects()
    }

    /**
     * Set the position offset for drawing. Call before draw().
     */
    fun setOffset(x: Float, y: Float) {
        offsetX = x
        offsetY = y
        calculateKeyRects()
    }

    /**
     * Get the keyboard height for layout calculations.
     */
    fun getHeight(): Float = keyboardHeight

    private fun getCurrentRows(): List<List<String>> = when (currentMode) {
        KeyboardMode.LETTERS -> lettersRows
        KeyboardMode.NUMBERS -> numbersRows
        KeyboardMode.SYMBOLS -> symbolsRows
    }

    private fun calculateKeyRects() {
        keyRects.clear()

        val rows = getCurrentRows()
        val padding = 3f
        val rowHeight = keyboardHeight / rows.size.toFloat()

        rows.forEachIndexed { rowIndex, row ->
            // Calculate total weight for this row
            val totalWeight = row.sumOf { (keyWeights[it] ?: 1f).toDouble() }.toFloat()
            val unitWidth = keyboardWidth / totalWeight

            var x = offsetX
            val y = offsetY + rowIndex * rowHeight

            row.forEach { key ->
                val weight = keyWeights[key] ?: 1f
                val keyWidth = unitWidth * weight

                val rect = RectF(
                    x + padding,
                    y + padding,
                    x + keyWidth - padding,
                    y + rowHeight - padding
                )

                val display = getKeyDisplay(key)
                val isSpecial = key in listOf("SHIFT", "BACKSPACE", "123", "ABC", "=/<", "ENTER")

                keyRects.add(KeyInfo(rect, key, display, weight, isSpecial))
                x += keyWidth
            }
        }

        // Set text sizes based on key height
        textPaint.textSize = rowHeight * 0.38f
        smallTextPaint.textSize = rowHeight * 0.28f
    }

    private fun getKeyDisplay(key: String): String {
        return when (key) {
            "SHIFT" -> if (isCapsLock) "⇪" else "⇧"
            "BACKSPACE" -> "⌫"
            "ENTER" -> "↵"
            "SPACE" -> " "
            "123" -> "123"
            "ABC" -> "ABC"
            "=/<" -> "=/<"
            else -> {
                if (currentMode == KeyboardMode.LETTERS && (isShiftActive || isCapsLock) && key.length == 1 && key[0].isLetter()) {
                    key.uppercase()
                } else {
                    key
                }
            }
        }
    }

    /**
     * Draw the keyboard on the canvas.
     */
    fun draw(canvas: Canvas) {
        // Background
        val bgRect = RectF(offsetX, offsetY, offsetX + keyboardWidth, offsetY + keyboardHeight)
        paint.color = backgroundColor
        canvas.drawRect(bgRect, paint)

        val cornerRadius = 6f

        for (keyInfo in keyRects) {
            val (rect, key, display, _, isSpecial) = keyInfo

            // Determine key color
            paint.color = when {
                key == pressedKey || key == highlightedKey -> keyPressedColor
                key == "SHIFT" && (isShiftActive || isCapsLock) -> shiftActiveColor
                key == "SPACE" -> spaceKeyColor
                isSpecial -> specialKeyColor
                else -> keyColor
            }

            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

            // Draw key label
            val usePaint = if (isSpecial || key == "SPACE") smallTextPaint else textPaint
            val textY = rect.centerY() - (usePaint.descent() + usePaint.ascent()) / 2

            if (key == "SPACE") {
                // Draw a subtle line for space bar
                paint.color = Color.parseColor("#606070")
                paint.strokeWidth = 2f
                val lineY = rect.centerY()
                val lineMargin = rect.width() * 0.3f
                canvas.drawLine(rect.left + lineMargin, lineY, rect.right - lineMargin, lineY, paint)
            } else {
                canvas.drawText(display, rect.centerX(), textY, usePaint)
            }
        }
    }

    /**
     * Find which key is at the given coordinates.
     * Returns null if no key is at that position.
     */
    fun findKeyAt(x: Float, y: Float): String? {
        for (keyInfo in keyRects) {
            if (keyInfo.rect.contains(x, y)) {
                return keyInfo.key
            }
        }
        return null
    }

    /**
     * Check if the given coordinates are within the keyboard bounds.
     */
    fun containsPoint(x: Float, y: Float): Boolean {
        return x >= offsetX && x <= offsetX + keyboardWidth &&
               y >= offsetY && y <= offsetY + keyboardHeight
    }

    /**
     * Update hover/highlight state based on cursor position.
     * Call this when the cursor moves over the keyboard.
     */
    fun updateHover(x: Float, y: Float) {
        highlightedKey = findKeyAt(x, y)
    }

    /**
     * Clear the hover highlight.
     */
    fun clearHover() {
        highlightedKey = null
    }

    /**
     * Handle a tap at the given coordinates.
     * Returns true if the tap was on a key, false otherwise.
     */
    fun onTap(x: Float, y: Float): Boolean {
        val key = findKeyAt(x, y) ?: return false
        handleKeyPress(key)
        return true
    }

    /**
     * Simulate pressing the currently highlighted key.
     * Used for temple tap or trackpad tap when cursor is over a key.
     */
    fun pressHighlightedKey(): Boolean {
        val key = highlightedKey ?: return false
        handleKeyPress(key)
        return true
    }

    private fun handleKeyPress(key: String) {
        // Visual feedback
        pressedKey = key

        when (key) {
            "SHIFT" -> {
                if (isShiftActive && !isCapsLock) {
                    // Second tap = caps lock
                    isCapsLock = true
                    isShiftActive = true
                } else if (isCapsLock) {
                    // Tap when caps lock = turn off
                    isCapsLock = false
                    isShiftActive = false
                } else {
                    // First tap = shift once
                    isShiftActive = true
                }
                calculateKeyRects()
            }
            "BACKSPACE" -> {
                onKeyPressed?.invoke("BACKSPACE")
            }
            "ENTER" -> {
                onKeyPressed?.invoke("\n")
            }
            "SPACE" -> {
                onKeyPressed?.invoke(" ")
            }
            "123" -> {
                currentMode = KeyboardMode.NUMBERS
                calculateKeyRects()
            }
            "ABC" -> {
                currentMode = KeyboardMode.LETTERS
                calculateKeyRects()
            }
            "=/<" -> {
                currentMode = KeyboardMode.SYMBOLS
                calculateKeyRects()
            }
            else -> {
                // Regular character
                val outputKey = if (currentMode == KeyboardMode.LETTERS &&
                                    (isShiftActive || isCapsLock) &&
                                    key.length == 1 && key[0].isLetter()) {
                    key.uppercase()
                } else {
                    key
                }
                onKeyPressed?.invoke(outputKey)

                // Auto-disable shift after typing a letter (but not caps lock)
                if (isShiftActive && !isCapsLock && key.length == 1 && key[0].isLetter()) {
                    isShiftActive = false
                    calculateKeyRects()
                }
            }
        }

        // Clear pressed state after a short delay (handled by caller's invalidation)
        pressedKey = null
    }

    /**
     * Get the bounds of the keyboard for hit testing.
     */
    fun getBounds(): RectF {
        return RectF(offsetX, offsetY, offsetX + keyboardWidth, offsetY + keyboardHeight)
    }
}
