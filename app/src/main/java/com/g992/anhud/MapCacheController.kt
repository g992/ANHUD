package com.g992.anhud

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.storage.FileSource
import org.maplibre.geojson.Feature
import java.io.File
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

const val OFFLINE_STATUS_IDLE = 0
const val OFFLINE_STATUS_RESOLVING = 1
const val OFFLINE_STATUS_DOWNLOADING = 2

private const val OFFLINE_METADATA_VERSION = 3
private const val MAP_CACHE_TAG = "MapCacheController"
private const val OFFLINE_STYLE_CACHE_URL_BASE = "https://offline.anhud.local/styles"
private const val OFFLINE_STYLE_CACHE_TTL_MS = 365L * 24L * 60L * 60L * 1000L
private const val OFFLINE_PROGRESS_LOG_INTERVAL_MS = 30_000L
private const val OFFLINE_PROGRESS_LOG_STEP_BYTES = 16L * 1024L * 1024L
private const val OFFLINE_PROGRESS_LOG_STEP_PERCENT = 10
private const val OFFLINE_RUNTIME_PREFS = "offline_region_runtime"
private const val KEY_ACTIVE_OFFLINE_REGION_ID = "active_offline_region_id"
private const val KEY_COMPLETED_OFFLINE_REGION_IDS = "completed_offline_region_ids"

data class MapCacheSnapshot(
    val configuredBytes: Long = 1024L * 1024L * 1024L,
    val usedBytes: Long = 0L,
    val clearingCache: Boolean = false,
    val cacheLastError: String? = null,
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
    val offlineDownloadProgressPrecise: Boolean = false,
    val offlineDownloadCompletedResources: Long = 0L,
    val offlineDownloadRequiredResources: Long = 0L,
    val offlineDownloadCompletedTiles: Long = 0L,
    val offlineDownloadCompletedBytes: Long = 0L,
    val offlineDownloadEstimatedLowBytes: Long = 0L,
    val offlineDownloadEstimatedHighBytes: Long = 0L,
    val offlineDownloadStartedAtMs: Long = 0L,
    val offlineDownloadUpdatedAtMs: Long = 0L,
    val offlineDownloadSpeedBytesPerSecond: Long = 0L,
    val offlineRegionReady: Boolean = false,
    val offlineLastError: String? = null,
)

private data class OfflineRegionMetadata(
    val regionId: String,
    val label: String,
    val providerId: String,
    val estimatedTileCount: Long,
    val createdAtMs: Long,
) {
    fun toBytes(): ByteArray {
        return JSONObject()
            .put("version", OFFLINE_METADATA_VERSION)
            .put("regionId", regionId)
            .put("label", label)
            .put("providerId", providerId)
            .put("estimatedTileCount", estimatedTileCount)
            .put("createdAtMs", createdAtMs)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }
}

private data class ManagedOfflineRegion(
    val region: OfflineRegion,
    val metadata: OfflineRegionMetadata,
)

private data class ManagedOfflineRegionStatus(
    val managed: ManagedOfflineRegion,
    val status: OfflineRegionStatus?,
)

private data class OfflineProgressLogState(
    val regionId: String,
    val lastLoggedAtMs: Long,
    val lastLoggedBytes: Long,
    val lastLoggedPercent: Int,
    val lastLoggedPrecise: Boolean,
)

object MapCacheController {
    private val listeners = CopyOnWriteArraySet<(MapCacheSnapshot) -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val offlineOperationCounter = AtomicLong(0L)

