package com.g992.anhud

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

const val MAP_STYLE_ASSET_PATH = "styles/hud_minimal.json"
const val MAP_STYLE_ASSET_URI = "asset://$MAP_STYLE_ASSET_PATH"
private const val MAP_STYLE_OFFLINE_CACHE_URL_BASE = "https://anhud.local/styles/hud_minimal"
private const val MAP_TILE_PREFS = "map_tile_source_prefs"
private const val MAP_TILE_PROVIDER_PREF_KEY = "active_provider"
private const val MAP_TILE_PROBE_TIMEOUT_MS = 3500
private const val MAP_TILE_SOURCE_TAG = "MapTileSource"

enum class MapTileProvider(
    val id: String,
    val displayName: String,
) {
    MAPTILER("maptiler", "MapTiler"),
    STADIA("stadia", "Stadia"),
    OPEN_FREE_MAP("openfreemap", "OpenFreeMap");

    fun tileJsonUrl(): String {
        return when (this) {
            MAPTILER -> "https://api.maptiler.com/tiles/v3/tiles.json?key=${BuildConfig.MAPTILER_API_KEY}"
            STADIA -> "https://tiles.stadiamaps.com/data/openmaptiles.json?api_key=${BuildConfig.STADIA_MAPS_API_KEY}"
            OPEN_FREE_MAP -> "https://tiles.openfreemap.org/planet"
        }
    }

    fun isConfigured(): Boolean {
        return when (this) {
            MAPTILER -> BuildConfig.MAPTILER_API_KEY.isNotBlank()
            STADIA -> BuildConfig.STADIA_MAPS_API_KEY.isNotBlank()
            OPEN_FREE_MAP -> true
        }
    }
}

@Volatile
private var tileProviderProbeStarted = false

fun buildHudMapStyle(context: Context): Style.Builder {
    return Style.Builder().fromUri(ensureHudMapStyleCached(context))
}

fun initializeMapTileProviderFallbacks(context: Context) {
    if (tileProviderProbeStarted) return
    tileProviderProbeStarted = true
    val appContext = context.applicationContext
    Thread {
        runCatching { selectReachableMapTileProvider(appContext) }
            .onFailure { Log.w(MAP_TILE_SOURCE_TAG, "tile provider probe failed: ${it.message}") }
    }.start()
}

fun loadHudMapStyleJson(
    context: Context,
    provider: MapTileProvider = currentMapTileProvider(context),
): String {
    val styleJson = JSONObject(
        context.assets.open(MAP_STYLE_ASSET_PATH)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
    )
    styleJson.getJSONObject("sources").put(
        "openmaptiles",
        JSONObject()
            .put("type", "vector")
            .put("url", provider.tileJsonUrl())
    )
    return styleJson.toString()
}

fun ensureHudMapStyleCached(
    context: Context,
    provider: MapTileProvider = currentMapTileProvider(context),
): String {
    val appContext = context.applicationContext
    val nowSeconds = System.currentTimeMillis() / 1000L
    val expiresSeconds = nowSeconds + TimeUnit.DAYS.toSeconds(3650)
    val cacheUrl = "$MAP_STYLE_OFFLINE_CACHE_URL_BASE.${provider.id}.json"
    OfflineManager.getInstance(appContext).putResourceWithUrl(
        cacheUrl,
        loadHudMapStyleJson(appContext, provider).toByteArray(Charsets.UTF_8),
        nowSeconds,
        expiresSeconds,
        "anhud-hud-style-${provider.id}",
        false,
    )
    return cacheUrl
}

fun currentMapTileProvider(context: Context): MapTileProvider {
    val available = configuredMapTileProviders()
    val savedId = context.applicationContext
        .getSharedPreferences(MAP_TILE_PREFS, Context.MODE_PRIVATE)
        .getString(MAP_TILE_PROVIDER_PREF_KEY, null)
    val saved = available.firstOrNull { it.id == savedId }
    return saved ?: available.first()
}

fun advanceMapTileProvider(context: Context, failedProviderId: String): MapTileProvider? {
    val available = configuredMapTileProviders()
    val failedIndex = available.indexOfFirst { it.id == failedProviderId }
    val next = available.getOrNull(failedIndex + 1) ?: return null
    persistMapTileProvider(context, next)
    return next
}

fun selectReachableMapTileProvider(context: Context): MapTileProvider {
    val available = configuredMapTileProviders()
    val preferred = currentMapTileProvider(context)
    val ordered = buildList {
        add(preferred)
        addAll(available.filter { it != preferred })
    }
    ordered.firstOrNull { isTileProviderReachable(it) }?.let { reachable ->
        persistMapTileProvider(context, reachable)
        Log.d(MAP_TILE_SOURCE_TAG, "selected tile provider: ${reachable.displayName}")
        return reachable
    }
    return preferred
}

private fun configuredMapTileProviders(): List<MapTileProvider> {
    return listOf(
        MapTileProvider.MAPTILER,
        MapTileProvider.STADIA,
        MapTileProvider.OPEN_FREE_MAP
    ).filter { it.isConfigured() }
}

private fun persistMapTileProvider(context: Context, provider: MapTileProvider) {
    context.applicationContext
        .getSharedPreferences(MAP_TILE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(MAP_TILE_PROVIDER_PREF_KEY, provider.id)
        .apply()
}

private fun isTileProviderReachable(provider: MapTileProvider): Boolean {
    return runCatching {
        (URL(provider.tileJsonUrl()).openConnection() as HttpURLConnection).run {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = MAP_TILE_PROBE_TIMEOUT_MS
            readTimeout = MAP_TILE_PROBE_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            connect()
            responseCode in 200..299
        }
    }.getOrElse {
        Log.w(MAP_TILE_SOURCE_TAG, "provider ${provider.displayName} probe failed: ${it.message}")
        false
    }
}
