package com.everyday.everyday_glasses.runtime

/**
 * Coalesces container-level redraw, transient and persistent notifications.
 * It's about telling or not telling MainActivity about state updates (transient, not needed, persistent, needed)
 * The strongest change requested inside a batch is emitted once when the outer batch ends.
 */
internal class DashboardChangeCoordinator(
    private val repaint: () -> Unit,
    private val transient: () -> Unit,
    private val persistent: () -> Unit,
    private val restored: () -> Unit,
    private val notificationsBlocked: () -> Boolean = { false },
) {
    private enum class Change { NONE, TRANSIENT, PERSISTENT }

    private var suppressionDepth = 0
    private var batchDepth = 0
    private var pendingChange = Change.NONE
    private var generation = 0L

    val notificationGeneration: Long get() = generation
    val isSuppressed: Boolean get() = suppressionDepth > 0

    fun requestRepaint() = repaint()

    fun notifyTransient() {
        request(Change.TRANSIENT)
    }

    fun notifyPersistent() {
        request(Change.PERSISTENT)
    }

    /** Restore is deliberately not a persistent change: loading saved data must not save it again. */
    fun notifyDashboardRestored() {
        repaint()
        restored()
    }

    fun <T> withNotificationsSuppressed(action: () -> T): T {
        suppressionDepth++
        return try {
            action()
        } finally {
            suppressionDepth--
        }
    }

    fun <T> batch(action: () -> T): T {
        batchDepth++
        return try {
            action()
        } finally {
            batchDepth--
            if (batchDepth == 0) flushPendingChange()
        }
    }

    /** Widget callbacks during the action are discarded; the measured result classifies it once. */
    fun <T> runPersistentMutation(action: () -> T, changed: (T) -> Boolean): T {
        val result = withNotificationsSuppressed(action)
        if (changed(result)) notifyPersistent()
        return result
    }

    fun runClassifiedInput(action: () -> Boolean, changed: () -> Boolean): Boolean {
        var persistentChange = false
        val handled = runPersistentMutation(
            action = { action().also { persistentChange = changed() } },
            changed = { it && persistentChange },
        )
        if (handled && !persistentChange) notifyTransient()
        return handled
    }

    private fun request(change: Change) {
        if (isSuppressed || notificationsBlocked()) return
        if (batchDepth > 0) {
            if (change.ordinal > pendingChange.ordinal) pendingChange = change
            return
        }
        emit(change)
    }

    private fun flushPendingChange() {
        val change = pendingChange
        pendingChange = Change.NONE
        if (change != Change.NONE) request(change)
    }

    private fun emit(change: Change) {
        when (change) {
            Change.NONE -> Unit
            Change.TRANSIENT -> {
                repaint()
                generation++
                transient()
            }
            Change.PERSISTENT -> {
                repaint()
                generation++
                persistent()
            }
        }
    }
}