package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log

/**
 * A simple popup dialog for confirming app close.
 * 
 * Shows a popup with "Close" and "Back" buttons.
 * Tapping "Close" will close the app, "Back" will dismiss the popup.
 */
class CloseConfirmationPopup(
    private val screenWidth: Float,
    private val screenHeight: Float
) {
    companion object {
        private const val TAG = "CloseConfirmationPopup"
        
        private const val POPUP_WIDTH = 280f
        private const val POPUP_HEIGHT = 140f
        private const val BUTTON_HEIGHT = 44f
        private const val BUTTON_WIDTH = 100f
        private const val PADDING = 16f
        private const val CORNER_RADIUS = 12f
        private const val BUTTON_CORNER_RADIUS = 8f
        private const val BUTTON_SPACING = 20f
    }
    
    var isVisible = false
        private set
    
    private var popupX = 0f
    private var popupY = 0f
    
    private val closeButtonRect = RectF()
    private val backButtonRect = RectF()
    
    private var hoveredButton: String? = null  // "close", "back", or null
    
    // Callbacks
    var onCloseConfirmed: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    
    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2a2a3e")
    }
    
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#4a4a6e")
    }
    
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }
    
    private val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")  // Red
    }
    
    private val closeButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")  // Lighter red on hover
    }
    
    private val backButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#424242")  // Gray
    }
    
    private val backButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161")  // Lighter gray on hover
    }
    
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }
    
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60000000")
    }
    
    /**
     * Show the popup centered on screen.
     */
    fun show() {
        // Center the popup
        popupX = (screenWidth - POPUP_WIDTH) / 2f
        popupY = (screenHeight - POPUP_HEIGHT) / 2f
        
        // Calculate button positions
        val buttonsY = popupY + POPUP_HEIGHT - PADDING - BUTTON_HEIGHT
        val totalButtonsWidth = BUTTON_WIDTH * 2 + BUTTON_SPACING
        val buttonsStartX = popupX + (POPUP_WIDTH - totalButtonsWidth) / 2f
        
        closeButtonRect.set(
            buttonsStartX,
            buttonsY,
            buttonsStartX + BUTTON_WIDTH,
            buttonsY + BUTTON_HEIGHT
        )
        
        backButtonRect.set(
            buttonsStartX + BUTTON_WIDTH + BUTTON_SPACING,
            buttonsY,
            buttonsStartX + BUTTON_WIDTH * 2 + BUTTON_SPACING,
            buttonsY + BUTTON_HEIGHT
        )
        
        hoveredButton = null
        isVisible = true
        
        Log.d(TAG, "Popup shown at ($popupX, $popupY)")
    }
    
    /**
     * Hide the popup.
     */
    fun dismiss() {
        if (isVisible) {
            isVisible = false
            hoveredButton = null
            onDismissed?.invoke()
            Log.d(TAG, "Popup dismissed")
        }
    }
    
    /**
     * Update hover state based on cursor position.
     */
    fun updateHover(px: Float, py: Float) {
        if (!isVisible) return
        
        hoveredButton = when {
            closeButtonRect.contains(px, py) -> "close"
            backButtonRect.contains(px, py) -> "back"
            else -> null
        }
    }
    
    /**
     * Handle tap at the given position.
     * Returns true if the tap was handled.
     */
    fun onTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false
        
        // Check if tap is on Close button
        if (closeButtonRect.contains(px, py)) {
            Log.d(TAG, "Close button tapped")
            dismiss()
            onCloseConfirmed?.invoke()
            return true
        }
        
        // Check if tap is on Back button
        if (backButtonRect.contains(px, py)) {
            Log.d(TAG, "Back button tapped")
            dismiss()
            return true
        }
        
        // Check if tap is inside popup (but not on buttons) - consume but ignore
        val popupBounds = RectF(popupX, popupY, popupX + POPUP_WIDTH, popupY + POPUP_HEIGHT)
        if (popupBounds.contains(px, py)) {
            return true  // Consume the tap
        }
        
        // Tap outside popup - dismiss
        dismiss()
        return true
    }
    
    /**
     * Check if a point is within the popup area.
     */
    fun containsPoint(px: Float, py: Float): Boolean {
        if (!isVisible) return false
        val popupBounds = RectF(popupX, popupY, popupX + POPUP_WIDTH, popupY + POPUP_HEIGHT)
        return popupBounds.contains(px, py)
    }
    
    /**
     * Draw the popup.
     */
    fun draw(canvas: Canvas) {
        if (!isVisible) return
        
        val popupBounds = RectF(popupX, popupY, popupX + POPUP_WIDTH, popupY + POPUP_HEIGHT)
        
        // Shadow
        canvas.drawRoundRect(
            popupBounds.left + 6f,
            popupBounds.top + 6f,
            popupBounds.right + 6f,
            popupBounds.bottom + 6f,
            CORNER_RADIUS, CORNER_RADIUS,
            shadowPaint
        )
        
        // Background
        canvas.drawRoundRect(popupBounds, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(popupBounds, CORNER_RADIUS, CORNER_RADIUS, borderPaint)
        
        // Title
        val titleY = popupY + PADDING + titlePaint.textSize
        canvas.drawText("Close app?", popupX + POPUP_WIDTH / 2f, titleY, titlePaint)
        
        // Close button
        val closeButtonBgPaint = if (hoveredButton == "close") closeButtonHoverPaint else closeButtonPaint
        canvas.drawRoundRect(closeButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, closeButtonBgPaint)
        
        val closeTextY = closeButtonRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("Close", closeButtonRect.centerX(), closeTextY, buttonTextPaint)
        
        // Back button
        val backButtonBgPaint = if (hoveredButton == "back") backButtonHoverPaint else backButtonPaint
        canvas.drawRoundRect(backButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, backButtonBgPaint)
        
        val backTextY = backButtonRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("Back", backButtonRect.centerX(), backTextY, buttonTextPaint)
    }
}
