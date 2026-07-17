package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomBlockJsonCodecTest {
    @Test
    fun versionedJsonRoundTripPreservesBase64IconAndLayout() {
        val block = CustomBlockDefinition(
            id = "4a15e7c2-420a-4abc-9676-33e600c426f4",
            name = "Температура",
            enabled = true,
            script = "car.speed().show()",
            iconBase64 = PNG_BASE64,
            xDp = 123f,
            yDp = 45f,
            scale = 1.4f,
            alpha = 0.7f
        )
        val original = CustomBlocksDocument(
            menuVisible = true,
            enabled = true,
            blocks = listOf(block)
        )

        val restored = CustomBlockJsonCodec.decode(CustomBlockJsonCodec.encode(original))

        assertEquals(CustomBlocksContract.SCHEMA_VERSION, restored.schemaVersion)
        assertTrue(restored.menuVisible)
        assertTrue(restored.enabled)
        assertEquals(PNG_BASE64, restored.blocks.single().iconBase64)
        assertEquals(123f, restored.blocks.single().xDp)
        assertEquals(0.7f, restored.blocks.single().alpha)
    }

    @Test
    fun rejectsUnsupportedSchemaVersion() {
        val result = runCatching {
            CustomBlockJsonCodec.decode("""{"schemaVersion":999,"blocks":[]}""")
        }

        assertFalse(result.isSuccess)
        assertTrue(result.exceptionOrNull()?.message?.contains("schema version") == true)
    }

    companion object {
        const val PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    }
}
