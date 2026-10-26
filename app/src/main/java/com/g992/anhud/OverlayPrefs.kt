package com.g992.anhud

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import kotlin.math.abs

object OverlayPrefs {
    enum class HudAlertSource(val storedValue: String) {
        HUDSPEED("HUDSPEED"),
        STRELKA("STRELKA");

        companion object {
            fun fromStoredValue(value: String?): HudAlertSource {
                return entries.firstOrNull { it.storedValue == value } ?: HUDSPEED
            }
        }
    }

    internal enum class DynamicHideTurnSpeedBucket {
        UP_TO_40,
        FROM_40_TO_60,
        FROM_60_TO_100,
        FROM_100_PLUS,
    }

    internal data class HideTurnThresholdResolution(
        val thresholdMeters: Int,
        val bucket: DynamicHideTurnSpeedBucket?,
    )

    data class DynamicHideTurnDistances(
        val upTo40Kmh: Int = HIDE_TURN_DYNAMIC_DEFAULT_UP_TO_40_METERS,
        val from40To60Kmh: Int = HIDE_TURN_DYNAMIC_DEFAULT_40_TO_60_METERS,
        val from60To100Kmh: Int = HIDE_TURN_DYNAMIC_DEFAULT_60_TO_100_METERS,
        val from100Kmh: Int = HIDE_TURN_DYNAMIC_DEFAULT_100_PLUS_METERS,
    ) {
        fun normalized(): DynamicHideTurnDistances {
            return copy(
                upTo40Kmh = normalizeDynamicHideTurnDistance(upTo40Kmh),
                from40To60Kmh = normalizeDynamicHideTurnDistance(from40To60Kmh),
                from60To100Kmh = normalizeDynamicHideTurnDistance(from60To100Kmh),
                from100Kmh = normalizeDynamicHideTurnDistance(from100Kmh),
            )
        }

        fun resolveForSpeed(speedKmh: Int?): Int {
            return resolveForBucket(resolveDynamicHideTurnSpeedBucket(speedKmh))
        }

        internal fun resolveForBucket(bucket: DynamicHideTurnSpeedBucket): Int {
            return when (bucket) {
                DynamicHideTurnSpeedBucket.UP_TO_40 -> upTo40Kmh
                DynamicHideTurnSpeedBucket.FROM_40_TO_60 -> from40To60Kmh
                DynamicHideTurnSpeedBucket.FROM_60_TO_100 -> from60To100Kmh
                DynamicHideTurnSpeedBucket.FROM_100_PLUS -> from100Kmh
            }
        }
    }

