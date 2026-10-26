package com.g992.anhud.hudbridge

import com.g992.anhud.OverlayPrefs
import com.g992.anhud.SideCameraCalibration
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure parts of the camera feature (rear on reverse, side cameras on turn signals): HUD placement,
 * CAMCFG contents, VEHSTATE flags and pacing, debug overrides.
 */
object RearCameraLogic {
    const val HEARTBEAT_MS = 500L
    const val SPEED_CHANGE_KMH = 1f
    const val MS_TO_KMH = 3.6f
    /** Turn signal state older than this is not sent as valid (SensorDataService polls every 150 ms on
     *  the main looper, which stalls for 1.5+ s at times; 1 s made VALID flap on the car). */
    const val TURN_STALE_MS = 3_000L
    const val DEBUG_DEFAULT_SECONDS = 20
    const val DEBUG_MAX_SECONDS = 120
    /** "Показать на проекции" from the view editor: only while the car stands, at most this long. */
    const val PREVIEW_MAX_SPEED_KMH = 1f
    const val PREVIEW_MAX_MS = 5 * 60_000L
    /** The editor sends parameter changes at most this often. */
    const val PREVIEW_UPDATE_MS = 100L
    // ISensorEvent.GEAR_*: GEAR_SELECTION stays P on some cars (G636), the ECarX gear sensor follows the lever.
    const val ECARX_GEAR_NEUTRAL = 0x200210
    const val ECARX_GEAR_DRIVE = 0x200220
    const val ECARX_GEAR_PARK = 0x200230
    const val ECARX_GEAR_REVERSE = 0x200240

    /** Raw VHAL gear (GEAR_SELECTION / CURRENT_GEAR) as sent in VEHSTATE; null = not readable. */
    fun normalizeGear(raw: Int?): Int = raw?.takeIf { it >= 0 } ?: Gcp.GEAR_UNKNOWN

    fun isReverse(gear: Int): Boolean = gear == Gcp.GEAR_REVERSE

    /** ECarX ISensorEvent gear (SENSOR_TYPE_GEAR) as a VHAL gear; null = unknown. */
    fun gearFromEcarx(event: Int?): Int? = when (event) {
        ECARX_GEAR_PARK -> Gcp.GEAR_PARK
        ECARX_GEAR_REVERSE -> Gcp.GEAR_REVERSE
        ECARX_GEAR_NEUTRAL -> Gcp.GEAR_NEUTRAL
        ECARX_GEAR_DRIVE -> Gcp.GEAR_DRIVE
        else -> null
    }

    /** VHAL speed (m/s) to km/h; null/NaN/negative = unknown (-1). Reversing reports negative on some cars. */
    fun speedKmh(metersPerSecond: Float?): Float {
        if (metersPerSecond == null || !metersPerSecond.isFinite()) return -1f
        return abs(metersPerSecond) * MS_TO_KMH
    }

    /** Speed known and at most [PREVIEW_MAX_SPEED_KMH]. */
    fun isStationary(speedKmh: Float): Boolean = speedKmh >= 0f && speedKmh <= PREVIEW_MAX_SPEED_KMH

    /**
     * The camera area in HUD pixels: the HUD container (or the separate camera container) placed
     * on the render display exactly like HudOverlayController places its window.
     */
    fun hudRect(
        displayWidthPx: Int,
        displayHeightPx: Int,
        density: Float,
        xDp: Float,
        yDp: Float,
        widthDp: Float,
        heightDp: Float,
        minSizePx: Int = OverlayPrefs.CONTAINER_MIN_SIZE_PX.roundToInt()
    ): Gcp.Rect {
        val minPx = minSizePx.coerceAtLeast(1)
        val w = (widthDp * density).roundToInt().coerceIn(minPx, displayWidthPx.coerceAtLeast(minPx))
        val h = (heightDp * density).roundToInt().coerceIn(minPx, displayHeightPx.coerceAtLeast(minPx))
        val x = (xDp * density).roundToInt().coerceIn(0, (displayWidthPx - w).coerceAtLeast(0))
        val y = (yDp * density).roundToInt().coerceIn(0, (displayHeightPx - h).coerceAtLeast(0))
        return Gcp.Rect(x, y, w, h)
    }

