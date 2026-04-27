package com.g992.anhud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.snapshotter.MapSnapshotter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.math.cos

private const val MAP_CACHE_TAG = "MapCache"
private const val CORRIDOR_CHUNK_SIZE = 12
private const val CORRIDOR_PADDING_METERS = 550.0
private const val BOUNDS_SNAP_STEP = 0.005
private const val WARMUP_SNAPSHOT_SIZE_PX = 384
private const val OFFLINE_METADATA_OWNER = "anhud"
const val OFFLINE_STATUS_IDLE = 0
const val OFFLINE_STATUS_RESOLVING = 1
const val OFFLINE_STATUS_DOWNLOADING = 2

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
    val offlineSelectionLabel: String? = null,
    val offlineDownloadedLabel: String? = null,
    val offlineDownloadedRegionId: String? = null,
    val offlineDownloadedRegionIds: Set<String> = emptySet(),
    val offlineDownloadedRegionSizesBytes: Map<String, Long> = emptyMap(),
    val offlineDownloadStatus: Int = OFFLINE_STATUS_IDLE,
    val offlineDownloadPercent: Int = 0,
    val offlineDownloadCompletedResources: Long = 0L,
    val offlineDownloadRequiredResources: Long = 0L,
    val offlineDownloadCompletedBytes: Long = 0L,
    val offlineRegionReady: Boolean = false,
    val offlineLastError: String? = null,
)

object MapCacheController {
    private val listeners = CopyOnWriteArraySet<(MapCacheSnapshot) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    private lateinit var appContext: Context
    private var initialized = false
    private var lastAppliedBytes: Long? = null
    private var lastDesiredChunkKeys: Set<String> = emptySet()
    private val chunkProgress = linkedMapOf<String, Float>()
    private val completedChunkCache = linkedSetOf<String>()
    private val desiredChunksByKey = linkedMapOf<String, CorridorChunk>()
    private var currentSnapshotter: MapSnapshotter? = null
    private var currentWarmupKey: String? = null
    private var currentOfflineRegion: OfflineRegion? = null
    private var offlineOperationToken: Long = 0L
    private var snapshot = MapCacheSnapshot()

