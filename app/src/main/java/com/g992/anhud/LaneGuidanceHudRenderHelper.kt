package com.g992.anhud

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.roundToInt

internal object LaneGuidanceHudRenderHelper {
    fun prepareBitmap(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 4 || height < 4) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val background = detectBackgroundColor(pixels, width, height) ?: return source
        val bgRed = Color.red(background)
        val bgGreen = Color.green(background)
        val bgBlue = Color.blue(background)
        val bgSpread = maxOf(bgRed, bgGreen, bgBlue) - minOf(bgRed, bgGreen, bgBlue)
        val bgLuma = computeLuma(bgRed, bgGreen, bgBlue)

        val maskedPixels = pixels.copyOf()
        var removedPixels = 0
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (index in maskedPixels.indices) {
            val color = maskedPixels[index]
            val alpha = Color.alpha(color)
            if (alpha <= 10) {
                maskedPixels[index] = Color.TRANSPARENT
                continue
            }
            if (shouldRemoveBackground(color, bgRed, bgGreen, bgBlue, bgSpread, bgLuma)) {
                maskedPixels[index] = Color.TRANSPARENT
                removedPixels += 1
                continue
            }
            val x = index % width
            val y = index / width
            if (x < left) left = x
            if (x > right) right = x
            if (y < top) top = y
            if (y > bottom) bottom = y
        }
        if (removedPixels < 12 || right < left || bottom < top) {
            return source
        }

        left = (left - 1).coerceAtLeast(0)
        top = (top - 1).coerceAtLeast(0)
        right = (right + 1).coerceAtMost(width - 1)
        bottom = (bottom + 1).coerceAtMost(height - 1)
        val croppedWidth = right - left + 1
        val croppedHeight = bottom - top + 1
        if (croppedWidth <= 0 || croppedHeight <= 0) return source

        val croppedPixels = IntArray(croppedWidth * croppedHeight)
        for (row in 0 until croppedHeight) {
            val srcOffset = (top + row) * width + left
            val dstOffset = row * croppedWidth
            System.arraycopy(maskedPixels, srcOffset, croppedPixels, dstOffset, croppedWidth)
        }
        return Bitmap.createBitmap(croppedPixels, croppedWidth, croppedHeight, Bitmap.Config.ARGB_8888)
    }

    private fun detectBackgroundColor(pixels: IntArray, width: Int, height: Int): Int? {
        if (pixels.isEmpty() || width <= 0 || height <= 0) return null
        val band = (minOf(width, height) * 0.06f).roundToInt().coerceIn(1, 8)
        val counts = HashMap<Int, Int>()
        val sumR = HashMap<Int, Int>()
        val sumG = HashMap<Int, Int>()
        val sumB = HashMap<Int, Int>()
        var sampleCount = 0

        fun sample(x: Int, y: Int) {
            val color = pixels[y * width + x]
            if (Color.alpha(color) < 180) return
            val red = Color.red(color)
            val green = Color.green(color)
            val blue = Color.blue(color)
            val bucket = ((red shr 4) shl 8) or ((green shr 4) shl 4) or (blue shr 4)
            counts[bucket] = (counts[bucket] ?: 0) + 1
            sumR[bucket] = (sumR[bucket] ?: 0) + red
            sumG[bucket] = (sumG[bucket] ?: 0) + green
            sumB[bucket] = (sumB[bucket] ?: 0) + blue
            sampleCount += 1
        }

        for (y in 0 until band) {
            for (x in 0 until width) sample(x, y)
        }
        for (y in (height - band).coerceAtLeast(0) until height) {
            for (x in 0 until width) sample(x, y)
        }
        for (x in 0 until band) {
            for (y in band until (height - band).coerceAtLeast(band)) sample(x, y)
        }
        for (x in (width - band).coerceAtLeast(0) until width) {
            for (y in band until (height - band).coerceAtLeast(band)) sample(x, y)
        }

        val bestBucket = counts.maxByOrNull { it.value }?.key ?: return null
        val bestCount = counts[bestBucket] ?: return null
        if (bestCount < 10 || bestCount * 4 < sampleCount) return null

        val red = (sumR[bestBucket] ?: return null) / bestCount
        val green = (sumG[bestBucket] ?: return null) / bestCount
        val blue = (sumB[bestBucket] ?: return null) / bestCount
        val spread = maxOf(red, green, blue) - minOf(red, green, blue)
        if (spread < 24) return null
        return Color.argb(255, red, green, blue)
    }

    private fun shouldRemoveBackground(
        color: Int,
        bgRed: Int,
        bgGreen: Int,
        bgBlue: Int,
        bgSpread: Int,
        bgLuma: Int
    ): Boolean {
        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val dr = red - bgRed
        val dg = green - bgGreen
        val db = blue - bgBlue
        val distanceSq = dr * dr + dg * dg + db * db
        if (distanceSq <= 44 * 44) return true

        val spread = maxOf(red, green, blue) - minOf(red, green, blue)
        val luma = computeLuma(red, green, blue)
        return distanceSq <= 68 * 68 &&
            spread >= (bgSpread - 28).coerceAtLeast(0) &&
            luma <= bgLuma + 26
    }

    private fun computeLuma(red: Int, green: Int, blue: Int): Int {
        return ((red * 2126) + (green * 7152) + (blue * 722)) / 10_000
    }
}
