package com.g992.anhud

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CopyOnWriteArraySet

data class MapRenderSettings(
    val zoom: Double = 17.8,
    val autoZoomEnabled: Boolean = false,
    val autoZoomAt0Kmh: Double = 20.0,
    val autoZoomAt60Kmh: Double = 15.0,
    val autoZoomAt90Kmh: Double = 10.0,
    val tilt: Double = 58.0,
    val arrowScalePercent: Int = MAP_ARROW_SCALE_MAX_PERCENT,
    val cacheSizeStep: Int = 2,
    val downloadRouteEnabled: Boolean = false,
    val offlineRegionId: String? = null,
)

const val MAP_ARROW_SCALE_MIN_PERCENT = 7
const val MAP_ARROW_SCALE_MAX_PERCENT = 30
const val MAP_ZOOM_MIN = 10.0
const val MAP_ZOOM_MAX = 21.0

val MapCacheSizeOptionsMb = intArrayOf(256, 512, 1024, 2048, 4096)

fun MapRenderSettings.cacheSizeMb(): Int =
    MapCacheSizeOptionsMb[cacheSizeStep.coerceIn(0, MapCacheSizeOptionsMb.lastIndex)]

fun MapRenderSettings.cacheSizeBytes(): Long = cacheSizeMb().toLong() * 1024L * 1024L

fun MapRenderSettings.normalized(): MapRenderSettings = copy(
    zoom = zoom.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
    autoZoomAt0Kmh = autoZoomAt0Kmh.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
    autoZoomAt60Kmh = autoZoomAt60Kmh.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
    autoZoomAt90Kmh = autoZoomAt90Kmh.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX),
    tilt = tilt.coerceIn(0.0, 80.0),
    arrowScalePercent = arrowScalePercent.coerceIn(
        MAP_ARROW_SCALE_MIN_PERCENT,
        MAP_ARROW_SCALE_MAX_PERCENT
    ),
    cacheSizeStep = cacheSizeStep.coerceIn(0, MapCacheSizeOptionsMb.lastIndex),
)

object MapRenderSettingsStore {
    private const val PREFS_NAME = "map_render_settings"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_AUTO_ZOOM_ENABLED = "auto_zoom_enabled"
    private const val KEY_AUTO_ZOOM_AT_0 = "auto_zoom_at_0"
    private const val KEY_AUTO_ZOOM_AT_60 = "auto_zoom_at_60"
    private const val KEY_AUTO_ZOOM_AT_90 = "auto_zoom_at_90"
    private const val KEY_TILT = "tilt"
    private const val KEY_ARROW_SCALE = "arrow_scale_percent"
    private const val KEY_CACHE_SIZE_STEP = "cache_size_step"
    private const val KEY_DOWNLOAD_ROUTE = "download_route_enabled"
    private const val KEY_OFFLINE_REGION_ID = "offline_region_id"

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
            .putBoolean(KEY_AUTO_ZOOM_ENABLED, updated.autoZoomEnabled)
            .putFloat(KEY_AUTO_ZOOM_AT_0, updated.autoZoomAt0Kmh.toFloat())
            .putFloat(KEY_AUTO_ZOOM_AT_60, updated.autoZoomAt60Kmh.toFloat())
            .putFloat(KEY_AUTO_ZOOM_AT_90, updated.autoZoomAt90Kmh.toFloat())
            .putFloat(KEY_TILT, updated.tilt.toFloat())
            .putInt(KEY_ARROW_SCALE, updated.arrowScalePercent)
            .putInt(KEY_CACHE_SIZE_STEP, updated.cacheSizeStep)
            .putBoolean(KEY_DOWNLOAD_ROUTE, updated.downloadRouteEnabled)
            .putString(KEY_OFFLINE_REGION_ID, updated.offlineRegionId)
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
            autoZoomEnabled = prefs.getBoolean(KEY_AUTO_ZOOM_ENABLED, false),
            autoZoomAt0Kmh = prefs.getFloat(KEY_AUTO_ZOOM_AT_0, 20.0f).toDouble(),
            autoZoomAt60Kmh = prefs.getFloat(KEY_AUTO_ZOOM_AT_60, 15.0f).toDouble(),
            autoZoomAt90Kmh = prefs.getFloat(KEY_AUTO_ZOOM_AT_90, 10.0f).toDouble(),
            tilt = prefs.getFloat(KEY_TILT, 58.0f).toDouble(),
            arrowScalePercent = prefs.getInt(KEY_ARROW_SCALE, MAP_ARROW_SCALE_MAX_PERCENT),
            cacheSizeStep = prefs.getInt(KEY_CACHE_SIZE_STEP, 2),
            downloadRouteEnabled = prefs.getBoolean(KEY_DOWNLOAD_ROUTE, false),
            offlineRegionId = prefs.getString(KEY_OFFLINE_REGION_ID, null),
        ).normalized()
    }
}
