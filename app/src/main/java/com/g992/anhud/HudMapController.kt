package com.g992.anhud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponent
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.OnLocationCameraTransitionListener
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

private const val MAP_ROUTE_SOURCE_ID = "anhud-map-route-source"
private const val MAP_ROUTE_LAYER_ID = "anhud-map-route-layer"
private const val MAP_ROUTE_COLOR_PROP = "routeColor"
private const val MAP_MAX_FPS = 15
private const val MAP_MAX_LAST_KNOWN_LOCATION_AGE_MS = 10_000L
private const val MAP_TRACKING_TOP_PADDING_RATIO = 0.97f
private const val HUD_MAP_TAG = "HudMapController"

class HudMapController(
    private val context: Context,
) {
    private val mapView = MapView(
        context,
        MapLibreMapOptions()
            .textureMode(true)
            .translucentTextureSurface(true)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .zoomGesturesEnabled(false)
            .doubleTapGesturesEnabled(false)
            .quickZoomGesturesEnabled(false)
            .scrollGesturesEnabled(false)
            .horizontalScrollGesturesEnabled(false)
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)
    )

    private var mapLibreMap: MapLibreMap? = null
    private var locationComponent: LocationComponent? = null
    private var deviceLocationListener: LocationListener? = null
    private var released = false
    private var currentSettings = MapRenderSettingsStore.current()
    private var currentSnapshot = MapRouteTelemetryStore.current()
    private var currentLocation: Location? = currentSnapshot.startLocation
    private var currentSpeedBucketKmh: Int = initialSpeedBucket(NavigationHudStore.snapshot().speedKmh)
    private var lastRouteRenderDebugKey: String? = null

    private val settingsListener: (MapRenderSettings) -> Unit = { settings ->
        currentSettings = settings
        applyLocationStyle()
        applyTrackingConfig()
    }

    private val routeListener: (MapRouteTelemetrySnapshot) -> Unit = { snapshot ->
        Log.d(
            HUD_MAP_TAG,
            "route snapshot received: state=${snapshot.state} hasRoute=${snapshot.hasRoute} " +
                "points=${snapshot.routePoints.size} jams=${snapshot.routeJams.size}"
        )
        currentSnapshot = snapshot
        applyTelemetrySnapshot()
    }

    private val navStateListener = object : NavigationHudStore.Listener {
        override fun onStateUpdated(state: NavigationHudState) {
            val newBucket = applySpeedBucketHysteresis(currentSpeedBucketKmh, state.speedKmh)
            if (newBucket == currentSpeedBucketKmh) return
            currentSpeedBucketKmh = newBucket
            if (currentSettings.autoZoomEnabled) {
                applyTrackingConfig()
            }
        }
    }

    init {
        MapRenderSettingsStore.addListener(settingsListener)
        MapRouteTelemetryStore.addListener(routeListener)
        NavigationHudStore.registerListener(navStateListener)
        mapView.setBackgroundColor(Color.BLACK)
        mapView.setMaximumFps(MAP_MAX_FPS)
        mapView.setOnTouchListener { _, _: MotionEvent -> true }
        mapView.onCreate(null)
        mapView.getMapAsync(::onMapReady)
        mapView.onStart()
        mapView.onResume()
    }

    fun attachTo(container: FrameLayout) {
        if (released) return
        val currentParent = mapView.parent as? ViewGroup
        if (currentParent !== container) {
            currentParent?.removeView(mapView)
            container.addView(
                mapView,
                0,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        mapView.visibility = View.VISIBLE
        applyTrackingConfig()
    }

    fun setVisible(visible: Boolean) {
        if (released) return
        mapView.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    fun release() {
        if (released) return
        released = true
        MapRenderSettingsStore.removeListener(settingsListener)
        MapRouteTelemetryStore.removeListener(routeListener)
        NavigationHudStore.unregisterListener(navStateListener)
        stopDeviceLocationTracking()
        mapView.onPause()
        mapView.onStop()
        mapView.onDestroy()
        (mapView.parent as? ViewGroup)?.removeView(mapView)
    }

    private fun onMapReady(map: MapLibreMap) {
        mapLibreMap = map
        map.uiSettings.setAllGesturesEnabled(false)
        map.uiSettings.setRotateGesturesEnabled(false)
        map.uiSettings.setTiltGesturesEnabled(false)
        map.uiSettings.setZoomGesturesEnabled(false)
        map.uiSettings.setDoubleTapGesturesEnabled(false)
        map.uiSettings.setQuickZoomGesturesEnabled(false)
        map.uiSettings.setScrollGesturesEnabled(false)
        map.uiSettings.setHorizontalScrollGesturesEnabled(false)
        map.uiSettings.setCompassEnabled(false)
        map.uiSettings.setLogoEnabled(false)
        map.uiSettings.setAttributionEnabled(false)
        map.setStyle(buildHudMapStyle(context)) { style ->
            Log.d(HUD_MAP_TAG, "map style loaded")
            ensureRouteLayer(style)
            enableLocationComponent(map, style)
            startDeviceLocationTracking()
            applyTelemetrySnapshot()
            applyTrackingConfig()
        }
    }

    private fun enableLocationComponent(map: MapLibreMap, style: Style) {
        val component = map.locationComponent
        component.activateLocationComponent(
            LocationComponentActivationOptions.builder(context, style)
                .locationComponentOptions(buildLocationOptions(context, currentSettings))
                .useDefaultLocationEngine(false)
                .build()
        )
        component.isLocationComponentEnabled = true
        component.renderMode = RenderMode.GPS
        component.setCameraMode(
            CameraMode.TRACKING_GPS,
            object : OnLocationCameraTransitionListener {
                override fun onLocationCameraTransitionFinished(cameraMode: Int) {
                    component.paddingWhileTracking(trackingPaddingValues())
                    component.zoomWhileTracking(resolveTargetZoom(currentSettings, currentSpeedBucketKmh))
                    component.tiltWhileTracking(currentSettings.tilt)
                }

                override fun onLocationCameraTransitionCanceled(cameraMode: Int) = Unit
            }
        )
        locationComponent = component
        applyLocationStyle()
        resolveDisplayLocation()?.let(component::forceLocationUpdate)
    }

    private fun ensureRouteLayer(style: Style) {
        if (style.getSource(MAP_ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(MAP_ROUTE_SOURCE_ID, FeatureCollection.fromFeatures(emptyArray())))
        }
        if (style.getLayer(MAP_ROUTE_LAYER_ID) == null) {
            style.addLayer(
                LineLayer(MAP_ROUTE_LAYER_ID, MAP_ROUTE_SOURCE_ID).withProperties(
                    lineColor(org.maplibre.android.style.expressions.Expression.get(MAP_ROUTE_COLOR_PROP)),
                    lineWidth(6.5f),
                    lineOpacity(0.98f),
                    lineCap("round"),
                    lineJoin("round")
                )
            )
        }
    }

    private fun applyTelemetrySnapshot() {
        val style = mapLibreMap?.style ?: run {
            Log.d(HUD_MAP_TAG, "route render skipped: style is not ready, points=${currentSnapshot.routePoints.size}")
            return
        }
        ensureRouteLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(MAP_ROUTE_SOURCE_ID) ?: run {
            Log.w(HUD_MAP_TAG, "route render skipped: source is missing")
            return
        }
        val points = currentSnapshot.routePoints
        if (points.size >= 2) {
            val visibleSegments = trimRouteSegments(points, currentSnapshot.routeJams, currentLocation)
            val segmentFeatures = visibleSegments.map { segment ->
                Feature.fromGeometry(
                    LineString.fromLngLats(
                        listOf(
                            Point.fromLngLat(segment.start.longitude, segment.start.latitude),
                            Point.fromLngLat(segment.end.longitude, segment.end.latitude)
                        )
                    )
                ).apply {
                    addStringProperty(MAP_ROUTE_COLOR_PROP, routeJamColor(segment.jam))
                }
            }
            logRouteRender(points.size, segmentFeatures.size)
            source.setGeoJson(FeatureCollection.fromFeatures(segmentFeatures))
        } else {
            logRouteRender(points.size, 0)
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
        }
        resolveDisplayLocation()?.let { location ->
            locationComponent?.forceLocationUpdate(location)
        }
    }

    private fun logRouteRender(pointCount: Int, visibleSegmentCount: Int) {
        val debugKey = "${currentSnapshot.routeToken.orEmpty()}|${currentSnapshot.state}|$pointCount|$visibleSegmentCount"
        if (debugKey == lastRouteRenderDebugKey) return
        lastRouteRenderDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "route rendered: state=${currentSnapshot.state} points=$pointCount visibleSegments=$visibleSegmentCount " +
                "hasLocation=${currentLocation != null}"
        )
    }

    private fun applyLocationStyle() {
        val component = locationComponent ?: return
        component.applyStyle(buildLocationOptions(context, currentSettings))
    }

    private fun applyTrackingConfig() {
        val component = locationComponent ?: return
        component.paddingWhileTracking(trackingPaddingValues())
        component.zoomWhileTracking(resolveTargetZoom(currentSettings, currentSpeedBucketKmh))
        component.tiltWhileTracking(currentSettings.tilt)
        trackingPadding().also { padding ->
            mapLibreMap?.setPadding(padding[0], padding[1], padding[2], padding[3])
        }
    }

    private fun trackingPadding(): IntArray {
        val side = dp(18)
        val top = (mapView.height.coerceAtLeast(1) * MAP_TRACKING_TOP_PADDING_RATIO).roundToInt()
        return intArrayOf(side, top, side, 0)
    }

    private fun trackingPaddingValues(): DoubleArray {
        val padding = trackingPadding()
        return doubleArrayOf(
            padding[0].toDouble(),
            padding[1].toDouble(),
            padding[2].toDouble(),
            padding[3].toDouble()
        )
    }

    private fun startDeviceLocationTracking() {
        if (deviceLocationListener != null || !hasLocationPermission()) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val listener = LocationListener { location ->
            currentLocation = location
            locationComponent?.forceLocationUpdate(location)
            applyTelemetrySnapshot()
        }
        deviceLocationListener = listener
        preferredLocationProviders(manager).forEach { provider ->
            latestKnownLocation(manager, provider)?.let {
                currentLocation = it
                locationComponent?.forceLocationUpdate(it)
            }
            runCatching {
                manager.requestLocationUpdates(provider, 1000L, 3f, listener, android.os.Looper.getMainLooper())
            }.onFailure {
                Log.w(HUD_MAP_TAG, "requestLocationUpdates failed for $provider: ${it.message}")
            }
        }
        applyTelemetrySnapshot()
    }

    private fun stopDeviceLocationTracking() {
        val listener = deviceLocationListener ?: return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        runCatching { manager.removeUpdates(listener) }
        deviceLocationListener = null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun resolveDisplayLocation(): Location? {
        return currentLocation ?: currentSnapshot.startLocation
    }
}

private fun buildLocationOptions(
    context: Context,
    settings: MapRenderSettings,
): LocationComponentOptions {
    val arrowScale = (settings.arrowScalePercent / 100f)
        .coerceIn(
            MAP_ARROW_SCALE_MIN_PERCENT / 100f,
            MAP_ARROW_SCALE_MAX_PERCENT / 100f
        )
    return LocationComponentOptions.builder(context)
        .gpsDrawable(R.drawable.ic_nav_arrow)
        .foregroundDrawable(R.drawable.ic_nav_arrow)
        .foregroundDrawableStale(R.drawable.ic_nav_arrow)
        .backgroundDrawable(R.drawable.ic_transparent_puck)
        .backgroundDrawableStale(R.drawable.ic_transparent_puck)
        .bearingDrawable(R.drawable.ic_transparent_puck)
        .maxZoomIconScale(arrowScale)
        .minZoomIconScale(arrowScale)
        .build()
}

private fun resolveTargetZoom(settings: MapRenderSettings, speedBucketKmh: Int): Double {
    if (!settings.autoZoomEnabled) {
        return settings.zoom
    }
    val speed = speedBucketKmh.coerceAtLeast(0).toDouble()
    val zoom = when {
        speed <= 60.0 -> interpolateLinear(
            x0 = 0.0,
            y0 = settings.autoZoomAt0Kmh,
            x1 = 60.0,
            y1 = settings.autoZoomAt60Kmh,
            x = speed
        )
        else -> interpolateLinear(
            x0 = 60.0,
            y0 = settings.autoZoomAt60Kmh,
            x1 = 90.0,
            y1 = settings.autoZoomAt90Kmh,
            x = speed
        )
    }
    return zoom.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
}

private fun interpolateLinear(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x: Double,
): Double {
    if (kotlin.math.abs(x1 - x0) < 0.0001) return y0
    val t = (x - x0) / (x1 - x0)
    return y0 + ((y1 - y0) * t)
}

private fun initialSpeedBucket(speedKmh: Int?): Int {
    val safeSpeed = (speedKmh ?: 0).coerceAtLeast(0)
    return ((safeSpeed + 5) / 10) * 10
}

private fun applySpeedBucketHysteresis(currentBucketKmh: Int, speedKmh: Int?): Int {
    val safeSpeed = (speedKmh ?: 0).coerceAtLeast(0)
    var bucket = currentBucketKmh.coerceAtLeast(0)
    while (safeSpeed > bucket + 5) {
        bucket += 10
    }
    while (safeSpeed < bucket - 5) {
        bucket = (bucket - 10).coerceAtLeast(0)
    }
    return bucket
}

private fun routeJamColor(jam: String?): String {
    return when (jam?.lowercase()) {
        "low" -> "#2BC96F"
        "moderate" -> "#FFC845"
        "heavy" -> "#FF5B4D"
        "severe" -> "#FF5B4D"
        else -> "#2BC96F"
    }
}

private data class VisibleRouteSegment(
    val start: LatLng,
    val end: LatLng,
    val jam: String?,
)

private fun trimRouteSegments(
    points: List<LatLng>,
    jams: List<String>,
    location: Location?,
): List<VisibleRouteSegment> {
    if (points.size < 2) return emptyList()
    if (location == null) {
        return points.zipWithNext().mapIndexed { index, (start, end) ->
            VisibleRouteSegment(start, end, jams.getOrNull(index))
        }
    }
    val closest = findClosestRouteProjection(points, location) ?: return points.zipWithNext().mapIndexed { index, (start, end) ->
        VisibleRouteSegment(start, end, jams.getOrNull(index))
    }
    val result = mutableListOf<VisibleRouteSegment>()
    val projectedStart = closest.projectedPoint
    val firstEnd = points[closest.segmentIndex + 1]
    if (distanceMeters(projectedStart, firstEnd) > 1.0) {
        result += VisibleRouteSegment(projectedStart, firstEnd, jams.getOrNull(closest.segmentIndex))
    }
    for (index in (closest.segmentIndex + 1) until points.lastIndex) {
        result += VisibleRouteSegment(points[index], points[index + 1], jams.getOrNull(index))
    }
    return result
}

private data class RouteProjection(
    val segmentIndex: Int,
    val projectedPoint: LatLng,
    val distanceMeters: Double,
)

private fun findClosestRouteProjection(points: List<LatLng>, location: Location): RouteProjection? {
    if (points.size < 2) return null
    var best: RouteProjection? = null
    val targetLat = location.latitude
    val targetLon = location.longitude
    for (index in 0 until points.lastIndex) {
        val projection = projectPointOntoSegment(targetLat, targetLon, points[index], points[index + 1])
        if (best == null || projection.distanceMeters < best.distanceMeters) {
            best = RouteProjection(index, projection.projectedPoint, projection.distanceMeters)
        }
    }
    return best
}

private data class SegmentProjection(
    val projectedPoint: LatLng,
    val distanceMeters: Double,
)

private fun projectPointOntoSegment(
    targetLat: Double,
    targetLon: Double,
    start: LatLng,
    end: LatLng,
): SegmentProjection {
    val latitudeScale = 111_320.0
    val longitudeScale = 111_320.0 * kotlin.math.cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
    val ax = start.longitude * longitudeScale
    val ay = start.latitude * latitudeScale
    val bx = end.longitude * longitudeScale
    val by = end.latitude * latitudeScale
    val px = targetLon * longitudeScale
    val py = targetLat * latitudeScale
    val abx = bx - ax
    val aby = by - ay
    val abLengthSquared = abx * abx + aby * aby
    val t = if (abLengthSquared <= 0.0001) 0.0 else (((px - ax) * abx) + ((py - ay) * aby)) / abLengthSquared
    val clampedT = t.coerceIn(0.0, 1.0)
    val closestX = ax + abx * clampedT
    val closestY = ay + aby * clampedT
    val projectedPoint = LatLng(
        closestY / latitudeScale,
        if (longitudeScale == 0.0) start.longitude else closestX / longitudeScale
    )
    return SegmentProjection(
        projectedPoint = projectedPoint,
        distanceMeters = kotlin.math.hypot(px - closestX, py - closestY)
    )
}

private fun distanceMeters(start: LatLng, end: LatLng): Double {
    val latitudeScale = 111_320.0
    val longitudeScale = 111_320.0 * kotlin.math.cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
    val dx = (end.longitude - start.longitude) * longitudeScale
    val dy = (end.latitude - start.latitude) * latitudeScale
    return kotlin.math.hypot(dx, dy)
}

private fun preferredLocationProviders(manager: LocationManager): List<String> {
    return buildList {
        if (runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) {
            add(LocationManager.GPS_PROVIDER)
        }
        if (runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) {
            add(LocationManager.NETWORK_PROVIDER)
        }
        if (runCatching { manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER) }.getOrDefault(false)) {
            add(LocationManager.PASSIVE_PROVIDER)
        }
    }.ifEmpty { listOf(LocationManager.GPS_PROVIDER) }
}

private fun latestKnownLocation(manager: LocationManager, provider: String): Location? {
    return runCatching { manager.getLastKnownLocation(provider) }
        .getOrNull()
        ?.takeIf { System.currentTimeMillis() - it.time <= MAP_MAX_LAST_KNOWN_LOCATION_AGE_MS }
}
