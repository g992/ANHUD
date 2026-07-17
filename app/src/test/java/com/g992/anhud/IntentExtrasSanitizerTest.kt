package com.g992.anhud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.Serializable

class IntentExtrasSanitizerTest {
    @Test
    fun acceptsOnlySafePrimitiveExtrasAndSimpleArrays() {
        assertEquals("42", IntentExtrasSanitizer.sanitize("42"))
        assertEquals(true, IntentExtrasSanitizer.sanitize(true))
        assertEquals(42, IntentExtrasSanitizer.sanitize(42))
        assertEquals(42L, IntentExtrasSanitizer.sanitize(42L))
        assertEquals(4.2f, IntentExtrasSanitizer.sanitize(4.2f))
        assertEquals(4.2, IntentExtrasSanitizer.sanitize(4.2))
        assertArrayEquals(
            intArrayOf(1, 2),
            IntentExtrasSanitizer.sanitize(intArrayOf(1, 2)) as IntArray
        )
        assertEquals(
            listOf("a", "b"),
            IntentExtrasSanitizer.sanitize(arrayOf("a", "b"))
        )
    }

    @Test
    fun rejectsSerializableAndUnsupportedArrays() {
        assertNull(IntentExtrasSanitizer.sanitize(UnsafeSerializable("secret")))
        assertNull(IntentExtrasSanitizer.sanitize(byteArrayOf(1, 2)))
        assertNull(IntentExtrasSanitizer.sanitize(arrayOf(1, 2)))
    }

    private data class UnsafeSerializable(val value: String) : Serializable
}
