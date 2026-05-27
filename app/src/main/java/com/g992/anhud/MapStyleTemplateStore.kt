package com.g992.anhud

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

const val MAP_STYLE_ASSET_PATH = "styles/hud_minimal.json"
const val MAP_STYLE_SOURCE_MAX_ZOOM = 14

private const val MAP_STYLE_TEMPLATE_DIR = "map_styles"
private const val MAP_STYLE_TEMPLATE_FILE_PREFIX = "hud_minimal_downloaded_"
private const val MAP_STYLE_TEMPLATE_TAG = "MapStyleTemplateStore"
private const val MAP_STYLE_TEMPLATE_TIMEOUT_MS = 15_000
private const val MAP_STYLE_TEMPLATE_USER_AGENT = "ANHUD-MapStyle/1.0"
private val MAP_STYLE_TEMPLATE_RETRY_DELAYS_MS = longArrayOf(60_000L, 180_000L, 300_000L)
private const val MAP_STYLE_TEMPLATE_MAX_ATTEMPTS = 4
private const val BUILDING_EXTRUSION_OPACITY = 0.4
private const val STARLINE_STYLE_TOKEN_PLACEHOLDER = "__STARLINE_ACCESS_TOKEN__"
private const val OPEN_FREE_MAP_TILEJSON_URL = "https://tiles.openfreemap.org/planet"
private const val OPEN_FREE_MAP_PROXY_TILEJSON_URL = "https://ofmproxy.ragdesign.ru/planet"

