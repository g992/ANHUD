package com.g992.anhud

import androidx.annotation.IdRes
import androidx.annotation.StringRes

object GuideContent {
    data class GuideItem(
        @IdRes val targetId: Int?,
        @StringRes val titleRes: Int,
        @StringRes val bodyRes: Int
    )

    data class GuideSection(
        @StringRes val titleRes: Int,
        val items: List<GuideItem>
    )

    fun mainItems(): List<GuideItem> = listOf(
        GuideItem(
            R.id.requestPermissionButton,
            R.string.guide_main_permission_title,
            R.string.guide_main_permission_body
        ),
        GuideItem(
            R.id.overlaySwitch,
            R.string.guide_main_overlay_title,
            R.string.guide_main_overlay_body
        ),
        GuideItem(
            R.id.displaySpinner,
            R.string.guide_main_display_title,
            R.string.guide_main_display_body
        ),
        GuideItem(
            R.id.positionContainerCard,
            R.string.guide_main_container_title,
            R.string.guide_main_container_body
        ),
        GuideItem(
            R.id.positionNavCard,
            R.string.guide_main_nav_title,
            R.string.guide_main_nav_body
        ),
        GuideItem(
            R.id.positionArrowCard,
            R.string.guide_main_arrow_title,
            R.string.guide_main_arrow_body
        ),
        GuideItem(
            R.id.positionSpeedCard,
            R.string.guide_main_speed_title,
            R.string.guide_main_speed_body
        ),
        GuideItem(
            R.id.positionHudSpeedCard,
            R.string.guide_main_hudspeed_title,
            R.string.guide_main_hudspeed_body
        ),
        GuideItem(
            R.id.positionRoadCameraCard,
            R.string.guide_main_road_camera_title,
            R.string.guide_main_road_camera_body
        ),
        GuideItem(
            R.id.positionTrafficLightCard,
            R.string.guide_main_traffic_light_title,
            R.string.guide_main_traffic_light_body
        ),
        GuideItem(
            R.id.positionSpeedometerCard,
            R.string.guide_main_speedometer_title,
            R.string.guide_main_speedometer_body
        ),
        GuideItem(
            R.id.positionClockCard,
            R.string.guide_main_clock_title,
            R.string.guide_main_clock_body
        ),
        GuideItem(
            R.id.btnSettings,
            R.string.guide_main_settings_title,
            R.string.guide_main_settings_body
        ),
        GuideItem(
            R.id.btnDonate,
            R.string.guide_main_donate_title,
            R.string.guide_main_donate_body
        )
    )

    fun faqItems(): List<GuideItem> = listOf(
        GuideItem(
            null,
            R.string.guide_faq_what_am_i_installed_title,
            R.string.guide_faq_what_am_i_installed_body
        ),
        GuideItem(
            null,
            R.string.guide_faq_overlay_title,
            R.string.guide_faq_overlay_body
        ),
        GuideItem(
            null,
            R.string.guide_faq_editor_title,
            R.string.guide_faq_editor_body
        ),
        GuideItem(
            null,
            R.string.guide_faq_hud_speed_title,
            R.string.guide_faq_hud_speed_body
        ),
        GuideItem(
            null,
            R.string.guide_faq_stock_hud_unavailable_title,
            R.string.guide_faq_stock_hud_unavailable_body
        )
    )

    fun helpSections(): List<GuideSection> = listOf(
        GuideSection(
            R.string.help_section_faq,
            faqItems()
        ),
        GuideSection(
            R.string.help_section_main,
            mainItems()
        ),
        GuideSection(
            R.string.help_section_settings,
            listOf(
                GuideItem(
                    null,
                    R.string.guide_settings_timeout_near_title,
                    R.string.guide_settings_timeout_near_body
                ),
                GuideItem(
                    null,
                    R.string.guide_settings_timeout_far_title,
                    R.string.guide_settings_timeout_far_body
                ),
                GuideItem(
                    null,
                    R.string.guide_settings_traffic_light_timeout_title,
                    R.string.guide_settings_traffic_light_timeout_body
                ),
                GuideItem(
                    null,
                    R.string.guide_settings_road_camera_timeout_title,
                    R.string.guide_settings_road_camera_timeout_body
                ),
                GuideItem(
                    null,
                    R.string.guide_settings_nav_notification_timeout_title,
                    R.string.guide_settings_nav_notification_timeout_body
                ),
                GuideItem(
                    null,
                    R.string.guide_settings_speed_correction_title,
                    R.string.guide_settings_speed_correction_body
                ),
                GuideItem(
                    null,
                    R.string.guide_settings_maneuver_title,
                    R.string.guide_settings_maneuver_body
                ),
                GuideItem(
                    null,
                    R.string.guide_settings_debug_title,
                    R.string.guide_settings_debug_body
                )
            )
        ),
        GuideSection(
            R.string.help_section_editor,
            listOf(
                GuideItem(
                    null,
                    R.string.guide_editor_drag_title,
                    R.string.guide_editor_drag_body
                ),
                GuideItem(
                    null,
                    R.string.guide_editor_scale_title,
                    R.string.guide_editor_scale_body
                ),
                GuideItem(
                    null,
                    R.string.guide_editor_brightness_title,
                    R.string.guide_editor_brightness_body
                ),
                GuideItem(
                    null,
                    R.string.guide_editor_container_size_title,
                    R.string.guide_editor_container_size_body
                ),
                GuideItem(
                    null,
                    R.string.guide_editor_show_others_title,
                    R.string.guide_editor_show_others_body
                )
            )
        )
    )

    fun editorDialogItems(): List<GuideItem> = listOf(
        GuideItem(
            R.id.dialogPreviewContainer,
            R.string.guide_editor_drag_title,
            R.string.guide_editor_drag_body
        ),
        GuideItem(
            R.id.dialogScaleSeek,
            R.string.guide_editor_scale_title,
            R.string.guide_editor_scale_body
        ),
        GuideItem(
            R.id.dialogBrightnessSeek,
            R.string.guide_editor_brightness_title,
            R.string.guide_editor_brightness_body
        )
    )
}
