package com.g992.anhud.hudbridge

/** Explicit HUD camera selection; protocol roles are trigger slots, inputId remains the camera. */
internal data class CameraShowRequest(val roles: List<Int>, val seconds: Int) {
    fun config(base: Gcp.CameraConfig): Gcp.CameraConfig {
        val selected = roles.map { role -> base.views.first { it.role == role } }
        val views = if (selected.size == 1) {
            listOf(selected.single().copy(role = Gcp.CAM_ROLE_REAR, enabled = true, holdMs = 0))
        } else {
            selected.map { view ->
                val slot = when (view.role) {
                    Gcp.CAM_ROLE_LEFT -> Gcp.CAM_ROLE_LEFT
                    Gcp.CAM_ROLE_RIGHT -> Gcp.CAM_ROLE_RIGHT
                    else -> if (Gcp.CAM_ROLE_LEFT in roles) Gcp.CAM_ROLE_RIGHT else Gcp.CAM_ROLE_LEFT
                }
                view.copy(
                    role = slot,
                    rect = base.views.first { it.role == slot }.rect,
                    enabled = true,
                    holdMs = 0
                )
            }
        }
        return Gcp.CameraConfig(enabled = true, views = views, hazardBoth = selected.size == 2)
    }

    val gear: Int get() = if (roles.size == 1) Gcp.GEAR_REVERSE else Gcp.GEAR_PARK
    val flags: Int get() = Gcp.VEH_TURN_VALID or if (roles.size == 2) {
        Gcp.VEH_TURN_LEFT or Gcp.VEH_TURN_RIGHT or Gcp.VEH_HAZARD
    } else 0

    companion object {
        const val ACTION = "com.g992.anhud.SHOW_CAMERAS"

        /** Invalid requests throw, leaving the current selection untouched. */
        fun parse(cameras: String, seconds: Int = 0): CameraShowRequest {
            require(seconds >= 0) { "seconds должен быть >= 0" }
            val roles = cameras.split(',', '+').map { name ->
                when (name.trim().lowercase()) {
                    "rear" -> Gcp.CAM_ROLE_REAR
                    "left" -> Gcp.CAM_ROLE_LEFT
                    "right" -> Gcp.CAM_ROLE_RIGHT
                    else -> throw IllegalArgumentException("неизвестная камера: $name (rear|left|right)")
                }
            }
            require(roles.size in 1..2 && roles.distinct().size == roles.size) {
                "нужна одна камера или две разные камеры"
            }
            return CameraShowRequest(roles, seconds)
        }
    }
}