    @Volatile
    private var snapshot = MapCacheSnapshot()
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var ambientCacheSizeInitialized = false
    @Volatile
    private var activeOfflineRegion: OfflineRegion? = null
    @Volatile
    private var activeOfflineMetadata: OfflineRegionMetadata? = null
    @Volatile
    private var activeOfflineOperationToken: Long = 0L
    @Volatile
    private var offlineProgressLogState: OfflineProgressLogState? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
        MapRenderSettingsStore.initialize(context.applicationContext)
        syncFromSettings(MapRenderSettingsStore.current())
        MapRenderSettingsStore.addListener(::syncFromSettings)
        restoreOfflineRegions()
        refreshUsage()
    }

    fun current(): MapCacheSnapshot = snapshot

    fun addListener(listener: (MapCacheSnapshot) -> Unit) {
        listeners += listener
        listener(snapshot)
        refreshUsage()
    }

    fun removeListener(listener: (MapCacheSnapshot) -> Unit) {
        listeners -= listener
    }

    fun clearRouteDownloadCache() = Unit

    fun clearCache() {
        val context = appContext ?: return
        if (snapshot.clearingCache) return
        updateSnapshot {
            it.copy(
                clearingCache = true,
                cacheLastError = null,
            )
        }
        runOnMain {
            OfflineManager.getInstance(context).clearAmbientCache(object : OfflineManager.FileSourceCallback {
                override fun onSuccess() {
                    updateSnapshot {
                        it.copy(
                            clearingCache = false,
                            cacheLastError = null,
                        )
                    }
                    refreshUsage()
                }

                override fun onError(message: String) {
                    updateSnapshot {
                        it.copy(
                            clearingCache = false,
                            cacheLastError = message,
                        )
                    }
                }
            })
        }
    }

    fun refreshUsage() {
        val context = appContext ?: return
        worker.execute {
            val usedBytes = runCatching { computeCacheUsageBytes(context) }.getOrDefault(0L)
            updateSnapshot {
                it.copy(usedBytes = usedBytes)
            }
        }
    }

    fun startOfflineRegionDownload(entry: OfflineRegionEntry) {
        val context = appContext ?: return
        if (snapshot.offlineDownloadStatus != OFFLINE_STATUS_IDLE) {
            return
        }
        offlineProgressLogState = null
        val operationToken = offlineOperationCounter.incrementAndGet()
        activeOfflineOperationToken = operationToken
        rememberActiveOfflineRegionId(context, entry.id)
        forgetCompletedOfflineRegionId(context, entry.id)
        updateSnapshot {
            it.copy(
                offlineDownloadedLabel = entry.displayLabel,
                offlineDownloadedRegionId = entry.id,
                offlineDownloadStatus = OFFLINE_STATUS_RESOLVING,
                offlineDownloadPercent = 0,
                offlineDownloadProgressPrecise = false,
                offlineDownloadCompletedResources = 0L,
                offlineDownloadRequiredResources = 0L,
                offlineDownloadCompletedTiles = 0L,
                offlineDownloadCompletedBytes = 0L,
                offlineDownloadEstimatedLowBytes = 0L,
                offlineDownloadEstimatedHighBytes = 0L,
                offlineDownloadStartedAtMs = 0L,
                offlineDownloadUpdatedAtMs = 0L,
                offlineDownloadSpeedBytesPerSecond = 0L,
                offlineRegionReady = false,
                offlineLastError = null,
            )
        }
        worker.execute {
            runCatching {
                val resolved = GeoBoundariesRegionResolver.resolveCatalogEntry(context, entry)
                val geometry = Feature.fromJson(resolved.geometryJson).geometry()
                    ?: error("Не удалось подготовить геометрию региона")
                val provider = currentMapTileProvider(context)
                val estimatedTileCount = OfflineRegionSizeEstimator.estimateTileCount(
                    geometry,
                    resolved.bounds,
                    currentOfflineMaxZoom()
                )
                val metadata = OfflineRegionMetadata(
                    regionId = entry.id,
                    label = entry.displayLabel,
                    providerId = provider.id,
                    estimatedTileCount = estimatedTileCount,
                    createdAtMs = System.currentTimeMillis(),
                )
                val preparedStyle = prepareOfflineStyle(context, provider)
                PreparedOfflineRegion(
                    geometry = geometry,
                    metadata = metadata,
                    styleUrl = preparedStyle.url,
                    styleJson = preparedStyle.json,
                    geometrySourceHint = resolved.sourceHint,
                )
            }.onSuccess { prepared ->
                if (activeOfflineOperationToken != operationToken) return@onSuccess
                runOnMain {
                    if (activeOfflineOperationToken != operationToken) return@runOnMain
                    createOfflineRegion(context, prepared, operationToken)
                }
            }.onFailure { error ->
                if (activeOfflineOperationToken != operationToken) return@onFailure
                clearActiveOfflineRegionId(context, entry.id)
                updateSnapshot {
                    it.clearOfflineProgress(keepCurrentRegion = true).copy(
                        offlineDownloadedLabel = entry.displayLabel,
                        offlineDownloadedRegionId = entry.id,
                        offlineLastError = error.message ?: "Не удалось подготовить оффлайн-регион",
                    )
                }
                offlineProgressLogState = null
            }
        }
    }

    fun deleteOfflineRegion(regionId: String) {
        val context = appContext ?: return
        activeOfflineOperationToken = offlineOperationCounter.incrementAndGet()
        clearActiveOfflineRegionId(context, regionId)
        forgetCompletedOfflineRegionId(context, regionId)
        listManagedOfflineRegions(
            onSuccess = { regions ->
                val matching = regions.filter { it.metadata.regionId == regionId }
                if (matching.isEmpty()) {
                    if (snapshot.offlineDownloadedRegionId == regionId) {
                        updateSnapshot { it.clearOfflineProgress() }
                    }
                    refreshOfflineRegions()
                    return@listManagedOfflineRegions
                }
                deleteManagedOfflineRegions(
                    regionId = regionId,
                    regions = matching,
                    onComplete = {
                        refreshOfflineRegions()
                        refreshUsage()
                    },
                    onError = { message ->
                        updateSnapshot {
                            it.copy(
                                offlineDownloadedRegionId = regionId,
                                offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                                offlineLastError = message,
                            )
                        }
                    }
                )
            },
            onError = { message ->
                updateSnapshot {
                    it.copy(
                        offlineDownloadedRegionId = regionId,
                        offlineDownloadStatus = OFFLINE_STATUS_IDLE,
                        offlineLastError = message,
                    )
                }
            }
        )
    }

    private fun syncFromSettings(settings: MapRenderSettings) {
        val configuredBytes = settings.cacheSizeBytes()
        val shouldApplyCacheSize = !ambientCacheSizeInitialized || snapshot.configuredBytes != configuredBytes
        updateSnapshot {
            it.copy(
                configuredBytes = configuredBytes,
                downloadRouteEnabled = false,
                routeDownloadActive = false,
                routeDownloadStartedChunks = 0,
                routeDownloadCompletedChunks = 0,
                routeDownloadTotalChunks = 0,
                routeDownloadPercent = 0,
                offlineSelectionLabel = settings.offlineRegionId
            )
        }
        if (shouldApplyCacheSize) {
            applyConfiguredAmbientCacheSize(configuredBytes)
        }
    }

    private fun updateSnapshot(transform: (MapCacheSnapshot) -> MapCacheSnapshot): MapCacheSnapshot {
        val updated = synchronized(this) {
            transform(snapshot).also { snapshot = it }
        }
        listeners.forEach { it(updated) }
        return updated
    }

    private fun applyConfiguredAmbientCacheSize(maxBytes: Long) {
        val context = appContext ?: return
        ambientCacheSizeInitialized = true
        runOnMain {
            OfflineManager.getInstance(context).setMaximumAmbientCacheSize(
                maxBytes,
                object : OfflineManager.FileSourceCallback {
                    override fun onSuccess() {
                        updateSnapshot {
                            it.copy(cacheLastError = null)
                        }
                        refreshUsage()
                    }

                    override fun onError(message: String) {
                        ambientCacheSizeInitialized = false
                        updateSnapshot {
                            it.copy(cacheLastError = message)
                        }
                    }
                }
            )
        }
    }

    private fun restoreOfflineRegions() {
        refreshOfflineRegions()
    }

    private fun refreshOfflineRegions() {
        listManagedOfflineRegions(
            onSuccess = { regions ->
                if (regions.isEmpty()) {
                    activeOfflineRegion = null
                    activeOfflineMetadata = null
                    appContext?.let { clearActiveOfflineRegionId(it) }
                    appContext?.let(::clearAllCompletedOfflineRegionIds)
                    updateSnapshot {
                        var updated = it.copy(
                            offlineDownloadedRegionIds = emptySet(),
                            offlineDownloadedRegionSizesBytes = emptyMap(),
                        )
                        if (updated.offlineDownloadStatus == OFFLINE_STATUS_DOWNLOADING &&
                            updated.offlineLastError.isNullOrBlank()
                        ) {
                            updated = updated.clearOfflineProgress()
                        }
                        updated
                    }
                    return@listManagedOfflineRegions
                }

                val ordered = regions.sortedByDescending { it.metadata.createdAtMs }
                val pending = AtomicInteger(ordered.size)
                val regionStatuses = Collections.synchronizedList(mutableListOf<ManagedOfflineRegionStatus>())

                ordered.forEach { managed ->
                    managed.region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            regionStatuses += ManagedOfflineRegionStatus(managed = managed, status = status)
                            if (pending.decrementAndGet() == 0) {
                                finishOfflineRegionRefresh(regionStatuses.toList())
                            }
                        }

                        override fun onError(error: String?) {
                            regionStatuses += ManagedOfflineRegionStatus(managed = managed, status = null)
                            if (pending.decrementAndGet() == 0) {
                                finishOfflineRegionRefresh(regionStatuses.toList())
                            }
                        }
                    })
                }
            },
            onError = { message ->
                updateSnapshot {
                    it.copy(offlineLastError = message)
                }
            }
        )
    }

    private fun finishOfflineRegionRefresh(regionStatuses: List<ManagedOfflineRegionStatus>) {
        if (regionStatuses.isEmpty()) {
            activeOfflineRegion = null
            activeOfflineMetadata = null
            return
        }
        val context = appContext
        val rememberedActiveRegionId = context?.let(::rememberedActiveOfflineRegionId)
        val rememberedCompletedRegionIds = context?.let(::rememberedCompletedOfflineRegionIds).orEmpty()
        val orderedStatuses = regionStatuses.sortedByDescending { it.managed.metadata.createdAtMs }
        val completedIds = LinkedHashSet<String>()
        val completedSizes = LinkedHashMap<String, Long>()
        val logicalIncompleteCandidates = mutableListOf<ManagedOfflineRegionStatus>()
        val redundantRegions = LinkedHashSet<ManagedOfflineRegion>()

        orderedStatuses
            .groupBy { it.managed.metadata.regionId }
            .values
            .forEach { group ->
                val sortedGroup = group.sortedByDescending { it.managed.metadata.createdAtMs }
                val regionId = sortedGroup.firstOrNull()?.managed?.metadata?.regionId.orEmpty()
                val completedGroup = sortedGroup.filter { it.status?.let(::isRegionDownloadComplete) == true }
                if (completedGroup.isNotEmpty()) {
                    val kept = completedGroup.first()
                    completedIds += kept.managed.metadata.regionId
                    completedSizes[kept.managed.metadata.regionId] =
                        completedGroup.maxOf { it.status?.completedResourceSize ?: 0L }
                    context?.let { markOfflineRegionCompleted(it, kept.managed.metadata.regionId) }
                    sortedGroup
                        .filter { it.managed.region.id != kept.managed.region.id }
                        .forEach { redundantRegions += it.managed }
                } else if (regionId in rememberedCompletedRegionIds) {
                    val kept = sortedGroup.firstOrNull { it.status != null }
                    if (kept != null) {
                        completedIds += kept.managed.metadata.regionId
                        completedSizes[kept.managed.metadata.regionId] = kept.status?.completedResourceSize ?: 0L
                    }
                    sortedGroup
                        .drop(1)
                        .forEach { redundantRegions += it.managed }
                } else {
                    val kept = sortedGroup.firstOrNull { it.status != null }
                    if (kept != null && rememberedActiveRegionId == regionId) {
                        logicalIncompleteCandidates += kept
                    } else if (kept != null) {
                        redundantRegions += kept.managed
                    }
                    sortedGroup
                        .drop(1)
                        .forEach { redundantRegions += it.managed }
                }
            }

        val restoreCandidate = logicalIncompleteCandidates
            .sortedByDescending { it.managed.metadata.createdAtMs }
            .firstOrNull()
        logicalIncompleteCandidates
            .filter { candidate -> candidate.managed.region.id != restoreCandidate?.managed?.region?.id }
            .forEach { redundantRegions += it.managed }

        updateSnapshot {
            it.copy(
                offlineDownloadedRegionIds = completedIds,
                offlineDownloadedRegionSizesBytes = completedSizes,
            )
        }
        if (restoreCandidate == null || restoreCandidate.status == null) {
            if (snapshot.offlineDownloadStatus == OFFLINE_STATUS_DOWNLOADING &&
                activeOfflineMetadata?.regionId !in completedIds &&
                snapshot.offlineLastError.isNullOrBlank()
            ) {
                updateSnapshot { it.clearOfflineProgress() }
            }
            activeOfflineRegion = null
            activeOfflineMetadata = null
            if (rememberedActiveRegionId != null && rememberedActiveRegionId !in completedIds) {
                context?.let { clearActiveOfflineRegionId(it, rememberedActiveRegionId) }
            }
            if (redundantRegions.isNotEmpty()) {
                deleteRedundantOfflineRegions(redundantRegions.toList())
            }
            return
        }
        bindOfflineRegionObserver(restoreCandidate.managed.region, restoreCandidate.managed.metadata)
        applyOfflineStatus(restoreCandidate.managed.region, restoreCandidate.managed.metadata, restoreCandidate.status)
        restoreCandidate.managed.region.setDownloadState(OfflineRegion.STATE_ACTIVE)
        if (redundantRegions.isNotEmpty()) {
            deleteRedundantOfflineRegions(redundantRegions.toList())
        }
    }

    private fun createOfflineRegion(
        context: Context,
        prepared: PreparedOfflineRegion,
        operationToken: Long,
    ) {
        cacheOfflineStyleResource(
            context = context,
            styleUrl = prepared.styleUrl,
            styleJson = prepared.styleJson,
        )
        val definition = OfflineGeometryRegionDefinition(
            prepared.styleUrl,
            prepared.geometry,
            MAP_ZOOM_MIN,
            currentOfflineMaxZoom(),
            context.resources.displayMetrics.density,
            false
        )
        OfflineManager.getInstance(context).createOfflineRegion(
            definition,
            prepared.metadata.toBytes(),
            object : OfflineManager.CreateOfflineRegionCallback {
                override fun onCreate(offlineRegion: OfflineRegion) {
                    if (activeOfflineOperationToken != operationToken) {
                        clearActiveOfflineRegionId(context, prepared.metadata.regionId)
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                        offlineRegion.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                            override fun onDelete() = Unit
                            override fun onError(error: String) = Unit
                        })
                        return
                    }
                    Log.d(
                        MAP_CACHE_TAG,
                        "offline region created: regionId=${prepared.metadata.regionId} " +
                            "styleUrl=${prepared.styleUrl} geometrySource=${prepared.geometrySourceHint} " +
                            "estimatedTiles=${prepared.metadata.estimatedTileCount}"
                    )
                    bindOfflineRegionObserver(offlineRegion, prepared.metadata)
                    updateSnapshot {
                        it.copy(
                            offlineDownloadedLabel = prepared.metadata.label,
                            offlineDownloadedRegionId = prepared.metadata.regionId,
                            offlineDownloadStatus = OFFLINE_STATUS_DOWNLOADING,
                            offlineDownloadPercent = 0,
                            offlineDownloadProgressPrecise = false,
                            offlineDownloadCompletedResources = 0L,
                            offlineDownloadRequiredResources = 0L,
                            offlineDownloadCompletedTiles = 0L,
                            offlineDownloadCompletedBytes = 0L,
                            offlineDownloadEstimatedLowBytes = 0L,
                            offlineDownloadEstimatedHighBytes = 0L,
                            offlineDownloadStartedAtMs = 0L,
                            offlineDownloadUpdatedAtMs = 0L,
                            offlineDownloadSpeedBytesPerSecond = 0L,
                            offlineRegionReady = false,
                            offlineLastError = null,
                        )
                    }
                    offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
                }

                override fun onError(error: String) {
                    if (activeOfflineOperationToken != operationToken) return
                    Log.e(
                        MAP_CACHE_TAG,
                        "offline region create failed: regionId=${prepared.metadata.regionId} error=$error"
                    )
                    clearActiveOfflineRegionId(context, prepared.metadata.regionId)
                    updateSnapshot {
                        it.clearOfflineProgress(keepCurrentRegion = true).copy(
                            offlineDownloadedLabel = prepared.metadata.label,
                            offlineDownloadedRegionId = prepared.metadata.regionId,
                            offlineLastError = error,
                        )
                    }
                    offlineProgressLogState = null
                }
            }
        )
    }

    private fun bindOfflineRegionObserver(
        offlineRegion: OfflineRegion,
        metadata: OfflineRegionMetadata,
    ) {
        activeOfflineRegion = offlineRegion
        activeOfflineMetadata = metadata
        offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
            override fun onStatusChanged(status: OfflineRegionStatus) {
                if (!isActiveOfflineRegion(offlineRegion, metadata)) return
                applyOfflineStatus(offlineRegion, metadata, status)
            }

            override fun onError(error: org.maplibre.android.offline.OfflineRegionError) {
                if (!isActiveOfflineRegion(offlineRegion, metadata)) return
                Log.e(
                    MAP_CACHE_TAG,
                    "offline region observer error: regionId=${metadata.regionId} reason=${error.reason} message=${error.message}"
                )
                updateSnapshot {
                    it.copy(
                        offlineDownloadedLabel = metadata.label,
                        offlineDownloadedRegionId = metadata.regionId,
                        offlineLastError = error.message,
                    )
                }
            }

            override fun mapboxTileCountLimitExceeded(limit: Long) {
                if (!isActiveOfflineRegion(offlineRegion, metadata)) return
                Log.e(
                    MAP_CACHE_TAG,
                    "offline region tile limit exceeded: regionId=${metadata.regionId} limit=$limit"
                )
                activeOfflineRegion = null
                activeOfflineMetadata = null
                offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                updateSnapshot {
                    it.clearOfflineProgress(keepCurrentRegion = true).copy(
                        offlineDownloadedLabel = metadata.label,
                        offlineDownloadedRegionId = metadata.regionId,
                        offlineLastError = "Превышен лимит оффлайн-тайлов SDK: $limit",
                    )
                }
                (appContext ?: return).let { clearActiveOfflineRegionId(it, metadata.regionId) }
                offlineProgressLogState = null
            }
        })
    }

    private fun applyOfflineStatus(
        offlineRegion: OfflineRegion,
        metadata: OfflineRegionMetadata,
        status: OfflineRegionStatus,
    ) {
        val isComplete = isRegionDownloadComplete(status)
        if (!isComplete && metadata.regionId in snapshot.offlineDownloadedRegionIds) {
            return
        }
        val updatedSnapshot = updateSnapshot {
            val sameRegion = it.offlineDownloadedRegionId == metadata.regionId
            val previousBytes = if (sameRegion) it.offlineDownloadCompletedBytes else 0L
            val previousTiles = if (sameRegion) it.offlineDownloadCompletedTiles else 0L
            val completedBytes = maxOf(previousBytes, status.completedResourceSize)
            val completedTiles = maxOf(previousTiles, status.completedTileCount)
            val completedResources = if (status.isRequiredResourceCountPrecise) {
                maxOf(it.offlineDownloadCompletedResources, status.completedResourceCount)
            } else {
                0L
            }
            val requiredResources = if (status.isRequiredResourceCountPrecise) {
                maxOf(it.offlineDownloadRequiredResources, status.requiredResourceCount)
            } else {
                0L
            }
            val percent = computeOfflinePercent(
                isComplete = isComplete,
                precise = status.isRequiredResourceCountPrecise,
                completedResources = completedResources,
                requiredResources = requiredResources,
            )
            val currentIds = if (isComplete) {
                it.offlineDownloadedRegionIds + metadata.regionId
            } else {
                it.offlineDownloadedRegionIds
            }
            val currentSizes = if (isComplete && completedBytes > 0L) {
                it.offlineDownloadedRegionSizesBytes + (metadata.regionId to completedBytes)
            } else {
                it.offlineDownloadedRegionSizesBytes
            }
            it.copy(
                offlineDownloadedLabel = metadata.label,
                offlineDownloadedRegionId = metadata.regionId,
                offlineDownloadedRegionIds = currentIds,
                offlineDownloadedRegionSizesBytes = currentSizes,
                offlineDownloadStatus = if (isComplete) OFFLINE_STATUS_IDLE else OFFLINE_STATUS_DOWNLOADING,
                offlineDownloadPercent = percent,
                offlineDownloadProgressPrecise = status.isRequiredResourceCountPrecise,
                offlineDownloadCompletedResources = completedResources,
                offlineDownloadRequiredResources = requiredResources,
                offlineDownloadCompletedTiles = completedTiles,
                offlineDownloadCompletedBytes = completedBytes,
                offlineDownloadEstimatedLowBytes = 0L,
                offlineDownloadEstimatedHighBytes = 0L,
                offlineDownloadStartedAtMs = 0L,
                offlineDownloadUpdatedAtMs = 0L,
                offlineDownloadSpeedBytesPerSecond = 0L,
                offlineRegionReady = isComplete,
                offlineLastError = null,
            )
        }
        maybeLogOfflineProgress(updatedSnapshot, metadata, isComplete)
        if (!isComplete) return
        appContext?.let {
            clearActiveOfflineRegionId(it, metadata.regionId)
            markOfflineRegionCompleted(it, metadata.regionId)
        }
        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
        activeOfflineRegion = null
        activeOfflineMetadata = null
        offlineProgressLogState = null
        OfflineRegionSizeEstimator.calibrate(
            appContext ?: return,
            status.completedTileSize,
            status.completedTileCount,
        )
        refreshUsage()
        refreshOfflineRegions()
    }

    fun debugRefreshOfflineRegions() {
        refreshOfflineRegions()
    }

    fun debugDumpState(onResult: (String) -> Unit) {
        val snapshotNow = snapshot
        listManagedOfflineRegions(
            onSuccess = { regions ->
                if (regions.isEmpty()) {
                    onResult(buildDebugDump(snapshotNow, emptyList()))
                    return@listManagedOfflineRegions
                }
                val pending = AtomicInteger(regions.size)
                val statuses = Collections.synchronizedList(mutableListOf<ManagedOfflineRegionStatus>())
                regions.forEach { managed ->
                    managed.region.getStatus(object : OfflineRegion.OfflineRegionStatusCallback {
                        override fun onStatus(status: OfflineRegionStatus?) {
                            statuses += ManagedOfflineRegionStatus(managed = managed, status = status)
                            if (pending.decrementAndGet() == 0) {
                                onResult(buildDebugDump(snapshotNow, statuses.toList()))
                            }
                        }

                        override fun onError(error: String?) {
                            statuses += ManagedOfflineRegionStatus(managed = managed, status = null)
                            if (pending.decrementAndGet() == 0) {
                                onResult(buildDebugDump(snapshotNow, statuses.toList()))
                            }
                        }
                    })
                }
            },
            onError = { error ->
                onResult("offline_debug_error: $error\nsnapshot=${snapshotNow}")
            }
        )
    }

    fun debugPruneDuplicateRegions(onResult: (String) -> Unit) {
        listManagedOfflineRegions(
            onSuccess = { regions ->
                val redundant = regions
                    .groupBy { it.metadata.regionId }
                    .values
                    .flatMap { group ->
                        group.sortedByDescending { it.metadata.createdAtMs }.drop(1)
                    }
                if (redundant.isEmpty()) {
                    onResult("offline_debug_cleanup: no duplicates")
                    return@listManagedOfflineRegions
                }
                deleteRedundantOfflineRegions(redundant)
                onResult("offline_debug_cleanup: deleted=${redundant.size}")
            },
            onError = { error ->
                onResult("offline_debug_cleanup_error: $error")
            }
        )
    }

    private fun listManagedOfflineRegions(
        onSuccess: (List<ManagedOfflineRegion>) -> Unit,
        onError: (String) -> Unit,
    ) {
        val context = appContext ?: return
        runOnMain {
            OfflineManager.getInstance(context).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(offlineRegions: Array<OfflineRegion>?) {
                    val managed = offlineRegions
                        .orEmpty()
                        .mapNotNull { region ->
                            decodeOfflineMetadata(region.metadata)?.let { metadata ->
                                ManagedOfflineRegion(region = region, metadata = metadata)
                            }
                        }
                    onSuccess(managed)
                }

                override fun onError(error: String) {
                    onError(error)
                }
            })
        }
    }

    private fun deleteManagedOfflineRegions(
        regionId: String,
        regions: List<ManagedOfflineRegion>,
        onComplete: () -> Unit,
        onError: (String) -> Unit,
    ) {
        fun deleteAt(index: Int) {
            if (index >= regions.size) {
                if (snapshot.offlineDownloadedRegionId == regionId) {
                    updateSnapshot { it.clearOfflineProgress() }
                }
                onComplete()
                return
            }
            val managed = regions[index]
            if (isActiveOfflineRegion(managed.region, managed.metadata)) {
                activeOfflineRegion = null
                activeOfflineMetadata = null
            }
            managed.region.setObserver(null)
            managed.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            managed.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    deleteAt(index + 1)
                }

                override fun onError(error: String) {
                    onError(error)
                }
            })
        }

        deleteAt(0)
    }

    private fun deleteRedundantOfflineRegions(regions: List<ManagedOfflineRegion>) {
        fun deleteAt(index: Int) {
            if (index >= regions.size) {
                refreshUsage()
                return
            }
            val managed = regions[index]
            if (isActiveOfflineRegion(managed.region, managed.metadata)) {
                activeOfflineRegion = null
                activeOfflineMetadata = null
            }
            managed.region.setObserver(null)
            managed.region.setDownloadState(OfflineRegion.STATE_INACTIVE)
            managed.region.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                override fun onDelete() {
                    deleteAt(index + 1)
                }

                override fun onError(error: String) {
                    Log.e(
                        MAP_CACHE_TAG,
                        "failed to delete redundant offline region: id=${managed.metadata.regionId} error=$error"
                    )
                    deleteAt(index + 1)
                }
            })
        }

        deleteAt(0)
    }

    private fun decodeOfflineMetadata(bytes: ByteArray): OfflineRegionMetadata? {
        return runCatching {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            if (json.optInt("version", 0) != OFFLINE_METADATA_VERSION) {
                return null
            }
            val regionId = json.optString("regionId").takeIf { it.isNotBlank() } ?: return null
            OfflineRegionMetadata(
                regionId = regionId,
                label = json.optString("label", regionId),
                providerId = json.optString("providerId"),
                estimatedTileCount = json.optLong("estimatedTileCount", 0L).coerceAtLeast(0L),
                createdAtMs = json.optLong("createdAtMs", 0L).coerceAtLeast(0L),
            )
        }.getOrNull()
    }

    private fun runtimePrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(OFFLINE_RUNTIME_PREFS, Context.MODE_PRIVATE)
    }

    private fun rememberedActiveOfflineRegionId(context: Context): String? {
        return runtimePrefs(context).getString(KEY_ACTIVE_OFFLINE_REGION_ID, null)?.takeIf { it.isNotBlank() }
    }

    private fun rememberActiveOfflineRegionId(context: Context, regionId: String) {
        runtimePrefs(context).edit().putString(KEY_ACTIVE_OFFLINE_REGION_ID, regionId).apply()
    }

    private fun clearActiveOfflineRegionId(context: Context, regionId: String? = null) {
        val prefs = runtimePrefs(context)
        val remembered = prefs.getString(KEY_ACTIVE_OFFLINE_REGION_ID, null)
        if (regionId != null && remembered != regionId) return
        prefs.edit().remove(KEY_ACTIVE_OFFLINE_REGION_ID).apply()
    }

    private fun rememberedCompletedOfflineRegionIds(context: Context): Set<String> {
        return runtimePrefs(context)
            .getStringSet(KEY_COMPLETED_OFFLINE_REGION_IDS, emptySet())
            .orEmpty()
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun markOfflineRegionCompleted(context: Context, regionId: String) {
        val prefs = runtimePrefs(context)
        val updated = rememberedCompletedOfflineRegionIds(context).toMutableSet()
        updated += regionId
        prefs.edit().putStringSet(KEY_COMPLETED_OFFLINE_REGION_IDS, updated).apply()
    }

    private fun forgetCompletedOfflineRegionId(context: Context, regionId: String) {
        val prefs = runtimePrefs(context)
        val updated = rememberedCompletedOfflineRegionIds(context).toMutableSet()
        if (!updated.remove(regionId)) return
        prefs.edit().putStringSet(KEY_COMPLETED_OFFLINE_REGION_IDS, updated).apply()
    }

    private fun clearAllCompletedOfflineRegionIds(context: Context) {
        runtimePrefs(context).edit().remove(KEY_COMPLETED_OFFLINE_REGION_IDS).apply()
    }

    private fun prepareOfflineStyle(
        context: Context,
        provider: MapTileProvider,
    ): PreparedOfflineStyle {
        val settings = MapRenderSettingsStore.current()
        val styleJson = prepareHudMapStyleJson(
            templateJson = MapStyleTemplateStore.loadTemplateJson(context),
            provider = provider,
            settings = settings,
        )
        val styleHash = styleJson.hashCode().toUInt().toString(16)
        return PreparedOfflineStyle(
            url = "$OFFLINE_STYLE_CACHE_URL_BASE/offline_style_${provider.id}_$styleHash.json",
            json = styleJson,
        )
    }

    private fun cacheOfflineStyleResource(
        context: Context,
        styleUrl: String,
        styleJson: String,
    ) {
        val now = System.currentTimeMillis()
        OfflineManager.getInstance(context).putResourceWithUrl(
            styleUrl,
            styleJson.toByteArray(Charsets.UTF_8),
            now,
            now + OFFLINE_STYLE_CACHE_TTL_MS,
            "\"${styleJson.hashCode().toUInt().toString(16)}\"",
            false,
        )
    }

    private fun isActiveOfflineRegion(
        offlineRegion: OfflineRegion,
        metadata: OfflineRegionMetadata,
    ): Boolean {
        return activeOfflineRegion?.id == offlineRegion.id &&
            activeOfflineMetadata?.regionId == metadata.regionId
    }

    private fun currentOfflineMaxZoom(): Double {
        val settings = MapRenderSettingsStore.current()
        return if (settings.autoZoomEnabled) {
            maxOf(settings.autoZoomAt0Kmh, settings.autoZoomAt60Kmh, settings.autoZoomAt90Kmh)
        } else {
            settings.zoom
        }.coerceIn(MAP_ZOOM_MIN, OFFLINE_REGION_MAX_ZOOM)
    }

    private fun isRegionDownloadComplete(status: OfflineRegionStatus): Boolean {
        if (!status.isRequiredResourceCountPrecise) return false
        if (status.requiredResourceCount <= 0L) return false
        return status.completedResourceCount >= status.requiredResourceCount
    }

    private fun computeOfflinePercent(
        isComplete: Boolean,
        precise: Boolean,
        completedResources: Long,
        requiredResources: Long,
    ): Int {
        if (isComplete) return 100
        if (precise && requiredResources > 0L) {
            return ((completedResources * 100L) / requiredResources)
                .toInt()
                .coerceIn(0, 99)
        }
        return 0
    }

    private fun maybeLogOfflineProgress(
        snapshot: MapCacheSnapshot,
        metadata: OfflineRegionMetadata,
        isComplete: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val previous = offlineProgressLogState
        val shouldLog = previous == null ||
            previous.regionId != metadata.regionId ||
            isComplete ||
            snapshot.offlineDownloadProgressPrecise != previous.lastLoggedPrecise ||
            snapshot.offlineDownloadCompletedBytes >= previous.lastLoggedBytes + OFFLINE_PROGRESS_LOG_STEP_BYTES ||
            (snapshot.offlineDownloadProgressPrecise &&
                snapshot.offlineDownloadPercent >= previous.lastLoggedPercent + OFFLINE_PROGRESS_LOG_STEP_PERCENT) ||
            now - previous.lastLoggedAtMs >= OFFLINE_PROGRESS_LOG_INTERVAL_MS
        if (!shouldLog) return

        val phase = when {
            isComplete -> "complete"
            snapshot.offlineDownloadProgressPrecise -> "download"
            snapshot.offlineDownloadCompletedBytes > 0L -> "package"
            else -> "prepare"
        }
        val parts = mutableListOf(
            "offline progress",
            "regionId=${metadata.regionId}",
            "phase=$phase",
            "downloaded=${formatProgressBytes(snapshot.offlineDownloadCompletedBytes)}",
        )
        if (snapshot.offlineDownloadProgressPrecise) {
            parts += "progress=${snapshot.offlineDownloadPercent}%"
            parts += "resources=${snapshot.offlineDownloadCompletedResources}/${snapshot.offlineDownloadRequiredResources}"
        }
        if (snapshot.offlineDownloadCompletedTiles > 0L) {
            parts += "tiles=${snapshot.offlineDownloadCompletedTiles}"
        }
        Log.d(MAP_CACHE_TAG, parts.joinToString(" "))
        offlineProgressLogState = OfflineProgressLogState(
            regionId = metadata.regionId,
            lastLoggedAtMs = now,
            lastLoggedBytes = snapshot.offlineDownloadCompletedBytes,
            lastLoggedPercent = snapshot.offlineDownloadPercent,
            lastLoggedPrecise = snapshot.offlineDownloadProgressPrecise,
        )
    }

    private fun formatProgressBytes(bytes: Long): String {
        val kb = 1024L
        val mb = kb * 1024L
        val gb = mb * 1024L
        return when {
            bytes >= gb -> String.format("%.1fGB", bytes.toDouble() / gb.toDouble())
            bytes >= mb -> String.format("%.0fMB", bytes.toDouble() / mb.toDouble())
            bytes >= kb -> String.format("%.0fKB", bytes.toDouble() / kb.toDouble())
            else -> "${bytes}B"
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun computeCacheUsageBytes(context: Context): Long {
        val cachePath = FileSource.getResourcesCachePath(context).orEmpty()
        if (cachePath.isBlank()) return 0L
        return File(cachePath).directorySizeBytes()
    }

    private fun File.directorySizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return listFiles()?.sumOf { child -> child.directorySizeBytes() } ?: 0L
    }
}

private data class PreparedOfflineRegion(
    val geometry: org.maplibre.geojson.Geometry,
    val metadata: OfflineRegionMetadata,
    val styleUrl: String,
    val styleJson: String,
    val geometrySourceHint: String,
)

private data class PreparedOfflineStyle(
    val url: String,
    val json: String,
)

private fun MapCacheSnapshot.clearOfflineProgress(
    keepCurrentRegion: Boolean = false,
): MapCacheSnapshot {
    return copy(
        offlineDownloadedLabel = if (keepCurrentRegion) offlineDownloadedLabel else null,
        offlineDownloadedRegionId = if (keepCurrentRegion) offlineDownloadedRegionId else null,
        offlineDownloadStatus = OFFLINE_STATUS_IDLE,
        offlineDownloadPercent = 0,
        offlineDownloadProgressPrecise = false,
        offlineDownloadCompletedResources = 0L,
        offlineDownloadRequiredResources = 0L,
        offlineDownloadCompletedTiles = 0L,
        offlineDownloadCompletedBytes = 0L,
        offlineDownloadEstimatedLowBytes = 0L,
        offlineDownloadEstimatedHighBytes = 0L,
        offlineDownloadStartedAtMs = 0L,
        offlineDownloadUpdatedAtMs = 0L,
        offlineDownloadSpeedBytesPerSecond = 0L,
        offlineRegionReady = false,
        offlineLastError = null,
    )
}

private fun buildDebugDump(
    snapshot: MapCacheSnapshot,
    statuses: List<ManagedOfflineRegionStatus>,
): String {
    val lines = mutableListOf<String>()
    lines += "snapshot status=${snapshot.offlineDownloadStatus} percent=${snapshot.offlineDownloadPercent} " +
        "precise=${snapshot.offlineDownloadProgressPrecise} downloaded=${snapshot.offlineDownloadCompletedBytes} " +
        "region=${snapshot.offlineDownloadedRegionId.orEmpty()} " +
        "ready=${snapshot.offlineRegionReady} error=${snapshot.offlineLastError.orEmpty()}"
    if (statuses.isEmpty()) {
        lines += "regions: none"
        return lines.joinToString("\n")
    }
    lines += "regions: ${statuses.size}"
    statuses
        .sortedWith(compareBy<ManagedOfflineRegionStatus>({ it.managed.metadata.regionId }, { -it.managed.metadata.createdAtMs }))
        .forEach { item ->
            val status = item.status
            lines += buildString {
                append("regionId=${item.managed.metadata.regionId}")
                append(" createdAt=${item.managed.metadata.createdAtMs}")
                append(" provider=${item.managed.metadata.providerId}")
                append(" estimatedTiles=${item.managed.metadata.estimatedTileCount}")
                append(" sdkRegionId=${item.managed.region.id}")
                if (status == null) {
                    append(" status=unavailable")
                } else {
                    append(" precise=${status.isRequiredResourceCountPrecise}")
                    append(
                        " complete=${
                            status.isRequiredResourceCountPrecise &&
                                status.requiredResourceCount > 0L &&
                                status.completedResourceCount >= status.requiredResourceCount
                        }"
                    )
                    append(" resources=${status.completedResourceCount}/${status.requiredResourceCount}")
                    append(" resourceBytes=${status.completedResourceSize}")
                    append(" tiles=${status.completedTileCount}")
                    append(" tileBytes=${status.completedTileSize}")
                    append(" state=${status.downloadState}")
                }
            }
        }
    return lines.joinToString("\n")
}
