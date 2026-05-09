package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

class WidgetLayoutDeleteConfirmationPopup(
    private val screenWidth: Float,
    private val screenHeight: Float
) {
    companion object {
        private const val POPUP_WIDTH = 340f
        private const val POPUP_HEIGHT = 152f
        private const val BUTTON_HEIGHT = 42f
        private const val BUTTON_WIDTH = 118f
        private const val PADDING = 16f
        private const val CORNER_RADIUS = 12f
        private const val BUTTON_CORNER_RADIUS = 8f
        private const val BUTTON_SPACING = 18f
    }

    var isVisible = false
        private set

    private var layoutName = ""
    private var popupX = 0f
    private var popupY = 0f
    private val deleteButtonRect = RectF()
    private val backButtonRect = RectF()
    private var hoveredButton: String? = null

    var onDeleteConfirmed: ((String) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

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
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val detailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D7D7D7")
        textSize = 17f
        textAlign = Paint.Align.CENTER
    }

    private val deleteButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D32F2F")
    }

    private val deleteButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
    }

    private val backButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#424242")
    }

    private val backButtonHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161")
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 19f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#60000000")
    }

    fun show(name: String) {
        layoutName = name
        popupX = (screenWidth - POPUP_WIDTH) / 2f
        popupY = (screenHeight - POPUP_HEIGHT) / 2f

        val buttonsY = popupY + POPUP_HEIGHT - PADDING - BUTTON_HEIGHT
        val totalButtonsWidth = BUTTON_WIDTH * 2 + BUTTON_SPACING
        val buttonsStartX = popupX + (POPUP_WIDTH - totalButtonsWidth) / 2f

        deleteButtonRect.set(buttonsStartX, buttonsY, buttonsStartX + BUTTON_WIDTH, buttonsY + BUTTON_HEIGHT)
        backButtonRect.set(
            buttonsStartX + BUTTON_WIDTH + BUTTON_SPACING,
            buttonsY,
            buttonsStartX + BUTTON_WIDTH * 2 + BUTTON_SPACING,
            buttonsY + BUTTON_HEIGHT
        )

        hoveredButton = null
        isVisible = true
    }

    fun dismiss() {
        if (!isVisible) return
        isVisible = false
        hoveredButton = null
        onDismissed?.invoke()
    }

    fun updateHover(px: Float, py: Float) {
        if (!isVisible) return
        hoveredButton = when {
            deleteButtonRect.contains(px, py) -> "delete"
            backButtonRect.contains(px, py) -> "back"
            else -> null
        }
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (!isVisible) return false

        if (deleteButtonRect.contains(px, py)) {
            val name = layoutName
            dismiss()
            onDeleteConfirmed?.invoke(name)
            return true
        }

        if (backButtonRect.contains(px, py)) {
            dismiss()
            return true
        }

        if (RectF(popupX, popupY, popupX + POPUP_WIDTH, popupY + POPUP_HEIGHT).contains(px, py)) {
            return true
        }

        dismiss()
        return true
    }

    fun containsPoint(px: Float, py: Float): Boolean {
        return isVisible && RectF(popupX, popupY, popupX + POPUP_WIDTH, popupY + POPUP_HEIGHT).contains(px, py)
    }

    fun draw(canvas: Canvas) {
        if (!isVisible) return

        val bounds = RectF(popupX, popupY, popupX + POPUP_WIDTH, popupY + POPUP_HEIGHT)
        canvas.drawRoundRect(
            bounds.left + 6f,
            bounds.top + 6f,
            bounds.right + 6f,
            bounds.bottom + 6f,
            CORNER_RADIUS,
            CORNER_RADIUS,
            shadowPaint
        )
        canvas.drawRoundRect(bounds, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(bounds, CORNER_RADIUS, CORNER_RADIUS, borderPaint)

        canvas.drawText("Delete layout?", bounds.centerX(), bounds.top + 38f, titlePaint)
        canvas.drawText(fitText(layoutName, POPUP_WIDTH - 44f), bounds.centerX(), bounds.top + 68f, detailPaint)

        val deletePaint = if (hoveredButton == "delete") deleteButtonHoverPaint else deleteButtonPaint
        val backPaint = if (hoveredButton == "back") backButtonHoverPaint else backButtonPaint
        canvas.drawRoundRect(deleteButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, deletePaint)
        canvas.drawRoundRect(backButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, backPaint)

        val deleteY = deleteButtonRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        val backY = backButtonRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("Delete", deleteButtonRect.centerX(), deleteY, buttonTextPaint)
        canvas.drawText("Back", backButtonRect.centerX(), backY, buttonTextPaint)
    }

    private fun fitText(value: String, maxWidth: Float): String {
        if (detailPaint.measureText(value) <= maxWidth) return value
        var trimmed = value
        while (trimmed.length > 1 && detailPaint.measureText("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed..."
    }
}
