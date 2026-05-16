package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAP_VIGNETTE_CLEAR_STOP = 0.65f
private const val MAP_VIGNETTE_MID_STOP = 0.9f
private const val MAP_VIGNETTE_MID_ALPHA = 90
private const val MAP_VIGNETTE_EDGE_ALPHA = 255

class MapVignetteView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var vignetteBitmap: Bitmap? = null

    override fun onDraw(canvas: Canvas) {
        updateBitmapIfNeeded()
        vignetteBitmap?.let { bitmap ->
            canvas.drawBitmap(bitmap, 0f, 0f, vignettePaint)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw || h != oldh) {
            vignetteBitmap?.recycle()
            vignetteBitmap = null
        }
    }

    private fun updateBitmapIfNeeded() {
        if (width <= 0 || height <= 0) return
        val cached = vignetteBitmap
        if (cached != null && cached.width == width && cached.height == height) return

        val maxDistanceToEdge = (min(width, height) - 1).coerceAtLeast(1) / 2f
        val pixels = IntArray(width * height)
        var index = 0
        for (y in 0 until height) {
            val distanceToHorizontalEdge = min(y.toFloat(), (height - 1 - y).toFloat())
            for (x in 0 until width) {
                val distanceToVerticalEdge = min(x.toFloat(), (width - 1 - x).toFloat())
                val distanceToNearestEdge = min(distanceToVerticalEdge, distanceToHorizontalEdge)
                val edgeProgress = 1f - (distanceToNearestEdge / maxDistanceToEdge).coerceIn(0f, 1f)
                val alpha = resolveAlpha(edgeProgress)
                pixels[index++] = Color.argb(alpha, 0, 0, 0)
            }
        }
        vignetteBitmap?.recycle()
        vignetteBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun resolveAlpha(edgeProgress: Float): Int {
        return when {
            edgeProgress <= MAP_VIGNETTE_CLEAR_STOP -> 0
            edgeProgress <= MAP_VIGNETTE_MID_STOP -> {
                val t = (edgeProgress - MAP_VIGNETTE_CLEAR_STOP) /
                    (MAP_VIGNETTE_MID_STOP - MAP_VIGNETTE_CLEAR_STOP)
                lerpAlpha(0, MAP_VIGNETTE_MID_ALPHA, t)
            }
            else -> {
                val t = (edgeProgress - MAP_VIGNETTE_MID_STOP) / (1f - MAP_VIGNETTE_MID_STOP)
                lerpAlpha(MAP_VIGNETTE_MID_ALPHA, MAP_VIGNETTE_EDGE_ALPHA, t)
            }
        }
    }

    private fun lerpAlpha(start: Int, end: Int, progress: Float): Int {
        return (start + (end - start) * progress.coerceIn(0f, 1f)).roundToInt()
    }
}
