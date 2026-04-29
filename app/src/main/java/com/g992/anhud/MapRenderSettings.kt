package com.g992.anhud

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.max
import kotlin.math.min

data class ManualOfflineBounds(
    val label: String,
    val lat1: Double,
    val lon1: Double,
    val lat2: Double,
    val lon2: Double,
) {
    fun normalizedOrNull(): ManualOfflineBounds? {
        val normalizedLat1 = lat1.coerceIn(-90.0, 90.0)
        val normalizedLat2 = lat2.coerceIn(-90.0, 90.0)
        val normalizedLon1 = lon1.coerceIn(-180.0, 180.0)
        val normalizedLon2 = lon2.coerceIn(-180.0, 180.0)
        if (normalizedLat1 == normalizedLat2 || normalizedLon1 == normalizedLon2) {
            return null
        }
        return copy(
            label = label.trim().ifBlank { "Прямоугольник" },
            lat1 = normalizedLat1,
            lon1 = normalizedLon1,
            lat2 = normalizedLat2,
            lon2 = normalizedLon2,
        )
    }

    val south: Double
        get() = min(lat1, lat2)

    val north: Double
        get() = max(lat1, lat2)

    val west: Double
        get() = min(lon1, lon2)

    val east: Double
        get() = max(lon1, lon2)
}

data class MapRenderSettings(
    val zoom: Double = 17.8,
    val mapStyleId: String = MAP_STYLE_ID_MINIMAL,
    val autoZoomEnabled: Boolean = false,
    val autoZoomAt0Kmh: Double = 20.0,
    val autoZoomAt60Kmh: Double = 15.0,
    val autoZoomAt90Kmh: Double = 10.0,
    val tilt: Double = 58.0,
    val arrowScalePercent: Int = MAP_ARROW_SCALE_MAX_PERCENT,
    val cacheSizeStep: Int = 2,
    val downloadRouteEnabled: Boolean = false,
    val snapRouteToRoadsEnabled: Boolean = false,
    val snapLocationToRoadsEnabled: Boolean = false,
    val routeSnapDistanceMeters: Int = MAP_ROUTE_SNAP_DEFAULT_METERS,
    val roadEventsEnabled: Boolean = true,
    val roadEventIconSizePx: Int = ROAD_EVENT_ICON_SIZE_DEFAULT_PX,
    val hiddenRoadEventTypes: Set<String> = emptySet(),
    val tripStatusEnabled: Boolean = true,
    val laneGuidanceEnabled: Boolean = true,
    val laneGuidanceWidthPx: Int = LANE_GUIDANCE_WIDTH_DEFAULT_PX,
    val offlineRegionId: String? = null,
    val offlineManualLabel: String? = null,
    val offlineManualLat1: Double? = null,
    val offlineManualLon1: Double? = null,
    val offlineManualLat2: Double? = null,
    val offlineManualLon2: Double? = null,
)

const val MAP_ARROW_SCALE_MIN_PERCENT = 7
const val MAP_ARROW_SCALE_MAX_PERCENT = 30
const val MAP_ROUTE_SNAP_MIN_METERS = 3
const val MAP_ROUTE_SNAP_MAX_METERS = 10
const val MAP_ROUTE_SNAP_DEFAULT_METERS = 5
const val MAP_ZOOM_MIN = 10.0
const val MAP_ZOOM_MAX = 21.0
const val MAP_STYLE_ID_MINIMAL = "minimal"
const val ROAD_EVENT_ICON_SIZE_MIN_PX = 20
const val ROAD_EVENT_ICON_SIZE_MAX_PX = 60
const val ROAD_EVENT_ICON_SIZE_DEFAULT_PX = 35
const val LANE_GUIDANCE_WIDTH_MIN_PX = 40
const val LANE_GUIDANCE_WIDTH_MAX_PX = 180
const val LANE_GUIDANCE_WIDTH_DEFAULT_PX = 60

data class RoadEventOption(
    val typeKey: String,
    val title: String,
    val iconRes: Int,
)

