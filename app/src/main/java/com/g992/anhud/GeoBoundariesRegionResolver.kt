package com.g992.anhud

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.PushbackReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val GEOBOUNDARIES_API_BASE = "https://www.geoboundaries.org/api/current"
private const val GEOBOUNDARIES_PRODUCT = "gbOpen"
private const val GEOBOUNDARIES_TIMEOUT_MS = 60_000
private const val GEOBOUNDARIES_EXACT_PREFERRED_TIMEOUT_MS = 200
private const val GEOBOUNDARIES_USER_AGENT = "ANHUD/1.0"
private const val GEOBOUNDARIES_GEOMETRY_CACHE_DIR = "offline_region_geometry"

data class ResolvedOfflineGeometry(
    val label: String,
    val regionId: String,
    val bounds: OfflineRegionBounds,
    val geometryJson: String,
    val sourceHint: String,
)

object GeoBoundariesRegionResolver {
    private val exactLookupExecutor = Executors.newCachedThreadPool()

    fun resolveSelected(context: Context, settings: MapRenderSettings): ResolvedOfflineGeometry {
        settings.manualOfflineBoundsOrNull()?.let { manual ->
            return ResolvedOfflineGeometry(
                label = manual.label,
                regionId = manual.offlineRegionId(),
                bounds = manual.boundsPreview(),
                geometryJson = manual.toPolygonFeatureJson(),
                sourceHint = "manual",
            )
        }
        val entry = OfflineRegionCatalog.findById(context, settings.offlineRegionId)
            ?: error("Оффлайн-регион не выбран")
        return resolveCatalogEntry(context, entry)
    }

    fun resolveCatalogEntry(context: Context, entry: OfflineRegionEntry): ResolvedOfflineGeometry {
        cachedExactGeometry(context, entry)?.let { return it }
        if (entry.bundledGeometryAvailable) {
            resolveExactWithinBudget(context, entry, GEOBOUNDARIES_EXACT_PREFERRED_TIMEOUT_MS)?.let { exact ->
                return exact
            }
            BundledOfflineGeometryStore.resolveCatalogEntry(context, entry)?.let { bundled ->
                return bundled
            }
        }
        return resolveExactCatalogEntry(context, entry, GEOBOUNDARIES_TIMEOUT_MS)
    }

    private fun resolveExactCatalogEntry(
        context: Context,
        entry: OfflineRegionEntry,
        timeoutMs: Int,
    ): ResolvedOfflineGeometry {
        val metadataUrl = "$GEOBOUNDARIES_API_BASE/$GEOBOUNDARIES_PRODUCT/${entry.countryIso3}/${entry.level}/"
        val metadata = JSONObject(httpGetText(metadataUrl, timeoutMs))
        val geometryUrl = metadata.optString("simplifiedGeometryGeoJSON")
            .ifBlank { metadata.optString("gjDownloadURL") }
            .ifBlank { error("geoBoundaries did not return a GeoJSON URL") }
        val sourceFeatureJson = findFeatureJson(geometryUrl, entry, timeoutMs)
            ?: error("Не удалось найти границы для ${entry.displayLabel}")
        val geometry = JSONObject(sourceFeatureJson).optJSONObject("geometry")
            ?: error("geoBoundaries returned empty geometry")
        val featureJson = JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", geometry)
            .toString()
        exactGeometryCacheFile(context, entry).writeText(featureJson, Charsets.UTF_8)
        return ResolvedOfflineGeometry(
            label = entry.displayLabel,
            regionId = entry.id,
            bounds = entry.boundsPreview(),
            geometryJson = featureJson,
            sourceHint = "geoboundaries_exact",
        )
    }

