package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.util.Log
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.atan2
import kotlin.math.cos
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
private const val EXTERNAL_ROUTE_SPEED_MPS = 10.0

data class MapRouteTelemetrySnapshot(
    val state: String? = null,
    val hasExternalTelemetry: Boolean = false,
    val routeToken: String? = null,
    val routeJamsToken: String? = null,
    val routePoints: List<LatLng> = emptyList(),
    val routeJams: List<String> = emptyList(),
    val startLocation: Location? = null,
) {
    val hasRoute: Boolean
        get() = routePoints.size >= 2

    val routeBuilt: Boolean
        get() = hasRoute || state == MAP_ROUTE_STATE_BUILT
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

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            currentSnapshot = buildSnapshot(context.applicationContext)
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
        val prefs = context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE)
        when (intent.action) {
            MAP_ROUTE_TELEMETRY_ACTION -> {
                val sampled = intent.getStringExtra(MAP_EXTRA_ROUTE_SAMPLED)
                val jams = intent.getStringExtra(MAP_EXTRA_ROUTE_JAMS)
                val changed = prefs.editIfChanged {
                    putStringIfChanged(PREF_START, intent.getStringExtra(MAP_EXTRA_ROUTE_START))
                    putStringIfChanged(PREF_END, intent.getStringExtra(MAP_EXTRA_ROUTE_END))
                    putStringIfChanged(PREF_SAMPLED, sampled)
                    putStringIfChanged(PREF_PRE_MANEUVER, intent.getStringExtra(MAP_EXTRA_ROUTE_PRE_MANEUVER))
                    putStringIfChanged(PREF_WAYPOINTS, intent.getStringExtra(MAP_EXTRA_ROUTE_WAYPOINTS))
                    putStringIfChanged(PREF_JAMS, jams)
                }
                if (!changed) {
                    return
                }
            }

            MAP_ROUTE_STATE_ACTION -> {
                val state = intent.getStringExtra(MAP_EXTRA_ROUTE_STATE)
                Log.d(ROUTE_TELEMETRY_TAG, "route state: $state")
                val changed = prefs.editIfChanged {
                    putStringIfChanged(PREF_STATE, state)
                    if (state == MAP_ROUTE_STATE_ARRIVED || state == MAP_ROUTE_STATE_CANCELLED) {
                        removeIfPresent(PREF_START)
                        removeIfPresent(PREF_END)
                        removeIfPresent(PREF_SAMPLED)
                        removeIfPresent(PREF_ROUTE_ID)
                        removeIfPresent(PREF_PRE_MANEUVER)
                        removeIfPresent(PREF_WAYPOINTS)
                        removeIfPresent(PREF_JAMS)
                    }
                }
                if (!changed) {
                    return
                }
            }
        }
        publishSnapshotIfChanged(context, buildSnapshot(context))
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
        val sampled = serializePoints(points)
        context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE).editIfChanged {
            putStringIfChanged(PREF_STATE, MAP_ROUTE_STATE_BUILT)
            putStringIfChanged(PREF_ROUTE_ID, routeId)
            putStringIfChanged(PREF_SAMPLED, sampled)
            removeIfPresent(PREF_JAMS)
        }
        publishSnapshotIfChanged(context, buildSnapshot(context))
        Log.d(ROUTE_TELEMETRY_TAG, "route polyline stored: id=${routeId.orEmpty()} points=${points.size}")
    }

    fun clearRoutePolyline(context: Context) {
        initialize(context)
        val changed = context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE).editIfChanged {
            putStringIfChanged(PREF_STATE, MAP_ROUTE_STATE_CANCELLED)
            removeIfPresent(PREF_START)
            removeIfPresent(PREF_END)
            removeIfPresent(PREF_SAMPLED)
            removeIfPresent(PREF_ROUTE_ID)
            removeIfPresent(PREF_PRE_MANEUVER)
            removeIfPresent(PREF_WAYPOINTS)
            removeIfPresent(PREF_JAMS)
        }
        if (!changed) {
            return
        }
        publishSnapshotIfChanged(context, buildSnapshot(context))
        Log.d(ROUTE_TELEMETRY_TAG, "route polyline cleared")
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

    private fun buildSnapshot(context: Context): MapRouteTelemetrySnapshot {
        val prefs = context.getSharedPreferences(ROUTE_PREFS_NAME, Context.MODE_PRIVATE)
        val sampledRaw = prefs.getString(PREF_SAMPLED, null)
        val startRaw = prefs.getString(PREF_START, null)
        val routeId = prefs.getString(PREF_ROUTE_ID, null)
        val jamsRaw = prefs.getString(PREF_JAMS, null)
        val parsedSampled = parseSampledRoute(sampledRaw, routeId)
        val points = parsedSampled?.points ?: listOfNotNull(
            parsePoint(startRaw),
            parsePoint(prefs.getString(PREF_END, null))
        )
        val routeToken = parsedSampled?.token ?: listOfNotNull(parsePoint(startRaw), parsePoint(prefs.getString(PREF_END, null)))
            .takeIf { it.size >= 2 }
            ?.routePointsToken(routeId)
        val jams = normalizeJams(jamsRaw, points.size - 1)
        return MapRouteTelemetrySnapshot(
            state = prefs.getString(PREF_STATE, null),
            hasExternalTelemetry = sampledRaw != null || prefs.getString(PREF_STATE, null) != null,
            routeToken = routeToken,
            routeJamsToken = jamsRaw.routeRawToken(),
            routePoints = points,
            routeJams = jams,
            startLocation = parsedSampled?.startLocation ?: points.startLocation(),
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

    private fun serializePoints(points: List<LatLng>): String {
        return points.joinToString(";") { point ->
            "${point.latitude},${point.longitude}"
        }
    }

    private fun normalizeJams(raw: String?, size: Int): List<String> {
        if (size <= 0) return emptyList()
        val base = raw.orEmpty()
            .split(';')
            .mapNotNull { jam ->
                when (jam.trim().uppercase()) {
                    "FREE" -> "low"
                    "LIGHT" -> "moderate"
                    "HARD" -> "heavy"
                    "VERY_HARD", "BLOCKED" -> "severe"
                    else -> null
                }
            }
            .toMutableList()
        while (base.size < size) {
            base += base.lastOrNull() ?: "low"
        }
        return base.take(size)
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
            routePoints.size == other.routePoints.size &&
            routeJams == other.routeJams
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

    private fun List<LatLng>.startLocation(): Location? {
        if (size < 2) return null
        val start = first()
        val next = this[1]
        return Location("route-telemetry").apply {
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
const val MAP_EXTRA_ROUTE_STATE = "route_state"
const val MAP_EXTRA_ROUTE_START = "route_start"
const val MAP_EXTRA_ROUTE_END = "route_end"
const val MAP_EXTRA_ROUTE_SAMPLED = "route_sampled"
const val MAP_EXTRA_ROUTE_PRE_MANEUVER = "route_pre_maneuver"
const val MAP_EXTRA_ROUTE_WAYPOINTS = "route_waypoints"
const val MAP_EXTRA_ROUTE_JAMS = "route_jams"
const val MAP_EXTRA_HAS_ROUTE = "has_route"
const val MAP_EXTRA_ROUTE_BUILT = "route_built"
const val MAP_ROUTE_STATE_BUILT = "BUILT"
const val MAP_ROUTE_STATE_ARRIVED = "ARRIVED"
const val MAP_ROUTE_STATE_CANCELLED = "CANCELLED"
const val MAP_RENDER_ROUTE_STATUS_ACTION = "com.g992.mapformer.ROUTE_STATUS_CHANGED"
