package com.g992.anhud.hudbridge

import android.content.Context

/**
 * Per-car calibration result between sessions. Kept apart from overlay prefs: it belongs to the
 * car, not to a preset, so export/import and presets never carry or clear it.
 */
class CameraCalibrationStore(context: Context) : CameraCalibrationCheck.Cache {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(key: String): CameraCalibrationCheck.Result? {
        if (prefs.getString(KEY_TABLE, null) != key) return null
        return CameraCalibrationCheck.decode(prefs.getString(KEY_RESULT, null))
    }

    override fun save(result: CameraCalibrationCheck.Result) {
        prefs.edit()
            .putString(KEY_TABLE, result.key)
            .putString(KEY_RESULT, CameraCalibrationCheck.encode(result))
            .apply()
    }

    /** The last result whatever table it came from (for the settings while no session has checked yet). */
    fun last(): CameraCalibrationCheck.Result? = CameraCalibrationCheck.decode(prefs.getString(KEY_RESULT, null))

    private companion object {
        const val PREFS_NAME = "camera_calibration_prefs"
        const val KEY_TABLE = "table"
        const val KEY_RESULT = "result"
    }
}
