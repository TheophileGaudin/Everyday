package com.everyday.everyday_glasses.runtime

import com.everyday.everyday_glasses.widgets._common.BaseWidget

/**
 * Cumulative change notifications emitted by widgets.
 *
 * Every widget reports through one instance of this interface, installed on
 * [BaseWidget.changeListener] by [WidgetLifecycleController] when the widget joins the
 * dashboard. Transient is visible runtime state that must be redrawn and mirrored; persistent
 * includes the transient work plus a debounced dashboard save.
 */
interface WidgetChangeListener {
    fun onTransientStateChanged() = Unit
    fun onPersistentStateChanged() = Unit
}

/**
 * Small platform-neutral dispatcher used to keep cumulative notification semantics explicit.
 * A persistent change includes the repaint and transient work exactly once before its save hook.
 */
class WidgetChangeDispatcher(
    private val repaint: () -> Unit,
    private val transient: () -> Unit,
    private val persistent: () -> Unit
) {
    fun requestRepaint() = repaint()

    fun notifyTransientStateChanged() {
        repaint()
        transient()
    }

    fun notifyPersistentStateChanged() {
        notifyTransientStateChanged()
        persistent()
    }
}

