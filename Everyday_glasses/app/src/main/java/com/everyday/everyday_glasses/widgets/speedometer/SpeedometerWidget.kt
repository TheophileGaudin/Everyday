package com.everyday.everyday_glasses.widgets.speedometer

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.persistence.DashboardPersistence.parseSizedWidgetObject
import com.everyday.everyday_glasses.persistence.DashboardPersistence.putCommonFields
import com.everyday.everyday_glasses.persistence.WidgetDocument
import com.everyday.everyday_glasses.runtime.WidgetContext
import com.everyday.everyday_glasses.runtime.WidgetPlugin
import com.everyday.everyday_glasses.runtime.AppChannels
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.settings.UnitDisplayFormatter
import java.util.Locale
import kotlin.math.abs
import androidx.core.graphics.toColorInt
import com.everyday.everyday_glasses.runtime.SpeedState
import com.everyday.everyday_glasses.runtime.WidgetDescriptor
import com.everyday.everyday_glasses.runtime.WidgetInstancePolicy
import com.everyday.everyday_glasses.runtime.WidgetType
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import org.json.JSONObject

/**
 * Speedometer widget that displays movement speed from local or phone location updates.
 *
 * Behavior:
 * - Uses BaseWidget for move/resize/pin/minimize/fullscreen behavior.
 * - Hides speed content when not moving, but still supports hover border/buttons.
 * - Supports in-widget unit toggle (km/h <-> mph) via a unit chip tap target.
 */
