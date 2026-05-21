package com.g992.anhud

import android.content.Context
import android.net.Uri
import org.maplibre.android.maps.Style

enum class MapTileProvider(
    val id: String,
    val displayName: String,
) {
    OPEN_FREE_MAP("openfreemap", "OpenFreeMap"),
    OPEN_FREE_MAP_PROXY("openfreemap_proxy", "OpenFreeMap (прокси)");

    fun isConfigured(): Boolean = true
}

fun initializeMapTileProviderFallbacks(context: Context) {
    MapStyleTemplateStore.initialize(context)
}

fun defaultMapTileProvider(): MapTileProvider {
    return MapTileProvider.OPEN_FREE_MAP_PROXY
}

fun resolveMapTileProvider(providerId: String?): MapTileProvider {
    return MapTileProvider.entries.firstOrNull { it.id == providerId } ?: defaultMapTileProvider()
}

fun resolveConfiguredMapTileProvider(providerId: String?): MapTileProvider {
    val preferred = resolveMapTileProvider(providerId)
    return if (preferred.isConfigured()) preferred else defaultMapTileProvider()
}

fun currentMapTileProvider(context: Context): MapTileProvider {
    return resolveConfiguredMapTileProvider(MapRenderSettingsStore.current().tileProviderId)
}

fun advanceMapTileProvider(context: Context, failedProviderId: String): MapTileProvider? = null

fun buildHudMapStyle(context: Context): Style.Builder {
    val templateJson = MapStyleTemplateStore.loadTemplateJson(context)
    val styleJson = prepareHudMapStyleJson(
        templateJson = templateJson,
        provider = currentMapTileProvider(context),
        settings = MapRenderSettingsStore.current()
    )
    return Style.Builder().fromJson(styleJson)
}

internal fun buildStarLineStyleFetchUrl(): String {
    return Uri.Builder()
        .scheme("https")
        .authority("maps.starline.ru")
        .path("api/style/v1/get")
        .appendQueryParameter("id", BuildConfig.STARLINE_MAP_STYLE_ID)
        .appendQueryParameter("accessToken", BuildConfig.STARLINE_MAPS_ACCESS_TOKEN)
        .build()
        .toString()
}
