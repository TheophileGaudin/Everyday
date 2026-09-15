package com.everyday.everyday_glasses.agent_tools

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.everyday.everyday_glasses.ui.hover_controls.BaseHoverControl
import com.everyday.shared.transport.AssistantSessionState

class AssistantVoiceHoverControl(private val onToggle: () -> Unit) :
    BaseHoverControl(ID, "Gemini voice", DEFAULT_SIZE) {
    companion object {
        const val ID = "assistant_voice"
        const val LEGACY_ID = "youtube_history"
        const val DEFAULT_SIZE = 64f
    }

    var configured: Boolean = false
    var state: AssistantSessionState = AssistantSessionState.UNCONFIGURED

    override fun containsPoint(px: Float, py: Float): Boolean = configured && super.containsPoint(px, py)
    override fun handleTap(px: Float, py: Float): Boolean = configured && super.handleTap(px, py)
    override fun draw(canvas: Canvas) {
        if (!configured || !isPlaced) return
        if (isHovered || state == AssistantSessionState.LISTENING || state == AssistantSessionState.PROCESSING) drawHovered(canvas)
    }

    override fun drawHovered(canvas: Canvas) {
        val color = when (state) {
            AssistantSessionState.LISTENING -> Color.rgb(210, 45, 45)
            AssistantSessionState.PROCESSING -> Color.rgb(224, 148, 20)
            AssistantSessionState.SUCCESS -> Color.rgb(45, 170, 90)
            AssistantSessionState.ERROR -> Color.rgb(190, 65, 65)
            else -> Color.rgb(80, 80, 80)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), size * .45f, paint)
        val cx = bounds.centerX()
        val cy = bounds.centerY() - 4f
        canvas.drawRoundRect(cx - 8f, cy - 16f, cx + 8f, cy + 10f, 8f, 8f, stroke)
        canvas.drawArc(cx - 15f, cy - 2f, cx + 15f, cy + 22f, 0f, 180f, false, stroke)
        canvas.drawLine(cx, cy + 22f, cx, cy + 29f, stroke)
    }

    override fun performAction() = onToggle()
}
