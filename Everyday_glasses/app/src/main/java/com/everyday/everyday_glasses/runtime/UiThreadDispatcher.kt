package com.everyday.everyday_glasses.runtime

/** Runs a widget mutation now on the UI thread, or posts it there from another thread. */
fun interface UiThreadDispatcher {
    /** False means the view rejected the post and the action will never run. */
    fun dispatch(action: () -> Unit): Boolean
}