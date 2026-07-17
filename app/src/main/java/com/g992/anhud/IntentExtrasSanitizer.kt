package com.g992.anhud

import android.content.Intent

object IntentExtrasSanitizer {
    @Suppress("DEPRECATION")
    fun read(intent: Intent, extraName: String): Any? {
        return runCatching {
            val extras = intent.extras ?: return null
            extras.classLoader = null
            if (!extras.containsKey(extraName)) return null
            sanitize(extras.get(extraName))
        }.getOrNull()
    }

    fun sanitize(value: Any?): Any? {
        return when (value) {
            null -> null
            is String, is Boolean, is Int, is Long, is Float, is Double -> value
            is BooleanArray -> value.copyOf()
            is IntArray -> value.copyOf()
            is LongArray -> value.copyOf()
            is FloatArray -> value.copyOf()
            is DoubleArray -> value.copyOf()
            is Array<*> -> {
                if (value.all { it is String }) value.map { it as String } else null
            }
            else -> null
        }
    }
}