    /**
     * Side views share the camera area: the left camera gets its left half, the right camera the
     * right half, as the driver sees them. A horizontally mirrored HUD (the overlay content is
     * flipped) swaps the halves on the display.
     */
    fun splitRect(full: Gcp.Rect, role: Int, flipX: Boolean = false): Gcp.Rect {
        if (role != Gcp.CAM_ROLE_LEFT && role != Gcp.CAM_ROLE_RIGHT) return full
        val leftW = full.w / 2
        val leftHalf = Gcp.Rect(full.x, full.y, leftW, full.h)
        val rightHalf = Gcp.Rect(full.x + leftW, full.y, full.w - leftW, full.h)
        val viewerLeft = role == Gcp.CAM_ROLE_LEFT
        return if (viewerLeft != flipX) leftHalf else rightHalf
    }

    fun inputOf(role: Int): Int = when (role) {
        Gcp.CAM_ROLE_LEFT -> Gcp.CAM_INPUT_LEFT
        Gcp.CAM_ROLE_RIGHT -> Gcp.CAM_INPUT_RIGHT
        Gcp.CAM_ROLE_FRONT -> Gcp.CAM_INPUT_FRONT
        else -> Gcp.CAM_INPUT_REAR
    }

    fun slotOf(role: Int): OverlayPrefs.CameraSlot = when (role) {
        Gcp.CAM_ROLE_LEFT -> OverlayPrefs.CameraSlot.LEFT
        Gcp.CAM_ROLE_RIGHT -> OverlayPrefs.CameraSlot.RIGHT
        else -> OverlayPrefs.CameraSlot.REAR
    }

    fun roleOf(slot: OverlayPrefs.CameraSlot): Int = when (slot) {
        OverlayPrefs.CameraSlot.LEFT -> Gcp.CAM_ROLE_LEFT
        OverlayPrefs.CameraSlot.RIGHT -> Gcp.CAM_ROLE_RIGHT
        OverlayPrefs.CameraSlot.REAR -> Gcp.CAM_ROLE_REAR
    }

    /** Lens sent for views without a per-car fit (NO_CORRECTION: the daemon ignores it). */
    private val NOMINAL_LENS = CameraViewRenderer.Lens(
        PerCarCalibration.F_INIT,
        PerCarCalibration.CX_INIT,
        PerCarCalibration.CY_INIT
    )

    /** Fitted lens of a camera: f, cx, cy per car, k fixed. */
    fun lensOf(fit: PerCarCalibration.Fit): CameraViewRenderer.Lens = CameraViewRenderer.Lens(fit.f, fit.cx, fit.cy)

    /**
     * What the daemon (and the view editor) draws for a car-terms [view] of a camera with [fit]:
     * the rotation relative to the physical camera. [mirror] is the final flag (already combined
     * with a mirrored HUD where needed).
     */
    fun rendererView(
        fit: PerCarCalibration.Fit,
        view: OverlayPrefs.CameraViewSettings,
        mirror: Boolean,
        vignette: Boolean
    ): CameraViewRenderer.View {
        val ypr = PerCarCalibration.protocolYpr(
            fit.r,
            view.headingDeg.toDouble(),
            view.downDeg.toDouble(),
            view.rollDeg.toDouble()
        )
        return CameraViewRenderer.View(
            hfovDeg = view.hfovDeg.toDouble(),
            panX = view.panX.toDouble(),
            panY = view.panY.toDouble(),
            mirror = mirror,
            yawDeg = ypr[0],
            pitchDeg = ypr[1],
            rollDeg = ypr[2],
            vignette = vignette
        )
    }

