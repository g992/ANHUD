package com.g992.anhud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object PrefsJson {
    const val OVERLAY_PREFS_NAME = "hud_overlay_prefs"
    const val MANEUVER_PREFS_NAME = "maneuver_match_prefs"
    private const val DEFAULTS_ASSET = "maneuver_match_defaults.properties"

    fun buildPayload(context: Context): JSONObject {
        val payload = JSONObject()
        payload.put("version", 1)
        val prefsObject = JSONObject()
        prefsObject.put(OVERLAY_PREFS_NAME, serializeOverlayPrefs(context))
        prefsObject.put(MANEUVER_PREFS_NAME, serializeManeuverPrefs(context))
        payload.put("prefs", prefsObject)
        return payload
    }

    fun applyPayload(context: Context, payload: JSONObject): Boolean {
        val prefsObject = payload.optJSONObject("prefs") ?: return false
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
        return overlayApplied || maneuverApplied
    }

    fun payloadEquals(first: JSONObject, second: JSONObject): Boolean {
        val left = snapshotFromPayload(first)
        val right = snapshotFromPayload(second)
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

        val containerPos = OverlayPrefs.containerPositionDp(context)
        val containerSize = OverlayPrefs.containerSizeDp(context)
        val navPos = OverlayPrefs.navPositionDp(context)
        val arrowPos = OverlayPrefs.arrowPositionDp(context)
        val speedPos = OverlayPrefs.speedPositionDp(context)
        val hudSpeedPos = OverlayPrefs.hudSpeedPositionDp(context)
        val roadCameraPos = OverlayPrefs.roadCameraPositionDp(context)
        val trafficLightPos = OverlayPrefs.trafficLightPositionDp(context)
        val speedometerPos = OverlayPrefs.speedometerPositionDp(context)
        val clockPos = OverlayPrefs.clockPositionDp(context)

        putBoolean("overlay_enabled", OverlayPrefs.isEnabled(context))
        putInt("overlay_display_id", OverlayPrefs.displayId(context))
        putFloat("overlay_x_dp", prefs.getFloat("overlay_x_dp", 16f))
        putFloat("overlay_y_dp", prefs.getFloat("overlay_y_dp", 16f))
        putFloat("overlay_container_x_dp", containerPos.x)
        putFloat("overlay_container_y_dp", containerPos.y)
        putFloat("overlay_container_width_dp", containerSize.x)
        putFloat("overlay_container_height_dp", containerSize.y)
        putFloat("overlay_nav_x_dp", navPos.x)
        putFloat("overlay_nav_y_dp", navPos.y)
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
        putFloat("overlay_clock_x_dp", clockPos.x)
        putFloat("overlay_clock_y_dp", clockPos.y)
        putFloat("overlay_nav_scale", OverlayPrefs.navScale(context))
        putFloat("overlay_nav_text_scale", OverlayPrefs.navTextScale(context))
        putFloat("overlay_speed_text_scale", OverlayPrefs.speedTextScale(context))
        putFloat("overlay_arrow_scale", OverlayPrefs.arrowScale(context))
        putFloat("overlay_speed_scale", OverlayPrefs.speedScale(context))
        putFloat("overlay_hudspeed_scale", OverlayPrefs.hudSpeedScale(context))
        putFloat("overlay_road_camera_scale", OverlayPrefs.roadCameraScale(context))
        putFloat("overlay_traffic_light_scale", OverlayPrefs.trafficLightScale(context))
        putFloat("overlay_speedometer_scale", OverlayPrefs.speedometerScale(context))
        putFloat("overlay_clock_scale", OverlayPrefs.clockScale(context))
        putFloat("overlay_nav_alpha", OverlayPrefs.navAlpha(context))
        putFloat("overlay_arrow_alpha", OverlayPrefs.arrowAlpha(context))
        putFloat("overlay_speed_alpha", OverlayPrefs.speedAlpha(context))
        putFloat("overlay_hudspeed_alpha", OverlayPrefs.hudSpeedAlpha(context))
        putFloat("overlay_road_camera_alpha", OverlayPrefs.roadCameraAlpha(context))
        putFloat("overlay_traffic_light_alpha", OverlayPrefs.trafficLightAlpha(context))
        putFloat("overlay_speedometer_alpha", OverlayPrefs.speedometerAlpha(context))
        putFloat("overlay_clock_alpha", OverlayPrefs.clockAlpha(context))
        putFloat("overlay_container_alpha", OverlayPrefs.containerAlpha(context))
        putBoolean("overlay_nav_enabled", OverlayPrefs.navEnabled(context))
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
        putBoolean("overlay_clock_enabled", OverlayPrefs.clockEnabled(context))
        putInt("overlay_traffic_light_max_active", OverlayPrefs.trafficLightMaxActive(context))
        putBoolean("native_nav_enabled", OverlayPrefs.nativeNavEnabled(context))
        putBoolean("overlay_map_enabled", OverlayPrefs.mapEnabled(context))
        putInt("camera_timeout_near", OverlayPrefs.cameraTimeoutNear(context))
        putInt("camera_timeout_far", OverlayPrefs.cameraTimeoutFar(context))
        putInt("traffic_light_timeout", OverlayPrefs.trafficLightTimeout(context))
        putInt("nav_notification_end_timeout", OverlayPrefs.navNotificationEndTimeout(context))
        putInt("road_camera_timeout", OverlayPrefs.roadCameraTimeout(context))
        putInt("speed_correction", OverlayPrefs.speedCorrection(context))
        putBoolean("guide_shown", OverlayPrefs.guideShown(context))

        return items
    }

    private fun serializeManeuverPrefs(context: Context): JSONArray {
        val prefs = context.getSharedPreferences(MANEUVER_PREFS_NAME, Context.MODE_PRIVATE)
        val defaults = loadDefaultMappings(context)
        val merged = mutableMapOf<String, Any?>()
        for ((name, defaultId) in defaults) {
            val key = "mapping_$name"
            merged[key] = prefs.getString(key, defaultId) ?: defaultId
        }
        for ((key, value) in prefs.all) {
            merged[key] = value
        }
        val items = JSONArray()
        for (key in merged.keys.sorted()) {
            val entry = serializeValue(key, merged[key]) ?: continue
            items.put(entry)
        }
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
                value.filterIsInstance<String>().forEach { array.put(it) }
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
        for (index in 0 until entries.length()) {
            val entry = entries.optJSONObject(index) ?: continue
            val key = entry.optString("k", "")
            val type = entry.optString("t", "")
            if (key.isBlank()) continue
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
                defaults[key] = value
            }
        }
        return defaults
    }
}
