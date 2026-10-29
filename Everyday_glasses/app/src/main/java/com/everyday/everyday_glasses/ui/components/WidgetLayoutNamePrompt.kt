package com.everyday.everyday_glasses.ui.components

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withClip
import com.everyday.everyday_glasses.interaction.user_input.TextEditAction
import com.everyday.everyday_glasses.interaction.user_input.TextZone
import com.everyday.everyday_glasses.interaction.user_input.TextZoneHost
import com.everyday.everyday_glasses.interaction.user_input.TextZoneKind

/**
 * Small modal prompt used for naming saved widget layouts.
 */
class WidgetLayoutNamePrompt(
    private val screenWidth: Float,
    private val screenHeight: Float
) {
    companion object {
        private const val PROMPT_WIDTH = 380f
        private const val PROMPT_HEIGHT = 176f
        private const val CORNER_RADIUS = 10f
        private const val BUTTON_HEIGHT = 38f
    }

    var isVisible = false
        private set

    var handler: LayoutNameHandler? = null

    /** Edits [nameZone] while the prompt is shown. */
    var textZoneHost: TextZoneHost? = null

    private var text = ""
    private var errorMessage: String? = null

    private val promptRect = RectF()
    private val inputRect = RectF()
    private val inputTextRect = RectF()
    private val saveButtonRect = RectF()
    private val cancelButtonRect = RectF()

    private val keyboardOverlay = KeyboardOverlayController().apply {
        onKeyPressed = { key ->
            textZoneHost?.let { host ->
                if (!host.isFocused(nameZone)) host.focus(nameZone)
                host.onKey(key)
            }
        }
    }

    /** The name field; typing, selection and clipboard are handled by the text editing controller. */
    val nameZone: TextZone = object : TextZone {
        override val kind = TextZoneKind.FIELD
        override val bounds: RectF get() = inputTextRect
        override val isScreenSpace = true
        override val fieldTextSize: Float get() = textPaint.textSize
        override fun content(): CharSequence = text
        override fun onTextEditAction(action: TextEditAction) {
            when (action) {
                is TextEditAction.Changed -> {
                    text = action.content.toString()
                    errorMessage = null
                }
                is TextEditAction.Submitted -> {
                    text = action.text
                    submit()
                }
                TextEditAction.Released, TextEditAction.LayoutChanged -> Unit
            }
        }
    }

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#EE222222".toColorInt()
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#666666".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val inputPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#151526".toColorInt()
        style = Paint.Style.FILL
    }

    private val inputBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#5B8DEF".toColorInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.LEFT
    }

    private val errorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#FFB0A8".toColorInt()
        textSize = 14f
        textAlign = Paint.Align.LEFT
    }

    private val primaryButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#4A90D9".toColorInt()
        style = Paint.Style.FILL
    }

    private val secondaryButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#616161".toColorInt()
        style = Paint.Style.FILL
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 17f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    init {
        updateLayout()
    }

    fun show(initialName: String) {
        text = initialName
        errorMessage = null
        isVisible = true
        keyboardOverlay.show()
        updateLayout()
        textZoneHost?.focus(nameZone, selectAll = true)
    }

    fun dismiss() {
        if (!isVisible) return
        isVisible = false
        textZoneHost?.release(nameZone)
        keyboardOverlay.hide()
        errorMessage = null
        handler?.onPromptDismissed()
    }

    fun showError(message: String) {
        if (!isVisible) return
        errorMessage = message
    }

    fun onTap(x: Float, y: Float): Boolean {
        if (!isVisible) return false
        if (keyboardOverlay.containsPoint(x, y)) {
            keyboardOverlay.onTap(x, y)
            return true
        }
        if (saveButtonRect.contains(x, y)) {
            submit()
            return true
        }
        if (cancelButtonRect.contains(x, y)) {
            dismiss()
            return true
        }
        if (inputRect.contains(x, y)) {
            textZoneHost?.tap(nameZone, x.coerceIn(inputTextRect.left, inputTextRect.right), y)
            return true
        }
        if (!promptRect.contains(x, y)) {
            dismiss()
        }
        return true
    }

    fun updateHover(x: Float, y: Float) {
        keyboardOverlay.updateHover(x, y)
    }

    fun containsPoint(x: Float, y: Float): Boolean {
        return isVisible && (promptRect.contains(x, y) || keyboardOverlay.containsPoint(x, y))
    }

    fun draw(canvas: Canvas) {
        if (!isVisible) return

        canvas.drawRoundRect(promptRect, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(promptRect, CORNER_RADIUS, CORNER_RADIUS, borderPaint)

        canvas.drawText("Save Layout As", promptRect.centerX(), promptRect.top + 34f, titlePaint)
        canvas.drawRoundRect(inputRect, 6f, 6f, inputPaint)
        canvas.drawRoundRect(inputRect, 6f, 6f, inputBorderPaint)

        canvas.withClip(inputTextRect) {
            if (textZoneHost?.drawZone(this, nameZone) != true) {
                val baseline = inputRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
                drawText(fitFromEnd(text, inputTextRect.width()), inputTextRect.left, baseline, textPaint)
            }
        }

        errorMessage?.let {
            canvas.drawText(it, inputRect.left, inputRect.bottom + 18f, errorPaint)
        }

        canvas.drawRoundRect(cancelButtonRect, 7f, 7f, secondaryButtonPaint)
        canvas.drawRoundRect(saveButtonRect, 7f, 7f, primaryButtonPaint)

        val cancelY = cancelButtonRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        val saveY = saveButtonRect.centerY() - (buttonTextPaint.descent() + buttonTextPaint.ascent()) / 2f
        canvas.drawText("Cancel", cancelButtonRect.centerX(), cancelY, buttonTextPaint)
        canvas.drawText("Save", saveButtonRect.centerX(), saveY, buttonTextPaint)

        keyboardOverlay.draw(canvas)
    }

    private fun submit() {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            errorMessage = "Name required"
            return
        }
        handler?.submitLayoutName(trimmed)
    }

    private fun updateLayout() {
        val left = ((screenWidth - PROMPT_WIDTH) / 2f).coerceAtLeast(8f)
        val top = 22f
        promptRect.set(left, top, left + PROMPT_WIDTH.coerceAtMost(screenWidth - 16f), top + PROMPT_HEIGHT)
        inputRect.set(promptRect.left + 20f, promptRect.top + 54f, promptRect.right - 20f, promptRect.top + 100f)
        inputTextRect.set(inputRect.left + 12f, inputRect.top, inputRect.right - 12f, inputRect.bottom)

        val buttonTop = promptRect.bottom - BUTTON_HEIGHT - 18f
        val buttonWidth = 116f
        saveButtonRect.set(promptRect.right - 20f - buttonWidth, buttonTop, promptRect.right - 20f, buttonTop + BUTTON_HEIGHT)
        cancelButtonRect.set(saveButtonRect.left - 12f - buttonWidth, buttonTop, saveButtonRect.left - 12f, buttonTop + BUTTON_HEIGHT)

        keyboardOverlay.updateLayout(screenWidth, screenHeight, 0f, 0f)
    }

    private fun fitFromEnd(value: String, maxWidth: Float): String {
        if (textPaint.measureText(value) <= maxWidth) return value
        var trimmed = value
        while (trimmed.length > 1 && textPaint.measureText("...$trimmed") > maxWidth) {
            trimmed = trimmed.drop(1)
        }
        return "...$trimmed"
    }
}