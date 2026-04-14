package com.everyday.everyday_phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * A soft keyboard view styled like a typical US English Android keyboard.
 * 
 * Features:
 * - QWERTY letter layout with shift support
 * - Numbers and common symbols mode
 * - Additional symbols mode
 * - Space bar, backspace, enter
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

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
    private val specialKeyColor = Color.parseColor("#303040")
    private val spaceKeyColor = Color.parseColor("#505060")
    private val shiftActiveColor = Color.parseColor("#5070AA")
    private val backgroundColor = Color.parseColor("#202030")

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        calculateKeyRects()
    }

    private fun getCurrentRows(): List<List<String>> = when (currentMode) {
        KeyboardMode.LETTERS -> lettersRows
        KeyboardMode.NUMBERS -> numbersRows
        KeyboardMode.SYMBOLS -> symbolsRows
    }

    private fun calculateKeyRects() {
        keyRects.clear()
        
        val rows = getCurrentRows()
        val padding = 3f
        val rowHeight = height / rows.size.toFloat()
        
        rows.forEachIndexed { rowIndex, row ->
            // Calculate total weight for this row
            val totalWeight = row.sumOf { (keyWeights[it] ?: 1f).toDouble() }.toFloat()
            val unitWidth = width / totalWeight
            
            var x = 0f
            val y = rowIndex * rowHeight
            
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Background
        canvas.drawColor(backgroundColor)

        val cornerRadius = 6f

        for (keyInfo in keyRects) {
            val (rect, key, display, _, isSpecial) = keyInfo
            
            // Determine key color
            paint.color = when {
                key == pressedKey -> keyPressedColor
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

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                pressedKey = findKeyAt(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val newKey = findKeyAt(event.x, event.y)
                if (newKey != pressedKey) {
                    pressedKey = newKey
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val key = findKeyAt(event.x, event.y)
                if (key != null && key == pressedKey) {
                    handleKeyPress(key)
                }
                pressedKey = null
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedKey = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findKeyAt(x: Float, y: Float): String? {
        for (keyInfo in keyRects) {
            if (keyInfo.rect.contains(x, y)) {
                return keyInfo.key
            }
        }
        return null
    }

    private fun handleKeyPress(key: String) {
        when (key) {
            "SHIFT" -> {
                if (isShiftActive && !isCapsLock) {
                    // Second tap within short time = caps lock
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
                invalidate()
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
                invalidate()
            }
            "ABC" -> {
                currentMode = KeyboardMode.LETTERS
                calculateKeyRects()
                invalidate()
            }
            "=/<" -> {
                currentMode = KeyboardMode.SYMBOLS
                calculateKeyRects()
                invalidate()
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
                    invalidate()
                }
            }
        }
    }
}
