package com.g992.anhud

import android.content.Context
import org.json.JSONObject
import org.maplibre.geojson.Feature
import org.maplibre.geojson.Geometry
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.PushbackReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private const val GEOBOUNDARIES_API_BASE = "https://www.geoboundaries.org/api/current"
private const val GEOBOUNDARIES_PRODUCT = "gbOpen"
private const val GEOBOUNDARIES_TIMEOUT_MS = 60_000
private const val GEOBOUNDARIES_USER_AGENT = "ANHUD/1.0"
private const val GEOBOUNDARIES_GEOMETRY_CACHE_DIR = "offline_region_geometry"

data class ResolvedOfflineGeometry(
    val label: String,
    val regionId: String,
    val bounds: OfflineRegionBounds,
    val geometry: Geometry,
)

object GeoBoundariesRegionResolver {
    fun resolveSelected(context: Context, settings: MapRenderSettings): ResolvedOfflineGeometry {
        settings.manualOfflineBoundsOrNull()?.let { manual ->
            return ResolvedOfflineGeometry(
                label = manual.label,
                regionId = manual.offlineRegionId(),
                bounds = manual.boundsPreview(),
                geometry = manual.toPolygonGeometry(),
            )
        }
        val entry = OfflineRegionCatalog.findById(context, settings.offlineRegionId)
            ?: error("Оффлайн-регион не выбран")
        return resolveCatalogEntry(context, entry)
    }

    fun resolveCatalogEntry(context: Context, entry: OfflineRegionEntry): ResolvedOfflineGeometry {
        val cacheFile = File(
            File(context.filesDir, GEOBOUNDARIES_GEOMETRY_CACHE_DIR).apply { mkdirs() },
            "${entry.id}.geojson"
        )
        if (cacheFile.isFile) {
            return ResolvedOfflineGeometry(
                label = entry.displayLabel,
                regionId = entry.id,
                bounds = entry.boundsPreview(),
                geometry = Feature.fromJson(cacheFile.readText(Charsets.UTF_8)).geometry()
                    ?: error("Cached geometry is empty for ${entry.id}")
            )
        }

        val metadataUrl = "$GEOBOUNDARIES_API_BASE/$GEOBOUNDARIES_PRODUCT/${entry.countryIso3}/${entry.level}/"
        val metadata = JSONObject(httpGetText(metadataUrl))
        val geometryUrl = metadata.optString("simplifiedGeometryGeoJSON")
            .ifBlank { metadata.optString("gjDownloadURL") }
            .ifBlank { error("geoBoundaries did not return a GeoJSON URL") }
        val sourceFeatureJson = findFeatureJson(geometryUrl, entry)
            ?: error("Не удалось найти границы для ${entry.displayLabel}")
        val feature = Feature.fromJson(sourceFeatureJson)
        val geometry = feature.geometry() ?: error("geoBoundaries returned empty geometry")
        val featureJson = Feature.fromGeometry(geometry).toJson()
        cacheFile.writeText(featureJson, Charsets.UTF_8)
        return ResolvedOfflineGeometry(
            label = entry.displayLabel,
            regionId = entry.id,
            bounds = entry.boundsPreview(),
            geometry = geometry,
        )
    }

    private fun findFeatureJson(url: String, entry: OfflineRegionEntry): String? {
        return withHttpReader(url) { reader ->
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

    private fun httpGetText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = GEOBOUNDARIES_TIMEOUT_MS
            readTimeout = GEOBOUNDARIES_TIMEOUT_MS
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

    private fun <T> withHttpReader(url: String, block: (PushbackReader) -> T): T {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = GEOBOUNDARIES_TIMEOUT_MS
            readTimeout = GEOBOUNDARIES_TIMEOUT_MS
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

private fun ManualOfflineBounds.toPolygonGeometry(): Polygon {
    return Polygon.fromLngLats(
        listOf(
            listOf(
                Point.fromLngLat(west, south),
                Point.fromLngLat(east, south),
                Point.fromLngLat(east, north),
                Point.fromLngLat(west, north),
                Point.fromLngLat(west, south),
            )
        )
    )
}

private fun ManualOfflineBounds.offlineRegionId(): String {
    fun snap(value: Double): String = String.format(java.util.Locale.US, "%.5f", value)
    return "manual:${snap(south)},${snap(west)}:${snap(north)},${snap(east)}"
}