    private fun resolveExactWithinBudget(
        context: Context,
        entry: OfflineRegionEntry,
        timeoutMs: Int,
    ): ResolvedOfflineGeometry? {
        val future = exactLookupExecutor.submit(
            Callable {
                resolveExactCatalogEntry(context, entry, timeoutMs)
            }
        )
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            future.cancel(true)
            null
        }
    }

    private fun cachedExactGeometry(context: Context, entry: OfflineRegionEntry): ResolvedOfflineGeometry? {
        val cacheFile = exactGeometryCacheFile(context, entry)
        if (!cacheFile.isFile) return null
        return ResolvedOfflineGeometry(
            label = entry.displayLabel,
            regionId = entry.id,
            bounds = entry.boundsPreview(),
            geometryJson = cacheFile.readText(Charsets.UTF_8),
            sourceHint = "cached_exact",
        )
    }

    private fun exactGeometryCacheFile(context: Context, entry: OfflineRegionEntry): File {
        return File(
            File(context.filesDir, GEOBOUNDARIES_GEOMETRY_CACHE_DIR).apply { mkdirs() },
            "${entry.id}.geojson"
        )
    }

    private fun findFeatureJson(url: String, entry: OfflineRegionEntry, timeoutMs: Int): String? {
        return withHttpReader(url, timeoutMs) { reader ->
            if (!skipToFeaturesArray(reader)) {
                error("geoBoundaries GeoJSON does not contain features[]")
            }
            while (true) {
                when (val token = readNextNonWhitespace(reader)) {
                    -1 -> return@withHttpReader null
                    ']'.code -> return@withHttpReader null
                    ','.code -> Unit
                    '{'.code -> {
                        val featureJson = readJsonObject(reader)
                        if (matchesEntry(featureJson, entry)) {
                            return@withHttpReader featureJson
                        }
                    }
                    else -> error("Unexpected GeoJSON token '${token.toChar()}' while reading features[]")
                }
            }
            null
        }
    }

    private fun matchesEntry(featureJson: String, entry: OfflineRegionEntry): Boolean {
        if (hasJsonStringProperty(featureJson, "shapeID", entry.id)) return true
        if (!entry.shapeISO.isNullOrBlank() &&
            hasJsonStringProperty(featureJson, "shapeISO", entry.shapeISO)
        ) {
            return true
        }
        return hasJsonStringProperty(featureJson, "shapeName", entry.name, ignoreCase = true)
    }

    private fun httpGetText(url: String, timeoutMs: Int): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", GEOBOUNDARIES_USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("geoBoundaries HTTP ${connection.responseCode}: ${body.ifBlank { connection.responseMessage ?: "unknown error" }}")
            }
            stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun <T> withHttpReader(url: String, timeoutMs: Int, block: (PushbackReader) -> T): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            setRequestProperty("User-Agent", GEOBOUNDARIES_USER_AGENT)
            setRequestProperty("Accept", "application/geo+json, application/json")
        }
        return try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                error("geoBoundaries HTTP ${connection.responseCode}: ${body.ifBlank { connection.responseMessage ?: "unknown error" }}")
            }
            PushbackReader(InputStreamReader(stream, Charsets.UTF_8), 2).use(block)
        } finally {
            connection.disconnect()
        }
    }

    private fun skipToFeaturesArray(reader: PushbackReader): Boolean {
        while (true) {
            when (reader.read()) {
                -1 -> return false
                '"'.code -> {
                    if (readJsonStringContent(reader) == "features") {
                        if (readNextNonWhitespace(reader) != ':'.code) continue
                        if (readNextNonWhitespace(reader) == '['.code) return true
                    }
                }
            }
        }
    }

    private fun readJsonObject(reader: PushbackReader): String {
        val builder = StringBuilder().append('{')
        var depth = 1
        var inString = false
        var escaped = false
        while (depth > 0) {
            val next = reader.read()
            if (next == -1) error("Unexpected end of GeoJSON object")
            val char = next.toChar()
            builder.append(char)
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
            } else {
                when (char) {
                    '"' -> inString = true
                    '{' -> depth += 1
                    '}' -> depth -= 1
                }
            }
        }
        return builder.toString()
    }

    private fun readJsonStringContent(reader: PushbackReader): String {
        val builder = StringBuilder()
        var escaped = false
        while (true) {
            val next = reader.read()
            if (next == -1) error("Unexpected end of GeoJSON string")
            val char = next.toChar()
            if (escaped) {
                builder.append(char)
                escaped = false
                continue
            }
            when (char) {
                '\\' -> escaped = true
                '"' -> return builder.toString()
                else -> builder.append(char)
            }
        }
    }

    private fun readNextNonWhitespace(reader: PushbackReader): Int {
        while (true) {
            val next = reader.read()
            if (next == -1 || !next.toChar().isWhitespace()) return next
        }
    }

    private fun hasJsonStringProperty(
        json: String,
        key: String,
        value: String,
        ignoreCase: Boolean = false,
    ): Boolean {
        val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
        return Regex(
            """"${Regex.escape(key)}"\s*:\s*"${Regex.escape(value)}"""",
            options
        ).containsMatchIn(json)
    }
}

private fun ManualOfflineBounds.toPolygonFeatureJson(): String {
    val ring = JSONArray()
        .put(JSONArray().put(west).put(south))
        .put(JSONArray().put(east).put(south))
        .put(JSONArray().put(east).put(north))
        .put(JSONArray().put(west).put(north))
        .put(JSONArray().put(west).put(south))
    return JSONObject()
        .put("type", "Feature")
        .put("properties", JSONObject())
        .put(
            "geometry",
            JSONObject()
                .put("type", "Polygon")
                .put("coordinates", JSONArray().put(ring))
        )
        .toString()
}

private fun ManualOfflineBounds.offlineRegionId(): String {
    fun snap(value: Double): String = String.format(java.util.Locale.US, "%.5f", value)
    return "manual:${snap(south)},${snap(west)}:${snap(north)},${snap(east)}"
}
