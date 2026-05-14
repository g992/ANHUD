package com.g992.anhud

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val ROUTE_PREFS_NAME = "map_route_telemetry"
private const val ROUTE_TELEMETRY_TAG = "MapRouteTelemetry"
private const val PREF_STATE = "state"
private const val PREF_START = "start"
private const val PREF_END = "end"
private const val PREF_SAMPLED = "sampled"
private const val PREF_ROUTE_ID = "route_id"
private const val PREF_PRE_MANEUVER = "pre_maneuver"
private const val PREF_WAYPOINTS = "waypoints"
private const val PREF_JAMS = "jams"
private const val PREF_ROUTE_ALERTS = "route_alerts"
private const val PREF_ROUTE_ALERTS_ROUTE_ID = "route_alerts_route_id"
private const val PREF_ROUTE_LANE_POINTS = "route_lane_points"
private const val EXTERNAL_ROUTE_SPEED_MPS = 10.0
private const val LANE_GUIDANCE_DISPLAY_MAX_DISTANCE_METERS = 1_000.0
private const val LANE_GUIDANCE_PASS_NEAR_DISTANCE_METERS = 60.0
private const val LANE_GUIDANCE_PASS_DISTANCE_INCREASE_METERS = 10.0
private const val LANE_GUIDANCE_PASS_SAMPLE_INCREASE_METERS = 5.0
private const val LANE_GUIDANCE_REVERSE_DISTANCE_INCREASE_METERS = 25.0
private const val LANE_GUIDANCE_CANDIDATE_MAX = 6
const val ROUTE_TELEMETRY_LOCATION_PROVIDER = "route-telemetry"

data class MapRouteTelemetrySnapshot(
    val state: String? = null,
    val hasExternalTelemetry: Boolean = false,
    val routeToken: String? = null,
    val routeJamsToken: String? = null,
    val routePoints: List<LatLng> = emptyList(),
    val routeJams: List<String> = emptyList(),
    val routeAlerts: List<MapRouteAlert> = emptyList(),
    val routeAlertsToken: String? = null,
    val laneManeuver: MapLaneManeuver? = null,
    val startLocation: Location? = null,
) {
    val hasRoute: Boolean
        get() = routePoints.size >= 2

    val routeBuilt: Boolean
        get() = hasRoute || state == MAP_ROUTE_STATE_BUILT
}

data class MapRouteAlert(
    val type: String,
    val point: LatLng,
)

data class MapLaneManeuver(
    val bitmap: Bitmap,
    val point: LatLng,
    val iconAnchor: PointF,
    val token: Int,
    val distanceMeters: Int,
)

private data class ResolvedLaneBitmapAnchor(
    val anchor: PointF,
    val source: String,
)

private data class MapLanePoint(
    val routeId: String,
    val segmentIndex: Int,
    val point: LatLng,
    val laneSig: String,
)

private data class LaneGuidanceCandidate(
    val routeId: String,
    val segmentIndex: Int,
    val laneSig: String,
    val bitmap: Bitmap,
    val explicitPoint: LatLng?,
    val iconAnchor: PointF,
    val token: Int,
    val receivedAtUptimeMs: Long,
    val firstDistanceMeters: Double? = null,
    val minDistanceMeters: Double? = null,
    val lastDistanceMeters: Double? = null,
)

private data class ResolvedLaneGuidanceCandidate(
    val candidate: LaneGuidanceCandidate,
    val point: LatLng,
    val directDistanceMeters: Double,
)

private data class RuntimeRoute(
    val routeId: String?,
    val points: List<LatLng>,
    val routeToken: String,
    val jamsRaw: String?,
    val lanePointsRaw: String?,
)

private data class NormalizedRouteJams(
    val values: List<String>,
    val rawTypes: List<String>,
    val unsupportedTypes: List<String>,
) {
    val containsUnknown: Boolean
        get() = rawTypes.any { it == "UNKNOWN" }
}

class MapRouteTelemetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MapRouteTelemetryStore.handleIntent(context.applicationContext, intent)
    }
}

object MapRouteTelemetryStore {
    private val listeners = CopyOnWriteArraySet<(MapRouteTelemetrySnapshot) -> Unit>()

