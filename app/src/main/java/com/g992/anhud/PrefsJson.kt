package com.g992.anhud

import android.content.Context
import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject

object PrefsJson {
    const val OVERLAY_PREFS_NAME = "hud_overlay_prefs"
    const val MANEUVER_PREFS_NAME = "maneuver_match_prefs"
    const val MAP_RENDER_PREFS_NAME = "map_render_settings"
    private const val DEFAULTS_ASSET = "maneuver_match_defaults.properties"
    private const val DEFAULT_MANEUVER_ICON_ID = "101"
    private const val MAX_SUPPORTED_MANEUVER_ICON_ID = 150
    private const val DEFAULT_PREFS_SUFFIX = "_preset_defaults"

    fun buildPayload(context: Context): JSONObject = buildPayloadInternal(context)

    fun applyPayload(context: Context, payload: JSONObject): Boolean {
        val sourcePrefs = payload.optJSONObject("prefs") ?: return false
        val prefsObject = normalizePayload(context, payload, sourcePrefs).optJSONObject("prefs") ?: return false
        val overlayApplied = applyPrefsFromJson(
            context,
            OVERLAY_PREFS_NAME,
            prefsObject.optJSONArray(OVERLAY_PREFS_NAME)
        )
        val maneuverApplied = applyPrefsFromJson(
            context,
            MANEUVER_PREFS_NAME,
            prefsObject.optJSONArray(MANEUVER_PREFS_NAME)
        )
        val mapRenderApplied = applyPrefsFromJson(
            context,
            MAP_RENDER_PREFS_NAME,
            prefsObject.optJSONArray(MAP_RENDER_PREFS_NAME)
        )
        return overlayApplied || maneuverApplied || mapRenderApplied
    }

    fun payloadEquals(context: Context, first: JSONObject, second: JSONObject): Boolean {
        val firstPrefs = first.optJSONObject("prefs") ?: return false
        val secondPrefs = second.optJSONObject("prefs") ?: return false
        val left = snapshotFromPayload(normalizePayload(context, first, firstPrefs))
        val right = snapshotFromPayload(normalizePayload(context, second, secondPrefs))
        if (left.keys != right.keys) return false
        for (prefName in left.keys) {
            val leftMap = left[prefName] ?: return false
            val rightMap = right[prefName] ?: return false
            if (leftMap.keys != rightMap.keys) return false
            for (key in leftMap.keys) {
                val leftEntry = leftMap[key] ?: return false
                val rightEntry = rightMap[key] ?: return false
                if (leftEntry.type != rightEntry.type) return false
                if (!valuesEqual(leftEntry.type, leftEntry.value, rightEntry.value)) {
                    return false
                }
            }
        }
        return true
    }

    private data class SnapshotEntry(
        val type: String,
        val value: Any?
    )

    private class DefaultsContext(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences(name + DEFAULT_PREFS_SUFFIX, mode)
    }

    private fun buildPayloadInternal(context: Context): JSONObject {
        val payload = JSONObject()
        payload.put("version", 1)
        val prefsObject = JSONObject()
        prefsObject.put(OVERLAY_PREFS_NAME, serializeOverlayPrefs(context))
        prefsObject.put(MANEUVER_PREFS_NAME, serializeManeuverPrefs(context))
        prefsObject.put(MAP_RENDER_PREFS_NAME, serializeMapRenderPrefs(context))
        payload.put("prefs", prefsObject)
        return payload
    }

    private fun buildDefaultPayload(context: Context): JSONObject {
        val defaultsContext = DefaultsContext(context.applicationContext)
        clearDefaultsPrefs(defaultsContext)
        return buildPayloadInternal(defaultsContext)
    }

