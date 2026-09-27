package com.everyday.everyday_glasses.interaction.user_input

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BulletSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.core.graphics.withClip
import com.everyday.everyday_glasses.ui.components.TextEditCommandHandler
import com.everyday.everyday_glasses.ui.components.TextFormattingHandler
import com.everyday.everyday_glasses.widgets._common.WidgetTheme
import com.everyday.everyday_glasses.widgets.text_editor.FormattingMenu
import com.everyday.everyday_glasses.widgets.text_editor.TextEditContextMenu

// ==================== Text zone contract ====================

/** A FIELD holds plain text; an AREA holds rich text and exposes the formatting menu. */
enum class TextZoneKind { FIELD, AREA }

/** What the controller routes back to the owner of the focused zone. */
sealed interface TextEditAction {
    /** Live content after an edit. It is the controller's buffer: copy it, do not keep it. */
    class Changed(val content: CharSequence) : TextEditAction
    /** Enter was pressed in a FIELD. */
    class Submitted(val text: String) : TextEditAction
    /** An AREA's font size, wrap or columns changed on its [TextZone.layout]. */
    object LayoutChanged : TextEditAction
    /** The zone lost focus. */
    object Released : TextEditAction
}

/**
 * Any UI component region that accepts typed text. The owner provides where the zone is and what
 * it currently holds; while focused, the controller owns editing and drawing of the text, and
 * hands results back through [onTextEditAction].
 */
interface TextZone {
    val kind: TextZoneKind

    /** The rectangle the text is drawn into, in the owner's drawing coordinates. */
    val bounds: RectF

    /** True for head-locked overlays drawn in screen space rather than dashboard content space. */
    val isScreenSpace: Boolean get() = false

    /** Text size used for a FIELD. */
    val fieldTextSize: Float get() = 22f

    /** Whether a FIELD wraps onto several lines instead of scrolling horizontally. */
    val fieldWraps: Boolean get() = false

    /** Display settings of an AREA, shared with its owner's unfocused rendering. */
    val layout: TextZoneLayout? get() = null

    /** Committed content that editing starts from. */
    fun content(): CharSequence

    /** Owner state that must be saved when it changes; null when edits never need persisting. */
    fun persistentState(): Any? = null

    fun onTextEditAction(action: TextEditAction)
}

/** A web page that takes typed keys itself while it has the keyboard focus. */
interface KeyTarget {
    /** The page's own text field (its address bar). Editing it keeps the page focused. */
    val ownField: TextZone?

    /** A key typed while the page has the keyboard and no text zone takes it. */
    fun onPageKey(key: String)

    /** What a typed key can change that is saved with the dashboard (the page address). */
    fun savedState(): Any?

    /** The page no longer has the keyboard. */
    fun onKeyFocusLost()
}

/** What zone owners use to reach the controller. */
interface TextZoneHost {
    fun focus(zone: TextZone, selectAll: Boolean = false)

    /** Focuses [zone] if needed and places the caret at ([x], [y]) in the zone's coordinates. */
    fun tap(zone: TextZone, x: Float, y: Float)

    fun release(zone: TextZone)
    fun isFocused(zone: TextZone): Boolean

    /** Shows the cut / copy / paste menu for the focused zone. */
    fun showTextEditMenu()

    /** Returns true when a focused zone consumed the key. */
    fun onKey(key: String): Boolean

    /** Draws the live text of [zone] if it is focused; returns false otherwise. */
    fun drawZone(canvas: Canvas, zone: TextZone): Boolean

    /** Gives the keyboard to a web page. Its own field ([KeyTarget.ownField]) may stay focused. */
    fun focusPage(page: KeyTarget)

    /** Takes the keyboard back from [page], if it has it. */
    fun releasePage(page: KeyTarget)

    /** Nothing takes typed keys any more: no text zone, no web page. */
    fun clearAllFocus()
}

// ==================== Rendering ====================

/**
 * Lays out, scrolls, hit-tests and draws a text zone, with optional columns. An AREA owner keeps
 * one to draw its idle content; the controller draws the focused text through the same instance.
 */
