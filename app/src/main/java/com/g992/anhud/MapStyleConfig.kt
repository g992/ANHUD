package com.g992.anhud

import android.content.Context
import org.maplibre.android.maps.Style

const val MAP_STYLE_ASSET_PATH = "styles/hud_minimal.json"

fun buildHudMapStyle(context: Context): Style.Builder {
    return Style.Builder().fromJson(loadHudMapStyleJson(context))
}

fun loadHudMapStyleJson(context: Context): String {
    return context.assets.open(MAP_STYLE_ASSET_PATH)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}
