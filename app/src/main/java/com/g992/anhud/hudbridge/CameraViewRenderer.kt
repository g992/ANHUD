package com.g992.anhud.hudbridge

import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * The daemon's picture, computed in Kotlin for the view editor: virtual pinhole output with pan and
 * mirror, rotated by R = Ry(yaw) Rx(pitch) Rz(roll), projected through the KB lens, nearest source
 * pixel, black past [PerCarCalibration.MAX_RAY_ANGLE_DEG] or outside the frame; then the vignette
 * (CAMCFG view flag VIGNETTE). Same math as ghudbridgelited's LUT and percar.py build_lut.
 */
object CameraViewRenderer {
    const val SRC_W = PerCarCalibration.SRC_W
    const val SRC_H = PerCarCalibration.SRC_H
    /** cap_*.yuv snapshots on QNX: NV12 1280x800. */
    const val NV12_BYTES = SRC_W * SRC_H * 3 / 2

    private const val VIGNETTE_CLEAR_STOP = 0.65f
    private const val VIGNETTE_MID_STOP = 0.9f
    private const val VIGNETTE_MID_ALPHA = 90
    private const val VIGNETTE_EDGE_ALPHA = 255

    data class Lens(val f: Double, val cx: Double, val cy: Double, val k: DoubleArray = PerCarCalibration.K_REF)

    data class View(
        val hfovDeg: Double,
        val panX: Double,
        val panY: Double,
        val mirror: Boolean,
        val yawDeg: Double,
        val pitchDeg: Double,
        val rollDeg: Double,
        val vignette: Boolean
    )

    /** NV12 (Y plane, then interleaved U/V at half resolution) to opaque ARGB pixels. */
    fun nv12ToArgb(nv12: ByteArray): IntArray {
        require(nv12.size >= NV12_BYTES) { "NV12 frame too short: ${nv12.size}" }
        val out = IntArray(SRC_W * SRC_H)
        val uvBase = SRC_W * SRC_H
        for (y in 0 until SRC_H) {
            val uvRow = uvBase + (y / 2) * SRC_W
            for (x in 0 until SRC_W) {
                val yy = (nv12[y * SRC_W + x].toInt() and 0xFF).toFloat()
                val uvAt = uvRow + (x / 2) * 2
                val u = (nv12[uvAt].toInt() and 0xFF) - 128f
                val v = (nv12[uvAt + 1].toInt() and 0xFF) - 128f
                val r = (yy + 1.402f * v).toInt().coerceIn(0, 255)
                val g = (yy - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                val b = (yy + 1.772f * u).toInt().coerceIn(0, 255)
                out[y * SRC_W + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return out
    }

    /** Black overlay alpha 0..255 of the vignette at output pixel (x, y) of a w x h view (MapVignetteView curve). */
    fun vignetteAlpha(x: Int, y: Int, w: Int, h: Int): Int {
        val maxD = (min(w, h) - 1).coerceAtLeast(1) / 2f
        val d = min(min(x, w - 1 - x), min(y, h - 1 - y)).toFloat()
        val p = 1f - (d / maxD).coerceIn(0f, 1f)
        return when {
            p <= VIGNETTE_CLEAR_STOP -> 0
            p <= VIGNETTE_MID_STOP -> lerp(0, VIGNETTE_MID_ALPHA, (p - VIGNETTE_CLEAR_STOP) / (VIGNETTE_MID_STOP - VIGNETTE_CLEAR_STOP))
            else -> lerp(VIGNETTE_MID_ALPHA, VIGNETTE_EDGE_ALPHA, (p - VIGNETTE_MID_STOP) / (1f - VIGNETTE_MID_STOP))
        }
    }

    private fun lerp(start: Int, end: Int, t: Float): Int = (start + (end - start) * t.coerceIn(0f, 1f)).roundToInt()

    /**
     * Source pixel index (y * 1280 + x) for every output pixel of a w x h view, -1 = black.
     * Exactly the daemon's lookup table.
     */
    fun lut(w: Int, h: Int, lens: Lens, view: View): IntArray {
        val out = IntArray(w * h)
        val fo = (w / 2.0) / tan(Math.toRadians(view.hfovDeg) / 2)
        val r = PerCarCalibration.yprMatrix(view.yawDeg, view.pitchDeg, view.rollDeg)
        val maxTheta = Math.toRadians(PerCarCalibration.MAX_RAY_ANGLE_DEG)
        val src = DoubleArray(2)
        for (v in 0 until h) {
            val py = (v + 0.5 - h / 2.0 - view.panY * h) / fo
            for (u in 0 until w) {
                val uu = if (view.mirror) w - 1 - u else u
                val px = (uu + 0.5 - w / 2.0 - view.panX * w) / fo
                val dx = r[0] * px + r[1] * py + r[2]
                val dy = r[3] * px + r[4] * py + r[5]
                val dz = r[6] * px + r[7] * py + r[8]
                val theta = atan2(hypot(dx, dy), dz)
                PerCarCalibration.kbProject(dx, dy, dz, lens.f, lens.cx, lens.cy, lens.k, src)
                val ix = floor(src[0]).toInt()
                val iy = floor(src[1]).toInt()
                val ok = ix in 0 until SRC_W && iy in 0 until SRC_H && theta < maxTheta
                out[v * w + u] = if (ok) iy * SRC_W + ix else -1
            }
        }
        return out
    }

    /** The picture of [view] as w x h ARGB pixels; [source] = [nv12ToArgb] of a snapshot, or null (black). */
    fun render(source: IntArray?, w: Int, h: Int, lens: Lens, view: View): IntArray {
        val map = lut(w, h, lens, view)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val s = map[i]
                var px = if (s >= 0 && source != null) source[s] else BLACK
                if (view.vignette && px != BLACK) {
                    val keep = 255 - vignetteAlpha(x, y, w, h)
                    if (keep < 255) {
                        val r = ((px shr 16) and 0xFF) * keep / 255
                        val g = ((px shr 8) and 0xFF) * keep / 255
                        val b = (px and 0xFF) * keep / 255
                        px = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                }
                out[i] = px
            }
        }
        return out
    }

    private const val BLACK = 0xFF000000.toInt()
}
