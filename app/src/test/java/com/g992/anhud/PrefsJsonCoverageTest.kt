package com.g992.anhud

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsJsonCoverageTest {
    @Test
    fun overlayPrefsKeysAreCoveredByPresetSerialization() {
        val overlayKeys = extractKeyConstants(
            "app/src/main/java/com/g992/anhud/OverlayPrefs.kt"
        )
        val serializedKeys = extractSerializedKeys(
            "app/src/main/java/com/g992/anhud/PrefsJson.kt",
            overlayKeys
        )

        val missing = overlayKeys.values.sorted().filterNot(serializedKeys::contains)

        assertTrue(
            "Overlay prefs missing from preset serialization: ${missing.joinToString()}",
            missing.isEmpty()
        )
    }

    @Test
    fun mapRenderPrefsKeysAreCoveredByPresetSerialization() {
        val mapRenderKeys = extractKeyConstants(
            "app/src/main/java/com/g992/anhud/MapRenderSettings.kt"
        )
        val serializedKeys = extractSerializedKeys(
            "app/src/main/java/com/g992/anhud/PrefsJson.kt",
            mapRenderKeys
        )

        val missing = mapRenderKeys.values.sorted().filterNot(serializedKeys::contains)

        assertTrue(
            "Map render prefs missing from preset serialization: ${missing.joinToString()}",
            missing.isEmpty()
        )
    }

    private fun extractSerializedKeys(
        relativePath: String,
        knownConstants: Map<String, String>
    ): Set<String> {
        val source = readProjectFile(relativePath)
        val literalKeys = Regex("put(?:Boolean|Float|Int|String|StringSet)\\(\\s*\"([^\"]+)\"")
            .findAll(source)
            .map { it.groupValues[1] }
            .toMutableSet()
        Regex("(?:OverlayPrefs|MapRenderSettingsStore)\\.(KEY_[A-Z0-9_]+)")
            .findAll(source)
            .map { it.groupValues[1] }
            .mapNotNull(knownConstants::get)
            .forEach(literalKeys::add)
        return literalKeys
    }

    private fun extractKeyConstants(relativePath: String): Map<String, String> {
        val source = readProjectFile(relativePath)
        return Regex("KEY_[A-Z0-9_]+ = \"([^\"]+)\"")
            .findAll(source)
            .associate { match ->
                val full = match.value
                val name = full.substringBefore(" =").trim()
                name to match.groupValues[1]
            }
    }

    private fun readProjectFile(relativePath: String): String {
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is not set" }
        val root = generateSequence(File(userDir).absoluteFile) { it.parentFile }
            .firstOrNull { candidate ->
                File(candidate, "settings.gradle.kts").isFile || File(candidate, "settings.gradle").isFile
            }
            ?: error("Could not locate project root from $userDir")
        val file = File(root, relativePath)
        check(file.isFile) { "Expected source file at ${file.absolutePath}" }
        return file.readText()
    }
}
