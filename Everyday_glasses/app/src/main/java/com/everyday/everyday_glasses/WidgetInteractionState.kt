package com.everyday.everyday_glasses

/**
 * Lightweight wrapper for converting BaseWidget hover/drag state into the
 * smaller state enums each widget uses for its own UI.
 */
data class WidgetInteractionState(
    val baseState: BaseWidget.BaseState
) {
    val chromeState: BaseWidget.BaseState
        get() = when (baseState) {
            BaseWidget.BaseState.HOVER_RESIZE,
            BaseWidget.BaseState.RESIZING -> BaseWidget.BaseState.HOVER_BORDER
            else -> baseState
        }

    val isHoveringWidget: Boolean
        get() = baseState != BaseWidget.BaseState.IDLE

    fun <T> toChromeLocal(
        idle: T,
        content: T,
        border: T,
        moving: T
    ): T {
        return when (chromeState) {
            BaseWidget.BaseState.HOVER_CONTENT -> content
            BaseWidget.BaseState.HOVER_BORDER -> border
            BaseWidget.BaseState.MOVING -> moving
            else -> idle
        }
    }
}