object MapStyleTemplateStore {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initialized = false
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var scheduledRetry: Runnable? = null

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
            scheduleRefreshAttempt(attemptIndex = 0, delayMs = 0L)
        }
    }

    fun loadTemplateJson(context: Context): String {
        loadCustomTemplateJson()?.let { return it }
        return loadSystemTemplateJson(context)
    }

    fun loadSystemTemplateJson(context: Context): String {
        val downloadedFile = downloadedTemplateFile(context.applicationContext)
        if (downloadedFile.isFile) {
            val downloadedJson = runCatching {
                downloadedFile.readText(Charsets.UTF_8).also { JSONObject(it) }
            }.getOrNull()
            if (!downloadedJson.isNullOrBlank()) {
                return downloadedJson
            }
            downloadedFile.delete()
        }
        return context.assets.open(MAP_STYLE_ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun scheduleRefreshAttempt(attemptIndex: Int, delayMs: Long) {
        if (!hasStarLineStyleConfig()) return
        scheduledRetry?.let(mainHandler::removeCallbacks)
        val runnable = Runnable {
            worker.execute {
                refreshTemplate(attemptIndex)
            }
        }
        scheduledRetry = runnable
        if (delayMs <= 0L) {
            runnable.run()
        } else {
            mainHandler.postDelayed(runnable, delayMs)
        }
    }

    private fun refreshTemplate(attemptIndex: Int) {
        val context = appContext ?: return
        runCatching {
            downloadStarLineStyleTemplate()
        }.onSuccess { rawJson ->
            val normalizedJson = normalizeDownloadedTemplateJson(rawJson)
            val targetFile = downloadedTemplateFile(context)
            val previousJson = targetFile.takeIf { it.isFile }?.readText(Charsets.UTF_8)
            if (previousJson != normalizedJson) {
                writeTextAtomically(targetFile, normalizedJson)
                listeners.forEach { it() }
            }
            scheduledRetry = null
            Log.d(MAP_STYLE_TEMPLATE_TAG, "style template refreshed")
        }.onFailure { error ->
            if (attemptIndex + 1 >= MAP_STYLE_TEMPLATE_MAX_ATTEMPTS) {
                scheduledRetry = null
                Log.w(
                    MAP_STYLE_TEMPLATE_TAG,
                    "style template refresh failed after ${attemptIndex + 1} attempts: ${error.message}"
                )
                return
            }
            val retryDelayMs = MAP_STYLE_TEMPLATE_RETRY_DELAYS_MS
                .getOrElse(attemptIndex) { MAP_STYLE_TEMPLATE_RETRY_DELAYS_MS.last() }
            Log.w(
                MAP_STYLE_TEMPLATE_TAG,
                "style template refresh failed on attempt ${attemptIndex + 1}, retry in ${retryDelayMs / 1000}s: ${error.message}"
            )
            scheduleRefreshAttempt(attemptIndex + 1, retryDelayMs)
        }
    }

    private fun downloadStarLineStyleTemplate(): String {
        val connection = (URL(buildStarLineStyleFetchUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = MAP_STYLE_TEMPLATE_TIMEOUT_MS
            readTimeout = MAP_STYLE_TEMPLATE_TIMEOUT_MS
            setRequestProperty("User-Agent", MAP_STYLE_TEMPLATE_USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("StarLine style HTTP ${connection.responseCode}: ${body.ifBlank { connection.responseMessage ?: "unknown error" }}")
            }
            stream.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().also { JSONObject(it) }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadedTemplateFile(context: Context): File {
        val dir = File(context.filesDir, MAP_STYLE_TEMPLATE_DIR).apply { mkdirs() }
        return File(dir, "$MAP_STYLE_TEMPLATE_FILE_PREFIX${currentStyleTemplateCacheKey()}.json")
    }

    private fun hasStarLineStyleConfig(): Boolean {
        return BuildConfig.STARLINE_MAP_STYLE_ID.isNotBlank() &&
            BuildConfig.STARLINE_MAPS_ACCESS_TOKEN.isNotBlank()
    }

    private fun writeTextAtomically(targetFile: File, text: String) {
        targetFile.parentFile?.mkdirs()
        val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
        tempFile.writeText(text, Charsets.UTF_8)
        if (targetFile.exists() && !targetFile.delete()) {
            error("Не удалось обновить шаблон стиля")
        }
        if (!tempFile.renameTo(targetFile)) {
            error("Не удалось сохранить шаблон стиля")
        }
    }

    private fun loadCustomTemplateJson(): String? {
        val settings = MapRenderSettingsStore.current()
        if (settings.effectiveMapStyleMode() != MapStyleMode.USER) {
            return null
        }
        val rawJson = settings.customStyleJson ?: return null
        return runCatching {
            validateCustomMapStyleJson(rawJson)
        }.onFailure { error ->
            Log.w(MAP_STYLE_TEMPLATE_TAG, "custom style template is invalid, fallback to system: ${error.message}")
        }.getOrNull()
    }
}

internal fun validateCustomMapStyleJson(rawJson: String): String {
    val normalized = rawJson.trim().trimStart('\uFEFF')
    require(normalized.isNotBlank()) { "файл пуст" }
    val style = JSONObject(normalized)
    require(style.optInt("version", -1) == 8) { "ожидалась версия style spec 8" }
    require(style.optJSONObject("sources") != null) { "не найден блок sources" }
    require(style.optJSONArray("layers") != null) { "не найден массив layers" }
    return style.toString()
}

internal fun normalizeDownloadedTemplateJson(rawJson: String): String {
    val style = JSONObject(rawJson)
    val sources = style.optJSONObject("sources") ?: JSONObject().also { style.put("sources", it) }
    val keys = mutableListOf<String>()
    val iterator = sources.keys()
    while (iterator.hasNext()) {
        keys += iterator.next()
    }
    keys.forEach { sourceName ->
        val source = sources.optJSONObject(sourceName) ?: return@forEach
        if (source.optString("type", "vector") != "vector") return@forEach
        source.remove("url")
        source.put(
            "tiles",
            JSONArray().put(buildSanitizedStarLineTilesUrl(sourceName, source))
        )
        source.put("maxzoom", MAP_STYLE_SOURCE_MAX_ZOOM)
        sources.put(sourceName, source)
    }
    style.put("sources", sources)
    return style.toString(2)
}

internal fun prepareHudMapStyleJson(
    templateJson: String,
    provider: MapTileProvider,
    settings: MapRenderSettings,
): String {
    val style = JSONObject(templateJson)
    val sources = style.optJSONObject("sources") ?: JSONObject().also { style.put("sources", it) }
    val keys = mutableListOf<String>()
    val iterator = sources.keys()
    while (iterator.hasNext()) {
        keys += iterator.next()
    }
    keys.forEach { sourceName ->
        val source = sources.optJSONObject(sourceName) ?: return@forEach
        if (source.optString("type", "vector") != "vector") return@forEach
        rewriteVectorSource(sourceName, source, provider)
        sources.put(sourceName, source)
    }
    style.put("sources", sources)
    applyBuildingVisibility(style.optJSONArray("layers"), settings)
    return style.toString()
}

private fun applyBuildingVisibility(
    layers: JSONArray?,
    settings: MapRenderSettings,
) {
    if (layers == null) return
    for (index in 0 until layers.length()) {
        val layer = layers.optJSONObject(index) ?: continue
        val type = layer.optString("type")
        if (!isBuildingGeometryLayer(layer, type)) continue
        val visibility = when (type) {
            "fill-extrusion" -> if (settings.are3dBuildingsVisible()) "visible" else "none"
            else -> if (settings.buildingsEnabled && !settings.are3dBuildingsVisible()) "visible" else "none"
        }
        val layout = layer.optJSONObject("layout") ?: JSONObject()
        layout.put("visibility", visibility)
        layer.put("layout", layout)
        if (type == "fill-extrusion") {
            val paint = layer.optJSONObject("paint") ?: JSONObject()
            paint.put("fill-extrusion-opacity", BUILDING_EXTRUSION_OPACITY)
            layer.put("paint", paint)
        }
    }
}

private fun isBuildingGeometryLayer(layer: JSONObject, type: String): Boolean {
    if (type != "fill" && type != "line" && type != "fill-extrusion") {
        return false
    }
    if (layer.optString("source-layer") == "building") {
        return true
    }
    return layer.optJSONObject("metadata")?.optString("taxonomy:group") == "building"
}

private fun rewriteVectorSource(
    sourceName: String,
    source: JSONObject,
    provider: MapTileProvider,
) {
    source.put("type", "vector")
    source.put("maxzoom", MAP_STYLE_SOURCE_MAX_ZOOM)
    when (provider) {
        MapTileProvider.OPEN_FREE_MAP -> {
            source.remove("tiles")
            source.put("url", OPEN_FREE_MAP_TILEJSON_URL)
        }

        MapTileProvider.OPEN_FREE_MAP_PROXY -> {
            source.remove("tiles")
            source.put("url", OPEN_FREE_MAP_PROXY_TILEJSON_URL)
        }
    }
}

private fun buildSanitizedStarLineTilesUrl(sourceName: String, source: JSONObject): String {
    val type = resolveStarLineTilesType(sourceName, source)
    return "https://maps.starline.ru/api/tiles/$type/{z}/{x}/{y}.pbf?accessToken=$STARLINE_STYLE_TOKEN_PLACEHOLDER"
}

private fun buildConfiguredStarLineTilesUrl(sourceName: String, source: JSONObject): String {
    val type = resolveStarLineTilesType(sourceName, source)
    return "https://maps.starline.ru/api/tiles/$type/{z}/{x}/{y}.pbf?accessToken=${BuildConfig.STARLINE_MAPS_ACCESS_TOKEN}"
}

private fun resolveStarLineTilesType(sourceName: String, source: JSONObject): String {
    if (sourceName.equals("poi", ignoreCase = true)) {
        return "poi"
    }
    val firstTileUrl = source.optJSONArray("tiles")?.optString(0).orEmpty()
    return if ("/poi/" in firstTileUrl) "poi" else "base"
}

private fun currentStyleTemplateCacheKey(): String {
    val raw = BuildConfig.STARLINE_MAP_STYLE_ID.trim()
    if (raw.isEmpty()) return "default"
    return raw.map { ch ->
        if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '_'
    }.joinToString("")
}
