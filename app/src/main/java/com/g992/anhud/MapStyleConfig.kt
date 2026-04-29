package com.g992.anhud

import android.content.Context

const val MAP_STYLE_ASSET_PATH = "styles/hud_minimal.json"
const val MAP_STYLE_ASSET_URI = "asset://$MAP_STYLE_ASSET_PATH"

enum class MapTileProvider(
    val id: String,
    val displayName: String,
) {
    YANDEX("yandex", "Yandex MapKit");

    fun isConfigured(): Boolean = BuildConfig.MAPKIT_API_KEY.isNotBlank()
}

fun initializeMapTileProviderFallbacks(context: Context) = Unit

fun currentMapTileProvider(context: Context): MapTileProvider = MapTileProvider.YANDEX

fun advanceMapTileProvider(context: Context, failedProviderId: String): MapTileProvider? = null
