package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.abs

/**
 * A hover control shaped like a lemon slice that exposes user-configurable shortcuts.
 *
 * Interaction model:
 * 1. Press inside the resting (small) lemon → the lemon magnifies to roughly 3× its base
 *    size. A first slice becomes the "selected" one.
 * 2. Slide while still pressed → selection rotates around the lemon. Direction and velocity
 *    of the slide determine how many slices are skipped: a fast flick to the right advances
 *    multiple steps clockwise, a slow drag advances one step at a time. Vertical motion is
 *    ignored.
 * 3. Release → the selection locks in; the lemon stays magnified.
 * 4. Tap inside the highlighted slice → the matching [ShortcutAction] runs and the lemon
 *    collapses back. Tap anywhere else → the lemon collapses without running anything.
 */
class LemonHoverControl(
    private val shortcutsProvider: () -> List<ShortcutAction>,
    private val onActionSelected: (ShortcutAction) -> Unit,
    private val invalidate: () -> Unit
) : BaseHoverControl(ID, "Lemon shortcuts", DEFAULT_SIZE) {

    companion object {
        const val ID = "lemon_shortcuts"
        const val DEFAULT_SIZE = 96f
        const val MAGNIFY_SCALE = 3f

        /** Horizontal distance the cursor must travel to advance one slice at base speed. */
        private const val STEP_DISTANCE_PX = 70f

        /** Slide speed (px/ms) treated as "slow" — base advance rate (1×). */
        private const val SLOW_SPEED_PX_PER_MS = 0.2f

        /** Slide speed (px/ms) treated as "fast" — caps velocity multiplier. */
        private const val FAST_SPEED_PX_PER_MS = 1.6f

        /** Maximum velocity multiplier applied to slide accumulation. */
        private const val MAX_VELOCITY_FACTOR = 4f

        /** Ignore the tap that some input paths emit from the same release that selected a slice. */
        private const val CONFIRM_TAP_GUARD_MS = 300L
    }

    private enum class State { IDLE, SELECTING, CONFIRMING }

    private var state: State = State.IDLE
    private var selectedIndex: Int = 0
    private var slideAccum: Float = 0f
    private var lastMoveTimeMs: Long = 0L
    private var confirmingStartedAtMs: Long = 0L
    private var guardTapAfterCursorUp: Boolean = false

    private var screenWidth: Float = Float.MAX_VALUE
    private var screenHeight: Float = Float.MAX_VALUE

    private val labelBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#EFFFF6BF")
        style = Paint.Style.FILL
    }
    private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B07B6800")
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2300")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val confirmationBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#F4FFFBE6")
        style = Paint.Style.FILL
    }
    private val confirmationBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD400")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val confirmationTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2B2300")
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val confirmationHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5F5200")
        textAlign = Paint.Align.CENTER
    }

    fun setScreenSize(w: Float, h: Float) {
        screenWidth = w
        screenHeight = h
    }

    override fun isInteracting(): Boolean = state != State.IDLE

    override fun freezesCursorWhileInteracting(): Boolean = state == State.SELECTING

    override fun cancelInteraction() {
        if (state != State.IDLE) {
            state = State.IDLE
            slideAccum = 0f
            selectedIndex = 0
            confirmingStartedAtMs = 0L
            guardTapAfterCursorUp = false
            invalidate()
        }
    }

    // ==================== Geometry ====================

    private fun magnifiedRect(out: RectF = RectF()): RectF {
        val baseCx = bounds.centerX()
        val baseCy = bounds.centerY()
        val targetSize = size * MAGNIFY_SCALE
        val half = targetSize / 2f
        var left = baseCx - half
        var top = baseCy - half
        val margin = 4f
        if (left + targetSize > screenWidth - margin) left = screenWidth - margin - targetSize
        if (top + targetSize > screenHeight - margin) top = screenHeight - margin - targetSize
        if (left < margin) left = margin
        if (top < margin) top = margin
        // If the screen is smaller than the target, just clamp to the available space.
        val w = minOf(targetSize, (screenWidth - 2f * margin).coerceAtLeast(0f))
        val h = minOf(targetSize, (screenHeight - 2f * margin).coerceAtLeast(0f))
        out.set(left, top, left + w, top + h)
        return out
    }

    private fun currentDrawRect(out: RectF = RectF()): RectF {
        return if (state == State.IDLE) {
            out.set(bounds)
            out
        } else {
            magnifiedRect(out)
        }
    }

    private fun slicesCount(): Int =
        shortcutsProvider().size.coerceIn(1, ShortcutAction.MAX_LEMON_SLICES)

    // ==================== Hit-testing ====================

    override fun containsPoint(px: Float, py: Float): Boolean {
        return if (state == State.IDLE) {
            bounds.contains(px, py)
        } else {
            currentDrawRect().contains(px, py)
        }
    }

    // ==================== Drawing ====================

    override fun draw(canvas: Canvas) {
        if (!isPlaced) return
        when (state) {
            State.IDLE -> if (isHovered) drawLemon(canvas, bounds, -1, drawLabels = false)
            State.SELECTING, State.CONFIRMING -> drawLemon(canvas, currentDrawRect(), selectedIndex, drawLabels = true)
        }
    }

    override fun drawForEditor(canvas: Canvas) {
        drawLemon(canvas, bounds, -1, drawLabels = false)
    }

    override fun drawHovered(canvas: Canvas) {
        drawLemon(canvas, bounds, -1, drawLabels = false)
    }

    private fun drawLemon(canvas: Canvas, rect: RectF, selected: Int, drawLabels: Boolean) {
        val n = slicesCount()
        LemonDrawing.draw(canvas, rect, n, selected.coerceAtMost(n - 1))
        if (drawLabels) drawSliceLabels(canvas, rect, n)
        if (state == State.CONFIRMING) drawConfirmationPopup(canvas, rect)
    }

    private fun drawSliceLabels(canvas: Canvas, rect: RectF, n: Int) {
        val actions = shortcutsProvider().take(n)
        if (actions.isEmpty()) return
        val cx = rect.centerX()
        val cy = rect.centerY()
        val r = minOf(rect.width(), rect.height()) / 2f
        val labelR = r * 0.46f  // mid-radius of the wedge band
        val anglePer = 360.0 / n
        val textSize = (r * 0.10f).coerceIn(11f, 22f)
        labelPaint.textSize = textSize
        val maxWidth = r * 0.50f
        val metrics = labelPaint.fontMetrics
        val padX = (textSize * 0.36f).coerceAtLeast(4f)
        val padY = (textSize * 0.18f).coerceAtLeast(3f)

        for (i in 0 until n) {
            val angleDeg = -90.0 + i * anglePer
            val a = Math.toRadians(angleDeg)
            val tx = cx + (Math.cos(a) * labelR).toFloat()
            val ty = cy + (Math.sin(a) * labelR).toFloat() + textSize * 0.35f
            val text = shortLabel(actions[i])
            val truncated = fitText(text, labelPaint, maxWidth)
            val textWidth = labelPaint.measureText(truncated)
            val badge = RectF(
                tx - textWidth / 2f - padX,
                ty + metrics.ascent - padY,
                tx + textWidth / 2f + padX,
                ty + metrics.descent + padY
            )
            canvas.drawRoundRect(badge, textSize * 0.35f, textSize * 0.35f, labelBackgroundPaint)
            canvas.drawRoundRect(badge, textSize * 0.35f, textSize * 0.35f, labelBorderPaint)
            canvas.drawText(truncated, tx, ty, labelPaint)
        }
    }

    private fun drawConfirmationPopup(canvas: Canvas, lemonRect: RectF) {
        val action = shortcutsProvider().getOrNull(selectedIndex) ?: return
        val label = shortLabel(action)
        val availableWidth = (screenWidth - 16f).coerceAtLeast(120f)
        val popupWidth = minOf(availableWidth, maxOf(188f, lemonRect.width() * 0.78f))
        val popupHeight = 58f
        val gap = 8f
        var left = lemonRect.centerX() - popupWidth / 2f
        var top = lemonRect.top - popupHeight - gap
        if (top < 8f) top = lemonRect.bottom + gap
        if (top + popupHeight > screenHeight - 8f) top = screenHeight - popupHeight - 8f
        val maxLeft = (screenWidth - popupWidth - 8f).coerceAtLeast(8f)
        left = left.coerceIn(8f, maxLeft)
        val rect = RectF(left, top, left + popupWidth, top + popupHeight)

        canvas.drawRoundRect(rect, 8f, 8f, confirmationBackgroundPaint)
        canvas.drawRoundRect(rect, 8f, 8f, confirmationBorderPaint)

        confirmationTitlePaint.textSize = 17f
        confirmationHintPaint.textSize = 12f
        val title = fitText("Run $label?", confirmationTitlePaint, popupWidth - 20f)
        canvas.drawText(title, rect.centerX(), rect.top + 23f, confirmationTitlePaint)
        canvas.drawText("Tap to run. Double tap to cancel.", rect.centerX(), rect.top + 43f, confirmationHintPaint)
    }

    private fun shortLabel(action: ShortcutAction): String = when (action) {
        is ShortcutAction.Layout -> action.name
        ShortcutAction.CreateText -> "Text"
        ShortcutAction.CreateBrowser -> "Browser"
        ShortcutAction.OpenYouTubeHistory -> "YouTube"
        ShortcutAction.ToggleStatus -> "System"
        ShortcutAction.ToggleLocation -> "Weather"
        ShortcutAction.ToggleCalendar -> "Calendar"
        ShortcutAction.ToggleFinance -> "Finance"
        ShortcutAction.ToggleNews -> "News"
        ShortcutAction.ToggleSpeedometer -> "Speed"
        ShortcutAction.ToggleSubtitle -> "Subs"
        ShortcutAction.ToggleMirror -> "Mirror"
    }

    private fun fitText(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var trimmed = text
        while (trimmed.length > 1 && paint.measureText("$trimmed…") > maxWidth) {
            trimmed = trimmed.dropLast(1)
        }
        return "$trimmed…"
    }

    // ==================== Input ====================

    override fun onCursorDown(px: Float, py: Float): Boolean {
        if (!isPlaced) return false
        return when (state) {
            State.IDLE -> {
                if (!bounds.contains(px, py)) return false
                state = State.SELECTING
                slideAccum = 0f
                selectedIndex = 0
                lastMoveTimeMs = System.currentTimeMillis()
                guardTapAfterCursorUp = false
                invalidate()
                true
            }
            State.SELECTING -> true
            State.CONFIRMING -> true
        }
    }

    override fun onCursorMove(px: Float, py: Float, dx: Float, dy: Float): Boolean {
        if (state == State.CONFIRMING) return true
        if (state != State.SELECTING) return false
        val now = System.currentTimeMillis()
        val dt = (now - lastMoveTimeMs).coerceAtLeast(1L)
        lastMoveTimeMs = now

        val speed = abs(dx) / dt.toFloat()
        val factor = velocityFactor(speed)
        slideAccum += dx * factor

        val n = slicesCount()
        while (slideAccum >= STEP_DISTANCE_PX) {
            slideAccum -= STEP_DISTANCE_PX
            selectedIndex = ((selectedIndex + 1) % n + n) % n
        }
        while (slideAccum <= -STEP_DISTANCE_PX) {
            slideAccum += STEP_DISTANCE_PX
            selectedIndex = ((selectedIndex - 1) % n + n) % n
        }
        invalidate()
        return true
    }

    override fun onCursorUp(px: Float, py: Float): Boolean {
        return when (state) {
            State.SELECTING -> {
                state = State.CONFIRMING
                confirmingStartedAtMs = System.currentTimeMillis()
                guardTapAfterCursorUp = true
                invalidate()
                true
            }
            State.CONFIRMING -> {
                guardTapAfterCursorUp = false
                true
            }
            State.IDLE -> false
        }
    }

    override fun shouldGuardTapAfterCursorUp(): Boolean {
        val shouldGuard = guardTapAfterCursorUp
        guardTapAfterCursorUp = false
        return shouldGuard
    }

    override fun handleTap(px: Float, py: Float): Boolean {
        if (state == State.CONFIRMING) {
            if (System.currentTimeMillis() - confirmingStartedAtMs < CONFIRM_TAP_GUARD_MS) return true
            runSelectedAction()
            reset()
            return true
        }
        if (state == State.SELECTING) {
            reset()
            return true
        }
        // IDLE: defer to default (no-op since performAction is empty).
        if (!bounds.contains(px, py)) return false
        // Tap on the idle lemon — treat as a press-release: enter confirming with the
        // first slice highlighted so the user can still pick something with a second tap.
        state = State.CONFIRMING
        selectedIndex = 0
        guardTapAfterCursorUp = false
        invalidate()
        return true
    }

    override fun handleDoubleTap(px: Float, py: Float): Boolean {
        if (state == State.IDLE) return false
        reset()
        return true
    }

    private fun runSelectedAction() {
        val actions = shortcutsProvider()
        if (actions.isEmpty()) return
        val idx = selectedIndex.coerceIn(0, actions.size - 1)
        onActionSelected(actions[idx])
    }

    private fun reset() {
        state = State.IDLE
        slideAccum = 0f
        selectedIndex = 0
        confirmingStartedAtMs = 0L
        guardTapAfterCursorUp = false
        invalidate()
    }

    private fun velocityFactor(speedPxPerMs: Float): Float {
        if (speedPxPerMs <= SLOW_SPEED_PX_PER_MS) return 1f
        val range = (FAST_SPEED_PX_PER_MS - SLOW_SPEED_PX_PER_MS).coerceAtLeast(1e-3f)
        val t = ((speedPxPerMs - SLOW_SPEED_PX_PER_MS) / range).coerceIn(0f, 1f)
        return 1f + t * (MAX_VELOCITY_FACTOR - 1f)
    }
}
