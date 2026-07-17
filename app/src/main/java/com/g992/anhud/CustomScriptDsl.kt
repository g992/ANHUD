package com.g992.anhud

import org.json.JSONArray
import org.json.JSONObject

sealed interface CustomSourceKey {
    val stableKey: String

    data object Speed : CustomSourceKey {
        override val stableKey: String = "car:speed"
    }

    data class Sensor(val sensorId: Int) : CustomSourceKey {
        override val stableKey: String = "car:sensor:$sensorId"
    }

    data class IntentExtra(val action: String, val extraName: String) : CustomSourceKey {
        override val stableKey: String = "intent:$action:$extraName"
    }
}

internal object CustomDependencyIndex {
    fun build(dependenciesByBlock: Map<String, Set<CustomSourceKey>>): Map<CustomSourceKey, Set<String>> =
        dependenciesByBlock.entries
            .flatMap { (blockId, sources) -> sources.map { source -> source to blockId } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, blockIds) -> blockIds.toSet() }
}

data class CustomDataValue(
    val value: Any?,
    val receivedAt: Long
)

data class CustomScriptEnvironment(
    val now: Long,
    val speed: CustomDataValue? = null,
    val sensors: Map<Int, CustomDataValue> = emptyMap(),
    val intents: Map<String, Map<String, CustomDataValue>> = emptyMap()
) {
    fun toJson(): String = JSONObject().apply {
        put("now", now)
        put("speed", speed?.toJsonValue() ?: JSONObject.NULL)
        put("sensors", JSONObject().apply {
            sensors.forEach { (sensorId, data) -> put(sensorId.toString(), data.toJsonValue()) }
        })
        put("intents", JSONObject().apply {
            intents.forEach { (action, extras) ->
                put(action, JSONObject().apply {
                    extras.forEach { (extraName, data) -> put(extraName, data.toJsonValue()) }
                })
            }
        })
    }.toString()
}

data class CustomScriptEvaluation(
    val visible: Boolean,
    val text: String,
    val dependencies: Set<CustomSourceKey>,
    val nextRefreshAt: Long?
) {
    companion object {
        fun parse(json: String): CustomScriptEvaluation {
            require(json.length <= CustomScriptRuntime.MAX_RESULT_JSON_LENGTH) {
                "Результат скрипта слишком большой"
            }
            val root = JSONObject(json)
            require(root.optInt("version", -1) == 1) { "Unsupported render result" }
            val render = root.getJSONObject("render")
            val text = render.optString("text", "")
            require(text.length <= CustomBlocksContract.MAX_RENDER_TEXT_LENGTH) {
                "Текст результата длиннее ${CustomBlocksContract.MAX_RENDER_TEXT_LENGTH} символов"
            }
            val dependenciesJson = root.optJSONArray("dependencies") ?: JSONArray()
            val dependencies = buildSet {
                for (index in 0 until dependenciesJson.length()) {
                    val dependency = dependenciesJson.getJSONObject(index)
                    when (dependency.getString("type")) {
                        "speed" -> add(CustomSourceKey.Speed)
                        "sensor" -> add(CustomSourceKey.Sensor(dependency.getInt("sensorId")))
                        "intent" -> add(
                            CustomSourceKey.IntentExtra(
                                dependency.getString("action"),
                                dependency.getString("extraName")
                            )
                        )
                    }
                }
            }
            return CustomScriptEvaluation(
                visible = render.optBoolean("visible", false),
                text = text,
                dependencies = dependencies,
                nextRefreshAt = root.optLong("nextRefreshAt", 0L).takeIf { it > 0L }
            )
        }
    }
}

object CustomScriptPolicy {
    private val speedRegex = Regex("""\bcar\s*\.\s*speed\s*\(""")
    private val sensorCallRegex = Regex("""\bcar\s*\.\s*readSensor\s*\(""")
    private val sensorRegex = Regex("""\bcar\s*\.\s*readSensor\s*\(\s*(\d+)\s*\)""")
    private val intentCallRegex = Regex("""\bintents\s*\.\s*read\s*\(""")
    private val intentRegex = Regex(
        """\bintents\s*\.\s*read\s*\(\s*([\"'])([^\"'\\]{1,200})\1\s*,\s*([\"'])([^\"'\\]{1,100})\3\s*\)"""
    )

    fun requireValidShape(rawScript: String): String {
        val script = rawScript.trim()
        require(script.isNotEmpty()) { "JS-выражение не должно быть пустым" }
        require(script.length <= CustomBlocksContract.MAX_SCRIPT_LENGTH) {
            "JS-выражение длиннее ${CustomBlocksContract.MAX_SCRIPT_LENGTH} символов"
        }
        require(!script.contains('\n') && !script.contains('\r')) {
            "JS-выражение должно быть однострочным"
        }
        return script
    }

