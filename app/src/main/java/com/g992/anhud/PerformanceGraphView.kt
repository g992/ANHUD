package com.g992.anhud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class PerformanceGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val values = ArrayList<Float>()

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#444444")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2f2f2f")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 0.8f
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4fc3f7")
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = linePaint.color
        style = Paint.Style.FILL
    }
    private val path = Path()

    fun setValues(newValues: List<Float>) {
        values.clear()
        values.addAll(newValues.map { it.coerceIn(0f, 100f) })
        invalidate()
    }

    fun setLineColor(color: Int) {
        linePaint.color = color
        dotPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        canvas.drawRect(left, top, right, bottom, borderPaint)

        for (i in 1..3) {
            val y = top + (bottom - top) * (i / 4f)
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        if (values.isEmpty()) return

        path.reset()
        val count = values.size
        val usableWidth = max(1f, right - left)
        val stepX = if (count <= 1) 0f else usableWidth / (count - 1)

        values.forEachIndexed { index, value ->
            val x = left + index * stepX
            val y = bottom - (value / 100f) * (bottom - top)
            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        canvas.drawPath(path, linePaint)

        val lastX = left + (count - 1) * stepX
        val lastY = bottom - (values.last() / 100f) * (bottom - top)
        canvas.drawCircle(lastX, lastY, resources.displayMetrics.density * 2.5f, dotPaint)
    }
}
