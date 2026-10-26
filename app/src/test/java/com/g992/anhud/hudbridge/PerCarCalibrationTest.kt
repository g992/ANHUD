package com.g992.anhud.hudbridge

import com.g992.anhud.hudbridge.PerCarCalibration.Camera
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Car #1 (FX11 J1): Table_2d.db sampled exactly like the app does on QNX (64 chunks x 8 blocks).
 * Expected values come from percar.py on the same sample.
 */
class PerCarCalibrationTest {
    private val sample: ByteArray by lazy {
        requireNotNull(javaClass.classLoader?.getResourceAsStream("camera/table2d_car1_sample.bin")) {
            "fixture missing"
        }.use { it.readBytes() }
    }

    private val results by lazy {
        PerCarCalibration.calibrateAll(
            PerCarCalibration.parseBlocks(sample),
            listOf(Camera.LEFT, Camera.RIGHT, Camera.FRONT, Camera.REAR)
        )
    }

    private class Expected(
        val n: Int, val rms: Double, val f: Double, val cx: Double, val cy: Double,
        val heading: Double, val down: Double, val roll: Double
    )

    private val expected = mapOf(
        Camera.LEFT to Expected(260, 0.4494, 327.3430, 634.5824, 401.2089, -92.4320, 45.4264, 1.4920),
        Camera.RIGHT to Expected(290, 0.8724, 329.2073, 647.9368, 400.1203, 90.9345, 45.4017, -1.0587),
        Camera.FRONT to Expected(144, 0.1114, 328.6189, 644.6120, 400.5581, -0.5131, 32.2220, -0.4927),
        Camera.REAR to Expected(178, 0.0356, 327.2072, 641.0720, 399.8640, 179.5719, 33.9724, -0.2583)
    )

    @Test
    fun sampleOffsetsMatchNumpyLinspaceRound() {
        // 10370 blocks in car #1's Table_2d.db; np.linspace(0, 10362, 64).round()
        val o = PerCarCalibration.sampleOffsets(10370)
        assertEquals(64, o.size)
        assertEquals(0, o[0])
        assertEquals(164, o[1])
        assertEquals(10362, o[63])
        assertEquals(PerCarCalibration.SAMPLE_BYTES, sample.size)
    }

    @Test
    fun car1FitMatchesTheResearchScript() {
        for ((camera, e) in expected) {
            val r = results.getValue(camera)
            val fit = assertNotNull("$camera: ${r.reason}", r.fit).let { r.fit!! }
            assertEquals("$camera n", e.n, fit.n)
            assertEquals("$camera rms", e.rms, fit.rms, 0.1)
            assertEquals("$camera f", e.f, fit.f, 0.5)
            assertEquals("$camera cx", e.cx, fit.cx, 0.5)
            assertEquals("$camera cy", e.cy, fit.cy, 0.5)
            val terms = fit.carTerms
            assertEquals("$camera heading", e.heading, terms[0], 0.2)
            assertEquals("$camera down", e.down, terms[1], 0.2)
            assertEquals("$camera roll", e.roll, terms[2], 0.2)
        }
    }

    @Test
    fun defaultViewsConvertToTheResearchProtocolAngles() {
        fun ypr(camera: Camera, heading: Double, down: Double) =
            PerCarCalibration.protocolYpr(results.getValue(camera).fit!!.r, heading, down, 0.0)
        assertYpr(doubleArrayOf(-73.9534, 14.3393, -43.6021), ypr(Camera.LEFT, 200.0, 0.0))
        assertYpr(doubleArrayOf(75.0345, 13.7163, 43.7288), ypr(Camera.RIGHT, 160.0, 0.0))
        assertYpr(doubleArrayOf(0.3548, -0.0300, 0.4975), ypr(Camera.REAR, 180.0, 34.0))
    }

    private fun assertYpr(expected: DoubleArray, actual: DoubleArray) {
        for (i in 0 until 3) assertEquals("ypr[$i]", expected[i], actual[i], 0.3)
    }

    @Test
    fun protocolRotationReproducesTheViewMatrix() {
        // Ry*Rx*Rz from the protocol angles must equal R_wc * V (the conversion is exact, not a fit).
        val fit = results.getValue(Camera.LEFT).fit!!
        val ypr = PerCarCalibration.protocolYpr(fit.r, 205.0, 10.0, 7.0)
        val m = PerCarCalibration.mul3(fit.r, PerCarCalibration.viewMatrix(205.0, 10.0, 7.0))
        val p = PerCarCalibration.yprMatrix(ypr[0], ypr[1], ypr[2])
        for (i in 0 until 9) assertEquals("m[$i]", m[i], p[i], 1e-9)
    }

    @Test
    fun truncatedDataIsRejected() {
        assertThrows { PerCarCalibration.parseBlocks(sample.copyOf(sample.size - 100)) }
        assertThrows { PerCarCalibration.parseBlocks(ByteArray(0)) }
        // A few blocks parse but leave too few points: no camera passes.
        val few = PerCarCalibration.calibrateAll(
            PerCarCalibration.parseBlocks(sample.copyOf(PerCarCalibration.BLOCK_SIZE * 16)),
            listOf(Camera.LEFT, Camera.RIGHT, Camera.REAR)
        )
        assertTrue(few.values.all { it.fit == null })
    }

    @Test
    fun nanAndBadTagsAreRejected() {
        val nan = sample.copyOf()
        ByteBuffer.wrap(nan).order(ByteOrder.LITTLE_ENDIAN).putDouble(PerCarCalibration.BLOCK_SIZE * 3 + 8 + 16, Double.NaN)
        assertThrows { PerCarCalibration.parseBlocks(nan) }
        val badTag = sample.copyOf()
        ByteBuffer.wrap(badTag).order(ByteOrder.LITTLE_ENDIAN).putLong(PerCarCalibration.BLOCK_SIZE * 5, 9L)
        assertThrows { PerCarCalibration.parseBlocks(badTag) }
    }

    @Test
    fun cameraOnTheWrongSideFailsSanity() {
        // The left camera's mesh checked as the right camera.
        val points = PerCarCalibration.parseBlocks(sample)
        val swapped = mapOf(PerCarCalibration.TAG_RIGHT to points.getValue(PerCarCalibration.TAG_LEFT))
        val r = PerCarCalibration.calibrateAll(swapped, listOf(Camera.RIGHT)).getValue(Camera.RIGHT)
        assertNull(r.fit)
        assertEquals("right cam not on right", r.reason)
    }

    @Test
    fun groundOnlyKeepsZeroHeightVertices() {
        val block = ByteBuffer.allocate(PerCarCalibration.BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        block.putLong(PerCarCalibration.TAG_REAR.toLong())
        for (v in 0 until 8) {
            block.putDouble(v * 0.1).putDouble(-4.0).putDouble(if (v < 4) 0.0 else 0.5)
                .putDouble(1.0).putDouble(0.3 + v * 0.01).putDouble(0.6)
        }
        val all = PerCarCalibration.parseBlocks(block.array()).getValue(PerCarCalibration.TAG_REAR)
        val ground = PerCarCalibration.parseBlocks(block.array(), groundOnly = true).getValue(PerCarCalibration.TAG_REAR)
        assertEquals(8, all.size)
        assertEquals(4, ground.size)
        assertEquals(0.31 * 1280, ground.uv[2], 1e-9)
        assertEquals(0.6 * 800, ground.uv[3], 1e-9)
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