class SpeedometerWidget(
    x: Float,
    y: Float,
    width: Float,
    height: Float
) : BaseWidget(SpeedometerPlugin.TYPE, x, y, width, height) {

    enum class SpeedUnit(val code: String, val label: String) {
        KMH("kmh", "km/h"),
        MPH("mph", "mph");

        companion object {
            fun fromCode(code: String?): SpeedUnit {
                return SpeedUnit.entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: KMH
            }
        }
    }

    override val minimizeLabel: String = "V"
    override val minWidth: Float = MIN_WIDGET_SIZE
    override val minHeight: Float = MIN_WIDGET_SIZE

    private var smoothedSpeedKmh = 0f
    private var hasReliableSpeed = false
    private var isMoving = false
    private var forceVisibleWhenIdle = false
    private var speedUnit = SpeedUnit.KMH

    var onUnitChanged: ((SpeedUnit) -> Unit)? = null
    var onSettingsRequested: (() -> Unit)? = null

    private val speedTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 52f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val unitTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#C6D0DA".toColorInt()
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    private val unitChipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#335060".toColorInt()
        style = Paint.Style.FILL
    }

    private val unitChipHoverPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = "#4480A0".toColorInt()
        style = Paint.Style.FILL
    }

    private val unitChipTextPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val unitChipBounds = RectF()
    private var unitChipHovered = false

    fun setUnit(unit: SpeedUnit) {
        if (speedUnit == unit) return
        speedUnit = unit
    }

    fun setForceVisibleWhenIdle(forceVisible: Boolean): Boolean {
        if (forceVisibleWhenIdle == forceVisible) return false
        forceVisibleWhenIdle = forceVisible
        return true
    }

    /** Whether the speedometer currently presents content rather than only its hover shell. */
    fun isContentVisible(): Boolean = forceVisibleWhenIdle || (isMoving && hasReliableSpeed)

    /**
     * Update speed state.
     *
     * @return true when visible state changed and container should redraw.
     */
    fun setSpeedData(kmh: Float, qualityOk: Boolean, moving: Boolean): Boolean {
        val newKmh = kmh.coerceAtLeast(0f)
        val changed = abs(smoothedSpeedKmh - newKmh) > 0.01f ||
                hasReliableSpeed != qualityOk ||
                isMoving != moving
        smoothedSpeedKmh = newKmh
        hasReliableSpeed = qualityOk
        isMoving = moving
        return changed
    }

    fun setPermissionDenied() {
        smoothedSpeedKmh = 0f
        hasReliableSpeed = false
        isMoving = false
    }

    override fun onContentTap(px: Float, py: Float): Boolean {
        val canToggleUnit = forceVisibleWhenIdle || (isMoving && hasReliableSpeed)
        val canShowUnitChip = canToggleUnit && isHovering()
        if (canShowUnitChip && unitChipBounds.contains(px, py)) {
            speedUnit = if (speedUnit == SpeedUnit.KMH) SpeedUnit.MPH else SpeedUnit.KMH
            onUnitChanged?.invoke(speedUnit)
            return true
        }
        return false
    }

    /** A double-tap on the speedometer opens its settings. */
    override fun onDoubleTap(px: Float, py: Float): Boolean {
        if (!isFullscreen && baseHitTest(px, py) != BaseHitArea.CONTENT) return false
        onSettingsRequested?.invoke()
        return true
    }

    override fun updateHover(px: Float, py: Float) {
        updateHoverState(px, py)
        val canToggleUnit = forceVisibleWhenIdle || (isMoving && hasReliableSpeed)
        val canShowUnitChip = canToggleUnit && isHovering()
        unitChipHovered = canShowUnitChip && unitChipBounds.contains(px, py)
    }

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        // Always draw shell so hover border/buttons still work when content is hidden.
        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)

        if (shouldShowBorder()) {
            canvas.drawRoundRect(widgetBounds, 8f, 8f, hoverBorderPaint)
        }
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
            drawResizeHandle(canvas)
        }

        if (!isContentVisible()) {
            unitChipBounds.setEmpty()
            unitChipHovered = false
            return
        }

        val baseSpeedKmh = if (hasReliableSpeed) smoothedSpeedKmh else 0f
        val displaySpeed = UnitDisplayFormatter.speedFromKmh(baseSpeedKmh, speedUnit)
        val speedText = if (displaySpeed >= 10f) {
            String.format(Locale.ENGLISH, "%.0f", displaySpeed)
        } else {
            String.format(Locale.ENGLISH, "%.1f", displaySpeed)
        }

        val horizontalPadding = 20f
        val verticalPadding = 14f
        val shouldShowUnitChip = isHovering()
        if (shouldShowUnitChip) {
            val chipWidth = 78f
            val chipHeight = 32f
            val chipLeft = widgetBounds.right - horizontalPadding - chipWidth
            val chipTop = widgetBounds.top + verticalPadding
            unitChipBounds.set(chipLeft, chipTop, chipLeft + chipWidth, chipTop + chipHeight)

            canvas.drawRoundRect(
                unitChipBounds,
                10f,
                10f,
                if (unitChipHovered) unitChipHoverPaint else unitChipPaint
            )

            val chipBaseline = unitChipBounds.centerY() - (unitChipTextPaint.descent() + unitChipTextPaint.ascent()) * 0.5f
            canvas.drawText(speedUnit.label, unitChipBounds.centerX(), chipBaseline, unitChipTextPaint)
        } else {
            unitChipBounds.setEmpty()
            unitChipHovered = false
        }

        val maxWidth = (widgetWidth - (horizontalPadding * 2f)).coerceAtLeast(80f)
        val maxHeight = (widgetHeight * 0.44f).coerceIn(24f, 72f)
        speedTextPaint.textSize = maxHeight
        while (speedTextPaint.measureText(speedText) > maxWidth && speedTextPaint.textSize > 22f) {
            speedTextPaint.textSize -= 2f
        }

        val speedCenterY = widgetBounds.centerY() + 8f
        val speedBaseline = speedCenterY - (speedTextPaint.descent() + speedTextPaint.ascent()) * 0.5f
        canvas.drawText(speedText, widgetBounds.centerX(), speedBaseline, speedTextPaint)

        val unitBaseline = speedBaseline + speedTextPaint.textSize * 0.62f
        canvas.drawText(speedUnit.label, widgetBounds.centerX(), unitBaseline, unitTextPaint)
    }
}
/** Puts the speedometer on the dashboard and feeds it the speed from the location sources. */
class SpeedometerPlugin(
    private val services: WidgetContext,
) : WidgetPlugin {
    private val current: () -> SpeedometerWidget? = { services.singleton(TYPE) as? SpeedometerWidget }
    private val showSettings: () -> Unit = { services.settings.showSpeedometerSettingsMenu() }
    private val dismissSettings: () -> Unit = { services.settings.dismissSpeedometerSettingsMenu() }
    private val forceVisibleWhenIdle: () -> Boolean = { services.settings.getSpeedVisibilityThreshold() <= 0f }
    // The last speed received, so that a speedometer opened later shows it right away.
    private val latest: SpeedState get() = services.appData.latest(AppChannels.speed) ?: NO_SPEED

    init {
        services.appData.subscribe(AppChannels.speed) { state ->
            if (state == null) {
                current()?.setPermissionDenied()
                if (!services.isDisplayOff) services.notifyTransientChange()
            } else {
                val changed = current()?.setSpeedData(state.speedKmh, state.qualityOk, state.isMoving) ?: false
                if (changed && !services.isDisplayOff) services.notifyTransientChange()
            }
        }
        services.appData.subscribe(AppChannels.unitsChanged) { current()?.setUnit(services.units.effectiveSpeedUnit()) }
    }

    override val lifecycle = WidgetLifecycleRegistration.singleton(
        SpeedometerWidget::class, SpeedometerWidgetState.Codec,
        current = current,
        create = ::create,
        restore = ::restore,
        capture = ::capture,
        beforeRemove = { dismissSettings() },
    )

    private fun create(): SpeedometerWidget {
        val x = (services.viewportWidth - WIDTH - 20f).coerceAtLeast(20f)
        val y = (services.viewportHeight - HEIGHT - 20f).coerceAtLeast(20f)
        return build(x, y, WIDTH, HEIGHT)
    }

    private fun restore(state: SpeedometerWidgetState): SpeedometerWidget {
        val widget = build(state.x, state.y, state.width, state.height)
        services.captureRestore.restoreCommonFields(widget, state, services.viewportWidth, services.viewportHeight)
        return widget
    }

    private fun capture(widget: SpeedometerWidget): SpeedometerWidgetState {
        val c = services.captureRestore.extractCommonFields(widget)
        return SpeedometerWidgetState(
            x = c.x, y = c.y, width = c.width, height = c.height,
            isMinimized = c.isMinimized,
            isFullscreen = c.isFullscreen,
            savedMinX = c.savedMinX, savedMinY = c.savedMinY,
            savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
            isPinned = c.isPinned
        )
    }

    private fun build(x: Float, y: Float, width: Float, height: Float) =
        SpeedometerWidget(x, y, width, height).apply {
            val units = this@SpeedometerPlugin.services.units
            onUnitChanged = { units.toggleSpeedOverride() }
            onSettingsRequested = showSettings
            setUnit(units.effectiveSpeedUnit())
            setForceVisibleWhenIdle(forceVisibleWhenIdle())
            setSpeedData(latest.speedKmh, latest.qualityOk, latest.isMoving)
        }

    /** The speedometer as a widget type: its id, its descriptor, how it is saved, and its plugin. */
    companion object : WidgetType {
        val TYPE = WidgetTypeId("speedometer")
        override val descriptor = WidgetDescriptor(
            TYPE,
            "Speedometer",
            WidgetInstancePolicy.SINGLETON,
            order = 7,
            category = "information",
            storesClosedTemplate = true,
            menuOrder = 4
        )
        override val codec get() = SpeedometerWidgetState.Codec
        override fun create(context: WidgetContext) = SpeedometerPlugin(context)
        private const val WIDTH = 220f
        private const val HEIGHT = 110f
        private val NO_SPEED = SpeedState(speedKmh = 0f, qualityOk = false, isMoving = false)
    }
}

