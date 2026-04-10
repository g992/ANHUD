package com.g992.anhud

import android.content.Context
import android.os.Looper
import android.util.Log
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.maps.Style
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.math.cos

private const val MAP_CACHE_TAG = "MapCache"
private const val CORRIDOR_CHUNK_SIZE = 12
private const val CORRIDOR_PADDING_METERS = 550.0
private const val BOUNDS_SNAP_STEP = 0.005
private const val WARMUP_SNAPSHOT_SIZE_PX = 384

data class MapCacheSnapshot(
    val configuredBytes: Long = 1024L * 1024L * 1024L,
    val usedBytes: Long = 0L,
    val downloadRouteEnabled: Boolean = false,
    val routeDownloadActive: Boolean = false,
    val routeDownloadStartedChunks: Int = 0,
    val routeDownloadCompletedChunks: Int = 0,
    val routeDownloadTotalChunks: Int = 0,
    val routeDownloadPercent: Int = 0,
    val lastError: String? = null,
)

object MapCacheController {
    private val listeners = CopyOnWriteArraySet<(MapCacheSnapshot) -> Unit>()

    private lateinit var appContext: Context
    private var initialized = false
    private var lastAppliedBytes: Long? = null
    private var lastDesiredChunkKeys: Set<String> = emptySet()
    private val chunkProgress = linkedMapOf<String, Float>()
    private val completedChunkCache = linkedSetOf<String>()
    private val desiredChunksByKey = linkedMapOf<String, CorridorChunk>()
    private var currentSnapshotter: MapSnapshotter? = null
    private var currentWarmupKey: String? = null
    private var snapshot = MapCacheSnapshot()

    private val settingsListener: (MapRenderSettings) -> Unit = { settings ->
        val targetBytes = settings.cacheSizeBytes()
        snapshot = snapshot.copy(
            configuredBytes = targetBytes,
            downloadRouteEnabled = settings.downloadRouteEnabled
        )
        notifyListeners()
        if (lastAppliedBytes != targetBytes) {
            applyAmbientCacheSize(targetBytes)
        }
        if (!settings.downloadRouteEnabled) {
            cancelWarmupAndReset()
        }
    }

