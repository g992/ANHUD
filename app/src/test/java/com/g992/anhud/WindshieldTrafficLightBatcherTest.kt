package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindshieldTrafficLightBatcherTest {
    private fun light(id: String, position: Int, color: String = "RED", countdown: String = "", arrow: String = "") =
        WindshieldTrafficLight(WindshieldTrafficLight.keyFor(id, position), position, color, countdown, arrow)

    @Test
    fun burstIsCollectedInOrder() {
        val batcher = WindshieldTrafficLightBatcher()
        assertNull(batcher.accept(light("a", 0), 1000))
        assertNull(batcher.accept(light("b", 1), 1005))
        assertNull(batcher.accept(light("c", 3), 1010))
        val batch = batcher.takeBatch()!!
        assertEquals(listOf(0, 1, 3), batch.map { it.position })
        assertNull(batcher.takeBatch())
    }

    @Test
    fun lowerPositionStartsNewBatch() {
        val batcher = WindshieldTrafficLightBatcher()
        batcher.accept(light("a", 0), 1000)
        batcher.accept(light("b", 1), 1001)
        val previous = batcher.accept(light("a", 0), 1002)
        assertEquals(listOf("ws:a", "ws:b"), previous!!.map { it.key })
        assertEquals(listOf("ws:a"), batcher.takeBatch()!!.map { it.key })
    }

    @Test
    fun lateTailExtendsCommittedBurst() {
        val batcher = WindshieldTrafficLightBatcher(batchGapMs = 150)
        batcher.accept(light("a", 0), 1000)
        assertEquals(listOf("ws:a"), batcher.takeBatch()!!.map { it.key })
        assertNull(batcher.accept(light("b", 1), 1100))
        assertEquals(listOf("ws:a", "ws:b"), batcher.takeBatch()!!.map { it.key })
        assertNull(batcher.takeBatch())
    }

    @Test
    fun gapStartsNewBatch() {
        val batcher = WindshieldTrafficLightBatcher(batchGapMs = 150)
        batcher.accept(light("a", 0), 1000)
        val previous = batcher.accept(light("b", 1), 1200)
        assertEquals(listOf("ws:a"), previous!!.map { it.key })
    }

    @Test
    fun mergeReplacesCurrentBatchAndKeepsCountdown() {
        val current = mapOf(
            "ws:a" to TrafficLightInfo("ws:a".hashCode(), "RED", "10", "", 0, 0, 0),
            "ws:gone" to TrafficLightInfo("ws:gone".hashCode(), "RED", "", "", 0, 0, 1)
        )
        val merged = WindshieldTrafficLightBatcher.merge(
            current,
            listOf(light("a", 0, countdown = ""), light("b", 1, color = "GREEN", countdown = "3", arrow = "LEFT")),
            now = 100,
            ttlMs = 1000
        )
        assertEquals(setOf("ws:a", "ws:b"), merged.keys)
        assertEquals("10", merged["ws:a"]!!.countdownText)
        assertEquals(0, merged["ws:a"]!!.position)
        assertEquals("LEFT", merged["ws:b"]!!.arrowDirection)
        assertEquals(1100, merged["ws:b"]!!.expiresAt)
    }

    @Test
    fun countdownDroppedWhenColorChanges() {
        val current = mapOf("ws:a" to TrafficLightInfo("ws:a".hashCode(), "RED", "2", "", 0, 0, 0))
        val merged = WindshieldTrafficLightBatcher.merge(current, listOf(light("a", 0, color = "GREEN")), now = 1)
        assertEquals("", merged["ws:a"]!!.countdownText)
    }

    @Test
    fun emptyBatchClearsAllLights() {
        val current = mapOf(
            "ws:a" to TrafficLightInfo(2, "RED", "", "", 0, 0, 0)
        )
        assertTrue(WindshieldTrafficLightBatcher.merge(current, emptyList(), now = 1).isEmpty())
        assertTrue(WindshieldTrafficLightBatcher.isClearSignal("", "", "", ""))
    }

    @Test
    fun keyFallsBackToPosition() {
        assertEquals("ws:#pos2", WindshieldTrafficLight.keyFor("", 2))
    }
}