    @Volatile
    private var initialized = false
    @Volatile
    private var currentSnapshot = MapRouteTelemetrySnapshot()
    private var parsedSampledRouteCache: ParsedSampledRoute? = null
    private var lastSnapshotLogKey: String? = null
    private var lastRouteJamsDebugKey: String? = null
    private var laneManeuverToken: Int = 0
    private var appContext: Context? = null
    private var routeLocation: Location? = null
    private var routeLocationListener: LocationListener? = null
    private var runtimeState: String? = null
    private var runtimeRoute: RuntimeRoute? = null
    private var runtimeRouteAlertsRaw: String? = null
    private var runtimeRouteAlertsRouteId: String? = null
    private val laneGuidanceCandidates = linkedMapOf<String, LaneGuidanceCandidate>()

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            ensureRouteLocationTracking(context.applicationContext)
            clearLegacyPersistedRouteTelemetry(context.applicationContext)
            currentSnapshot = buildSnapshot()
            initialized = true
        }
    }

    fun current(): MapRouteTelemetrySnapshot = currentSnapshot

    fun addListener(listener: (MapRouteTelemetrySnapshot) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (MapRouteTelemetrySnapshot) -> Unit) {
        listeners -= listener
    }

    fun handleIntent(context: Context, intent: Intent) {
        initialize(context)
        ensureRouteLocationTracking(context.applicationContext)
        clearLegacyPersistedRouteTelemetry(context.applicationContext)
        when (intent.action) {
            MAP_ROUTE_TELEMETRY_ACTION -> {
                val sampled = intent.getStringExtra(MAP_EXTRA_ROUTE_SAMPLED)
                val jams = intent.getStringExtra(MAP_EXTRA_ROUTE_JAMS)
                val routeId = intent.getStringExtra(MAP_EXTRA_ROUTE_ID)
                val lanePointsRaw = intent.getStringExtra(MAP_EXTRA_ROUTE_LANE_POINTS)
                val parsedSampled = parseSampledRoute(sampled, routeId)
                val points = parsedSampled?.points?.takeIf { it.size >= 2 } ?: listOfNotNull(
                    parsePoint(intent.getStringExtra(MAP_EXTRA_ROUTE_START)),
                    parsePoint(intent.getStringExtra(MAP_EXTRA_ROUTE_END))
                )
                if (points.size >= 2) {
                    val routeToken = parsedSampled?.token ?: points.routePointsToken(routeId)
                    if (runtimeRoute?.routeToken != routeToken) {
                        runtimeRouteAlertsRaw = null
                        runtimeRouteAlertsRouteId = null
                        laneGuidanceCandidates.clear()
                    }
                    runtimeState = MAP_ROUTE_STATE_BUILT
                    runtimeRoute = RuntimeRoute(
                        routeId = routeId,
                        points = points,
                        routeToken = routeToken,
                        jamsRaw = jams,
                        lanePointsRaw = lanePointsRaw
                    )
                } else {
                    if (!jams.isNullOrBlank()) {
                        Log.d(
                            ROUTE_TELEMETRY_TAG,
                            "route telemetry intent: jams-only update routeId=${routeId.orEmpty()} " +
                                "currentRouteToken=${runtimeRoute?.routeToken.orEmpty()} " +
                                "jamsToken=${jams.routeRawToken().orEmpty()}"
                        )
                    }
                    runtimeRoute?.let { route ->
                        runtimeRoute = route.copy(
                            jamsRaw = jams ?: route.jamsRaw,
                            lanePointsRaw = lanePointsRaw ?: route.lanePointsRaw
                        )
                    }
                }
            }

            MAP_ROUTE_STATE_ACTION -> {
                val state = intent.getStringExtra(MAP_EXTRA_ROUTE_STATE)
                Log.d(ROUTE_TELEMETRY_TAG, "route state: $state")
                runtimeState = state
                if (state == MAP_ROUTE_STATE_ARRIVED || state == MAP_ROUTE_STATE_CANCELLED) {
                    clearRuntimeRoute()
                }
            }

            MAP_ROUTE_ALERTS_ACTION,
            MAP_ROUTE_ALERTS_ALT_ACTION -> {
                runtimeRouteAlertsRaw = intent.getStringExtra(MAP_EXTRA_ROUTE_ALERTS)
                runtimeRouteAlertsRouteId = intent.getStringExtra(MAP_EXTRA_ROUTE_ID)
            }

            MAP_MANEUVER_BLOCK_ACTION,
            MAP_MANEUVER_BLOCK_ALT_ACTION -> {
                handleManeuverBlockIntent(context, intent)
                publishSnapshotIfChanged(context, buildSnapshot())
                return
            }
        }
        publishSnapshotIfChanged(context, buildSnapshot())
    }

    private fun handleManeuverBlockIntent(context: Context, intent: Intent) {
        val sourceType = intent.getStringExtra(MAP_EXTRA_MANEUVER_BLOCK_SOURCE_TYPE)
        if (sourceType != MAP_MANEUVER_SOURCE_LANE_AND_MANEUVER) {
            return
        }
        val bitmap = readBitmapExtra(intent, MAP_EXTRA_MANEUVER_BLOCK_BITMAP)
        if (bitmap == null || bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            val cleared = laneGuidanceCandidates.isNotEmpty()
            laneGuidanceCandidates.clear()
            Log.d(
                ROUTE_TELEMETRY_TAG,
                "lane maneuver clear: invalid bitmap cleared=$cleared " +
                    "action=${intent.action.orEmpty()} " +
                    "extras=${intent.extras?.keySet()?.joinToString(prefix = "[", postfix = "]") ?: "-"}"
            )
            return
        }
        val routeId = intent.getStringExtra(MAP_EXTRA_LANE_ROUTE_ID).orEmpty().trim()
        val laneSig = intent.getStringExtra(MAP_EXTRA_LANE_SIG).orEmpty().trim()
        val laneSeg = intent.getIntExtra(MAP_EXTRA_LANE_SEG, Int.MIN_VALUE)
        val explicitPoint = resolveLanePositionFromIntent(intent)
        if (explicitPoint == null && (laneSeg < 0 || laneSig.isBlank())) {
            Log.d(
                ROUTE_TELEMETRY_TAG,
                "lane maneuver ignored: no position routeId=${routeId.ifBlank { "-" }} " +
                    "laneSig=${laneSig.ifBlank { "-" }} laneSeg=$laneSeg"
            )
            return
        }
        laneManeuverToken += 1
        val iconAnchor = resolveLaneBitmapAnchor(intent, bitmap)
        val key = buildLaneGuidanceKey(routeId, laneSeg, laneSig, explicitPoint)
        val previous = laneGuidanceCandidates[key]
        val candidate = LaneGuidanceCandidate(
            routeId = routeId,
            segmentIndex = laneSeg,
            laneSig = laneSig,
            bitmap = bitmap,
            explicitPoint = explicitPoint,
            iconAnchor = iconAnchor.anchor,
            token = laneManeuverToken,
            receivedAtUptimeMs = SystemClock.uptimeMillis(),
            firstDistanceMeters = previous?.firstDistanceMeters,
            minDistanceMeters = previous?.minDistanceMeters,
            lastDistanceMeters = previous?.lastDistanceMeters
        )
        laneGuidanceCandidates[key] = candidate
        pruneLaneGuidanceCandidates()
        Log.d(
            ROUTE_TELEMETRY_TAG,
            "lane maneuver anchor: source=${iconAnchor.source} x=${iconAnchor.anchor.x.format(3)} " +
                "y=${iconAnchor.anchor.y.format(3)} " +
                "bitmap=${bitmap.width}x${bitmap.height} routeId=${routeId.ifBlank { "-" }} " +
                "laneSig=${laneSig.ifBlank { "-" }} laneSeg=$laneSeg " +
                "explicitPoint=${explicitPoint?.let { "${it.latitude},${it.longitude}" } ?: "-"} " +
                "intentAnchorValid=${intent.getBooleanExtra(MAP_EXTRA_LANE_BITMAP_ANCHOR_VALID, false)} " +
                "intentAnchorX=${intent.getFloatExtra(MAP_EXTRA_LANE_BITMAP_ANCHOR_X, Float.NaN)} " +
                "intentAnchorY=${intent.getFloatExtra(MAP_EXTRA_LANE_BITMAP_ANCHOR_Y, Float.NaN)}"
        )
    }

    private fun readBitmapExtra(intent: Intent, key: String): Bitmap? {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, Bitmap::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(key) as? Bitmap
            }
        }.getOrNull()
    }

    private fun resolveLanePositionFromIntent(intent: Intent): LatLng? {
        if (!intent.getBooleanExtra(MAP_EXTRA_LANE_POS_VALID, false)) return null
        val lat = intent.getFloatExtra(MAP_EXTRA_LANE_POS_LAT, Float.NaN)
        val lon = intent.getFloatExtra(MAP_EXTRA_LANE_POS_LON, Float.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return null
        return LatLng(lat.toDouble(), lon.toDouble())
    }

    private fun resolveLaneBitmapAnchor(intent: Intent, bitmap: Bitmap): ResolvedLaneBitmapAnchor {
        detectLaneBitmapAnchor(bitmap)?.let { return it }
        val fallbackAnchor = if (bitmap.width >= (bitmap.height * 1.3f)) {
            PointF(0.12f, 0.16f)
        } else {
            PointF(0.12f, 0.88f)
        }
        return ResolvedLaneBitmapAnchor(
            anchor = fallbackAnchor,
            source = "fallback_corner"
        )
    }

    private fun detectLaneBitmapAnchor(bitmap: Bitmap): ResolvedLaneBitmapAnchor? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 2 || height <= 2) return null

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val alphaThreshold = 12
        val left = IntArray(height) { width }
        val right = IntArray(height) { -1 }
        val bottom = IntArray(width) { -1 }

        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                val alpha = pixels[rowOffset + x] ushr 24
                if (alpha <= alphaThreshold) continue
                if (y > bottom[x]) bottom[x] = y
                if (x < left[y]) left[y] = x
                if (x > right[y]) right[y] = x
            }
        }

        val validLeft = left.filter { it < width }
        val validRight = right.filter { it >= 0 }
        val validBottom = bottom.filter { it >= 0 }
        if (validLeft.isEmpty() || validRight.isEmpty() || validBottom.isEmpty()) {
            return null
        }

        val sideThresholdPx = maxOf(6f, width * 0.03f)
        val bottomThresholdPx = maxOf(6f, height * 0.05f)
        val leftBody = median(validLeft)
        val rightBody = median(validRight)
        val bottomBody = median(validBottom)

        detectHorizontalEdgeCluster(
            extents = left,
            bodyEdge = leftBody,
            side = HorizontalSide.LEFT,
            thresholdPx = sideThresholdPx
        )?.let { return it.toResolvedAnchor(width, height) }

        detectHorizontalEdgeCluster(
            extents = right,
            bodyEdge = rightBody,
            side = HorizontalSide.RIGHT,
            thresholdPx = sideThresholdPx
        )?.let { return it.toResolvedAnchor(width, height) }

        detectBottomEdgeCluster(
            extents = bottom,
            bodyEdge = bottomBody,
            thresholdPx = bottomThresholdPx
        )?.let { return it.toResolvedAnchor(width, height) }

        return null
    }

    private fun detectHorizontalEdgeCluster(
        extents: IntArray,
        bodyEdge: Float,
        side: HorizontalSide,
        thresholdPx: Float,
    ): DetectedAnchor? {
        val ranges = mutableListOf<IntRange>()
        var rangeStart = -1
        for (index in extents.indices) {
            val extent = extents[index]
            val protrusion = when {
                extent < 0 -> 0f
                side == HorizontalSide.LEFT -> bodyEdge - extent
                else -> extent - bodyEdge
            }
            if (protrusion >= thresholdPx) {
                if (rangeStart < 0) rangeStart = index
            } else if (rangeStart >= 0) {
                ranges += rangeStart until index
                rangeStart = -1
            }
        }
        if (rangeStart >= 0) {
            ranges += rangeStart until extents.size
        }
        val bestRange = ranges
            .filter { !it.isEmpty() && (it.last - it.first + 1) <= 48 }
            .maxByOrNull { range ->
                val peak = range.maxOf { rowIndex ->
                    if (side == HorizontalSide.LEFT) bodyEdge - extents[rowIndex] else extents[rowIndex] - bodyEdge
                }
                peak * (range.last - range.first + 1)
            } ?: return null

        val extreme = when (side) {
            HorizontalSide.LEFT -> bestRange.minOf { extents[it] }
            HorizontalSide.RIGHT -> bestRange.maxOf { extents[it] }
        }
        val rows = bestRange.filter { rowIndex ->
            when (side) {
                HorizontalSide.LEFT -> extents[rowIndex] <= extreme + 1
                HorizontalSide.RIGHT -> extents[rowIndex] >= extreme - 1
            }
        }
        if (rows.isEmpty()) return null
        val y = rows.average().toFloat()
        val x = extreme.toFloat()
        val source = if (side == HorizontalSide.LEFT) "detected_left" else "detected_right"
        return DetectedAnchor(x = x, y = y, score = 1f, source = source)
    }

    private fun detectBottomEdgeCluster(
        extents: IntArray,
        bodyEdge: Float,
        thresholdPx: Float,
    ): DetectedAnchor? {
        val ranges = mutableListOf<IntRange>()
        var rangeStart = -1
        for (index in extents.indices) {
            val extent = extents[index]
            val protrusion = if (extent < 0) 0f else extent - bodyEdge
            if (protrusion >= thresholdPx) {
                if (rangeStart < 0) rangeStart = index
            } else if (rangeStart >= 0) {
                ranges += rangeStart until index
                rangeStart = -1
            }
        }
        if (rangeStart >= 0) {
            ranges += rangeStart until extents.size
        }
        val bestRange = ranges
            .filter { !it.isEmpty() && (it.last - it.first + 1) <= 64 }
            .maxByOrNull { range ->
                val peak = range.maxOf { columnIndex -> extents[columnIndex] - bodyEdge }
                peak * (range.last - range.first + 1)
            } ?: return null

        val extreme = bestRange.maxOf { extents[it] }
        val columns = bestRange.filter { extents[it] >= extreme - 1 }
        if (columns.isEmpty()) return null
        return DetectedAnchor(
            x = columns.average().toFloat(),
            y = extreme.toFloat(),
            score = 1f,
            source = "detected_bottom"
        )
    }

    private fun DetectedAnchor.toResolvedAnchor(width: Int, height: Int): ResolvedLaneBitmapAnchor {
        return ResolvedLaneBitmapAnchor(
            anchor = PointF(
                (x / (width - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f),
                (y / (height - 1).coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
            ),
            source = source
        )
    }

    private fun median(values: List<Int>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[middle - 1] + sorted[middle]) / 2f
        } else {
            sorted[middle].toFloat()
        }
    }

    private data class DetectedAnchor(
        val x: Float,
        val y: Float,
        val score: Float,
        val source: String,
    )

    private fun IntRange.isEmpty(): Boolean = first > last

    private enum class HorizontalSide {
        LEFT,
        RIGHT,
    }

    private fun Float.format(decimals: Int): String = "%.${decimals}f".format(this)

    private fun ensureRouteLocationTracking(context: Context) {
        if (routeLocationListener != null || !hasLocationPermission(context)) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        val listener = LocationListener { location ->
            routeLocation = Location(location)
            publishSnapshotIfChanged(context, buildSnapshot())
        }
        routeLocationListener = listener
        preferredLocationProviders(manager).forEach { provider ->
            latestKnownLocation(manager, provider)?.let { known ->
                routeLocation = Location(known)
            }
            runCatching {
                manager.requestLocationUpdates(provider, 1000L, 3f, listener, Looper.getMainLooper())
            }.onFailure {
                Log.w(ROUTE_TELEMETRY_TAG, "route location subscribe failed for $provider: ${it.message}")
            }
        }
    }

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun pruneLaneGuidanceCandidates() {
        val iterator = laneGuidanceCandidates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val candidate = entry.value
            val invalidBitmap = candidate.bitmap.isRecycled || candidate.bitmap.width <= 0 || candidate.bitmap.height <= 0
            if (invalidBitmap) {
                iterator.remove()
            }
        }
        while (laneGuidanceCandidates.size > LANE_GUIDANCE_CANDIDATE_MAX) {
            val oldestKey = laneGuidanceCandidates.entries.minByOrNull { it.value.receivedAtUptimeMs }?.key ?: break
            laneGuidanceCandidates.remove(oldestKey)
        }
    }

    private fun resolveActiveLaneGuidance(
        lanePointsRaw: String?,
        routeId: String?,
    ): MapLaneManeuver? {
        if (laneGuidanceCandidates.isEmpty()) return null
        val location = routeLocation
        val currentAnchor = location?.let { LatLng(it.latitude, it.longitude) } ?: return null
        pruneLaneGuidanceCandidates()

        val resolvedCandidates = mutableListOf<ResolvedLaneGuidanceCandidate>()
        val iterator = laneGuidanceCandidates.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val candidate = entry.value
            val point = candidate.explicitPoint
                ?: findLanePoint(
                    raw = lanePointsRaw,
                    routeId = candidate.routeId.ifBlank { routeId.orEmpty() },
                    segmentIndex = candidate.segmentIndex,
                    laneSig = candidate.laneSig
                )
                ?: continue
            val directDistance = distanceMetersBetween(currentAnchor, point)
            val updatedCandidate = updateLaneGuidanceCandidateDistance(entry.key, candidate, directDistance)
            if (updatedCandidate == null) {
                iterator.remove()
                continue
            }
            entry.setValue(updatedCandidate)
            if (directDistance >= LANE_GUIDANCE_DISPLAY_MAX_DISTANCE_METERS) {
                continue
            }
            resolvedCandidates += ResolvedLaneGuidanceCandidate(
                candidate = updatedCandidate,
                point = point,
                directDistanceMeters = directDistance
            )
        }
        if (resolvedCandidates.isEmpty()) return null

        var selected: ResolvedLaneGuidanceCandidate? = null
        var bestDistance = Double.MAX_VALUE
        var bestSegment = Int.MAX_VALUE
        var bestToken = Int.MIN_VALUE
        for (candidate in resolvedCandidates) {
            val distance = candidate.directDistanceMeters
            val segment = candidate.candidate.segmentIndex
            val token = candidate.candidate.token
            val better = distance < bestDistance ||
                (distance == bestDistance && segment < bestSegment) ||
                (distance == bestDistance && segment == bestSegment && token > bestToken)
            if (better) {
                selected = candidate
                bestDistance = distance
                bestSegment = segment
                bestToken = token
            }
        }
        selected ?: return null

        return MapLaneManeuver(
            bitmap = selected.candidate.bitmap,
            point = selected.point,
            iconAnchor = selected.candidate.iconAnchor,
            token = selected.candidate.token,
            distanceMeters = selected.directDistanceMeters.toInt().coerceAtLeast(0)
        )
    }

    private fun updateLaneGuidanceCandidateDistance(
        key: String,
        candidate: LaneGuidanceCandidate,
        directDistanceMeters: Double,
    ): LaneGuidanceCandidate? {
        val firstDistance = candidate.firstDistanceMeters ?: directDistanceMeters
        val previousMinDistance = candidate.minDistanceMeters
        val minDistance = previousMinDistance?.let { minOf(it, directDistanceMeters) } ?: directDistanceMeters
        val movedAwayFromFirst =
            directDistanceMeters >= firstDistance + LANE_GUIDANCE_REVERSE_DISTANCE_INCREASE_METERS
        val lastDistance = candidate.lastDistanceMeters
        val movedAwayFromMinimum =
            minDistance <= LANE_GUIDANCE_PASS_NEAR_DISTANCE_METERS &&
                directDistanceMeters >= minDistance + LANE_GUIDANCE_PASS_DISTANCE_INCREASE_METERS
        val movedAwayFromLastSample =
            lastDistance != null &&
                directDistanceMeters >= lastDistance + LANE_GUIDANCE_PASS_SAMPLE_INCREASE_METERS
        if (movedAwayFromFirst || (movedAwayFromMinimum && movedAwayFromLastSample)) {
            Log.d(
                ROUTE_TELEMETRY_TAG,
                "lane maneuver passed: key=$key first=${firstDistance.roundToInt()}m " +
                    "min=${minDistance.roundToInt()}m distance=${directDistanceMeters.roundToInt()}m " +
                    "reason=${if (movedAwayFromFirst) "reverse" else "passed"}"
            )
            return null
        }
        return candidate.copy(
            firstDistanceMeters = firstDistance,
            minDistanceMeters = minDistance,
            lastDistanceMeters = directDistanceMeters
        )
    }

    private fun buildLaneGuidanceKey(
        routeId: String,
        segmentIndex: Int,
        laneSig: String,
        explicitPoint: LatLng?,
    ): String {
        if (segmentIndex < 0 || laneSig.isBlank()) {
            return explicitPoint?.let { point ->
                "point|" +
                    (point.latitude * 100_000.0).roundToInt() +
                    "|" +
                    (point.longitude * 100_000.0).roundToInt()
            } ?: "unknown"
        }
        return routeId.trim() + "|" + segmentIndex + "|" + laneSig.trim()
    }

    private fun publishSnapshotIfChanged(context: Context, snapshot: MapRouteTelemetrySnapshot) {
        if (snapshot.sameRouteContentAs(currentSnapshot)) {
            currentSnapshot = snapshot
            return
        }
        currentSnapshot = snapshot
        logSnapshot(currentSnapshot)
        listeners.forEach { it(currentSnapshot) }
        notifyRouteStatusChanged(context, currentSnapshot)
    }

    private fun logSnapshot(snapshot: MapRouteTelemetrySnapshot) {
        val debugKey = "${snapshot.state}|${snapshot.routeToken.orEmpty()}|${snapshot.hasRoute}|" +
            "${snapshot.routePoints.size}|${snapshot.routeJams.size}"
        if (debugKey == lastSnapshotLogKey) return
        lastSnapshotLogKey = debugKey
        Log.d(
            ROUTE_TELEMETRY_TAG,
            "snapshot: state=${snapshot.state} hasRoute=${snapshot.hasRoute} " +
                "built=${snapshot.routeBuilt} points=${snapshot.routePoints.size} " +
                "routeToken=${snapshot.routeToken.orEmpty()}"
        )
    }

    fun replaceRoutePolyline(context: Context, routeId: String?, points: List<LatLng>) {
        if (points.size < 2) return
        initialize(context)
        val routeToken = points.routePointsToken(routeId)
        if (currentSnapshot.state == MAP_ROUTE_STATE_BUILT &&
            currentSnapshot.routeToken == routeToken &&
            currentSnapshot.routeJams.isEmpty()
        ) {
            return
        }
        clearLegacyPersistedRouteTelemetry(context.applicationContext)
        runtimeState = MAP_ROUTE_STATE_BUILT
        runtimeRoute = RuntimeRoute(
            routeId = routeId,
            points = points,
            routeToken = routeToken,
            jamsRaw = null,
            lanePointsRaw = null
        )
        runtimeRouteAlertsRaw = null
        runtimeRouteAlertsRouteId = null
        laneGuidanceCandidates.clear()
        publishSnapshotIfChanged(context, buildSnapshot())
        Log.d(ROUTE_TELEMETRY_TAG, "route polyline received: id=${routeId.orEmpty()} points=${points.size}")
    }

    fun clearRoutePolyline(context: Context) {
        initialize(context)
        clearLegacyPersistedRouteTelemetry(context.applicationContext)
        runtimeState = MAP_ROUTE_STATE_CANCELLED
        clearRuntimeRoute()
        publishSnapshotIfChanged(context, buildSnapshot())
        Log.d(ROUTE_TELEMETRY_TAG, "route polyline cleared")
    }

    private fun clearRuntimeRoute() {
        runtimeRoute = null
        runtimeRouteAlertsRaw = null
        runtimeRouteAlertsRouteId = null
        laneGuidanceCandidates.clear()
    }

    private fun clearLegacyPersistedRouteTelemetry(context: Context) {
        val changed = context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE).editIfChanged {
            removeIfPresent(PREF_STATE)
            removeIfPresent(PREF_START)
            removeIfPresent(PREF_END)
            removeIfPresent(PREF_SAMPLED)
            removeIfPresent(PREF_ROUTE_ID)
            removeIfPresent(PREF_PRE_MANEUVER)
            removeIfPresent(PREF_WAYPOINTS)
            removeIfPresent(PREF_JAMS)
            removeIfPresent(PREF_ROUTE_ALERTS)
            removeIfPresent(PREF_ROUTE_ALERTS_ROUTE_ID)
            removeIfPresent(PREF_ROUTE_LANE_POINTS)
        }
        if (changed) {
            parsedSampledRouteCache = null
            Log.d(ROUTE_TELEMETRY_TAG, "legacy persisted route telemetry cleared")
        }
    }

    private fun notifyRouteStatusChanged(context: Context, snapshot: MapRouteTelemetrySnapshot) {
        context.sendBroadcast(
            Intent(MAP_RENDER_ROUTE_STATUS_ACTION).apply {
                putExtra(MAP_EXTRA_HAS_ROUTE, snapshot.hasRoute)
                putExtra(MAP_EXTRA_ROUTE_BUILT, snapshot.routeBuilt)
                putExtra(MAP_EXTRA_ROUTE_STATE, snapshot.state)
            }
        )
    }

    private fun buildSnapshot(): MapRouteTelemetrySnapshot {
        val route = runtimeRoute
        val routeId = route?.routeId
        val points = route?.points.orEmpty()
        val routeToken = route?.routeToken
        val jamsRaw = route?.jamsRaw
        val alertsRaw = runtimeRouteAlertsRaw.takeIf {
            route != null && routeId.matchesRouteId(runtimeRouteAlertsRouteId)
        }
        val normalizedJams = normalizeJams(jamsRaw, points.size - 1)
        logRouteJams(
            routeId = routeId,
            routeToken = routeToken,
            jamsRaw = jamsRaw,
            routeSegmentCount = (points.size - 1).coerceAtLeast(0),
            normalized = normalizedJams
        )
        return MapRouteTelemetrySnapshot(
            state = runtimeState,
            hasExternalTelemetry = route != null || runtimeState != null,
            routeToken = routeToken,
            routeJamsToken = jamsRaw.routeRawToken(),
            routePoints = points,
            routeJams = normalizedJams.values,
            routeAlerts = parseRouteAlerts(alertsRaw),
            routeAlertsToken = alertsRaw.routeRawToken(runtimeRouteAlertsRouteId),
            laneManeuver = resolveActiveLaneGuidance(route?.lanePointsRaw, routeId),
            startLocation = points.startLocation(),
        )
    }

    private fun parseSampledRoute(raw: String?, routeId: String?): ParsedSampledRoute? {
        if (raw.isNullOrBlank()) return null
        parsedSampledRouteCache?.takeIf { it.raw == raw && it.routeId == routeId }?.let { return it }
        val points = parsePoints(raw)
        val parsed = ParsedSampledRoute(
            raw = raw,
            routeId = routeId,
            points = points,
            startLocation = points.startLocation(),
            token = points.routePointsToken(routeId),
        )
        parsedSampledRouteCache = parsed
        return parsed
    }

    private fun parsePoints(raw: String?): List<LatLng> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull(::parsePoint)
    }

    private fun parsePoint(raw: String?): LatLng? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split(',')
        if (parts.size < 2) return null
        val lat = parts[0].trim().toDoubleOrNull() ?: return null
        val lon = parts[1].trim().toDoubleOrNull() ?: return null
        return LatLng(lat, lon)
    }

    private fun parseRouteAlerts(raw: String?): List<MapRouteAlert> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { chunk ->
            val separator = chunk.indexOf('@')
            if (separator <= 0 || separator >= chunk.lastIndex) return@mapNotNull null
            val type = chunk.substring(0, separator).trim().takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val point = parsePoint(chunk.substring(separator + 1)) ?: return@mapNotNull null
            MapRouteAlert(type, point)
        }
    }

    private fun parseLanePoints(raw: String?): List<MapLanePoint> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull(::parseLanePoint)
    }

    private fun parseLanePoint(raw: String): MapLanePoint? {
        val parts = raw.split('|')
        if (parts.size >= 4 && ',' in parts[1]) {
            val seg = parts[0].trim().toIntOrNull() ?: return null
            val point = parsePoint(parts[1]) ?: return null
            val sig = parts[2].trim()
            val routeId = parts[3].trim()
            if (sig.isBlank()) return null
            return MapLanePoint(routeId, seg, point, sig)
        }
        if (parts.size >= 5) {
            val seg = parts[0].trim().toIntOrNull() ?: return null
            val lat = parts[1].trim().toDoubleOrNull() ?: return null
            val lon = parts[2].trim().toDoubleOrNull() ?: return null
            val sig = parts[3].trim()
            val routeId = parts[4].trim()
            if (sig.isBlank()) return null
            return MapLanePoint(routeId, seg, LatLng(lat, lon), sig)
        }
        return null
    }

    private fun findLanePoint(
        raw: String?,
        routeId: String,
        segmentIndex: Int,
        laneSig: String,
    ): LatLng? {
        if (segmentIndex == Int.MIN_VALUE || laneSig.isBlank()) return null
        val candidates = parseLanePoints(raw).filter { point ->
            point.segmentIndex == segmentIndex &&
                point.laneSig == laneSig &&
                (routeId.isBlank() || point.routeId.isBlank() || point.routeId == routeId)
        }
        return candidates.firstOrNull()?.point
    }

    private fun normalizeJams(raw: String?, size: Int): NormalizedRouteJams {
        if (size <= 0) {
            return NormalizedRouteJams(
                values = emptyList(),
                rawTypes = emptyList(),
                unsupportedTypes = emptyList()
            )
        }
        val rawTypes = raw.orEmpty()
            .split(Regex("[,;]"))
            .mapNotNull { jam ->
                jam.trim()
                    .uppercase()
                    .takeIf { it.isNotBlank() }
            }
        val unsupportedTypes = rawTypes
            .filterNot(::isSupportedRouteJamType)
            .distinct()
        val normalized = rawTypes
            .mapNotNull(::normalizeRouteJamType)
            .toMutableList()
        while (normalized.size < size) {
            normalized += normalized.lastOrNull() ?: "low"
        }
        return NormalizedRouteJams(
            values = normalized.take(size),
            rawTypes = rawTypes,
            unsupportedTypes = unsupportedTypes
        )
    }

    private fun logRouteJams(
        routeId: String?,
        routeToken: String?,
        jamsRaw: String?,
        routeSegmentCount: Int,
        normalized: NormalizedRouteJams,
    ) {
        val debugKey = buildString {
            append(routeToken.orEmpty())
            append('|')
            append(jamsRaw.routeRawToken().orEmpty())
            append('|')
            append(routeSegmentCount)
        }
        if (debugKey == lastRouteJamsDebugKey) return
        lastRouteJamsDebugKey = debugKey

        val rawCount = normalized.rawTypes.size
        val rawPreview = normalized.rawTypes.take(16).joinToString(separator = ",").ifBlank { "-" }
        val buckets = normalized.values.distinct().joinToString(separator = ",").ifBlank { "-" }
        val unsupported = normalized.unsupportedTypes.joinToString(separator = ",").ifBlank { "-" }
        val hasLengthMismatch = rawCount > 0 && rawCount != routeSegmentCount
        val message =
            "route jams telemetry: routeId=${routeId.orEmpty()} routeToken=${routeToken.orEmpty()} " +
                "segmentCount=$routeSegmentCount rawCount=$rawCount normalizedCount=${normalized.values.size} " +
                "containsUnknown=${normalized.containsUnknown} unsupported=$unsupported " +
                "rawPreview=$rawPreview buckets=$buckets"
        if (normalized.containsUnknown || normalized.unsupportedTypes.isNotEmpty() || hasLengthMismatch) {
            Log.w(ROUTE_TELEMETRY_TAG, message)
        } else {
            Log.d(ROUTE_TELEMETRY_TAG, message)
        }
    }

    private data class ParsedSampledRoute(
        val raw: String,
        val routeId: String?,
        val points: List<LatLng>,
        val startLocation: Location?,
        val token: String,
    )

    private class PrefsChangeBuilder(
        private val prefs: android.content.SharedPreferences,
        private val editor: android.content.SharedPreferences.Editor,
    ) {
        var changed: Boolean = false
            private set

        fun putStringIfChanged(key: String, value: String?) {
            if (prefs.getString(key, null) == value) return
            editor.putString(key, value)
            changed = true
        }

        fun removeIfPresent(key: String) {
            if (!prefs.contains(key)) return
            editor.remove(key)
            changed = true
        }
    }

    private fun android.content.SharedPreferences.editIfChanged(block: PrefsChangeBuilder.() -> Unit): Boolean {
        val editor = edit()
        val builder = PrefsChangeBuilder(this, editor)
        builder.block()
        if (builder.changed) {
            editor.apply()
        }
        return builder.changed
    }

    private fun MapRouteTelemetrySnapshot.sameRouteContentAs(other: MapRouteTelemetrySnapshot): Boolean {
        return state == other.state &&
            hasExternalTelemetry == other.hasExternalTelemetry &&
            routeToken == other.routeToken &&
            routeJamsToken == other.routeJamsToken &&
            routeAlertsToken == other.routeAlertsToken &&
            laneManeuver?.token == other.laneManeuver?.token &&
            laneManeuver?.point == other.laneManeuver?.point &&
            laneManeuver?.distanceMeters == other.laneManeuver?.distanceMeters &&
            routePoints.size == other.routePoints.size &&
            routeJams == other.routeJams &&
            routeAlerts == other.routeAlerts
    }

    private fun String?.routeRawToken(extra: String? = null): String? {
        if (this.isNullOrBlank()) return null
        return buildString {
            append(this@routeRawToken.length)
            append(':')
            append(stableHash64(this@routeRawToken).toString(16))
            if (!extra.isNullOrBlank()) {
                append(':')
                append(stableHash64(extra).toString(16))
            }
        }
    }

    private fun String?.matchesRouteId(other: String?): Boolean {
        if (this.isNullOrBlank() || other.isNullOrBlank()) return true
        return trim() == other.trim()
    }

    private fun isSupportedRouteJamType(value: String): Boolean {
        return normalizeRouteJamType(value) != null
    }

    private fun normalizeRouteJamType(value: String): String? {
        return when (value.trim().uppercase()) {
            "FREE" -> "low"
            "LIGHT" -> "moderate"
            "HARD" -> "heavy"
            "VERY_HARD", "VERYHARD" -> "severe"
            "BLOCKED" -> "blocked"
            "UNKNOWN" -> "unknown"
            else -> null
        }
    }

    private fun List<LatLng>.routePointsToken(routeId: String?): String {
        var hash = 1125899906842597L
        forEach { point ->
            hash = (hash * 31L) + point.latitude.toBits()
            hash = (hash * 31L) + point.longitude.toBits()
        }
        if (!routeId.isNullOrBlank()) {
            hash = (hash * 31L) + stableHash64(routeId)
        }
        return "$size:${hash.toString(16)}"
    }

    private fun stableHash64(value: String): Long {
        var hash = -3750763034362895579L
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 1099511628211L
        }
        return hash
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
        return runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
    }

    private fun List<LatLng>.startLocation(): Location? {
        if (size < 2) return null
        val start = first()
        val next = this[1]
        return Location(ROUTE_TELEMETRY_LOCATION_PROVIDER).apply {
            latitude = start.latitude
            longitude = start.longitude
            altitude = start.altitude
            bearing = bearingBetween(start, next).toFloat()
            speed = EXTERNAL_ROUTE_SPEED_MPS.toFloat()
            accuracy = 3f
            time = System.currentTimeMillis()
        }
    }

    private fun bearingBetween(start: LatLng, end: LatLng): Double {
        val startLat = Math.toRadians(start.latitude)
        val endLat = Math.toRadians(end.latitude)
        val deltaLon = Math.toRadians(end.longitude - start.longitude)
        val y = sin(deltaLon) * cos(endLat)
        val x = cos(startLat) * sin(endLat) -
            sin(startLat) * cos(endLat) * cos(deltaLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }
}

