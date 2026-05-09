package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.everyday.shared.sync.SubtitleControl
import com.everyday.shared.sync.SubtitleControlAction
import com.everyday.shared.sync.SubtitleOptions
import com.everyday.shared.sync.SubtitleSource
import com.everyday.shared.sync.SubtitleStatus
import com.everyday.shared.sync.SubtitleStatusState
import com.everyday.shared.sync.SubtitleTranscript

class SubtitleWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(x, y, width, height) {

    companion object {
        const val DEFAULT_WIDTH = 520f
        const val DEFAULT_HEIGHT = 120f

        private const val CORNER_RADIUS = 8f
        private const val CONTENT_PADDING = 16f
        private const val TOGGLE_WIDTH = 74f
        private const val TOGGLE_HEIGHT = 34f
        private const val OPTION_ROW_HEIGHT = 34f
        private const val TEXT_TTL_MS = 8_000L
        private const val PHONE_DISCONNECTED_MESSAGE = "Phone disconnected"
    }

    override val minimizeLabel: String = "S"
    override val minWidth: Float = 260f
    override val minHeight: Float = 84f

    private val toggleBounds = RectF()
    private val optionsPanelBounds = RectF()
    private val optionRows = Array(3) { RectF() }

    private var optionsPanelVisible = false
    private var latestText = ""
    private var latestTextIsFinal = false
    private var latestTextAtMs = 0L
    private var statusMessage = "Stopped"
    private var statusState = SubtitleStatusState.STOPPED
    private var disconnectedNoticePersistent = false

    var captureEnabled: Boolean = false
        private set

    var options: SubtitleOptions = SubtitleOptions()
        private set

