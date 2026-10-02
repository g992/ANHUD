package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** The traffic bar from Navigator's ProgressView, split into a softened bar and the position on it. */
class JamsBar(val bar: Bitmap, val progress: Float?)

object JamsBarParser {
    private const val OPAQUE_ALPHA = 128
    private const val SOFT_MIN_WIDTH = 32
    private const val SOFT_MIN_HEIGHT = 3

    /**
     * Navigator draws its position arrow taller than the bar, so the arrow is found as the columns
     * with opaque pixels above or below the bar band. The arrow is cut out (its columns take the bar
     * colour next to it), the band is cropped and softened; the arrow is redrawn by [JamsBarView].
     */
    fun parse(source: Bitmap): JamsBar? {
        val w = source.width
        val h = source.height
        if (w < 8 || h < 3 || h > w) return null
        val px = IntArray(w * h)
        source.getPixels(px, 0, w, 0, 0, w, h)
        fun opaque(x: Int, y: Int) = (px[y * w + x] ushr 24) >= OPAQUE_ALPHA

        // Bar band: rows opaque in most columns (the arrow covers only a few).
        var bandTop = -1
        var bandBottom = -1
        for (y in 0 until h) {
            var count = 0
            for (x in 0 until w) if (opaque(x, y)) count++
            if (count * 2 >= w) {
                if (bandTop < 0) bandTop = y
                bandBottom = y
            }
        }
        if (bandTop < 0) return null

        var arrowLeft = -1
        var arrowRight = -1
        for (x in 0 until w) {
            var outside = false
            for (y in 0 until h) {
                if ((y < bandTop || y > bandBottom) && opaque(x, y)) {
                    outside = true
                    break
                }
            }
            if (outside) {
                if (arrowLeft < 0) arrowLeft = x
                arrowRight = x
            }
        }

        val bandHeight = bandBottom - bandTop + 1
        val band = IntArray(w * bandHeight)
        for (y in 0 until bandHeight) {
            System.arraycopy(px, (bandTop + y) * w, band, y * w, w)
        }
        var progress: Float? = null
        if (arrowLeft >= 0) {
            val fill = if (arrowRight + 1 < w) arrowRight + 1 else arrowLeft - 1
            if (fill in 0 until w) {
                for (y in 0 until bandHeight) {
                    val color = band[y * w + fill]
                    for (x in arrowLeft..arrowRight) band[y * w + x] = color
                }
            }
            // Navigator puts the arrow's left edge a fifth of its width before the progress point.
            progress = ((arrowLeft + (arrowRight - arrowLeft + 1) / 5f) / w).coerceIn(0f, 1f)
        }
        val cropped = Bitmap.createBitmap(band, w, bandHeight, Bitmap.Config.ARGB_8888)
        return JamsBar(soften(cropped), progress)
    }

    /** Halving averages neighbouring colours; the bilinear upscale then turns steps into gradients. */
    private fun soften(source: Bitmap): Bitmap {
        var current = source
        while (current.width / 2 >= SOFT_MIN_WIDTH || current.height / 2 >= SOFT_MIN_HEIGHT) {
            val w = if (current.width / 2 >= SOFT_MIN_WIDTH) current.width / 2 else current.width
            val h = if (current.height / 2 >= SOFT_MIN_HEIGHT) current.height / 2 else current.height
            val next = Bitmap.createScaledBitmap(current, w, h, true)
            if (current !== source) current.recycle()
            current = next
        }
        return current
    }
}

/** Softened traffic bar centred vertically, with a position arrow as tall as the view. */
class JamsBarView(context: Context) : View(context) {
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val arrowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = Color.BLACK
    }
    private val arrowPath = Path()
    private val barDst = RectF()
    private val barSrc = Rect()

    private var jams: JamsBar? = null

    /** Thickness of the bar itself; the rest of the height is room for the arrow. */
    var barHeightPx: Int = 0
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    fun setJams(value: JamsBar?) {
        if (value === jams) return
        jams = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val current = jams ?: return
        val viewW = width.toFloat()
        val viewH = height.toFloat()
        if (viewW <= 0f || viewH <= 0f) return
        val barH = (if (barHeightPx > 0) barHeightPx.toFloat() else viewH).coerceAtMost(viewH)
        val arrowH = viewH
        val arrowW = arrowH * ARROW_ASPECT
        // Keep the whole arrow inside the view at both ends.
        val barLeft = arrowW / 5f
        val barRight = viewW - arrowW * 4f / 5f
        if (barRight <= barLeft) return
        val top = (viewH - barH) / 2f
        barSrc.set(0, 0, current.bar.width, current.bar.height)
        barDst.set(barLeft, top, barRight, top + barH)
        canvas.drawBitmap(current.bar, barSrc, barDst, barPaint)

        val progress = current.progress ?: return
        val tipX = barLeft + (barRight - barLeft) * progress
        val stroke = max(1f, arrowH * 0.09f)
        arrowStroke.strokeWidth = stroke
        val inset = stroke / 2f
        val left = tipX - arrowW / 5f + inset
        val right = left + arrowW - inset * 2f
        arrowPath.reset()
        arrowPath.moveTo(left, inset)
        arrowPath.lineTo(right, viewH / 2f)
        arrowPath.lineTo(left, viewH - inset)
        arrowPath.lineTo(left + min(arrowW * 0.22f, arrowW / 3f), viewH / 2f)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowFill)
        canvas.drawPath(arrowPath, arrowStroke)
    }

    private companion object {
        /** Width to height of Navigator's arrow. */
        const val ARROW_ASPECT = 0.8f
    }
}