val RoadEventOptions = listOf(
    RoadEventOption("SPEED_CONTROL", "Камера контроля скорости", R.drawable.pin_alerts_speed_camera),
    RoadEventOption("NO_STOPPING_CONTROL", "Камера на остановку", R.drawable.pin_alerts_no_stopping_control),
    RoadEventOption("LANE_CONTROL", "Камера контроля полосы", R.drawable.pin_alerts_lane_control),
    RoadEventOption("ROAD_MARKING_CONTROL", "Камера на разметку", R.drawable.pin_alerts_lane_control),
    RoadEventOption("CROSS_ROAD_CONTROL", "Камера контроля перекрёстка", R.drawable.pin_alerts_cross_road_control),
    RoadEventOption("MOBILE_CONTROL", "Мобильная засада", R.drawable.pin_alerts_mobile_control),
    RoadEventOption("DANGER", "Опасный участок", R.drawable.pin_alerts_danger),
    RoadEventOption("ACCIDENT", "ДТП", R.drawable.pin_alerts_accident),
    RoadEventOption("RECONSTRUCTION", "Дорожные работы", R.drawable.pin_alerts_road_works),
    RoadEventOption("CHAT", "Разговорчики", R.drawable.pin_alerts_chat),
    RoadEventOption("CLOSED", "Перекрытие", R.drawable.pin_alerts_closed),
    RoadEventOption("DRAWBRIDGE", "Разводной мост", R.drawable.pin_alerts_drawbridge),
    RoadEventOption("TRAFFIC_LIGHT", "Светофоры", R.drawable.route_alert_trafficlight),
    RoadEventOption("RAILWAY_CROSSING", "Ж/Д переезд", R.drawable.pin_alerts_railway_crossing),
    RoadEventOption("SPEED_BUMP", "Искусственная неровность", R.drawable.pin_alerts_speed_bump),
    RoadEventOption("RUGGED_ROAD", "Неровная дорога", R.drawable.pin_alerts_road_rugged),
    RoadEventOption("SCHOOL", "Школа", R.drawable.pin_alerts_school_ahead),
    RoadEventOption("EVENT", "Прочее", R.drawable.pin_alerts_other),
)

fun resolveRoadEventToggleKey(typeRaw: String?): String {
    val type = typeRaw.orEmpty().trim().uppercase()
    return when {
        "SCHOOL" in type -> "SCHOOL"
        "TRAFFIC_LIGHT" in type -> "TRAFFIC_LIGHT"
        "RAILWAY" in type -> "RAILWAY_CROSSING"
        "SPEED_BUMP" in type -> "SPEED_BUMP"
        "RUGGED" in type -> "RUGGED_ROAD"
        "SPEED_CONTROL" in type -> "SPEED_CONTROL"
        "NO_STOPPING" in type -> "NO_STOPPING_CONTROL"
        "LANE_CONTROL" in type -> "LANE_CONTROL"
        "ROAD_MARKING" in type -> "ROAD_MARKING_CONTROL"
        "CROSS_ROAD_CONTROL" in type -> "CROSS_ROAD_CONTROL"
        "MOBILE_CONTROL" in type || "POLICE" in type -> "MOBILE_CONTROL"
        "DANGER" in type -> "DANGER"
        "ACCIDENT" in type -> "ACCIDENT"
        "RECONSTRUCTION" in type || "ROAD_WORKS" in type -> "RECONSTRUCTION"
        "CHAT" in type -> "CHAT"
        "CLOSED" in type -> "CLOSED"
        "DRAWBRIDGE" in type -> "DRAWBRIDGE"
        else -> "EVENT"
    }
}

fun resolveRoadEventIconRes(typeRaw: String?): Int =
    RoadEventOptions.firstOrNull { it.typeKey == resolveRoadEventToggleKey(typeRaw) }?.iconRes
        ?: R.drawable.pin_alerts_other

data class MapStyleOption(
    val id: String,
    val title: String,
    val assetPath: String,
)

val MapStyleOptions = listOf(
    MapStyleOption(
        id = MAP_STYLE_ID_MINIMAL,
        title = "Минимальный",
        assetPath = "styles/map_minimal.json"
    ),
    MapStyleOption(
        id = "buildings",
        title = "Здания",
        assetPath = "styles/map_buildings.json"
    ),
)

fun resolveMapStyleOption(styleId: String?): MapStyleOption =
    MapStyleOptions.firstOrNull { it.id == normalizeMapStyleId(styleId) } ?: MapStyleOptions.first()

private fun normalizeMapStyleId(styleId: String?): String {
    return when (styleId) {
        "hud_minimal" -> MAP_STYLE_ID_MINIMAL
        "minimal_trafficlights" -> MAP_STYLE_ID_MINIMAL
        "buildings_trafficlights" -> "buildings"
        else -> styleId ?: MAP_STYLE_ID_MINIMAL
    }
}