    private val routeListener: (MapRouteTelemetrySnapshot) -> Unit = routeListener@{ routeSnapshot ->
        if (!MapRenderSettingsStore.current().downloadRouteEnabled) {
            return@routeListener
        }
        val routeToken = routeSnapshot.routeToken
        if (routeSnapshot.routePoints.size < 2 || routeToken.isNullOrBlank()) {
            if (routeSnapshot.state == MAP_ROUTE_STATE_ARRIVED ||
                routeSnapshot.state == MAP_ROUTE_STATE_CANCELLED
            ) {
                cancelWarmupAndReset()
            }
            return@routeListener
        }
        val desiredChunks = buildCorridorChunks(routeSnapshot.routePoints)
        val desiredKeys = desiredChunks.map { it.key }.toSet()
        if (desiredKeys == lastDesiredChunkKeys) {
            return@routeListener
        }
        lastDesiredChunkKeys = desiredKeys
        prefetchRouteCorridor(desiredChunks)
    }

    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        MapLibre.getInstance(appContext, null, WellKnownTileServer.MapLibre)
        initialized = true
        snapshot = snapshot.copy(
            configuredBytes = MapRenderSettingsStore.current().cacheSizeBytes(),
            downloadRouteEnabled = MapRenderSettingsStore.current().downloadRouteEnabled
        )
        MapRenderSettingsStore.addListener(settingsListener)
        MapRouteTelemetryStore.addListener(routeListener)
        applyAmbientCacheSize(snapshot.configuredBytes)
    }

    fun current(): MapCacheSnapshot = snapshot

    fun addListener(listener: (MapCacheSnapshot) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (MapCacheSnapshot) -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        listeners.forEach { it(snapshot) }
    }

    private fun applyAmbientCacheSize(sizeBytes: Long) {
        val apply = {
            OfflineManager.getInstance(appContext).setMaximumAmbientCacheSize(
                sizeBytes,
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        lastAppliedBytes = sizeBytes
                        snapshot = snapshot.copy(lastError = null)
                        notifyListeners()
                    }

                    override fun onError(message: String) {
                        Log.e(MAP_CACHE_TAG, "ambient cache size failed: $message")
                        snapshot = snapshot.copy(lastError = message)
                        notifyListeners()
                    }
                }
            )
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            apply()
        } else {
            android.os.Handler(Looper.getMainLooper()).post { apply() }
        }
    }

    private fun prefetchRouteCorridor(chunks: List<CorridorChunk>) {
        val desiredKeys = chunks.map { it.key }.toSet()
        desiredChunksByKey.clear()
        chunks.forEach { desiredChunksByKey[it.key] = it }
        chunkProgress.keys.retainAll(desiredKeys)
        desiredKeys.forEach { key ->
            val existing = chunkProgress[key]
            chunkProgress[key] = when {
                existing != null -> existing
                key in completedChunkCache -> 1f
                else -> 0f
            }
        }
        if (currentWarmupKey != null && currentWarmupKey !in desiredKeys) {
            cancelCurrentWarmup()
        }
        publishChunkProgress(null)
        maybeStartWarmup()
    }

    private fun maybeStartWarmup() {
        if (currentSnapshotter != null) return
        val nextChunk = desiredChunksByKey.values.firstOrNull { chunk ->
            (chunkProgress[chunk.key] ?: 0f) < 1f
        } ?: run {
            publishChunkProgress(null)
            return
        }
        startWarmup(nextChunk)
    }

    private fun startWarmup(chunk: CorridorChunk) {
        val options = MapSnapshotter.Options(WARMUP_SNAPSHOT_SIZE_PX, WARMUP_SNAPSHOT_SIZE_PX)
            .withStyleBuilder(buildHudMapStyle(appContext))
            .withRegion(chunk.bounds)
            .withLogo(false)
            .withAttribution(false)
            .withPixelRatio(1f)
        val snapshotter = MapSnapshotter(appContext, options)
        currentSnapshotter = snapshotter
        currentWarmupKey = chunk.key
        if ((chunkProgress[chunk.key] ?: 0f) <= 0f) {
            updateChunkProgress(chunk.key, 0.05f)
        }
        snapshotter.start(
            { result ->
                result.bitmap.recycle()
                completedChunkCache.add(chunk.key)
                updateChunkProgress(chunk.key, 1f)
                currentSnapshotter = null
                currentWarmupKey = null
                maybeStartWarmup()
            },
            { error ->
                Log.e(MAP_CACHE_TAG, "route chunk warm failed key=${chunk.key} message=$error")
                snapshot = snapshot.copy(lastError = error)
                notifyListeners()
                currentSnapshotter = null
                currentWarmupKey = null
                maybeStartWarmup()
            }
        )
    }

    private fun cancelCurrentWarmup() {
        currentSnapshotter?.cancel()
        currentSnapshotter = null
        currentWarmupKey = null
    }

    private fun cancelWarmupAndReset() {
        cancelCurrentWarmup()
        desiredChunksByKey.clear()
        lastDesiredChunkKeys = emptySet()
        chunkProgress.clear()
        snapshot = snapshot.copy(
            routeDownloadActive = false,
            routeDownloadStartedChunks = 0,
            routeDownloadCompletedChunks = 0,
            routeDownloadTotalChunks = 0,
            routeDownloadPercent = 0,
        )
        notifyListeners()
    }

    private fun updateChunkProgress(key: String, progress: Float) {
        if (key !in chunkProgress) return
        chunkProgress[key] = progress.coerceIn(0f, 1f)
        publishChunkProgress(snapshot.lastError)
    }

    private fun publishChunkProgress(lastError: String?) {
        val total = chunkProgress.size
        val completed = chunkProgress.values.count { it >= 1f }
        val started = chunkProgress.values.count { it > 0f }
        val percent = if (total == 0) {
            0
        } else {
            ((chunkProgress.values.sum() / total.toFloat()) * 100f).toInt().coerceIn(0, 100)
        }
        snapshot = snapshot.copy(
            routeDownloadActive = total > 0 && completed < total,
            routeDownloadStartedChunks = started,
            routeDownloadCompletedChunks = completed,
            routeDownloadTotalChunks = total,
            routeDownloadPercent = percent,
            lastError = lastError,
        )
        notifyListeners()
    }

    private fun buildCorridorChunks(points: List<LatLng>): List<CorridorChunk> {
        val result = mutableListOf<CorridorChunk>()
        points.chunked(CORRIDOR_CHUNK_SIZE).forEachIndexed { index, chunk ->
            if (chunk.isEmpty()) return@forEachIndexed
            val padded = buildBounds(chunk)
            result += CorridorChunk(
                key = "${index}_${snapBoundsKey(padded)}",
                bounds = padded,
            )
        }
        return result
    }

    private fun buildBounds(points: List<LatLng>): LatLngBounds {
        val latPadding = CORRIDOR_PADDING_METERS / 111_320.0
        val averageLat = points.map { it.latitude }.average()
        val lonPadding = CORRIDOR_PADDING_METERS / (111_320.0 * cos(Math.toRadians(averageLat)).coerceAtLeast(0.1))
        val minLat = points.minOf { it.latitude } - latPadding
        val maxLat = points.maxOf { it.latitude } + latPadding
        val minLon = points.minOf { it.longitude } - lonPadding
        val maxLon = points.maxOf { it.longitude } + lonPadding
        return LatLngBounds.Builder()
            .include(LatLng(minLat, minLon))
            .include(LatLng(maxLat, maxLon))
            .build()
    }

    private fun snapBoundsKey(bounds: LatLngBounds): String {
        fun snap(value: Double): String {
            val snapped = kotlin.math.round(value / BOUNDS_SNAP_STEP) * BOUNDS_SNAP_STEP
            return "%.3f".format(snapped)
        }
        return listOf(
            snap(bounds.latitudeNorth),
            snap(bounds.longitudeEast),
            snap(bounds.latitudeSouth),
            snap(bounds.longitudeWest)
        ).joinToString("_")
    }

    data class CorridorChunk(
        val key: String,
        val bounds: LatLngBounds,
    )
}