    private fun clearDefaultsPrefs(context: Context) {
        listOf(OVERLAY_PREFS_NAME, MANEUVER_PREFS_NAME, MAP_RENDER_PREFS_NAME).forEach { prefName ->
            context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    private fun normalizePayload(context: Context, payload: JSONObject, sourcePrefs: JSONObject): JSONObject {
        val normalized = buildDefaultPayload(context)
        normalized.put("version", payload.optInt("version", 1))
        val normalizedPrefs = normalized.optJSONObject("prefs") ?: JSONObject().also {
            normalized.put("prefs", it)
        }
        val prefNames = sourcePrefs.keys()
        while (prefNames.hasNext()) {
            val prefName = prefNames.next()
            val mergedEntries = mergePrefEntries(
                normalizedPrefs.optJSONArray(prefName),
                sourcePrefs.optJSONArray(prefName)
            )
            normalizedPrefs.put(prefName, mergedEntries)
        }
        return normalized
    }

    private fun mergePrefEntries(baseEntries: JSONArray?, overrideEntries: JSONArray?): JSONArray {
        val merged = linkedMapOf<String, JSONObject>()

        fun consume(entries: JSONArray?) {
            if (entries == null) return
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val key = entry.optString("k", "")
                val type = entry.optString("t", "")
                if (key.isBlank() || type.isBlank()) continue
                merged[key] = JSONObject(entry.toString())
            }
        }

        consume(baseEntries)
        consume(overrideEntries)

        val result = JSONArray()
        merged.keys.sorted().forEach { key ->
            result.put(merged.getValue(key))
        }
        return result
    }

    private fun snapshotFromPayload(payload: JSONObject): Map<String, Map<String, SnapshotEntry>> {
        val prefsObject = payload.optJSONObject("prefs") ?: return emptyMap()
        val result = mutableMapOf<String, Map<String, SnapshotEntry>>()
        val keys = prefsObject.keys()
        while (keys.hasNext()) {
            val prefName = keys.next()
            val entries = prefsObject.optJSONArray(prefName) ?: continue
            val map = mutableMapOf<String, SnapshotEntry>()
            for (index in 0 until entries.length()) {
                val entry = entries.optJSONObject(index) ?: continue
                val key = entry.optString("k", "")
                val type = entry.optString("t", "")
                if (key.isBlank() || type.isBlank()) continue
                val value = when (type) {
                    "b" -> entry.optBoolean("v")
                    "f" -> entry.optDouble("v")
                    "i" -> entry.optInt("v")
                    "l" -> entry.optLong("v")
                    "s" -> entry.optString("v", "")
                    "ss" -> {
                        val array = entry.optJSONArray("v")
                        val set = mutableSetOf<String>()
                        if (array != null) {
                            for (i in 0 until array.length()) {
                                set.add(array.optString(i))
                            }
                        }
                        set
                    }
                    else -> null
                }
                map[key] = SnapshotEntry(type, value)
            }
            result[prefName] = map
        }
        return result
    }

    private fun valuesEqual(type: String, left: Any?, right: Any?): Boolean {
        return when (type) {
            "f" -> {
                val leftValue = (left as? Number)?.toDouble()
                val rightValue = (right as? Number)?.toDouble()
                if (leftValue == null || rightValue == null) return false
                kotlin.math.abs(leftValue - rightValue) < 0.0001
            }
            "ss" -> {
                val leftSet = left as? Set<*>
                val rightSet = right as? Set<*>
                if (leftSet == null || rightSet == null) return false
                leftSet == rightSet
            }
            else -> left == right
        }
    }

    private fun serializeOverlayPrefs(context: Context): JSONArray {
        val items = JSONArray()
        val prefs = context.getSharedPreferences(OVERLAY_PREFS_NAME, Context.MODE_PRIVATE)

        fun putBoolean(key: String, value: Boolean) {
            items.put(JSONObject().put("k", key).put("t", "b").put("v", value))
        }

        fun putFloat(key: String, value: Float) {
            items.put(JSONObject().put("k", key).put("t", "f").put("v", value.toDouble()))
        }

        fun putInt(key: String, value: Int) {
            items.put(JSONObject().put("k", key).put("t", "i").put("v", value))
        }

        fun putString(key: String, value: String?) {
            if (value == null) return
            items.put(JSONObject().put("k", key).put("t", "s").put("v", value))
        }

        val containerPos = OverlayPrefs.containerPositionDp(context)
        val containerSize = OverlayPrefs.containerSizeDp(context)
        val mapPos = OverlayPrefs.mapPositionDp(context)
        val mapSize = OverlayPrefs.mapSizeDp(context)
        val navPos = OverlayPrefs.navPositionDp(context)
        val laneGuidancePos = OverlayPrefs.laneGuidancePositionDp(context)
        val arrowPos = OverlayPrefs.arrowPositionDp(context)
        val speedPos = OverlayPrefs.speedPositionDp(context)
        val hudSpeedPos = OverlayPrefs.hudSpeedPositionDp(context)
        val roadCameraPos = OverlayPrefs.roadCameraPositionDp(context)
        val trafficLightPos = OverlayPrefs.trafficLightPositionDp(context)
        val speedometerPos = OverlayPrefs.speedometerPositionDp(context)
        val turnSignalsPos = OverlayPrefs.turnSignalsPositionDp(context)
        val clockPos = OverlayPrefs.clockPositionDp(context)
        val customTurnSignalIcon = OverlayPrefs.turnSignalsCustomIcon(context)

        putBoolean("overlay_enabled", OverlayPrefs.isEnabled(context))
        putInt("overlay_display_id", OverlayPrefs.displayId(context))
        putFloat("overlay_x_dp", prefs.getFloat("overlay_x_dp", 16f))
        putFloat("overlay_y_dp", prefs.getFloat("overlay_y_dp", 16f))
        putFloat("overlay_container_x_dp", containerPos.x)
        putFloat("overlay_container_y_dp", containerPos.y)
        putFloat("overlay_container_width_dp", containerSize.x)
        putFloat("overlay_container_height_dp", containerSize.y)
        putFloat("overlay_map_x_dp", mapPos.x)
        putFloat("overlay_map_y_dp", mapPos.y)
        putFloat("overlay_map_width_dp", mapSize.x)
        putFloat("overlay_map_height_dp", mapSize.y)
        putFloat("overlay_nav_x_dp", navPos.x)
        putFloat("overlay_nav_y_dp", navPos.y)
        putFloat("overlay_lane_guidance_x_dp", laneGuidancePos.x)
        putFloat("overlay_lane_guidance_y_dp", laneGuidancePos.y)
        putFloat("overlay_nav_width_dp", OverlayPrefs.navWidthDp(context))
        putFloat("overlay_arrow_x_dp", arrowPos.x)
        putFloat("overlay_arrow_y_dp", arrowPos.y)
        putFloat("overlay_speed_x_dp", speedPos.x)
        putFloat("overlay_speed_y_dp", speedPos.y)
        putFloat("overlay_hudspeed_x_dp", hudSpeedPos.x)
        putFloat("overlay_hudspeed_y_dp", hudSpeedPos.y)
        putFloat("overlay_road_camera_x_dp", roadCameraPos.x)
        putFloat("overlay_road_camera_y_dp", roadCameraPos.y)
        putFloat("overlay_traffic_light_x_dp", trafficLightPos.x)
        putFloat("overlay_traffic_light_y_dp", trafficLightPos.y)
        putFloat("overlay_speedometer_x_dp", speedometerPos.x)
        putFloat("overlay_speedometer_y_dp", speedometerPos.y)
        putFloat("overlay_turn_signals_x_dp", turnSignalsPos.x)
        putFloat("overlay_turn_signals_y_dp", turnSignalsPos.y)
        putBoolean(
            OverlayPrefs.KEY_TURN_SIGNALS_X_CENTER_MIGRATED,
            OverlayPrefs.turnSignalsPositionUsesCenterX(context)
        )
        putFloat("overlay_clock_x_dp", clockPos.x)
        putFloat("overlay_clock_y_dp", clockPos.y)
        putFloat("overlay_nav_scale", OverlayPrefs.navScale(context))
        putFloat("overlay_lane_guidance_scale", OverlayPrefs.laneGuidanceScale(context))
        putFloat("overlay_nav_text_scale", OverlayPrefs.navTextScale(context))
        putFloat("overlay_speed_text_scale", OverlayPrefs.speedTextScale(context))
        putFloat("overlay_arrow_scale", OverlayPrefs.arrowScale(context))
        putFloat("overlay_speed_scale", OverlayPrefs.speedScale(context))
        putFloat("overlay_hudspeed_scale", OverlayPrefs.hudSpeedScale(context))
        putFloat("overlay_road_camera_scale", OverlayPrefs.roadCameraScale(context))
        putFloat("overlay_traffic_light_scale", OverlayPrefs.trafficLightScale(context))
        putFloat("overlay_speedometer_scale", OverlayPrefs.speedometerScale(context))
        putFloat("overlay_turn_signals_scale", OverlayPrefs.turnSignalsScale(context))
        putFloat("overlay_turn_signals_spacing_dp", OverlayPrefs.turnSignalsSpacingDp(context))
        putInt("overlay_turn_signals_icon_style", OverlayPrefs.turnSignalsIconStyle(context))
        putString("overlay_turn_signals_custom_icon_uri", customTurnSignalIcon?.uriString)
        putString("overlay_turn_signals_custom_icon_name", customTurnSignalIcon?.displayName)
        putString(
            "overlay_turn_signals_custom_icon_base_direction",
            customTurnSignalIcon?.baseDirection?.name
        )
        putFloat("overlay_clock_scale", OverlayPrefs.clockScale(context))
        putFloat("overlay_nav_alpha", OverlayPrefs.navAlpha(context))
        putFloat("overlay_lane_guidance_alpha", OverlayPrefs.laneGuidanceAlpha(context))
        putFloat("overlay_arrow_alpha", OverlayPrefs.arrowAlpha(context))
        putFloat("overlay_speed_alpha", OverlayPrefs.speedAlpha(context))
        putFloat("overlay_hudspeed_alpha", OverlayPrefs.hudSpeedAlpha(context))
        putFloat("overlay_road_camera_alpha", OverlayPrefs.roadCameraAlpha(context))
        putFloat("overlay_traffic_light_alpha", OverlayPrefs.trafficLightAlpha(context))
        putFloat("overlay_speedometer_alpha", OverlayPrefs.speedometerAlpha(context))
        putFloat("overlay_turn_signals_alpha", OverlayPrefs.turnSignalsAlpha(context))
        putFloat("overlay_clock_alpha", OverlayPrefs.clockAlpha(context))
        putFloat("overlay_container_alpha", OverlayPrefs.containerAlpha(context))
        putFloat("overlay_map_alpha", OverlayPrefs.mapAlpha(context))
        putBoolean("overlay_nav_enabled", OverlayPrefs.navEnabled(context))
        putBoolean("overlay_lane_guidance_enabled", OverlayPrefs.laneGuidanceEnabled(context))
        putBoolean("overlay_arrow_enabled", OverlayPrefs.arrowEnabled(context))
        putBoolean("overlay_arrow_only_when_no_icon", OverlayPrefs.arrowOnlyWhenNoIcon(context))
        putBoolean("overlay_speed_enabled", OverlayPrefs.speedEnabled(context))
        putBoolean("overlay_speed_limit_from_hudspeed", OverlayPrefs.speedLimitFromHudSpeed(context))
        putBoolean("overlay_hudspeed_enabled", OverlayPrefs.hudSpeedEnabled(context))
        putBoolean("overlay_hudspeed_gps_status_enabled", OverlayPrefs.hudSpeedGpsStatusEnabled(context))
        putBoolean("overlay_hudspeed_limit_enabled", OverlayPrefs.hudSpeedLimitEnabled(context))
        putBoolean("overlay_hudspeed_limit_alert_enabled", OverlayPrefs.hudSpeedLimitAlertEnabled(context))
        putInt("overlay_hudspeed_limit_alert_threshold", OverlayPrefs.hudSpeedLimitAlertThreshold(context))
        putBoolean("overlay_road_camera_enabled", OverlayPrefs.roadCameraEnabled(context))
        putBoolean("overlay_traffic_light_enabled", OverlayPrefs.trafficLightEnabled(context))
        putBoolean("overlay_speed_limit_alert_enabled", OverlayPrefs.speedLimitAlertEnabled(context))
        putInt("overlay_speed_limit_alert_threshold", OverlayPrefs.speedLimitAlertThreshold(context))
        putBoolean("overlay_speedometer_enabled", OverlayPrefs.speedometerEnabled(context))
        putBoolean("overlay_speedometer_show_unit_text", OverlayPrefs.speedometerShowUnitText(context))
        putBoolean("overlay_turn_signals_enabled", OverlayPrefs.turnSignalsEnabled(context))
        putBoolean("overlay_clock_enabled", OverlayPrefs.clockEnabled(context))
        putInt("overlay_traffic_light_max_active", OverlayPrefs.trafficLightMaxActive(context))
        putBoolean("native_nav_enabled", OverlayPrefs.nativeNavEnabled(context))
        putBoolean("overlay_map_enabled", OverlayPrefs.mapEnabled(context))
        putBoolean("overlay_lane_guidance_show_distance", OverlayPrefs.laneGuidanceShowDistance(context))
        putInt("camera_timeout_near", OverlayPrefs.cameraTimeoutNear(context))
        putInt("camera_timeout_far", OverlayPrefs.cameraTimeoutFar(context))
        putInt("traffic_light_timeout", OverlayPrefs.trafficLightTimeout(context))
        putInt("nav_notification_end_timeout", OverlayPrefs.navNotificationEndTimeout(context))
        putInt("nav_updates_end_timeout", OverlayPrefs.navUpdatesEndTimeout(context))
        putInt("road_camera_timeout", OverlayPrefs.roadCameraTimeout(context))
        putInt("speed_correction", OverlayPrefs.speedCorrection(context))
        putInt("speedometer_freeze_timeout", OverlayPrefs.speedometerFreezeTimeout(context))
        putBoolean("speed_from_gps", OverlayPrefs.speedFromGps(context))
        putBoolean("info_mirror_starsheep7", OverlayPrefs.infoMirrorStarsheep7Enabled(context))
        putBoolean("hide_turn_when_far_enabled", OverlayPrefs.hideTurnWhenFarEnabled(context))
        putInt("hide_turn_when_far_distance_meters", OverlayPrefs.hideTurnWhenFarDistanceMeters(context))
        putBoolean("guide_shown", OverlayPrefs.guideShown(context))

        return items
    }

    private fun serializeManeuverPrefs(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(MANEUVER_PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = loadDefaultMappings(context)
        val merged = mutableMapOf<String, Any?>()
        for ((name, defaultId) in defaults) {
            val key = "mapping_$name"
            val stored = prefs.getString(key, defaultId) ?: defaultId
            merged[key] = sanitizeManeuverMappingValue(key, stored)
        }
        for ((key, value) in prefs.all) {
            merged[key] = sanitizeManeuverMappingValue(key, value)
        }
        val items = JSONArray()
        for (key in merged.keys.sorted()) {
            val entry = serializeValue(key, merged[key]) ?: continue
            items.put(entry)
        }
        return items
    }

    private fun serializeMapRenderPrefs(context: Context): JSONArray {
        val items = JSONArray()

        fun putFloat(key: String, value: Double) {
            items.put(JSONObject().put("k", key).put("t", "f").put("v", value))
        }

        fun putBoolean(key: String, value: Boolean) {
            items.put(JSONObject().put("k", key).put("t", "b").put("v", value))
        }

        fun putInt(key: String, value: Int) {
            items.put(JSONObject().put("k", key).put("t", "i").put("v", value))
        }

        fun putString(key: String, value: String?) {
            if (value == null) return
            items.put(JSONObject().put("k", key).put("t", "s").put("v", value))
        }

        fun putStringSet(key: String, value: Set<String>) {
            val array = JSONArray()
            value.sorted().forEach { entry ->
                array.put(entry)
            }
            items.put(JSONObject().put("k", key).put("t", "ss").put("v", array))
        }

        val settings = MapRenderSettingsStore.snapshot(context)
        putFloat("zoom", settings.zoom)
        putString("tile_provider_id", settings.tileProviderId)
        putBoolean("auto_zoom_enabled", settings.autoZoomEnabled)
        putFloat("auto_zoom_at_0", settings.autoZoomAt0Kmh)
        putFloat("auto_zoom_at_60", settings.autoZoomAt60Kmh)
        putFloat("auto_zoom_at_90", settings.autoZoomAt90Kmh)
        putFloat("tilt", settings.tilt)
        putInt("arrow_scale_percent", settings.arrowScalePercent)
        putInt("cache_size_step", settings.cacheSizeStep)
        putBoolean("download_route_enabled", settings.downloadRouteEnabled)
        putBoolean("snap_route_to_roads_enabled", settings.snapRouteToRoadsEnabled)
        putBoolean("snap_location_to_roads_enabled", settings.snapLocationToRoadsEnabled)
        putInt("route_snap_distance_meters", settings.routeSnapDistanceMeters)
        putBoolean("road_events_enabled", settings.roadEventsEnabled)
        putInt("road_event_icon_size_px", settings.roadEventIconSizePx)
        putStringSet("hidden_road_event_types", settings.hiddenRoadEventTypes)
        putBoolean("trip_status_enabled", settings.tripStatusEnabled)
        putBoolean("lane_guidance_enabled", settings.laneGuidanceEnabled)
        putInt("lane_guidance_width_px", settings.laneGuidanceWidthPx)
        putString("offline_region_id", settings.offlineRegionId)
        putString("offline_manual_label", settings.offlineManualLabel)
        settings.offlineManualLat1?.let { putFloat("offline_manual_lat1", it) }
        settings.offlineManualLon1?.let { putFloat("offline_manual_lon1", it) }
        settings.offlineManualLat2?.let { putFloat("offline_manual_lat2", it) }
        settings.offlineManualLon2?.let { putFloat("offline_manual_lon2", it) }
        return items
    }

    private fun serializeValue(key: String, value: Any?): JSONObject? {
        if (value == null) return null
        val entry = JSONObject()
        entry.put("k", key)
        when (value) {
            is Boolean -> {
                entry.put("t", "b")
                entry.put("v", value)
            }
            is Float -> {
                entry.put("t", "f")
                entry.put("v", value.toDouble())
            }
            is Double -> {
                entry.put("t", "f")
                entry.put("v", value)
            }
            is Int -> {
                entry.put("t", "i")
                entry.put("v", value)
            }
            is Long -> {
                entry.put("t", "l")
                entry.put("v", value)
            }
            is String -> {
                entry.put("t", "s")
                entry.put("v", value)
            }
            is Set<*> -> {
                val array = JSONArray()
                value.filterIsInstance<String>().sorted().forEach { item ->
                    array.put(item)
                }
                entry.put("t", "ss")
                entry.put("v", array)
            }
            else -> return null
        }
        return entry
    }

    private fun applyPrefsFromJson(context: Context, prefName: String, entries: JSONArray?): Boolean {
        if (entries == null) {
            return false
        }
        val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val key = entry.optString("k", "")
            val type = entry.optString("t", "")
            if (key.isBlank()) continue
            if (prefName == MANEUVER_PREFS_NAME && isManeuverMappingKey(key)) {
                val raw = when (type) {
                    "s" -> entry.optString("v", "")
                    "i" -> entry.optInt("v").toString()
                    "l" -> entry.optLong("v").toString()
                    "f" -> entry.optDouble("v").toInt().toString()
                    else -> null
                }
                editor.putString(key, sanitizeManeuverIconId(raw))
                continue
            }
            when (type) {
                "b" -> editor.putBoolean(key, entry.optBoolean("v"))
                "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
                "i" -> editor.putInt(key, entry.optInt("v"))
                "l" -> editor.putLong(key, entry.optLong("v"))
                "s" -> editor.putString(key, entry.optString("v", ""))
                "ss" -> {
                    val array = entry.optJSONArray("v")
                    val set = mutableSetOf<String>()
                    if (array != null) {
                        for (i in 0 until array.length()) {
                            set.add(array.optString(i))
                        }
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
        editor.apply()
        return true
    }

    private fun loadDefaultMappings(context: Context): Map<String, String> {
        val defaults = mutableMapOf<String, String>()
        val content = runCatching {
            context.assets.open(DEFAULTS_ASSET).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return defaults
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                return@forEach
            }
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.lastIndex) {
                return@forEach
            }
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                defaults[key] = sanitizeManeuverIconId(value)
            }
        }
        return defaults
    }

    private fun sanitizeManeuverMappingValue(key: String, value: Any?): Any? {
        if (!isManeuverMappingKey(key)) {
            return value
        }
        return sanitizeManeuverIconId(value?.toString())
    }

    private fun isManeuverMappingKey(key: String): Boolean = key.startsWith("mapping_")

    private fun sanitizeManeuverIconId(raw: String?): String {
        val value = raw?.trim().orEmpty()
        val numeric = value.toIntOrNull() ?: return DEFAULT_MANEUVER_ICON_ID
        return if (numeric > MAX_SUPPORTED_MANEUVER_ICON_ID) {
            DEFAULT_MANEUVER_ICON_ID
        } else {
            numeric.toString()
        }
    }
}