val MapCacheSizeOptionsMb = intArrayOf(256, 512, 1024, 2048, 4096)

fun MapRenderSettings.cacheSizeMb(): Int =
    MapCacheSizeOptionsMb[cacheSizeStep.coerceIn(0, MapCacheSizeOptionsMb.lastIndex)]

fun MapRenderSettings.cacheSizeBytes(): Long = cacheSizeMb().toLong() * 1024L * 1024L

fun MapRenderSettings.manualOfflineBoundsOrNull(): ManualOfflineBounds? {
    val label = offlineManualLabel ?: return null
    val lat1 = offlineManualLat1 ?: return null
    val lon1 = offlineManualLon1 ?: return null
    val lat2 = offlineManualLat2 ?: return null
    val lon2 = offlineManualLon2 ?: return null
    return ManualOfflineBounds(
        label = label,
        lat1 = lat1,
        lon1 = lon1,
        lat2 = lat2,
        lon2 = lon2,
    ).normalizedOrNull()
}

fun MapRenderSettings.normalized(): MapRenderSettings {
    val base = copy(
        zoom = zoom.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
        mapStyleId = resolveMapStyleOption(mapStyleId).id,
        autoZoomAt0Kmh = autoZoomAt0Kmh.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
        autoZoomAt60Kmh = autoZoomAt60Kmh.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
        autoZoomAt90Kmh = autoZoomAt90Kmh.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
        tilt = tilt.coerceIn(0.0, 80.0),
        arrowScalePercent = arrowScalePercent.coerceIn(
            MAP_ARROW_SCALE_MIN_PERCENT,
            MAP_ARROW_SCALE_MAX_PERCENT
        ),
        cacheSizeStep = cacheSizeStep.coerceIn(0, MapCacheSizeOptionsMb.lastIndex),
        routeSnapDistanceMeters = routeSnapDistanceMeters.coerceIn(
            MAP_ROUTE_SNAP_MIN_METERS,
            MAP_ROUTE_SNAP_MAX_METERS
        ),
        roadEventIconSizePx = roadEventIconSizePx.coerceIn(
            ROAD_EVENT_ICON_SIZE_MIN_PX,
            ROAD_EVENT_ICON_SIZE_MAX_PX
        ),
        laneGuidanceWidthPx = laneGuidanceWidthPx.coerceIn(
            LANE_GUIDANCE_WIDTH_MIN_PX,
            LANE_GUIDANCE_WIDTH_MAX_PX
        ),
        hiddenRoadEventTypes = hiddenRoadEventTypes
            .map(::resolveRoadEventToggleKey)
            .filter { key -> RoadEventOptions.any { it.typeKey == key } }
            .toSet(),
        offlineRegionId = offlineRegionId?.takeIf { it.isNotBlank() },
    )
    return if (base.offlineRegionId != null) {
        base.copy(
            offlineManualLabel = null,
            offlineManualLat1 = null,
            offlineManualLon1 = null,
            offlineManualLat2 = null,
            offlineManualLon2 = null,
        )
    } else {
        val manual = base.manualOfflineBoundsOrNull()
        if (manual == null) {
            base.copy(
                offlineManualLabel = null,
                offlineManualLat1 = null,
                offlineManualLon1 = null,
                offlineManualLat2 = null,
                offlineManualLon2 = null,
            )
        } else {
            base.copy(
                offlineManualLabel = manual.label,
                offlineManualLat1 = manual.lat1,
                offlineManualLon1 = manual.lon1,
                offlineManualLat2 = manual.lat2,
                offlineManualLon2 = manual.lon2,
            )
        }
    }
}

