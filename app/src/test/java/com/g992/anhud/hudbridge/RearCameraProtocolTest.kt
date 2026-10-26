package com.g992.anhud.hudbridge

import com.g992.anhud.OverlayPrefs
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RearCameraProtocolTest {
    private fun view(
        role: Int = Gcp.CAM_ROLE_REAR,
        rect: Gcp.Rect = Gcp.Rect(100, 50, 320, 200),
        z: Int = 8,
        enabled: Boolean = true,
        mirror: Boolean = false,
        noCorrection: Boolean = false
    ) = Gcp.CameraView(
        role = role,
        enabled = enabled,
        mirror = mirror,
        inputId = Gcp.CAM_INPUT_REAR,
        rect = rect,
        z = z,
        fx = 331.5f,
        fy = 329.25f,
        cx = 641f,
        cy = 399.5f,
        k1 = 0.01f,
        k2 = -0.02f,
        k3 = 0.003f,
        k4 = -0.0004f,
        outHfovDeg = 120f,
        panX = -0.25f,
        panY = 0.1f,
        yawDeg = 12.5f,
        pitchDeg = -7f,
        rollDeg = 1.5f,
        maxSpeedKmh = 15,
        holdMs = 800,
        noCorrection = noCorrection
    )

    private fun cfg(vararg views: Gcp.CameraView, enabled: Boolean = true, hazardBoth: Boolean = false) =
        Gcp.CameraConfig(enabled = enabled, views = views.toList(), hazardBoth = hazardBoth)

    @Test
    fun camCfgV2HeaderAndViewOffsetsMatchTheSpec() {
        val p = Gcp.camCfg(cfg(view(mirror = true), view(role = Gcp.CAM_ROLE_LEFT, noCorrection = true)))
        assertEquals(16 + 2 * 112, p.size)
        val b = le(p)
        assertEquals(2, b.getInt(0))
        assertEquals(Gcp.CAMCFG_FLAG_ENABLED, b.getInt(4))
        assertEquals(2, b.getInt(8))
        assertEquals(112, b.getInt(12))
        val v = 16
        assertEquals(Gcp.CAM_ROLE_REAR, b.getInt(v + 0))
        assertEquals(Gcp.CAM_FLAG_ENABLED or Gcp.CAM_FLAG_MIRROR, b.getInt(v + 4))
        assertEquals(3, b.getInt(v + 8))
        assertEquals(100, b.getInt(v + 12))
        assertEquals(50, b.getInt(v + 16))
        assertEquals(320, b.getInt(v + 20))
        assertEquals(200, b.getInt(v + 24))
        assertEquals(8, b.getInt(v + 28))
        assertEquals(331.5f, b.getFloat(v + 32), 0f)
        assertEquals(329.25f, b.getFloat(v + 36), 0f)
        assertEquals(641f, b.getFloat(v + 40), 0f)
        assertEquals(399.5f, b.getFloat(v + 44), 0f)
        assertEquals(0.01f, b.getFloat(v + 48), 0f)
        assertEquals(-0.02f, b.getFloat(v + 52), 0f)
        assertEquals(0.003f, b.getFloat(v + 56), 0f)
        assertEquals(-0.0004f, b.getFloat(v + 60), 0f)
        assertEquals(120f, b.getFloat(v + 64), 0f)
        assertEquals(-0.25f, b.getFloat(v + 68), 0f)
        assertEquals(0.1f, b.getFloat(v + 72), 0f)
        assertEquals(12.5f, b.getFloat(v + 76), 0f)
        assertEquals(-7f, b.getFloat(v + 80), 0f)
        assertEquals(1.5f, b.getFloat(v + 84), 0f)
        assertEquals(15, b.getInt(v + 88))
        assertEquals(800, b.getInt(v + 92))
        for (off in 96 until 112 step 4) {
            assertEquals("reserved @$off", 0, b.getInt(v + off))
        }
        val second = v + 112
        assertEquals(Gcp.CAM_ROLE_LEFT, b.getInt(second))
        assertEquals(Gcp.CAM_FLAG_ENABLED or Gcp.CAM_FLAG_NO_CORRECTION, b.getInt(second + 4))
    }

    @Test
    fun ecarxGearMapsToVhalGear() {
        assertEquals(Gcp.GEAR_PARK, RearCameraLogic.gearFromEcarx(0x200230))
        assertEquals(Gcp.GEAR_REVERSE, RearCameraLogic.gearFromEcarx(0x200240))
        assertEquals(Gcp.GEAR_NEUTRAL, RearCameraLogic.gearFromEcarx(0x200210))
        assertEquals(Gcp.GEAR_DRIVE, RearCameraLogic.gearFromEcarx(0x200220))
        assertEquals(null, RearCameraLogic.gearFromEcarx(-1))
        assertEquals(null, RearCameraLogic.gearFromEcarx(null))
    }

    @Test
    fun camCfgHeaderFlags() {
        assertEquals(0, le(Gcp.camCfg(cfg(view(), enabled = false))).getInt(4))
        assertEquals(
            Gcp.CAMCFG_FLAG_ENABLED or Gcp.CAMCFG_FLAG_HAZARD_BOTH,
            le(Gcp.camCfg(cfg(view(), hazardBoth = true))).getInt(4)
        )
        assertEquals(0x2, Gcp.CAMCFG_FLAG_HAZARD_BOTH)
        assertEquals(0x4, Gcp.CAM_FLAG_NO_CORRECTION)
    }

    @Test
    fun camCfgViewFlags() {
        assertEquals(0, le(Gcp.camCfg(cfg(view(enabled = false)))).getInt(16 + 4))
        assertEquals(Gcp.CAM_FLAG_ENABLED, le(Gcp.camCfg(cfg(view()))).getInt(16 + 4))
        assertEquals(
            Gcp.CAM_FLAG_ENABLED or Gcp.CAM_FLAG_MIRROR or Gcp.CAM_FLAG_NO_CORRECTION,
            le(Gcp.camCfg(cfg(view(mirror = true, noCorrection = true)))).getInt(16 + 4)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun camCfgNeedsAtLeastOneView() {
        Gcp.camCfg(cfg())
    }

    @Test
    fun camCfgClampsGeometryIntoTheHud() {
        val big = le(Gcp.camCfg(cfg(view(rect = Gcp.Rect(-20, 900, 1000, 5), z = 42))))
        assertEquals(0, big.getInt(16 + 12))
        assertEquals(480 - 16, big.getInt(16 + 16))
        assertEquals(800, big.getInt(16 + 20))
        assertEquals(16, big.getInt(16 + 24))
        assertEquals(10, big.getInt(16 + 28))
        val offRight = le(Gcp.camCfg(cfg(view(rect = Gcp.Rect(700, 400, 200, 100), z = 0))))
        assertEquals(600, offRight.getInt(16 + 12))
        assertEquals(380, offRight.getInt(16 + 16))
        assertEquals(5, offRight.getInt(16 + 28))
    }

    @Test
    fun vehStatePayloadMatchesTheSpec() {
        val b = le(Gcp.vehState(Gcp.GEAR_REVERSE, 7.5f))
        assertEquals(12, b.capacity())
        assertEquals(2, b.getInt(0))
        assertEquals(7.5f, b.getFloat(4), 0f)
        assertEquals(0, b.getInt(8))
        assertEquals(-1f, le(Gcp.vehState(Gcp.GEAR_PARK, Float.NaN)).getFloat(4), 0f)
        assertEquals(-1f, le(Gcp.vehState(Gcp.GEAR_PARK, -3f)).getFloat(4), 0f)
        assertEquals(0xB, le(Gcp.vehState(Gcp.GEAR_DRIVE, 0f, 0xB)).getInt(8))
    }

    @Test
    fun vehStateTurnFlags() {
        val t = RearCameraLogic.TurnSignals
        assertEquals(0x1 or 0x8, t.parse("left")!!.flags)
        assertEquals(0x2 or 0x8, t.parse("right")!!.flags)
        assertEquals(0x1 or 0x2 or 0x4 or 0x8, t.parse("hazard")!!.flags)
        assertEquals(0x8, t.parse("off")!!.flags)
        assertEquals(null, t.parse("both"))
        assertEquals("not valid: no turn bits at all", 0, RearCameraLogic.TurnSignals(left = true).flags)
        assertEquals(0, t.UNKNOWN.flags)
    }

    @Test
    fun turnSignalsAreValidOnlyWhileFresh() {
        val t = RearCameraLogic.TurnSignals
        assertEquals(0x1 or 0x8, t.fromSensors(true, false, false, readAtMs = 1_000, nowMs = 1_900).flags)
        val stale = RearCameraLogic.TURN_STALE_MS
        assertEquals(0x8, t.fromSensors(false, false, false, readAtMs = 1_000, nowMs = 1_000 + stale).flags)
        assertEquals(0, t.fromSensors(true, false, false, readAtMs = 1_000, nowMs = 1_001 + stale).flags)
        assertEquals("never read", 0, t.fromSensors(true, false, false, readAtMs = 0, nowMs = 5).flags)
    }

    @Test
    fun cameraMessagesUseTheirOwnTypes() {
        val m = Gcp.message(Gcp.T_CAMCFG, 0, 3, Gcp.camCfg(cfg(view())))
        val h = Gcp.Header(m.copyOf(Gcp.HEADER_SIZE))
        assertEquals(0x0050, h.type)
        assertEquals(16 + 112, h.length)
        val v = Gcp.message(Gcp.T_VEHSTATE, 0, 4, Gcp.vehState(Gcp.GEAR_DRIVE, 0f))
        assertEquals(0x0051, Gcp.Header(v.copyOf(Gcp.HEADER_SIZE)).type)
    }

    @Test
    fun gearMapping() {
        assertTrue(RearCameraLogic.isReverse(RearCameraLogic.normalizeGear(2)))
        assertFalse(RearCameraLogic.isReverse(RearCameraLogic.normalizeGear(4)))
        assertFalse(RearCameraLogic.isReverse(RearCameraLogic.normalizeGear(8)))
        assertFalse(RearCameraLogic.isReverse(RearCameraLogic.normalizeGear(1)))
        assertEquals(Gcp.GEAR_UNKNOWN, RearCameraLogic.normalizeGear(null))
        assertEquals(Gcp.GEAR_UNKNOWN, RearCameraLogic.normalizeGear(-1))
    }

    @Test
    fun speedConversion() {
        assertEquals(36f, RearCameraLogic.speedKmh(10f), 1e-4f)
        assertEquals(3.6f, RearCameraLogic.speedKmh(-1f), 1e-4f)
        assertEquals(-1f, RearCameraLogic.speedKmh(null), 0f)
        assertEquals(-1f, RearCameraLogic.speedKmh(Float.NaN), 0f)
    }

    @Test
    fun pacerSendsOnChangeAndEveryHalfSecond() {
        val pacer = RearCameraLogic.VehicleStatePacer(500)
        assertTrue(pacer.shouldSend(1_000, Gcp.GEAR_PARK, 0f))
        assertFalse(pacer.shouldSend(1_100, Gcp.GEAR_PARK, 0.4f))
        assertEquals(400, pacer.delayUntilHeartbeat(1_100))
        assertTrue("gear change goes out at once", pacer.shouldSend(1_150, Gcp.GEAR_REVERSE, 0.4f))
        assertFalse(pacer.shouldSend(1_400, Gcp.GEAR_REVERSE, 0.9f))
        assertTrue("speed moved by 1 km/h", pacer.shouldSend(1_450, Gcp.GEAR_REVERSE, 1.5f))
        assertTrue("speed became unknown", pacer.shouldSend(1_460, Gcp.GEAR_REVERSE, -1f))
        assertFalse(pacer.shouldSend(1_959, Gcp.GEAR_REVERSE, -1f))
        assertTrue("heartbeat", pacer.shouldSend(1_960, Gcp.GEAR_REVERSE, -1f))
        pacer.reset()
        assertEquals(0, pacer.delayUntilHeartbeat(1_970))
        assertTrue("first send after reset", pacer.shouldSend(1_970, Gcp.GEAR_REVERSE, -1f))
    }

    @Test
    fun pacerSendsAtOnceOnTurnFlagsChange() {
        val pacer = RearCameraLogic.VehicleStatePacer(500)
        assertTrue(pacer.shouldSend(1_000, Gcp.GEAR_DRIVE, 30f, 0x8))
        assertFalse(pacer.shouldSend(1_050, Gcp.GEAR_DRIVE, 30f, 0x8))
        assertTrue("left on", pacer.shouldSend(1_100, Gcp.GEAR_DRIVE, 30f, 0x9))
        assertFalse(pacer.shouldSend(1_150, Gcp.GEAR_DRIVE, 30f, 0x9))
        assertTrue("signal state became unknown", pacer.shouldSend(1_200, Gcp.GEAR_DRIVE, 30f, 0))
    }

    @Test
    fun hudRectPlacesTheContainerOnTheDisplay() {
        val r = RearCameraLogic.hudRect(
            displayWidthPx = 800, displayHeightPx = 480, density = 1.5f,
            xDp = 20f, yDp = 10f, widthDp = 400f, heightDp = 200f
        )
        assertEquals(Gcp.Rect(30, 15, 600, 300), r)
    }

    @Test
    fun hudRectClampsLikeTheOverlay() {
        // Wider than the display and past its bottom edge; then smaller than the minimum.
        val r = RearCameraLogic.hudRect(
            displayWidthPx = 800, displayHeightPx = 480, density = 1f,
            xDp = 50f, yDp = 600f, widthDp = 900f, heightDp = 300f
        )
        assertEquals(Gcp.Rect(0, 180, 800, 300), r)
        val tiny = RearCameraLogic.hudRect(
            displayWidthPx = 800, displayHeightPx = 480, density = 1f,
            xDp = 790f, yDp = 0f, widthDp = 10f, heightDp = 10f, minSizePx = 48
        )
        assertEquals(Gcp.Rect(800 - 48, 0, 48, 48), tiny)
    }

    @Test
    fun sideViewsSplitTheCameraAreaInHalves() {
        val full = Gcp.Rect(100, 40, 401, 200)
        assertEquals(Gcp.Rect(100, 40, 200, 200), RearCameraLogic.splitRect(full, Gcp.CAM_ROLE_LEFT))
        assertEquals(Gcp.Rect(300, 40, 201, 200), RearCameraLogic.splitRect(full, Gcp.CAM_ROLE_RIGHT))
        assertEquals("rear uses the whole area", full, RearCameraLogic.splitRect(full, Gcp.CAM_ROLE_REAR))
        // Horizontally mirrored HUD: the driver's left half is on the right of the display.
        assertEquals(Gcp.Rect(300, 40, 201, 200), RearCameraLogic.splitRect(full, Gcp.CAM_ROLE_LEFT, flipX = true))
        assertEquals(Gcp.Rect(100, 40, 200, 200), RearCameraLogic.splitRect(full, Gcp.CAM_ROLE_RIGHT, flipX = true))
    }

    /** A camera mounted exactly like the default rear view (heading 180, 34° down): zero rotation. */
    private val rearFit = PerCarCalibration.Fit(
        r = PerCarCalibration.transpose3(PerCarCalibration.viewMatrix(180.0, 34.0)),
        t = doubleArrayOf(0.0, 0.0, 0.0),
        f = 327.2, cx = 641.1, cy = 399.9, rms = 0.04, maxErr = 0.15, n = 178
    )

    private fun carView(heading: Float = 180f, down: Float = 34f, mirror: Boolean = true) =
        OverlayPrefs.CameraViewSettings(
            headingDeg = heading, downDeg = down, rollDeg = 0f, hfovDeg = 130f,
            panX = 0f, panY = -0.12f, mirror = mirror
        )

    @Test
    fun cameraViewsUseTheirInputsHalvesAndThePerCarLens() {
        val block = Gcp.Rect(0, 0, 400, 200)
        fun side(role: Int) = RearCameraLogic.cameraView(
            role = role, enabled = true, rect = block, flipX = false, z = 7, view = carView(),
            maxSpeedKmh = 40, fit = null, correction = true, vignette = false
        )
        val left = side(Gcp.CAM_ROLE_LEFT)
        val right = side(Gcp.CAM_ROLE_RIGHT)
        assertEquals(0, left.inputId)
        assertEquals(1, right.inputId)
        assertEquals(Gcp.Rect(0, 0, 200, 200), left.rect)
        assertEquals(Gcp.Rect(200, 0, 200, 200), right.rect)
        assertEquals(800, left.holdMs)
        assertEquals(40, right.maxSpeedKmh)
        assertTrue("no per-car fit: plain picture", left.noCorrection)
        val rear = RearCameraLogic.cameraView(
            role = Gcp.CAM_ROLE_REAR, enabled = true, rect = block, flipX = false, z = 7, view = carView(),
            maxSpeedKmh = 15, fit = rearFit, correction = true, vignette = true
        )
        assertEquals(Gcp.CAM_INPUT_REAR, rear.inputId)
        assertEquals(block, rear.rect)
        assertEquals(0, rear.holdMs)
        assertFalse(rear.noCorrection)
        assertEquals(327.2f, rear.fx, 1e-4f)
        assertEquals(327.2f, rear.fy, 1e-4f)
        assertEquals(641.1f, rear.cx, 1e-4f)
        assertEquals(399.9f, rear.cy, 1e-4f)
        assertEquals(PerCarCalibration.K_REF[0].toFloat(), rear.k1, 0f)
        assertEquals(PerCarCalibration.K_REF[3].toFloat(), rear.k4, 0f)
        assertEquals("view = camera: no rotation", 0f, rear.yawDeg, 1e-4f)
        assertEquals(0f, rear.pitchDeg, 1e-4f)
        assertEquals(0f, rear.rollDeg, 1e-4f)
        assertEquals(-0.12f, rear.panY, 0f)
        assertTrue(rear.mirror)
        assertTrue(rear.vignette)
    }

    @Test
    fun carTermsBecomeProtocolRotation() {
        fun rear(heading: Float, down: Float) = RearCameraLogic.cameraView(
            role = Gcp.CAM_ROLE_REAR, enabled = true, rect = Gcp.Rect(0, 0, 400, 200), flipX = false, z = 8,
            view = carView(heading, down), maxSpeedKmh = 15, fit = rearFit, correction = true, vignette = false
        )
        // Sign errata: positive pitch looks UP; looking 10° less down = pitch +10.
        assertEquals(10f, rear(180f, 24f).pitchDeg, 1e-3f)
        assertEquals(0f, rear(180f, 24f).yawDeg, 1e-3f)
        // Heading 190 turns toward the car's left, which is the camera's right when looking back.
        assertTrue(rear(190f, 34f).yawDeg > 5f)
        val plain = RearCameraLogic.cameraView(
            role = Gcp.CAM_ROLE_REAR, enabled = true, rect = Gcp.Rect(0, 0, 400, 200), flipX = false, z = 8,
            view = carView(190f, 34f), maxSpeedKmh = 15, fit = rearFit, correction = false, vignette = false
        )
        assertTrue(plain.noCorrection)
        assertEquals("correction off: no rotation sent", 0f, plain.yawDeg, 0f)
    }

    @Test
    fun mirroredHudFlipsTheMirrorFlag() {
        fun mirror(viewMirror: Boolean, flipX: Boolean) = RearCameraLogic.cameraView(
            role = Gcp.CAM_ROLE_LEFT, enabled = true, rect = Gcp.Rect(0, 0, 400, 200), flipX = flipX, z = 8,
            view = carView(mirror = viewMirror), maxSpeedKmh = 15, fit = null, correction = false, vignette = false
        ).mirror
        assertTrue(mirror(true, false))
        assertFalse(mirror(true, true))
        assertTrue(mirror(false, true))
    }

    @Test
    fun lensLimitKeepsTheCentreRayInsideCoverage() {
        val from = carView()
        val wanted = carView(heading = 180f, down = -80f)
        assertTrue(PerCarCalibration.axisAngleDeg(rearFit.r, 180.0, -80.0) > 100.0)
        val limited = RearCameraLogic.limitToLens(rearFit, from, wanted)
        val angle = PerCarCalibration.axisAngleDeg(rearFit.r, limited.headingDeg.toDouble(), limited.downDeg.toDouble())
        assertTrue("at the edge: $angle", angle <= 100.0 && angle > 99.5)
        assertEquals("inside: unchanged", carView(190f, 20f), RearCameraLogic.limitToLens(rearFit, from, carView(190f, 20f)))
    }

    @Test
    fun vignetteFlagIsBit3() {
        assertEquals(0x8, Gcp.CAM_FLAG_VIGNETTE)
        val v = view(mirror = true).copy(vignette = true)
        assertEquals(
            Gcp.CAM_FLAG_ENABLED or Gcp.CAM_FLAG_MIRROR or Gcp.CAM_FLAG_VIGNETTE,
            le(Gcp.camCfg(cfg(v))).getInt(16 + 4)
        )
        val raw = view(noCorrection = true).copy(vignette = true)
        assertEquals(
            "also with NO_CORRECTION",
            Gcp.CAM_FLAG_ENABLED or Gcp.CAM_FLAG_NO_CORRECTION or Gcp.CAM_FLAG_VIGNETTE,
            le(Gcp.camCfg(cfg(raw))).getInt(16 + 4)
        )
        val disabled = RearCameraLogic.cameraView(
            role = Gcp.CAM_ROLE_LEFT, enabled = false, rect = Gcp.Rect(0, 0, 400, 200), flipX = false, z = 8,
            view = carView(), maxSpeedKmh = 15, fit = null, correction = false, vignette = true
        )
        assertEquals("only enabled views", 0, le(Gcp.camCfg(cfg(disabled))).getInt(16 + 4) and Gcp.CAM_FLAG_VIGNETTE)
    }

    @Test
    fun debugOverridesApplyUntilTheyExpire() {
        val d = RearCameraLogic.DebugOverrides()
        assertFalse(d.active)
        assertEquals(Gcp.GEAR_DRIVE, d.gear(Gcp.GEAR_DRIVE))
        d.set(nowMs = 1_000, gear = 2, turn = RearCameraLogic.TurnSignals.parse("hazard"), speedKmh = 5f, seconds = 20)
        assertTrue(d.active)
        assertEquals(21_000, d.untilMs)
        assertEquals(Gcp.GEAR_REVERSE, d.gear(Gcp.GEAR_DRIVE))
        assertEquals(5f, d.speedKmh(60f), 0f)
        assertEquals(0xF, d.turn(RearCameraLogic.TurnSignals.UNKNOWN).flags)
        assertFalse(d.expire(20_999))
        assertTrue(d.expire(21_000))
        assertFalse(d.active)
        assertFalse("expires once", d.expire(21_001))
        assertEquals(Gcp.GEAR_DRIVE, d.gear(Gcp.GEAR_DRIVE))
        assertEquals(60f, d.speedKmh(60f), 0f)
    }

    @Test
    fun debugOverridesReplaceClampAndClear() {
        val d = RearCameraLogic.DebugOverrides()
        d.set(nowMs = 0, gear = 2, turn = null, speedKmh = null, seconds = 1_000)
        assertEquals("max 120 s", 120_000, d.untilMs)
        d.set(nowMs = 0, gear = null, turn = RearCameraLogic.TurnSignals.parse("left"), speedKmh = null, seconds = 0)
        assertEquals("min 1 s", 1_000, d.untilMs)
        assertEquals("a new broadcast replaces the old overrides", 8, d.gear(8))
        // DEBUG_REAR_GEAR --ei gear -1: nothing left to override.
        d.set(nowMs = 0, gear = -1, turn = null, speedKmh = Float.NaN)
        assertFalse(d.active)
        assertFalse(d.expire(5_000))
    }

    @Test
    fun correctionNeedsAFitAndThePref() {
        assertTrue(CameraCalibrationCheck.correctionEnabled(true, rearFit))
        assertFalse(CameraCalibrationCheck.correctionEnabled(false, rearFit))
        assertFalse(CameraCalibrationCheck.correctionEnabled(true, null))
        assertTrue(CameraCalibrationCheck.correctionBlocked(CameraCalibrationCheck.State.MISSING))
        assertTrue(CameraCalibrationCheck.correctionBlocked(CameraCalibrationCheck.State.CHECK_FAILED))
        assertFalse(CameraCalibrationCheck.correctionBlocked(CameraCalibrationCheck.State.AVAILABLE))
        assertFalse(CameraCalibrationCheck.correctionBlocked(CameraCalibrationCheck.State.NOT_CHECKED))
    }

    @Test
    fun noCorrectionFlagFollowsTheFit() {
        fun flags(pref: Boolean, fit: PerCarCalibration.Fit?): Int {
            val v = RearCameraLogic.cameraView(
                role = Gcp.CAM_ROLE_REAR, enabled = true, rect = Gcp.Rect(0, 0, 400, 200), flipX = false, z = 8,
                view = carView(), maxSpeedKmh = 15, fit = fit,
                correction = CameraCalibrationCheck.correctionEnabled(pref, fit), vignette = false
            )
            return le(Gcp.camCfg(cfg(v))).getInt(16 + 4) and Gcp.CAM_FLAG_NO_CORRECTION
        }
        assertEquals(0, flags(true, rearFit))
        assertEquals(4, flags(false, rearFit))
        assertEquals(4, flags(true, null))
    }

    private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
}
