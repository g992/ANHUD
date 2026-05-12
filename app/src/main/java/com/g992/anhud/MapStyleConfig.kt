package com.g992.anhud

import android.content.Context
import android.net.Uri
import org.maplibre.android.maps.Style

const val MAP_STYLE_ASSET_PATH = "styles/hud_minimal.json"
const val MAP_STYLE_ASSET_URI = "asset://$MAP_STYLE_ASSET_PATH"

enum class MapTileProvider(
    val id: String,
    val displayName: String,
) {
    STARLINE("starline", "StarLine Maps"),
    OPEN_FREE_MAP("openfreemap", "OpenFreeMap");

    fun isConfigured(): Boolean {
        return when (this) {
            STARLINE -> BuildConfig.STARLINE_MAP_STYLE_ID.isNotBlank() &&
                BuildConfig.STARLINE_MAPS_ACCESS_TOKEN.isNotBlank()
            OPEN_FREE_MAP -> true
        }
    }
}

fun initializeMapTileProviderFallbacks(context: Context) = Unit

fun currentMapTileProvider(context: Context): MapTileProvider {
    return if (MapTileProvider.STARLINE.isConfigured()) {
        MapTileProvider.STARLINE
    } else {
        MapTileProvider.OPEN_FREE_MAP
    }
}

fun advanceMapTileProvider(context: Context, failedProviderId: String): MapTileProvider? = null

fun buildHudMapStyle(context: Context): Style.Builder {
    return Style.Builder().fromUri(resolveHudMapStyleUri(context))
}

private fun resolveHudMapStyleUri(context: Context): String {
    return when (currentMapTileProvider(context)) {
        MapTileProvider.STARLINE -> buildStarLineStyleUrl()
        MapTileProvider.OPEN_FREE_MAP -> MAP_STYLE_ASSET_URI
    }
}

private fun buildStarLineStyleUrl(): String {
    return Uri.Builder()
        .scheme("https")
        .authority("maps.starline.ru")
        .path("api/style/v1/get")
        .appendQueryParameter("id", BuildConfig.STARLINE_MAP_STYLE_ID)
        .appendQueryParameter("accessToken", BuildConfig.STARLINE_MAPS_ACCESS_TOKEN)
        .build()
        .toString()
}
