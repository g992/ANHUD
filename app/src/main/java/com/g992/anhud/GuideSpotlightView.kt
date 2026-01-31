package com.g992.anhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class GuideSpotlightView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xB3000000.toInt()
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x80FFFFFF.toInt()
        strokeWidth = dp(1.5f)
    }

    var targetRect: RectF? = null
        set(value) {
            field = value
            invalidate()
        }

    var cornerRadius: Float = dp(12f)
        set(value) {
            field = value
            invalidate()
        }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    override fun onDraw(canvas: Canvas) {
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
        val rect = targetRect
        if (rect != null) {
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, clearPaint)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, strokePaint)
        }
        canvas.restoreToCount(save)
    }

    private fun dp(value: Float): Float {
        return value * resources.displayMetrics.density
    }
}