/** What a saved dashboard keeps of the speedometer: only the common fields. */
data class SpeedometerWidgetState(
    override val x: Float,
    override val y: Float,
    override val width: Float,
    override val height: Float,
    override val isMinimized: Boolean = false,
    override val isFullscreen: Boolean = false,
    override val savedMinX: Float = 0f,
    override val savedMinY: Float = 0f,
    override val savedMinWidth: Float = 0f,
    override val savedMinHeight: Float = 0f,
    override val isPinned: Boolean = false
) : DashboardPersistence.SizedWidgetFields {
    /** Its entry in the saved dashboard file. */
    companion object Codec : WidgetDocument.StateCodec<SpeedometerWidgetState>(SpeedometerPlugin.TYPE, SpeedometerWidgetState::class) {
        override fun toJson(state: SpeedometerWidgetState): JSONObject = JSONObject().apply { putCommonFields(state) }

        override fun fromJson(payload: JSONObject): SpeedometerWidgetState? =
            parseSizedWidgetObject(payload) { _, c, w, h ->
                SpeedometerWidgetState(
                    x = c.x, y = c.y, width = w, height = h,
                    isMinimized = c.isMinimized,
                    isFullscreen = c.isFullscreen,
                    savedMinX = c.savedMinX, savedMinY = c.savedMinY,
                    savedMinWidth = c.savedMinWidth, savedMinHeight = c.savedMinHeight,
                    isPinned = c.isPinned
                )
            }
    }
}