package com.g992.anhud

object NavTimeLine {
    /** "<time> · <distance> (<arrival>)", empty parts are dropped together with their separators. */
    fun format(time: String, distance: String, arrival: String): String {
        val head = listOf(time.trim(), distance.trim())
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        val tail = arrival.trim()
        return when {
            head.isNotBlank() && tail.isNotBlank() -> "$head ($tail)"
            head.isNotBlank() -> head
            tail.isNotBlank() -> "($tail)"
            else -> ""
        }
    }
}
