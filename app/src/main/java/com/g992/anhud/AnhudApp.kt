package com.g992.anhud

import android.app.Application
import android.content.IntentFilter
import android.os.Build
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer

class AnhudApp : Application() {
    private val mapRouteTelemetryReceiver = MapRouteTelemetryReceiver()

    override fun onCreate() {
        super.onCreate()
        PerformanceDebugMonitor.start(this)
        MapLibre.getInstance(applicationContext, null, WellKnownTileServer.MapLibre)
        initializeMapTileProviderFallbacks(applicationContext)
        MapRenderSettingsStore.initialize(applicationContext)
        MapRouteTelemetryStore.initialize(applicationContext)
        MapCacheController.initialize(applicationContext)
        val filter = IntentFilter().apply {
            addAction(MAP_ROUTE_TELEMETRY_ACTION)
            addAction(MAP_ROUTE_STATE_ACTION)
            addAction(MAP_ROUTE_ALERTS_ACTION)
            addAction(MAP_ROUTE_ALERTS_ALT_ACTION)
            addAction(MAP_MANEUVER_BLOCK_ACTION)
            addAction(MAP_MANEUVER_BLOCK_ALT_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mapRouteTelemetryReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(mapRouteTelemetryReceiver, filter)
        }
    }
}