    /**
     * One CAMCFG view. With a per-car [fit] and [correction] on, the car-terms [view] becomes the
     * protocol rotation and the fitted intrinsics; otherwise NO_CORRECTION (plain picture).
     * Side views ([Gcp.CAM_ROLE_LEFT]/[Gcp.CAM_ROLE_RIGHT]) take their half of [rect].
     */
    fun cameraView(
        role: Int,
        enabled: Boolean,
        rect: Gcp.Rect,
        flipX: Boolean,
        z: Int,
        view: OverlayPrefs.CameraViewSettings,
        maxSpeedKmh: Int,
        fit: PerCarCalibration.Fit?,
        correction: Boolean,
        vignette: Boolean,
        holdMs: Int = if (role == Gcp.CAM_ROLE_REAR) 0 else SideCameraCalibration.HOLD_MS
    ): Gcp.CameraView {
        // A horizontally mirrored HUD mirrors the camera pictures as well.
        val mirror = view.mirror != flipX
        val corrected = if (correction && fit != null) rendererView(fit, view, mirror, vignette) else null
        val lens = fit?.let(::lensOf) ?: NOMINAL_LENS
        return Gcp.CameraView(
            role = role,
            enabled = enabled,
            mirror = mirror,
            inputId = inputOf(role),
            rect = splitRect(rect, role, flipX),
            z = z,
            fx = lens.f.toFloat(),
            fy = lens.f.toFloat(),
            cx = lens.cx.toFloat(),
            cy = lens.cy.toFloat(),
            k1 = lens.k[0].toFloat(),
            k2 = lens.k[1].toFloat(),
            k3 = lens.k[2].toFloat(),
            k4 = lens.k[3].toFloat(),
            outHfovDeg = view.hfovDeg,
            panX = view.panX,
            panY = view.panY,
            yawDeg = corrected?.yawDeg?.toFloat() ?: 0f,
            pitchDeg = corrected?.pitchDeg?.toFloat() ?: 0f,
            rollDeg = corrected?.rollDeg?.toFloat() ?: 0f,
            maxSpeedKmh = maxSpeedKmh,
            holdMs = holdMs,
            noCorrection = corrected == null,
            vignette = vignette && enabled
        )
    }

    /**
     * Keeps a view's centre ray within the lens coverage (at most [PerCarCalibration.MAX_RAY_ANGLE_DEG]
     * from the optical axis): when [wanted] is outside, the farthest point toward it from [from].
     */
    fun limitToLens(
        fit: PerCarCalibration.Fit,
        from: OverlayPrefs.CameraViewSettings,
        wanted: OverlayPrefs.CameraViewSettings
    ): OverlayPrefs.CameraViewSettings {
        fun inside(v: OverlayPrefs.CameraViewSettings) =
            PerCarCalibration.axisAngleDeg(fit.r, v.headingDeg.toDouble(), v.downDeg.toDouble()) <=
                PerCarCalibration.MAX_RAY_ANGLE_DEG
        if (inside(wanted) || !inside(from)) return wanted
        fun at(t: Float) = wanted.copy(
            headingDeg = from.headingDeg + (wanted.headingDeg - from.headingDeg) * t,
            downDeg = from.downDeg + (wanted.downDeg - from.downDeg) * t
        )
        var lo = 0f
        var hi = 1f
        repeat(20) {
            val mid = (lo + hi) / 2
            if (inside(at(mid))) lo = mid else hi = mid
        }
        return at(lo)
    }

    /** Turn signal state as carried in VEHSTATE; [valid] = the turn bits mean something. */
    data class TurnSignals(
        val left: Boolean = false,
        val right: Boolean = false,
        val hazard: Boolean = false,
        val valid: Boolean = false
    ) {
        val flags: Int
            get() {
                if (!valid) return 0
                var f = Gcp.VEH_TURN_VALID
                if (left) f = f or Gcp.VEH_TURN_LEFT
                if (right) f = f or Gcp.VEH_TURN_RIGHT
                if (hazard) f = f or Gcp.VEH_HAZARD
                return f
            }

        companion object {
            val UNKNOWN = TurnSignals()

            /** Real data: valid while the last successful read is at most [TURN_STALE_MS] old. */
            fun fromSensors(left: Boolean, right: Boolean, hazard: Boolean, readAtMs: Long, nowMs: Long): TurnSignals {
                val fresh = readAtMs > 0L && nowMs - readAtMs in 0..TURN_STALE_MS
                return if (fresh) TurnSignals(left, right, hazard, valid = true) else UNKNOWN
            }

            /** Debug override: left | right | hazard | off; null for anything else. */
            fun parse(value: String?): TurnSignals? = when (value?.trim()?.lowercase()) {
                "left" -> TurnSignals(left = true, valid = true)
                "right" -> TurnSignals(right = true, valid = true)
                // Like the car reports it: both lamps plus the hazard bit.
                "hazard" -> TurnSignals(left = true, right = true, hazard = true, valid = true)
                "off" -> TurnSignals(valid = true)
                else -> null
            }
        }
    }

