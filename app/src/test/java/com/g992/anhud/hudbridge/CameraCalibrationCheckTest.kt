package com.g992.anhud.hudbridge

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCalibrationCheckTest {
    private val sample: ByteArray by lazy {
        javaClass.classLoader!!.getResourceAsStream("camera/table2d_car1_sample.bin")!!.use { it.readBytes() }
    }

    private val table2d = CameraCalibrationCheck.TableFile(
        CameraCalibrationCheck.TABLE_2D,
        4_065_040,
        "-rw-r--r--  1 0  0  4065040 Oct 24 18:58 /calibration/Table_2d.db"
    )

    @Test
    fun lsOutputPicksTable2dThenTable() {
        val out = """
            -rw-r--r--  1 0        0           4065040 Oct 24 18:58 /calibration/Table_2d.db
            -rw-r--r--  1 0        0           6026216 Oct 24 18:58 /calibration/Table.db
        """.trimIndent()
        val files = CameraCalibrationCheck.parseLs(out)
        assertEquals(2, files.size)
        assertEquals(4_065_040L, files[0].size)
        assertEquals(10370, files[0].blockCount)
        assertEquals(CameraCalibrationCheck.TABLE_2D, CameraCalibrationCheck.pickTable(files)!!.path)
        // Table_2d.db truncated (not a multiple of 392): Table.db with ground points only.
        val broken = CameraCalibrationCheck.parseLs(out.replace("4065040", "4065041"))
        val picked = CameraCalibrationCheck.pickTable(broken)!!
        assertEquals(CameraCalibrationCheck.TABLE_3D, picked.path)
        assertTrue(picked.groundOnly)
        // Too small to be a real table, or nothing at all.
        assertNull(CameraCalibrationCheck.pickTable(CameraCalibrationCheck.parseLs(
            "-rw-r--r--  1 0  0  392000 Oct 24 18:58 /calibration/Table_2d.db"
        )))
        assertNull(CameraCalibrationCheck.pickTable(CameraCalibrationCheck.parseLs("")))
    }

    @Test
    fun cacheKeyFollowsSizeAndDate() {
        val other = table2d.copy(line = table2d.line.replace("18:58", "19:02"))
        assertTrue(table2d.cacheKey != other.cacheKey)
    }

    @Test
    fun fetchCommandReadsTheSampleOffsets() {
        val cmd = CameraCalibrationCheck.fetchCommand(table2d)
        assertEquals(
            "i=0; while [ \$i -lt 64 ]; do dd if=/calibration/Table_2d.db bs=392 " +
                "skip=\$(( (2*i*10362+63)/126 )) count=8 2>/dev/null; i=\$((i+1)); done | openssl base64",
            cmd
        )
        assertTrue("fits a telnet line", cmd.length < 200)
        // The ksh arithmetic is the same as sampleOffsets().
        val offsets = PerCarCalibration.sampleOffsets(10370)
        for (i in 0 until 64) assertEquals(offsets[i], ((2L * i * 10362 + 63) / 126).toInt())
    }

    @Test
    fun base64IsCheckedByLength() {
        val text = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(sample)
        assertEquals(sample.toList(), CameraCalibrationCheck.decodeBase64(text, sample.size)!!.toList())
        assertNull("truncated", CameraCalibrationCheck.decodeBase64(text.substring(0, text.length / 2), sample.size))
        assertNull(CameraCalibrationCheck.decodeBase64("ksh: openssl: not found", sample.size))
    }

    @Test
    fun sampleGivesAllThreeCamerasAndSurvivesTheCache() {
        val result = CameraCalibrationCheck.fromSample(sample, table2d)
        assertEquals(CameraCalibrationCheck.State.AVAILABLE, result.state)
        for (role in listOf(Gcp.CAM_ROLE_REAR, Gcp.CAM_ROLE_LEFT, Gcp.CAM_ROLE_RIGHT)) {
            assertNotNull("role $role", result.fit(role))
        }
        val back = CameraCalibrationCheck.decode(CameraCalibrationCheck.encode(result))!!
        assertEquals(result.state, back.state)
        assertEquals(result.key, back.key)
        val a = result.fit(Gcp.CAM_ROLE_LEFT)!!
        val b = back.fit(Gcp.CAM_ROLE_LEFT)!!
        assertEquals(a.f, b.f, 0.0)
        assertEquals(a.r.toList(), b.r.toList())
        assertEquals(a.n, b.n)
        assertNull(CameraCalibrationCheck.decode("garbage"))
        assertNull(CameraCalibrationCheck.decode(null))
    }

    @Test
    fun brokenSampleMeansNoCorrection() {
        val result = CameraCalibrationCheck.fromSample(sample.copyOf(1000), table2d)
        assertEquals(CameraCalibrationCheck.State.MISSING, result.state)
        assertTrue(result.fits.isEmpty())
        val failed = CameraCalibrationCheck.Result(
            CameraCalibrationCheck.State.MISSING,
            notes = mapOf(Gcp.CAM_ROLE_LEFT to "left cam not on left")
        )
        val back = CameraCalibrationCheck.decode(CameraCalibrationCheck.encode(failed))!!
        assertEquals("left cam not on left", back.notes[Gcp.CAM_ROLE_LEFT])
        assertNull(back.key)
    }

    @Test
    fun car1DefaultViewsGoOutWithTheResearchRotation() {
        val result = CameraCalibrationCheck.fromSample(sample, table2d)
        fun sent(role: Int, view: com.g992.anhud.OverlayPrefs.CameraViewSettings) = RearCameraLogic.cameraView(
            role = role, enabled = true, rect = Gcp.Rect(0, 0, 370, 232), flipX = false, z = 8, view = view,
            maxSpeedKmh = 15, fit = result.fit(role), correction = true, vignette = true
        )
        val left = sent(Gcp.CAM_ROLE_LEFT, com.g992.anhud.OverlayPrefs.defaultCameraView(com.g992.anhud.OverlayPrefs.CameraSlot.LEFT))
        assertEquals(-73.95f, left.yawDeg, 0.3f)
        assertEquals(14.34f, left.pitchDeg, 0.3f)
        assertEquals(-43.60f, left.rollDeg, 0.3f)
        assertEquals(327.34f, left.fx, 0.5f)
        assertEquals(60f, left.outHfovDeg, 0f)
        assertEquals(-0.25f, left.panY, 0f)
        val right = sent(Gcp.CAM_ROLE_RIGHT, com.g992.anhud.OverlayPrefs.defaultCameraView(com.g992.anhud.OverlayPrefs.CameraSlot.RIGHT))
        assertEquals(75.03f, right.yawDeg, 0.3f)
        assertEquals(13.72f, right.pitchDeg, 0.3f)
        assertEquals(43.73f, right.rollDeg, 0.3f)
        val rear = sent(Gcp.CAM_ROLE_REAR, com.g992.anhud.OverlayPrefs.defaultCameraView(com.g992.anhud.OverlayPrefs.CameraSlot.REAR))
        assertEquals(0.35f, rear.yawDeg, 0.3f)
        assertEquals(0.0f, rear.pitchDeg, 0.3f)
        assertEquals(0.50f, rear.rollDeg, 0.3f)
        assertEquals(130f, rear.outHfovDeg, 0f)
        assertEquals(-0.12f, rear.panY, 0f)
    }
}