object MapRenderSettingsStore {
    private const val PREFS_NAME = "map_render_settings"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_MAP_STYLE_ID = "map_style_id"
    private const val KEY_AUTO_ZOOM_ENABLED = "auto_zoom_enabled"
    private const val KEY_AUTO_ZOOM_AT_0 = "auto_zoom_at_0"
    private const val KEY_AUTO_ZOOM_AT_60 = "auto_zoom_at_60"
    private const val KEY_AUTO_ZOOM_AT_90 = "auto_zoom_at_90"
    private const val KEY_TILT = "tilt"
    private const val KEY_ARROW_SCALE = "arrow_scale_percent"
    private const val KEY_CACHE_SIZE_STEP = "cache_size_step"
    private const val KEY_DOWNLOAD_ROUTE = "download_route_enabled"
    private const val KEY_SNAP_ROUTE_TO_ROADS = "snap_route_to_roads_enabled"
    private const val KEY_SNAP_LOCATION_TO_ROADS = "snap_location_to_roads_enabled"
    private const val KEY_ROUTE_SNAP_DISTANCE = "route_snap_distance_meters"
    private const val KEY_ROAD_EVENTS_ENABLED = "road_events_enabled"
    private const val KEY_ROAD_EVENT_ICON_SIZE = "road_event_icon_size_px"
    private const val KEY_HIDDEN_ROAD_EVENT_TYPES = "hidden_road_event_types"
    private const val KEY_TRIP_STATUS_ENABLED = "trip_status_enabled"
    private const val KEY_LANE_GUIDANCE_ENABLED = "lane_guidance_enabled"
    private const val KEY_LANE_GUIDANCE_WIDTH = "lane_guidance_width_px"
    private const val KEY_OFFLINE_REGION_ID = "offline_region_id"
    private const val KEY_OFFLINE_MANUAL_LABEL = "offline_manual_label"
    private const val KEY_OFFLINE_MANUAL_LAT1 = "offline_manual_lat1"
    private const val KEY_OFFLINE_MANUAL_LON1 = "offline_manual_lon1"
    private const val KEY_OFFLINE_MANUAL_LAT2 = "offline_manual_lat2"
    private const val KEY_OFFLINE_MANUAL_LON2 = "offline_manual_lon2"

    private val listeners = CopyOnWriteArraySet<(MapRenderSettings) -> Unit>()

