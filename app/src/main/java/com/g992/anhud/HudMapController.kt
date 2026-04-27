package com.g992.anhud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.RectF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlin.math.abs
import kotlin.math.roundToInt

private const val MAP_ROUTE_SOURCE_ID = "anhud-map-route-source"
private const val MAP_ROUTE_LAYER_ID = "anhud-map-route-layer"
private const val MAP_ROUTE_COLOR_PROP = "routeColor"
private const val MAP_MAX_FPS = 15
private const val MAP_MAX_LAST_KNOWN_LOCATION_AGE_MS = 10_000L
private const val MAP_TRACKING_TOP_PADDING_RATIO = 0.97f
private const val HUD_MAP_TAG = "HudMapController"
private const val MAP_ROUTE_TRIM_BACKTRACK_METERS = 3.0
private const val MAP_ROUTE_SNAP_VIEWPORT_MARGIN_PX = 96f
private const val MAP_ROUTE_SNAP_MIN_DIRECTION_COS = 0.45
private const val MAP_LOCATION_SNAP_MIN_QUERY_RADIUS_PX = 28f
private const val MAP_LOCATION_SNAP_MAX_QUERY_RADIUS_PX = 96f
private const val MAP_LOCATION_SNAP_HYSTERESIS_METERS = 1.5
private const val MAP_LOCATION_SNAP_MIN_BEARING_SPEED_MPS = 1.0f
private const val MAP_ROUTE_SNAP_REUSE_WINDOW_MS = 5000L

