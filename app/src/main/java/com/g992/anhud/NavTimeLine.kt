package com.g992.anhud

object NavTimeLine {
    /** Put the destination distance and arrival time below the remaining travel time. */
    fun format(time: String, distance: String, arrival: String): String {
        val head = time.trim()
        val destination = distance.trim()
        val arrivalTime = arrival.trim()
        val tail = when {
            destination.isNotBlank() && arrivalTime.isNotBlank() -> "$destination ($arrivalTime)"
            destination.isNotBlank() -> destination
            arrivalTime.isNotBlank() -> "($arrivalTime)"
            else -> ""
        }
        return when {
            head.isNotBlank() && destination.isNotBlank() -> "$head\n$tail"
            head.isNotBlank() && tail.isNotBlank() -> "$head $tail"
            head.isNotBlank() -> head
            else -> tail
        }
    }
}