    private const val PREFS_NAME = "hud_overlay_prefs"
    private const val KEY_ENABLED = "overlay_enabled"
    private const val KEY_DISPLAY_ID = "overlay_display_id"
    private const val KEY_X_DP = "overlay_x_dp"
    private const val KEY_Y_DP = "overlay_y_dp"
    private const val KEY_CONTAINER_X_DP = "overlay_container_x_dp"
    private const val KEY_CONTAINER_Y_DP = "overlay_container_y_dp"
    private const val KEY_CONTAINER_WIDTH_DP = "overlay_container_width_dp"
    private const val KEY_CONTAINER_HEIGHT_DP = "overlay_container_height_dp"
    private const val KEY_MAP_X_DP = "overlay_map_x_dp"
    private const val KEY_MAP_Y_DP = "overlay_map_y_dp"
    private const val KEY_MAP_WIDTH_DP = "overlay_map_width_dp"
    private const val KEY_MAP_HEIGHT_DP = "overlay_map_height_dp"
    private const val KEY_NAV_X_DP = "overlay_nav_x_dp"
    private const val KEY_NAV_Y_DP = "overlay_nav_y_dp"
    private const val KEY_LANE_GUIDANCE_X_DP = "overlay_lane_guidance_x_dp"
    private const val KEY_LANE_GUIDANCE_Y_DP = "overlay_lane_guidance_y_dp"
    private const val KEY_ARROW_X_DP = "overlay_arrow_x_dp"
    private const val KEY_ARROW_Y_DP = "overlay_arrow_y_dp"
    private const val KEY_SPEED_X_DP = "overlay_speed_x_dp"
    private const val KEY_SPEED_Y_DP = "overlay_speed_y_dp"
    private const val KEY_HUD_ALERT_SOURCE = "overlay_hud_alert_source"
    private const val KEY_HUDSPEED_X_DP = "overlay_hudspeed_x_dp"
    private const val KEY_HUDSPEED_Y_DP = "overlay_hudspeed_y_dp"
    private const val KEY_STRELKA_X_DP = "overlay_strelka_x_dp"
    private const val KEY_STRELKA_Y_DP = "overlay_strelka_y_dp"
    private const val KEY_ROAD_CAMERA_X_DP = "overlay_road_camera_x_dp"
    private const val KEY_ROAD_CAMERA_Y_DP = "overlay_road_camera_y_dp"
    private const val KEY_TRAFFIC_LIGHT_X_DP = "overlay_traffic_light_x_dp"
    private const val KEY_TRAFFIC_LIGHT_Y_DP = "overlay_traffic_light_y_dp"
    private const val KEY_SPEEDOMETER_X_DP = "overlay_speedometer_x_dp"
    private const val KEY_SPEEDOMETER_Y_DP = "overlay_speedometer_y_dp"
    private const val KEY_TURN_SIGNALS_X_DP = "overlay_turn_signals_x_dp"
    private const val KEY_TURN_SIGNALS_Y_DP = "overlay_turn_signals_y_dp"
    internal const val KEY_TURN_SIGNALS_X_CENTER_MIGRATED = "overlay_turn_signals_x_center_migrated_v1"
    private const val KEY_CLOCK_X_DP = "overlay_clock_x_dp"
    private const val KEY_CLOCK_Y_DP = "overlay_clock_y_dp"
    private const val KEY_NAV_SCALE = "overlay_nav_scale"
    private const val KEY_LANE_GUIDANCE_SCALE = "overlay_lane_guidance_scale"
    private const val KEY_NAV_TEXT_SCALE = "overlay_nav_text_scale"
    private const val KEY_SPEED_TEXT_SCALE = "overlay_speed_text_scale"
    private const val KEY_ARROW_SCALE = "overlay_arrow_scale"
    private const val KEY_SPEED_SCALE = "overlay_speed_scale"
    private const val KEY_HUDSPEED_SCALE = "overlay_hudspeed_scale"
    private const val KEY_STRELKA_SCALE = "overlay_strelka_scale"
    private const val KEY_ROAD_CAMERA_SCALE = "overlay_road_camera_scale"
    private const val KEY_TRAFFIC_LIGHT_SCALE = "overlay_traffic_light_scale"
    private const val KEY_SPEEDOMETER_SCALE = "overlay_speedometer_scale"
    private const val KEY_TURN_SIGNALS_SCALE = "overlay_turn_signals_scale"
    private const val KEY_TURN_SIGNALS_SPACING_DP = "overlay_turn_signals_spacing_dp"
    private const val KEY_TURN_SIGNALS_ICON_STYLE = "overlay_turn_signals_icon_style"
    private const val KEY_TURN_SIGNALS_ICON_STYLE_MIGRATED = "overlay_turn_signals_icon_style_migrated_v2"
    private const val KEY_TURN_SIGNALS_CUSTOM_ICON_URI = "overlay_turn_signals_custom_icon_uri"
    private const val KEY_TURN_SIGNALS_CUSTOM_ICON_NAME = "overlay_turn_signals_custom_icon_name"
    private const val KEY_TURN_SIGNALS_CUSTOM_ICON_BASE_DIRECTION = "overlay_turn_signals_custom_icon_base_direction"
    private const val KEY_CLOCK_SCALE = "overlay_clock_scale"
    private const val KEY_NAV_ALPHA = "overlay_nav_alpha"
    private const val KEY_LANE_GUIDANCE_ALPHA = "overlay_lane_guidance_alpha"
    private const val KEY_ARROW_ALPHA = "overlay_arrow_alpha"
    private const val KEY_SPEED_ALPHA = "overlay_speed_alpha"
    private const val KEY_HUDSPEED_ALPHA = "overlay_hudspeed_alpha"
    private const val KEY_STRELKA_ALPHA = "overlay_strelka_alpha"
    private const val KEY_ROAD_CAMERA_ALPHA = "overlay_road_camera_alpha"
    private const val KEY_TRAFFIC_LIGHT_ALPHA = "overlay_traffic_light_alpha"
    private const val KEY_SPEEDOMETER_ALPHA = "overlay_speedometer_alpha"
    private const val KEY_TURN_SIGNALS_ALPHA = "overlay_turn_signals_alpha"
    private const val KEY_CLOCK_ALPHA = "overlay_clock_alpha"
    private const val KEY_CONTAINER_ALPHA = "overlay_container_alpha"
    private const val KEY_MAP_ALPHA = "overlay_map_alpha"
    private const val KEY_NAV_ENABLED = "overlay_nav_enabled"
    private const val KEY_NAV_HIDE_WHEN_MAP_ACTIVE = "overlay_nav_hide_when_map_active"
    private const val KEY_MAP_JAMS_ENABLED = "overlay_map_jams_enabled"
    private const val KEY_MAP_MINIMAP_ZOOM = "overlay_map_minimap_zoom"
    private const val KEY_MAP_MINIMAP_VIEW = "overlay_map_minimap_view"
    private const val KEY_MAP_MINIMAP_ROADS_ONLY = "overlay_map_minimap_roads_only"
    private const val KEY_MAP_MINIMAP_LABELS = "overlay_map_minimap_labels"
    private const val KEY_MAP_MINIMAP_CURSOR_SCALE = "overlay_map_minimap_cursor_scale"
    private const val KEY_MAP_MINIMAP_ROUTE_SCALE = "overlay_map_minimap_route_scale"
    private const val KEY_NAV_SHOW_DISTANCE = "overlay_nav_show_distance"
    private const val KEY_LANE_GUIDANCE_ENABLED = "overlay_lane_guidance_enabled"
    private const val KEY_LANE_GUIDANCE_HIDE_WHEN_MAP_ACTIVE = "overlay_lane_guidance_hide_when_map_active"
    private const val KEY_ARROW_ENABLED = "overlay_arrow_enabled"
    private const val KEY_ARROW_HIDE_WHEN_MAP_ACTIVE = "overlay_arrow_hide_when_map_active"
    private const val KEY_ARROW_ONLY_WHEN_NO_ICON = "overlay_arrow_only_when_no_icon"
    private const val KEY_SPEED_ENABLED = "overlay_speed_enabled"
    private const val KEY_SPEED_HIDE_WHEN_MAP_ACTIVE = "overlay_speed_hide_when_map_active"
    private const val KEY_SPEED_LIMIT_FROM_HUDSPEED = "overlay_speed_limit_from_hudspeed"
    private const val KEY_HUDSPEED_ENABLED = "overlay_hudspeed_enabled"
    private const val KEY_HUDSPEED_HIDE_WHEN_MAP_ACTIVE = "overlay_hudspeed_hide_when_map_active"
    private const val KEY_STRELKA_HIDE_WHEN_MAP_ACTIVE = "overlay_strelka_hide_when_map_active"
    private const val KEY_HUDSPEED_GPS_STATUS_ENABLED = "overlay_hudspeed_gps_status_enabled"
    private const val KEY_HUDSPEED_LIMIT_ENABLED = "overlay_hudspeed_limit_enabled"
    private const val KEY_HUDSPEED_LIMIT_ALERT_ENABLED = "overlay_hudspeed_limit_alert_enabled"
    private const val KEY_HUDSPEED_LIMIT_ALERT_THRESHOLD = "overlay_hudspeed_limit_alert_threshold"
    private const val KEY_ROAD_CAMERA_ENABLED = "overlay_road_camera_enabled"
    private const val KEY_ROAD_CAMERA_HIDE_WHEN_MAP_ACTIVE = "overlay_road_camera_hide_when_map_active"
    private const val KEY_TRAFFIC_LIGHT_ENABLED = "overlay_traffic_light_enabled"
    private const val KEY_TRAFFIC_LIGHT_HIDE_WHEN_MAP_ACTIVE = "overlay_traffic_light_hide_when_map_active"
    private const val KEY_SPEED_LIMIT_ALERT_ENABLED = "overlay_speed_limit_alert_enabled"
    private const val KEY_SPEED_LIMIT_ALERT_THRESHOLD = "overlay_speed_limit_alert_threshold"
    private const val KEY_SPEEDOMETER_ENABLED = "overlay_speedometer_enabled"
    private const val KEY_SPEEDOMETER_HIDE_WHEN_MAP_ACTIVE = "overlay_speedometer_hide_when_map_active"
    private const val KEY_SPEEDOMETER_SHOW_UNIT_TEXT = "overlay_speedometer_show_unit_text"
    private const val KEY_TURN_SIGNALS_ENABLED = "overlay_turn_signals_enabled"
    private const val KEY_TURN_SIGNALS_HIDE_WHEN_MAP_ACTIVE = "overlay_turn_signals_hide_when_map_active"
    private const val KEY_CLOCK_ENABLED = "overlay_clock_enabled"
    private const val KEY_CLOCK_HIDE_WHEN_MAP_ACTIVE = "overlay_clock_hide_when_map_active"
    private const val KEY_TRAFFIC_LIGHT_MAX_ACTIVE = "overlay_traffic_light_max_active"
    private const val KEY_TRAFFIC_LIGHT_DISPLAY_DISTANCE_M = "overlay_traffic_light_display_distance_m"
    private const val KEY_MAIN_MENU_ROAD_CAMERA_VISIBLE = "main_menu_road_camera_visible"
    private const val KEY_MAIN_MENU_TRAFFIC_LIGHT_VISIBLE = "main_menu_traffic_light_visible"
    private const val KEY_NATIVE_NAV_ENABLED = "native_nav_enabled"
    private const val KEY_MAP_ENABLED = "overlay_map_enabled"
    private const val KEY_LANE_GUIDANCE_SHOW_DISTANCE = "overlay_lane_guidance_show_distance"
    private const val KEY_LANE_GUIDANCE_DISTANCE_LIMIT_ENABLED = "overlay_lane_guidance_distance_limit_enabled"
    private const val KEY_LANE_GUIDANCE_MAX_DISTANCE_M = "overlay_lane_guidance_max_distance_m"
    private const val KEY_CAMERA_TIMEOUT_NEAR = "camera_timeout_near"
    private const val KEY_CAMERA_TIMEOUT_FAR = "camera_timeout_far"
    private const val KEY_NAV_NOTIFICATION_END_TIMEOUT = "nav_notification_end_timeout"
    private const val KEY_NAV_UPDATES_END_TIMEOUT = "nav_updates_end_timeout"
    private const val KEY_ROAD_CAMERA_TIMEOUT = "road_camera_timeout"
    private const val KEY_SPEEDOMETER_FREEZE_TIMEOUT = "speedometer_freeze_timeout"
    private const val KEY_INFO_MIRROR_STARSHEEP7 = "info_mirror_starsheep7"
    private const val KEY_INFO_MIRROR_GALAXY = "info_mirror_galaxy"
    private const val KEY_HIDE_TURN_WHEN_FAR_ENABLED = "hide_turn_when_far_enabled"
    private const val KEY_HIDE_TURN_WHEN_FAR_DISTANCE_METERS = "hide_turn_when_far_distance_meters"
    private const val KEY_HIDE_TURN_DYNAMIC_ENABLED = "hide_turn_dynamic_enabled"
    private const val KEY_HIDE_TURN_DYNAMIC_UP_TO_40_METERS = "hide_turn_dynamic_up_to_40_meters"
    private const val KEY_HIDE_TURN_DYNAMIC_40_TO_60_METERS = "hide_turn_dynamic_40_to_60_meters"
    private const val KEY_HIDE_TURN_DYNAMIC_60_TO_100_METERS = "hide_turn_dynamic_60_to_100_meters"
    private const val KEY_HIDE_TURN_DYNAMIC_100_PLUS_METERS = "hide_turn_dynamic_100_plus_meters"
    private const val KEY_HIDE_TURN_DYNAMIC_HIDE_MAP_BLOCK = "hide_turn_dynamic_hide_map_block"
    private const val KEY_GUIDE_SHOWN = "guide_shown"
    private const val KEY_NAV_WIDTH_DP = "overlay_nav_width_dp"
    internal const val KEY_CAMERAS_ENABLED = "overlay_cameras_enabled"
    internal const val KEY_CAMERAS_IN_CONTAINER = "overlay_cameras_in_container"
    internal const val KEY_CAMERAS_CORRECTION = "overlay_cameras_correction"
    internal const val KEY_CAMERA_CONTAINER_X_DP = "overlay_camera_container_x_dp"
    internal const val KEY_CAMERA_CONTAINER_Y_DP = "overlay_camera_container_y_dp"
    internal const val KEY_CAMERA_CONTAINER_WIDTH_DP = "overlay_camera_container_width_dp"
    internal const val KEY_CAMERA_CONTAINER_HEIGHT_DP = "overlay_camera_container_height_dp"
    // Before the camera settings dialog this was the only switch ("Камера на проекции при заднем ходе").
    internal const val KEY_REAR_CAMERA_ENABLED = "overlay_rear_camera_enabled"
    internal const val KEY_REAR_CAMERA_Z = "overlay_rear_camera_z"
    internal const val KEY_REAR_CAMERA_HFOV = "overlay_rear_camera_hfov"
    internal const val KEY_REAR_CAMERA_PAN_X = "overlay_rear_camera_pan_x"
    internal const val KEY_REAR_CAMERA_PAN_Y = "overlay_rear_camera_pan_y"
    internal const val KEY_REAR_CAMERA_MIRROR = "overlay_rear_camera_mirror"
    internal const val KEY_REAR_CAMERA_MAX_SPEED = "overlay_rear_camera_max_speed_kmh"
    // Views in car terms (heading/down/roll), see RearCameraCalibration.
    internal const val KEY_REAR_CAMERA_HEADING = "overlay_rear_camera_heading"
    internal const val KEY_REAR_CAMERA_DOWN = "overlay_rear_camera_down"
    internal const val KEY_REAR_CAMERA_ROLL = "overlay_rear_camera_roll"
    internal const val KEY_SIDE_CAMERAS_ENABLED = "overlay_side_cameras_enabled"
    internal const val KEY_SIDE_CAMERAS_HAZARD = "overlay_side_cameras_hazard"
    internal const val KEY_SIDE_CAMERA_MAX_SPEED = "overlay_side_camera_max_speed_kmh"
    internal const val KEY_LEFT_CAMERA_HEADING = "overlay_left_camera_heading"
    internal const val KEY_LEFT_CAMERA_DOWN = "overlay_left_camera_down"
    internal const val KEY_LEFT_CAMERA_ROLL = "overlay_left_camera_roll"
    internal const val KEY_LEFT_CAMERA_HFOV = "overlay_left_camera_hfov"
    internal const val KEY_LEFT_CAMERA_PAN_Y = "overlay_left_camera_pan_y"
    internal const val KEY_LEFT_CAMERA_MIRROR = "overlay_left_camera_mirror"
    internal const val KEY_RIGHT_CAMERA_HEADING = "overlay_right_camera_heading"
    internal const val KEY_RIGHT_CAMERA_DOWN = "overlay_right_camera_down"
    internal const val KEY_RIGHT_CAMERA_ROLL = "overlay_right_camera_roll"
    internal const val KEY_RIGHT_CAMERA_HFOV = "overlay_right_camera_hfov"
    internal const val KEY_RIGHT_CAMERA_PAN_Y = "overlay_right_camera_pan_y"
    internal const val KEY_RIGHT_CAMERA_MIRROR = "overlay_right_camera_mirror"
    internal const val KEY_CAMERAS_VIGNETTE = "overlay_cameras_vignette"
    // Camera-relative side placeholders (yaw/pitch were never set); replaced by the per-side car-terms keys.
    private val LEGACY_SIDE_CAMERA_KEYS = listOf(
        "overlay_side_camera_hfov",
        "overlay_side_camera_yaw",
        "overlay_side_camera_pitch",
        "overlay_side_camera_mirror"
    )

