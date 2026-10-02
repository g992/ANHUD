package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/** A saved Navigator frame and its traffic strip, scaled together for layout previews. */
class YandexMapPreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val map: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.preview_yandex_minimap)
    private val jams: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.preview_yandex_jams)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val mapRatio = map.height.toFloat() / map.width
        val jamRatio = jams.height.toFloat() / jams.width
        val contentWidth = minOf(width.toFloat(), height / (mapRatio + jamRatio))
        val left = (width - contentWidth) / 2f
        val mapHeight = contentWidth * mapRatio
        destination.set(left, 0f, left + contentWidth, mapHeight)
        canvas.drawBitmap(map, null, destination, paint)
        destination.set(left, mapHeight, left + contentWidth, mapHeight + contentWidth * jamRatio)
        canvas.drawBitmap(jams, null, destination, paint)
    }
}
