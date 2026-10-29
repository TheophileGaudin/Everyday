package com.everyday.everyday_glasses.ui.hover_controls

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.toColorInt

class CloseAppHoverControl(
    private val onCloseRequested: () -> Unit
) : BaseHoverControl(ID, "Close app", DEFAULT_SIZE) {

    companion object {
        const val ID                    = "close_app"
        const val DEFAULT_SIZE          = 40f
        const val DEFAULT_MARGIN        = 16f
        private const val CROSS_PADDING = 10f
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#E53935".toColorInt()
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun drawHovered(canvas: Canvas) {
        canvas.drawRoundRect(bounds, 4f, 4f, backgroundPaint)
        drawCross(canvas)
    }

    private fun drawCross(canvas: Canvas) {
        canvas.drawLine(
            bounds.left + CROSS_PADDING,
            bounds.top + CROSS_PADDING,
            bounds.right - CROSS_PADDING,
            bounds.bottom - CROSS_PADDING,
            crossPaint
        )
        canvas.drawLine(
            bounds.right - CROSS_PADDING,
            bounds.top + CROSS_PADDING,
            bounds.left + CROSS_PADDING,
            bounds.bottom - CROSS_PADDING,
            crossPaint
        )
    }

    override fun performAction() {
        onCloseRequested()
    }
}
