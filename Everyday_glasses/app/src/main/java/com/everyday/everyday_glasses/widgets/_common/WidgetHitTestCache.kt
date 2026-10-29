package com.everyday.everyday_glasses.widgets._common

/** Caches only membership/z-order; geometry and eligibility are evaluated per hit test. */
internal class WidgetHitTestCache<T>(
    private val items: () -> List<T>,
    private val zOrder: (T) -> Int
) {
    private var topmostFirst: List<T>? = null

    fun findFirstEligible(isEligible: (T) -> Boolean): T? {
        // Match the previous stable ascending sort followed by reversal, including
        // its tie behavior for widgets that temporarily share a z-order.
        val ordered = topmostFirst ?: items().sortedBy(zOrder).asReversed().also {
            topmostFirst = it
        }
        return ordered.firstOrNull(isEligible)
    }

    fun invalidate() {
        topmostFirst = null
    }
}
