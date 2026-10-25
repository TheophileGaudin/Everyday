package com.everyday.everyday_glasses.widgets.clock

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.TextPaint
import androidx.core.graphics.toColorInt
import com.everyday.everyday_glasses.interaction.commands.AppCommandDefinition
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetCommand
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetMenu
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The time, large, with the date below it. A tap shows or hides the seconds, and the choice is saved. */
class ClockWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(ClockPlugin.TYPE, x, y, width, height) {

    override val minimizeLabel: String = "C"

    /** Whether the time shows seconds; set by a tap, or by the saved dashboard. */
    var showsSeconds = false
        set(value) {
            field = value
            refresh()
        }

    private var timeText = ""
    private var dateText = ""

    private val minutesFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val secondsFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateFormat = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    private val timePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#C6D0DA".toColorInt()
        textAlign = Paint.Align.CENTER
    }

    init {
        refresh()
    }

    /** Reads the time again. True when what the clock shows changed. */
    fun refresh(now: Date = Date()): Boolean {
        val time = (if (showsSeconds) secondsFormat else minutesFormat).format(now)
        val date = dateFormat.format(now)
        if (time == timeText && date == dateText) return false
        timeText = time
        dateText = date
        return true
    }

    override fun contentSignature(): List<Any?> = listOf(showsSeconds)

    override fun onContentTap(px: Float, py: Float): Boolean {
        if (isMinimized || baseHitTest(px, py) != BaseHitArea.CONTENT) return false
        showsSeconds = !showsSeconds
        return true
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)
        if (shouldShowBorder()) canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }

        // The time takes about half the height, shrunk until it fits the width.
        val maxWidth = (widgetWidth - 2f * PADDING).coerceAtLeast(40f)
        timePaint.textSize = (widgetHeight * 0.46f).coerceIn(18f, 160f)
        while (timePaint.measureText(timeText) > maxWidth && timePaint.textSize > 14f) timePaint.textSize -= 2f
        datePaint.textSize = (timePaint.textSize * 0.4f).coerceAtLeast(12f)

        val timeBaseline = widgetBounds.centerY() - (timePaint.descent() + timePaint.ascent()) * 0.5f - datePaint.textSize * 0.5f
        canvas.drawText(timeText, widgetBounds.centerX(), timeBaseline, timePaint)
        canvas.drawText(dateText, widgetBounds.centerX(), timeBaseline + datePaint.textSize * 1.4f, datePaint)
    }

    private companion object {
        const val PADDING = 16f
    }
}

/** Puts the clock on the dashboard and moves it on every second while it's there. */
class ClockPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val current: () -> ClockWidget? = { services.singleton(TYPE) as? ClockWidget }

    // Each second: read the time, and redraw when the clock changed (not while the display is off).
    private val tick = object : Runnable {
        override fun run() {
            val clock = current() ?: return
            if (clock.refresh() && !services.isDisplayOff) services.notifyTransientChange()
            services.dashboardView.postDelayed(this, TICK_MS)
        }
    }

    override val lifecycle = WidgetLifecycleRegistration.singleton(
        ClockWidget::class, ClockWidgetState.Codec,
        current = current,
        create = ::create,
        restore = ::restore,
        capture = ::capture,
        configure = { startTicking() },
        beforeRemove = { stopTicking() },
    )

    override fun release() = stopTicking()

    private fun startTicking() {
        stopTicking()
        services.dashboardView.postDelayed(tick, TICK_MS)
    }

    private fun stopTicking() {
        services.dashboardView.removeCallbacks(tick)
    }

    private fun create(): ClockWidget {
        val x = ((services.viewportWidth - WIDTH) / 2f).coerceAtLeast(20f)
        return ClockWidget(x, 20f, WIDTH, HEIGHT)
    }

    private fun restore(state: ClockWidgetState): ClockWidget {
        val widget = ClockWidget(state.x, state.y, state.width, state.height)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight) {
            widget.showsSeconds = state.showsSeconds
        }
        return widget
    }

    private fun capture(widget: ClockWidget): ClockWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return ClockWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            showsSeconds = widget.showsSeconds,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    /** The clock as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("clock")
        override val descriptor = WidgetDescriptor(TYPE, "Clock", WidgetInstancePolicy.SINGLETON, order = 16, storesClosedTemplate = true)
        override val codec get() = ClockWidgetState.Codec
        override fun create(context: WidgetContext) = ClockPlugin(context)
        override val commands = listOf(
            WidgetCommand(
                AppCommandDefinition("toggle:clock", "Toggle: Clock", "Clock", "Show or hide the clock", shortcutEligible = true),
                WidgetMenu.TOGGLE
            ),
        )
        private const val WIDTH = 200f
        private const val HEIGHT = 110f
        private const val TICK_MS = 1_000L
    }
}

/** What a saved dashboard keeps of the clock: whether it shows the seconds. */
data class ClockWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    val showsSeconds: Boolean = false,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<ClockWidgetState>(ClockPlugin.TYPE, ClockWidgetState::class) {
        override fun toJson(state: ClockWidgetState): JSONObject = JSONObject().apply {
            putCommonFields(state)
            put("showsSeconds", state.showsSeconds)
        }

        override fun fromJson(payload: JSONObject): ClockWidgetState? =
            parseSizedWidgetObject(payload) { obj, c, w, h ->
                ClockWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    showsSeconds = obj.optBoolean("showsSeconds", false),
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }
    }
}