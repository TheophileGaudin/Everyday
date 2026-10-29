package com.everyday.everyday_glasses.interaction.user_input

enum class UserInputKind {
    GLASSES_DOWN,
    GLASSES_MOVE,
    GLASSES_UP,
    PHONE_DOWN,
    PHONE_MOVE,
    PHONE_UP,
    TAP,
    DOUBLE_TAP,
    SCROLL,
    LONG_PRESS,
    DRAG,
    KEYBOARD,
    SHORTCUT
}

/**
 * The single glasses-side boundary for genuine user interaction.
 *
 * Assistant-generated commands, sensor updates, keepalives, and layout/drawing work do not use
 * this entry point. The presentation controller therefore cancels only when a real control
 * event is about to be dispatched.
 */
class UserInputActivity(private val onUserInput: (UserInputKind) -> Unit) {
    fun onUserInput(kind: UserInputKind) = onUserInput.invoke(kind)
}
