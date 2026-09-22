package com.everyday.everyday_glasses.interaction.user_input

import com.everyday.everyday_glasses.widgets._common.BaseWidget
import com.everyday.everyday_glasses.widgets.browser.BrowserWidget
import com.everyday.everyday_glasses.widgets.text_editor.FormattingMenu
import com.everyday.everyday_glasses.widgets.text_editor.TextBoxWidget
import com.everyday.everyday_glasses.widgets.text_editor.TextEditContextMenu

/**
 * Owns the currently focused editor and the operations performed on text or browser-address text.
 * Top-level input priority and drawing remain in WidgetContainer.
 */
internal class TextEditingController(
    private val fullscreenTextWidget: () -> TextBoxWidget?,
    private val fullscreenBrowserWidget: () -> BrowserWidget?,
    private val textEditMenu: () -> TextEditContextMenu?,
    private val formattingMenu: () -> FormattingMenu?,
    private val requestPhoneClipboard: () -> Unit,
    private val ensureEditingOverlayVisible: () -> Unit,
    private val notifyFocusChanged: (Boolean) -> Unit,
    private val notifyTransientChange: () -> Unit,
    private val notificationGeneration: () -> Long,
    private val runPersistentMutation: (() -> Unit) -> Unit,
    private val runClassifiedInput: (action: () -> Boolean, changed: () -> Boolean) -> Boolean,
) {
    private enum class MenuTarget { TEXT_WIDGET, BROWSER_ADDRESS }

    var focusedTextWidget: TextBoxWidget? = null
        private set

    var focusedBrowserWidget: BrowserWidget? = null
        private set

    var phoneClipboard: String? = null
        private set

    private var glassesClipboard: String? = null
    private var menuTarget = MenuTarget.TEXT_WIDGET
    private var suppressNestedFocusCallbacks = false
    private var menuDispatchDepth = 0

    /**
     * Switches the one active editor. Calls caused by unfocusing the previous widget are ignored
     * until the switch is complete, preventing its callback from clearing the new focus.
     */
    fun focus(widget: BaseWidget?) {
        if (suppressNestedFocusCallbacks) return
        if ((widget is TextBoxWidget && focusedTextWidget === widget) ||
            (widget is BrowserWidget && focusedBrowserWidget === widget)
        ) return

        suppressNestedFocusCallbacks = true
        try {
            when (widget) {
                is TextBoxWidget -> {
                    focusedBrowserWidget?.setFocused(false)
                    focusedBrowserWidget = null
                    focusedTextWidget?.clearSelection()
                    focusedTextWidget?.setFocused(false)
                    focusedTextWidget = widget
                }
                is BrowserWidget -> {
                    focusedTextWidget?.clearSelection()
                    focusedTextWidget?.setFocused(false)
                    focusedTextWidget = null
                    focusedBrowserWidget?.setFocused(false)
                    focusedBrowserWidget = widget
                }
                null -> {
                    focusedTextWidget?.clearSelection()
                    focusedTextWidget?.setFocused(false)
                    focusedTextWidget = null
                    focusedBrowserWidget?.setFocused(false)
                    focusedBrowserWidget = null
                }
            }
            formattingMenu()?.dismiss()
        } finally {
            suppressNestedFocusCallbacks = false
        }
        notifyFocusChanged(widget != null)
    }

    /** Returns true only when a focused text or browser editor consumed this key. */
    fun handleFocusedKeyPress(key: String): Boolean {
        focusedTextWidget?.let { widget ->
            val before = textPersistentSignature(widget)
            runClassifiedInput(
                {
                    widget.onKeyPress(key)
                    true
                },
                { textPersistentSignature(widget) != before },
            )
            return true
        }

        focusedBrowserWidget?.let { browser ->
            if (browser.isEditingAddress() && (key == "ENTER" || key == "\n")) {
                runPersistentMutation { browser.onKeyPress(key) }
            } else {
                val before = browserPersistentSignature(browser)
                runClassifiedInput(
                    {
                        browser.onKeyPress(key)
                        true
                    },
                    { browserPersistentSignature(browser) != before },
                )
            }
            return true
        }

        return false
    }

    fun showTextEditMenu(clipboardContent: String?) {
        val widget = focusedTextWidget ?: fullscreenTextWidget() ?: return
        if (!widget.isFocused()) return

        menuTarget = MenuTarget.TEXT_WIDGET
        if (clipboardContent != null) phoneClipboard = clipboardContent
        requestPhoneClipboard()
        textEditMenu()?.show(
            widget.x + widget.widgetWidth / 2 - 90f,
            widget.y - 60f,
            glassesClipboard ?: phoneClipboard,
            widget.hasSelection(),
        )
        notifyTransientChange()
    }

    fun showBrowserAddressEditMenu(widget: BrowserWidget, clipboardContent: String?) {
        menuTarget = MenuTarget.BROWSER_ADDRESS
        if (clipboardContent != null) phoneClipboard = clipboardContent
        if (!widget.isEditingAddress()) widget.beginAddressEditing(selectAll = true)
        focus(widget)
        ensureEditingOverlayVisible()
        requestPhoneClipboard()
        textEditMenu()?.show(
            widget.x + widget.widgetWidth / 2 - 90f,
            (widget.y + 8f).coerceAtLeast(0f),
            glassesClipboard ?: phoneClipboard,
            widget.addressHasSelection(),
            includeFormatting = false,
        )
        notifyTransientChange()
    }

    fun setPhoneClipboard(content: String?) {
        phoneClipboard = content
        updateVisibleClipboard()
    }

    private fun updateVisibleClipboard() {
        val text = focusedTextWidget ?: fullscreenTextWidget()
        val browser = focusedBrowserWidget ?: fullscreenBrowserWidget()
        if (textEditMenu()?.isVisible == true &&
            (text?.isFocused() == true || browser?.isEditingAddress() == true)
        ) {
            textEditMenu()?.updateClipboard(glassesClipboard ?: phoneClipboard)
            notifyTransientChange()
        }
    }

    /** Keeps a menu command and the dismissal it causes from producing two notifications. */
    fun dispatchMenuAction(action: () -> Boolean): Boolean {
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

    fun onMenuDismissed() {
        if (menuDispatchDepth == 0) notifyTransientChange()
    }

    fun cut() {
        if (menuTarget == MenuTarget.BROWSER_ADDRESS) {
            val browser = focusedBrowserWidget ?: fullscreenBrowserWidget() ?: return
            glassesClipboard = browser.cutSelectedAddressText()
            notifyTransientChange()
            return
        }

        val widget = focusedTextWidget ?: fullscreenTextWidget() ?: return
        if (!widget.hasSelection()) return
        runPersistentMutation {
            glassesClipboard = widget.getSelectedText()
            widget.deleteSelection()
        }
    }

    fun copy() {
        if (menuTarget == MenuTarget.BROWSER_ADDRESS) {
            val browser = focusedBrowserWidget ?: fullscreenBrowserWidget() ?: return
            glassesClipboard = browser.copySelectedAddressText()
            notifyTransientChange()
            return
        }

        val widget = focusedTextWidget ?: fullscreenTextWidget() ?: return
        if (!widget.hasSelection()) return
        glassesClipboard = widget.getSelectedText()
        notifyTransientChange()
    }

    fun paste(content: String) {
        if (menuTarget == MenuTarget.BROWSER_ADDRESS) {
            (focusedBrowserWidget ?: fullscreenBrowserWidget())?.pasteIntoAddress(content)
            notifyTransientChange()
            return
        }
        runPersistentMutation {
            (focusedTextWidget ?: fullscreenTextWidget())?.pasteText(content)
        }
    }

    fun selectAll() {
        if (menuTarget == MenuTarget.BROWSER_ADDRESS) {
            (focusedBrowserWidget ?: fullscreenBrowserWidget())?.selectAllAddressText()
            notifyTransientChange()
            return
        }
        (focusedTextWidget ?: fullscreenTextWidget())?.let {
            it.selectAll()
            notifyTransientChange()
        }
    }

    fun showFormattingMenu() {
        val widget = focusedTextWidget ?: fullscreenTextWidget() ?: return
        if (!widget.isFocused()) return

        textEditMenu()?.dismiss()
        formattingMenu()?.show(
            widget.x + widget.widgetWidth / 2,
            widget.y - 20f,
            widget.textFontSize,
            widget.isTextWrap,
            widget.getColumnCount(),
        )
        formattingMenu()?.apply {
            isBold = widget.isBoldActive()
            isItalic = widget.isItalicActive()
            isUnderline = widget.isUnderlineActive()
        }
        notifyTransientChange()
    }

    fun setFontSize(size: Float) = runPersistentMutation {
        (focusedTextWidget ?: fullscreenTextWidget())?.setFontSize(size)
    }

    fun setTextWrap(enabled: Boolean) = runPersistentMutation {
        (focusedTextWidget ?: fullscreenTextWidget())?.setTextWrap(enabled)
    }

    fun toggleBold() = runPersistentMutation {
        (focusedTextWidget ?: fullscreenTextWidget())?.let { widget ->
            widget.toggleBold()
            formattingMenu()?.isBold = widget.isBoldActive()
        }
    }

    fun toggleItalic() = runPersistentMutation {
        (focusedTextWidget ?: fullscreenTextWidget())?.let { widget ->
            widget.toggleItalic()
            formattingMenu()?.isItalic = widget.isItalicActive()
        }
    }

    fun toggleUnderline() = runPersistentMutation {
        (focusedTextWidget ?: fullscreenTextWidget())?.let { widget ->
            widget.toggleUnderline()
            formattingMenu()?.isUnderline = widget.isUnderlineActive()
        }
    }

    fun toggleBulletList() = runPersistentMutation {
        (focusedTextWidget ?: fullscreenTextWidget())?.toggleBulletList()
    }

    fun toggleNumberedList() = runPersistentMutation {
        (focusedTextWidget ?: fullscreenTextWidget())?.toggleNumberedList()
    }

    fun setColumns(columns: Int) = runPersistentMutation {
        focusedTextWidget?.setColumns(columns)
    }

    private fun browserPersistentSignature(widget: BrowserWidget): List<Any?> = listOf(
        widget.getUrl(),
        widget.isFullscreen,
        widget.isMinimized,
        widget.isPinned,
    )

    private fun textPersistentSignature(widget: TextBoxWidget): List<Any?> = listOf(
        widget.text,
        widget.getHtmlContent(),
        widget.textFontSize,
        widget.isTextWrap,
        widget.getColumnCount(),
    )
}