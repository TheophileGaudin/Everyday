package com.everyday.everyday_glasses.assistant.page_agent

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/** Head-locked confirmation surface shared by temple and phone-trackpad input. */
class PageAgentApprovalOverlay(
    private val invalidate: () -> Unit
) {
    private data class Pending(
        val runId: String,
        val request: PageAgentConfirmationRequest,
        val respond: (Boolean) -> Unit
    )

    private var pending: Pending? = null
    private val panel = RectF()
    private val rejectButton = RectF()
    private val allowButton = RectF()

    private val scrimPaint = Paint().apply { color = Color.argb(165, 0, 0, 0) }
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(28, 31, 37) }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(235, 180, 55)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val rejectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(82, 88, 98) }
    private val allowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(38, 126, 76) }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 19f
        isFakeBoldText = true
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 228, 233)
        textSize = 14f
    }
    private val riskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(244, 199, 94)
        textSize = 13f
    }
    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    fun show(
        runId: String,
        request: PageAgentConfirmationRequest,
        respond: (Boolean) -> Unit
    ) {
        pending?.respond?.invoke(false)
        pending = Pending(runId, request, respond)
        invalidate()
    }

    fun dismiss(runId: String) {
        if (pending?.runId != runId) return
        pending = null
        invalidate()
    }

    fun isVisible(): Boolean = pending != null

    /** A visible approval is modal: taps outside the buttons are consumed but do not approve. */
    fun onTap(x: Float, y: Float): Boolean {
        val current = pending ?: return false
        val approved = when {
            allowButton.contains(x, y) -> true
            rejectButton.contains(x, y) -> false
            else -> return true
        }
        pending = null
        invalidate()
        current.respond(approved)
        return true
    }

    fun draw(canvas: Canvas) {
        val current = pending ?: return
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), scrimPaint)

        val panelWidth = minOf(440f, canvas.width - 36f)
        val panelHeight = minOf(205f, canvas.height - 36f)
        val left = (canvas.width - panelWidth) / 2f
        val top = (canvas.height - panelHeight) / 2f
        panel.set(left, top, left + panelWidth, top + panelHeight)
        canvas.drawRoundRect(panel, 12f, 12f, panelPaint)
        canvas.drawRoundRect(panel, 12f, 12f, rimPaint)

        val textLeft = panel.left + 18f
        val textWidth = panel.width() - 36f
        canvas.drawText("Confirm browser action", textLeft, panel.top + 31f, titlePaint)
        canvas.drawText(fit(current.request.description, bodyPaint, textWidth), textLeft, panel.top + 62f, bodyPaint)
        drawWrappedRisk(canvas, current.request.riskDescription, textLeft, panel.top + 88f, textWidth)

        val buttonTop = panel.bottom - 52f
        val gap = 14f
        val buttonWidth = (textWidth - gap) / 2f
        rejectButton.set(textLeft, buttonTop, textLeft + buttonWidth, panel.bottom - 14f)
        allowButton.set(rejectButton.right + gap, buttonTop, panel.right - 18f, panel.bottom - 14f)
        canvas.drawRoundRect(rejectButton, 8f, 8f, rejectPaint)
        canvas.drawRoundRect(allowButton, 8f, 8f, allowPaint)
        val baseline = rejectButton.centerY() - (buttonTextPaint.ascent() + buttonTextPaint.descent()) / 2f
        canvas.drawText("Reject", rejectButton.centerX(), baseline, buttonTextPaint)
        canvas.drawText("Allow", allowButton.centerX(), baseline, buttonTextPaint)
    }

    private fun drawWrappedRisk(canvas: Canvas, text: String, x: Float, y: Float, width: Float) {
        val words = text.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val lines = mutableListOf<String>()
        var line = ""
        for (word in words) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (riskPaint.measureText(candidate) <= width) {
                line = candidate
            } else {
                if (line.isNotEmpty()) lines += line
                line = word
                if (lines.size == 1) break
            }
        }
        if (line.isNotEmpty() && lines.size < 2) lines += line
        lines.take(2).forEachIndexed { index, value ->
            canvas.drawText(fit(value, riskPaint, width), x, y + index * 19f, riskPaint)
        }
    }

    private fun fit(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > width) end--
        return text.substring(0, end) + "…"
    }
}
