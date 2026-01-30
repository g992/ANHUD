package com.g992.anhud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import kotlin.math.abs

object ManeuverRecognition {
    data class Candidate(val name: String, val distance: Long)

    data class Result(
        val bestName: String,
        val bestDistance: Long,
        val top: List<Candidate>,
        val mask: IntArray
    )

    private data class ManeuverSignature(
        val name: String,
        val mask: IntArray
    )

    private data class Params(
        val renderSize: Int,
        val normSize: Int,
        val alphaThreshold: Int
    )

    private val lock = Any()
    @Volatile
    private var cachedSignatures: List<ManeuverSignature>? = null

    private val defaultParams = Params(
        renderSize = 128,
        normSize = 64,
        alphaThreshold = 8
    )

    fun analyze(context: Context, bitmap: Bitmap): Result {
        val params = defaultParams
        val mask = normalizeToAlphaMask(bitmap, params)
        val references = getManeuverSignatures(context, params)
        if (references.isEmpty()) {
            return Result("", Long.MAX_VALUE, emptyList(), mask)
        }
        var bestName = ""
        var bestDistance = Long.MAX_VALUE
        val top = ArrayList<Candidate>(3)
        for (ref in references) {
            val distance = alphaDistance(mask, ref.mask)
            if (distance < bestDistance) {
                bestDistance = distance
                bestName = ref.name
            }
            if (top.size < 3) {
                top.add(Candidate(ref.name, distance))
                top.sortBy { it.distance }
            } else if (distance < top.last().distance) {
                top.removeAt(top.size - 1)
                top.add(Candidate(ref.name, distance))
                top.sortBy { it.distance }
            }
        }
        return Result(bestName, bestDistance, top, mask)
    }

    private fun getManeuverSignatures(context: Context, params: Params): List<ManeuverSignature> {
        val cached = cachedSignatures
        if (cached != null) {
            return cached
        }
        synchronized(lock) {
            val currentCached = cachedSignatures
            if (currentCached != null) {
                return currentCached
            }
            val signatures = ArrayList<ManeuverSignature>(MANEUVER_RESOURCES.size)
            for (resId in MANEUVER_RESOURCES) {
                val name = runCatching {
                    context.resources.getResourceEntryName(resId)
                }.getOrNull() ?: continue
                val bitmap = runCatching {
                    renderDrawableToBitmap(context, resId, params.renderSize)
                }.getOrNull() ?: continue
                val mask = normalizeToAlphaMask(bitmap, params)
                bitmap.recycle()
                signatures.add(ManeuverSignature(name, mask))
            }
            cachedSignatures = signatures
            return signatures
        }
    }

    private fun renderDrawableToBitmap(
        context: Context,
        @DrawableRes resId: Int,
        sizePx: Int
    ): Bitmap {
        val drawable = AppCompatResources.getDrawable(context, resId)
            ?: error("Failed to inflate vector drawable: $resId")
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    private fun normalizeToAlphaMask(bitmap: Bitmap, params: Params): IntArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var minX = bitmap.width
        var minY = bitmap.height
        var maxX = -1
        var maxY = -1
        for (y in 0 until bitmap.height) {
            val rowOffset = y * bitmap.width
            for (x in 0 until bitmap.width) {
                val alpha = Color.alpha(pixels[rowOffset + x])
                if (alpha > params.alphaThreshold) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        val cropped = if (maxX >= minX && maxY >= minY) {
            Bitmap.createBitmap(bitmap, minX, minY, maxX - minX + 1, maxY - minY + 1)
        } else {
            bitmap
        }
        val scaled = if (cropped.width != params.normSize || cropped.height != params.normSize) {
            Bitmap.createScaledBitmap(cropped, params.normSize, params.normSize, true)
        } else {
            cropped
        }
        val mask = IntArray(params.normSize * params.normSize)
        val scaledPixels = IntArray(params.normSize * params.normSize)
        scaled.getPixels(
            scaledPixels,
            0,
            params.normSize,
            0,
            0,
            params.normSize,
            params.normSize
        )
        for (i in scaledPixels.indices) {
            mask[i] = Color.alpha(scaledPixels[i])
        }
        if (scaled !== bitmap && scaled !== cropped) {
            scaled.recycle()
        }
        if (cropped !== bitmap && cropped !== scaled) {
            cropped.recycle()
        }
        return mask
    }

    private fun alphaDistance(a: IntArray, b: IntArray): Long {
        var sum = 0L
        for (i in a.indices) {
            sum += abs(a[i] - b[i]).toLong()
        }
        return sum
    }

    private val MANEUVER_RESOURCES = intArrayOf(
        R.drawable.context_ra_forward,
        R.drawable.context_ra_in_circular_movement,
        R.drawable.context_ra_hard_turn_left,
        R.drawable.context_ra_hard_turn_right,
        R.drawable.context_ra_turn_left,
        R.drawable.context_ra_turn_right,
        R.drawable.context_ra_turn_back_left,
        R.drawable.context_ra_turn_back_right,
        R.drawable.context_ra_take_left,
        R.drawable.context_ra_take_right,
        R.drawable.context_ra_exit_left,
        R.drawable.context_ra_exit_right,
        R.drawable.context_ra_boardferry,
        R.drawable.context_ra_out_circular_movement,
        R.drawable.context_ra_via,
        R.drawable.context_ra_finish
    )
}