    private val settingsListener: (MapRenderSettings) -> Unit = { settings ->
        val targetBytes = settings.cacheSizeBytes()
        snapshot = snapshot.copy(
            configuredBytes = targetBytes,
            downloadRouteEnabled = settings.downloadRouteEnabled,
            offlineSelectionLabel = resolveOfflineSelectionLabel(settings)
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
            downloadRouteEnabled = MapRenderSettingsStore.current().downloadRouteEnabled,
            offlineSelectionLabel = resolveOfflineSelectionLabel(MapRenderSettingsStore.current()),
        )
        MapRenderSettingsStore.addListener(settingsListener)
        MapRouteTelemetryStore.addListener(routeListener)
        applyAmbientCacheSize(snapshot.configuredBytes)
        refreshOfflineRegionState()
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

    fun startSelectedOfflineDownload() {
        check(initialized) { "MapCacheController is not initialized" }
        val settings = MapRenderSettingsStore.current()
        val selectionLabel = resolveOfflineSelectionLabel(settings)
        if (selectionLabel == null) {
            snapshot = snapshot.copy(offlineLastError = "Не выбран оффлайн-регион")
            notifyListeners()
            return
        }
        val operationToken = ++offlineOperationToken
        snapshot = snapshot.copy(
            offlineSelectionLabel = selectionLabel,
            offlineDownloadedRegionId = settings.offlineRegionId,
            offlineDownloadStatus = OFFLINE_STATUS_RESOLVING,
            offlineDownloadPercent = 0,
            offlineDownloadCompletedResources = 0L,
            offlineDownloadRequiredResources = 0L,
            offlineDownloadCompletedBytes = 0L,
            offlineRegionReady = false,
            offlineLastError = null,
        )
        notifyListeners()
        ioExecutor.execute {
            try {
                selectReachableMapTileProvider(appContext)
                val resolved = GeoBoundariesRegionResolver.resolveSelected(appContext, settings)
                mainHandler.post {
                    if (operationToken != offlineOperationToken) return@post
                    replaceManagedOfflineRegion(resolved, operationToken)
                }
            } catch (t: Throwable) {
                publishOfflineError(
                    label = selectionLabel,
                    regionId = settings.offlineRegionId,
                    message = t.message ?: "Не удалось подготовить оффлайн-регион"
                )
            }
        }
    }

    fun startOfflineRegionDownload(entry: OfflineRegionEntry) {
        check(initialized) { "MapCacheController is not initialized" }
        startOfflineDownload(
            selectionLabel = entry.displayLabel,
            regionId = entry.id,
            resolve = { GeoBoundariesRegionResolver.resolveCatalogEntry(appContext, entry) }
        )
    }

    fun startManualOfflineDownload(manual: ManualOfflineBounds) {
        check(initialized) { "MapCacheController is not initialized" }
        val normalized = manual.normalizedOrNull() ?: run {
            snapshot = snapshot.copy(offlineLastError = "Введите две разные точки прямоугольника")
            notifyListeners()
            return
        }
        startOfflineDownload(
            selectionLabel = "${normalized.label} · bbox",
            regionId = null,
            resolve = {
                val settings = MapRenderSettings(
                    offlineManualLabel = normalized.label,
                    offlineManualLat1 = normalized.lat1,
                    offlineManualLon1 = normalized.lon1,
                    offlineManualLat2 = normalized.lat2,
                    offlineManualLon2 = normalized.lon2,
                )
                GeoBoundariesRegionResolver.resolveSelected(appContext, settings)
            }
        )
    }

    private fun startOfflineDownload(
        selectionLabel: String,
        regionId: String?,
        resolve: () -> ResolvedOfflineGeometry,
    ) {
        val operationToken = ++offlineOperationToken
        snapshot = snapshot.copy(
            offlineSelectionLabel = selectionLabel,
            offlineDownloadedLabel = selectionLabel,
            offlineDownloadedRegionId = regionId,
            offlineDownloadStatus = OFFLINE_STATUS_RESOLVING,
            offlineDownloadPercent = 0,
            offlineDownloadCompletedResources = 0L,
            offlineDownloadRequiredResources = 0L,
            offlineDownloadCompletedBytes = 0L,
            offlineRegionReady = false,
            offlineLastError = null,
        )
        notifyListeners()
        ioExecutor.execute {
            try {
                selectReachableMapTileProvider(appContext)
                val resolved = resolve()
                mainHandler.post {
                    if (operationToken != offlineOperationToken) return@post
                    replaceManagedOfflineRegion(resolved, operationToken)
                }
            } catch (t: Throwable) {
                publishOfflineError(
                    label = selectionLabel,
                    regionId = regionId,
                    message = t.message ?: "Не удалось подготовить оффлайн-регион"
                )
            }
        }
    }

    fun clearOfflineDownloads() {
        if (!initialized) return
        val operationToken = ++offlineOperationToken
        mainHandler.post {
            currentOfflineRegion?.setDownloadState(OfflineRegion.STATE_INACTIVE)
            listManagedOfflineRegions(
                onSuccess = { managed ->
                    deleteRegionsSequentially(managed.iterator(), operationToken) {
                        if (operationToken != offlineOperationToken) return@deleteRegionsSequentially
                        currentOfflineRegion = null
                        snapshot = snapshot.copy(
                            offlineDownloadedLabel = null,
                            offlineDownloadedRegionId = null,
                            offlineDownloadedRegionIds = emptySet(),
                            offlineDownloadedRegionSizesBytes = emptyMap(),
                            offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                            offlineDownloadPercent = 0,
                            offlineDownloadCompletedResources = 0L,
                            offlineDownloadRequiredResources = 0L,
                            offlineDownloadCompletedBytes = 0L,
                            offlineRegionReady = false,
                            offlineLastError = null,
                        )
                        notifyListeners()
                    }
                },
                onError = { error ->
                    snapshot = snapshot.copy(
                        offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                        offlineLastError = error
                    )
                    notifyListeners()
                }
            )
        }
    }

    fun deleteOfflineRegion(regionId: String) {
        if (!initialized || regionId.isBlank()) return
        val operationToken = ++offlineOperationToken
        mainHandler.post {
            listManagedOfflineRegions(
                onSuccess = { managed ->
                    val matching = managed.filter { parseManagedMetadata(it.metadata)?.optString("regionId") == regionId }
                    deleteRegionsSequentially(matching.iterator(), operationToken) {
                        if (operationToken != offlineOperationToken) return@deleteRegionsSequentially
                        refreshOfflineRegionState()
                    }
                },
                onError = { error ->
                    snapshot = snapshot.copy(
                        offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                        offlineLastError = error
                    )
                    notifyListeners()
                }
            )
        }
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

    private fun refreshOfflineRegionState() {
        mainHandler.post {
            listManagedOfflineRegions(
                onSuccess = { managed ->
                    val region = managed.maxByOrNull { it.id }
                    snapshot = snapshot.copy(
                        offlineDownloadedRegionIds = managed.mapNotNull {
                            parseManagedMetadata(it.metadata)?.optString("regionId")?.takeIf { id -> id.isNotBlank() }
                        }.toSet()
                    )
                    if (region == null) {
                        currentOfflineRegion = null
                        snapshot = snapshot.copy(
                            offlineDownloadedLabel = null,
                            offlineDownloadedRegionId = null,
                            offlineDownloadedRegionIds = emptySet(),
                            offlineDownloadedRegionSizesBytes = emptyMap(),
                            offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                            offlineDownloadPercent = 0,
                            offlineDownloadCompletedResources = 0L,
                            offlineDownloadRequiredResources = 0L,
                            offlineDownloadCompletedBytes = 0L,
                            offlineRegionReady = false,
                            offlineLastError = null,
                        )
                        notifyListeners()
                        return@listManagedOfflineRegions
                    }
                    refreshCompletedOfflineRegionIds(managed)
                    val metadata = parseManagedMetadata(region.metadata)
                    currentOfflineRegion = region
                    observeOfflineRegion(
                        region = region,
                        label = metadata?.optString("label").orEmpty(),
                        regionId = metadata?.optString("regionId")?.takeIf { it.isNotBlank() },
                        operationToken = offlineOperationToken,
                    )
                },
                onError = { error ->
                    snapshot = snapshot.copy(offlineLastError = error)
                    notifyListeners()
                }
            )
        }
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

    private fun replaceManagedOfflineRegion(resolved: ResolvedOfflineGeometry, operationToken: Long) {
        listManagedOfflineRegions(
            onSuccess = { managed ->
                val sameRegion = managed.filter {
                    parseManagedMetadata(it.metadata)?.optString("regionId") == resolved.regionId
                }
                deleteRegionsSequentially(sameRegion.iterator(), operationToken) {
                    if (operationToken != offlineOperationToken) return@deleteRegionsSequentially
                    createOfflineRegion(resolved, operationToken)
                }
            },
            onError = { error ->
                snapshot = snapshot.copy(
                    offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                    offlineLastError = error
                )
                notifyListeners()
            }
        )
    }

    private fun createOfflineRegion(resolved: ResolvedOfflineGeometry, operationToken: Long) {
        val settings = MapRenderSettingsStore.current()
        val offlineMaxZoom = resolveOfflinePackMaxZoom(settings)
        val provider = currentMapTileProvider(appContext)
        val offlineStyleUrl = prepareOfflineStyleUrl(provider)
        Log.d(
            MAP_CACHE_TAG,
            "create offline region label=${resolved.label} regionId=${resolved.regionId} maxZoom=$offlineMaxZoom provider=${provider.id} styleUrl=$offlineStyleUrl"
        )
        val definition = OfflineGeometryRegionDefinition(
            offlineStyleUrl,
            resolved.geometry,
            MAP_ZOOM_MIN,
            offlineMaxZoom,
            appContext.resources.displayMetrics.density,
            false,
        )
        val estimatedTileCount = OfflineRegionSizeEstimator.estimateTileCount(resolved.bounds, offlineMaxZoom)
        OfflineManager.getInstance(appContext).createOfflineRegion(
            definition,
            buildManagedMetadata(resolved, offlineMaxZoom, estimatedTileCount).toByteArray(Charsets.UTF_8),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    Log.d(MAP_CACHE_TAG, "offline region created id=${offlineRegion.id} label=${resolved.label}")
                    if (operationToken != offlineOperationToken) {
                        offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() = Unit
                            override fun onError(error: String) = Unit
                        })
                        return
                    }
                    currentOfflineRegion = offlineRegion
                    observeOfflineRegion(
                        region = offlineRegion,
                        label = resolved.label,
                        regionId = resolved.regionId,
                        resolved = resolved,
                        provider = provider,
                        operationToken = operationToken,
                    )
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    Log.e(MAP_CACHE_TAG, "offline region create failed: $error")
                    snapshot = snapshot.copy(
                        offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                        offlineDownloadedLabel = resolved.label,
                        offlineDownloadedRegionId = resolved.regionId,
                        offlineLastError = error,
                    )
                    notifyListeners()
                }
            }
        )
    }

    private fun observeOfflineRegion(
        region: OfflineRegion,
        label: String,
        regionId: String?,
        resolved: ResolvedOfflineGeometry? = null,
        provider: MapTileProvider? = null,
        operationToken: Long,
    ) {
        region.setDeliverInactiveMessages(true)
        region.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (operationToken != offlineOperationToken && region != currentOfflineRegion) return
                Log.d(
                    MAP_CACHE_TAG,
                    "offline status label=$label state=${status.downloadState} complete=${status.isComplete} resources=${status.completedResourceCount}/${status.requiredResourceCount} bytes=${status.completedResourceSize}"
                )
                publishOfflineStatus(label, regionId, status)
                if (status.isComplete && status.downloadState == OfflineRegion.STATE_ACTIVE) {
                    region.setDownloadState(OfflineRegion.STATE_INACTIVE)
                }
            }

            override fun onError(error: OfflineRegionError) {
                if (operationToken != offlineOperationToken && region != currentOfflineRegion) return
                Log.e(MAP_CACHE_TAG, "offline observer error label=$label reason=${error.reason} message=${error.message}")
                if (resolved != null &&
                    provider != null &&
                    shouldRetryOfflineWithFallback(error) &&
                    retryOfflineRegionWithFallback(region, resolved, provider, operationToken, error.message.orEmpty())
                ) {
                    return
                }
                snapshot = snapshot.copy(
                    offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                    offlineDownloadedLabel = label,
                    offlineDownloadedRegionId = regionId,
                    offlineLastError = error.message,
                )
                notifyListeners()
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (operationToken != offlineOperationToken && region != currentOfflineRegion) return
                Log.e(MAP_CACHE_TAG, "offline tile limit exceeded label=$label limit=$limit")
                snapshot = snapshot.copy(
                    offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                    offlineDownloadedLabel = label,
                    offlineDownloadedRegionId = regionId,
                    offlineLastError = "Достигнут лимит тайлов: $limit",
                )
                notifyListeners()
            }
        })
        region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
            override fun onStatus(status: OfflineRegionStatus?) {
                if (status != null) {
                    Log.d(MAP_CACHE_TAG, "offline initial status label=$label resources=${status.completedResourceCount}/${status.requiredResourceCount} bytes=${status.completedResourceSize}")
                    publishOfflineStatus(label, regionId, status)
                }
            }

            override fun onError(error: String?) {
                Log.e(MAP_CACHE_TAG, "offline status read failed label=$label error=$error")
                snapshot = snapshot.copy(
                    offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                    offlineDownloadedLabel = label,
                    offlineDownloadedRegionId = regionId,
                    offlineLastError = error ?: "Не удалось прочитать статус оффлайн-региона",
                )
                notifyListeners()
            }
        })
    }

    private fun publishOfflineStatus(
        label: String,
        regionId: String?,
        status: OfflineRegionStatus,
    ) {
        val required = status.requiredResourceCount.coerceAtLeast(status.completedResourceCount)
        val percent = if (required <= 0L) {
            0
        } else {
            ((status.completedResourceCount * 100L) / required).toInt().coerceIn(0, 100)
        }
        snapshot = snapshot.copy(
            offlineDownloadedLabel = label,
            offlineDownloadedRegionId = regionId,
            offlineDownloadStatus = when {
                status.isComplete -> OFFLINE_STATUS_IDLE
                status.downloadState == OfflineRegion.STATE_ACTIVE -> OFFLINE_STATUS_DOWNLOADING
                else -> OFFLINE_STATUS_IDLE
            },
            offlineDownloadPercent = if (status.isComplete) 100 else percent,
            offlineDownloadCompletedResources = status.completedResourceCount,
            offlineDownloadRequiredResources = status.requiredResourceCount,
            offlineDownloadCompletedBytes = status.completedResourceSize,
            offlineRegionReady = status.isComplete,
            offlineLastError = null,
        )
        if (status.isComplete) {
            val metadata = currentOfflineRegion?.metadata?.let { parseManagedMetadata(it) }
            calibrateSizeEstimate(metadata, status)
            snapshot = snapshot.copy(
                offlineDownloadedRegionIds = snapshot.offlineDownloadedRegionIds + listOfNotNull(regionId),
                offlineDownloadedRegionSizesBytes = if (regionId == null) {
                    snapshot.offlineDownloadedRegionSizesBytes
                } else {
                    snapshot.offlineDownloadedRegionSizesBytes + (regionId to status.completedResourceSize)
                }
            )
        }
        notifyListeners()
    }

    private fun publishOfflineError(label: String, regionId: String?, message: String) {
        mainHandler.post {
            Log.e(MAP_CACHE_TAG, "offline resolve failed label=$label error=$message")
            snapshot = snapshot.copy(
                offlineDownloadedLabel = label,
                offlineDownloadedRegionId = regionId,
                offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                offlineLastError = message,
            )
            notifyListeners()
        }
    }

    private fun listManagedOfflineRegions(
        onSuccess: (List<OfflineRegion>) -> Unit,
        onError: (String) -> Unit,
    ) {
        OfflineManager.getInstance(appContext).listOfflineRegions(
            object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    onSuccess(offlineRegions.orEmpty().filter { parseManagedMetadata(it.metadata) != null })
                }

                override fun onError(error: String) {
                    onError(error)
                }
            }
        )
    }

    private fun shouldRetryOfflineWithFallback(error: OfflineRegionError): Boolean {
        val reason = error.reason.orEmpty().lowercase()
        val message = error.message.orEmpty().lowercase()
        return "timeout" in reason ||
            "server" in reason ||
            "connection" in reason ||
            "timeout" in message ||
            "timed out" in message ||
            "connection timed out" in message
    }

    private fun retryOfflineRegionWithFallback(
        region: OfflineRegion,
        resolved: ResolvedOfflineGeometry,
        provider: MapTileProvider,
        operationToken: Long,
        errorMessage: String,
    ): Boolean {
        val fallback = advanceMapTileProvider(appContext, provider.id) ?: return false
        Log.w(
            MAP_CACHE_TAG,
            "offline provider ${provider.id} failed for ${resolved.label}: $errorMessage; retrying with ${fallback.id}"
        )
        snapshot = snapshot.copy(
            offlineDownloadStatus = OFFLINE_STATUS_RESOLVING,
            offlineDownloadedLabel = resolved.label,
            offlineDownloadedRegionId = resolved.regionId,
            offlineLastError = "Источник ${provider.displayName} недоступен, пробуем ${fallback.displayName}"
        )
        notifyListeners()
        runCatching { region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
        region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                if (operationToken != offlineOperationToken) return
                createOfflineRegion(resolved, operationToken)
            }

            override fun onError(error: String) {
                Log.e(MAP_CACHE_TAG, "offline region delete before fallback failed: $error")
                snapshot = snapshot.copy(
                    offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                    offlineDownloadedLabel = resolved.label,
                    offlineDownloadedRegionId = resolved.regionId,
                    offlineLastError = error,
                )
                notifyListeners()
            }
        })
        return true
    }

    private fun refreshCompletedOfflineRegionIds(regions: List<OfflineRegion>) {
        if (regions.isEmpty()) return
        val completedIds = mutableSetOf<String>()
        val completedSizes = mutableMapOf<String, Long>()
        var remaining = regions.size
        regions.forEach { region ->
            val metadata = parseManagedMetadata(region.metadata)
            val regionId = metadata?.optString("regionId")?.takeIf { it.isNotBlank() }
            region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                override fun onStatus(status: OfflineRegionStatus?) {
                    if (regionId != null && status?.isComplete == true) {
                        completedIds += regionId
                        completedSizes[regionId] = status.completedResourceSize
                        calibrateSizeEstimate(metadata, status)
                    }
                    publishWhenDone()
                }

                override fun onError(error: String?) {
                    publishWhenDone()
                }

                private fun publishWhenDone() {
                    remaining -= 1
                    if (remaining == 0) {
                        snapshot = snapshot.copy(
                            offlineDownloadedRegionIds = completedIds,
                            offlineDownloadedRegionSizesBytes = completedSizes.toMap(),
                            offlineLastError = snapshot.offlineLastError,
                        )
                        notifyListeners()
                    }
                }
            })
        }
    }

    private fun deleteRegionsSequentially(
        iterator: Iterator<OfflineRegion>,
        operationToken: Long,
        onDone: () -> Unit,
    ) {
        if (operationToken != offlineOperationToken) return
        val next = if (iterator.hasNext()) iterator.next() else null
        if (next == null) {
            onDone()
            return
        }
        next.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
            override fun onDelete() {
                deleteRegionsSequentially(iterator, operationToken, onDone)
            }

            override fun onError(error: String) {
                snapshot = snapshot.copy(
                    offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                    offlineLastError = error
                )
                notifyListeners()
            }
        })
    }

    private fun buildManagedMetadata(
        resolved: ResolvedOfflineGeometry,
        maxZoom: Double,
        estimatedTileCount: Long,
    ): String {
        return JSONObject()
            .put("owner", OFFLINE_METADATA_OWNER)
            .put("label", resolved.label)
            .put("regionId", resolved.regionId)
            .put("providerId", currentMapTileProvider(appContext).id)
            .put("maxZoom", maxZoom)
            .put("estimatedTileCount", estimatedTileCount)
            .toString()
    }

    private fun parseManagedMetadata(bytes: ByteArray?): JSONObject? {
        if (bytes == null || bytes.isEmpty()) return null
        return try {
            JSONObject(bytes.toString(Charsets.UTF_8)).takeIf {
                it.optString("owner") == OFFLINE_METADATA_OWNER
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun resolveOfflineSelectionLabel(settings: MapRenderSettings): String? {
        settings.manualOfflineBoundsOrNull()?.let { return "${it.label} · bbox" }
        return OfflineRegionCatalog.findById(appContext, settings.offlineRegionId)?.displayLabel
    }

    private fun calibrateSizeEstimate(metadata: JSONObject?, status: OfflineRegionStatus) {
        val estimatedTileCount = metadata?.optLong("estimatedTileCount", 0L)
            ?.takeIf { it > 0L }
            ?: estimateTileCountFromRegionId(metadata?.optString("regionId").orEmpty())
        OfflineRegionSizeEstimator.calibrate(
            context = appContext,
            completedBytes = status.completedResourceSize,
            estimatedTileCount = estimatedTileCount,
        )
    }

    private fun estimateTileCountFromRegionId(regionId: String): Long {
        if (regionId.isBlank() || regionId.startsWith("manual:")) return 0L
        val entry = OfflineRegionCatalog.findById(appContext, regionId) ?: return 0L
        return OfflineRegionSizeEstimator.estimateTileCount(
            bounds = entry.boundsPreview(),
            maxZoom = resolveOfflinePackMaxZoom(MapRenderSettingsStore.current()),
        )
    }

    private fun resolveOfflinePackMaxZoom(settings: MapRenderSettings): Double {
        val maxDynamicZoom = if (settings.autoZoomEnabled) {
            maxOf(settings.autoZoomAt0Kmh, settings.autoZoomAt60Kmh, settings.autoZoomAt90Kmh)
        } else {
            settings.zoom
        }
        return maxDynamicZoom.coerceIn(MAP_ZOOM_MIN, MAP_ZOOM_MAX)
    }

    private fun prepareOfflineStyleUrl(provider: MapTileProvider): String {
        return ensureHudMapStyleCached(appContext, provider)
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
