package com.everyday.everyday_glasses.widgets.text_editor

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.util.Log
import androidx.core.graphics.withSave
import androidx.core.text.HtmlCompat
import androidx.core.text.toHtml
import com.everyday.everyday_glasses.interaction.user_input.TextEditAction
import com.everyday.everyday_glasses.interaction.user_input.TextZone
import com.everyday.everyday_glasses.interaction.user_input.TextZoneKind
import com.everyday.everyday_glasses.interaction.user_input.TextZoneLayout
import com.everyday.everyday_glasses.persistence.DashboardPersistence
import com.everyday.everyday_glasses.runtime.DashboardServices
import com.everyday.everyday_glasses.runtime.WidgetLifecycleRegistration
import com.everyday.everyday_glasses.runtime.WidgetTypeId
import com.everyday.everyday_glasses.ui.components.GlassesKeyboardView
import com.everyday.everyday_glasses.ui.components.KeyboardOverlayController
import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets._common.WidgetInteractionState
import com.everyday.everyday_glasses.widgets._common.WidgetTheme

/**
 * A dynamic text box widget that can be moved, resized, deleted, and toggled fullscreen.
 * Its content is a rich text area ([textZone]): while focused, typing, caret, selection,
 * clipboard and formatting are handled by the shared text editing controller. The widget keeps
 * the committed content and draws it when unfocused.
 *
 * Extends BaseWidget for common functionality (pin, fullscreen, move).
 *
 * Additional features:
 * - Resize handle (bottom-right corner)
 * - Delete button (top-right, next to fullscreen)
 * - Scrollable content with scrollbar
 * - Glasses keyboard overlay
 */
