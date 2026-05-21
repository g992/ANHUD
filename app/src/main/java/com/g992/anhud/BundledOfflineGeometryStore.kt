package com.g992.anhud

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.LinkedHashMap
import java.util.zip.GZIPInputStream

private const val BUNDLED_OFFLINE_GEOMETRY_ASSET_PATH = "offline_regions_geometry.json.gz"
private const val BUNDLED_OFFLINE_GEOMETRY_CACHE_SIZE = 48
private const val BUNDLED_OFFLINE_GEOMETRY_TAG = "BundledOfflineGeometry"

object BundledOfflineGeometryStore {
    private val featureCache = object : LinkedHashMap<String, String>(BUNDLED_OFFLINE_GEOMETRY_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > BUNDLED_OFFLINE_GEOMETRY_CACHE_SIZE
        }
    }

    fun resolveCatalogEntry(context: Context, entry: OfflineRegionEntry): ResolvedOfflineGeometry? {
        if (!entry.bundledGeometryAvailable) return null
        cachedFeature(entry.id)?.let { featureJson ->
            return ResolvedOfflineGeometry(
                label = entry.displayLabel,
                regionId = entry.id,
                bounds = entry.boundsPreview(),
                geometryJson = featureJson,
                sourceHint = "bundled_simplified_cached",
            )
        }
        val featureJson = findFeatureJson(context, entry.id) ?: return null
        cacheFeature(entry.id, featureJson)
        return ResolvedOfflineGeometry(
            label = entry.displayLabel,
            regionId = entry.id,
            bounds = entry.boundsPreview(),
            geometryJson = featureJson,
            sourceHint = "bundled_simplified",
        )
    }

    private fun findFeatureJson(context: Context, regionId: String): String? {
        return runCatching {
            GZIPInputStream(context.assets.open(BUNDLED_OFFLINE_GEOMETRY_ASSET_PATH)).use { input ->
                JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                    reader.beginObject()
                    var featureJson: String? = null
                    while (reader.hasNext()) {
                        when (reader.nextName()) {
                            "regions" -> {
                                reader.beginArray()
                                while (reader.hasNext()) {
                                    val candidate = readRegionFeatureJson(reader, regionId)
                                    if (candidate != null) {
                                        featureJson = candidate
                                        break
                                    }
                                }
                                while (reader.hasNext()) {
                                    reader.skipValue()
                                }
                                reader.endArray()
                            }

                            else -> reader.skipValue()
                        }
                        if (featureJson != null) break
                    }
                    while (reader.hasNext()) {
                        reader.skipValue()
                    }
                    reader.endObject()
                    featureJson
                }
            }
        }.onFailure { error ->
            Log.w(
                BUNDLED_OFFLINE_GEOMETRY_TAG,
                "Bundled offline geometry asset is unavailable, exact online geometry will be required",
                error
            )
        }.getOrNull()
    }

    private fun readRegionFeatureJson(reader: JsonReader, targetRegionId: String): String? {
        reader.beginObject()
        var regionId: String? = null
        var geometryJson: JSONObject? = null
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> regionId = reader.nextString()
                "geometry" -> {
                    geometryJson = if (regionId == targetRegionId) {
                        readJsonObject(reader)
                    } else {
                        reader.skipValue()
                        null
                    }
                }

                else -> reader.skipValue()
            }
        }
        reader.endObject()
        if (regionId != targetRegionId || geometryJson == null) return null
        return JSONObject()
            .put("type", "Feature")
            .put("properties", JSONObject())
            .put("geometry", geometryJson)
            .toString()
    }

    private fun readJsonObject(reader: JsonReader): JSONObject {
        val jsonObject = JSONObject()
        reader.beginObject()
        while (reader.hasNext()) {
            jsonObject.put(reader.nextName(), readJsonValue(reader))
        }
        reader.endObject()
        return jsonObject
    }

    private fun readJsonArray(reader: JsonReader): JSONArray {
        val jsonArray = JSONArray()
        reader.beginArray()
        while (reader.hasNext()) {
            jsonArray.put(readJsonValue(reader))
        }
        reader.endArray()
        return jsonArray
    }

    private fun readJsonValue(reader: JsonReader): Any? {
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> readJsonObject(reader)
            JsonToken.BEGIN_ARRAY -> readJsonArray(reader)
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> reader.nextDouble()
            JsonToken.BOOLEAN -> reader.nextBoolean()
            JsonToken.NULL -> {
                reader.nextNull()
                JSONObject.NULL
            }

            else -> error("Unsupported bundled geometry token ${reader.peek()}")
        }
    }

    private fun cachedFeature(regionId: String): String? = synchronized(featureCache) {
        featureCache[regionId]
    }

    private fun cacheFeature(regionId: String, featureJson: String) {
        synchronized(featureCache) {
            featureCache[regionId] = featureJson
        }
    }
}
