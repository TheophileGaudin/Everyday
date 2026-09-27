package com.everyday.everyday_glasses.ui.hover_controls

import android.content.Context
import android.graphics.Canvas
import com.everyday.everyday_glasses.interaction.assistant.AssistantVoiceHoverControl
import com.everyday.everyday_glasses.interaction.commands.AppCommandRegistry
import com.everyday.everyday_glasses.persistence.ShortcutsStore
import com.everyday.shared.command.AppCommandInvocation
import com.everyday.shared.transport.AssistantStatus

/**
 * The dashboard's hover controls: Close, the assistant's voice button and the lemon shortcuts.
 * They sit above every widget, fullscreen ones included, and give way only to an open menu or
 * popup ([isModalOpen]). Coordinates are in content space, like the widgets'.
 */
class HoverControlsLayer(
    private val context: Context,
    private val commandRegistry: AppCommandRegistry,
    closeApp: () -> Unit,
    toggleAssistantVoice: () -> Unit,
    runShortcut: (AppCommandInvocation) -> Unit,
    private val requestCursorCenter: () -> Unit,
    private val repaint: () -> Unit,
    private val notifyTransientChange: () -> Unit,
    private val isModalOpen: () -> Boolean,
) {
    private companion object {
        const val CONFIRM_RELEASE_TAP_GUARD_MS = 300L
    }

    private val closeAppControl = CloseAppHoverControl(closeApp)
    private val assistantVoiceControl = AssistantVoiceHoverControl(toggleAssistantVoice)

    // Loaded from disk on first use rather than while the dashboard is being built.
    private var lemonShortcuts: MutableList<AppCommandInvocation>? = null
    private val lemonControl = LemonHoverControl(
        commandRegistry = commandRegistry,
        shortcutsProvider = { loadedShortcuts() },
        onActionSelected = runShortcut,
        invalidate = repaint,
    )

    /** In draw order: the last one is drawn on top and gets input first. */
    val controls: List<BaseHoverControl> = listOf(closeAppControl, assistantVoiceControl, lemonControl)

    /** The control that took the current press, until its release. */
    private var capturedControl: BaseHoverControl? = null
    private var tapGuardUntilMs = 0L

    /** True when the controls take hover from the widgets: one is hovered or busy, and no menu or popup is open. */
    val ownsHover: Boolean get() = !isModalOpen() && controls.any { it.isHovered || it.isInteracting() }

    /** True while the control that took the press holds the cursor still (the lemon, while magnified). */
    val freezesCursor: Boolean get() = capturedControl?.freezesCursorWhileInteracting() == true

    /**
     * Places the controls at their default spots on a [width] by [height] dashboard. The hover-controls
     * layout editor then snaps them to its grid as soon as it is built.
     */
    fun onSizeChanged(width: Float, height: Float) {
        closeAppControl.setBounds(
            width - CloseAppHoverControl.DEFAULT_SIZE - CloseAppHoverControl.DEFAULT_MARGIN,
            CloseAppHoverControl.DEFAULT_MARGIN,
            CloseAppHoverControl.DEFAULT_SIZE
        )
        assistantVoiceControl.setBounds(
            width - AssistantVoiceHoverControl.DEFAULT_SIZE,
            height - AssistantVoiceHoverControl.DEFAULT_SIZE,
            AssistantVoiceHoverControl.DEFAULT_SIZE
        )
        lemonControl.setBounds(
            CloseAppHoverControl.DEFAULT_MARGIN,
            height - LemonHoverControl.DEFAULT_SIZE - CloseAppHoverControl.DEFAULT_MARGIN,
            LemonHoverControl.DEFAULT_SIZE
        )
        lemonControl.setScreenSize(width, height)
    }

    // ==================== Input ====================

    /** A press: true when a control took it, and then gets the moves and the release. */
    fun onCursorDown(cx: Float, cy: Float): Boolean {
        if (isModalOpen()) return false
        for (control in controls.asReversed()) {
            if (!control.isPlaced) continue
            if (control.onCursorDown(cx, cy)) {
                capturedControl = control
                repaint()
                return true
            }
        }
        return false
    }

    /** A move during a press: true when the control that took the press used it. */
    fun onCursorMove(cx: Float, cy: Float, dx: Float, dy: Float): Boolean =
        capturedControl?.onCursorMove(cx, cy, dx, dy) == true

    fun onCursorUp(cx: Float, cy: Float) {
        val captured = capturedControl ?: return
        if (captured.onCursorUp(cx, cy) && captured.shouldGuardTapAfterCursorUp()) {
            tapGuardUntilMs = System.currentTimeMillis() + CONFIRM_RELEASE_TAP_GUARD_MS
        }
        capturedControl = null
    }

    /**
     * A control that is still busy (the lemon with a slice selected) gets the tap first, even
     * outside its idle bounds: a tap outside a magnified slice must reach the lemon to cancel it.
     * Right after a release that confirmed something, the tap that follows is swallowed.
     */
    fun onTap(cx: Float, cy: Float): Boolean {
        if (isTapGuardActive()) return true
        for (control in controls.asReversed()) {
            if (control.isInteracting() && control.handleTap(cx, cy)) return true
        }
        if (isModalOpen()) return false
        for (control in controls.asReversed()) {
            if (!control.isPlaced) continue
            if (control.handleTap(cx, cy)) return true
        }
        return false
    }

    fun onDoubleTap(cx: Float, cy: Float): Boolean {
        if (isTapGuardActive()) return true
        for (control in controls.asReversed()) {
            if (control.isInteracting() && control.handleDoubleTap(cx, cy)) {
                notifyTransientChange()
                if (control === lemonControl) requestCursorCenter()
                return true
            }
        }
        return false
    }

    fun cancelInteractions() {
        for (control in controls) control.cancelInteraction()
        capturedControl = null
        tapGuardUntilMs = 0L
    }

    private fun isTapGuardActive(): Boolean {
        if (tapGuardUntilMs <= 0L) return false
        if (System.currentTimeMillis() > tapGuardUntilMs) {
            tapGuardUntilMs = 0L
            return false
        }
        return controls.any { it.isInteracting() }
    }

    // ==================== Hover and drawing ====================

    fun updateHover(cx: Float, cy: Float) = controls.forEach { it.updateHover(cx, cy) }
    fun clearHover() = controls.forEach { it.clearHover() }
    fun draw(canvas: Canvas) = controls.forEach { it.draw(canvas) }

    // ==================== Lemon shortcuts and the assistant button ====================

    fun shortcuts(): List<AppCommandInvocation> = loadedShortcuts().toList()

    /** Keeps the eligible [invocations] once each, up to the lemon's capacity, and saves them. */
    fun applyShortcuts(invocations: List<AppCommandInvocation>) {
        val list = loadedShortcuts()
        list.clear()
        val seen = mutableSetOf<String>()
        for (invocation in invocations) {
            if (invocation.commandId in seen) continue
            val definition = commandRegistry.find(invocation.commandId) ?: continue
            if (!definition.shortcutEligible) continue
            seen.add(invocation.commandId)
            list.add(invocation)
            if (list.size >= AppCommandRegistry.MAX_LEMON_COMMANDS) break
        }
        ShortcutsStore.save(context, commandRegistry, list.toList())
        lemonControl.cancelInteraction()
        notifyTransientChange()
    }

    fun setAssistantStatus(status: AssistantStatus) {
        assistantVoiceControl.configured = status.configured
        assistantVoiceControl.state = status.state
    }

    private fun loadedShortcuts(): MutableList<AppCommandInvocation> =
        lemonShortcuts ?: ShortcutsStore.load(context, commandRegistry).toMutableList().also { lemonShortcuts = it }
}