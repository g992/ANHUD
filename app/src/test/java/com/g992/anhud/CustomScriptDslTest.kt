package com.g992.anhud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable

class CustomScriptDslTest {
    @Test
    fun formatTemplateAndMpsToKmh() {
        val result = evaluate(
            "car.readSensor(1055232).mpsToKmh().format(\"{value} км/ч\", 0).show()",
            environment(sensors = mapOf(1_055_232 to CustomDataValue(10f, NOW)))
        ).getOrThrow()

        assertTrue(result.visible)
        assertEquals("36 км/ч", result.text)
    }

    @Test
    fun callbackFormat() {
        val result = evaluate(
            "car.speed().format(v => v > 120 ? `⚠ ${'$'}{v} км/ч` : `${'$'}{v} км/ч`).show()",
            environment(speed = CustomDataValue(130, NOW))
        ).getOrThrow()

        assertEquals("⚠ 130 км/ч", result.text)
    }

    @Test
    fun numberMultiplyAndDivide() {
        val result = evaluate(
            "intents.read(\"car.data\", \"value\").number().multiply(2).divide(4).show()",
            environment(
                intents = mapOf("car.data" to mapOf("value" to CustomDataValue("10", NOW)))
            )
        ).getOrThrow()

        assertEquals("5", result.text)
    }

    @Test
    fun roundAndFixed() {
        assertEquals(
            "2",
            evaluate("car.speed().divide(10).round().show()", environment(speed = CustomDataValue(16, NOW)))
                .getOrThrow().text
        )
        assertEquals(
            "1.23",
            evaluate("car.speed().divide(10).fixed(2).show()", environment(speed = CustomDataValue(12.345, NOW)))
                .getOrThrow().text
        )
    }

    @Test
    fun fallbackIsUsedForMissingValue() {
        val result = evaluate("car.speed().fallback(\"нет данных\").show()", environment()).getOrThrow()

        assertTrue(result.visible)
        assertEquals("нет данных", result.text)
    }

    @Test
    fun ttlExpiresValueAndHideIfStaleHidesIt() {
        val stale = CustomDataValue(42, NOW - 6_000)
        val ttl = evaluate(
            "car.speed().ttl(5000).fallback(\"устарело\").show()",
            environment(speed = stale)
        ).getOrThrow()
        val hidden = evaluate(
            "car.speed().hideIfStale(5000).show()",
            environment(speed = stale)
        ).getOrThrow()

        assertEquals("устарело", ttl.text)
        assertTrue(ttl.visible)
        assertFalse(hidden.visible)
    }

    @Test
    fun ttlSchedulesRefreshAndHideIfEmptyHidesBlankText() {
        val fresh = evaluate(
            "car.speed().ttl(5000).show()",
            environment(speed = CustomDataValue(42, NOW - 1_000))
        ).getOrThrow()
        val empty = evaluate(
            "car.speed().format(\"\").hideIfEmpty().show()",
            environment(speed = CustomDataValue(42, NOW))
        ).getOrThrow()

        assertEquals(NOW + 4_000, fresh.nextRefreshAt)
        assertFalse(empty.visible)
    }

    @Test
    fun dependenciesAreRegisteredAndIndexed() {
        val script = "car.speed().multiply(1).show()"
        val evaluation = evaluate(script, environment(speed = CustomDataValue(80, NOW))).getOrThrow()
        val sensor = CustomSourceKey.Sensor(1_055_232)
        val intent = CustomSourceKey.IntentExtra("car.data", "temperature")
        val dependencies = CustomScriptPolicy.dependencies(
            "intents.read(\"car.data\", \"temperature\").fallback(\"-\").show()"
        ) + sensor
        val index = CustomDependencyIndex.build(mapOf("one" to evaluation.dependencies, "two" to dependencies))

        assertEquals(setOf(CustomSourceKey.Speed), evaluation.dependencies)
        assertEquals(setOf("one"), index[CustomSourceKey.Speed])
        assertEquals(setOf("two"), index[sensor])
        assertEquals(setOf("two"), index[intent])
    }

    @Test
    fun unsupportedSensorHasExplicitError() {
        val error = runCatching {
            CustomScriptPolicy.dependencies("car.readSensor(123456).show()")
        }.exceptionOrNull()

        assertEquals("Unsupported sensor ID: 123456", error?.message)
    }

    @Test
    fun javascriptErrorDoesNotEscapeEvaluationResult() {
        val result = evaluate(
            "car.speed().divide(0).show()",
            environment(speed = CustomDataValue(10, NOW))
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun infiniteScriptCanBeInterruptedByWatchdogHarness() {
        val factory = object : ContextFactory() {
            override fun makeContext(): Context = super.makeContext().apply {
                optimizationLevel = -1
                instructionObserverThreshold = 1_000
            }

            override fun observeInstructionCount(cx: Context?, instructionCount: Int) {
                throw ScriptTimeoutException()
            }
        }
        val result = runCatching {
            factory.call { context ->
                context.evaluateString(
                    context.initStandardObjects(),
                    CustomScriptDsl.buildProgram("(() => { while (true) {} })()", environment()),
                    "infinite.js",
                    1,
                    null
                )
            }
        }

        assertTrue(result.exceptionOrNull() is ScriptTimeoutException)
    }

    private fun evaluate(
        script: String,
        environment: CustomScriptEnvironment
    ): Result<CustomScriptEvaluation> = runCatching {
        val context = Context.enter()
        try {
            context.optimizationLevel = -1
            val scope: Scriptable = context.initStandardObjects()
            val raw = context.evaluateString(
                scope,
                CustomScriptDsl.buildProgram(script, environment),
                "custom-block-test.js",
                1,
                null
            )
            CustomScriptEvaluation.parse(Context.toString(raw))
        } finally {
            Context.exit()
        }
    }

    private fun environment(
        speed: CustomDataValue? = null,
        sensors: Map<Int, CustomDataValue> = emptyMap(),
        intents: Map<String, Map<String, CustomDataValue>> = emptyMap()
    ) = CustomScriptEnvironment(NOW, speed, sensors, intents)

    private class ScriptTimeoutException : RuntimeException()

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