private val MAP_ROAD_LAYER_IDS = arrayOf(
    "road_motorway",
    "road_trunk_primary",
    "road_secondary_tertiary",
    "road_minor",
    "road_motorway_link",
)

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
    private val renderHandler = Handler(Looper.getMainLooper())
    private var pendingTelemetryRender: Runnable? = null
    private var lastTelemetryRenderUptimeMs: Long = 0L
    private var currentSettings = MapRenderSettingsStore.current()
    private var currentSnapshot = MapRouteTelemetryStore.current()
    private var currentLocation: Location? = currentSnapshot.startLocation
    private var currentDisplayLocation: Location? = currentLocation
    private var currentSpeedBucketKmh: Int = initialSpeedBucket(NavigationHudStore.snapshot().speedKmh)
    private var lastRouteRenderDebugKey: String? = null
    private var lastRouteSnapshotDebugKey: String? = null
    private var routeSnapCache: RouteSnapCache? = null
    private var routeRoadQueryCache: RoadQueryCache? = null
    private var routeFeatureCache: RouteFeatureCache? = null
    private var locationSnapCache: LocationSnapCache? = null
    private var lastRouteSnapDebugKey: String? = null
    private var lastLocationRoadSegment: RoadLineSegment? = null

    private val settingsListener: (MapRenderSettings) -> Unit = { settings ->
        val snapSettingsChanged = currentSettings.snapRouteToRoadsEnabled != settings.snapRouteToRoadsEnabled ||
            currentSettings.snapLocationToRoadsEnabled != settings.snapLocationToRoadsEnabled ||
            currentSettings.routeSnapDistanceMeters != settings.routeSnapDistanceMeters
        currentSettings = settings
        applyLocationStyle()
        applyTrackingConfig()
        if (snapSettingsChanged) {
            invalidateRouteSnapCache()
            routeFeatureCache = null
            locationSnapCache = null
            if (!settings.snapLocationToRoadsEnabled) {
                lastLocationRoadSegment = null
            }
            applyTelemetrySnapshot()
        }
    }

    private val routeListener: (MapRouteTelemetrySnapshot) -> Unit = { snapshot ->
        logRouteSnapshot(snapshot)
        currentSnapshot = snapshot
        requestTelemetryRender()
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

    private fun logRouteSnapshot(snapshot: MapRouteTelemetrySnapshot) {
        val debugKey = "${snapshot.state}|${snapshot.routeToken.orEmpty()}|${snapshot.hasRoute}|" +
            "${snapshot.routePoints.size}|${snapshot.routeJams.size}"
        if (debugKey == lastRouteSnapshotDebugKey) return
        lastRouteSnapshotDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "route snapshot received: state=${snapshot.state} hasRoute=${snapshot.hasRoute} " +
                "points=${snapshot.routePoints.size} jams=${snapshot.routeJams.size} " +
                "routeToken=${snapshot.routeToken.orEmpty()}"
        )
    }

    private fun requestTelemetryRender() {
        if (released) return
        val now = SystemClock.uptimeMillis()
        val minIntervalMs = (1000L / MAP_MAX_FPS).coerceAtLeast(1L)
        val elapsed = now - lastTelemetryRenderUptimeMs
        if (elapsed >= minIntervalMs) {
            pendingTelemetryRender?.let(renderHandler::removeCallbacks)
            pendingTelemetryRender = null
            lastTelemetryRenderUptimeMs = now
            applyTelemetrySnapshot()
            return
        }
        if (pendingTelemetryRender != null) return
        val runnable = Runnable {
            pendingTelemetryRender = null
            lastTelemetryRenderUptimeMs = SystemClock.uptimeMillis()
            applyTelemetrySnapshot()
        }
        pendingTelemetryRender = runnable
        renderHandler.postDelayed(runnable, minIntervalMs - elapsed)
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
        pendingTelemetryRender?.let(renderHandler::removeCallbacks)
        pendingTelemetryRender = null
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
        updateDisplayLocation()
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
        lastTelemetryRenderUptimeMs = SystemClock.uptimeMillis()
        val map = mapLibreMap ?: run {
            Log.d(HUD_MAP_TAG, "route render skipped: map is not ready, points=${currentSnapshot.routePoints.size}")
            return
        }
        val style = map.style ?: run {
            Log.d(HUD_MAP_TAG, "route render skipped: style is not ready, points=${currentSnapshot.routePoints.size}")
            return
        }
        ensureRouteLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(MAP_ROUTE_SOURCE_ID) ?: run {
            Log.w(HUD_MAP_TAG, "route render skipped: source is missing")
            return
        }
        val rawPoints = currentSnapshot.routePoints
        val displayLocation = resolveDisplayLocation()?.let(::resolveLocationForRender)
        currentDisplayLocation = displayLocation
        val renderedRoute = resolveRoutePointsForRender(map, rawPoints, currentSnapshot.routeToken)
        val points = renderedRoute.points
        val renderCacheKey = routeRenderCacheKey(
            renderedRouteToken = renderedRoute.token,
            jamsToken = currentSnapshot.routeJamsToken,
            state = currentSnapshot.state,
            pointCount = rawPoints.size,
            displayLocation = displayLocation
        )
        val cachedFeatures = routeFeatureCache?.takeIf { it.key == renderCacheKey }
        displayLocation?.let { locationComponent?.forceLocationUpdate(it) }
        if (cachedFeatures != null) {
            logRouteRender(rawPoints.size, cachedFeatures.visibleSegmentCount)
        } else if (points.size >= 2) {
            val featureCollection = buildRouteFeatureCollection(points, currentSnapshot.routeJams, displayLocation)
            routeFeatureCache = RouteFeatureCache(
                key = renderCacheKey,
                featureCollection = featureCollection.collection,
                visibleSegmentCount = featureCollection.visibleSegmentCount
            )
            logRouteRender(rawPoints.size, featureCollection.visibleSegmentCount)
            source.setGeoJson(featureCollection.collection)
        } else {
            routeFeatureCache = RouteFeatureCache(
                key = renderCacheKey,
                featureCollection = FeatureCollection.fromFeatures(emptyArray()),
                visibleSegmentCount = 0
            )
            logRouteRender(rawPoints.size, 0)
            source.setGeoJson(routeFeatureCache?.featureCollection ?: FeatureCollection.fromFeatures(emptyArray()))
        }
    }

    private fun buildRouteFeatureCollection(
        points: List<LatLng>,
        jams: List<String>,
        displayLocation: Location?,
    ): RouteFeatureCollection {
        val visibleSegments = trimRouteSegments(points, jams, displayLocation)
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
        return RouteFeatureCollection(
            collection = FeatureCollection.fromFeatures(segmentFeatures),
            visibleSegmentCount = segmentFeatures.size
        )
    }

    private fun routeRenderCacheKey(
        renderedRouteToken: String,
        jamsToken: String?,
        state: String?,
        pointCount: Int,
        displayLocation: Location?,
    ): String {
        return buildString {
            append(renderedRouteToken)
            append('|')
            append(jamsToken.orEmpty())
            append('|')
            append(state.orEmpty())
            append('|')
            append(pointCount)
            append('|')
            append(displayLocation.renderKey())
        }
    }

    private fun logRouteRender(pointCount: Int, visibleSegmentCount: Int) {
        val debugKey = "${currentSnapshot.routeToken.orEmpty()}|${currentSnapshot.state}|$pointCount|$visibleSegmentCount"
        if (debugKey == lastRouteRenderDebugKey) return
        lastRouteRenderDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "route rendered: state=${currentSnapshot.state} points=$pointCount visibleSegments=$visibleSegmentCount " +
                "hasLocation=${currentLocation != null} hasDisplayLocation=${currentDisplayLocation != null}"
        )
    }

    private fun resolveRoutePointsForRender(
        map: MapLibreMap,
        points: List<LatLng>,
        routeToken: String?,
    ): RenderRoutePoints {
        val geometryToken = routeSnapGeometryKey(points)
        if (!currentSettings.snapRouteToRoadsEnabled || points.size < 2) {
            return RenderRoutePoints(points, "raw:${routeToken ?: geometryToken}")
        }
        val cacheKey = buildRouteSnapCacheKey(map, geometryToken) ?: return RenderRoutePoints(points, "raw:$geometryToken")
        routeSnapCache?.takeIf { it.key == cacheKey }?.let { return RenderRoutePoints(it.points, "snap:$cacheKey") }
        val now = SystemClock.uptimeMillis()
        routeSnapCache?.takeIf {
            now - it.computedAtMs <= MAP_ROUTE_SNAP_REUSE_WINDOW_MS && it.points.size == points.size
        }?.let {
            return RenderRoutePoints(it.points, "snap:${it.key}")
        }
        val roadQueryStartNs = SystemClock.elapsedRealtimeNanos()
        val roadSegments = queryRenderedRoadSegments(
            map = map,
            points = points,
            viewportWidth = mapView.width,
            viewportHeight = mapView.height,
            maxDistanceMeters = currentSettings.routeSnapDistanceMeters.toDouble()
        )
        val roadQueryDurationMs = elapsedSinceMs(roadQueryStartNs)
        if (roadSegments.isEmpty()) {
            logRouteSnap(
                pointCount = points.size,
                roadSegmentCount = 0,
                snappedCount = 0,
                consideredPointCount = 0,
                checkedSegmentCount = 0,
                maxCandidateCount = 0,
                roadQueryDurationMs = roadQueryDurationMs,
                snapDurationMs = 0.0
            )
            return RenderRoutePoints(points, "raw:$geometryToken")
        }
        val snapStartNs = SystemClock.elapsedRealtimeNanos()
        val snapResult = snapRoutePointsToRoads(
            map = map,
            points = points,
            roadSegments = roadSegments,
            maxDistanceMeters = currentSettings.routeSnapDistanceMeters.toDouble(),
            viewportWidth = mapView.width,
            viewportHeight = mapView.height
        )
        val snapDurationMs = elapsedSinceMs(snapStartNs)
        routeSnapCache = RouteSnapCache(cacheKey, snapResult.points, now)
        logRouteSnap(
            pointCount = points.size,
            roadSegmentCount = roadSegments.size,
            snappedCount = snapResult.snappedCount,
            consideredPointCount = snapResult.consideredPointCount,
            checkedSegmentCount = snapResult.checkedSegmentCount,
            maxCandidateCount = snapResult.maxCandidateCount,
            roadQueryDurationMs = roadQueryDurationMs,
            snapDurationMs = snapDurationMs
        )
        return RenderRoutePoints(snapResult.points, "snap:$cacheKey")
    }

    private fun buildRouteSnapCacheKey(map: MapLibreMap, routeToken: String): String? {
        if (mapView.width <= 0 || mapView.height <= 0) return null
        return buildString {
            append(routeToken)
            append('|')
            append(currentSettings.routeSnapDistanceMeters)
            append('|')
            append(mapView.width)
            append('x')
            append(mapView.height)
            append('|')
            append((map.cameraPosition.zoom * 100.0).roundToInt())
            append('|')
            append((map.cameraPosition.bearing * 10.0).roundToInt())
            append('|')
            append((map.cameraPosition.tilt * 10.0).roundToInt())
        }
    }

    private fun queryRenderedRoadSegments(
        map: MapLibreMap,
        points: List<LatLng>,
        viewportWidth: Int,
        viewportHeight: Int,
        maxDistanceMeters: Double,
    ): List<RoadLineSegment> {
        if (viewportWidth <= 0 || viewportHeight <= 0) return emptyList()
        val queryRect = buildRouteRoadQueryRect(
            map = map,
            points = points,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            maxDistanceMeters = maxDistanceMeters
        ) ?: return emptyList()
        val cacheKey = buildRoadQueryCacheKey(
            map = map,
            queryRect = queryRect,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            maxDistanceMeters = maxDistanceMeters
        )
        routeRoadQueryCache?.takeIf { it.key == cacheKey }?.let { return it.segments }
        val features = runCatching {
            map.queryRenderedFeatures(queryRect, *MAP_ROAD_LAYER_IDS)
        }.onFailure {
            Log.w(HUD_MAP_TAG, "route snap skipped: road query failed: ${it.message}")
        }.getOrDefault(emptyList())
        return deduplicateRoadSegments(features.flatMap(::roadSegmentsFromFeature)).also { segments ->
            routeRoadQueryCache = RoadQueryCache(cacheKey, segments)
        }
    }

    private fun logRouteSnap(
        pointCount: Int,
        roadSegmentCount: Int,
        snappedCount: Int,
        consideredPointCount: Int,
        checkedSegmentCount: Int,
        maxCandidateCount: Int,
        roadQueryDurationMs: Double,
        snapDurationMs: Double,
    ) {
        val debugKey = "${currentSnapshot.routeToken.orEmpty()}|${currentSettings.routeSnapDistanceMeters}|" +
            "$pointCount|$roadSegmentCount|$snappedCount|$consideredPointCount|$checkedSegmentCount|$maxCandidateCount"
        if (debugKey == lastRouteSnapDebugKey) return
        lastRouteSnapDebugKey = debugKey
        Log.d(
            HUD_MAP_TAG,
            "route snap: points=$pointCount roadSegments=$roadSegmentCount snapped=$snappedCount " +
                "considered=$consideredPointCount checked=$checkedSegmentCount maxCandidates=$maxCandidateCount " +
                "roadQueryMs=${"%.1f".format(roadQueryDurationMs)} snapMs=${"%.1f".format(snapDurationMs)} " +
                "maxDistance=${currentSettings.routeSnapDistanceMeters}m"
        )
    }

    private fun invalidateRouteSnapCache() {
        routeSnapCache = null
        routeRoadQueryCache = null
        routeFeatureCache = null
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
            requestTelemetryRender()
        }
        deviceLocationListener = listener
        preferredLocationProviders(manager).forEach { provider ->
            latestKnownLocation(manager, provider)?.let {
                currentLocation = it
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

    private fun updateDisplayLocation() {
        val rawLocation = resolveDisplayLocation() ?: return
        val displayLocation = resolveLocationForRender(rawLocation)
        currentDisplayLocation = displayLocation
        locationComponent?.forceLocationUpdate(displayLocation)
    }

    private fun resolveLocationForRender(rawLocation: Location): Location {
        if (!currentSettings.snapLocationToRoadsEnabled) {
            lastLocationRoadSegment = null
            locationSnapCache = null
            return rawLocation
        }
        val map = mapLibreMap ?: return rawLocation
        val cacheKey = buildLocationSnapCacheKey(map, rawLocation)
        locationSnapCache?.takeIf { it.key == cacheKey }?.let { return Location(it.location) }
        val point = LatLng(rawLocation.latitude, rawLocation.longitude)
        val roadSegments = queryRenderedRoadSegmentsNearLocation(map, point)
        if (roadSegments.isEmpty()) {
            lastLocationRoadSegment = null
            return rememberLocationSnap(cacheKey, rawLocation)
        }
        val snapped = findNearestRoadProjection(
            point = point,
            routeVector = locationDirectionMeters(rawLocation),
            roadSegments = roadSegments,
            maxDistanceMeters = currentSettings.routeSnapDistanceMeters.toDouble(),
            preferredSegment = lastLocationRoadSegment,
            preferredToleranceMeters = MAP_LOCATION_SNAP_HYSTERESIS_METERS
        ) ?: run {
            lastLocationRoadSegment = null
            return rememberLocationSnap(cacheKey, rawLocation)
        }
        lastLocationRoadSegment = snapped.roadSegment
        val displayLocation = Location(rawLocation).apply {
            latitude = snapped.projectedPoint.latitude
            longitude = snapped.projectedPoint.longitude
        }
        return rememberLocationSnap(cacheKey, displayLocation)
    }

    private fun rememberLocationSnap(key: String, location: Location): Location {
        val cached = Location(location)
        locationSnapCache = LocationSnapCache(key, cached)
        return Location(cached)
    }

    private fun buildLocationSnapCacheKey(map: MapLibreMap, location: Location): String {
        return buildString {
            append((location.latitude * 1_000_000.0).roundToInt())
            append('|')
            append((location.longitude * 1_000_000.0).roundToInt())
            append('|')
            append(if (location.hasBearing()) location.bearing.roundToInt() else -1)
            append('|')
            append(if (location.hasSpeed()) (location.speed * 10f).roundToInt() else -1)
            append('|')
            append(currentSettings.routeSnapDistanceMeters)
            append('|')
            append(mapView.width)
            append('x')
            append(mapView.height)
            append('|')
            append((map.cameraPosition.zoom * 100.0).roundToInt())
            append('|')
            append((map.cameraPosition.bearing * 10.0).roundToInt())
            append('|')
            append((map.cameraPosition.tilt * 10.0).roundToInt())
        }
    }

    private fun queryRenderedRoadSegmentsNearLocation(map: MapLibreMap, point: LatLng): List<RoadLineSegment> {
        if (mapView.width <= 0 || mapView.height <= 0) return emptyList()
        val screenPoint = runCatching { map.projection.toScreenLocation(point) }.getOrNull() ?: return emptyList()
        if (screenPoint.x.isNaN() || screenPoint.y.isNaN()) return emptyList()
        val metersPerPixel = runCatching {
            map.projection.getMetersPerPixelAtLatitude(point.latitude)
        }.getOrDefault(1.0).coerceAtLeast(0.05)
        val queryRadiusPx = ((currentSettings.routeSnapDistanceMeters / metersPerPixel).toFloat() + 16f)
            .coerceIn(MAP_LOCATION_SNAP_MIN_QUERY_RADIUS_PX, MAP_LOCATION_SNAP_MAX_QUERY_RADIUS_PX)
        val queryRect = RectF(
            screenPoint.x - queryRadiusPx,
            screenPoint.y - queryRadiusPx,
            screenPoint.x + queryRadiusPx,
            screenPoint.y + queryRadiusPx
        )
        val features = runCatching {
            map.queryRenderedFeatures(queryRect, *MAP_ROAD_LAYER_IDS)
        }.onFailure {
            Log.w(HUD_MAP_TAG, "location snap skipped: road query failed: ${it.message}")
        }.getOrDefault(emptyList())
        return deduplicateRoadSegments(features.flatMap(::roadSegmentsFromFeature))
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

private fun Location?.renderKey(): String {
    if (this == null) return "no-location"
    return "${(latitude * 100_000.0).roundToInt()}|${(longitude * 100_000.0).roundToInt()}"
}

private data class VisibleRouteSegment(
    val start: LatLng,
    val end: LatLng,
    val jam: String?,
)

private data class RouteSnapCache(
    val key: String,
    val points: List<LatLng>,
    val computedAtMs: Long,
)

private data class RoadQueryCache(
    val key: String,
    val segments: List<RoadLineSegment>,
)

private data class RouteFeatureCache(
    val key: String,
    val featureCollection: FeatureCollection,
    val visibleSegmentCount: Int,
)

private data class RouteFeatureCollection(
    val collection: FeatureCollection,
    val visibleSegmentCount: Int,
)

private data class RenderRoutePoints(
    val points: List<LatLng>,
    val token: String,
)

private data class LocationSnapCache(
    val key: String,
    val location: Location,
)

private data class RouteSnapResult(
    val points: List<LatLng>,
    val snappedCount: Int,
    val consideredPointCount: Int,
    val checkedSegmentCount: Int,
    val maxCandidateCount: Int,
)

private data class RoadLineSegment(
    val start: LatLng,
    val end: LatLng,
)

private data class RoadSegmentSpatialIndex(
    val segments: List<RoadLineSegment>,
    val cellLatSize: Double,
    val cellLonSize: Double,
    val cells: Map<Long, IntArray>,
)

private data class RoadSnapProjection(
    val projectedPoint: LatLng,
    val distanceMeters: Double,
    val roadSegment: RoadLineSegment,
)

private data class MeterVector(
    val x: Double,
    val y: Double,
    val length: Double,
)

private fun roadSegmentsFromFeature(feature: Feature): List<RoadLineSegment> {
    val geometry = feature.geometry() ?: return emptyList()
    return when (geometry) {
        is LineString -> roadSegmentsFromPoints(geometry.coordinates())
        is MultiLineString -> geometry.coordinates().flatMap(::roadSegmentsFromPoints)
        else -> emptyList()
    }
}

private fun roadSegmentsFromPoints(points: List<Point>): List<RoadLineSegment> {
    if (points.size < 2) return emptyList()
    return points.zipWithNext().mapNotNull { (start, end) ->
        val segment = RoadLineSegment(
            start = LatLng(start.latitude(), start.longitude()),
            end = LatLng(end.latitude(), end.longitude())
        )
        segment.takeIf { distanceMeters(it.start, it.end) >= 0.5 }
    }
}

private fun deduplicateRoadSegments(segments: List<RoadLineSegment>): List<RoadLineSegment> {
    if (segments.size < 2) return segments
    val result = ArrayList<RoadLineSegment>(segments.size)
    val seen = HashSet<String>(segments.size * 2)
    segments.forEach { segment ->
        if (seen.add(segment.dedupKey())) {
            result += segment
        }
    }
    return result
}

private fun buildRouteRoadQueryRect(
    map: MapLibreMap,
    points: List<LatLng>,
    viewportWidth: Int,
    viewportHeight: Int,
    maxDistanceMeters: Double,
): RectF? {
    if (points.size < 2) return null
    var minX = Float.POSITIVE_INFINITY
    var minY = Float.POSITIVE_INFINITY
    var maxX = Float.NEGATIVE_INFINITY
    var maxY = Float.NEGATIVE_INFINITY
    var visibleCount = 0
    var visibleLatSum = 0.0
    points.forEach { point ->
        if (!isPointInsideSnapViewport(map, point, viewportWidth = viewportWidth, viewportHeight = viewportHeight)) return@forEach
        val screenPoint = runCatching { map.projection.toScreenLocation(point) }.getOrNull() ?: return@forEach
        if (screenPoint.x.isNaN() || screenPoint.y.isNaN()) return@forEach
        minX = minOf(minX, screenPoint.x)
        minY = minOf(minY, screenPoint.y)
        maxX = maxOf(maxX, screenPoint.x)
        maxY = maxOf(maxY, screenPoint.y)
        visibleCount += 1
        visibleLatSum += point.latitude
    }
    if (visibleCount == 0) return null
    val averageLat = visibleLatSum / visibleCount.toDouble()
    val metersPerPixel = runCatching {
        map.projection.getMetersPerPixelAtLatitude(averageLat)
    }.getOrDefault(1.0).coerceAtLeast(0.05)
    val paddingPx = ((maxDistanceMeters / metersPerPixel).toFloat() + 24f).coerceIn(24f, 160f)
    return RectF(
        (minX - paddingPx).coerceAtLeast(0f),
        (minY - paddingPx).coerceAtLeast(0f),
        (maxX + paddingPx).coerceAtMost(viewportWidth.toFloat()),
        (maxY + paddingPx).coerceAtMost(viewportHeight.toFloat())
    )
}

private fun snapRoutePointsToRoads(
    map: MapLibreMap,
    points: List<LatLng>,
    roadSegments: List<RoadLineSegment>,
    maxDistanceMeters: Double,
    viewportWidth: Int,
    viewportHeight: Int,
): RouteSnapResult {
    if (points.size < 2 || roadSegments.isEmpty() || viewportWidth <= 0 || viewportHeight <= 0) {
        return RouteSnapResult(points, 0, 0, 0, 0)
    }
    val roadIndex = buildRoadSegmentSpatialIndex(roadSegments, maxDistanceMeters)
    var snappedCount = 0
    var consideredPointCount = 0
    var checkedSegmentCount = 0
    var maxCandidateCount = 0
    val snappedPoints = points.mapIndexed { index, point ->
        if (!isPointInsideSnapViewport(map, point, viewportWidth, viewportHeight)) {
            point
        } else {
            consideredPointCount += 1
            val routeVector = routeDirectionMeters(points, index)
            val candidateSegments = roadIndex.candidatesNear(point, maxDistanceMeters)
            checkedSegmentCount += candidateSegments.size
            if (candidateSegments.size > maxCandidateCount) {
                maxCandidateCount = candidateSegments.size
            }
            val snapped = findNearestRoadProjection(point, routeVector, candidateSegments, maxDistanceMeters)
            if (snapped != null) {
                if (snapped.distanceMeters > 0.05) snappedCount += 1
                snapped.projectedPoint
            } else {
                point
            }
        }
    }
    return RouteSnapResult(
        points = snappedPoints,
        snappedCount = snappedCount,
        consideredPointCount = consideredPointCount,
        checkedSegmentCount = checkedSegmentCount,
        maxCandidateCount = maxCandidateCount
    )
}

private fun isPointInsideSnapViewport(
    map: MapLibreMap,
    point: LatLng,
    viewportWidth: Int,
    viewportHeight: Int,
): Boolean {
    val screenPoint = runCatching { map.projection.toScreenLocation(point) }.getOrNull() ?: return false
    if (screenPoint.x.isNaN() || screenPoint.y.isNaN()) return false
    return screenPoint.x >= -MAP_ROUTE_SNAP_VIEWPORT_MARGIN_PX &&
        screenPoint.y >= -MAP_ROUTE_SNAP_VIEWPORT_MARGIN_PX &&
        screenPoint.x <= viewportWidth.toFloat() + MAP_ROUTE_SNAP_VIEWPORT_MARGIN_PX &&
        screenPoint.y <= viewportHeight.toFloat() + MAP_ROUTE_SNAP_VIEWPORT_MARGIN_PX
}

private fun findNearestRoadProjection(
    point: LatLng,
    routeVector: MeterVector?,
    roadSegments: List<RoadLineSegment>,
    maxDistanceMeters: Double,
    preferredSegment: RoadLineSegment? = null,
    preferredToleranceMeters: Double = 0.0,
): RoadSnapProjection? {
    var bestDirectional: RoadSnapProjection? = null
    var bestAny: RoadSnapProjection? = null
    val preferredProjection = preferredSegment
        ?.takeIf { isDirectionCompatible(routeVector, vectorMeters(it.start, it.end)) }
        ?.let { segment ->
            val projection = projectPointOntoSegment(point.latitude, point.longitude, segment.start, segment.end)
            if (projection.distanceMeters <= maxDistanceMeters) {
                RoadSnapProjection(projection.projectedPoint, projection.distanceMeters, segment)
            } else {
                null
            }
        }
    roadSegments.forEach { segment ->
        if (!isRoadSegmentNearPoint(segment, point, maxDistanceMeters)) return@forEach
        val projection = projectPointOntoSegment(point.latitude, point.longitude, segment.start, segment.end)
        if (projection.distanceMeters > maxDistanceMeters) return@forEach
        val candidate = RoadSnapProjection(projection.projectedPoint, projection.distanceMeters, segment)
        val currentBestAny = bestAny
        if (currentBestAny == null || candidate.distanceMeters < currentBestAny.distanceMeters) {
            bestAny = candidate
        }
        val roadVector = vectorMeters(segment.start, segment.end)
        val currentBestDirectional = bestDirectional
        if (isDirectionCompatible(routeVector, roadVector) &&
            (currentBestDirectional == null || candidate.distanceMeters < currentBestDirectional.distanceMeters)
        ) {
            bestDirectional = candidate
        }
    }
    val best = bestDirectional ?: bestAny
    if (preferredProjection != null && best != null) {
        if (best.distanceMeters >= preferredProjection.distanceMeters - preferredToleranceMeters) {
            return preferredProjection
        }
    }
    return preferredProjection ?: best
}

private fun buildRoadSegmentSpatialIndex(
    segments: List<RoadLineSegment>,
    maxDistanceMeters: Double,
): RoadSegmentSpatialIndex {
    if (segments.isEmpty()) {
        return RoadSegmentSpatialIndex(emptyList(), 1.0, 1.0, emptyMap())
    }
    val averageLatitude = segments.asSequence()
        .map { (it.start.latitude + it.end.latitude) / 2.0 }
        .average()
        .takeUnless { it.isNaN() }
        ?: 0.0
    val cellSizeMeters = (maxDistanceMeters.coerceAtLeast(6.0) * 2.0).coerceAtLeast(12.0)
    val cellLatSize = (cellSizeMeters / 111_320.0).coerceAtLeast(0.00001)
    val longitudeMetersScale = (111_320.0 * kotlin.math.cos(Math.toRadians(averageLatitude))).let(::abs).coerceAtLeast(1.0)
    val cellLonSize = (cellSizeMeters / longitudeMetersScale).coerceAtLeast(0.00001)
    val expandedLat = ((maxDistanceMeters + 1.0) / 111_320.0).coerceAtLeast(cellLatSize * 0.5)
    val expandedLon = (((maxDistanceMeters + 1.0) / longitudeMetersScale)).coerceAtLeast(cellLonSize * 0.5)
    val cells = HashMap<Long, MutableList<Int>>(segments.size * 2)
    segments.forEachIndexed { index, segment ->
        val minLat = minOf(segment.start.latitude, segment.end.latitude) - expandedLat
        val maxLat = maxOf(segment.start.latitude, segment.end.latitude) + expandedLat
        val minLon = minOf(segment.start.longitude, segment.end.longitude) - expandedLon
        val maxLon = maxOf(segment.start.longitude, segment.end.longitude) + expandedLon
        val rowStart = kotlin.math.floor(minLat / cellLatSize).toInt()
        val rowEnd = kotlin.math.floor(maxLat / cellLatSize).toInt()
        val colStart = kotlin.math.floor(minLon / cellLonSize).toInt()
        val colEnd = kotlin.math.floor(maxLon / cellLonSize).toInt()
        for (row in rowStart..rowEnd) {
            for (col in colStart..colEnd) {
                cells.getOrPut(cellKey(row, col)) { mutableListOf() }.add(index)
            }
        }
    }
    return RoadSegmentSpatialIndex(
        segments = segments,
        cellLatSize = cellLatSize,
        cellLonSize = cellLonSize,
        cells = cells.mapValues { (_, value) -> value.toIntArray() }
    )
}

private fun RoadSegmentSpatialIndex.candidatesNear(point: LatLng, maxDistanceMeters: Double): List<RoadLineSegment> {
    if (segments.isEmpty()) return emptyList()
    val queryLatRadius = ((maxDistanceMeters + 1.0) / 111_320.0).coerceAtLeast(cellLatSize)
    val longitudeMetersScale = (111_320.0 * kotlin.math.cos(Math.toRadians(point.latitude))).let(::abs).coerceAtLeast(1.0)
    val queryLonRadius = (((maxDistanceMeters + 1.0) / longitudeMetersScale)).coerceAtLeast(cellLonSize)
    val rowStart = kotlin.math.floor((point.latitude - queryLatRadius) / cellLatSize).toInt()
    val rowEnd = kotlin.math.floor((point.latitude + queryLatRadius) / cellLatSize).toInt()
    val colStart = kotlin.math.floor((point.longitude - queryLonRadius) / cellLonSize).toInt()
    val colEnd = kotlin.math.floor((point.longitude + queryLonRadius) / cellLonSize).toInt()
    val seen = HashSet<Int>()
    val result = ArrayList<RoadLineSegment>()
    for (row in rowStart..rowEnd) {
        for (col in colStart..colEnd) {
            cells[cellKey(row, col)]?.forEach { segmentIndex ->
                if (seen.add(segmentIndex)) {
                    result += segments[segmentIndex]
                }
            }
        }
    }
    return result
}

private fun cellKey(row: Int, col: Int): Long {
    return (row.toLong() shl 32) xor (col.toLong() and 0xffffffffL)
}

private fun isRoadSegmentNearPoint(
    segment: RoadLineSegment,
    point: LatLng,
    maxDistanceMeters: Double,
): Boolean {
    val expandedMeters = maxDistanceMeters + 1.0
    val latDelta = expandedMeters / 111_320.0
    val lonScale = 111_320.0 * kotlin.math.cos(Math.toRadians(point.latitude))
    val lonDelta = if (abs(lonScale) <= 0.0001) 180.0 else expandedMeters / abs(lonScale)
    val minLat = minOf(segment.start.latitude, segment.end.latitude) - latDelta
    val maxLat = maxOf(segment.start.latitude, segment.end.latitude) + latDelta
    val minLon = minOf(segment.start.longitude, segment.end.longitude) - lonDelta
    val maxLon = maxOf(segment.start.longitude, segment.end.longitude) + lonDelta
    return point.latitude in minLat..maxLat && point.longitude in minLon..maxLon
}

private fun RoadLineSegment.dedupKey(): String {
    val startLat = (start.latitude * 1_000_000.0).roundToInt()
    val startLon = (start.longitude * 1_000_000.0).roundToInt()
    val endLat = (end.latitude * 1_000_000.0).roundToInt()
    val endLon = (end.longitude * 1_000_000.0).roundToInt()
    val forward = "$startLat,$startLon:$endLat,$endLon"
    val reverse = "$endLat,$endLon:$startLat,$startLon"
    return if (forward <= reverse) forward else reverse
}

private fun routeDirectionMeters(points: List<LatLng>, index: Int): MeterVector? {
    val current = points.getOrNull(index) ?: return null
    val start = points.getOrNull(index - 1) ?: current
    val end = points.getOrNull(index + 1) ?: current
    return vectorMeters(start, end).takeIf { it.length >= 1.0 }
}

private fun locationDirectionMeters(location: Location): MeterVector? {
    if (!location.hasBearing()) return null
    if (location.hasSpeed() && location.speed < MAP_LOCATION_SNAP_MIN_BEARING_SPEED_MPS) return null
    val bearingRadians = Math.toRadians(location.bearing.toDouble())
    val x = kotlin.math.sin(bearingRadians)
    val y = kotlin.math.cos(bearingRadians)
    return MeterVector(x, y, 1.0)
}

private fun vectorMeters(start: LatLng, end: LatLng): MeterVector {
    val latitudeScale = 111_320.0
    val longitudeScale = 111_320.0 * kotlin.math.cos(Math.toRadians((start.latitude + end.latitude) / 2.0))
    val x = (end.longitude - start.longitude) * longitudeScale
    val y = (end.latitude - start.latitude) * latitudeScale
    val length = kotlin.math.hypot(x, y)
    return MeterVector(x, y, length)
}

private fun isDirectionCompatible(routeVector: MeterVector?, roadVector: MeterVector): Boolean {
    if (routeVector == null || roadVector.length < 1.0) return true
    val dot = ((routeVector.x * roadVector.x) + (routeVector.y * roadVector.y)) /
        (routeVector.length * roadVector.length)
    return abs(dot) >= MAP_ROUTE_SNAP_MIN_DIRECTION_COS
}

private fun routePointsKey(points: List<LatLng>): String {
    var hash = 1125899906842597L
    points.forEach { point ->
        hash = (hash * 31L) + (point.latitude * 1_000_000.0).roundToInt()
        hash = (hash * 31L) + (point.longitude * 1_000_000.0).roundToInt()
    }
    return "${points.size}|$hash"
}

private fun routeSnapGeometryKey(points: List<LatLng>): String {
    if (points.isEmpty()) return "0|0"
    var hash = 1125899906842597L
    val skipHeadPoints = (points.size / 10).coerceAtLeast(8).coerceAtMost((points.lastIndex).coerceAtLeast(0))
    val stableRange = if (skipHeadPoints < points.lastIndex) {
        points.subList(skipHeadPoints, points.size)
    } else {
        points
    }
    val sampleStep = (stableRange.size / 12).coerceAtLeast(1)
    stableRange.forEachIndexed { index, point ->
        if (index != 0 && index != stableRange.lastIndex && index % sampleStep != 0) return@forEachIndexed
        hash = (hash * 31L) + (point.latitude * 10_000.0).roundToInt()
        hash = (hash * 31L) + (point.longitude * 10_000.0).roundToInt()
    }
    return "${points.size}|$skipHeadPoints|$hash"
}

private fun buildRoadQueryCacheKey(
    map: MapLibreMap,
    queryRect: RectF,
    viewportWidth: Int,
    viewportHeight: Int,
    maxDistanceMeters: Double,
): String {
    return buildString {
        append(viewportWidth)
        append('x')
        append(viewportHeight)
        append('|')
        append(queryRect.left.roundToInt())
        append(',')
        append(queryRect.top.roundToInt())
        append(',')
        append(queryRect.right.roundToInt())
        append(',')
        append(queryRect.bottom.roundToInt())
        append('|')
        append((maxDistanceMeters * 10.0).roundToInt())
        append('|')
        append((map.cameraPosition.zoom * 20.0).roundToInt())
        append('|')
        append((map.cameraPosition.bearing * 5.0).roundToInt())
        append('|')
        append((map.cameraPosition.tilt * 5.0).roundToInt())
    }
}

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
    val trimStart = backtrackRouteProjection(points, closest, MAP_ROUTE_TRIM_BACKTRACK_METERS)
    val result = mutableListOf<VisibleRouteSegment>()
    val projectedStart = trimStart.projectedPoint
    val firstEnd = points[trimStart.segmentIndex + 1]
    if (distanceMeters(projectedStart, firstEnd) > 1.0) {
        result += VisibleRouteSegment(projectedStart, firstEnd, jams.getOrNull(trimStart.segmentIndex))
    }
    for (index in (trimStart.segmentIndex + 1) until points.lastIndex) {
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

private fun backtrackRouteProjection(
    points: List<LatLng>,
    projection: RouteProjection,
    backtrackMeters: Double,
): RouteProjection {
    if (backtrackMeters <= 0.0 || projection.segmentIndex !in 0 until points.lastIndex) {
        return projection
    }
    var remaining = backtrackMeters
    val currentSegmentStart = points[projection.segmentIndex]
    val distanceFromSegmentStart = distanceMeters(currentSegmentStart, projection.projectedPoint)
    if (distanceFromSegmentStart >= remaining) {
        return projection.copy(
            projectedPoint = interpolateLatLng(
                start = projection.projectedPoint,
                end = currentSegmentStart,
                fraction = remaining / distanceFromSegmentStart
            )
        )
    }

    remaining -= distanceFromSegmentStart
    var segmentIndex = projection.segmentIndex - 1
    while (segmentIndex >= 0) {
        val segmentStart = points[segmentIndex]
        val segmentEnd = points[segmentIndex + 1]
        val segmentLength = distanceMeters(segmentStart, segmentEnd)
        if (segmentLength >= remaining) {
            return projection.copy(
                segmentIndex = segmentIndex,
                projectedPoint = interpolateLatLng(
                    start = segmentEnd,
                    end = segmentStart,
                    fraction = remaining / segmentLength
                )
            )
        }
        remaining -= segmentLength
        segmentIndex -= 1
    }
    return projection.copy(segmentIndex = 0, projectedPoint = points.first())
}

private fun interpolateLatLng(start: LatLng, end: LatLng, fraction: Double): LatLng {
    val safeFraction = fraction.coerceIn(0.0, 1.0)
    return LatLng(
        start.latitude + ((end.latitude - start.latitude) * safeFraction),
        start.longitude + ((end.longitude - start.longitude) * safeFraction)
    )
}

private fun elapsedSinceMs(startNs: Long): Double =
    (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000.0

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