    const val ICON_SIZE_DP = 48f
    const val TURN_SIGNALS_ICON_SIZE_DP = 24f
    const val TURN_SIGNALS_ICON_STYLE_DEFAULT = 1
    const val NAV_WIDTH_MIN_DP = ICON_SIZE_DP * 2
    const val CONTAINER_MIN_SIZE_PX = 100f
    const val MAP_MIN_SIZE_DP = 48f
    // MapKit zoom level for the Yandex minimap (`minimap_zoom`); navigator's own default is 16.
    const val MINIMAP_ZOOM_MIN = 12f
    const val MINIMAP_ZOOM_MAX = 17.5f
    const val MINIMAP_ZOOM_STEP = 0.5f
    const val MINIMAP_ZOOM_DEFAULT = 15f
    // Navigator `minimap_view`: 0 = 3D tilt 40, 1 = 2D speed auto-zoom, 4 = perspective tilt 72 + auto-zoom.
    // 2 equals 1 once `minimap_zoom` is sent; 3 latches roads-only inside the navigator, so neither is offered.
    const val MINIMAP_VIEW_3D = 0
    const val MINIMAP_VIEW_2D = 1
    const val MINIMAP_VIEW_PERSPECTIVE = 4
    // Navigator clamps `minimap_overlay` / `minimap_route` to 0.25..2.5.
    const val MINIMAP_SCALE_MIN = 0.25f
    const val MINIMAP_SCALE_MAX = 2.5f
    const val MINIMAP_SCALE_STEP = 0.25f
    const val MINIMAP_SCALE_DEFAULT = 1f
    const val SPEED_LIMIT_ALERT_THRESHOLD_MAX = 20
    const val CAMERA_CONTAINER_MIN_SIZE_DP = 48f
    const val CAMERA_Z_MIN = 5
    const val CAMERA_Z_MAX = 10
    const val CAMERA_Z_DEFAULT = 8
    const val CAMERA_HFOV_MIN = 30f
    const val CAMERA_HFOV_MAX = 170f
    const val CAMERA_PAN_MIN = -0.5f
    const val CAMERA_PAN_MAX = 0.5f
    /** "Направление": at most this far from the camera's default heading, either way. */
    const val CAMERA_HEADING_RANGE = 90f
    const val CAMERA_DOWN_MIN = -30f
    const val CAMERA_DOWN_MAX = 80f
    const val CAMERA_ROLL_MIN = -45f
    const val CAMERA_ROLL_MAX = 45f
    const val REAR_CAMERA_MAX_SPEED_MAX = 30
    const val REAR_CAMERA_MAX_SPEED_DEFAULT = 15
    const val SIDE_CAMERA_MAX_SPEED_MIN = 15
    const val SIDE_CAMERA_MAX_SPEED_MAX = 300
    const val SIDE_CAMERA_MAX_SPEED_STEP = 5
    const val TIMEOUT_MAX = 360
    const val HIDE_TURN_DISTANCE_DEFAULT_METERS = 1000
    const val HIDE_TURN_DYNAMIC_DISTANCE_MIN_METERS = 100
    const val HIDE_TURN_DYNAMIC_DISTANCE_MAX_METERS = 10_000
    const val HIDE_TURN_DYNAMIC_DEFAULT_UP_TO_40_METERS = 100
    const val HIDE_TURN_DYNAMIC_DEFAULT_40_TO_60_METERS = 300
    const val HIDE_TURN_DYNAMIC_DEFAULT_60_TO_100_METERS = 500
    const val HIDE_TURN_DYNAMIC_DEFAULT_100_PLUS_METERS = 1_000
    private val HIDE_TURN_DISTANCE_STEPS_METERS = intArrayOf(
        100, 300, 500, 1000, 3000, 5000, 10_000, 30_000, 50_000
    )

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

