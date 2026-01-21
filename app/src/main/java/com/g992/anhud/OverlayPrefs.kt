package com.g992.anhud

import android.content.Context
import android.graphics.PointF

object OverlayPrefs {
    private const val PREFS_NAME = "hud_overlay_prefs"
    private const val KEY_ENABLED = "overlay_enabled"
    private const val KEY_DISPLAY_ID = "overlay_display_id"
    private const val KEY_X_DP = "overlay_x_dp"
    private const val KEY_Y_DP = "overlay_y_dp"
    private const val KEY_CONTAINER_X_DP = "overlay_container_x_dp"
    private const val KEY_CONTAINER_Y_DP = "overlay_container_y_dp"
    private const val KEY_CONTAINER_WIDTH_DP = "overlay_container_width_dp"
    private const val KEY_CONTAINER_HEIGHT_DP = "overlay_container_height_dp"
    private const val KEY_NAV_X_DP = "overlay_nav_x_dp"
    private const val KEY_NAV_Y_DP = "overlay_nav_y_dp"
    private const val KEY_SPEED_X_DP = "overlay_speed_x_dp"
    private const val KEY_SPEED_Y_DP = "overlay_speed_y_dp"
    private const val KEY_SPEEDOMETER_X_DP = "overlay_speedometer_x_dp"
    private const val KEY_SPEEDOMETER_Y_DP = "overlay_speedometer_y_dp"
    private const val KEY_CLOCK_X_DP = "overlay_clock_x_dp"
    private const val KEY_CLOCK_Y_DP = "overlay_clock_y_dp"
    private const val KEY_NAV_SCALE = "overlay_nav_scale"
    private const val KEY_SPEED_SCALE = "overlay_speed_scale"
    private const val KEY_SPEEDOMETER_SCALE = "overlay_speedometer_scale"
    private const val KEY_CLOCK_SCALE = "overlay_clock_scale"
    private const val KEY_NAV_ALPHA = "overlay_nav_alpha"
    private const val KEY_SPEED_ALPHA = "overlay_speed_alpha"
    private const val KEY_SPEEDOMETER_ALPHA = "overlay_speedometer_alpha"
    private const val KEY_CLOCK_ALPHA = "overlay_clock_alpha"
    private const val KEY_CONTAINER_ALPHA = "overlay_container_alpha"
    private const val KEY_NAV_ENABLED = "overlay_nav_enabled"
    private const val KEY_SPEED_ENABLED = "overlay_speed_enabled"
    private const val KEY_SPEED_LIMIT_ALERT_ENABLED = "overlay_speed_limit_alert_enabled"
    private const val KEY_SPEED_LIMIT_ALERT_THRESHOLD = "overlay_speed_limit_alert_threshold"
    private const val KEY_SPEEDOMETER_ENABLED = "overlay_speedometer_enabled"
    private const val KEY_CLOCK_ENABLED = "overlay_clock_enabled"
    private const val KEY_NAV_APP_PACKAGE = "nav_app_package"
    private const val KEY_NAV_APP_LABEL = "nav_app_label"

    const val DISPLAY_ID_AUTO = -1
    const val CONTAINER_MIN_SIZE_PX = 150f
    const val SPEED_LIMIT_ALERT_THRESHOLD_MAX = 20

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun displayId(context: Context): Int {
        return prefs(context).getInt(KEY_DISPLAY_ID, DISPLAY_ID_AUTO)
    }

    fun setDisplayId(context: Context, displayId: Int) {
        prefs(context).edit().putInt(KEY_DISPLAY_ID, displayId).apply()
    }

