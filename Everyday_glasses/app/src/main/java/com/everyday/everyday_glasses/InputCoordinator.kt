package com.everyday.everyday_glasses

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import kotlin.math.sqrt

class InputCoordinator(
    private val cursorView: CursorView,
    private val widgetContainer: WidgetContainer,
    private val tapGestureDetectorProvider: () -> TapGestureDetector?,
    private val isPhoneMode: () -> Boolean,
    private val isAwake: () -> Boolean,
    private val wakeDisplay: () -> Unit,
    private val notifyUserActivity: () -> Unit,
    private val notifyTransientChange: () -> Unit,
    private val onTripleTap: () -> Unit
) {
    companion object {
        private const val TAG = "InputCoordinator"

        private const val PHONE_SENSITIVITY = 1.0f
        private const val TEMPLE_SENSITIVITY_X = 1.0f
        private const val TEMPLE_SENSITIVITY_Y = 2.5f
        private const val MIN_MOVEMENT_THRESHOLD = 0.5f
        private const val TAP_MAX_DISTANCE = 50f
        private const val TAP_MAX_DURATION = 300L
        private const val DOUBLE_TAP_TIMEOUT_MS = 300L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastPointerCount = 1
    private var lastTempleX = 0f
    private var lastTempleY = 0f
    private var lastTempleMovementTime = 0L
    private var templeDownX = 0f
    private var templeDownY = 0f
    private var templeDownTime = 0L
    private var templeTotalDistance = 0f
    private var lastTempleTapTime = 0L
    private var pendingTempleTap: Runnable? = null

    fun handlePhoneData(data: RfcommClient.PhoneData) {
        if (!isAwake()) {
            if (data.event == "down" || data.event == "tap" || data.event == "doubletap") {
                Log.d(TAG, "Wake from sleep via phone ${data.event}")
                wakeDisplay()
            }
            return
        }

        when (data.event) {
            "down" -> {
                notifyUserActivity()
                Log.d(TAG, "Phone DOWN at ${cursorView.getCursorX()}, ${cursorView.getCursorY()} pointerCount=${data.pointerCount}")
                handlePointerDown(data.pointerCount.coerceAtLeast(1))
                notifyTransientChange()
            }
            "up" -> {
                Log.d(TAG, "Phone UP at ${cursorView.getCursorX()}, ${cursorView.getCursorY()}")
                handlePointerUp()
            }
            "move" -> {
                notifyUserActivity()
                handlePointerMove(
                    WidgetContainer.InputSource.PHONE_TRACKPAD,
                    data.pointerCount.coerceAtLeast(1),
                    data.dx * PHONE_SENSITIVITY,
                    data.dy * PHONE_SENSITIVITY
                )
            }
            "tap" -> {
                notifyUserActivity()
                cursorView.onActivity()
                Log.d(TAG, "Phone tap at ${cursorView.getCursorX()}, ${cursorView.getCursorY()}")
                val x = cursorView.getCursorX()
                val y = cursorView.getCursorY()
                if (!handleImmediateContextMenuNavigationTap(x, y)) {
                    handleTapAtCoordinates(x, y)
                }
                notifyTransientChange()
            }
            "doubletap" -> {
                notifyUserActivity()
                cursorView.onActivity()
                Log.d(TAG, "Phone double-tap at ${cursorView.getCursorX()}, ${cursorView.getCursorY()}")
                handleDoubleTapAtCoordinates(cursorView.getCursorX(), cursorView.getCursorY())
                notifyTransientChange()
            }
            "tripletap" -> {
                notifyUserActivity()
                cursorView.onActivity()
                Log.d(TAG, "Phone triple-tap")
                onTripleTap()
                notifyTransientChange()
            }
            "pointercount" -> {
                val pointerCount = data.pointerCount.coerceAtLeast(1)
                notifyUserActivity()
                cursorView.onActivity()
                Log.d(TAG, "Phone pointer count changed: $pointerCount")
                handlePointerCountChanged(pointerCount)
                notifyTransientChange()
            }
        }
    }

    fun handleTap() {
        val x = cursorView.getCursorX()
        val y = cursorView.getCursorY()

        if (handleImmediateContextMenuNavigationTap(x, y)) {
            notifyTransientChange()
            return
        }

        tapGestureDetectorProvider()?.onTap(x, y) ?: handleTapAtCoordinates(x, y)
    }

    fun handleDoubleTap() {
        val x = cursorView.getCursorX()
        val y = cursorView.getCursorY()

        tapGestureDetectorProvider()?.onTap(x, y) ?: handleDoubleTapAtCoordinates(x, y)
    }

    fun handleTapAtCoordinates(screenX: Float, screenY: Float) {
        flashCursor()
        widgetContainer.onTap(screenX, screenY)
    }

    fun handleDoubleTapAtCoordinates(screenX: Float, screenY: Float) {
        flashCursor()
        widgetContainer.onDoubleTap(screenX, screenY)
    }

    fun flashCursor() {
        cursorView.setCursorColor(Color.YELLOW)
        cursorView.postDelayed({
            cursorView.setCursorColor(if (isPhoneMode()) Color.WHITE else Color.CYAN)
            notifyTransientChange()
        }, 150)
    }

    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!isAwake()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_HOVER_ENTER -> {
                    Log.d(TAG, "Temple touch detected while sleeping - waking up")
                    wakeDisplay()
                    resetTempleTracking(event.x, event.y)
                }
            }
            return true
        }

        notifyUserActivity()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_HOVER_ENTER -> {
                resetTempleTracking(event.x, event.y)
                Log.d(TAG, "Temple DOWN/ENTER at ($templeDownX, $templeDownY)")
                handlePointerDown(1)
                return true
            }
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_HOVER_MOVE -> {
                handleTempleMove(event.x, event.y, pointerCount = 1)
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_HOVER_EXIT -> {
                val currentTime = System.currentTimeMillis()
                val duration = currentTime - templeDownTime
                val timeSinceLastMove = currentTime - lastTempleMovementTime
                Log.d(TAG, "Temple UP/EXIT - distance: $templeTotalDistance, duration: ${duration}ms, timeSinceLastMove: ${timeSinceLastMove}ms")
                handlePointerUp()
                if (templeTotalDistance < TAP_MAX_DISTANCE && duration < TAP_MAX_DURATION) {
                    Log.d(TAG, "Temple TAP detected")
                    handleTempleTapDetected()
                }
                return true
            }
        }
        return false
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isAwake()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                Log.d(TAG, "Touch detected while sleeping - waking up")
                wakeDisplay()
                resetTempleTracking(event.x, event.y)
            }
            return true
        }

        notifyUserActivity()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetTempleTracking(event.x, event.y)
                Log.d(TAG, "Trackpad DOWN at ($templeDownX, $templeDownY) pointerCount=${event.pointerCount}")
                handlePointerDown(event.pointerCount)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                Log.d(TAG, "Second finger down - entering scroll mode, pointerCount=${event.pointerCount}")
                handlePointerCountChanged(event.pointerCount)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                handleTempleMove(event.x, event.y, event.pointerCount)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val newPointerCount = event.pointerCount - 1
                Log.d(TAG, "Finger up - exiting scroll mode, newPointerCount=$newPointerCount")
                handlePointerCountChanged(newPointerCount)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val currentTime = System.currentTimeMillis()
                val duration = currentTime - templeDownTime
                handlePointerUp()
                if (templeTotalDistance < TAP_MAX_DISTANCE && duration < TAP_MAX_DURATION) {
                    handleTempleTapDetected()
                }
                return true
            }
        }
        return false
    }

    fun release() {
        pendingTempleTap?.let { handler.removeCallbacks(it) }
        pendingTempleTap = null
    }

    private fun handlePointerDown(pointerCount: Int) {
        cursorView.onActivity()
        lastPointerCount = pointerCount
        widgetContainer.onCursorDown(cursorView.getCursorX(), cursorView.getCursorY(), pointerCount)
    }

    private fun handlePointerMove(source: WidgetContainer.InputSource, pointerCount: Int, dx: Float, dy: Float) {
        if (pointerCount != lastPointerCount) {
            handlePointerCountChanged(pointerCount)
        }
        if (pointerCount >= 2) {
            widgetContainer.scrollWidgetAtCursor(cursorView.getCursorX(), cursorView.getCursorY(), dy)
            cursorView.onActivity()
        } else {
            val isScrollbarDrag = widgetContainer.isScrollbarDragActive()
            if (isScrollbarDrag && !widgetContainer.isDragging()) {
                cursorView.moveCursor(dx, 0f)
                cursorView.onActivity()
                widgetContainer.updateCursor(
                    cursorView.getCursorX(),
                    cursorView.getCursorY(),
                    cursorView.isCursorVisible()
                )
                widgetContainer.getScrollbarDragWidget()?.onScroll(dy)
            } else {
                val (newX, newY) = widgetContainer.onMove(dx, dy, source)
                cursorView.setCursorPosition(newX, newY)
                cursorView.onActivity()
            }
        }
        notifyTransientChange()
    }

    private fun handlePointerUp() {
        cursorView.onActivity()
        widgetContainer.onCursorUp(cursorView.getCursorX(), cursorView.getCursorY())
        lastPointerCount = 1
        notifyTransientChange()
    }

    private fun handlePointerCountChanged(pointerCount: Int) {
        lastPointerCount = pointerCount
        widgetContainer.onPointerCountChanged(pointerCount)
    }

    private fun handleImmediateContextMenuNavigationTap(screenX: Float, screenY: Float): Boolean {
        if (!widgetContainer.onContextMenuNavigationTap(screenX, screenY)) return false

        flashCursor()
        tapGestureDetectorProvider()?.reset()
        return true
    }

    private fun handleTempleMove(x: Float, y: Float, pointerCount: Int) {
        val rawDx = x - lastTempleX
        val rawDy = y - lastTempleY
        val dx = rawDx * TEMPLE_SENSITIVITY_X
        val dy = rawDy * TEMPLE_SENSITIVITY_Y
        templeTotalDistance += sqrt(rawDx * rawDx + rawDy * rawDy)
        lastTempleX = x
        lastTempleY = y
        lastTempleMovementTime = System.currentTimeMillis()
        val movementMagnitude = sqrt(dx * dx + dy * dy)
        if (movementMagnitude >= MIN_MOVEMENT_THRESHOLD) {
            handlePointerMove(WidgetContainer.InputSource.GLASSES_TEMPLE, pointerCount, dx, dy)
        }
    }

    private fun handleTempleTapDetected() {
        val x = cursorView.getCursorX()
        val y = cursorView.getCursorY()

        if (handleImmediateContextMenuNavigationTap(x, y)) {
            notifyTransientChange()
            return
        }

        val detector = tapGestureDetectorProvider()
        if (detector != null) {
            detector.onTap(x, y)
        } else {
            handleTempleTapFallback(x, y)
        }

        notifyTransientChange()
    }

    private fun handleTempleTapFallback(x: Float, y: Float) {
        val currentTime = System.currentTimeMillis()
        val timeSinceLastTap = currentTime - lastTempleTapTime

        pendingTempleTap?.let { handler.removeCallbacks(it) }
        pendingTempleTap = null

        if (timeSinceLastTap < DOUBLE_TAP_TIMEOUT_MS) {
            Log.d(TAG, "Temple double-tap confirmed (fallback)")
            lastTempleTapTime = 0L
            handleDoubleTapAtCoordinates(x, y)
        } else {
            lastTempleTapTime = currentTime
            pendingTempleTap = Runnable {
                Log.d(TAG, "Temple single tap confirmed (fallback)")
                handleTapAtCoordinates(x, y)
                pendingTempleTap = null
            }
            handler.postDelayed(pendingTempleTap!!, DOUBLE_TAP_TIMEOUT_MS)
        }
    }

    private fun resetTempleTracking(x: Float, y: Float) {
        lastTempleX = x
        lastTempleY = y
        templeDownX = x
        templeDownY = y
        templeDownTime = System.currentTimeMillis()
        templeTotalDistance = 0f
    }
}