    private lateinit var prefs: SharedPreferences
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener
    @Volatile
    private var initialized = false
    @Volatile
    private var currentSettings = MapRenderSettings()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            currentSettings = readSettings()
            prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                currentSettings = readSettings()
                notifyListeners(currentSettings)
            }
            prefs.registerOnSharedPreferenceChangeListener(prefsListener)
            initialized = true
        }
    }

    fun current(): MapRenderSettings = currentSettings

    fun update(transform: (MapRenderSettings) -> MapRenderSettings) {
        check(initialized) { "MapRenderSettingsStore is not initialized" }
        val updated = transform(currentSettings).normalized()
        prefs.edit()
            .putFloat(KEY_ZOOM, updated.zoom.toFloat())
            .putString(KEY_MAP_STYLE_ID, updated.mapStyleId)
            .putBoolean(KEY_AUTO_ZOOM_ENABLED, updated.autoZoomEnabled)
            .putFloat(KEY_AUTO_ZOOM_AT_0, updated.autoZoomAt0Kmh.toFloat())
            .putFloat(KEY_AUTO_ZOOM_AT_60, updated.autoZoomAt60Kmh.toFloat())
            .putFloat(KEY_AUTO_ZOOM_AT_90, updated.autoZoomAt90Kmh.toFloat())
            .putFloat(KEY_TILT, updated.tilt.toFloat())
            .putInt(KEY_ARROW_SCALE, updated.arrowScalePercent)
            .putInt(KEY_CACHE_SIZE_STEP, updated.cacheSizeStep)
            .putBoolean(KEY_DOWNLOAD_ROUTE, updated.downloadRouteEnabled)
            .putBoolean(KEY_SNAP_ROUTE_TO_ROADS, updated.snapRouteToRoadsEnabled)
            .putBoolean(KEY_SNAP_LOCATION_TO_ROADS, updated.snapLocationToRoadsEnabled)
            .putInt(KEY_ROUTE_SNAP_DISTANCE, updated.routeSnapDistanceMeters)
            .putBoolean(KEY_ROAD_EVENTS_ENABLED, updated.roadEventsEnabled)
            .putInt(KEY_ROAD_EVENT_ICON_SIZE, updated.roadEventIconSizePx)
            .putStringSet(KEY_HIDDEN_ROAD_EVENT_TYPES, updated.hiddenRoadEventTypes)
            .putBoolean(KEY_TRIP_STATUS_ENABLED, updated.tripStatusEnabled)
            .putBoolean(KEY_LANE_GUIDANCE_ENABLED, updated.laneGuidanceEnabled)
            .putInt(KEY_LANE_GUIDANCE_WIDTH, updated.laneGuidanceWidthPx)
            .putString(KEY_OFFLINE_REGION_ID, updated.offlineRegionId)
            .putString(KEY_OFFLINE_MANUAL_LABEL, updated.offlineManualLabel)
            .putOptionalFloat(KEY_OFFLINE_MANUAL_LAT1, updated.offlineManualLat1)
            .putOptionalFloat(KEY_OFFLINE_MANUAL_LON1, updated.offlineManualLon1)
            .putOptionalFloat(KEY_OFFLINE_MANUAL_LAT2, updated.offlineManualLat2)
            .putOptionalFloat(KEY_OFFLINE_MANUAL_LON2, updated.offlineManualLon2)
            .apply()
        currentSettings = updated
        notifyListeners(updated)
    }

    fun addListener(listener: (MapRenderSettings) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (MapRenderSettings) -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners(settings: MapRenderSettings) {
        listeners.forEach { it(settings) }
    }

    private fun readSettings(): MapRenderSettings {
        return MapRenderSettings(
            zoom = prefs.getFloat(KEY_ZOOM, 17.8f).toDouble(),
            mapStyleId = prefs.getString(KEY_MAP_STYLE_ID, MAP_STYLE_ID_MINIMAL) ?: MAP_STYLE_ID_MINIMAL,
            autoZoomEnabled = prefs.getBoolean(KEY_AUTO_ZOOM_ENABLED, false),
            autoZoomAt0Kmh = prefs.getFloat(KEY_AUTO_ZOOM_AT_0, 20.0f).toDouble(),
            autoZoomAt60Kmh = prefs.getFloat(KEY_AUTO_ZOOM_AT_60, 15.0f).toDouble(),
            autoZoomAt90Kmh = prefs.getFloat(KEY_AUTO_ZOOM_AT_90, 10.0f).toDouble(),
            tilt = prefs.getFloat(KEY_TILT, 58.0f).toDouble(),
            arrowScalePercent = prefs.getInt(KEY_ARROW_SCALE, MAP_ARROW_SCALE_MAX_PERCENT),
            cacheSizeStep = prefs.getInt(KEY_CACHE_SIZE_STEP, 2),
            downloadRouteEnabled = prefs.getBoolean(KEY_DOWNLOAD_ROUTE, false),
            snapRouteToRoadsEnabled = prefs.getBoolean(KEY_SNAP_ROUTE_TO_ROADS, false),
            snapLocationToRoadsEnabled = prefs.getBoolean(KEY_SNAP_LOCATION_TO_ROADS, false),
            routeSnapDistanceMeters = prefs.getInt(KEY_ROUTE_SNAP_DISTANCE, MAP_ROUTE_SNAP_DEFAULT_METERS),
            roadEventsEnabled = prefs.getBoolean(KEY_ROAD_EVENTS_ENABLED, true),
            roadEventIconSizePx = prefs.getInt(KEY_ROAD_EVENT_ICON_SIZE, ROAD_EVENT_ICON_SIZE_DEFAULT_PX),
            hiddenRoadEventTypes = prefs.getStringSet(KEY_HIDDEN_ROAD_EVENT_TYPES, emptySet()).orEmpty(),
            tripStatusEnabled = prefs.getBoolean(KEY_TRIP_STATUS_ENABLED, true),
            laneGuidanceEnabled = prefs.getBoolean(KEY_LANE_GUIDANCE_ENABLED, true),
            laneGuidanceWidthPx = prefs.getInt(KEY_LANE_GUIDANCE_WIDTH, LANE_GUIDANCE_WIDTH_DEFAULT_PX),
            offlineRegionId = prefs.getString(KEY_OFFLINE_REGION_ID, null),
            offlineManualLabel = prefs.getString(KEY_OFFLINE_MANUAL_LABEL, null),
            offlineManualLat1 = prefs.getOptionalFloat(KEY_OFFLINE_MANUAL_LAT1),
            offlineManualLon1 = prefs.getOptionalFloat(KEY_OFFLINE_MANUAL_LON1),
            offlineManualLat2 = prefs.getOptionalFloat(KEY_OFFLINE_MANUAL_LAT2),
            offlineManualLon2 = prefs.getOptionalFloat(KEY_OFFLINE_MANUAL_LON2),
        ).normalized()
    }
}

private fun SharedPreferences.Editor.putOptionalFloat(key: String, value: Double?): SharedPreferences.Editor {
    if (value == null) {
        remove(key)
    } else {
        putFloat(key, value.toFloat())
    }
    return this
}

private fun SharedPreferences.getOptionalFloat(key: String): Double? {
    if (!contains(key)) return null
    return getFloat(key, 0f).toDouble()
}