const val MAP_ROUTE_TELEMETRY_ACTION = "com.yandex.ROUTE_TELEMETRY"
const val MAP_ROUTE_STATE_ACTION = "com.yandex.ROUTE_STATE"
const val MAP_ROUTE_ALERTS_ACTION = "com.yandex.ROUTE_ALERTS"
const val MAP_ROUTE_ALERTS_ALT_ACTION = "ru.yandex.yandexmaps.ROUTE_ALERTS"
const val MAP_MANEUVER_BLOCK_ACTION = "com.yandex.MANEUVER_BLOCK_BITMAP"
const val MAP_MANEUVER_BLOCK_ALT_ACTION = "ru.yandex.yandexmaps.MANEUVER_BLOCK_BITMAP"
const val MAP_EXTRA_ROUTE_STATE = "route_state"
const val MAP_EXTRA_ROUTE_ID = "route_id"
const val MAP_EXTRA_ROUTE_START = "route_start"
const val MAP_EXTRA_ROUTE_END = "route_end"
const val MAP_EXTRA_ROUTE_SAMPLED = "route_sampled"
const val MAP_EXTRA_ROUTE_PRE_MANEUVER = "route_pre_maneuver"
const val MAP_EXTRA_ROUTE_WAYPOINTS = "route_waypoints"
const val MAP_EXTRA_ROUTE_JAMS = "route_jams"
const val MAP_EXTRA_ROUTE_LANE_POINTS = "route_lane_points"
const val MAP_EXTRA_ROUTE_ALERTS = "route_alerts"
const val MAP_EXTRA_MANEUVER_BLOCK_BITMAP = "maneuver_block_bitmap"
const val MAP_EXTRA_MANEUVER_BLOCK_SOURCE_TYPE = "source_type"
const val MAP_MANEUVER_SOURCE_LANE_AND_MANEUVER = "lane_and_maneuver"
const val MAP_EXTRA_LANE_SIG = "lane_sig"
const val MAP_EXTRA_LANE_SEG = "lane_seg"
const val MAP_EXTRA_LANE_ROUTE_ID = "lane_route_id"
const val MAP_EXTRA_LANE_POS_VALID = "lane_pos_valid"
const val MAP_EXTRA_LANE_POS_LAT = "lane_pos_lat"
const val MAP_EXTRA_LANE_POS_LON = "lane_pos_lon"
const val MAP_EXTRA_LANE_BITMAP_ANCHOR_VALID = "lane_bitmap_anchor_valid"
const val MAP_EXTRA_LANE_BITMAP_ANCHOR_X = "lane_bitmap_anchor_x"
const val MAP_EXTRA_LANE_BITMAP_ANCHOR_Y = "lane_bitmap_anchor_y"
const val MAP_EXTRA_HAS_ROUTE = "has_route"
const val MAP_EXTRA_ROUTE_BUILT = "route_built"
const val MAP_ROUTE_STATE_BUILT = "BUILT"
const val MAP_ROUTE_STATE_ARRIVED = "ARRIVED"
const val MAP_ROUTE_STATE_CANCELLED = "CANCELLED"
const val MAP_RENDER_ROUTE_STATUS_ACTION = "com.g992.mapformer.ROUTE_STATUS_CHANGED"
