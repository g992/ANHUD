package com.g992.anhud

/** Wire order and enums verified against GeeGeek 0.2.0-beta1 (r1/c, r1/x, r1/m). */
internal data class CarPlayRouteData(
    val maneuver: Int,
    val distance: String,
    val distanceUnits: Int,
    val road: String,
    val remainingDistance: String,
    val remainingUnits: Int,
    val remainingSeconds: Long,
    val routeState: Int,
    val junctionType: Int,
    val drivingSide: Int,
    val exitAngle: Int
) {
    val hasRoute: Boolean get() = routeState in 1..6

    val exitNumber: Int? get() = maneuver.takeIf { it in 28..46 }?.minus(27)

    fun update(): ProviderNavUpdate = ProviderNavUpdate(
        maneuverType = maneuverKey(maneuver),
        maneuverDistance = displayDistance(distance, distanceUnits),
        street = ProviderNavFormat.normalize(road),
        remainingDistance = displayDistance(remainingDistance, remainingUnits),
        remainingTime = ProviderNavFormat.seconds(remainingSeconds),
        arrival = ProviderNavFormat.arrival(remainingSeconds),
        exitNumber = exitNumber
    )

    companion object {
        internal fun displayDistance(value: String, units: Int): String {
            val text = ProviderNavFormat.normalize(value)
            if (text.isBlank()) return ""
            val suffix = when (units) {
                0 -> "км"
                1 -> "миль"
                2 -> "м"
                3 -> "ярд"
                4 -> "фт"
                else -> ""
            }
            return listOf(text, suffix).filter { it.isNotBlank() }.joinToString(" ")
        }

        internal fun maneuverKey(value: Int): String = when (value) {
            0, 3, 5, 11, 51 -> "context_ra_forward"
            1, 20 -> "context_ra_turn_left"
            2, 21 -> "context_ra_turn_right"
            4, 18, 19, 26 -> "context_ra_turn_back_left"
            6 -> "context_ra_in_circular_movement"
            7, in 28..46 -> "context_ra_out_circular_movement"
            23 -> "context_ra_exit_right"
            22 -> "context_ra_exit_left"
            10, 12, 24, 25, 27 -> "context_ra_finish"
            13, 49, 52 -> "context_ra_take_left"
            14, 50, 53 -> "context_ra_take_right"
            15, 16, 17 -> "context_ra_boardferry"
            47 -> "context_ra_hard_turn_left"
            48 -> "context_ra_hard_turn_right"
            else -> "context_ra_via"
        }
    }
}
