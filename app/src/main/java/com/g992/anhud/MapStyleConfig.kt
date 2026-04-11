package com.g992.anhud

import android.content.Context
import org.maplibre.android.maps.Style
import org.maplibre.android.offline.OfflineManager
import java.util.concurrent.TimeUnit

const val MAP_STYLE_ASSET_PATH = "styles/hud_minimal.json"
const val MAP_STYLE_ASSET_URI = "asset://$MAP_STYLE_ASSET_PATH"
const val MAP_STYLE_OFFLINE_CACHE_URL = "https://anhud.local/styles/hud_minimal.json"

fun buildHudMapStyle(context: Context): Style.Builder {
    return Style.Builder().fromUri(ensureHudMapStyleCached(context))
}

fun loadHudMapStyleJson(context: Context): String {
    return context.assets.open(MAP_STYLE_ASSET_PATH)
        .bufferedReader(Charsets.UTF_8)
        .use { it.readText() }
}

fun ensureHudMapStyleCached(context: Context): String {
    val appContext = context.applicationContext
    val nowSeconds = System.currentTimeMillis() / 1000L
    val expiresSeconds = nowSeconds + TimeUnit.DAYS.toSeconds(3650)
    OfflineManager.getInstance(appContext).putResourceWithUrl(
        MAP_STYLE_OFFLINE_CACHE_URL,
        loadHudMapStyleJson(appContext).toByteArray(Charsets.UTF_8),
        nowSeconds,
        expiresSeconds,
        "anhud-hud-style",
        false,
    )
    return MAP_STYLE_OFFLINE_CACHE_URL
}