    fun dependencies(rawScript: String): Set<CustomSourceKey> {
        val script = requireValidShape(rawScript)
        val dependencies = linkedSetOf<CustomSourceKey>()
        if (speedRegex.containsMatchIn(script)) dependencies.add(CustomSourceKey.Speed)

        val sensorMatches = sensorRegex.findAll(script).toList()
        if (sensorCallRegex.findAll(script).count() != sensorMatches.size) {
            throw IllegalArgumentException("car.readSensor() принимает числовой sensor ID")
        }
        sensorMatches.forEach { match ->
            val sensorId = match.groupValues[1].toIntOrNull()
                ?: throw IllegalArgumentException("Некорректный sensor ID")
            CustomCarSensorRegistry.requireSupported(sensorId)
            dependencies.add(CustomSourceKey.Sensor(sensorId))
        }

        val intentMatches = intentRegex.findAll(script).toList()
        if (intentCallRegex.findAll(script).count() != intentMatches.size) {
            throw IllegalArgumentException("intents.read() принимает строковые action и extraName")
        }
        intentMatches.forEach { match ->
            dependencies.add(
                CustomSourceKey.IntentExtra(
                    action = match.groupValues[2],
                    extraName = match.groupValues[4]
                )
            )
        }
        return dependencies
    }
}

object CustomScriptDsl {
    fun buildProgram(script: String, environment: CustomScriptEnvironment): String {
        val expression = CustomScriptPolicy.requireValidShape(script)
        val environmentLiteral = JSONObject.quote(environment.toJson())
        return """
            (() => {
              "use strict";
              const __env = JSON.parse($environmentLiteral);
              const __deps = [];
              const __depKeys = Object.create(null);
              let __nextRefreshAt = 0;
              function __dependency(key, value) {
                if (!__depKeys[key]) { __depKeys[key] = true; __deps.push(value); }
              }
              function __finiteNumber(value, method) {
                const number = Number(value);
                if (!Number.isFinite(number)) throw new Error(method + ": значение не является числом");
                return number;
              }
              function __positiveMs(value, method) {
                const number = __finiteNumber(value, method);
                if (number < 0) throw new Error(method + ": время не может быть отрицательным");
                return number;
              }
              function __defaultText(value, fixedDigits) {
                if (typeof value === "number") {
                  return fixedDigits === null ? String(value) : value.toFixed(fixedDigits);
                }
                if (Array.isArray(value)) return JSON.stringify(value);
                return value === null || value === undefined ? "" : String(value);
              }
              function Value(value, receivedAt) {
                this.value = value;
                this.receivedAt = Number(receivedAt) || 0;
                this.template = null;
                this.templateDigits = null;
                this.callback = null;
                this.fixedDigits = null;
                this.fallbackText = null;
                this.ttlMs = null;
                this.hideStaleMs = null;
                this.shouldHideIfEmpty = false;
              }
              Value.prototype.number = function() {
                if (this.value !== null && this.value !== undefined && this.value !== "") {
                  const converted = Number(this.value);
                  this.value = Number.isFinite(converted) ? converted : null;
                }
                return this;
              };
              Value.prototype.multiply = function(value) {
                this.value = __finiteNumber(this.value, "multiply") * __finiteNumber(value, "multiply");
                return this;
              };
              Value.prototype.divide = function(value) {
                const divisor = __finiteNumber(value, "divide");
                if (divisor === 0) throw new Error("divide: деление на ноль");
                this.value = __finiteNumber(this.value, "divide") / divisor;
                return this;
              };
              Value.prototype.mpsToKmh = function() {
                this.value = __finiteNumber(this.value, "mpsToKmh") * 3.6;
                return this;
              };
              Value.prototype.round = function() {
                this.value = Math.round(__finiteNumber(this.value, "round"));
                return this;
              };
              Value.prototype.fixed = function(digits) {
                const value = Math.trunc(__finiteNumber(digits, "fixed"));
                if (value < 0 || value > 10) throw new Error("fixed: digits должен быть от 0 до 10");
                this.value = __finiteNumber(this.value, "fixed");
                this.fixedDigits = value;
                return this;
              };
              Value.prototype.format = function(formatter, digits) {
                if (typeof formatter === "function") {
                  this.callback = formatter;
                  this.template = null;
                  return this;
                }
                this.template = String(formatter);
                this.callback = null;
                if (digits !== undefined) {
                  const value = Math.trunc(__finiteNumber(digits, "format"));
                  if (value < 0 || value > 10) throw new Error("format: digits должен быть от 0 до 10");
                  this.templateDigits = value;
                }
                return this;
              };
              Value.prototype.fallback = function(text) { this.fallbackText = String(text); return this; };
              Value.prototype.ttl = function(milliseconds) { this.ttlMs = __positiveMs(milliseconds, "ttl"); return this; };
              Value.prototype.hideIfStale = function(milliseconds) { this.hideStaleMs = __positiveMs(milliseconds, "hideIfStale"); return this; };
              Value.prototype.hideIfEmpty = function() { this.shouldHideIfEmpty = true; return this; };
              Value.prototype.show = function() {
                const now = Number(__env.now) || Date.now();
                const age = this.receivedAt > 0 ? Math.max(0, now - this.receivedAt) : Number.POSITIVE_INFINITY;
                let hidden = false;
                let value = this.value;
                function scheduleExpiry(receivedAt, timeout) {
                  if (receivedAt <= 0 || timeout === null) return;
                  const expiry = receivedAt + timeout;
                  if (expiry > now && (__nextRefreshAt === 0 || expiry < __nextRefreshAt)) __nextRefreshAt = expiry;
                }
                scheduleExpiry(this.receivedAt, this.ttlMs);
                scheduleExpiry(this.receivedAt, this.hideStaleMs);
                if (this.ttlMs !== null && age >= this.ttlMs) value = null;
                if (this.hideStaleMs !== null && age >= this.hideStaleMs) hidden = true;
                const missing = value === null || value === undefined || value === "" ||
                  (Array.isArray(value) && value.length === 0);
                let text = "";
                if (missing) {
                  if (this.fallbackText !== null) text = this.fallbackText; else hidden = true;
                } else if (this.callback !== null) {
                  text = String(this.callback(value));
                } else if (this.template !== null) {
                  const replacement = typeof value === "number" && this.templateDigits !== null
                    ? value.toFixed(this.templateDigits)
                    : __defaultText(value, this.fixedDigits);
                  text = this.template.split("{value}").join(replacement);
                } else {
                  text = __defaultText(value, this.fixedDigits);
                }
                if (this.shouldHideIfEmpty && text.trim().length === 0) hidden = true;
                return { __customRender: true, visible: !hidden, text: text };
              };
              const car = Object.freeze({
                speed: function() {
                  __dependency("car:speed", { type: "speed" });
                  const data = __env.speed;
                  return new Value(data ? data.value : null, data ? data.receivedAt : 0);
                },
                readSensor: function(sensorId) {
                  const id = Number(sensorId);
                  if (!Number.isInteger(id)) throw new Error("car.readSensor: sensor ID должен быть целым");
                  if (id !== ${CustomBlocksContract.SPEED_SENSOR_ID}) throw new Error("Unsupported sensor ID: " + id);
                  __dependency("car:sensor:" + id, { type: "sensor", sensorId: id });
                  const data = __env.sensors[String(id)];
                  return new Value(data ? data.value : null, data ? data.receivedAt : 0);
                }
              });
              const intents = Object.freeze({
                read: function(action, extraName) {
                  if (typeof action !== "string" || typeof extraName !== "string") {
                    throw new Error("intents.read: action и extraName должны быть строками");
                  }
                  __dependency("intent:" + action + ":" + extraName, {
                    type: "intent", action: action, extraName: extraName
                  });
                  const actionData = __env.intents[action];
                  const data = actionData ? actionData[extraName] : null;
                  return new Value(data ? data.value : null, data ? data.receivedAt : 0);
                }
              });
              const __result = ($expression);
              if (!__result || __result.__customRender !== true) {
                throw new Error("Скрипт должен быть выражением, завершающимся .show()");
              }
              return JSON.stringify({
                version: 1,
                render: { visible: Boolean(__result.visible), text: String(__result.text || "") },
                dependencies: __deps,
                nextRefreshAt: __nextRefreshAt
              });
            })()
        """.trimIndent()
    }
}

