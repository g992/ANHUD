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
    private const val KEY_NAV_X_DP = "overlay_nav_x_dp"
    private const val KEY_NAV_Y_DP = "overlay_nav_y_dp"
    private const val KEY_SPEED_X_DP = "overlay_speed_x_dp"
    private const val KEY_SPEED_Y_DP = "overlay_speed_y_dp"
    private const val KEY_NAV_SCALE = "overlay_nav_scale"
    private const val KEY_SPEED_SCALE = "overlay_speed_scale"
    private const val KEY_NAV_ALPHA = "overlay_nav_alpha"
    private const val KEY_SPEED_ALPHA = "overlay_speed_alpha"
    private const val KEY_CONTAINER_ALPHA = "overlay_container_alpha"
    private const val KEY_NAV_APP_PACKAGE = "nav_app_package"
    private const val KEY_NAV_APP_LABEL = "nav_app_label"

    const val DISPLAY_ID_AUTO = -1
    const val CONTAINER_MAX_SIZE_PX = 255

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
        val containerSizeDp = containerSizeDp(context)
        val defaultSpeedX = (containerSizeDp - SPEED_BLOCK_SIZE_DP - DEFAULT_MARGIN_DP).coerceAtLeast(0f)
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

    fun containerAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_CONTAINER_ALPHA, 1f)
    }

    fun setContainerAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_CONTAINER_ALPHA, alpha)
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

    private fun containerSizeDp(context: Context): Float {
        val density = context.resources.displayMetrics.density
        if (density <= 0f) {
            return CONTAINER_MAX_SIZE_PX.toFloat()
        }
        return CONTAINER_MAX_SIZE_PX / density
    }

    private const val SPEED_BLOCK_SIZE_DP = 40f
    private const val DEFAULT_MARGIN_DP = 16f
}