    fun mapPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val defaultPos = defaultMapPositionDp(context)
        val x = prefs.getFloat(KEY_MAP_X_DP, defaultPos.x)
        val y = prefs.getFloat(KEY_MAP_Y_DP, defaultPos.y)
        return PointF(x, y)
    }

    fun setMapPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_MAP_X_DP, xDp)
            .putFloat(KEY_MAP_Y_DP, yDp)
            .apply()
    }

    fun setNavPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_NAV_X_DP, xDp)
            .putFloat(KEY_NAV_Y_DP, yDp)
            .apply()
    }

    fun laneGuidancePositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val navPos = navPositionDp(context)
        val x = prefs.getFloat(KEY_LANE_GUIDANCE_X_DP, navPos.x)
        val y = prefs.getFloat(
            KEY_LANE_GUIDANCE_Y_DP,
            (navPos.y + NAV_BLOCK_DEFAULT_HEIGHT_DP + LANE_GUIDANCE_BLOCK_GAP_DP).coerceAtLeast(0f)
        )
        return PointF(x, y)
    }

    fun setLaneGuidancePositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_LANE_GUIDANCE_X_DP, xDp)
            .putFloat(KEY_LANE_GUIDANCE_Y_DP, yDp)
            .apply()
    }

    fun navWidthDp(context: Context): Float {
        val prefs = prefs(context)
        val containerWidth = containerSizeDp(context).x
        val maxWidth = containerWidth.coerceAtLeast(0f)
        val minWidth = NAV_WIDTH_MIN_DP.coerceAtMost(maxWidth)
        val defaultWidth = containerWidth.coerceAtLeast(minWidth)
        return prefs.getFloat(KEY_NAV_WIDTH_DP, defaultWidth)
            .coerceIn(minWidth, maxWidth)
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

    fun hudAlertSource(context: Context): HudAlertSource {
        val stored = prefs(context).getString(KEY_HUD_ALERT_SOURCE, null)
        return HudAlertSource.fromStoredValue(stored)
    }

    fun setHudAlertSource(context: Context, source: HudAlertSource) {
        prefs(context).edit()
            .putString(KEY_HUD_ALERT_SOURCE, source.storedValue)
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

    fun strelkaPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val hudSpeedPosition = hudSpeedPositionDp(context)
        val x = prefs.getFloat(KEY_STRELKA_X_DP, hudSpeedPosition.x)
        val y = prefs.getFloat(KEY_STRELKA_Y_DP, hudSpeedPosition.y)
        return PointF(x, y)
    }

    fun setStrelkaPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_STRELKA_X_DP, xDp)
            .putFloat(KEY_STRELKA_Y_DP, yDp)
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

    fun turnSignalsPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val containerHeightDp = containerSizeDp(context).y
        val speedometerPos = speedometerPositionDp(context)
        val maxY = (containerHeightDp - TURN_SIGNALS_BLOCK_HEIGHT_DP - DEFAULT_MARGIN_DP).coerceAtLeast(0f)
        val defaultY = (speedometerPos.y + SPEEDOMETER_BLOCK_HEIGHT_DP + TURN_SIGNALS_BLOCK_GAP_DP)
            .coerceIn(0f, maxY)
        val defaultX = speedometerPos.x + turnSignalsHalfWidthDp(context)
        val x = when {
            prefs.getBoolean(KEY_TURN_SIGNALS_X_CENTER_MIGRATED, false) -> {
                prefs.getFloat(KEY_TURN_SIGNALS_X_DP, defaultX)
            }
            prefs.contains(KEY_TURN_SIGNALS_X_DP) -> {
                val migratedX = prefs.getFloat(KEY_TURN_SIGNALS_X_DP, speedometerPos.x) + turnSignalsHalfWidthDp(context)
                prefs.edit()
                    .putFloat(KEY_TURN_SIGNALS_X_DP, migratedX)
                    .putBoolean(KEY_TURN_SIGNALS_X_CENTER_MIGRATED, true)
                    .apply()
                migratedX
            }
            else -> defaultX
        }
        val y = prefs.getFloat(KEY_TURN_SIGNALS_Y_DP, defaultY)
        return PointF(x, y)
    }

    fun setTurnSignalsPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_TURN_SIGNALS_X_DP, xDp)
            .putFloat(KEY_TURN_SIGNALS_Y_DP, yDp)
            .putBoolean(KEY_TURN_SIGNALS_X_CENTER_MIGRATED, true)
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

    fun laneGuidanceScale(context: Context): Float {
        return prefs(context).getFloat(KEY_LANE_GUIDANCE_SCALE, 1f)
    }

    fun setLaneGuidanceScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_LANE_GUIDANCE_SCALE, scale)
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

    fun strelkaScale(context: Context): Float {
        return prefs(context).getFloat(KEY_STRELKA_SCALE, 1f)
    }

    fun setStrelkaScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_STRELKA_SCALE, scale)
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

    fun turnSignalsScale(context: Context): Float {
        return prefs(context).getFloat(KEY_TURN_SIGNALS_SCALE, 1f)
    }

    fun setTurnSignalsScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_TURN_SIGNALS_SCALE, scale)
            .apply()
    }

    internal fun turnSignalsPositionUsesCenterX(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TURN_SIGNALS_X_CENTER_MIGRATED, false)
    }

    fun turnSignalsMaxSpacingDp(context: Context, scale: Float = turnSignalsScale(context)): Float {
        val containerWidthDp = containerSizeDp(context).x
        val safeScale = scale.coerceAtLeast(0.01f)
        return ((containerWidthDp / safeScale) - TURN_SIGNALS_ICON_SIZE_DP)
            .coerceAtLeast(TURN_SIGNALS_ICON_SIZE_DP)
    }

    fun turnSignalsSpacingDp(context: Context): Float {
        val maxSpacingDp = turnSignalsMaxSpacingDp(context)
        val defaultSpacingDp = TURN_SIGNALS_SPACING_DEFAULT_DP.coerceIn(
            TURN_SIGNALS_ICON_SIZE_DP,
            maxSpacingDp
        )
        return prefs(context)
            .getFloat(KEY_TURN_SIGNALS_SPACING_DP, defaultSpacingDp)
            .coerceIn(TURN_SIGNALS_ICON_SIZE_DP, maxSpacingDp)
    }

    fun setTurnSignalsSpacingDp(context: Context, spacingDp: Float) {
        val maxSpacingDp = turnSignalsMaxSpacingDp(context)
        prefs(context).edit()
            .putFloat(
                KEY_TURN_SIGNALS_SPACING_DP,
                spacingDp.coerceIn(TURN_SIGNALS_ICON_SIZE_DP, maxSpacingDp)
            )
            .apply()
    }

    private fun turnSignalsHalfWidthDp(
        context: Context,
        scale: Float = turnSignalsScale(context),
        spacingDp: Float = turnSignalsSpacingDp(context)
    ): Float {
        val safeScale = scale.coerceAtLeast(0f)
        val safeSpacingDp = spacingDp.coerceAtLeast(TURN_SIGNALS_ICON_SIZE_DP)
        val rawWidthDp = TURN_SIGNALS_ICON_SIZE_DP + safeSpacingDp
        return (rawWidthDp * safeScale) / 2f
    }

    fun turnSignalsIconStyle(context: Context): Int {
        val preferences = prefs(context)
        val migrated = preferences.getBoolean(KEY_TURN_SIGNALS_ICON_STYLE_MIGRATED, false)
        val stored = preferences.getInt(KEY_TURN_SIGNALS_ICON_STYLE, TURN_SIGNALS_ICON_STYLE_DEFAULT)
        val resolved = if (migrated) {
            TurnSignalIcons.sanitize(stored)
        } else {
            migrateLegacyTurnSignalsIconStyle(stored)
        }
        if (!migrated || resolved != stored) {
            preferences.edit()
                .putInt(KEY_TURN_SIGNALS_ICON_STYLE, resolved)
                .putBoolean(KEY_TURN_SIGNALS_ICON_STYLE_MIGRATED, true)
                .apply()
        }
        return resolved
    }

    fun setTurnSignalsIconStyle(context: Context, styleId: Int) {
        prefs(context).edit()
            .putInt(KEY_TURN_SIGNALS_ICON_STYLE, TurnSignalIcons.sanitize(styleId))
            .putBoolean(KEY_TURN_SIGNALS_ICON_STYLE_MIGRATED, true)
            .apply()
    }

    internal fun turnSignalsCustomIcon(context: Context): TurnSignalCustomIcon? {
        val preferences = prefs(context)
        val uriString = preferences.getString(KEY_TURN_SIGNALS_CUSTOM_ICON_URI, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val displayName = preferences.getString(KEY_TURN_SIGNALS_CUSTOM_ICON_NAME, null)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val baseDirectionName = preferences.getString(
            KEY_TURN_SIGNALS_CUSTOM_ICON_BASE_DIRECTION,
            TurnSignalBaseDirection.LEFT.name
        ).orEmpty()
        val baseDirection = runCatching {
            TurnSignalBaseDirection.valueOf(baseDirectionName)
        }.getOrDefault(TurnSignalBaseDirection.LEFT)
        val icon = TurnSignalCustomIcon(
            uriString = uriString,
            displayName = displayName,
            baseDirection = baseDirection
        )
        return icon.takeIf { it.uri.scheme == "file" }
    }

    internal fun setTurnSignalsCustomIcon(
        context: Context,
        uriString: String,
        displayName: String,
        baseDirection: TurnSignalBaseDirection
    ) {
        prefs(context).edit()
            .putString(KEY_TURN_SIGNALS_CUSTOM_ICON_URI, uriString)
            .putString(KEY_TURN_SIGNALS_CUSTOM_ICON_NAME, displayName)
            .putString(KEY_TURN_SIGNALS_CUSTOM_ICON_BASE_DIRECTION, baseDirection.name)
            .apply()
    }

    internal fun clearTurnSignalsCustomIcon(context: Context) {
        prefs(context).edit()
            .remove(KEY_TURN_SIGNALS_CUSTOM_ICON_URI)
            .remove(KEY_TURN_SIGNALS_CUSTOM_ICON_NAME)
            .remove(KEY_TURN_SIGNALS_CUSTOM_ICON_BASE_DIRECTION)
            .apply()
    }

    private fun migrateLegacyTurnSignalsIconStyle(styleId: Int): Int {
        return when (styleId) {
            12 -> 2
            in 2..11 -> styleId + 1
            else -> TurnSignalIcons.sanitize(styleId)
        }
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

    fun laneGuidanceAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_LANE_GUIDANCE_ALPHA, 1f)
    }

    fun setLaneGuidanceAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_LANE_GUIDANCE_ALPHA, alpha)
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

    fun strelkaAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_STRELKA_ALPHA, 1f)
    }

    fun setStrelkaAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_STRELKA_ALPHA, alpha)
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

    fun turnSignalsAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_TURN_SIGNALS_ALPHA, 1f)
    }

    fun setTurnSignalsAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_TURN_SIGNALS_ALPHA, alpha)
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

    fun mapAlpha(context: Context): Float {
        return prefs(context).getFloat(KEY_MAP_ALPHA, 1f)
    }

    fun setMapAlpha(context: Context, alpha: Float) {
        prefs(context).edit()
            .putFloat(KEY_MAP_ALPHA, alpha)
            .apply()
    }

    fun containerSizeDp(context: Context): PointF {
        val prefs = prefs(context)
        val defaultSize = defaultContainerSizeDp(context)
        val width = prefs.getFloat(KEY_CONTAINER_WIDTH_DP, defaultSize.x)
        val height = prefs.getFloat(KEY_CONTAINER_HEIGHT_DP, defaultSize.y)
        return PointF(width, height)
    }

    fun setContainerSizeDp(context: Context, widthDp: Float, heightDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_CONTAINER_WIDTH_DP, widthDp)
            .putFloat(KEY_CONTAINER_HEIGHT_DP, heightDp)
            .apply()
    }

    fun mapSizeDp(context: Context): PointF {
        val prefs = prefs(context)
        val defaultSize = defaultMapSizeDp(context)
        val width = prefs.getFloat(KEY_MAP_WIDTH_DP, defaultSize.x)
        val height = prefs.getFloat(KEY_MAP_HEIGHT_DP, defaultSize.y)
        return PointF(width, height)
    }

    fun setMapSizeDp(context: Context, widthDp: Float, heightDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_MAP_WIDTH_DP, widthDp)
            .putFloat(KEY_MAP_HEIGHT_DP, heightDp)
            .apply()
    }

    /**
     * "Камеры на проекцию": rear camera on reverse and side cameras on turn signals, drawn by
     * ghudbridgelited. Views are kept in car terms ([CameraViewSettings]) and turned into CAMCFG
     * rotations with the per-car calibration; edited in CameraViewEditorActivity.
     */
    data class CameraSettings(
        val enabled: Boolean,
        val inContainer: Boolean,
        val correction: Boolean,
        val vignette: Boolean,
        val containerPositionDp: PointF,
        val containerSizeDp: PointF,
        val z: Int,
        val rear: RearCameraSettings,
        val side: SideCameraSettings
    )

    /**
     * One camera's picture in car terms: [headingDeg] from straight ahead (180 = back, more = to the
     * left), [downDeg] below the horizon, [rollDeg] picture rotation; [hfovDeg] zoom; pan as in CAMCFG.
     */
    data class CameraViewSettings(
        val headingDeg: Float,
        val downDeg: Float,
        val rollDeg: Float,
        val hfovDeg: Float,
        val panX: Float,
        val panY: Float,
        val mirror: Boolean
    )

    data class RearCameraSettings(
        val enabled: Boolean,
        val view: CameraViewSettings,
        val maxSpeedKmh: Int
    )

    data class SideCameraSettings(
        val enabled: Boolean,
        val hazardBoth: Boolean,
        val left: CameraViewSettings,
        val right: CameraViewSettings,
        val maxSpeedKmh: Int
    )

    enum class CameraSlot { REAR, LEFT, RIGHT }

    private class CameraViewKeys(
        val heading: String,
        val down: String,
        val roll: String,
        val hfov: String,
        val panX: String?,
        val panY: String,
        val mirror: String
    )

    private fun cameraViewKeys(slot: CameraSlot) = when (slot) {
        CameraSlot.REAR -> CameraViewKeys(
            KEY_REAR_CAMERA_HEADING, KEY_REAR_CAMERA_DOWN, KEY_REAR_CAMERA_ROLL, KEY_REAR_CAMERA_HFOV,
            KEY_REAR_CAMERA_PAN_X, KEY_REAR_CAMERA_PAN_Y, KEY_REAR_CAMERA_MIRROR
        )
        CameraSlot.LEFT -> CameraViewKeys(
            KEY_LEFT_CAMERA_HEADING, KEY_LEFT_CAMERA_DOWN, KEY_LEFT_CAMERA_ROLL, KEY_LEFT_CAMERA_HFOV,
            null, KEY_LEFT_CAMERA_PAN_Y, KEY_LEFT_CAMERA_MIRROR
        )
        CameraSlot.RIGHT -> CameraViewKeys(
            KEY_RIGHT_CAMERA_HEADING, KEY_RIGHT_CAMERA_DOWN, KEY_RIGHT_CAMERA_ROLL, KEY_RIGHT_CAMERA_HFOV,
            null, KEY_RIGHT_CAMERA_PAN_Y, KEY_RIGHT_CAMERA_MIRROR
        )
    }

    fun defaultCameraView(slot: CameraSlot): CameraViewSettings = when (slot) {
        CameraSlot.REAR -> CameraViewSettings(
            headingDeg = RearCameraCalibration.DEFAULT_HEADING_DEG,
            downDeg = RearCameraCalibration.DEFAULT_DOWN_DEG,
            rollDeg = RearCameraCalibration.DEFAULT_ROLL_DEG,
            hfovDeg = RearCameraCalibration.DEFAULT_OUT_HFOV_DEG,
            panX = RearCameraCalibration.DEFAULT_PAN_X,
            panY = RearCameraCalibration.DEFAULT_PAN_Y,
            mirror = RearCameraCalibration.DEFAULT_MIRROR
        )
        CameraSlot.LEFT, CameraSlot.RIGHT -> CameraViewSettings(
            headingDeg = if (slot == CameraSlot.LEFT) {
                SideCameraCalibration.DEFAULT_LEFT_HEADING_DEG
            } else {
                SideCameraCalibration.DEFAULT_RIGHT_HEADING_DEG
            },
            downDeg = SideCameraCalibration.DEFAULT_DOWN_DEG,
            rollDeg = SideCameraCalibration.DEFAULT_ROLL_DEG,
            hfovDeg = SideCameraCalibration.DEFAULT_OUT_HFOV_DEG,
            panX = SideCameraCalibration.DEFAULT_PAN_X,
            panY = SideCameraCalibration.DEFAULT_PAN_Y,
            mirror = SideCameraCalibration.DEFAULT_MIRROR
        )
    }

    /** Keeps a view inside the editor's ranges. */
    fun clampCameraView(slot: CameraSlot, view: CameraViewSettings): CameraViewSettings {
        val heading0 = defaultCameraView(slot).headingDeg
        return view.copy(
            headingDeg = view.headingDeg.coerceIn(heading0 - CAMERA_HEADING_RANGE, heading0 + CAMERA_HEADING_RANGE),
            downDeg = view.downDeg.coerceIn(CAMERA_DOWN_MIN, CAMERA_DOWN_MAX),
            rollDeg = view.rollDeg.coerceIn(CAMERA_ROLL_MIN, CAMERA_ROLL_MAX),
            hfovDeg = view.hfovDeg.coerceIn(CAMERA_HFOV_MIN, CAMERA_HFOV_MAX),
            panX = view.panX.coerceIn(CAMERA_PAN_MIN, CAMERA_PAN_MAX),
            panY = view.panY.coerceIn(CAMERA_PAN_MIN, CAMERA_PAN_MAX)
        )
    }

    fun cameraView(context: Context, slot: CameraSlot): CameraViewSettings {
        val prefs = prefs(context)
        val keys = cameraViewKeys(slot)
        val d = defaultCameraView(slot)
        return clampCameraView(
            slot,
            CameraViewSettings(
                headingDeg = prefs.getFloat(keys.heading, d.headingDeg),
                downDeg = prefs.getFloat(keys.down, d.downDeg),
                rollDeg = prefs.getFloat(keys.roll, d.rollDeg),
                hfovDeg = prefs.getFloat(keys.hfov, d.hfovDeg),
                panX = keys.panX?.let { prefs.getFloat(it, d.panX) } ?: d.panX,
                panY = prefs.getFloat(keys.panY, d.panY),
                mirror = prefs.getBoolean(keys.mirror, d.mirror)
            )
        )
    }

    fun setCameraView(context: Context, slot: CameraSlot, view: CameraViewSettings) {
        val keys = cameraViewKeys(slot)
        val v = clampCameraView(slot, view)
        prefs(context).edit().apply {
            putFloat(keys.heading, v.headingDeg)
            putFloat(keys.down, v.downDeg)
            putFloat(keys.roll, v.rollDeg)
            putFloat(keys.hfov, v.hfovDeg)
            keys.panX?.let { putFloat(it, v.panX) }
            putFloat(keys.panY, v.panY)
            putBoolean(keys.mirror, v.mirror)
        }.apply()
    }

    fun cameraSettings(context: Context): CameraSettings {
        val prefs = prefs(context)
        migrateCameraPrefs(prefs)
        return CameraSettings(
            enabled = camerasEnabled(context),
            inContainer = camerasInContainer(context),
            correction = camerasCorrection(context),
            vignette = camerasVignette(context),
            containerPositionDp = cameraContainerPositionDp(context),
            containerSizeDp = cameraContainerSizeDp(context),
            z = prefs.getInt(KEY_REAR_CAMERA_Z, CAMERA_Z_DEFAULT).coerceIn(CAMERA_Z_MIN, CAMERA_Z_MAX),
            rear = RearCameraSettings(
                enabled = rearCameraEnabled(context),
                view = cameraView(context, CameraSlot.REAR),
                maxSpeedKmh = prefs.getInt(KEY_REAR_CAMERA_MAX_SPEED, REAR_CAMERA_MAX_SPEED_DEFAULT)
                    .coerceIn(0, REAR_CAMERA_MAX_SPEED_MAX)
            ),
            side = SideCameraSettings(
                enabled = sideCamerasEnabled(context),
                hazardBoth = sideCamerasHazard(context),
                left = cameraView(context, CameraSlot.LEFT),
                right = cameraView(context, CameraSlot.RIGHT),
                maxSpeedKmh = sideCameraMaxSpeedKmh(context)
            )
        )
    }

    /**
     * The old single switch "Камера на проекции при заднем ходе" becomes the master switch;
     * the rear camera itself stays on (it was the only camera then).
     */
    private fun migrateCameraPrefs(prefs: SharedPreferences) {
        // Rear hfov/pan/mirror keep their keys and meaning; the old side placeholders are dropped.
        if (LEGACY_SIDE_CAMERA_KEYS.any(prefs::contains)) {
            prefs.edit().apply { LEGACY_SIDE_CAMERA_KEYS.forEach(::remove) }.apply()
        }
        if (prefs.contains(KEY_CAMERAS_ENABLED) || !prefs.contains(KEY_REAR_CAMERA_ENABLED)) return
        val wasEnabled = prefs.getBoolean(KEY_REAR_CAMERA_ENABLED, false)
        prefs.edit()
            .putBoolean(KEY_CAMERAS_ENABLED, wasEnabled)
            .putBoolean(KEY_REAR_CAMERA_ENABLED, true)
            .apply()
    }

    fun camerasEnabled(context: Context): Boolean {
        val prefs = prefs(context)
        migrateCameraPrefs(prefs)
        return prefs.getBoolean(KEY_CAMERAS_ENABLED, false)
    }

    fun setCamerasEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERAS_ENABLED, enabled).apply()
    }

    /** Comma-separated keys of [OVER_STOCK_BLOCK_KEYS] drawn over the stock HUD picture (background render). */
    internal const val KEY_OVER_STOCK_BLOCKS = "overlay_over_stock_blocks"
    const val OVER_STOCK_CUSTOM_BLOCKS = "custom"

    /** Blocks that can go over the stock HUD picture, in the settings dialog's order. */
    val OVER_STOCK_BLOCK_KEYS: List<String> = listOf(
        OverlayBroadcasts.PREVIEW_TARGET_NAV,
        OverlayBroadcasts.PREVIEW_TARGET_LANE_GUIDANCE,
        OverlayBroadcasts.PREVIEW_TARGET_ARROW,
        OverlayBroadcasts.PREVIEW_TARGET_SPEED,
        OverlayBroadcasts.PREVIEW_TARGET_HUDSPEED,
        OverlayBroadcasts.PREVIEW_TARGET_ROAD_CAMERA,
        OverlayBroadcasts.PREVIEW_TARGET_TRAFFIC_LIGHT,
        OverlayBroadcasts.PREVIEW_TARGET_SPEEDOMETER,
        OverlayBroadcasts.PREVIEW_TARGET_TURN_SIGNALS,
        OverlayBroadcasts.PREVIEW_TARGET_CLOCK,
        OverlayBroadcasts.PREVIEW_TARGET_MAP,
        OVER_STOCK_CUSTOM_BLOCKS
    )

    fun overStockBlocks(context: Context): Set<String> =
        parseOverStockBlocks(prefs(context).getString(KEY_OVER_STOCK_BLOCKS, null))

    fun setOverStockBlocks(context: Context, blocks: Set<String>) {
        prefs(context).edit().putString(KEY_OVER_STOCK_BLOCKS, formatOverStockBlocks(blocks)).apply()
    }

    /** Known keys only, in [OVER_STOCK_BLOCK_KEYS] order. */
    internal fun formatOverStockBlocks(blocks: Set<String>): String =
        OVER_STOCK_BLOCK_KEYS.filter(blocks::contains).joinToString(",")

    internal fun parseOverStockBlocks(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val stored = raw.split(',').map { it.trim() }.toSet()
        return OVER_STOCK_BLOCK_KEYS.filterTo(linkedSetOf(), stored::contains)
    }

    /** Master switch on and at least one camera trigger on. */
    fun cameraFeatureEnabled(context: Context): Boolean =
        camerasEnabled(context) && (rearCameraEnabled(context) || sideCamerasEnabled(context))

    /** true: cameras use the HUD container rect; false: their own camera container. */
    fun camerasInContainer(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERAS_IN_CONTAINER, true)

    fun setCamerasInContainer(context: Context, inContainer: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERAS_IN_CONTAINER, inContainer).apply()
    }

    /** "Выполнять автокоррекцию картинки"; off = raw fisheye picture (NO_CORRECTION). */
    fun camerasCorrection(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERAS_CORRECTION, true)

    fun setCamerasCorrection(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERAS_CORRECTION, enabled).apply()
    }

    /** "Виньетирование": darkened edges of the camera pictures (CAMCFG view flag VIGNETTE), like the map's. */
    fun camerasVignette(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CAMERAS_VIGNETTE, true)

    fun setCamerasVignette(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAMERAS_VIGNETTE, enabled).apply()
    }

    fun rearCameraEnabled(context: Context): Boolean {
        val prefs = prefs(context)
        migrateCameraPrefs(prefs)
        return prefs.getBoolean(KEY_REAR_CAMERA_ENABLED, true)
    }

    fun setRearCameraEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REAR_CAMERA_ENABLED, enabled).apply()
    }

    fun sideCamerasEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SIDE_CAMERAS_ENABLED, false)

    fun setSideCamerasEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIDE_CAMERAS_ENABLED, enabled).apply()
    }

    /** "Активация при аварийке": hazard lights show both side cameras (HAZARD_BOTH). */
    fun sideCamerasHazard(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SIDE_CAMERAS_HAZARD, false)

    fun setSideCamerasHazard(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SIDE_CAMERAS_HAZARD, enabled).apply()
    }

    fun sideCameraMaxSpeedKmh(context: Context): Int =
        prefs(context).getInt(KEY_SIDE_CAMERA_MAX_SPEED, SideCameraCalibration.DEFAULT_MAX_SPEED_KMH)
            .coerceIn(SIDE_CAMERA_MAX_SPEED_MIN, SIDE_CAMERA_MAX_SPEED_MAX)

    fun setSideCameraMaxSpeedKmh(context: Context, speedKmh: Int) {
        prefs(context).edit()
            .putInt(
                KEY_SIDE_CAMERA_MAX_SPEED,
                speedKmh.coerceIn(SIDE_CAMERA_MAX_SPEED_MIN, SIDE_CAMERA_MAX_SPEED_MAX)
            )
            .apply()
    }

    /** Separate camera container on the HUD display (like the HUD container), used when not in the container. */
    fun cameraContainerPositionDp(context: Context): PointF {
        val prefs = prefs(context)
        val containerPos = containerPositionDp(context)
        return PointF(
            prefs.getFloat(KEY_CAMERA_CONTAINER_X_DP, containerPos.x),
            prefs.getFloat(KEY_CAMERA_CONTAINER_Y_DP, containerPos.y)
        )
    }

    fun setCameraContainerPositionDp(context: Context, xDp: Float, yDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_CAMERA_CONTAINER_X_DP, xDp)
            .putFloat(KEY_CAMERA_CONTAINER_Y_DP, yDp)
            .apply()
    }

    fun cameraContainerSizeDp(context: Context): PointF {
        val prefs = prefs(context)
        val containerSize = containerSizeDp(context)
        return PointF(
            prefs.getFloat(KEY_CAMERA_CONTAINER_WIDTH_DP, containerSize.x),
            prefs.getFloat(KEY_CAMERA_CONTAINER_HEIGHT_DP, containerSize.y)
        )
    }

    fun setCameraContainerSizeDp(context: Context, widthDp: Float, heightDp: Float) {
        prefs(context).edit()
            .putFloat(KEY_CAMERA_CONTAINER_WIDTH_DP, widthDp)
            .putFloat(KEY_CAMERA_CONTAINER_HEIGHT_DP, heightDp)
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

    fun mapJamsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MAP_JAMS_ENABLED, true)
    }

    fun setMapJamsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_MAP_JAMS_ENABLED, enabled)
            .apply()
    }

    fun mapMinimapZoom(context: Context): Float {
        return prefs(context).getFloat(KEY_MAP_MINIMAP_ZOOM, MINIMAP_ZOOM_DEFAULT)
            .coerceIn(MINIMAP_ZOOM_MIN, MINIMAP_ZOOM_MAX)
    }

    fun setMapMinimapZoom(context: Context, zoom: Float) {
        prefs(context).edit()
            .putFloat(KEY_MAP_MINIMAP_ZOOM, zoom.coerceIn(MINIMAP_ZOOM_MIN, MINIMAP_ZOOM_MAX))
            .apply()
    }

    data class MinimapStyle(
        val zoom: Float,
        val view: Int,
        val roadsOnly: Boolean,
        val labels: Boolean,
        val cursorScale: Float,
        val routeScale: Float
    )

    fun minimapStyle(context: Context) = MinimapStyle(
        zoom = mapMinimapZoom(context),
        view = mapMinimapView(context),
        roadsOnly = mapMinimapRoadsOnly(context),
        labels = mapMinimapLabels(context),
        cursorScale = mapMinimapCursorScale(context),
        routeScale = mapMinimapRouteScale(context)
    )

    fun mapMinimapView(context: Context): Int =
        prefs(context).getInt(KEY_MAP_MINIMAP_VIEW, MINIMAP_VIEW_3D)
            .takeIf { it in setOf(MINIMAP_VIEW_3D, MINIMAP_VIEW_2D, MINIMAP_VIEW_PERSPECTIVE) } ?: MINIMAP_VIEW_3D

    fun setMapMinimapView(context: Context, view: Int) {
        prefs(context).edit().putInt(KEY_MAP_MINIMAP_VIEW, view).apply()
    }

    fun mapMinimapRoadsOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAP_MINIMAP_ROADS_ONLY, false)

    fun setMapMinimapRoadsOnly(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAP_MINIMAP_ROADS_ONLY, enabled).apply()
    }

    fun mapMinimapLabels(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MAP_MINIMAP_LABELS, true)

    fun setMapMinimapLabels(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MAP_MINIMAP_LABELS, enabled).apply()
    }

    fun mapMinimapCursorScale(context: Context): Float =
        prefs(context).getFloat(KEY_MAP_MINIMAP_CURSOR_SCALE, MINIMAP_SCALE_DEFAULT)
            .coerceIn(MINIMAP_SCALE_MIN, MINIMAP_SCALE_MAX)

    fun setMapMinimapCursorScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_MAP_MINIMAP_CURSOR_SCALE, scale.coerceIn(MINIMAP_SCALE_MIN, MINIMAP_SCALE_MAX))
            .apply()
    }

    fun mapMinimapRouteScale(context: Context): Float =
        prefs(context).getFloat(KEY_MAP_MINIMAP_ROUTE_SCALE, MINIMAP_SCALE_DEFAULT)
            .coerceIn(MINIMAP_SCALE_MIN, MINIMAP_SCALE_MAX)

    fun setMapMinimapRouteScale(context: Context, scale: Float) {
        prefs(context).edit()
            .putFloat(KEY_MAP_MINIMAP_ROUTE_SCALE, scale.coerceIn(MINIMAP_SCALE_MIN, MINIMAP_SCALE_MAX))
            .apply()
    }

    fun navHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NAV_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setNavHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NAV_HIDE_WHEN_MAP_ACTIVE, enabled)
            .apply()
    }

    fun navShowDistance(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_NAV_SHOW_DISTANCE, true)
    }

    fun setNavShowDistance(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_NAV_SHOW_DISTANCE, enabled)
            .apply()
    }

    fun laneGuidanceEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LANE_GUIDANCE_ENABLED, true)
    }

    fun setLaneGuidanceEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_LANE_GUIDANCE_ENABLED, enabled)
            .apply()
    }

    fun laneGuidanceHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LANE_GUIDANCE_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setLaneGuidanceHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_LANE_GUIDANCE_HIDE_WHEN_MAP_ACTIVE, enabled)
            .apply()
    }

    fun laneGuidanceShowDistance(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LANE_GUIDANCE_SHOW_DISTANCE, true)
    }

    fun setLaneGuidanceShowDistance(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_LANE_GUIDANCE_SHOW_DISTANCE, enabled)
            .apply()
    }

    /** Lane hints only from this distance to the manoeuvre on (slider steps, roughly logarithmic). */
    val LANE_GUIDANCE_DISTANCE_STEPS_M = intArrayOf(10, 20, 30, 50, 75, 100, 150, 200, 250, 300, 400, 500, 600, 750, 1000)
    const val LANE_GUIDANCE_MAX_DISTANCE_DEFAULT_M = 300

    fun laneGuidanceDistanceLimitEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_LANE_GUIDANCE_DISTANCE_LIMIT_ENABLED, false)
    }

    fun setLaneGuidanceDistanceLimitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_LANE_GUIDANCE_DISTANCE_LIMIT_ENABLED, enabled)
            .apply()
    }

    fun laneGuidanceMaxDistanceMeters(context: Context): Int =
        prefs(context).getInt(KEY_LANE_GUIDANCE_MAX_DISTANCE_M, LANE_GUIDANCE_MAX_DISTANCE_DEFAULT_M)
            .coerceIn(LANE_GUIDANCE_DISTANCE_STEPS_M.first(), LANE_GUIDANCE_DISTANCE_STEPS_M.last())

    fun setLaneGuidanceMaxDistanceMeters(context: Context, distanceMeters: Int) {
        prefs(context).edit()
            .putInt(
                KEY_LANE_GUIDANCE_MAX_DISTANCE_M,
                distanceMeters.coerceIn(LANE_GUIDANCE_DISTANCE_STEPS_M.first(), LANE_GUIDANCE_DISTANCE_STEPS_M.last())
            )
            .apply()
    }

    /** Slider index of the step closest to [distanceMeters]. */
    fun laneGuidanceDistanceStepIndex(distanceMeters: Int): Int =
        LANE_GUIDANCE_DISTANCE_STEPS_M.indices.minByOrNull { abs(LANE_GUIDANCE_DISTANCE_STEPS_M[it] - distanceMeters) } ?: 0

    /** Unknown distance (blank or unparsable text) never hides the hint. */
    fun laneGuidanceWithinDistance(distanceMeters: Int?, limitEnabled: Boolean, maxDistanceMeters: Int): Boolean =
        !limitEnabled || distanceMeters == null || distanceMeters <= maxDistanceMeters

    fun arrowEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ARROW_ENABLED, false)
    }

    fun setArrowEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ARROW_ENABLED, enabled)
            .apply()
    }

    fun arrowHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ARROW_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setArrowHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ARROW_HIDE_WHEN_MAP_ACTIVE, enabled)
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

    fun speedHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEED_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setSpeedHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPEED_HIDE_WHEN_MAP_ACTIVE, enabled)
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

    fun hudSpeedHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HUDSPEED_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setHudSpeedHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HUDSPEED_HIDE_WHEN_MAP_ACTIVE, enabled)
            .apply()
    }

    fun strelkaHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_STRELKA_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setStrelkaHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_STRELKA_HIDE_WHEN_MAP_ACTIVE, enabled)
            .apply()
    }

    fun hudSpeedGpsStatusEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HUDSPEED_GPS_STATUS_ENABLED, false)
    }

    fun setHudSpeedGpsStatusEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HUDSPEED_GPS_STATUS_ENABLED, enabled)
            .apply()
    }

    fun roadCameraEnabled(context: Context): Boolean {
        return false
    }

    fun setRoadCameraEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ROAD_CAMERA_ENABLED, enabled)
            .apply()
    }

    fun roadCameraHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_ROAD_CAMERA_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setRoadCameraHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_ROAD_CAMERA_HIDE_WHEN_MAP_ACTIVE, enabled)
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

    fun mainMenuRoadCameraVisible(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MAIN_MENU_ROAD_CAMERA_VISIBLE, false)
    }

    fun setMainMenuRoadCameraVisible(context: Context, visible: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_MAIN_MENU_ROAD_CAMERA_VISIBLE, visible)
            .apply()
    }

    fun mainMenuTrafficLightVisible(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_MAIN_MENU_TRAFFIC_LIGHT_VISIBLE, false)
    }

    fun setMainMenuTrafficLightVisible(context: Context, visible: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_MAIN_MENU_TRAFFIC_LIGHT_VISIBLE, visible)
            .apply()
    }

    fun trafficLightHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TRAFFIC_LIGHT_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setTrafficLightHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_TRAFFIC_LIGHT_HIDE_WHEN_MAP_ACTIVE, enabled)
            .apply()
    }

    fun trafficLightMaxActive(context: Context): Int {
        val value = prefs(context).getInt(KEY_TRAFFIC_LIGHT_MAX_ACTIVE, 3)
        return value.coerceAtLeast(1)
    }

    fun trafficLightDisplayDistanceMeters(context: Context): Int =
        prefs(context).getInt(KEY_TRAFFIC_LIGHT_DISPLAY_DISTANCE_M, 500).coerceIn(20, 500)

    fun setTrafficLightDisplayDistanceMeters(context: Context, distanceMeters: Int) {
        prefs(context).edit()
            .putInt(KEY_TRAFFIC_LIGHT_DISPLAY_DISTANCE_M, distanceMeters.coerceIn(20, 500))
            .apply()
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

    fun hudSpeedLimitEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HUDSPEED_LIMIT_ENABLED, false)
    }

    fun setHudSpeedLimitEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HUDSPEED_LIMIT_ENABLED, enabled)
            .apply()
    }

    fun hudSpeedLimitAlertEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HUDSPEED_LIMIT_ALERT_ENABLED, false)
    }

    fun setHudSpeedLimitAlertEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HUDSPEED_LIMIT_ALERT_ENABLED, enabled)
            .apply()
    }

    fun hudSpeedLimitAlertThreshold(context: Context): Int {
        return prefs(context).getInt(KEY_HUDSPEED_LIMIT_ALERT_THRESHOLD, 0)
            .coerceIn(0, SPEED_LIMIT_ALERT_THRESHOLD_MAX)
    }

    fun setHudSpeedLimitAlertThreshold(context: Context, threshold: Int) {
        prefs(context).edit()
            .putInt(KEY_HUDSPEED_LIMIT_ALERT_THRESHOLD, threshold.coerceIn(0, SPEED_LIMIT_ALERT_THRESHOLD_MAX))
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

    fun speedometerHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEEDOMETER_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setSpeedometerHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPEEDOMETER_HIDE_WHEN_MAP_ACTIVE, enabled)
            .apply()
    }

    fun speedometerShowUnitText(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_SPEEDOMETER_SHOW_UNIT_TEXT, false)
    }

    fun setSpeedometerShowUnitText(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_SPEEDOMETER_SHOW_UNIT_TEXT, enabled)
            .apply()
    }

    fun turnSignalsEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TURN_SIGNALS_ENABLED, true)
    }

    fun setTurnSignalsEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_TURN_SIGNALS_ENABLED, enabled)
            .apply()
    }

    fun turnSignalsHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_TURN_SIGNALS_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setTurnSignalsHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_TURN_SIGNALS_HIDE_WHEN_MAP_ACTIVE, enabled)
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

    fun clockHideWhenMapActive(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_CLOCK_HIDE_WHEN_MAP_ACTIVE, false)
    }

    fun setClockHideWhenMapActive(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_CLOCK_HIDE_WHEN_MAP_ACTIVE, enabled)
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

    fun navNotificationEndTimeout(context: Context): Int {
        return prefs(context).getInt(KEY_NAV_NOTIFICATION_END_TIMEOUT, 2)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setNavNotificationEndTimeout(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_NAV_NOTIFICATION_END_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    fun navUpdatesEndTimeout(context: Context): Int {
        return prefs(context).getInt(KEY_NAV_UPDATES_END_TIMEOUT, 0)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setNavUpdatesEndTimeout(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_NAV_UPDATES_END_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
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

    fun speedometerFreezeTimeout(context: Context): Int {
        return prefs(context).getInt(KEY_SPEEDOMETER_FREEZE_TIMEOUT, 0)
            .coerceIn(0, TIMEOUT_MAX)
    }

    fun setSpeedometerFreezeTimeout(context: Context, timeout: Int) {
        prefs(context).edit()
            .putInt(KEY_SPEEDOMETER_FREEZE_TIMEOUT, timeout.coerceIn(0, TIMEOUT_MAX))
            .apply()
    }

    fun infoMirrorStarsheep7Enabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_INFO_MIRROR_STARSHEEP7, false)
    }

    fun setInfoMirrorStarsheep7Enabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_INFO_MIRROR_STARSHEEP7, enabled)
            .apply()
    }

    fun infoMirrorGalaxyEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_INFO_MIRROR_GALAXY, false)
    }

    fun setInfoMirrorGalaxyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_INFO_MIRROR_GALAXY, enabled)
            .apply()
    }

    fun hideTurnWhenFarEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_TURN_WHEN_FAR_ENABLED, false)
    }

    fun setHideTurnWhenFarEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_TURN_WHEN_FAR_ENABLED, enabled)
            .apply()
    }

    fun hideTurnDistanceStepsMeters(): IntArray = HIDE_TURN_DISTANCE_STEPS_METERS.copyOf()

    fun hideTurnWhenFarDistanceMeters(context: Context): Int {
        val value = prefs(context).getInt(
            KEY_HIDE_TURN_WHEN_FAR_DISTANCE_METERS,
            HIDE_TURN_DISTANCE_DEFAULT_METERS
        )
        return normalizeHideTurnDistance(value)
    }

    fun hideTurnWhenFarDistanceProgress(context: Context): Int {
        val distance = hideTurnWhenFarDistanceMeters(context)
        return HIDE_TURN_DISTANCE_STEPS_METERS.indexOf(distance).coerceAtLeast(0)
    }

    fun hideTurnWhenFarDistanceMetersByProgress(progress: Int): Int {
        val index = progress.coerceIn(0, HIDE_TURN_DISTANCE_STEPS_METERS.lastIndex)
        return HIDE_TURN_DISTANCE_STEPS_METERS[index]
    }

    fun setHideTurnWhenFarDistanceMeters(context: Context, distanceMeters: Int) {
        prefs(context).edit()
            .putInt(
                KEY_HIDE_TURN_WHEN_FAR_DISTANCE_METERS,
                normalizeHideTurnDistance(distanceMeters)
            )
            .apply()
    }

    fun hideTurnDynamicEnabled(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_TURN_DYNAMIC_ENABLED, false)
    }

    fun setHideTurnDynamicEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_TURN_DYNAMIC_ENABLED, enabled)
            .apply()
    }

    fun hideTurnDynamicDistances(context: Context): DynamicHideTurnDistances {
        val prefs = prefs(context)
        return DynamicHideTurnDistances(
            upTo40Kmh = prefs.getInt(
                KEY_HIDE_TURN_DYNAMIC_UP_TO_40_METERS,
                HIDE_TURN_DYNAMIC_DEFAULT_UP_TO_40_METERS
            ),
            from40To60Kmh = prefs.getInt(
                KEY_HIDE_TURN_DYNAMIC_40_TO_60_METERS,
                HIDE_TURN_DYNAMIC_DEFAULT_40_TO_60_METERS
            ),
            from60To100Kmh = prefs.getInt(
                KEY_HIDE_TURN_DYNAMIC_60_TO_100_METERS,
                HIDE_TURN_DYNAMIC_DEFAULT_60_TO_100_METERS
            ),
            from100Kmh = prefs.getInt(
                KEY_HIDE_TURN_DYNAMIC_100_PLUS_METERS,
                HIDE_TURN_DYNAMIC_DEFAULT_100_PLUS_METERS
            ),
        ).normalized()
    }

    fun setHideTurnDynamicDistances(context: Context, distances: DynamicHideTurnDistances) {
        val normalized = distances.normalized()
        prefs(context).edit()
            .putInt(KEY_HIDE_TURN_DYNAMIC_UP_TO_40_METERS, normalized.upTo40Kmh)
            .putInt(KEY_HIDE_TURN_DYNAMIC_40_TO_60_METERS, normalized.from40To60Kmh)
            .putInt(KEY_HIDE_TURN_DYNAMIC_60_TO_100_METERS, normalized.from60To100Kmh)
            .putInt(KEY_HIDE_TURN_DYNAMIC_100_PLUS_METERS, normalized.from100Kmh)
            .apply()
    }

    fun hideTurnDynamicHideMapBlock(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_HIDE_TURN_DYNAMIC_HIDE_MAP_BLOCK, false)
    }

    fun setHideTurnDynamicHideMapBlock(context: Context, enabled: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_HIDE_TURN_DYNAMIC_HIDE_MAP_BLOCK, enabled)
            .apply()
    }

    fun resolveHideTurnThresholdMeters(context: Context, speedKmh: Int?): Int {
        return if (hideTurnDynamicEnabled(context)) {
            hideTurnDynamicDistances(context).resolveForSpeed(speedKmh)
        } else {
            hideTurnWhenFarDistanceMeters(context)
        }
    }

    internal fun resolveHideTurnThreshold(
        context: Context,
        speedKmh: Int?,
        currentBucket: DynamicHideTurnSpeedBucket?,
    ): HideTurnThresholdResolution {
        if (!hideTurnDynamicEnabled(context)) {
            return HideTurnThresholdResolution(
                thresholdMeters = hideTurnWhenFarDistanceMeters(context),
                bucket = null,
            )
        }
        val nextBucket = applyDynamicHideTurnSpeedBucketHysteresis(
            currentBucket = currentBucket,
            speedKmh = speedKmh
        )
        return HideTurnThresholdResolution(
            thresholdMeters = hideTurnDynamicDistances(context).resolveForBucket(nextBucket),
            bucket = nextBucket,
        )
    }

    internal fun resolveDynamicHideTurnSpeedBucket(speedKmh: Int?): DynamicHideTurnSpeedBucket {
        val speed = (speedKmh ?: 0).coerceAtLeast(0)
        return when {
            speed < 40 -> DynamicHideTurnSpeedBucket.UP_TO_40
            speed < 60 -> DynamicHideTurnSpeedBucket.FROM_40_TO_60
            speed < 100 -> DynamicHideTurnSpeedBucket.FROM_60_TO_100
            else -> DynamicHideTurnSpeedBucket.FROM_100_PLUS
        }
    }

    internal fun applyDynamicHideTurnSpeedBucketHysteresis(
        currentBucket: DynamicHideTurnSpeedBucket?,
        speedKmh: Int?,
        hysteresisKmh: Int = 5
    ): DynamicHideTurnSpeedBucket {
        val safeSpeed = (speedKmh ?: 0).coerceAtLeast(0)
        val hysteresis = hysteresisKmh.coerceAtLeast(0)
        val bucket = currentBucket ?: return resolveDynamicHideTurnSpeedBucket(safeSpeed)
        return when (bucket) {
            DynamicHideTurnSpeedBucket.UP_TO_40 -> {
                if (safeSpeed >= 40 + hysteresis) {
                    DynamicHideTurnSpeedBucket.FROM_40_TO_60
                } else {
                    bucket
                }
            }
            DynamicHideTurnSpeedBucket.FROM_40_TO_60 -> when {
                safeSpeed < 40 - hysteresis -> DynamicHideTurnSpeedBucket.UP_TO_40
                safeSpeed >= 60 + hysteresis -> DynamicHideTurnSpeedBucket.FROM_60_TO_100
                else -> bucket
            }
            DynamicHideTurnSpeedBucket.FROM_60_TO_100 -> when {
                safeSpeed < 60 - hysteresis -> DynamicHideTurnSpeedBucket.FROM_40_TO_60
                safeSpeed >= 100 + hysteresis -> DynamicHideTurnSpeedBucket.FROM_100_PLUS
                else -> bucket
            }
            DynamicHideTurnSpeedBucket.FROM_100_PLUS -> {
                if (safeSpeed < 100 - hysteresis) {
                    DynamicHideTurnSpeedBucket.FROM_60_TO_100
                } else {
                    bucket
                }
            }
        }
    }

    fun guideShown(context: Context): Boolean {
        return prefs(context).getBoolean(KEY_GUIDE_SHOWN, false)
    }

    fun setGuideShown(context: Context, shown: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_GUIDE_SHOWN, shown)
            .apply()
    }

    private fun normalizeHideTurnDistance(distanceMeters: Int): Int {
        var nearest = HIDE_TURN_DISTANCE_STEPS_METERS.first()
        var nearestDiff = abs(distanceMeters - nearest)
        for (step in HIDE_TURN_DISTANCE_STEPS_METERS) {
            val diff = abs(distanceMeters - step)
            if (diff < nearestDiff) {
                nearest = step
                nearestDiff = diff
            }
        }
        return nearest
    }

    private fun normalizeDynamicHideTurnDistance(distanceMeters: Int): Int {
        return distanceMeters.coerceIn(
            HIDE_TURN_DYNAMIC_DISTANCE_MIN_METERS,
            HIDE_TURN_DYNAMIC_DISTANCE_MAX_METERS
        )
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun defaultContainerSizeDp(context: Context): PointF {
        val display = HudDisplayUtils.resolveDisplay(context, displayId(context), allowFallback = false)
        if (display != null) {
            val metrics = context.createDisplayContext(display).resources.displayMetrics
            if (metrics.density > 0f) {
                return PointF(metrics.widthPixels / metrics.density, metrics.heightPixels / metrics.density)
            }
        }
        val density = context.resources.displayMetrics.density
        if (density <= 0f) {
            val size = maxOf(CONTAINER_MIN_SIZE_PX, CONTAINER_DEFAULT_SIZE_PX)
            return PointF(size, size)
        }
        val minDp = CONTAINER_MIN_SIZE_PX / density
        val defaultDp = CONTAINER_DEFAULT_SIZE_PX / density
        val size = maxOf(minDp, defaultDp)
        return PointF(size, size)
    }

    private fun defaultMapSizeDp(context: Context): PointF {
        val containerSize = containerSizeDp(context)
        val width = (containerSize.x * 0.6f).coerceAtLeast(MAP_MIN_SIZE_DP)
        val height = (containerSize.y * 0.45f).coerceAtLeast(MAP_MIN_SIZE_DP)
        return PointF(width, height)
    }

    private fun defaultMapPositionDp(context: Context): PointF {
        val containerSize = containerSizeDp(context)
        val mapSize = defaultMapSizeDp(context)
        val x = ((containerSize.x - mapSize.x) / 2f).coerceAtLeast(0f)
        val y = ((containerSize.y - mapSize.y) / 2f).coerceAtLeast(0f)
        return PointF(x, y)
    }

    private const val SPEED_BLOCK_SIZE_DP = 40f
    private const val NAV_BLOCK_DEFAULT_HEIGHT_DP = 56f
    private const val LANE_GUIDANCE_BLOCK_GAP_DP = 8f
    private const val LANE_GUIDANCE_BLOCK_HEIGHT_DP = 48f
    private const val HUD_SPEED_BLOCK_SIZE_DP = 48f
    private const val HUD_SPEED_BLOCK_GAP_DP = 8f
    private const val ROAD_CAMERA_BLOCK_GAP_DP = 8f
    private const val TRAFFIC_LIGHT_BLOCK_GAP_DP = 8f
    private const val SPEEDOMETER_BLOCK_HEIGHT_DP = 32f
    private const val TURN_SIGNALS_BLOCK_HEIGHT_DP = 24f
    private const val TURN_SIGNALS_BLOCK_GAP_DP = 8f
    private const val TURN_SIGNALS_SPACING_DEFAULT_DP = TURN_SIGNALS_ICON_SIZE_DP + TURN_SIGNALS_BLOCK_GAP_DP
    private const val CLOCK_BLOCK_HEIGHT_DP = 24f
    private const val DEFAULT_MARGIN_DP = 16f
    private const val CONTAINER_DEFAULT_SIZE_PX = 255f
}
