package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

class YouTubeHistoryHoverControl(
    private val onOpenHistory: () -> Unit
) : BaseHoverControl(ID, "YouTube history", DEFAULT_SIZE) {

    companion object {
        const val ID = "youtube_history"
        const val DEFAULT_SIZE = 64f
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
    }

    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0000")
        style = Paint.Style.FILL
    }

    private val playIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    override fun drawHovered(canvas: Canvas) {
        canvas.drawRoundRect(bounds, 8f, 8f, backgroundPaint)

        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val iconWidth = size * 0.6f
        val iconHeight = size * 0.42f
        val iconRect = RectF(
            cx - iconWidth / 2f,
            cy - iconHeight / 2f,
            cx + iconWidth / 2f,
            cy + iconHeight / 2f
        )

        canvas.drawRoundRect(iconRect, 8f, 8f, iconPaint)

        val playSize = iconHeight * 0.4f
        val playPath = Path().apply {
            moveTo(cx - playSize * 0.4f, cy - playSize)
            lineTo(cx - playSize * 0.4f, cy + playSize)
            lineTo(cx + playSize * 0.8f, cy)
            close()
        }
        canvas.drawPath(playPath, playIconPaint)
    }

    override fun performAction() {
        onOpenHistory()
    }
}
