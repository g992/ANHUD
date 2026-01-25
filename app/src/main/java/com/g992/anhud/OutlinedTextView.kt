package com.g992.anhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class OutlinedTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle
) : AppCompatTextView(context, attrs, defStyleAttr) {
    var strokeWidthPx: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var strokeColor: Int = Color.BLACK
        set(value) {
            field = value
            invalidate()
        }

    var strokeEnabled: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val layout = layout
        if (!strokeEnabled || strokeWidthPx <= 0f || layout == null) {
            super.onDraw(canvas)
            return
        }

        val contentHeight = height - compoundPaddingTop - compoundPaddingBottom
        val top = when (gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK) {
            android.view.Gravity.BOTTOM -> compoundPaddingTop + (contentHeight - layout.height)
            android.view.Gravity.CENTER_VERTICAL -> compoundPaddingTop + (contentHeight - layout.height) / 2
            else -> compoundPaddingTop
        }.coerceAtLeast(compoundPaddingTop)
        val left = compoundPaddingLeft
        val right = width - compoundPaddingRight
        val bottom = height - compoundPaddingBottom

        val textColor = currentTextColor
        val paint = paint
        val oldStyle = paint.style
        val oldColor = paint.color
        val oldStrokeWidth = paint.strokeWidth

        val save = canvas.save()
        canvas.clipRect(
            left,
            compoundPaddingTop,
            right,
            bottom
        )
        canvas.translate(left.toFloat(), top.toFloat())
        canvas.translate(-scrollX.toFloat(), -scrollY.toFloat())

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidthPx
        paint.color = strokeColor
        layout.draw(canvas)

        paint.style = Paint.Style.FILL
        paint.color = textColor
        layout.draw(canvas)

        canvas.restoreToCount(save)
        paint.style = oldStyle
        paint.color = oldColor
        paint.strokeWidth = oldStrokeWidth
    }
}
