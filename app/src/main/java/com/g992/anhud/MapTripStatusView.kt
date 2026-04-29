package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TRIP_STATUS_TEXT_SIDE_OFFSET_RATIO = 0.03f
private const val TRIP_STATUS_PROGRESS_HEIGHT_MULTIPLIER = 2f

internal fun resolveMapTripStatusHeightPx(mapHeightPx: Int, hasProgressBitmap: Boolean): Int {
    if (mapHeightPx <= 0) {
        return if (hasProgressBitmap) 40 else 30
    }
    return if (hasProgressBitmap) {
        (mapHeightPx * 0.19f).roundToInt().coerceIn(28, 44)
    } else {
        (mapHeightPx * 0.15f).roundToInt().coerceIn(22, 34)
    }
}

class MapTripStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val progressStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private var distanceText: String = ""
    private var arrivalText: String = ""
    private var timeText: String = ""
    private var progressBitmap: Bitmap? = null
    private var contentSignature: Int = 0

    fun updateContent(
        distance: String,
        arrival: String,
        time: String,
        bitmap: Bitmap?
    ) {
        val safeBitmap = bitmap?.takeUnless { it.isRecycled || it.width <= 0 || it.height <= 0 }
        val signature = buildSignature(distance, arrival, time, safeBitmap)
        if (signature == contentSignature) return
        distanceText = distance
        arrivalText = arrival
        timeText = time
        progressBitmap = safeBitmap
        contentSignature = signature
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val widthPx = width
        val heightPx = height
        if (widthPx <= 0 || heightPx <= 0) return

        canvas.drawRect(0f, 0f, widthPx.toFloat(), heightPx.toFloat(), backgroundPaint)

        val bitmap = progressBitmap
        val showProgress = bitmap != null
        if (bitmap != null) {
            drawProgressBitmap(canvas, widthPx, heightPx, bitmap)
        }

        val textSize = if (showProgress) {
            (heightPx * 0.55f).coerceAtLeast(12f)
        } else {
            (heightPx * 0.60f).coerceAtLeast(12f)
        }
        textPaint.textSize = textSize
        val sidePadding = max(8f, widthPx * TRIP_STATUS_TEXT_SIDE_OFFSET_RATIO)
        val baselineY = if (showProgress) {
            heightPx - max(4f, heightPx * 0.10f)
        } else {
            val metrics = textPaint.fontMetrics
            (heightPx * 0.5f) - ((metrics.ascent + metrics.descent) * 0.5f)
        }

        canvas.drawText(distanceText, sidePadding, baselineY, textPaint)

        val arrivalWidth = textPaint.measureText(arrivalText)
        canvas.drawText(arrivalText, ((widthPx - arrivalWidth) * 0.5f).coerceAtLeast(sidePadding), baselineY, textPaint)

        val timeWidth = textPaint.measureText(timeText)
        canvas.drawText(
            timeText,
            (widthPx - sidePadding - timeWidth).coerceAtLeast(sidePadding),
            baselineY,
            textPaint
        )
    }

    private fun drawProgressBitmap(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        bitmap: Bitmap
    ) {
        val sourceWidth = bitmap.width
        val sourceHeight = bitmap.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return

        var drawWidth = min(sourceWidth, widthPx)
        var drawHeight = max(
            1,
            (((sourceHeight * drawWidth) / sourceWidth.toFloat()) * TRIP_STATUS_PROGRESS_HEIGHT_MULTIPLIER).roundToInt()
        )
        val bottomMarginPx = 1f
        if (drawHeight > heightPx - bottomMarginPx) {
            drawHeight = max(1, (heightPx - bottomMarginPx).roundToInt())
            drawWidth = max(1, ((sourceWidth * drawHeight) / sourceHeight.toFloat()).roundToInt())
        }
        val left = ((widthPx - drawWidth) * 0.5f).coerceAtLeast(0f)
        val top = (heightPx - bottomMarginPx - drawHeight).coerceAtLeast(0f)
        val dst = RectF(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(bitmap, null, dst, null)
        canvas.drawRect(dst, progressStrokePaint)
    }

    private fun buildSignature(distance: String, arrival: String, time: String, bitmap: Bitmap?): Int {
        var signature = 17
        signature = 31 * signature + distance.hashCode()
        signature = 31 * signature + arrival.hashCode()
        signature = 31 * signature + time.hashCode()
        if (bitmap != null) {
            signature = 31 * signature + bitmap.width
            signature = 31 * signature + bitmap.height
            signature = 31 * signature + bitmap.generationId
        }
        return signature
    }
}
