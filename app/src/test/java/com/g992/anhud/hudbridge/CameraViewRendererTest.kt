package com.g992.anhud.hudbridge

import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.tan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraViewRendererTest {
    private val lens = CameraViewRenderer.Lens(327.2, 641.1, 399.9)

    private fun view(
        hfov: Double = 130.0,
        panX: Double = 0.0,
        panY: Double = -0.12,
        mirror: Boolean = false,
        yaw: Double = 0.0,
        pitch: Double = 0.0,
        roll: Double = 0.0,
        vignette: Boolean = false
    ) = CameraViewRenderer.View(hfov, panX, panY, mirror, yaw, pitch, roll, vignette)

    @Test
    fun zeroRotationIsTheV1Formula() {
        val w = 37
        val h = 23
        val lut = CameraViewRenderer.lut(w, h, lens, view(panX = 0.1))
        val k = lens.k
        val fo = (w / 2.0) / tan(Math.toRadians(130.0) / 2)
        for ((u, v) in listOf(0 to 0, 5 to 17, 36 to 22, 18 to 11)) {
            val x = (u + 0.5 - w / 2.0 - 0.1 * w) / fo
            val y = (v + 0.5 - h / 2.0 + 0.12 * h) / fo
            val r = sqrt(x * x + y * y)
            val t = atan(r)
            val rd = t * (1 + k[0] * t * t + k[1] * t * t * t * t + k[2] * Math.pow(t, 6.0) + k[3] * Math.pow(t, 8.0))
            val sx = floor(lens.cx + lens.f * rd * x / r).toInt()
            val sy = floor(lens.cy + lens.f * rd * y / r).toInt()
            assertEquals("($u, $v)", sy * 1280 + sx, lut[v * w + u])
        }
    }

    @Test
    fun mirrorFlipsColumns() {
        val w = 20
        val h = 10
        val plain = CameraViewRenderer.lut(w, h, lens, view(yaw = 12.0, roll = 5.0))
        val mirrored = CameraViewRenderer.lut(w, h, lens, view(yaw = 12.0, roll = 5.0, mirror = true))
        for (v in 0 until h) for (u in 0 until w) {
            assertEquals(plain[v * w + (w - 1 - u)], mirrored[v * w + u])
        }
    }

    @Test
    fun raysPastTheLensCoverageAreBlack() {
        // Looking 90° right with a wide output: the right part of the picture is beyond 100°.
        val w = 40
        val lut = CameraViewRenderer.lut(w, 10, lens, view(hfov = 120.0, panY = 0.0, yaw = 90.0))
        assertTrue(lut[5 * w + 0] >= 0)
        assertEquals(-1, lut[5 * w + w - 1])
    }

    @Test
    fun positivePitchLooksUp() {
        val w = 21
        val h = 21
        val level = CameraViewRenderer.lut(w, h, lens, view(panY = 0.0))
        val up = CameraViewRenderer.lut(w, h, lens, view(panY = 0.0, pitch = 10.0))
        val centre = 10 * w + 10
        assertTrue("source row above the centre", up[centre] / 1280 < level[centre] / 1280)
    }

    @Test
    fun vignetteMatchesTheMapCurve() {
        assertEquals(0, CameraViewRenderer.vignetteAlpha(50, 50, 101, 101))
        assertEquals(0, CameraViewRenderer.vignetteAlpha(20, 50, 101, 101))
        assertEquals(54, CameraViewRenderer.vignetteAlpha(10, 50, 101, 101))
        assertEquals(90, CameraViewRenderer.vignetteAlpha(50, 5, 101, 101))
        assertEquals(189, CameraViewRenderer.vignetteAlpha(98, 50, 101, 101))
        assertEquals(255, CameraViewRenderer.vignetteAlpha(0, 0, 101, 101))
        assertEquals(255, CameraViewRenderer.vignetteAlpha(100, 37, 101, 101))
        // Narrow side: maxD from the shorter side.
        assertEquals(CameraViewRenderer.vignetteAlpha(5, 30, 61, 200), CameraViewRenderer.vignetteAlpha(30, 5, 200, 61))
    }

    @Test
    fun renderDarkensEdgesOnlyWithVignette() {
        val grey = IntArray(1280 * 800) { 0xFF808080.toInt() }
        val w = 30
        val h = 20
        val plain = CameraViewRenderer.render(grey, w, h, lens, view(hfov = 60.0, panY = 0.0))
        val dark = CameraViewRenderer.render(grey, w, h, lens, view(hfov = 60.0, panY = 0.0, vignette = true))
        val c = 10 * w + 15
        assertEquals(0xFF808080.toInt(), plain[c])
        assertEquals("centre untouched", plain[c], dark[c])
        assertEquals("edge fully dark", 0xFF000000.toInt(), dark[0])
        assertEquals(0xFF808080.toInt(), plain[0])
    }

    @Test
    fun nv12Conversion() {
        val nv12 = ByteArray(CameraViewRenderer.NV12_BYTES) { if (it < 1280 * 800) 100 else 128.toByte() }
        val argb = CameraViewRenderer.nv12ToArgb(nv12)
        assertEquals(0xFF646464.toInt(), argb[0])
        assertEquals(0xFF646464.toInt(), argb[argb.size - 1])
    }
}
