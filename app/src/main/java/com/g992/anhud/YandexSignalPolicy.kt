package com.g992.anhud

/** This APK reports route_gone=true while clearing a passed junction's lane picture. */
internal fun isYandexRouteEnd(source: String?, routeGone: Boolean): Boolean =
    source != "yandex_lane_clear" && routeGone

internal fun yandexSpeedLimitAfterUpdate(current: String, incoming: String, routeActive: Boolean?): String =
    when {
        incoming.isBlank() && routeActive == false -> current
        incoming == "--" -> ""
        else -> incoming
    }

internal fun formatYandexLanes(queue: String): String = queue.substringBefore(';').split('|')
    .mapNotNull { lane ->
        val parts = lane.split(':')
        val direction = parts.firstOrNull()?.split(',')?.firstOrNull().orEmpty()
        val arrow = when {
            direction.startsWith("UTURN_LEFT") -> "↶"
            direction.startsWith("UTURN_RIGHT") -> "↷"
            direction.startsWith("LEFT") -> "←"
            direction.startsWith("RIGHT") -> "→"
            direction.startsWith("STRAIGHT") -> "↑"
            else -> return@mapNotNull null
        }
        if (parts.getOrNull(1).orEmpty() != "NONE") "[$arrow]" else arrow
    }.joinToString("  ")
