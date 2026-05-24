package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

class CloseAppHoverControl(
    private val onCloseRequested: () -> Unit
) : BaseHoverControl(ID, "Close app", DEFAULT_SIZE) {

    companion object {
        const val ID = "close_app"
        const val DEFAULT_SIZE = 40f
        const val DEFAULT_MARGIN = 16f
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935")
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 4f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun drawHovered(canvas: Canvas) {
        canvas.drawRoundRect(bounds, 4f, 4f, backgroundPaint)
        drawCross(canvas, 10f)
    }

    private fun drawCross(canvas: Canvas, padding: Float) {
        canvas.drawLine(
            bounds.left + padding,
            bounds.top + padding,
            bounds.right - padding,
            bounds.bottom - padding,
            crossPaint
        )
        canvas.drawLine(
            bounds.right - padding,
            bounds.top + padding,
            bounds.left + padding,
            bounds.bottom - padding,
            crossPaint
        )
    }

    override fun performAction() {
        onCloseRequested()
    }
}
