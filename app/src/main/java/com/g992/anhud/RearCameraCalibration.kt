package com.g992.anhud

/**
 * Rear camera (QCarCam input 3) view defaults in CAR terms: heading from straight ahead
 * (180 = straight back), down = degrees below the horizon, roll = picture rotation.
 * The lens (f, cx, cy) and the camera's mounting come from the per-car calibration
 * (hudbridge.PerCarCalibration), which turns these into CAMCFG yaw/pitch/roll.
 * Verified on car #1's previews.
 */
object RearCameraCalibration {
    const val DEFAULT_HEADING_DEG = 180f
    const val DEFAULT_DOWN_DEG = 34f
    const val DEFAULT_ROLL_DEG = 0f
    /** Default output horizontal FOV (zoom), degrees. */
    const val DEFAULT_OUT_HFOV_DEG = 130f
    const val DEFAULT_PAN_X = 0f
    /** CAMCFG pan_y: negative looks further down toward the bumper. */
    const val DEFAULT_PAN_Y = -0.12f
    const val DEFAULT_MIRROR = true
}
