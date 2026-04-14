package com.everyday.everyday_glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * A simple text input view for AR glasses.
 * 
 * Click to focus (shows cursor, notifies phone to show keyboard).
 * Receives key events from phone to update text.
 * Click outside or press ENTER to unfocus.
 */
class TextInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onFocusChanged: ((Boolean) -> Unit)? = null
    
    private var text: String = ""
    private var isFocused: Boolean = false
    private var cursorVisible: Boolean = true
    private var cursorBlinkHandler: android.os.Handler? = null
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.WHITE
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 32f
        color = Color.GRAY
    }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 2f
    }
    
    private val rect = RectF()
    private val cornerRadius = 8f
    
    var hint: String = "Click to type..."
        set(value) {
            field = value
            invalidate()
        }
    
    init {
        // Make clickable
        isClickable = true
        isFocusable = true
    }
    
    fun getText(): String = text
    
    fun setText(newText: String) {
        text = newText
        invalidate()
    }
    
    override fun isFocused(): Boolean = isFocused
    
    /**
     * Called when user clicks on this view (via cursor click).
     * Toggles focus state.
     */
    fun requestTextFocus(): Boolean {
        if (!isFocused) {
            setFocused(true)
            return true
        }
        return false
    }
    
    /**
     * Called to unfocus this view (e.g., when clicking elsewhere or pressing ENTER).
     */
    fun clearTextFocus() {
        if (isFocused) {
            setFocused(false)
        }
    }
    
    private fun setFocused(focused: Boolean) {
        if (isFocused == focused) return
        
        isFocused = focused
        
        if (focused) {
            startCursorBlink()
        } else {
            stopCursorBlink()
        }
        
        onFocusChanged?.invoke(focused)
        invalidate()
    }
    
    /**
     * Handle a key press from the phone keyboard.
     */
    fun onKeyPress(key: String) {
        if (!isFocused) return
        
        when (key) {
            "BACKSPACE" -> {
                if (text.isNotEmpty()) {
                    text = text.dropLast(1)
                }
            }
            "ENTER" -> {
                // ENTER unfocuses the text field
                clearTextFocus()
                return
            }
            else -> {
                text += key
            }
        }
        
        // Reset cursor visibility on key press
        cursorVisible = true
        invalidate()
    }
    
    override fun performClick(): Boolean {
        super.performClick()
        requestTextFocus()
        return true
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        textPaint.textSize = h * 0.4f
        hintPaint.textSize = h * 0.4f
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Background
        backgroundPaint.color = if (isFocused) Color.parseColor("#222244") else Color.parseColor("#1a1a2e")
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, backgroundPaint)
        
        // Border
        borderPaint.color = if (isFocused) Color.parseColor("#4444AA") else Color.parseColor("#444466")
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)
        
        // Text or hint
        val displayText = if (text.isEmpty() && !isFocused) hint else text
        val paint = if (text.isEmpty() && !isFocused) hintPaint else textPaint
        
        val textX = 16f
        val textY = height / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(displayText, textX, textY, paint)
        
        // Cursor (only when focused)
        if (isFocused && cursorVisible) {
            val cursorX = textX + textPaint.measureText(text) + 2f
            val cursorTop = height * 0.2f
            val cursorBottom = height * 0.8f
            canvas.drawLine(cursorX, cursorTop, cursorX, cursorBottom, cursorPaint)
        }
    }
    
    private fun startCursorBlink() {
        cursorVisible = true
        cursorBlinkHandler = android.os.Handler(android.os.Looper.getMainLooper())
        
        val blinkRunnable = object : Runnable {
            override fun run() {
                if (isFocused) {
                    cursorVisible = !cursorVisible
                    invalidate()
                    cursorBlinkHandler?.postDelayed(this, 530)
                }
            }
        }
        cursorBlinkHandler?.postDelayed(blinkRunnable, 530)
    }
    
    private fun stopCursorBlink() {
        cursorBlinkHandler?.removeCallbacksAndMessages(null)
        cursorBlinkHandler = null
        cursorVisible = false
    }
    
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopCursorBlink()
    }
}
