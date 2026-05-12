package com.g992.anhud

import android.content.Context
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.storage.FileSource
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

const val OFFLINE_STATUS_IDLE = 0
const val OFFLINE_STATUS_RESOLVING = 1
const val OFFLINE_STATUS_DOWNLOADING = 2

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
    val offlineDownloadCompletedResources: Long = 0L,
    val offlineDownloadRequiredResources: Long = 0L,
    val offlineDownloadCompletedBytes: Long = 0L,
    val offlineRegionReady: Boolean = false,
    val offlineLastError: String? = null,
)

object MapCacheController {
    private val listeners = CopyOnWriteArraySet<(MapCacheSnapshot) -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()

    @Volatile
    private var snapshot = MapCacheSnapshot()
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var ambientCacheSizeInitialized = false

    fun initialize(context: Context) {
        appContext = context.applicationContext
        MapRenderSettingsStore.initialize(context.applicationContext)
        syncFromSettings(MapRenderSettingsStore.current())
        MapRenderSettingsStore.addListener(::syncFromSettings)
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
        updateSnapshot {
            it.copy(
                clearingCache = true,
                cacheLastError = null,
            )
        }
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
        updateSnapshot {
            it.copy(
                offlineDownloadedLabel = entry.displayLabel,
                offlineDownloadedRegionId = entry.id,
                offlineLastError = "Оффлайн-загрузка старого тайлового кэша временно отключена для MapLibre"
            )
        }
    }

    fun deleteOfflineRegion(regionId: String) {
        if (snapshot.offlineDownloadedRegionId == regionId) {
            updateSnapshot {
                it.copy(
                    offlineDownloadedLabel = null,
                    offlineDownloadedRegionId = null,
                    offlineLastError = null
                )
            }
        }
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

    private fun updateSnapshot(transform: (MapCacheSnapshot) -> MapCacheSnapshot) {
        val updated = synchronized(this) {
            transform(snapshot).also { snapshot = it }
        }
        listeners.forEach { it(updated) }
    }

    private fun applyConfiguredAmbientCacheSize(maxBytes: Long) {
        val context = appContext ?: return
        ambientCacheSizeInitialized = true
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