    fun navPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val x = prefs.getFloat(KEY_NAV_X_DP, prefs.getFloat(KEY_X_DP, 16f))
        val y = prefs.getFloat(KEY_NAV_Y_DP, prefs.getFloat(KEY_Y_DP, 16f))
        return PointF(x, y)
    }

    fun containerPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val x = prefs.getFloat(KEY_CONTAINER_X_DP, 16f)
        val y = prefs.getFloat(KEY_CONTAINER_Y_DP, 16f)
        return PointF(x, y)
    }

    fun setContainerPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_CONTAINER_X_DP, xDp)
            .putFloat(KEY_CONTAINER_Y_DP, yDp)
            .apply()
    }

    fun setNavPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_NAV_X_DP, xDp)
            .putFloat(KEY_NAV_Y_DP, yDp)
            .apply()
    }

    fun speedPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val containerWidthDp = containerSizeDp(context).x
        val defaultSpeedX = (containerWidthDp - SPEED_BLOCK_SIZE_DP - DEFAULT_MARGIN_DP).coerceAtLeast(0f)
        val x = prefs.getFloat(KEY_SPEED_X_DP, defaultSpeedX)
        val y = prefs.getFloat(KEY_SPEED_Y_DP, DEFAULT_MARGIN_DP)
        return PointF(x, y)
    }

    fun setSpeedPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEED_X_DP, xDp)
            .putFloat(KEY_SPEED_Y_DP, yDp)
            .apply()
    }

    fun speedometerPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val containerSizeDp = containerSizeDp(context)
        val defaultSpeedometerY = (containerSizeDp.y - SPEEDOMETER_BLOCK_HEIGHT_DP - DEFAULT_MARGIN_DP).coerceAtLeast(0f)
        val x = prefs.getFloat(KEY_SPEEDOMETER_X_DP, DEFAULT_MARGIN_DP)
        val y = prefs.getFloat(KEY_SPEEDOMETER_Y_DP, defaultSpeedometerY)
        return PointF(x, y)
    }

    fun setSpeedometerPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEEDOMETER_X_DP, xDp)
            .putFloat(KEY_SPEEDOMETER_Y_DP, yDp)
            .apply()
    }

    fun clockPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val containerHeightDp = containerSizeDp(context).y
        val defaultClockY = (containerHeightDp - CLOCK_BLOCK_HEIGHT_DP - DEFAULT_MARGIN_DP).coerceAtLeast(0f)
        val x = prefs.getFloat(KEY_CLOCK_X_DP, DEFAULT_MARGIN_DP)
        val y = prefs.getFloat(KEY_CLOCK_Y_DP, defaultClockY)
        return PointF(x, y)
    }

    fun setClockPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_CLOCK_X_DP, xDp)
            .putFloat(KEY_CLOCK_Y_DP, yDp)
            .apply()
    }

    fun navScale(context: Context): Float {
        return prefs(context).getFloat(KEY_NAV_SCALE, 1f)
    }

    fun setNavScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_NAV_SCALE, scale)
            .apply()
    }

    fun speedScale(context: Context): Float {
        return prefs(context).getFloat(KEY_SPEED_SCALE, 1f)
    }

    fun setSpeedScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEED_SCALE, scale)
            .apply()
    }

    fun speedometerScale(context: Context): Float {
        return prefs(context).getFloat(KEY_SPEEDOMETER_SCALE, 1f)
    }

    fun setSpeedometerScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEEDOMETER_SCALE, scale)
            .apply()
    }

    fun clockScale(context: Context): Float {
        return prefs(context).getFloat(KEY_CLOCK_SCALE, 1f)
    }

    fun setClockScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_CLOCK_SCALE, scale)
            .apply()
    }

    fun navAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_NAV_ALPHA, 1f)
    }

    fun setNavAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_NAV_ALPHA, alpha)
            .apply()
    }

    fun speedAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_SPEED_ALPHA, 1f)
    }

    fun setSpeedAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEED_ALPHA, alpha)
            .apply()
    }

    fun speedometerAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_SPEEDOMETER_ALPHA, 1f)
    }

    fun setSpeedometerAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEEDOMETER_ALPHA, alpha)
            .apply()
    }

    fun clockAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_CLOCK_ALPHA, 1f)
    }

    fun setClockAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_CLOCK_ALPHA, alpha)
            .apply()
    }

    fun containerAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_CONTAINER_ALPHA, 1f)
    }

    fun setContainerAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_CONTAINER_ALPHA, alpha)
            .apply()
    }

    fun containerSizeDp(context: Context): PointF {
        val prefs = prefs(context)
        val defaultSize = defaultContainerSizeDp(context)
        val width = prefs.getFloat(KEY_CONTAINER_WIDTH_DP, defaultSize)
        val height = prefs.getFloat(KEY_CONTAINER_HEIGHT_DP, defaultSize)
        return PointF(width, height)
    }

    fun setContainerSizeDp(context: Context, widthDp: Float, heightDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_CONTAINER_WIDTH_DP, widthDp)
            .putFloat(KEY_CONTAINER_HEIGHT_DP, heightDp)
            .apply()
    }

    fun navEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NAV_ENABLED, true)
    }

    fun setNavEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NAV_ENABLED, enabled)
            .apply()
    }

    fun speedEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEED_ENABLED, true)
    }

    fun setSpeedEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPEED_ENABLED, enabled)
            .apply()
    }

    fun speedLimitAlertEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEED_LIMIT_ALERT_ENABLED, false)
    }

    fun setSpeedLimitAlertEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPEED_LIMIT_ALERT_ENABLED, enabled)
            .apply()
    }

    fun speedLimitAlertThreshold(context: Context): Int {
        return prefs(context).getInt(KEY_SPEED_LIMIT_ALERT_THRESHOLD, 0)
            .coerceIn(0, SPEED_LIMIT_ALERT_THRESHOLD_MAX)
    }

    fun setSpeedLimitAlertThreshold(context: Context, threshold: Int) {
        prefs(context).edit()
            .putInt(KEY_SPEED_LIMIT_ALERT_THRESHOLD, threshold.coerceIn(0, SPEED_LIMIT_ALERT_THRESHOLD_MAX))
            .apply()
    }

    fun speedometerEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEEDOMETER_ENABLED, true)
    }

    fun setSpeedometerEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPEEDOMETER_ENABLED, enabled)
            .apply()
    }

    fun clockEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CLOCK_ENABLED, true)
    }

    fun setClockEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CLOCK_ENABLED, enabled)
            .apply()
    }

    fun navAppPackage(context: Context): String {
        return prefs(context).getString(KEY_NAV_APP_PACKAGE, "").orEmpty()
    }

    fun navAppLabel(context: Context): String {
        return prefs(context).getString(KEY_NAV_APP_LABEL, "").orEmpty()
    }

    fun setNavApp(context: Context, packageName: String, label: String) {
        prefs(context).edit()
            .putString(KEY_NAV_APP_PACKAGE, packageName)
            .putString(KEY_NAV_APP_LABEL, label)
            .apply()
    }

    fun clearNavApp(context: Context) {
        prefs(context).edit()
            .remove(KEY_NAV_APP_PACKAGE)
            .remove(KEY_NAV_APP_LABEL)
            .apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun defaultContainerSizeDp(context: Context): Float {
        val density = context.resources.displayMetrics.density
        if (density <= 0f) {
            return maxOf(CONTAINER_MIN_SIZE_PX, CONTAINER_DEFAULT_SIZE_PX)
        }
        val minDp = CONTAINER_MIN_SIZE_PX / density
        val defaultDp = CONTAINER_DEFAULT_SIZE_PX / density
        return maxOf(minDp, defaultDp)
    }

    private const val SPEED_BLOCK_SIZE_DP = 40f
    private const val SPEEDOMETER_BLOCK_HEIGHT_DP = 32f
    private const val CLOCK_BLOCK_HEIGHT_DP = 24f
    private const val DEFAULT_MARGIN_DP = 16f
    private const val CONTAINER_DEFAULT_SIZE_PX = 255f
}
