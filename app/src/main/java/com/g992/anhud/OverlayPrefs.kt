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
    private const val KEY_ARROW_X_DP = "overlay_arrow_x_dp"
    private const val KEY_ARROW_Y_DP = "overlay_arrow_y_dp"
    private const val KEY_SPEED_X_DP = "overlay_speed_x_dp"
    private const val KEY_SPEED_Y_DP = "overlay_speed_y_dp"
    private const val KEY_HUDSPEED_X_DP = "overlay_hudspeed_x_dp"
    private const val KEY_HUDSPEED_Y_DP = "overlay_hudspeed_y_dp"
    private const val KEY_ROAD_CAMERA_X_DP = "overlay_road_camera_x_dp"
    private const val KEY_ROAD_CAMERA_Y_DP = "overlay_road_camera_y_dp"
    private const val KEY_TRAFFIC_LIGHT_X_DP = "overlay_traffic_light_x_dp"
    private const val KEY_TRAFFIC_LIGHT_Y_DP = "overlay_traffic_light_y_dp"
    private const val KEY_SPEEDOMETER_X_DP = "overlay_speedometer_x_dp"
    private const val KEY_SPEEDOMETER_Y_DP = "overlay_speedometer_y_dp"
    private const val KEY_CLOCK_X_DP = "overlay_clock_x_dp"
    private const val KEY_CLOCK_Y_DP = "overlay_clock_y_dp"
    private const val KEY_NAV_SCALE = "overlay_nav_scale"
    private const val KEY_NAV_TEXT_SCALE = "overlay_nav_text_scale"
    private const val KEY_SPEED_TEXT_SCALE = "overlay_speed_text_scale"
    private const val KEY_ARROW_SCALE = "overlay_arrow_scale"
    private const val KEY_SPEED_SCALE = "overlay_speed_scale"
    private const val KEY_HUDSPEED_SCALE = "overlay_hudspeed_scale"
    private const val KEY_ROAD_CAMERA_SCALE = "overlay_road_camera_scale"
    private const val KEY_TRAFFIC_LIGHT_SCALE = "overlay_traffic_light_scale"
    private const val KEY_SPEEDOMETER_SCALE = "overlay_speedometer_scale"
    private const val KEY_CLOCK_SCALE = "overlay_clock_scale"
    private const val KEY_NAV_ALPHA = "overlay_nav_alpha"
    private const val KEY_ARROW_ALPHA = "overlay_arrow_alpha"
    private const val KEY_SPEED_ALPHA = "overlay_speed_alpha"
    private const val KEY_HUDSPEED_ALPHA = "overlay_hudspeed_alpha"
    private const val KEY_ROAD_CAMERA_ALPHA = "overlay_road_camera_alpha"
    private const val KEY_TRAFFIC_LIGHT_ALPHA = "overlay_traffic_light_alpha"
    private const val KEY_SPEEDOMETER_ALPHA = "overlay_speedometer_alpha"
    private const val KEY_CLOCK_ALPHA = "overlay_clock_alpha"
    private const val KEY_CONTAINER_ALPHA = "overlay_container_alpha"
    private const val KEY_NAV_ENABLED = "overlay_nav_enabled"
    private const val KEY_ARROW_ENABLED = "overlay_arrow_enabled"
    private const val KEY_ARROW_ONLY_WHEN_NO_ICON = "overlay_arrow_only_when_no_icon"
    private const val KEY_SPEED_ENABLED = "overlay_speed_enabled"
    private const val KEY_SPEED_LIMIT_FROM_HUDSPEED = "overlay_speed_limit_from_hudspeed"
    private const val KEY_HUDSPEED_ENABLED = "overlay_hudspeed_enabled"
    private const val KEY_ROAD_CAMERA_ENABLED = "overlay_road_camera_enabled"
    private const val KEY_TRAFFIC_LIGHT_ENABLED = "overlay_traffic_light_enabled"
    private const val KEY_SPEED_LIMIT_ALERT_ENABLED = "overlay_speed_limit_alert_enabled"
    private const val KEY_SPEED_LIMIT_ALERT_THRESHOLD = "overlay_speed_limit_alert_threshold"
    private const val KEY_SPEEDOMETER_ENABLED = "overlay_speedometer_enabled"
    private const val KEY_CLOCK_ENABLED = "overlay_clock_enabled"
    private const val KEY_TRAFFIC_LIGHT_MAX_ACTIVE = "overlay_traffic_light_max_active"
    private const val KEY_NATIVE_NAV_ENABLED = "native_nav_enabled"
    private const val KEY_MAP_ENABLED = "overlay_map_enabled"
    private const val KEY_CAMERA_TIMEOUT_NEAR = "camera_timeout_near"
    private const val KEY_CAMERA_TIMEOUT_FAR = "camera_timeout_far"
    private const val KEY_TRAFFIC_LIGHT_TIMEOUT = "traffic_light_timeout"
    private const val KEY_NAV_NOTIFICATION_END_TIMEOUT = "nav_notification_end_timeout"
    private const val KEY_ROAD_CAMERA_TIMEOUT = "road_camera_timeout"
    private const val KEY_SPEED_CORRECTION = "speed_correction"
    private const val KEY_GUIDE_SHOWN = "guide_shown"
    private const val KEY_NAV_WIDTH_DP = "overlay_nav_width_dp"

    const val ICON_SIZE_DP = 48f
    const val NAV_WIDTH_MIN_DP = ICON_SIZE_DP * 2
    const val CONTAINER_MIN_SIZE_PX = 150f
    const val SPEED_LIMIT_ALERT_THRESHOLD_MAX = 20
    const val TIMEOUT_MAX = 360
    const val SPEED_CORRECTION_MIN = -10
    const val SPEED_CORRECTION_MAX = 10

    fun isEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun displayId(context: Context): Int {
        val prefs = prefs(context)
        val stored = prefs.getInt(KEY_DISPLAY_ID, Int.MIN_VALUE)
        if (stored >= 0) {
            return stored
        }
        val initial = HudDisplayUtils.DEFAULT_DISPLAY_ID
        prefs.edit().putInt(KEY_DISPLAY_ID, initial).apply()
        return initial
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

    fun arrowPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val defaultPos = navPositionDp(context)
        val x = prefs.getFloat(KEY_ARROW_X_DP, defaultPos.x)
        val y = prefs.getFloat(KEY_ARROW_Y_DP, defaultPos.y)
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

    fun navWidthDp(context: Context): Float {
        val prefs = prefs(context)
        val containerWidth = containerSizeDp(context).x
        val defaultWidth = containerWidth.coerceAtLeast(NAV_WIDTH_MIN_DP)
        return prefs.getFloat(KEY_NAV_WIDTH_DP, defaultWidth)
            .coerceIn(NAV_WIDTH_MIN_DP, containerWidth)
    }

    fun setNavWidthDp(context: Context, widthDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_NAV_WIDTH_DP, widthDp)
            .apply()
    }

    fun setArrowPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_ARROW_X_DP, xDp)
            .putFloat(KEY_ARROW_Y_DP, yDp)
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

    fun hudSpeedPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val containerWidthDp = containerSizeDp(context).x
        val defaultX = (containerWidthDp - HUD_SPEED_BLOCK_SIZE_DP - DEFAULT_MARGIN_DP).coerceAtLeast(0f)
        val defaultY = (DEFAULT_MARGIN_DP + SPEED_BLOCK_SIZE_DP + HUD_SPEED_BLOCK_GAP_DP).coerceAtLeast(0f)
        val x = prefs.getFloat(KEY_HUDSPEED_X_DP, defaultX)
        val y = prefs.getFloat(KEY_HUDSPEED_Y_DP, defaultY)
        return PointF(x, y)
    }

    fun setHudSpeedPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_HUDSPEED_X_DP, xDp)
            .putFloat(KEY_HUDSPEED_Y_DP, yDp)
            .apply()
    }

    fun roadCameraPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val hudSpeedPos = hudSpeedPositionDp(context)
        val defaultX = hudSpeedPos.x
        val defaultY = (hudSpeedPos.y + HUD_SPEED_BLOCK_SIZE_DP + ROAD_CAMERA_BLOCK_GAP_DP)
            .coerceAtLeast(0f)
        val x = prefs.getFloat(KEY_ROAD_CAMERA_X_DP, defaultX)
        val y = prefs.getFloat(KEY_ROAD_CAMERA_Y_DP, defaultY)
        return PointF(x, y)
    }

    fun setRoadCameraPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_ROAD_CAMERA_X_DP, xDp)
            .putFloat(KEY_ROAD_CAMERA_Y_DP, yDp)
            .apply()
    }

    fun trafficLightPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val hudSpeedPos = hudSpeedPositionDp(context)
        val defaultX = hudSpeedPos.x
        val defaultY = (hudSpeedPos.y + HUD_SPEED_BLOCK_SIZE_DP + TRAFFIC_LIGHT_BLOCK_GAP_DP)
            .coerceAtLeast(0f)
        val x = prefs.getFloat(KEY_TRAFFIC_LIGHT_X_DP, defaultX)
        val y = prefs.getFloat(KEY_TRAFFIC_LIGHT_Y_DP, defaultY)
        return PointF(x, y)
    }

    fun setTrafficLightPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_TRAFFIC_LIGHT_X_DP, xDp)
            .putFloat(KEY_TRAFFIC_LIGHT_Y_DP, yDp)
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

    fun navTextScale(context: Context): Float {
        return prefs(context).getFloat(KEY_NAV_TEXT_SCALE, 1f)
    }

    fun setNavTextScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_NAV_TEXT_SCALE, scale)
            .apply()
    }

    fun speedTextScale(context: Context): Float {
        return prefs(context).getFloat(KEY_SPEED_TEXT_SCALE, 1f)
    }

    fun setSpeedTextScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_SPEED_TEXT_SCALE, scale)
            .apply()
    }

    fun arrowScale(context: Context): Float {
        return prefs(context).getFloat(KEY_ARROW_SCALE, 1f)
    }

    fun setArrowScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_ARROW_SCALE, scale)
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

    fun hudSpeedScale(context: Context): Float {
        return prefs(context).getFloat(KEY_HUDSPEED_SCALE, 1f)
    }

    fun setHudSpeedScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_HUDSPEED_SCALE, scale)
            .apply()
    }

    fun roadCameraScale(context: Context): Float {
        return prefs(context).getFloat(KEY_ROAD_CAMERA_SCALE, 1f)
    }

    fun setRoadCameraScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_ROAD_CAMERA_SCALE, scale)
            .apply()
    }

    fun trafficLightScale(context: Context): Float {
        return prefs(context).getFloat(KEY_TRAFFIC_LIGHT_SCALE, 1f)
    }

    fun setTrafficLightScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_TRAFFIC_LIGHT_SCALE, scale)
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

    fun arrowAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_ARROW_ALPHA, 1f)
    }

    fun setArrowAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_ARROW_ALPHA, alpha)
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

    fun hudSpeedAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_HUDSPEED_ALPHA, 1f)
    }

    fun setHudSpeedAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_HUDSPEED_ALPHA, alpha)
            .apply()
    }

    fun roadCameraAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_ROAD_CAMERA_ALPHA, 1f)
    }

    fun setRoadCameraAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_ROAD_CAMERA_ALPHA, alpha)
            .apply()
    }

    fun trafficLightAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_TRAFFIC_LIGHT_ALPHA, 1f)
    }

    fun setTrafficLightAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_TRAFFIC_LIGHT_ALPHA, alpha)
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

    fun arrowEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ARROW_ENABLED, false)
    }

    fun setArrowEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ARROW_ENABLED, enabled)
            .apply()
    }

    fun arrowOnlyWhenNoIcon(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ARROW_ONLY_WHEN_NO_ICON, false)
    }

    fun setArrowOnlyWhenNoIcon(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ARROW_ONLY_WHEN_NO_ICON, enabled)
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

    fun speedLimitFromHudSpeed(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEED_LIMIT_FROM_HUDSPEED, false)
    }

    fun setSpeedLimitFromHudSpeed(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPEED_LIMIT_FROM_HUDSPEED, enabled)
            .apply()
    }

    fun hudSpeedEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HUDSPEED_ENABLED, true)
    }

    fun setHudSpeedEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HUDSPEED_ENABLED, enabled)
            .apply()
    }

    fun roadCameraEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ROAD_CAMERA_ENABLED, true)
    }

    fun setRoadCameraEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ROAD_CAMERA_ENABLED, enabled)
            .apply()
    }

    fun trafficLightEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TRAFFIC_LIGHT_ENABLED, true)
    }

    fun setTrafficLightEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_TRAFFIC_LIGHT_ENABLED, enabled)
            .apply()
    }

    fun trafficLightMaxActive(context: Context): Int {
        val value = prefs(context).getInt(KEY_TRAFFIC_LIGHT_MAX_ACTIVE, 3)
        return value.coerceAtLeast(1)
    }

    fun setTrafficLightMaxActive(context: Context, maxActive: Int) {
        prefs(context).edit()
            .putInt(KEY_TRAFFIC_LIGHT_MAX_ACTIVE, maxActive.coerceAtLeast(1))
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

    fun nativeNavEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NATIVE_NAV_ENABLED, false)
    }

    fun setNativeNavEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NATIVE_NAV_ENABLED, enabled)
            .apply()
    }

    fun mapEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MAP_ENABLED, false)
    }

    fun setMapEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_MAP_ENABLED, enabled)
            .apply()
    }

    fun cameraTimeoutNear(context: Context): Int {
        return prefs(context).getInt(KEY_CAMERA_TIMEOUT_NEAR, 3)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setCameraTimeoutNear(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_CAMERA_TIMEOUT_NEAR, timeout.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    fun cameraTimeoutFar(context: Context): Int {
        return prefs(context).getInt(KEY_CAMERA_TIMEOUT_FAR, 0)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setCameraTimeoutFar(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_CAMERA_TIMEOUT_FAR, timeout.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    fun trafficLightTimeout(context: Context): Int {
        return prefs(context).getInt(KEY_TRAFFIC_LIGHT_TIMEOUT, 2)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setTrafficLightTimeout(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_TRAFFIC_LIGHT_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    fun navNotificationEndTimeout(context: Context): Int {
        return prefs(context).getInt(KEY_NAV_NOTIFICATION_END_TIMEOUT, 2)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setNavNotificationEndTimeout(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_NAV_NOTIFICATION_END_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    fun roadCameraTimeout(context: Context): Int {
        return prefs(context).getInt(KEY_ROAD_CAMERA_TIMEOUT, 2)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setRoadCameraTimeout(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_ROAD_CAMERA_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    fun speedCorrection(context: Context): Int {
        return prefs(context).getInt(KEY_SPEED_CORRECTION, 0)
            .coerceIn(SPEED_CORRECTION_MIN, SPEED_CORRECTION_MAX)
    }

    fun setSpeedCorrection(context: Context, correction: Int) {
        prefs(context).edit()
            .putInt(KEY_SPEED_CORRECTION, correction.coerceIn(SPEED_CORRECTION_MIN, SPEED_CORRECTION_MAX))
            .apply()
    }

    fun guideShown(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_GUIDE_SHOWN, false)
    }

    fun setGuideShown(context: Context, shown: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_GUIDE_SHOWN, shown)
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
    private const val HUD_SPEED_BLOCK_SIZE_DP = 48f
    private const val HUD_SPEED_BLOCK_GAP_DP = 8f
    private const val ROAD_CAMERA_BLOCK_GAP_DP = 8f
    private const val TRAFFIC_LIGHT_BLOCK_GAP_DP = 8f
    private const val SPEEDOMETER_BLOCK_HEIGHT_DP = 32f
    private const val CLOCK_BLOCK_HEIGHT_DP = 24f
    private const val DEFAULT_MARGIN_DP = 16f
    private const val CONTAINER_DEFAULT_SIZE_PX = 255f
}