class TextBoxWidget(
    x: Float,
    y: Float,
    widgetWidth: Float,
    widgetHeight: Float
) : BaseWidget(WidgetTypeId.TEXT, x, y, widgetWidth, widgetHeight) {

    companion object {
        private const val TAG = "TextBoxWidget"

        // Scrolling
        private const val SCROLL_SENSITIVITY = 0.5f
        private const val SCROLLBAR_WIDTH_RATIO = 0.10f
    }

    // ==================== Extended State ====================

    /**
     * Extended states for TextBoxWidget.
     * Includes base states plus resize and editing.
     */
    enum class State {
        IDLE, HOVER_CONTENT, HOVER_BORDER, EDITING, MOVING, RESIZING
    }

    /**
     * Extended hit areas for TextBoxWidget (close button handled via BaseHitArea).
     */
    enum class HitArea {
        NONE, CONTENT, BORDER, FULLSCREEN_BUTTON, PIN_BUTTON, MINIMIZE_BUTTON, SCROLLBAR, RESIZE_HANDLE,
        KEYBOARD_BUTTON, KEYBOARD_KEY
    }

    // TextBoxWidget uses its own state that maps to base state
    var state = State.IDLE

    // Label for minimized state
    override val minimizeLabel: String = "T"

    // ==================== Text Content ====================

    private var richText = SpannableStringBuilder()

    val text: String
        get() = richText.toString()

    /** Font size, wrap, columns and scroll of the text area, shared with the text editing controller. */
    private val textLayout = TextZoneLayout(textSize = 28f)

    val textFontSize: Float
        get() = textLayout.textSize

    val isTextWrap: Boolean
        get() = textLayout.wrap

    fun setColumns(count: Int) {
        textLayout.columns = count
        notifyTransientChange() // Trigger redraw
    }

    //expose the count of columns
    fun getColumnCount(): Int = textLayout.columns

    private val textBounds = RectF()

    /** The rich text area, edited through the shared text editing controller. */
    val textZone: TextZone = object : TextZone {
        override val kind = TextZoneKind.AREA

        // The content area above the glasses keyboard, when it is shown.
        override val bounds: RectF
            get() = textBounds.apply {
                set(
                    contentBounds.left,
                    contentBounds.top,
                    contentBounds.right,
                    contentBounds.bottom - keyboardOverlay.getHeight()
                )
            }

        override val layout: TextZoneLayout get() = textLayout

        override fun content(): CharSequence = richText

        override fun persistentState(): Any =
            listOf(getHtmlContent(), textFontSize, isTextWrap, getColumnCount())

        override fun onTextEditAction(action: TextEditAction) {
            if (action is TextEditAction.Changed) richText = SpannableStringBuilder(action.content)
            notifyTransientChange()
        }
    }

    fun isFocused(): Boolean = textZoneHost?.isFocused(textZone) == true

    // ==================== Additional Paints ====================

    private val focusedBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = BORDER_WIDTH
        color = WidgetTheme.ColorValue.focusedBorder
    }

    private val hintPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        textSize = 28f
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    // Close button paints are inherited from BaseWidget

    private val scrollbarTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.ColorValue.scrollbarTrack
    }

    private val scrollbarThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.ColorValue.scrollbarThumb
    }

    // ==================== Additional Bounds ====================
    // (closeButtonBounds is inherited from BaseWidget)

    // Use lazy initialization to avoid NullPointerException when called from BaseWidget constructor
    private val scrollbarBounds by lazy { RectF() }

    // ==================== Keyboard ====================

    // Keys go to the text editing controller, which edits this widget's text area.
    private val keyboardOverlay = KeyboardOverlayController().apply {
        onKeyPressed = { key ->
            textZoneHost?.let { host ->
                if (!host.isFocused(textZone)) host.focus(textZone)
                host.onKey(key)
            }
        }
    }
    private val keyboardButtonBounds: RectF
        get() = keyboardOverlay.buttonBounds
    val isKeyboardVisible: Boolean
        get() = keyboardOverlay.isVisible

    init {
        // Ensure bounds are set at least once correctly
        updateBaseBounds()
    }

    /**
     * Override base bounds update to sync our local bounds.
     * BaseWidget calls this whenever x, y, width, or height changes (and in its init).
     * Button positions (close, fullscreen, minimize, pin) are now handled by BaseWidget.
     */
    override fun updateBaseBounds() {
        super.updateBaseBounds()

        // Scrollbar on right side of content
        val scrollbarWidth = (contentBounds.width() * SCROLLBAR_WIDTH_RATIO).coerceAtLeast(12f)
        scrollbarBounds.set(
            contentBounds.right - scrollbarWidth,
            contentBounds.top,
            contentBounds.right,
            contentBounds.bottom
        )

        // Keyboard button at the bottom center edge
        keyboardOverlay.updateButtonPosition(widgetBounds.centerX(), widgetBounds.bottom)

        // Update keyboard dimensions if visible
        updateKeyboardLayout()
    }

    /**
     * Update keyboard layout when widget size changes or keyboard is toggled.
     */
    private fun updateKeyboardLayout() {
        keyboardOverlay.updateLayout(
            areaWidth = contentBounds.width(),
            areaHeight = contentBounds.height(),
            areaLeft = contentBounds.left,
            areaTop = contentBounds.top
        )
    }

    /**
     * Toggle keyboard visibility.
     */
    fun toggleKeyboard() {
        val nowVisible = keyboardOverlay.toggle()
        if (nowVisible) {
            updateKeyboardLayout()
        }
        notifyTransientChange()
    }


    /**
     * Hide keyboard.
     */
    fun hideKeyboard() {
        if (keyboardOverlay.hide()) {
            notifyTransientChange()
        }
    }

    /**
     * Get the keyboard for external hover/tap handling.
     */
    fun getKeyboard(): GlassesKeyboardView? = keyboardOverlay.getKeyboard()
    override val visibleKeyboard: GlassesKeyboardView? get() = if (isKeyboardVisible) getKeyboard() else null

    // ==================== State Management ====================

    override fun isHovering(): Boolean {
        return state == State.HOVER_CONTENT ||
               state == State.HOVER_BORDER ||
               state == State.MOVING ||
               baseState == BaseState.HOVER_RESIZE ||
               baseState == BaseState.RESIZING
    }

    // Use unified border hover from BaseWidget - no override needed

    private fun updateBaseStateFromState() {
        baseState = when (state) {
            State.IDLE -> BaseState.IDLE
            State.HOVER_CONTENT -> BaseState.HOVER_CONTENT
            State.HOVER_BORDER -> BaseState.HOVER_BORDER
            State.MOVING -> BaseState.MOVING
            else -> BaseState.IDLE
        }
    }

    // ==================== Hit Testing ====================

    /** Only the border moves this widget and only the resize handle resizes it. */
    override fun dragHitTest(px: Float, py: Float): DragHit = when (hitTest(px, py)) {
        HitArea.BORDER -> DragHit.DRAG
        HitArea.RESIZE_HANDLE -> DragHit.RESIZE
        else -> DragHit.NONE
    }

    override fun isBorderHit(px: Float, py: Float): Boolean = hitTest(px, py) == HitArea.BORDER

    fun hitTest(px: Float, py: Float): HitArea {
        // Check base widget buttons first (close button is handled by baseHitTest)
        val baseResult = baseHitTest(px, py)
        when (baseResult) {
            BaseHitArea.CLOSE_BUTTON -> return HitArea.NONE  // Handled via onCloseRequested callback
            BaseHitArea.FULLSCREEN_BUTTON -> return HitArea.FULLSCREEN_BUTTON
            BaseHitArea.MINIMIZE_BUTTON -> return HitArea.MINIMIZE_BUTTON
            BaseHitArea.PIN_BUTTON -> return HitArea.PIN_BUTTON
            BaseHitArea.RESIZE_HANDLE -> return HitArea.RESIZE_HANDLE
            else -> {}
        }

        // Check keyboard key (if keyboard is visible)
        if (keyboardOverlay.containsPoint(px, py)) {
            return HitArea.KEYBOARD_KEY
        }

        // Check keyboard button (at bottom edge)
        if (keyboardButtonBounds.contains(px, py)) {
            return HitArea.KEYBOARD_BUTTON
        }

        // Check scrollbar
        if (textLayout.maxScrollOffset > 0 && scrollbarBounds.contains(px, py)) {
            return HitArea.SCROLLBAR
        }

        // Check content (but exclude keyboard area if visible)
        if (contentBounds.contains(px, py)) {
            // If keyboard is visible, check if point is above keyboard
            if (isKeyboardVisible) {
                val keyboardBounds = keyboardOverlay.getBounds() ?: return HitArea.CONTENT
                if (py < keyboardBounds.top) {
                    return HitArea.CONTENT
                }
                // Otherwise it's a keyboard key area
                return HitArea.KEYBOARD_KEY
            }
            return HitArea.CONTENT
        }

        // Check border
        val expandedBounds = RectF(
            widgetBounds.left - BORDER_HIT_AREA,
            widgetBounds.top - BORDER_HIT_AREA,
            widgetBounds.right + BORDER_HIT_AREA,
            widgetBounds.bottom + BORDER_HIT_AREA
        )
        if (expandedBounds.contains(px, py)) {
            return HitArea.BORDER
        }

        return HitArea.NONE
    }

    override fun isOverScrollbar(x: Float, y: Float): Boolean {
        return textLayout.maxScrollOffset > 0 && scrollbarBounds.contains(x, y)
    }

    override fun containsPoint(px: Float, py: Float): Boolean {
        // Check keyboard button (extends below widget)
        if (keyboardButtonBounds.contains(px, py)) return true
        // All other button bounds are now handled by BaseWidget
        return super.containsPoint(px, py)
    }

    // ==================== Hover Update ====================

    override fun updateHover(px: Float, py: Float) {
        if (state == State.MOVING) {
            return
        }

        // Update keyboard hover state
        keyboardOverlay.updateHover(px, py)

        // Use unified hover state from BaseWidget
        val baseResult = updateHoverState(px, py)

        val newState = WidgetInteractionState(baseResult).toChromeLocal(
            idle = State.IDLE,
            content = State.HOVER_CONTENT,
            border = State.HOVER_BORDER,
            moving = State.MOVING
        )

        if (newState != state) {
            state = newState
            updateBaseStateFromState()
        }
    }

    // ==================== Tap Handling ====================

    override fun onContentTap(px: Float, py: Float): Boolean = when (hitTest(px, py)) {
        HitArea.KEYBOARD_BUTTON -> { toggleKeyboard(); true }
        // Keys are taken by the container's keyboard pass before any tap is routed here.
        HitArea.KEYBOARD_KEY -> true
        else -> {
            val onText = baseHitTest(px, py) == BaseHitArea.CONTENT
            if (onText) textZoneHost?.tap(textZone, px, py)
            onText
        }
    }

    // ==================== Scroll ====================

    override fun onScroll(dy: Float) {
        if (state == State.HOVER_CONTENT || state == State.EDITING || isFocused()) {
            textLayout.scrollBy(dy * SCROLL_SENSITIVITY)
        }
    }

    // ==================== Drag / Resize ====================

    override fun startDrag(isResize: Boolean) {
        super.startDrag(isResize)
        state = if (isResize) State.RESIZING else State.MOVING
        notifyTransientChange()
    }

    override fun onDrag(dx: Float, dy: Float, screenWidth: Float, screenHeight: Float) {
        if (isFullscreen) return

        if (state == State.MOVING) {
            super.onDrag(dx, dy, screenWidth, screenHeight)
        } else if (baseState == BaseState.HOVER_RESIZE || baseState == BaseState.RESIZING) {
            super.onDrag(dx, dy, screenWidth, screenHeight)
        }
    }

    override fun onDragEnd() {
        super.onDragEnd()
        if (state == State.MOVING) {
            state = State.IDLE
            updateBaseStateFromState()
            notifyTransientChange()
        }
    }

    // ==================== Text Content Methods ====================

    fun setTextContent(newText: String) {
        richText = SpannableStringBuilder(newText)
        textLayout.invalidate()
    }

    /**
     * Set assistant-authored content directly. This intentionally bypasses the text editing
     * controller so a factual answer is not treated as simulated user typing and does not
     * depend on focus.
     */
    fun setAssistantContent(newText: String) {
        richText = SpannableStringBuilder(newText)
        textLayout.resetScroll()
        textLayout.invalidate()
        notifyTransientChange()
    }

    fun setHtmlContent(html: String) {
        try {
            val spanned = HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY)
            richText = SpannableStringBuilder(spanned)
            textLayout.invalidate()
            Log.d(TAG, "Restored HTML content: ${richText.length} chars")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing HTML, falling back to plain text", e)
            setTextContent(html)
        }
    }

    fun getHtmlContent(): String {
        return richText.toHtml()
    }

    fun setFontSize(size: Float) {
        textLayout.textSize = size.coerceIn(12f, 72f)
        hintPaint.textSize = textLayout.textSize
    }

    fun setTextWrap(wrap: Boolean) {
        textLayout.wrap = wrap
    }

    // ==================== Drawing ====================

    override fun draw(canvas: Canvas) {
        if (isMinimized) {
            drawMinimized(canvas)
            return
        }

        val focused = isFocused()

        // Background
        canvas.drawRoundRect(widgetBounds, 8f, 8f, backgroundPaint)

        if (shouldShowBorder()) {
            val borderP = if (focused) focusedBorderPaint else hoverBorderPaint
            canvas.drawRoundRect(widgetBounds, 8f, 8f, borderP)
        }

        // Content: while focused, the text editing controller draws the live text.
        canvas.withSave {
            val bounds = textZone.bounds
            clipRect(bounds)
            if (textZoneHost?.drawZone(this, textZone) != true) {
                if (richText.isNotEmpty()) {
                    textLayout.draw(this, richText, bounds)
                } else if (isBorderHovered) {
                    hintPaint.textSize = textLayout.textSize
                    drawText("Click to type...", bounds.left, bounds.top - hintPaint.ascent(), hintPaint)
                }
            }
        }

        // Draw keyboard if visible (inside content bounds but at bottom)
        keyboardOverlay.draw(canvas)

        // Border buttons - uses drawBorderButtons() which includes pin, minimize, fullscreen, and close buttons
        if (shouldShowBorderButtons()) {
            drawBorderButtons(canvas)
        }

        drawResizeHandle(canvas)

        // Keyboard button at bottom edge (show when border is hovered or keyboard is visible)
        if (isBorderHovered || isKeyboardVisible) {
            drawKeyboardButton(canvas)
        }

        val maxScrollOffset = textLayout.maxScrollOffset
        if (maxScrollOffset > 0 && isHovering()) {
            val scrollbarWidth = (contentBounds.width() * SCROLLBAR_WIDTH_RATIO).coerceAtLeast(12f)
            val trackPadding = 4f
            val trackHeight = contentBounds.height() - (trackPadding * 2)
            val thumbHeight = ((contentBounds.height() / (contentBounds.height() + maxScrollOffset)) * trackHeight).coerceAtLeast(20f)
            val thumbY = (textLayout.scrollOffset / maxScrollOffset) * (trackHeight - thumbHeight)

            canvas.drawRoundRect(
                contentBounds.right - scrollbarWidth,
                contentBounds.top + trackPadding,
                contentBounds.right - 2f,
                contentBounds.bottom - trackPadding,
                4f, 4f,
                scrollbarTrackPaint
            )

            canvas.drawRoundRect(
                contentBounds.right - scrollbarWidth + 2f,
                contentBounds.top + trackPadding + thumbY,
                contentBounds.right - 4f,
                contentBounds.top + trackPadding + thumbY + thumbHeight,
                3f, 3f,
                scrollbarThumbPaint
            )
        }
    }

    /**
     * Draw the keyboard toggle button at the bottom edge.
     */
    private fun drawKeyboardButton(canvas: Canvas) {
        keyboardOverlay.drawButton(canvas)
    }
}

