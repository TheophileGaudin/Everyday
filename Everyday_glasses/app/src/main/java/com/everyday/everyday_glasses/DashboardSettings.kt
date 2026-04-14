package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * A standalone modal menu for app settings (brightness, head-up wake settings).
 * Not a widget (does not extend BaseWidget), but a system overlay similar to ContextMenu.
 *
 * Has three view modes:
 * - Main menu: Brightness slider + adaptive brightness toggle + "Heads-up" button + "Google" button
 * - Heads-up submenu: Head-up specific settings (time, duration, angle, enable toggle)
 * - Google submenu: Google sign-in and Calendar authorization state/actions
 */
class DashboardSettings(
    private val screenWidth: Float,
    private val screenHeight: Float
) {

    companion object {
        private const val TAG = "DashboardSettings"
        private const val MENU_WIDTH = 320f
        private const val MAIN_MENU_HEIGHT = 285f  // Main menu: brightness + adaptive brightness + heads-up + Google button
        private const val HEADSUP_MENU_HEIGHT = 350f  // Heads-up submenu: 3 sliders + toggle + back button
        private const val GOOGLE_MENU_HEIGHT = 360f
        private const val CORNER_RADIUS = 12f
        private const val CLOSE_BUTTON_SIZE = 40f
        private const val CLOSE_BUTTON_MARGIN = 10f

        private const val SLIDER_MARGIN_X = 25f
        private const val SLIDER_TRACK_HEIGHT = 4f
        private const val THUMB_RADIUS = 12f
        private const val SLIDER_SPACING = 70f  // Vertical spacing between sliders
        private const val LABEL_OFFSET_Y = -25f  // Label above slider

        private const val BUTTON_HEIGHT = 44f
        private const val BUTTON_CORNER_RADIUS = 8f
        private const val TOGGLE_WIDTH = 50f
        private const val TOGGLE_HEIGHT = 28f
        private const val CHECKBOX_SIZE = 22f
        private const val GOOGLE_STATUS_BOX_HEIGHT = 122f

        // Angle threshold range (degrees)
        const val MIN_ANGLE_THRESHOLD = 10f
        const val MAX_ANGLE_THRESHOLD = 45f
        const val DEFAULT_ANGLE_THRESHOLD = 25f
    }

    // View mode
    private enum class ViewMode { MAIN, HEADSUP, GOOGLE }
    private var currentView = ViewMode.MAIN

    var isVisible = false
        private set

    // Current brightness value (0.0 to 1.0)
    // 1.0 = full brightness (no dimming, system default)
    // 0.0 = minimum brightness (maximum dimming)
    var brightnessValue: Float = 1.0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            onBrightnessChanged?.invoke(field)
        }

    var adaptiveBrightnessEnabled: Boolean = true
        set(value) {
            field = value
            onAdaptiveBrightnessChanged?.invoke(field)
        }

    // Head-up hold time value (0.0 to 1.0, maps to 0.3s - 3s)
    var headUpTimeValue: Float = 0.26f  // Default ~1 second: (1000-300)/(3000-300) = 0.26
        set(value) {
            field = value.coerceIn(0f, 1f)
            onHeadUpTimeChanged?.invoke(field)
        }

    // Wake duration value (0.0 to 1.0, maps to 3s - 30s)
    var wakeDurationValue: Float = 0.26f  // Default ~10 seconds: (10000-3000)/(30000-3000) = 0.26
        set(value) {
            field = value.coerceIn(0f, 1f)
            onWakeDurationChanged?.invoke(field)
        }

    // Angle threshold value (0.0 to 1.0, maps to MIN_ANGLE_THRESHOLD - MAX_ANGLE_THRESHOLD degrees)
    var angleThresholdValue: Float = (DEFAULT_ANGLE_THRESHOLD - MIN_ANGLE_THRESHOLD) / (MAX_ANGLE_THRESHOLD - MIN_ANGLE_THRESHOLD)
        set(value) {
            field = value.coerceIn(0f, 1f)
            onAngleThresholdChanged?.invoke(angleThresholdValueToDegrees(field))
        }

    // Head-up enabled state
    var headsUpEnabled: Boolean = true
        set(value) {
            field = value
            onHeadsUpEnabledChanged?.invoke(field)
        }

    var googleAuthState: GoogleAuthState = GoogleAuthState.signedOut()
        set(value) {
            field = value
        }

    // Callbacks when sliders change
    var onBrightnessChanged: ((Float) -> Unit)? = null
    var onAdaptiveBrightnessChanged: ((Boolean) -> Unit)? = null
    var onHeadUpTimeChanged: ((Float) -> Unit)? = null
    var onWakeDurationChanged: ((Float) -> Unit)? = null
    var onAngleThresholdChanged: ((Float) -> Unit)? = null  // Callback with degrees
    var onHeadsUpEnabledChanged: ((Boolean) -> Unit)? = null
    var onConnectGoogle: (() -> Unit)? = null
    var onGrantCalendarAccess: (() -> Unit)? = null
    var onDisconnectGoogle: (() -> Unit)? = null
    var onRetryGoogleAuth: (() -> Unit)? = null
    var onDismissed: (() -> Unit)? = null

    // Backwards-compatible aliases for brightness
    var sliderValue: Float
        get() = brightnessValue
        set(value) { brightnessValue = value }

    var onValueChanged: ((Float) -> Unit)?
        get() = onBrightnessChanged
        set(value) { onBrightnessChanged = value }

    /**
     * Get head-up hold time in milliseconds.
     */
    fun getHeadUpTimeMs(): Long = headUpTimeValueToMs(headUpTimeValue)

    /**
     * Get wake duration in milliseconds.
     */
    fun getWakeDurationMs(): Long = wakeDurationValueToMs(wakeDurationValue)

    /**
     * Get angle threshold in degrees.
     */
    fun getAngleThresholdDegrees(): Float = angleThresholdValueToDegrees(angleThresholdValue)

    /**
     * Set head-up hold time from milliseconds.
     */
    fun setHeadUpTimeMs(ms: Long) {
        headUpTimeValue = msToHeadUpTimeValue(ms)
    }

    /**
     * Set wake duration from milliseconds.
     */
    fun setWakeDurationMs(ms: Long) {
        wakeDurationValue = msToWakeDurationValue(ms)
    }

    /**
     * Set angle threshold from degrees.
     */
    fun setAngleThresholdDegrees(degrees: Float) {
        angleThresholdValue = degreesToAngleThresholdValue(degrees)
    }

    // Expose conversion functions
    fun headUpTimeValueToMs(value: Float): Long {
        return (HeadUpWakeManager.MIN_HEAD_UP_HOLD_TIME_MS +
                value * (HeadUpWakeManager.MAX_HEAD_UP_HOLD_TIME_MS - HeadUpWakeManager.MIN_HEAD_UP_HOLD_TIME_MS)).toLong()
    }

    fun wakeDurationValueToMs(value: Float): Long {
        return (HeadUpWakeManager.MIN_WAKE_DURATION_MS +
                value * (HeadUpWakeManager.MAX_WAKE_DURATION_MS - HeadUpWakeManager.MIN_WAKE_DURATION_MS)).toLong()
    }

    fun angleThresholdValueToDegrees(value: Float): Float {
        return MIN_ANGLE_THRESHOLD + value * (MAX_ANGLE_THRESHOLD - MIN_ANGLE_THRESHOLD)
    }

    /**
     * Convert milliseconds to head-up time slider value (0-1).
     */
    fun msToHeadUpTimeValue(ms: Long): Float {
        return ((ms - HeadUpWakeManager.MIN_HEAD_UP_HOLD_TIME_MS).toFloat() /
                (HeadUpWakeManager.MAX_HEAD_UP_HOLD_TIME_MS - HeadUpWakeManager.MIN_HEAD_UP_HOLD_TIME_MS)).coerceIn(0f, 1f)
    }

    /**
     * Convert milliseconds to wake duration slider value (0-1).
     */
    fun msToWakeDurationValue(ms: Long): Float {
        return ((ms - HeadUpWakeManager.MIN_WAKE_DURATION_MS).toFloat() /
                (HeadUpWakeManager.MAX_WAKE_DURATION_MS - HeadUpWakeManager.MIN_WAKE_DURATION_MS)).coerceIn(0f, 1f)
    }

    /**
     * Convert degrees to angle threshold slider value (0-1).
     */
    fun degreesToAngleThresholdValue(degrees: Float): Float {
        return ((degrees - MIN_ANGLE_THRESHOLD) / (MAX_ANGLE_THRESHOLD - MIN_ANGLE_THRESHOLD)).coerceIn(0f, 1f)
    }

    // Layout - dynamic based on view mode
    private val menuRect = RectF()
    private val closeButtonRect = RectF()

    // Main menu elements
    private val brightnessTrackRect = RectF()
    private val adaptiveBrightnessRowRect = RectF()
    private val adaptiveBrightnessBoxRect = RectF()
    private val headsUpButtonRect = RectF()
    private val googleButtonRect = RectF()

    // Heads-up submenu elements
    private val backButtonRect = RectF()
    private val headUpTimeTrackRect = RectF()
    private val wakeDurationTrackRect = RectF()
    private val angleThresholdTrackRect = RectF()
    private val toggleRect = RectF()
    private val toggleKnobRect = RectF()

    // Google submenu elements
    private val googleStatusRect = RectF()
    private val googlePrimaryButtonRect = RectF()
    private val googleSecondaryButtonRect = RectF()

    // State - which slider is being dragged
    private enum class ActiveSlider { NONE, BRIGHTNESS, HEAD_UP_TIME, WAKE_DURATION, ANGLE_THRESHOLD }
    private var activeSlider = ActiveSlider.NONE

    // Paints
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EE222222") // Slightly transparent dark background
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#555555")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = SLIDER_TRACK_HEIGHT
        strokeCap = Paint.Cap.ROUND
    }

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val closeButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E53935") // Red
        style = Paint.Style.FILL
    }

    private val closeIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCCCCC")
        textSize = 16f
        textAlign = Paint.Align.LEFT
    }

    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f
        textAlign = Paint.Align.RIGHT
    }

    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4A90D9")  // Blue button
        style = Paint.Style.FILL
    }

    private val secondaryButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#616161")
        style = Paint.Style.FILL
    }

    private val buttonTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val googleStatusBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A2A2A")
        style = Paint.Style.FILL
    }

    private val googleStatusBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A5A5A")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val googleHeadingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#BDBDBD")
        textSize = 15f
        textAlign = Paint.Align.LEFT
    }

    private val googleStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 18f
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val googleDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#D7D7D7")
        textSize = 15f
        textAlign = Paint.Align.LEFT
    }

    private val backButtonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")  // Gray back button
        style = Paint.Style.FILL
    }

    private val toggleOnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4CAF50")  // Green when enabled
        style = Paint.Style.FILL
    }

    private val toggleOffPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#666666")  // Gray when disabled
        style = Paint.Style.FILL
    }

    private val toggleKnobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val checkboxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val checkmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val disabledOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#80000000")  // Semi-transparent black overlay
        style = Paint.Style.FILL
    }

    init {
        updateLayout()
    }

    /**
     * Update layout based on current view mode.
     */
    private fun updateLayout() {
        val menuHeight = when (currentView) {
            ViewMode.MAIN -> MAIN_MENU_HEIGHT
            ViewMode.HEADSUP -> HEADSUP_MENU_HEIGHT
            ViewMode.GOOGLE -> GOOGLE_MENU_HEIGHT
        }

        // Center the menu
        val left = (screenWidth - MENU_WIDTH) / 2f
        val top = (screenHeight - menuHeight) / 2f
        menuRect.set(left, top, left + MENU_WIDTH, top + menuHeight)

        // Close button at top-right inside the menu
        val closeLeft = menuRect.right - CLOSE_BUTTON_SIZE - CLOSE_BUTTON_MARGIN
        val closeTop = menuRect.top + CLOSE_BUTTON_MARGIN
        closeButtonRect.set(closeLeft, closeTop, closeLeft + CLOSE_BUTTON_SIZE, closeTop + CLOSE_BUTTON_SIZE)

        val trackLeft = menuRect.left + SLIDER_MARGIN_X
        val trackRight = menuRect.right - SLIDER_MARGIN_X

        if (currentView == ViewMode.MAIN) {
            // Main menu layout
            val firstSliderY = menuRect.top + 90f

            // Brightness slider
            brightnessTrackRect.set(trackLeft, firstSliderY - 10f, trackRight, firstSliderY + 10f)

            // Adaptive brightness toggle row below the brightness slider
            val adaptiveRowTop = firstSliderY + 24f
            adaptiveBrightnessRowRect.set(trackLeft, adaptiveRowTop, trackRight, adaptiveRowTop + 28f)
            adaptiveBrightnessBoxRect.set(
                trackLeft,
                adaptiveRowTop + 3f,
                trackLeft + CHECKBOX_SIZE,
                adaptiveRowTop + 3f + CHECKBOX_SIZE
            )

            // Heads-up button below brightness controls
            val buttonY = adaptiveBrightnessRowRect.bottom + 16f
            headsUpButtonRect.set(trackLeft, buttonY, trackRight, buttonY + BUTTON_HEIGHT)
            val googleButtonY = buttonY + BUTTON_HEIGHT + 12f
            googleButtonRect.set(trackLeft, googleButtonY, trackRight, googleButtonY + BUTTON_HEIGHT)
        } else if (currentView == ViewMode.HEADSUP) {
            // Heads-up submenu layout
            val firstSliderY = menuRect.top + 90f

            // Back button (smaller, at top left)
            val backButtonLeft = menuRect.left + CLOSE_BUTTON_MARGIN
            val backButtonTop = menuRect.top + CLOSE_BUTTON_MARGIN
            backButtonRect.set(backButtonLeft, backButtonTop, backButtonLeft + CLOSE_BUTTON_SIZE, backButtonTop + CLOSE_BUTTON_SIZE)

            // Toggle for enable/disable at the top (below title)
            val toggleY = firstSliderY - 30f
            val toggleLeft = trackRight - TOGGLE_WIDTH
            toggleRect.set(toggleLeft, toggleY, toggleLeft + TOGGLE_WIDTH, toggleY + TOGGLE_HEIGHT)

            // Head-up time slider (first slider in submenu)
            val headUpTimeY = firstSliderY + 40f
            headUpTimeTrackRect.set(trackLeft, headUpTimeY - 10f, trackRight, headUpTimeY + 10f)

            // Wake duration slider (second)
            val wakeDurationY = headUpTimeY + SLIDER_SPACING
            wakeDurationTrackRect.set(trackLeft, wakeDurationY - 10f, trackRight, wakeDurationY + 10f)

            // Angle threshold slider (third)
            val angleThresholdY = wakeDurationY + SLIDER_SPACING
            angleThresholdTrackRect.set(trackLeft, angleThresholdY - 10f, trackRight, angleThresholdY + 10f)
        } else {
            val statusTop = menuRect.top + 74f
            googleStatusRect.set(trackLeft, statusTop, trackRight, statusTop + GOOGLE_STATUS_BOX_HEIGHT)

            val primaryTop = googleStatusRect.bottom + 18f
            googlePrimaryButtonRect.set(trackLeft, primaryTop, trackRight, primaryTop + BUTTON_HEIGHT)

            val secondaryTop = googlePrimaryButtonRect.bottom + 12f
            googleSecondaryButtonRect.set(trackLeft, secondaryTop, trackRight, secondaryTop + BUTTON_HEIGHT)

            val backButtonLeft = menuRect.left + CLOSE_BUTTON_MARGIN
            val backButtonTop = menuRect.top + CLOSE_BUTTON_MARGIN
            backButtonRect.set(backButtonLeft, backButtonTop, backButtonLeft + CLOSE_BUTTON_SIZE, backButtonTop + CLOSE_BUTTON_SIZE)
        }
    }

    fun show() {
        isVisible = true
        currentView = ViewMode.MAIN
        updateLayout()
    }

    fun dismiss() {
        if (isVisible) {
            isVisible = false
            activeSlider = ActiveSlider.NONE
            currentView = ViewMode.MAIN
            updateLayout()
            onDismissed?.invoke()
        }
    }

    /**
     * Switch to the heads-up submenu.
     */
    private fun showHeadsUpSubmenu() {
        currentView = ViewMode.HEADSUP
        updateLayout()
    }

    private fun showGoogleSubmenu() {
        currentView = ViewMode.GOOGLE
        updateLayout()
    }

    /**
     * Switch back to the main menu.
     */
    private fun showMainMenu() {
        currentView = ViewMode.MAIN
        updateLayout()
    }

    fun draw(canvas: Canvas) {
        if (!isVisible) return

        // Draw background
        canvas.drawRoundRect(menuRect, CORNER_RADIUS, CORNER_RADIUS, backgroundPaint)
        canvas.drawRoundRect(menuRect, CORNER_RADIUS, CORNER_RADIUS, borderPaint)

        // Draw Close Button (always visible)
        canvas.drawRoundRect(closeButtonRect, 8f, 8f, closeButtonPaint)
        val cx = closeButtonRect.centerX()
        val cy = closeButtonRect.centerY()
        val offset = CLOSE_BUTTON_SIZE * 0.25f
        canvas.drawLine(cx - offset, cy - offset, cx + offset, cy + offset, closeIconPaint)
        canvas.drawLine(cx + offset, cy - offset, cx - offset, cy + offset, closeIconPaint)

        when (currentView) {
            ViewMode.MAIN -> drawMainMenu(canvas)
            ViewMode.HEADSUP -> drawHeadsUpSubmenu(canvas)
            ViewMode.GOOGLE -> drawGoogleSubmenu(canvas)
        }
    }

    /**
     * Draw the main settings menu.
     */
    private fun drawMainMenu(canvas: Canvas) {
        // Draw Title
        val titleY = menuRect.top + 45f
        canvas.drawText("Settings", menuRect.centerX(), titleY, titlePaint)

        val trackLeft = menuRect.left + SLIDER_MARGIN_X
        val trackRight = menuRect.right - SLIDER_MARGIN_X

        // Draw Brightness slider
        drawSlider(
            canvas,
            brightnessTrackRect,
            brightnessValue,
            if (adaptiveBrightnessEnabled) "Brightness Max" else "Brightness",
            "${(brightnessValue * 100).toInt()}%",
            trackLeft,
            trackRight
        )

        drawAdaptiveBrightnessToggle(canvas, trackRight)

        // Draw Heads-up button
        canvas.drawRoundRect(headsUpButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, buttonPaint)
        val buttonTextY = headsUpButtonRect.centerY() + 6f  // Adjust for text baseline
        canvas.drawText("Heads-up", headsUpButtonRect.centerX(), buttonTextY, buttonTextPaint)

        canvas.drawRoundRect(googleButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, buttonPaint)
        val googleButtonTextY = googleButtonRect.centerY() + 6f
        canvas.drawText("Google", googleButtonRect.centerX(), googleButtonTextY, buttonTextPaint)
    }

    private fun drawAdaptiveBrightnessToggle(canvas: Canvas, valueRight: Float) {
        canvas.drawRect(adaptiveBrightnessBoxRect, checkboxPaint)

        if (adaptiveBrightnessEnabled) {
            val left = adaptiveBrightnessBoxRect.left
            val top = adaptiveBrightnessBoxRect.top
            val width = adaptiveBrightnessBoxRect.width()
            val height = adaptiveBrightnessBoxRect.height()
            canvas.drawLine(
                left + width * 0.22f,
                top + height * 0.55f,
                left + width * 0.44f,
                top + height * 0.78f,
                checkmarkPaint
            )
            canvas.drawLine(
                left + width * 0.44f,
                top + height * 0.78f,
                left + width * 0.82f,
                top + height * 0.26f,
                checkmarkPaint
            )
        }

        val labelY = adaptiveBrightnessRowRect.centerY() + 6f
        canvas.drawText("Adaptive brightness", adaptiveBrightnessBoxRect.right + 12f, labelY, labelPaint)
        canvas.drawText(if (adaptiveBrightnessEnabled) "On" else "Off", valueRight, labelY, valuePaint)
    }

    /**
     * Draw the heads-up settings submenu.
     */
    private fun drawHeadsUpSubmenu(canvas: Canvas) {
        // Draw Title
        val titleY = menuRect.top + 45f
        canvas.drawText("Heads-up", menuRect.centerX(), titleY, titlePaint)

        // Draw Back Button (arrow pointing left)
        canvas.drawRoundRect(backButtonRect, 8f, 8f, backButtonPaint)
        val bcx = backButtonRect.centerX()
        val bcy = backButtonRect.centerY()
        val arrowSize = CLOSE_BUTTON_SIZE * 0.25f
        // Draw left arrow
        canvas.drawLine(bcx + arrowSize * 0.5f, bcy - arrowSize, bcx - arrowSize * 0.5f, bcy, closeIconPaint)
        canvas.drawLine(bcx - arrowSize * 0.5f, bcy, bcx + arrowSize * 0.5f, bcy + arrowSize, closeIconPaint)

        val trackLeft = menuRect.left + SLIDER_MARGIN_X
        val trackRight = menuRect.right - SLIDER_MARGIN_X

        // Draw Enable toggle with label
        val toggleLabelY = toggleRect.centerY() + 5f
        canvas.drawText("Enabled", trackLeft, toggleLabelY, labelPaint)
        drawToggle(canvas)

        // Draw sliders (with disabled overlay if toggle is off)
        val slidersEnabled = headsUpEnabled

        // Head-up Time slider
        val headUpTimeMs = headUpTimeValueToMs(headUpTimeValue)
        drawSlider(
            canvas,
            headUpTimeTrackRect,
            headUpTimeValue,
            "Hold Time",
            "${String.format("%.1f", headUpTimeMs / 1000f)}s",
            trackLeft,
            trackRight,
            enabled = slidersEnabled
        )

        // Wake Duration slider
        val wakeDurationMs = wakeDurationValueToMs(wakeDurationValue)
        drawSlider(
            canvas,
            wakeDurationTrackRect,
            wakeDurationValue,
            "Wake Duration",
            "${wakeDurationMs / 1000}s",
            trackLeft,
            trackRight,
            enabled = slidersEnabled
        )

        // Angle Threshold slider
        val angleDegrees = angleThresholdValueToDegrees(angleThresholdValue)
        drawSlider(
            canvas,
            angleThresholdTrackRect,
            angleThresholdValue,
            "Trigger Angle",
            "${angleDegrees.toInt()}°",
            trackLeft,
            trackRight,
            enabled = slidersEnabled
        )
    }

    private fun drawGoogleSubmenu(canvas: Canvas) {
        val titleY = menuRect.top + 45f
        canvas.drawText("Google", menuRect.centerX(), titleY, titlePaint)

        drawBackButton(canvas)

        canvas.drawRoundRect(googleStatusRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, googleStatusBackgroundPaint)
        canvas.drawRoundRect(googleStatusRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, googleStatusBorderPaint)

        val textLeft = googleStatusRect.left + 14f
        val statusLabelY = googleStatusRect.top + 24f
        canvas.drawText("Status", textLeft, statusLabelY, googleHeadingPaint)
        canvas.drawText(statusText(), textLeft, statusLabelY + 24f, googleStatusPaint)

        val account = googleAuthState.account
        if (account != null) {
            val accountY = statusLabelY + 54f
            canvas.drawText("Account", textLeft, accountY, googleHeadingPaint)
            canvas.drawText(
                fitText(account.primaryLabel, googleStatusPaint, googleStatusRect.width() - 28f),
                textLeft,
                accountY + 22f,
                googleStatusPaint
            )
        } else {
            val hintY = statusLabelY + 58f
            canvas.drawText(
                fitText(detailText(), googleDetailPaint, googleStatusRect.width() - 28f),
                textLeft,
                hintY,
                googleDetailPaint
            )
        }

        googleAuthState.userCode?.takeIf { it.isNotBlank() }?.let { userCode ->
            val codeY = googleStatusRect.bottom - 18f
            canvas.drawText("Code", textLeft, codeY - 18f, googleHeadingPaint)
            canvas.drawText(
                fitText(userCode, googleStatusPaint, googleStatusRect.width() - 28f),
                textLeft,
                codeY,
                googleStatusPaint
            )
        }

        if (account != null && googleAuthState.detail != null) {
            val detailY = googleStatusRect.bottom + 18f
            canvas.drawText(
                fitText(googleAuthState.detail.orEmpty(), googleDetailPaint, googleStatusRect.width()),
                googleStatusRect.left,
                detailY,
                googleDetailPaint
            )
        }

        googlePrimaryActionLabel()?.let { label ->
            canvas.drawRoundRect(googlePrimaryButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, buttonPaint)
            canvas.drawText(label, googlePrimaryButtonRect.centerX(), googlePrimaryButtonRect.centerY() + 6f, buttonTextPaint)
        }

        googleSecondaryActionLabel()?.let { label ->
            canvas.drawRoundRect(googleSecondaryButtonRect, BUTTON_CORNER_RADIUS, BUTTON_CORNER_RADIUS, secondaryButtonPaint)
            canvas.drawText(label, googleSecondaryButtonRect.centerX(), googleSecondaryButtonRect.centerY() + 6f, buttonTextPaint)
        }
    }

    private fun drawBackButton(canvas: Canvas) {
        canvas.drawRoundRect(backButtonRect, 8f, 8f, backButtonPaint)
        val bcx = backButtonRect.centerX()
        val bcy = backButtonRect.centerY()
        val arrowSize = CLOSE_BUTTON_SIZE * 0.25f
        canvas.drawLine(bcx + arrowSize * 0.5f, bcy - arrowSize, bcx - arrowSize * 0.5f, bcy, closeIconPaint)
        canvas.drawLine(bcx - arrowSize * 0.5f, bcy, bcx + arrowSize * 0.5f, bcy + arrowSize, closeIconPaint)
    }

    /**
     * Draw the enable/disable toggle.
     */
    private fun drawToggle(canvas: Canvas) {
        val togglePaint = if (headsUpEnabled) toggleOnPaint else toggleOffPaint
        canvas.drawRoundRect(toggleRect, TOGGLE_HEIGHT / 2f, TOGGLE_HEIGHT / 2f, togglePaint)

        // Draw knob
        val knobRadius = (TOGGLE_HEIGHT - 4f) / 2f
        val knobX = if (headsUpEnabled) {
            toggleRect.right - knobRadius - 2f
        } else {
            toggleRect.left + knobRadius + 2f
        }
        val knobY = toggleRect.centerY()
        canvas.drawCircle(knobX, knobY, knobRadius, toggleKnobPaint)
    }

    /**
     * Draw a single slider with label and value.
     */
    private fun drawSlider(
        canvas: Canvas,
        trackRect: RectF,
        value: Float,
        label: String,
        valueText: String,
        trackLeft: Float,
        trackRight: Float,
        enabled: Boolean = true
    ) {
        val trackY = trackRect.centerY()

        // Use dimmed colors if disabled
        val labelColor = if (enabled) Color.parseColor("#CCCCCC") else Color.parseColor("#666666")
        val valueColor = if (enabled) Color.WHITE else Color.parseColor("#888888")
        val trackColor = if (enabled) Color.GRAY else Color.parseColor("#444444")
        val thumbColor = if (enabled) Color.WHITE else Color.parseColor("#888888")

        labelPaint.color = labelColor
        valuePaint.color = valueColor
        trackPaint.color = trackColor
        thumbPaint.color = thumbColor

        // Draw label
        canvas.drawText(label, trackLeft, trackY + LABEL_OFFSET_Y, labelPaint)

        // Draw value
        canvas.drawText(valueText, trackRight, trackY + LABEL_OFFSET_Y, valuePaint)

        // Draw track
        canvas.drawLine(trackLeft, trackY, trackRight, trackY, trackPaint)

        // Draw thumb
        val thumbX = trackLeft + (trackRight - trackLeft) * value
        canvas.drawCircle(thumbX, trackY, THUMB_RADIUS, thumbPaint)

        // Reset colors
        labelPaint.color = Color.parseColor("#CCCCCC")
        valuePaint.color = Color.WHITE
        trackPaint.color = Color.GRAY
        thumbPaint.color = Color.WHITE
    }

    /**
     * Handle touch down event.
     * Returns true if the event was consumed by this menu.
     */
    fun onDown(x: Float, y: Float): Boolean {
        if (!isVisible) return false

        // Check close button
        if (closeButtonRect.contains(x, y)) {
            return true
        }

        // Check view-specific elements
        if (currentView == ViewMode.MAIN) {
            // Check heads-up button
            if (headsUpButtonRect.contains(x, y) || googleButtonRect.contains(x, y)) {
                return true
            }

            if (adaptiveBrightnessRowRect.contains(x, y)) {
                return true
            }

            // Check brightness slider
            activeSlider = getSliderAt(x, y)
            if (activeSlider != ActiveSlider.NONE) {
                updateSliderFromTouch(x, activeSlider)
                return true
            }
        } else if (currentView == ViewMode.HEADSUP) {
            // Heads-up submenu
            // Check back button
            if (backButtonRect.contains(x, y)) {
                return true
            }

            // Check toggle
            if (toggleRect.contains(x, y)) {
                return true
            }

            // Check sliders (only if enabled)
            if (headsUpEnabled) {
                activeSlider = getSliderAt(x, y)
                if (activeSlider != ActiveSlider.NONE) {
                    updateSliderFromTouch(x, activeSlider)
                    return true
                }
            }
        } else {
            if (backButtonRect.contains(x, y)) {
                return true
            }
            if (googlePrimaryActionLabel() != null && googlePrimaryButtonRect.contains(x, y)) {
                return true
            }
            if (googleSecondaryActionLabel() != null && googleSecondaryButtonRect.contains(x, y)) {
                return true
            }
        }

        // Check if inside menu background (consume event but do nothing)
        if (menuRect.contains(x, y)) {
            return true
        }

        return false
    }

    /**
     * Handle touch move event.
     */
    fun onMove(x: Float, y: Float): Boolean {
        if (!isVisible) return false

        if (activeSlider != ActiveSlider.NONE) {
            updateSliderFromTouch(x, activeSlider)
            return true
        }

        return menuRect.contains(x, y)
    }

    /**
     * Handle touch up event.
     */
    fun onUp(): Boolean {
        if (!isVisible) return false

        if (activeSlider != ActiveSlider.NONE) {
            activeSlider = ActiveSlider.NONE
            return true
        }

        return false
    }

    /**
     * Handle tap event.
     * Returns true if consumed.
     */
    fun onTap(x: Float, y: Float): Boolean {
        if (!isVisible) return false

        if (closeButtonRect.contains(x, y)) {
            dismiss()
            return true
        }

        // Check view-specific elements
        if (currentView == ViewMode.MAIN) {
            // Check heads-up button
            if (headsUpButtonRect.contains(x, y)) {
                showHeadsUpSubmenu()
                return true
            }
            if (googleButtonRect.contains(x, y)) {
                showGoogleSubmenu()
                return true
            }
            if (adaptiveBrightnessRowRect.contains(x, y)) {
                adaptiveBrightnessEnabled = !adaptiveBrightnessEnabled
                return true
            }

            // Check brightness slider (jump to position)
            val tappedSlider = getSliderAt(x, y)
            if (tappedSlider != ActiveSlider.NONE) {
                updateSliderFromTouch(x, tappedSlider)
                return true
            }
        } else if (currentView == ViewMode.HEADSUP) {
            // Heads-up submenu
            // Check back button
            if (backButtonRect.contains(x, y)) {
                showMainMenu()
                return true
            }

            // Check toggle
            if (toggleRect.contains(x, y)) {
                headsUpEnabled = !headsUpEnabled
                return true
            }

            // Check sliders (only if enabled)
            if (headsUpEnabled) {
                val tappedSlider = getSliderAt(x, y)
                if (tappedSlider != ActiveSlider.NONE) {
                    updateSliderFromTouch(x, tappedSlider)
                    return true
                }
            }
        } else {
            if (backButtonRect.contains(x, y)) {
                showMainMenu()
                return true
            }

            if (googlePrimaryActionLabel() != null && googlePrimaryButtonRect.contains(x, y)) {
                when (googlePrimaryAction()) {
                    GoogleAction.CONNECT -> onConnectGoogle?.invoke()
                    GoogleAction.CONTINUE_PHONE -> onRetryGoogleAuth?.invoke()
                    GoogleAction.GRANT -> onGrantCalendarAccess?.invoke()
                    GoogleAction.RETRY -> onRetryGoogleAuth?.invoke()
                    GoogleAction.DISCONNECT,
                    GoogleAction.NONE -> Unit
                }
                return true
            }

            if (googleSecondaryActionLabel() != null && googleSecondaryButtonRect.contains(x, y)) {
                when (googleSecondaryAction()) {
                    GoogleAction.CONNECT -> onConnectGoogle?.invoke()
                    GoogleAction.CONTINUE_PHONE -> onRetryGoogleAuth?.invoke()
                    GoogleAction.GRANT -> onGrantCalendarAccess?.invoke()
                    GoogleAction.RETRY -> onRetryGoogleAuth?.invoke()
                    GoogleAction.DISCONNECT -> onDisconnectGoogle?.invoke()
                    GoogleAction.NONE -> Unit
                }
                return true
            }
        }

        if (menuRect.contains(x, y)) {
            return true // Consume taps inside menu
        }

        return false // Tap outside -> allow caller to handle (likely dismiss)
    }

    /**
     * Determine which slider is at the given coordinates.
     */
    private fun getSliderAt(x: Float, y: Float): ActiveSlider {
        if (currentView == ViewMode.MAIN) {
            // Main menu only has brightness slider
            val expandedBrightness = expandRect(brightnessTrackRect, 20f)
            if (expandedBrightness.contains(x, y)) return ActiveSlider.BRIGHTNESS
        } else if (currentView == ViewMode.HEADSUP) {
            // Heads-up submenu has three sliders
            val expandedHeadUpTime = expandRect(headUpTimeTrackRect, 20f)
            if (expandedHeadUpTime.contains(x, y)) return ActiveSlider.HEAD_UP_TIME

            val expandedWakeDuration = expandRect(wakeDurationTrackRect, 20f)
            if (expandedWakeDuration.contains(x, y)) return ActiveSlider.WAKE_DURATION

            val expandedAngleThreshold = expandRect(angleThresholdTrackRect, 20f)
            if (expandedAngleThreshold.contains(x, y)) return ActiveSlider.ANGLE_THRESHOLD
        }

        return ActiveSlider.NONE
    }

    /**
     * Create an expanded rectangle for touch detection.
     */
    private fun expandRect(rect: RectF, margin: Float): RectF {
        return RectF(
            rect.left - margin,
            rect.top - margin,
            rect.right + margin,
            rect.bottom + margin
        )
    }

    /**
     * Update the appropriate slider value based on touch position.
     */
    private fun updateSliderFromTouch(touchX: Float, slider: ActiveSlider) {
        val trackLeft = menuRect.left + SLIDER_MARGIN_X
        val trackRight = menuRect.right - SLIDER_MARGIN_X
        val trackWidth = trackRight - trackLeft

        if (trackWidth > 0) {
            val relativeX = touchX - trackLeft
            val newValue = (relativeX / trackWidth).coerceIn(0f, 1f)

            when (slider) {
                ActiveSlider.BRIGHTNESS -> brightnessValue = newValue
                ActiveSlider.HEAD_UP_TIME -> headUpTimeValue = newValue
                ActiveSlider.WAKE_DURATION -> wakeDurationValue = newValue
                ActiveSlider.ANGLE_THRESHOLD -> angleThresholdValue = newValue
                ActiveSlider.NONE -> { /* Do nothing */ }
            }
        }
    }

    /**
     * Hover updates (optional).
     * WidgetContainer calls this with either real coordinates or (-inf, -inf) when cursor hides.
     * Currently a no-op, but kept for API symmetry with other menus/widgets.
     */
    fun updateHover(x: Float, y: Float) {
        // No hover visuals for now.
        // In the future, you can track hovered states for buttons/thumb etc.
        // Example: closeHovered = closeButtonRect.contains(x, y)
    }

    fun containsPoint(x: Float, y: Float): Boolean {
        return isVisible && menuRect.contains(x, y)
    }

    private enum class GoogleAction {
        NONE,
        CONNECT,
        CONTINUE_PHONE,
        GRANT,
        RETRY,
        DISCONNECT
    }

    private fun googlePrimaryAction(): GoogleAction {
        return when (googleAuthState.status) {
            GoogleAuthState.Status.SIGNED_OUT -> GoogleAction.CONNECT
            GoogleAuthState.Status.PHONE_FALLBACK_REQUIRED -> GoogleAction.CONTINUE_PHONE
            GoogleAuthState.Status.SIGNED_IN,
            GoogleAuthState.Status.CALENDAR_DENIED -> GoogleAction.GRANT
            GoogleAuthState.Status.GOOGLE_UNAVAILABLE,
            GoogleAuthState.Status.NETWORK_ERROR -> GoogleAction.RETRY
            GoogleAuthState.Status.SIGNING_IN,
            GoogleAuthState.Status.AWAITING_DEVICE_VERIFICATION,
            GoogleAuthState.Status.CALENDAR_AUTHORIZED -> GoogleAction.NONE
        }
    }

    private fun googleSecondaryAction(): GoogleAction {
        return if (googleAuthState.account != null) GoogleAction.DISCONNECT else GoogleAction.NONE
    }

    private fun googlePrimaryActionLabel(): String? {
        return when (googlePrimaryAction()) {
            GoogleAction.CONNECT -> "Connect"
            GoogleAction.CONTINUE_PHONE -> "Continue on Phone"
            GoogleAction.GRANT -> "Grant Calendar"
            GoogleAction.RETRY -> "Retry"
            GoogleAction.NONE,
            GoogleAction.DISCONNECT -> null
        }
    }

    private fun googleSecondaryActionLabel(): String? {
        return when (googleSecondaryAction()) {
            GoogleAction.DISCONNECT -> "Disconnect"
            else -> null
        }
    }

    private fun statusText(): String {
        return when (googleAuthState.status) {
            GoogleAuthState.Status.SIGNED_OUT -> "Signed out"
            GoogleAuthState.Status.SIGNING_IN -> "Signing in"
            GoogleAuthState.Status.AWAITING_DEVICE_VERIFICATION -> "Approve on phone"
            GoogleAuthState.Status.SIGNED_IN -> "Signed in"
            GoogleAuthState.Status.CALENDAR_AUTHORIZED ->
                if (googleAuthState.authMode == GoogleAuthState.AuthMode.PHONE_FALLBACK) {
                    "Calendar via phone"
                } else {
                    "Calendar access granted"
                }
            GoogleAuthState.Status.CALENDAR_DENIED -> "Calendar access denied"
            GoogleAuthState.Status.PHONE_FALLBACK_REQUIRED -> "Phone sign-in required"
            GoogleAuthState.Status.GOOGLE_UNAVAILABLE -> "Google unavailable"
            GoogleAuthState.Status.NETWORK_ERROR -> "Network error"
        }
    }

    private fun detailText(): String {
        return googleAuthState.detail ?: when (googleAuthState.status) {
            GoogleAuthState.Status.SIGNED_OUT -> "Connect a Google account for personalized widgets."
            GoogleAuthState.Status.SIGNING_IN -> "Preparing Google authorization."
            GoogleAuthState.Status.AWAITING_DEVICE_VERIFICATION ->
                buildString {
                    append("Visit ")
                    append(googleAuthState.verificationUri ?: "google.com/device")
                    googleAuthState.userCode?.takeIf { it.isNotBlank() }?.let {
                        append(" and enter ")
                        append(it)
                    }
                }
            GoogleAuthState.Status.SIGNED_IN -> "Calendar access is not granted yet."
            GoogleAuthState.Status.CALENDAR_AUTHORIZED ->
                if (googleAuthState.authMode == GoogleAuthState.AuthMode.PHONE_FALLBACK) {
                    "Calendar data is being synced from the phone."
                } else {
                    "Calendar access is ready."
                }
            GoogleAuthState.Status.CALENDAR_DENIED -> "Grant Calendar access to enable reminders."
            GoogleAuthState.Status.PHONE_FALLBACK_REQUIRED -> "Open the phone companion app to continue Google authorization."
            GoogleAuthState.Status.GOOGLE_UNAVAILABLE -> "Google sign-in is unavailable. Check the Google client ID setup."
            GoogleAuthState.Status.NETWORK_ERROR -> "Check the network connection and retry."
        }
    }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) {
            return text
        }

        var trimmed = text
        while (trimmed.length > 1 && paint.measureText("$trimmed...") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed..."
    }
}
