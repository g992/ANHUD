package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * The view editor's picture: a bitmap fitted into the view (letterboxed), plus the gestures:
 * one finger drags, two fingers pinch and rotate. Deltas go to [listener] in screen pixels
 * relative to the shown picture's width.
 */
class CameraPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        /** One finger moved by (dx, dy) screen pixels; [imageWidthPx] = shown picture width. */
        fun onDrag(dx: Float, dy: Float, imageWidthPx: Float)

        /** Fingers spread by [scale] (> 1 = zoom in). */
        fun onPinch(scale: Float)

        /** Fingers turned by [degrees], clockwise on screen positive. */
        fun onRotate(degrees: Float)

        fun onGestureStart()
        fun onGestureEnd()
    }

    var listener: Listener? = null

    /** Width / height of the picture, used for the frame even before a bitmap arrives. */
    var aspect: Float = 16f / 10f
        set(value) {
            field = value
            invalidate()
        }

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val framePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * context.resources.displayMetrics.density
        color = 0xFF3A3A3A.toInt()
    }
    private val dest = RectF()

    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var lastAngle = 0f
    private var twoFingers = false
    private var active = false

    fun setBitmap(b: Bitmap?) {
        bitmap = b
        invalidate()
    }

    private fun layoutDest() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val dw = min(w, h * aspect)
        val dh = dw / aspect
        dest.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2)
    }

    override fun onDraw(canvas: Canvas) {
        layoutDest()
        bitmap?.let { canvas.drawBitmap(it, null, dest, paint) }
        canvas.drawRect(dest, framePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                lastX = event.x
                lastY = event.y
                twoFingers = false
                active = true
                listener?.onGestureStart()
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                twoFingers = true
                lastSpan = span(event)
                lastAngle = angle(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (twoFingers && event.pointerCount >= 2) {
                    val s = span(event)
                    val a = angle(event)
                    if (lastSpan > 0f && s > 0f) listener?.onPinch(s / lastSpan)
                    var da = a - lastAngle
                    if (da > 180f) da -= 360f
                    if (da < -180f) da += 360f
                    listener?.onRotate(da)
                    lastSpan = s
                    lastAngle = a
                } else if (!twoFingers) {
                    listener?.onDrag(event.x - lastX, event.y - lastY, dest.width().coerceAtLeast(1f))
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) {
                // Back to one finger: no drag jump, the rest of this gesture is ignored.
                twoFingers = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                if (active) listener?.onGestureEnd()
                active = false
                twoFingers = false
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun span(e: MotionEvent): Float = hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0))

    private fun angle(e: MotionEvent): Float =
        Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
}