/**
 * Puts text boxes on the dashboard: from the menu (reopening the last closed one first), for the
 * assistant, and from the saved dashboard.
 */
class TextBoxModule(
    private val services: DashboardServices,
    private val focus: (TextBoxWidget) -> Unit,
    releaseFocus: (TextBoxWidget) -> Unit,
) {
    val lifecycle = WidgetLifecycleRegistration.multiple(
        WidgetTypeId.TEXT, TextBoxWidget::class, DashboardPersistence.TextWidgetState::class,
        capture = { services.captureRestore.getTextWidgetState(it) },
        restore = { state ->
            services.captureRestore
                .restoreTextWidgets(listOf(state), services.viewportWidth, services.viewportHeight)
                .firstOrNull()
        },
        beforeRemove = releaseFocus,
    )

    /** Opens a text box at ([x], [y]), or reopens the last closed one, and focuses it. */
    fun open(x: Float, y: Float) {
        if (reopenClosed() != null) return
        val width = services.viewportWidth * DEFAULT_WIDTH_PERCENT
        val height = services.viewportHeight * DEFAULT_HEIGHT_PERCENT
        val box = TextBoxWidget(
            x.coerceIn(0f, services.viewportWidth - width),
            y.coerceIn(0f, services.viewportHeight - height),
            width,
            height
        )
        services.runPersistentMutation {
            services.open(WidgetTypeId.TEXT) { box }
            focus(box)
        }
    }

    /** A large centered box for text the assistant presents; null before the dashboard has a size. */
    fun openForAssistant(): TextBoxWidget? {
        if (services.viewportWidth <= 0 || services.viewportHeight <= 0) return null
        val width = (services.viewportWidth * 0.72f).coerceAtMost(services.viewportWidth.toFloat())
        val height = (services.viewportHeight * 0.55f).coerceAtMost(services.viewportHeight.toFloat())
        val x = ((services.viewportWidth - width) / 2f).coerceAtLeast(0f)
        val y = ((services.viewportHeight - height) / 2f).coerceAtLeast(0f)
        return services.open(WidgetTypeId.TEXT) { TextBoxWidget(x, y, width, height) }
    }

    private fun reopenClosed(): TextBoxWidget? = services.runPersistentMutation(changed = { it != null }) {
        (services.openClosedTemplate(WidgetTypeId.TEXT) as? TextBoxWidget)?.also { box ->
            if (!box.isMinimized) focus(box)
        }
    }

    companion object {
        /** Size of a new text box, as a fraction of the dashboard. */
        const val DEFAULT_WIDTH_PERCENT = 0.30f
        const val DEFAULT_HEIGHT_PERCENT = 0.15f
    }
}