class TextZoneLayout(
    textSize: Float,
    wrap: Boolean = true,
    columns: Int = 1,
    /** Single-line text is centered vertically instead of top aligned. */
    private val centerSingleLine: Boolean = false,
) {
    companion object {
        const val COLUMN_GAP = 24f
        private const val NO_WRAP_WIDTH = Int.MAX_VALUE / 2
        private const val CARET_MARGIN = 4f

        private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetTheme.ColorValue.selectionHighlight
            style = Paint.Style.FILL
        }
        private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
        }
    }

    val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        this.textSize = textSize
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
    }

    var textSize: Float
        get() = paint.textSize
        set(value) {
            paint.textSize = value
            invalidate()
        }

    var wrap: Boolean = wrap
        set(value) {
            field = value
            horizontalOffset = 0f
            invalidate()
        }

    var columns: Int = columns.coerceIn(1, 3)
        set(value) {
            field = value.coerceIn(1, 3)
            // Reset scroll when changing layout to prevent getting lost.
            scrollOffset = 0f
            invalidate()
        }

    var scrollOffset = 0f
        private set
    var maxScrollOffset = 0f
        private set
    private var horizontalOffset = 0f

    private var cached: StaticLayout? = null
    private var cachedText: CharSequence? = null
    private var cachedWidth = -1
    private var dirty = true
    private val selectionPath = Path()

    /** Call after mutating text in place; replaced text objects are detected automatically. */
    fun invalidate() {
        dirty = true
    }

    fun scrollBy(dy: Float) {
        scrollOffset = (scrollOffset + dy).coerceIn(0f, maxScrollOffset)
    }

    fun resetScroll() {
        scrollOffset = 0f
        horizontalOffset = 0f
    }

    fun draw(
        canvas: Canvas,
        text: CharSequence,
        bounds: RectF,
        selectionStart: Int = 0,
        selectionEnd: Int = 0,
        caret: Int = -1,
    ) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val layout = measure(text, bounds)
        val columnWidth = columnWidth(bounds)
        val starts = columnStarts(layout, bounds.height())
        val top = originY(layout, bounds)
        val selStart = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val selEnd = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)

        for (col in 0 until columns) {
            val colX = bounds.left + col * (columnWidth + COLUMN_GAP)
            val virtualTop = starts[col]
            canvas.withClip(colX, bounds.top, colX + columnWidth, bounds.bottom) {
                translate(colX - horizontalOffset, top - virtualTop)
                if (selStart != selEnd) {
                    selectionPath.reset()
                    layout.getSelectionPath(selStart, selEnd, selectionPath)
                    drawPath(selectionPath, selectionPaint)
                }
                layout.draw(this)
                if (caret >= 0) {
                    val offset = caret.coerceIn(0, text.length)
                    val line = layout.getLineForOffset(offset)
                    val lineTop = layout.getLineTop(line).toFloat()
                    val lineBottom = layout.getLineBottom(line).toFloat()
                    if (lineBottom > virtualTop && lineTop < virtualTop + bounds.height()) {
                        val x = layout.getPrimaryHorizontal(offset)
                        drawLine(x, lineTop, x, lineBottom, caretPaint)
                    }
                }
            }
        }
    }

    /** Text offset under ([x], [y]), accounting for columns and scroll. */
    fun offsetAt(text: CharSequence, bounds: RectF, x: Float, y: Float): Int {
        if (text.isEmpty()) return 0
        val layout = measure(text, bounds)
        val columnWidth = columnWidth(bounds)
        val stride = columnWidth + COLUMN_GAP
        val relativeX = x - bounds.left
        val col = (relativeX / stride).toInt().coerceIn(0, columns - 1)
        val virtualY = columnStarts(layout, bounds.height())[col] + (y - originY(layout, bounds))
        val columnX = (relativeX - col * stride).coerceIn(0f, columnWidth.toFloat()) + horizontalOffset
        val line = if (virtualY < 0f) 0 else layout.getLineForVertical(virtualY.toInt())
        return layout.getOffsetForHorizontal(line, columnX).coerceIn(0, text.length)
    }

    /** Scrolls just enough to keep [offset] visible. */
    fun scrollToOffset(text: CharSequence, bounds: RectF, offset: Int) {
        val layout = measure(text, bounds)
        val line = layout.getLineForOffset(offset.coerceIn(0, text.length))
        val capacity = bounds.height() * columns
        val lineTop = layout.getLineTop(line).toFloat()
        val lineBottom = layout.getLineBottom(line).toFloat()
        if (lineBottom > scrollOffset + capacity) scrollOffset = lineBottom - capacity
        if (lineTop < scrollOffset) scrollOffset = lineTop
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)

        if (!wrap) {
            val visibleWidth = columnWidth(bounds).toFloat()
            val caretX = layout.getPrimaryHorizontal(offset.coerceIn(0, text.length))
            if (caretX - horizontalOffset > visibleWidth - CARET_MARGIN) {
                horizontalOffset = caretX - visibleWidth + CARET_MARGIN
            }
            if (caretX < horizontalOffset) horizontalOffset = caretX
            horizontalOffset = horizontalOffset.coerceAtLeast(0f)
        }
    }

    /** Builds (or reuses) the layout and updates the scroll range for [bounds]. */
    fun measure(text: CharSequence, bounds: RectF): StaticLayout {
        val width = if (wrap) columnWidth(bounds) else NO_WRAP_WIDTH
        val layout = cached?.takeIf { !dirty && cachedText === text && cachedWidth == width }
            ?: StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1f)
                .setIncludePad(false)
                .build()
                .also {
                    cached = it
                    cachedText = text
                    cachedWidth = width
                    dirty = false
                }
        // Text flows from the bottom of one column to the top of the next.
        maxScrollOffset = (layout.height - bounds.height() * columns).coerceAtLeast(0f)
        scrollOffset = scrollOffset.coerceIn(0f, maxScrollOffset)
        return layout
    }

    private fun columnWidth(bounds: RectF): Int =
        ((bounds.width() - (columns - 1) * COLUMN_GAP) / columns).toInt().coerceAtLeast(1)

    private fun originY(layout: StaticLayout, bounds: RectF): Float =
        if (centerSingleLine && layout.lineCount == 1) bounds.centerY() - layout.height / 2f else bounds.top

    /**
     * The starting Y coordinate in the text for each column. Snaps to line boundaries to prevent
     * text from being sliced in half.
     */
    private fun columnStarts(layout: StaticLayout, visibleHeight: Float): FloatArray {
        val offsets = FloatArray(columns)
        var currentY = scrollOffset
        offsets[0] = currentY
        for (i in 1 until columns) {
            val idealCutY = currentY + visibleHeight
            val lineIndex = layout.getLineForVertical(idealCutY.toInt() - 1)
            val lineTop = layout.getLineTop(lineIndex)
            val lineBottom = layout.getLineBottom(lineIndex)
            currentY = if (idealCutY - lineTop >= lineBottom - idealCutY) lineBottom.toFloat() else lineTop.toFloat()
            offsets[i] = currentY
        }
        return offsets
    }
}

