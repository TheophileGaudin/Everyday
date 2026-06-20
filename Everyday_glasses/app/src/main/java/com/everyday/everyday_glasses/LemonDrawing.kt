package com.everyday.everyday_glasses

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Procedural lemon-slice icon, transcribed from the matplotlib reference. Used by the
 * lemon hover control, the shortcuts-settings entry point and the in-menu preview.
 *
 * All geometry is expressed in fractions of the bounding-box radius so the same code
 * works at any size.
 */
object LemonDrawing {

    // Colours from the reference.
    private val COLOR_RIND = Color.parseColor("#FFD400")
    private val COLOR_WHITE_RING = Color.WHITE
    private val COLOR_GOLD_RING = Color.parseColor("#D8B900")
    private val COLOR_SEGMENT = Color.parseColor("#FFD51F")
    private val COLOR_SEGMENT_SELECTED = Color.parseColor("#FFF06A")
    private val COLOR_SEGMENT_BORDER = Color.WHITE
    private val COLOR_RIND_BORDER = Color.parseColor("#40000000")
    private val COLOR_SEED = Color.WHITE
    private val COLOR_SEED_BORDER = Color.parseColor("#73000000")
    private val COLOR_PITH = Color.WHITE
    private val COLOR_PITH_INNER_BORDER = Color.parseColor("#D2D2D2")

    // Radii as fractions of the bounding-box radius.
    private const val OUTER_R = 1.00f
    private const val MID_R = 0.935f      // white pith disc inside the rind
    private const val GOLD_RING_R = 0.875f
    private const val GOLD_RING_W_FRAC = 9f / 220f       // matplotlib lw=9 at ~size 220
    private const val WHITE_RING_R = 0.805f
    private const val WHITE_RING_W_FRAC = 18f / 220f     // matplotlib lw=18
    private const val SEGMENT_OUTER_R = 0.755f
    private const val SEGMENT_INNER_R = 0.115f
    private const val SEED_RING_R = 0.42f
    private const val SEED_LENGTH_FRAC = 0.075f
    private const val SEED_WIDTH_FRAC = 0.032f
    private const val PITH_OUTER_R = 0.105f
    private const val PITH_INNER_R = 0.08f

    private fun gapDegFor(n: Int): Float = when {
        n <= 4 -> 5f
        n <= 8 -> 3.5f
        n <= 12 -> 2.5f
        else -> 1.5f
    }

    fun draw(canvas: Canvas, bounds: RectF, slices: Int, selectedIndex: Int = -1) {
        if (slices <= 0) return
        val n = slices.coerceAtLeast(1)
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val r = min(bounds.width(), bounds.height()) / 2f
        if (r <= 0f) return

        val outerR = r * OUTER_R
        val midR = r * MID_R
        val goldR = r * GOLD_RING_R
        val whiteR = r * WHITE_RING_R
        val segOuterR = r * SEGMENT_OUTER_R
        val segInnerR = r * SEGMENT_INNER_R

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        // Outer rind.
        fill.color = COLOR_RIND
        canvas.drawCircle(cx, cy, outerR, fill)
        stroke.color = COLOR_RIND_BORDER
        stroke.strokeWidth = max(1f, r * 0.012f)
        canvas.drawCircle(cx, cy, outerR, stroke)

        // White pith disc inside the rind.
        fill.color = COLOR_WHITE_RING
        canvas.drawCircle(cx, cy, midR, fill)

        // Gold ring at 0.875.
        stroke.color = COLOR_GOLD_RING
        stroke.strokeWidth = max(1.5f, r * GOLD_RING_W_FRAC)
        canvas.drawCircle(cx, cy, goldR, stroke)

        // Wide white ring at 0.805 — softens the inner rind transition.
        stroke.color = COLOR_WHITE_RING
        stroke.strokeWidth = max(2f, r * WHITE_RING_W_FRAC)
        canvas.drawCircle(cx, cy, whiteR, stroke)

        // Segments (annulus wedges).
        val gapDeg = gapDegFor(n)
        val anglePer = 360f / n
        val outerRect = RectF(cx - segOuterR, cy - segOuterR, cx + segOuterR, cy + segOuterR)
        val innerRect = RectF(cx - segInnerR, cy - segInnerR, cx + segInnerR, cy + segInnerR)
        val segStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = COLOR_SEGMENT_BORDER
            strokeWidth = max(1.2f, r * 2f / 220f)
        }
        val segFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        for (i in 0 until n) {
            val startAngle = -90f + i * anglePer + gapDeg / 2f - anglePer / 2f
            val sweep = anglePer - gapDeg
            val path = Path().apply {
                arcTo(outerRect, startAngle, sweep, true)
                arcTo(innerRect, startAngle + sweep, -sweep, false)
                close()
            }
            segFill.color = if (i == selectedIndex) COLOR_SEGMENT_SELECTED else COLOR_SEGMENT
            canvas.drawPath(path, segFill)
            canvas.drawPath(path, segStroke)
        }

        // Seeds on odd-indexed slices (only while there is room).
        if (n <= 10) {
            val seedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = COLOR_SEED
            }
            val seedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = COLOR_SEED_BORDER
                strokeWidth = max(0.6f, r * 0.8f / 220f)
            }
            for (i in 0 until n) {
                if (i % 2 == 0) continue
                val midAngleDeg = -90f + i * anglePer
                drawSeed(canvas, cx, cy, midAngleDeg, r * SEED_RING_R, r, seedFill, seedStroke)
            }
        }

        // Central pith.
        fill.color = COLOR_PITH
        canvas.drawCircle(cx, cy, r * PITH_OUTER_R, fill)
        canvas.drawCircle(cx, cy, r * PITH_INNER_R, fill)
        stroke.color = COLOR_PITH_INNER_BORDER
        stroke.strokeWidth = max(0.6f, r * 0.6f / 220f)
        canvas.drawCircle(cx, cy, r * PITH_INNER_R, stroke)
    }

    private fun drawSeed(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        angleDeg: Float,
        ringR: Float,
        scaleR: Float,
        fill: Paint,
        stroke: Paint
    ) {
        val a = Math.toRadians(angleDeg.toDouble())
        val sx = cx + cos(a).toFloat() * ringR
        val sy = cy + sin(a).toFloat() * ringR
        val length = scaleR * SEED_LENGTH_FRAC
        val width = scaleR * SEED_WIDTH_FRAC

        val rotation = angleDeg + 90f
        val rad = Math.toRadians(rotation.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        fun tx(lx: Float, ly: Float, out: FloatArray) {
            out[0] = sx + lx * cosA - ly * sinA
            out[1] = sy + lx * sinA + ly * cosA
        }

        val pts = Array(7) { FloatArray(2) }
        // Same control structure as the matplotlib teardrop.
        val verts = arrayOf(
            floatArrayOf(0f, -length / 2f),
            floatArrayOf(width, -length * 0.20f),
            floatArrayOf(width * 0.75f, length * 0.25f),
            floatArrayOf(0f, length / 2f),
            floatArrayOf(-width * 0.75f, length * 0.25f),
            floatArrayOf(-width, -length * 0.20f),
            floatArrayOf(0f, -length / 2f)
        )
        for (i in verts.indices) tx(verts[i][0], verts[i][1], pts[i])

        val path = Path().apply {
            moveTo(pts[0][0], pts[0][1])
            cubicTo(pts[1][0], pts[1][1], pts[2][0], pts[2][1], pts[3][0], pts[3][1])
            cubicTo(pts[4][0], pts[4][1], pts[5][0], pts[5][1], pts[6][0], pts[6][1])
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, stroke)
    }
}