    var onSubtitleControlRequested: ((SubtitleControl) -> Unit)? = null
    var onStateChanged: (() -> Unit)? = null

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D0000000")
        style = Paint.Style.FILL
    }

    private val statusPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B0BEC5")
        textSize = 17f
    }

    private val subtitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 30f
        isSubpixelText = true
    }

    private val partialPaint = TextPaint(subtitlePaint).apply {
        color = Color.parseColor("#CCDCE9")
        isSubpixelText = true
    }

    private val fadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var fadeShaderTop = Float.NaN
    private var fadeShaderBottom = Float.NaN

    private val optionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
    }

    private val disabledOptionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#777777")
        textSize = 20f
    }

    private val checkboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#DDE7F2")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EE7A8")
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
    }

    private val toggleOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3A3A44")
        style = Paint.Style.FILL
    }

    private val toggleOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2D9CDB")
        style = Paint.Style.FILL
    }

    private val toggleKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        updateSubtitleBounds()
    }

    override fun updateBaseBounds() {
        super.updateBaseBounds()
        updateSubtitleBounds()
    }

    override fun containsPoint(px: Float, py: Float): Boolean {
        return super.containsPoint(px, py) ||
            toggleBounds.contains(px, py) ||
            (optionsPanelVisible && optionsPanelBounds.contains(px, py))
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
    }

    fun setCaptureEnabled(enabled: Boolean, notifyPhone: Boolean = true) {
        if (captureEnabled == enabled) return
        disconnectedNoticePersistent = false
        captureEnabled = enabled
        if (!enabled) {
            latestText = ""
            latestTextAtMs = 0L
            statusState = SubtitleStatusState.STOPPED
            statusMessage = "Stopped"
        } else if (statusState == SubtitleStatusState.STOPPED) {
            statusMessage = "Starting..."
        }

        if (notifyPhone) {
            sendControl(if (enabled) SubtitleControlAction.START else SubtitleControlAction.STOP)
        }
        onStateChanged?.invoke()
    }

    fun restoreOptions(
        phonePlaybackEnabled: Boolean,
        microphoneEnabled: Boolean,
        translationEnabled: Boolean
    ) {
        options = options.copy(
            source = SubtitleSource.PHONE_PLAYBACK,
            phonePlaybackEnabled = phonePlaybackEnabled,
            microphoneEnabled = microphoneEnabled,
            translationEnabled = translationEnabled
        )
        onStateChanged?.invoke()
    }

    fun applyStatus(status: SubtitleStatus) {
        disconnectedNoticePersistent = false
        statusState = status.state
        statusMessage = status.message ?: status.state.wireName.replace('_', ' ')
        if (status.state == SubtitleStatusState.STOPPED || status.state == SubtitleStatusState.ERROR) {
            captureEnabled = false
            latestText = ""
            latestTextAtMs = 0L
        }
        onStateChanged?.invoke()
    }

    fun applyTranscript(transcript: SubtitleTranscript) {
        if (!captureEnabled) return
        latestText = transcript.text
        latestTextIsFinal = transcript.isFinal
        latestTextAtMs = System.currentTimeMillis()
        if (transcript.text.isNotBlank()) {
            statusState = SubtitleStatusState.LISTENING
            statusMessage = "Listening"
        }
        onStateChanged?.invoke()
    }

    fun onPhoneDisconnected() {
        val wasCapturing = captureEnabled
        captureEnabled = false
        latestText = ""
        latestTextAtMs = 0L
        disconnectedNoticePersistent = wasCapturing
        statusState = if (wasCapturing) SubtitleStatusState.ERROR else SubtitleStatusState.STOPPED
        statusMessage = PHONE_DISCONNECTED_MESSAGE
        onStateChanged?.invoke()
    }

    override fun onPause() {
        setCaptureEnabled(false, notifyPhone = true)
    }

    override fun onDestroy() {
        setCaptureEnabled(false, notifyPhone = true)
    }

    fun onTap(px: Float, py: Float): Boolean {
        if (toggleBounds.contains(px, py)) {
            setCaptureEnabled(!captureEnabled)
            return true
        }

        if (optionsPanelVisible) {
            for (index in optionRows.indices) {
                if (optionRows[index].contains(px, py)) {
                    when (index) {
                        0 -> {
                            options = options.copy(
                                source = SubtitleSource.PHONE_PLAYBACK,
                                phonePlaybackEnabled = true
                            )
                            sendControl(SubtitleControlAction.SET_OPTIONS)
                        }
                        else -> {
                            // Microphone and translation are visible but disabled in v1.
                        }
                    }
                    onStateChanged?.invoke()
                    return true
                }
            }

            if (!widgetBounds.contains(px, py)) {
                optionsPanelVisible = false
                onStateChanged?.invoke()
                return true
            }
        }

        return false
    }

    fun onDoubleTap(px: Float, py: Float): Boolean {
        if (isMinimized || !containsPoint(px, py)) return false
        optionsPanelVisible = !optionsPanelVisible
        onStateChanged?.invoke()
        return true
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        val hasRecentText = captureEnabled &&
            latestText.isNotBlank() &&
            System.currentTimeMillis() - latestTextAtMs <= TEXT_TTL_MS
        val isDisconnectedHint = statusMessage == PHONE_DISCONNECTED_MESSAGE && !disconnectedNoticePersistent
        val showDisconnectedHint = isDisconnectedHint && (isHovering() || optionsPanelVisible)
        val showPersistentError = statusState == SubtitleStatusState.ERROR && !isDisconnectedHint
        val shouldDrawPanel = hasRecentText ||
            captureEnabled ||
            optionsPanelVisible ||
            shouldShowBorder() ||
            showPersistentError ||
            showDisconnectedHint

        if (shouldDrawPanel) {
            canvas.drawRoundRect(widgetBounds, CORNER_RADIUS, CORNER_RADIUS, panelPaint)
        }

        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, CORNER_RADIUS, CORNER_RADIUS, hoverBorderPaint)
        }

        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }

        if (isHovering() || optionsPanelVisible) {
            drawToggle(canvas)
        }

        if (hasRecentText) {
            drawSubtitleText(canvas)
        } else if (captureEnabled || showPersistentError || optionsPanelVisible || showDisconnectedHint) {
            drawStatus(canvas)
        }

        if (optionsPanelVisible) {
            drawOptionsPanel(canvas)
        }
    }

    private fun updateSubtitleBounds() {
        val top = y + CONTENT_PADDING
        toggleBounds.set(
            x + widgetWidth - CONTENT_PADDING - TOGGLE_WIDTH,
            top,
            x + widgetWidth - CONTENT_PADDING,
            top + TOGGLE_HEIGHT
        )

        val panelWidth = (widgetWidth * 0.66f).coerceAtLeast(250f)
        val panelHeight = OPTION_ROW_HEIGHT * 3 + CONTENT_PADDING * 1.5f
        optionsPanelBounds.set(
            x + CONTENT_PADDING,
            y + widgetHeight - panelHeight - CONTENT_PADDING,
            x + CONTENT_PADDING + panelWidth,
            y + widgetHeight - CONTENT_PADDING
        )

        var rowTop = optionsPanelBounds.top + CONTENT_PADDING * 0.75f
        for (row in optionRows) {
            row.set(
                optionsPanelBounds.left + CONTENT_PADDING,
                rowTop,
                optionsPanelBounds.right - CONTENT_PADDING,
                rowTop + OPTION_ROW_HEIGHT
            )
            rowTop += OPTION_ROW_HEIGHT
        }
    }

    private fun drawToggle(canvas: Canvas) {
        canvas.drawRoundRect(
            toggleBounds,
            TOGGLE_HEIGHT / 2f,
            TOGGLE_HEIGHT / 2f,
            if (captureEnabled) toggleOnPaint else toggleOffPaint
        )
        val knobRadius = TOGGLE_HEIGHT * 0.38f
        val knobX = if (captureEnabled) {
            toggleBounds.right - TOGGLE_HEIGHT / 2f
        } else {
            toggleBounds.left + TOGGLE_HEIGHT / 2f
        }
        canvas.drawCircle(knobX, toggleBounds.centerY(), knobRadius, toggleKnobPaint)
    }

    private fun drawSubtitleText(canvas: Canvas) {
        val displayText = latestText.lowercase()
        if (displayText.isEmpty()) return

        val padX = CONTENT_PADDING
        val padY = CONTENT_PADDING * 0.75f
        val interiorLeft = x + padX
        val interiorRight = x + widgetWidth - padX
        val interiorTop = y + padY
        val interiorBottom = y + widgetHeight - padY
        val interiorWidth = (interiorRight - interiorLeft).toInt().coerceAtLeast(1)
        val interiorHeight = (interiorBottom - interiorTop).coerceAtLeast(1f)

        val textPaint = if (latestTextIsFinal) subtitlePaint else partialPaint
        val sizedFont = (widgetHeight * 0.24f)
            .coerceAtMost(interiorWidth * 0.18f)
            .coerceIn(13f, 34f)
        textPaint.textSize = sizedFont

        val layout = StaticLayout.Builder
            .obtain(displayText, 0, displayText.length, textPaint, interiorWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(2f, 1.05f)
            .setIncludePad(false)
            .build()

        val totalH = layout.height.toFloat()
        // Anchor to bottom when overflowing so the most recent line stays in view.
        val translateY = if (totalH <= interiorHeight) {
            interiorTop + (interiorHeight - totalH) / 2f
        } else {
            interiorBottom - totalH
        }

        canvas.save()
        canvas.clipRect(interiorLeft, interiorTop, interiorRight, interiorBottom)
        canvas.translate(interiorLeft, translateY)
        layout.draw(canvas)
        canvas.restore()

        if (totalH > interiorHeight) {
            drawTopFade(canvas, interiorLeft, interiorTop, interiorRight, sizedFont)
        }
    }

    private fun drawTopFade(canvas: Canvas, left: Float, top: Float, right: Float, fontSize: Float) {
        val fadeBottom = top + (fontSize * 1.4f).coerceAtMost((widgetHeight * 0.4f))
        if (fadeShaderTop != top || fadeShaderBottom != fadeBottom) {
            fadePaint.shader = LinearGradient(
                0f, top, 0f, fadeBottom,
                0xD0000000.toInt(),
                0x00000000,
                Shader.TileMode.CLAMP
            )
            fadeShaderTop = top
            fadeShaderBottom = fadeBottom
        }
        canvas.drawRect(left, top, right, fadeBottom, fadePaint)
    }

    private fun drawStatus(canvas: Canvas) {
        val text = when {
            captureEnabled && statusState == SubtitleStatusState.LISTENING -> "Listening"
            else -> statusMessage
        }
        val reservedToggleWidth = if (isHovering() || optionsPanelVisible) TOGGLE_WIDTH + CONTENT_PADDING else 0f
        val textWidth = (widgetWidth - CONTENT_PADDING * 2f - reservedToggleWidth).toInt().coerceAtLeast(1)
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, statusPaint, textWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setMaxLines(2)
            .build()
        canvas.save()
        canvas.translate(x + CONTENT_PADDING, y + CONTENT_PADDING)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun drawOptionsPanel(canvas: Canvas) {
        canvas.drawRoundRect(optionsPanelBounds, CORNER_RADIUS, CORNER_RADIUS, panelPaint)
        drawOptionRow(canvas, 0, "Phone playback", options.phonePlaybackEnabled, enabled = true)
        drawOptionRow(canvas, 1, "Microphone", false, enabled = false)
        drawOptionRow(canvas, 2, "Translation", false, enabled = false)
    }

    private fun drawOptionRow(canvas: Canvas, index: Int, label: String, checked: Boolean, enabled: Boolean) {
        val row = optionRows[index]
        val boxSize = 20f
        val box = RectF(row.left, row.centerY() - boxSize / 2f, row.left + boxSize, row.centerY() + boxSize / 2f)
        checkboxPaint.color = if (enabled) Color.parseColor("#DDE7F2") else Color.parseColor("#777777")
        canvas.drawRect(box, checkboxPaint)
        if (checked) {
            canvas.drawLine(box.left + 4f, box.centerY(), box.left + 9f, box.bottom - 4f, checkPaint)
            canvas.drawLine(box.left + 9f, box.bottom - 4f, box.right - 4f, box.top + 4f, checkPaint)
        }

        val paint = if (enabled) optionTextPaint else disabledOptionTextPaint
        canvas.drawText(
            label,
            box.right + 12f,
            row.centerY() - (paint.descent() + paint.ascent()) / 2f,
            paint
        )
    }

    private fun sendControl(action: SubtitleControlAction) {
        onSubtitleControlRequested?.invoke(
            SubtitleControl(
                action = action,
                options = options.copy(
                    source = SubtitleSource.PHONE_PLAYBACK,
                    languageTag = "en-US",
                    phonePlaybackEnabled = true,
                    microphoneEnabled = false,
                    translationEnabled = false
                )
            )
        )
    }
}
