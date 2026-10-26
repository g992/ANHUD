package com.g992.anhud

/**
 * Side (mirror) cameras, QCarCam inputs 0 (left) and 1 (right): view defaults in CAR terms
 * (see RearCameraCalibration). Both look back along the car, 20° outward, like a mirror.
 * Left and right are stored independently (each has its own view editor).
 */
object SideCameraCalibration {
    /** 180 = straight back; the left camera turns outward to the left (> 180), the right one to the right. */
    const val DEFAULT_LEFT_HEADING_DEG = 200f
    const val DEFAULT_RIGHT_HEADING_DEG = 160f
    const val DEFAULT_DOWN_DEG = 0f
    const val DEFAULT_ROLL_DEG = 0f
    const val DEFAULT_OUT_HFOV_DEG = 60f
    const val DEFAULT_PAN_X = 0f
    /** CAMCFG pan_y: negative looks further down. */
    const val DEFAULT_PAN_Y = -0.25f
    const val DEFAULT_MIRROR = true
    /** "до какой скорости показывать" default. */
    const val DEFAULT_MAX_SPEED_KMH = 15
    /** Fixed: the daemon keeps the side view this long after the turn signal goes off (bridges blinker gaps). */
    const val HOLD_MS = 800
}