private fun CustomDataValue.toJsonValue(): JSONObject = JSONObject().apply {
    put("value", value.toSafeJsonValue())
    put("receivedAt", receivedAt)
}

internal fun Any?.toSafeJsonValue(): Any {
    return when (this) {
        null -> JSONObject.NULL
        is String, is Boolean, is Int, is Long, is Float, is Double -> this
        is BooleanArray -> JSONArray().apply { this@toSafeJsonValue.forEach { put(it) } }
        is IntArray -> JSONArray().apply { this@toSafeJsonValue.forEach { put(it) } }
        is LongArray -> JSONArray().apply { this@toSafeJsonValue.forEach { put(it) } }
        is FloatArray -> JSONArray().apply { this@toSafeJsonValue.forEach { put(it.toDouble()) } }
        is DoubleArray -> JSONArray().apply { this@toSafeJsonValue.forEach { put(it) } }
        is Array<*> -> JSONArray().apply {
            this@toSafeJsonValue.forEach { value ->
                require(value is String) { "Only String arrays are supported" }
                put(value)
            }
        }
        is List<*> -> JSONArray().apply { this@toSafeJsonValue.forEach { put(it.toSafeJsonValue()) } }
        else -> throw IllegalArgumentException("Unsafe value type: ${this::class.java.name}")
    }
}