    /**
     * Debug-only bench overrides (DEBUG_CAMERA broadcast). Each broadcast replaces all overrides;
     * they apply on top of the real data until they run out, then the real data is used again.
     */
    class DebugOverrides {
        var gear: Int? = null
            private set
        var turn: TurnSignals? = null
            private set
        var speedKmh: Float? = null
            private set
        var untilMs = 0L
            private set

        val active: Boolean
            get() = gear != null || turn != null || speedKmh != null

        fun set(nowMs: Long, gear: Int?, turn: TurnSignals?, speedKmh: Float?, seconds: Int = DEBUG_DEFAULT_SECONDS) {
            this.gear = gear?.takeIf { it >= 0 }
            this.turn = turn
            this.speedKmh = speedKmh?.takeIf { it.isFinite() }
            untilMs = if (active) nowMs + seconds.coerceIn(1, DEBUG_MAX_SECONDS) * 1000L else 0L
        }

        /** Drops the overrides once they ran out; true when that happened on this call. */
        fun expire(nowMs: Long): Boolean {
            if (!active || nowMs < untilMs) return false
            clear()
            return true
        }

        fun clear() {
            gear = null
            turn = null
            speedKmh = null
            untilMs = 0L
        }

        fun gear(real: Int): Int = gear ?: real
        fun turn(real: TurnSignals): TurnSignals = turn ?: real
        fun speedKmh(real: Float): Float = speedKmh ?: real

        override fun toString(): String = listOfNotNull(
            gear?.let { "передача $it" },
            turn?.let { t ->
                "поворотник " + when {
                    t.hazard -> "аварийка"
                    t.left -> "левый"
                    t.right -> "правый"
                    else -> "выкл"
                }
            },
            speedKmh?.let { "скорость ${it} км/ч" }
        ).joinToString(", ")
    }

    /**
     * VEHSTATE pacing: at once on a gear/flags change or a speed change of [SPEED_CHANGE_KMH],
     * else every [HEARTBEAT_MS].
     */
    class VehicleStatePacer(private val heartbeatMs: Long = HEARTBEAT_MS) {
        private var sentAt = 0L
        private var sentGear = 0
        private var sentSpeed = 0f
        private var sentFlags = 0
        private var sentOnce = false

        fun shouldSend(nowMs: Long, gear: Int, speedKmh: Float, flags: Int = 0): Boolean {
            val due = !sentOnce ||
                gear != sentGear ||
                flags != sentFlags ||
                speedChanged(sentSpeed, speedKmh) ||
                nowMs - sentAt >= heartbeatMs
            if (due) {
                sentOnce = true
                sentAt = nowMs
                sentGear = gear
                sentSpeed = speedKmh
                sentFlags = flags
            }
            return due
        }

        /** Next send is due in this many ms (for scheduling the heartbeat). */
        fun delayUntilHeartbeat(nowMs: Long): Long =
            if (!sentOnce) 0L else (sentAt + heartbeatMs - nowMs).coerceIn(0L, heartbeatMs)

        fun reset() {
            sentOnce = false
        }

        private fun speedChanged(before: Float, now: Float): Boolean {
            if ((before < 0f) != (now < 0f)) return true
            return abs(now - before) >= SPEED_CHANGE_KMH
        }
    }
}