// ==================== Controller ====================

/**
 * Owns the one focused [TextZone] and everything the user does to it: typing, caret and
 * selection, clipboard (phone and glasses), the text edit context menu and, for areas, rich
 * formatting. It knows nothing about widgets; results are routed back to the zone's owner as
 * [TextEditAction]s. Web content editing is left to the WebView.
 */
internal class TextEditingController(
    private val toContentSpace: (Float, Float) -> Pair<Float, Float>,
    private val requestPhoneClipboard: () -> Unit,
    private val notifyFocusChanged: (Boolean) -> Unit,
    private val notifyTransientChange: () -> Unit,
    private val notificationGeneration: () -> Long,
    private val runClassifiedInput: (action: () -> Boolean, changed: () -> Boolean) -> Boolean,
) : TextZoneHost {

    private companion object {
        const val BULLET_GAP_WIDTH = 20
        const val CARET_BLINK_MS = 530L
        const val MENU_HALF_WIDTH = 90f
        const val MENU_OFFSET_Y = 60f
        val KEYBOARD_MODE_KEYS = setOf("SHIFT", "123", "ABC", "=/<")
    }

    /** Edit state of the focused zone. */
    private class Session(val zone: TextZone, val layout: TextZoneLayout) {
        val text = SpannableStringBuilder()
        var cursor = 0
        var selectionStart = 0
        var selectionEnd = 0
        var anchor = 0
        var selecting = false
        var bold = false
        var italic = false
        var underline = false
        var caretVisible = true
        var caretBlinkAt = System.currentTimeMillis()

        val isArea get() = zone.kind == TextZoneKind.AREA
        val hasSelection get() = selectionStart != selectionEnd
        val selectionMin get() = minOf(selectionStart, selectionEnd).coerceIn(0, text.length)
        val selectionMax get() = maxOf(selectionStart, selectionEnd).coerceIn(0, text.length)
    }

    private var session: Session? = null
    private var switchingFocus = false
    private var menuDispatchDepth = 0
    private var glassesClipboard: String? = null

    var phoneClipboard: String? = null
        private set

    var textEditMenu: TextEditContextMenu? = null
        private set
    var formattingMenu: FormattingMenu? = null
        private set
    val hasFocus: Boolean get() = session != null

    /** The web page taking typed keys when no text zone (other than its own field) does. */
    var focusedPage: KeyTarget? = null
        private set

    /** Something takes typed keys: a text zone or a web page. */
    val hasKeyFocus: Boolean get() = session != null || focusedPage != null

    val isSelecting: Boolean get() = session?.selecting == true
    val isMenuVisible: Boolean get() = textEditMenu?.isVisible == true || formattingMenu?.isVisible == true

    // ==================== Focus ====================

    override fun focus(zone: TextZone, selectAll: Boolean) {
        if (session?.zone === zone) {
            if (selectAll) selectAll()
            return
        }
        switchTo(zone)
        if (selectAll) session?.let(::selectAllIn)
    }

    override fun release(zone: TextZone) {
        if (session?.zone === zone) switchTo(null)
    }

    fun clearFocus() {
        if (session != null) switchTo(null)
    }

    override fun focusPage(page: KeyTarget) {
        if (session?.zone !== page.ownField) clearFocus()
        if (focusedPage === page) return
        val previous = focusedPage
        focusedPage = page
        previous?.onKeyFocusLost()
        notifyFocusChanged(true)
    }

    override fun releasePage(page: KeyTarget) {
        if (focusedPage === page) clearPageFocus()
    }

    override fun clearAllFocus() {
        clearFocus()
        clearPageFocus()
    }

    private fun clearPageFocus() {
        val page = focusedPage ?: return
        focusedPage = null
        page.onKeyFocusLost()
        notifyFocusChanged(false)
    }

    /** A typed key goes to the focused text zone, otherwise to the focused web page. */
    fun typeKey(key: String) {
        if (onKey(key)) return
        val page = focusedPage ?: return
        val before = page.savedState()
        // A key can make the page navigate, which is saved with the dashboard.
        runClassifiedInput({ page.onPageKey(key); true }, { page.savedState() != before })
    }

    override fun isFocused(zone: TextZone): Boolean = session?.zone === zone

    /** Only one zone is focused. The old zone learns it lost focus after the new one is set. */
    private fun switchTo(zone: TextZone?) {
        if (switchingFocus) return
        switchingFocus = true
        val previous = session
        try {
            session = zone?.let { newSession(it) }
            textEditMenu?.dismiss()
            formattingMenu?.dismiss()
            previous?.zone?.onTextEditAction(TextEditAction.Released)
        } finally {
            switchingFocus = false
        }
        // Only one thing takes typed keys: a newly focused zone ends typing into a web page,
        // unless it is that page's own field.
        val page = focusedPage
        if (zone != null && page != null && zone !== page.ownField) {
            focusedPage = null
            page.onKeyFocusLost()
        }
        notifyFocusChanged(zone != null)
    }

    private fun newSession(zone: TextZone): Session {
        val layout = zone.layout ?: TextZoneLayout(
            textSize = zone.fieldTextSize,
            wrap = zone.fieldWraps,
            centerSingleLine = !zone.fieldWraps,
        )
        return Session(zone, layout).apply {
            val initial = zone.content()
            text.append(if (isArea) initial else initial.toString())
            cursor = text.length
            selectionStart = cursor
            selectionEnd = cursor
            layout.invalidate()
            updateActiveFormattingFromCursor(this)
        }
    }

    // ==================== Keys ====================

    override fun onKey(key: String): Boolean {
        val s = session ?: return false
        if (key.isEmpty() || key in KEYBOARD_MODE_KEYS) return true
        when (key) {
            "BACKSPACE" -> edit(s) { backspace(s) }
            "ENTER", "\n", "\r" -> {
                if (s.isArea) edit(s) { insert(s, "\n") }
                else {
                    val submitted = s.text.toString()
                    classified(s) { s.zone.onTextEditAction(TextEditAction.Submitted(submitted)) }
                }
            }
            "SPACE" -> edit(s) { insert(s, " ") }
            else -> edit(s) { insert(s, key) }
        }
        return true
    }

    // ==================== Pointer ====================

    override fun tap(zone: TextZone, x: Float, y: Float) {
        if (session?.zone !== zone) switchTo(zone)
        val s = session ?: return
        s.cursor = s.layout.offsetAt(s.text, zone.bounds, x, y)
        collapseSelection(s)
        updateActiveFormattingFromCursor(s)
        showCaret(s)
        notifyTransientChange()
    }

    /** Whether a screen point lands on the focused zone. */
    fun focusedZoneContains(screenX: Float, screenY: Float): Boolean {
        val s = session ?: return false
        val (x, y) = zonePoint(s, screenX, screenY)
        return s.zone.bounds.contains(x, y)
    }

    fun beginSelection(screenX: Float, screenY: Float) {
        val s = session ?: return
        moveCursorToScreenPoint(s, screenX, screenY)
        s.selecting = true
        s.anchor = s.cursor
        collapseSelection(s)
        notifyTransientChange()
    }

    fun extendSelection(screenX: Float, screenY: Float) {
        val s = session ?: return
        if (!s.selecting) return
        moveCursorToScreenPoint(s, screenX, screenY)
        s.selectionStart = minOf(s.anchor, s.cursor)
        s.selectionEnd = maxOf(s.anchor, s.cursor)
        notifyTransientChange()
    }

    fun endSelection() {
        session?.selecting = false
    }

    /** Double tap on the focused zone opens the text edit menu. */
    fun onDoubleTap(screenX: Float, screenY: Float): Boolean {
        if (!focusedZoneContains(screenX, screenY)) return false
        showTextEditMenu()
        return true
    }

    private fun moveCursorToScreenPoint(s: Session, screenX: Float, screenY: Float) {
        val (x, y) = zonePoint(s, screenX, screenY)
        s.cursor = s.layout.offsetAt(s.text, s.zone.bounds, x, y)
        updateActiveFormattingFromCursor(s)
    }

    private fun zonePoint(s: Session, screenX: Float, screenY: Float): Pair<Float, Float> =
        if (s.zone.isScreenSpace) screenX to screenY else toContentSpace(screenX, screenY)

    // ==================== Drawing ====================

    override fun drawZone(canvas: Canvas, zone: TextZone): Boolean {
        val s = session?.takeIf { it.zone === zone } ?: return false
        val now = System.currentTimeMillis()
        if (now - s.caretBlinkAt > CARET_BLINK_MS) {
            s.caretVisible = !s.caretVisible
            s.caretBlinkAt = now
        }
        s.layout.draw(
            canvas,
            s.text,
            zone.bounds,
            s.selectionStart,
            s.selectionEnd,
            caret = if (s.caretVisible) s.cursor else -1,
        )
        return true
    }

    // ==================== Menus ====================

    fun createMenus(screenWidth: Float, screenHeight: Float) {
        textEditMenu = TextEditContextMenu(screenWidth, screenHeight).apply {
            commandHandler = object : TextEditCommandHandler {
                override fun cut() = this@TextEditingController.cut()
                override fun copy() = this@TextEditingController.copy()
                override fun paste(content: String) = this@TextEditingController.paste(content)
                override fun selectAll() = this@TextEditingController.selectAll()
                override fun showFormatting() = showFormattingMenu()
                override fun onMenuDismissed() {
                    if (menuDispatchDepth == 0) notifyTransientChange()
                }
            }
        }
        formattingMenu = FormattingMenu(screenWidth, screenHeight).apply {
            formattingHandler = object : TextFormattingHandler {
                override fun setFontSize(size: Float) = updateAreaLayout { it.textSize = size.coerceIn(12f, 72f) }
                override fun setTextWrap(enabled: Boolean) = updateAreaLayout { it.wrap = enabled }
                override fun setColumns(columns: Int) = updateAreaLayout { it.columns = columns }
                override fun setBold(enabled: Boolean) = toggleStyle(Typeface.BOLD)
                override fun setItalic(enabled: Boolean) = toggleStyle(Typeface.ITALIC)
                override fun setUnderline(enabled: Boolean) = toggleUnderline()
                override fun setBulletList(enabled: Boolean) = toggleBulletList()
                override fun setNumberedList(enabled: Boolean) = toggleNumberedList()
                override fun onMenuDismissed() = notifyTransientChange()
            }
        }
    }

    override fun showTextEditMenu() {
        val s = session ?: return
        requestPhoneClipboard()
        val bounds = s.zone.bounds
        textEditMenu?.show(
            bounds.centerX() - MENU_HALF_WIDTH,
            bounds.top - MENU_OFFSET_Y,
            glassesClipboard ?: phoneClipboard,
            s.hasSelection,
            includeFormatting = s.isArea,
        )
        notifyTransientChange()
    }

    /** Returns null when no text edit menu is open, otherwise whether the tap was handled. */
    fun onMenuTap(x: Float, y: Float): Boolean? {
        formattingMenu?.takeIf { it.isVisible }?.let { return it.onTap(x, y) }
        textEditMenu?.takeIf { it.isVisible }?.let { menu -> return dispatchMenuAction { menu.onTap(x, y) } }
        return null
    }

    fun updateMenuHover(x: Float, y: Float) {
        formattingMenu?.updateHover(x, y)
        textEditMenu?.updateHover(x, y)
    }

    fun drawMenus(canvas: Canvas) {
        textEditMenu?.draw(canvas)
        formattingMenu?.draw(canvas)
    }

    fun dismissMenus() {
        textEditMenu?.dismiss()
        formattingMenu?.dismiss()
    }

    fun setPhoneClipboard(content: String?) {
        phoneClipboard = content
        if (textEditMenu?.isVisible == true && session != null) {
            textEditMenu?.updateClipboard(glassesClipboard ?: phoneClipboard)
            notifyTransientChange()
        }
    }

    /** Keeps a menu command and the dismissal it causes from producing two notifications. */
    private fun dispatchMenuAction(action: () -> Boolean): Boolean {
        val before = notificationGeneration()
        menuDispatchDepth++
        val handled = try {
            action()
        } finally {
            menuDispatchDepth--
        }
        if (handled && notificationGeneration() == before) notifyTransientChange()
        return handled
    }

    private fun showFormattingMenu() {
        val s = session?.takeIf { it.isArea } ?: return
        textEditMenu?.dismiss()
        val bounds = s.zone.bounds
        formattingMenu?.show(bounds.centerX(), bounds.top - 20f, s.layout.textSize, s.layout.wrap, s.layout.columns)
        formattingMenu?.apply {
            isBold = isStyleActive(s, Typeface.BOLD)
            isItalic = isStyleActive(s, Typeface.ITALIC)
            isUnderline = isUnderlineActive(s)
        }
        notifyTransientChange()
    }

    // ==================== Clipboard ====================

    fun cut() {
        val s = session ?: return
        if (!s.hasSelection) return
        edit(s) {
            glassesClipboard = selectedText(s)
            deleteSelection(s)
        }
    }

    fun copy() {
        val s = session ?: return
        if (!s.hasSelection) return
        glassesClipboard = selectedText(s)
        notifyTransientChange()
    }

    fun paste(content: String) {
        val s = session ?: return
        edit(s) { insert(s, content) }
    }

    fun selectAll() {
        val s = session ?: return
        selectAllIn(s)
        notifyTransientChange()
    }

    private fun selectAllIn(s: Session) {
        s.selectionStart = 0
        s.selectionEnd = s.text.length
        s.cursor = s.text.length
    }

    // ==================== Editing primitives ====================

    /** Runs a content edit, reports it to the owner, and classifies it for persistence. */
    private fun edit(s: Session, mutation: () -> Unit) = classified(s) {
        mutation()
        s.layout.invalidate()
        s.layout.scrollToOffset(s.text, s.zone.bounds, s.cursor)
        showCaret(s)
        s.zone.onTextEditAction(TextEditAction.Changed(s.text))
    }

    private fun classified(s: Session, action: () -> Unit) {
        val before = s.zone.persistentState()
        runClassifiedInput(
            {
                action()
                true
            },
            { s.zone.persistentState() != before },
        )
    }

    private fun insert(s: Session, value: String) {
        if (s.hasSelection) deleteSelection(s)
        val position = s.cursor.coerceIn(0, s.text.length)
        s.text.insert(position, value)
        val end = position + value.length
        if (s.isArea) {
            val style = when {
                s.bold && s.italic -> Typeface.BOLD_ITALIC
                s.bold -> Typeface.BOLD
                s.italic -> Typeface.ITALIC
                else -> null
            }
            style?.let { s.text.setSpan(StyleSpan(it), position, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            if (s.underline) s.text.setSpan(UnderlineSpan(), position, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        s.cursor = end
        collapseSelection(s)
    }

    private fun backspace(s: Session) {
        if (s.hasSelection) {
            deleteSelection(s)
        } else if (s.cursor > 0) {
            s.text.delete(s.cursor - 1, s.cursor)
            s.cursor--
            collapseSelection(s)
        }
    }

    private fun deleteSelection(s: Session) {
        val start = s.selectionMin
        s.text.delete(start, s.selectionMax)
        s.cursor = start
        collapseSelection(s)
    }

    private fun selectedText(s: Session): String = s.text.substring(s.selectionMin, s.selectionMax)

    private fun collapseSelection(s: Session) {
        s.cursor = s.cursor.coerceIn(0, s.text.length)
        s.selectionStart = s.cursor
        s.selectionEnd = s.cursor
    }

    private fun showCaret(s: Session) {
        s.caretVisible = true
        s.caretBlinkAt = System.currentTimeMillis()
    }

    // ==================== Rich formatting (areas only) ====================

    private fun updateAreaLayout(change: (TextZoneLayout) -> Unit) {
        val s = session?.takeIf { it.isArea } ?: return
        classified(s) {
            change(s.layout)
            s.zone.onTextEditAction(TextEditAction.LayoutChanged)
        }
    }

    private fun toggleStyle(style: Int) {
        val s = session?.takeIf { it.isArea } ?: return
        if (s.hasSelection) {
            edit(s) { toggleStyleSpanOnSelection(s, style) }
        } else {
            if (style == Typeface.BOLD) s.bold = !s.bold else s.italic = !s.italic
        }
        formattingMenu?.isBold = isStyleActive(s, Typeface.BOLD)
        formattingMenu?.isItalic = isStyleActive(s, Typeface.ITALIC)
    }

    private fun toggleUnderline() {
        val s = session?.takeIf { it.isArea } ?: return
        if (s.hasSelection) edit(s) { toggleUnderlineSpanOnSelection(s) } else s.underline = !s.underline
        formattingMenu?.isUnderline = isUnderlineActive(s)
    }

    private fun toggleBulletList() {
        val s = session?.takeIf { it.isArea } ?: return
        edit(s) {
            val lineStart = findLineStart(s, if (s.hasSelection) s.selectionMin else s.cursor)
            val lineEnd = findLineEnd(s, if (s.hasSelection) s.selectionMax else s.cursor)
            val existing = s.text.getSpans(lineStart, lineEnd, BulletSpan::class.java)
            if (existing.isNotEmpty()) {
                existing.forEach(s.text::removeSpan)
            } else {
                s.text.setSpan(BulletSpan(BULLET_GAP_WIDTH, Color.WHITE), lineStart, lineEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun toggleNumberedList() {
        val s = session?.takeIf { it.isArea } ?: return
        edit(s) {
            val lineStart = findLineStart(s, if (s.hasSelection) s.selectionMin else s.cursor)
            val lineText = s.text.substring(lineStart, minOf(lineStart + 10, s.text.length))
            val match = Regex("^\\d+\\.\\s").find(lineText)
            if (match != null) {
                s.text.delete(lineStart, lineStart + match.value.length)
                s.cursor = (s.cursor - match.value.length).coerceAtLeast(lineStart)
            } else {
                s.text.insert(lineStart, "1. ")
                s.cursor += 3
            }
            collapseSelection(s)
        }
    }

    private fun findLineStart(s: Session, position: Int): Int {
        var start = position.coerceIn(0, s.text.length)
        while (start > 0 && s.text[start - 1] != '\n') start--
        return start
    }

    private fun findLineEnd(s: Session, position: Int): Int {
        var end = position.coerceIn(0, s.text.length)
        while (end < s.text.length && s.text[end] != '\n') end++
        return end
    }

    private fun isStyleActive(s: Session, style: Int): Boolean {
        val matches = { span: StyleSpan -> span.style == style || span.style == Typeface.BOLD_ITALIC }
        if (s.hasSelection) return spansInSelection(s, StyleSpan::class.java).any(matches)
        val active = if (style == Typeface.BOLD) s.bold else s.italic
        return active || spansAtCursor(s, StyleSpan::class.java).any(matches)
    }

    private fun isUnderlineActive(s: Session): Boolean {
        if (s.hasSelection) return spansInSelection(s, UnderlineSpan::class.java).isNotEmpty()
        return s.underline || spansAtCursor(s, UnderlineSpan::class.java).isNotEmpty()
    }

    private fun <T> spansInSelection(s: Session, type: Class<T>): Array<T> =
        s.text.getSpans(s.selectionMin, s.selectionMax, type)

    private fun <T> spansAtCursor(s: Session, type: Class<T>): Array<T> {
        if (s.text.isEmpty()) return s.text.getSpans(0, 0, type)
        val position = s.cursor.coerceIn(0, s.text.length)
        val check = if (position > 0) position - 1 else position
        return s.text.getSpans(check, check + 1, type)
    }

    private fun updateActiveFormattingFromCursor(s: Session) {
        if (!s.isArea || s.text.isEmpty() || s.cursor == 0) {
            s.bold = false
            s.italic = false
            s.underline = false
            return
        }
        val check = (s.cursor - 1).coerceIn(0, s.text.length - 1)
        val styles = s.text.getSpans(check, check + 1, StyleSpan::class.java)
        s.bold = styles.any { it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC }
        s.italic = styles.any { it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC }
        s.underline = s.text.getSpans(check, check + 1, UnderlineSpan::class.java).isNotEmpty()
    }

    private fun toggleStyleSpanOnSelection(s: Session, style: Int) {
        val start = s.selectionMin
        val end = s.selectionMax
        if (start == end) return
        val text = s.text
        val existing = text.getSpans(start, end, StyleSpan::class.java)
        val hasStyle = existing.any {
            text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end &&
                (it.style == style || it.style == Typeface.BOLD_ITALIC)
        }
        if (!hasStyle) {
            text.setSpan(StyleSpan(style), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        for (span in existing) {
            if (span.style != style && span.style != Typeface.BOLD_ITALIC) continue
            val spanStart = text.getSpanStart(span)
            val spanEnd = text.getSpanEnd(span)
            text.removeSpan(span)
            if (spanStart < start) text.setSpan(StyleSpan(span.style), spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (span.style == Typeface.BOLD_ITALIC) {
                val remaining = if (style == Typeface.BOLD) Typeface.ITALIC else Typeface.BOLD
                text.setSpan(StyleSpan(remaining), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (spanEnd > end) text.setSpan(StyleSpan(span.style), end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun toggleUnderlineSpanOnSelection(s: Session) {
        val start = s.selectionMin
        val end = s.selectionMax
        if (start == end) return
        val text = s.text
        val existing = text.getSpans(start, end, UnderlineSpan::class.java)
        val hasUnderline = existing.any { text.getSpanStart(it) <= start && text.getSpanEnd(it) >= end }
        if (!hasUnderline) {
            text.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            return
        }
        for (span in existing) {
            val spanStart = text.getSpanStart(span)
            val spanEnd = text.getSpanEnd(span)
            text.removeSpan(span)
            if (spanStart < start) text.setSpan(UnderlineSpan(), spanStart, start, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (spanEnd > end) text.setSpan(UnderlineSpan(), end, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
