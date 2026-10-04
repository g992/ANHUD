package com.g992.anhud

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Android Auto turn-by-turn from Headunit Revived (contributed in PR #3).
 *
 * Contract verified in Bastel2020/headunit-revived-broadcasts, update-navigation at b630e36:
 * contract/HeadUnitIntent.kt and aap/AapNavigationHelper.kt. Requires that custom HUR build.
 */
class AndroidAutoNavProvider(private val context: Context) : NavDataProvider {
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            try {
                handleUpdate(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process Headunit broadcast", e)
                UiLogStore.append(LogCategory.NAVIGATION, "android auto ошибка: ${e.message}")
            }
        }
    }

    override fun start() {
        if (registered) {
            return
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(ACTION_NAVIGATION_UPDATE),
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        UiLogStore.append(LogCategory.NAVIGATION, "android auto: ожидание данных Headunit Revived")
    }

    override fun stop() {
        if (!registered) {
            return
        }
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver already unregistered", e)
        }
        registered = false
    }

    private fun handleUpdate(intent: Intent) {
        if (!registered || NavDataSourcePrefs.source(context) != NavDataSource.ANDROID_AUTO) return
        val distanceMeters = intent.getIntExtra(EXTRA_DISTANCE_METERS, -1)
        val road = normalize(intent.getStringExtra(EXTRA_ROAD))
        val nextEventType = intent.getIntExtra(EXTRA_NEXT_EVENT_TYPE, 0)
        val actionText = normalize(intent.getStringExtra(EXTRA_ACTION_TEXT))
        val turnSide = intent.getIntExtra(EXTRA_TURN_SIDE, TURN_SIDE_UNSPECIFIED)
        val turnNumber = intent.getIntExtra("turn_number", -1)
        val totalDistanceMeters = intent.getIntExtra(EXTRA_TOTAL_DISTANCE_METERS, -1)
        val totalTimeSeconds = readLongOrInt(intent, EXTRA_TOTAL_TIME_SECONDS)
        val estimatedArrival = normalize(intent.getStringExtra(EXTRA_ESTIMATED_ARRIVAL))
        Log.d(
            TAG,
            "Headunit nav: dist=$distanceMeters road=\"$road\" event=$nextEventType side=$turnSide " +
                "total=$totalDistanceMeters m/$totalTimeSeconds s eta=\"$estimatedArrival\" action=\"$actionText\""
        )
        UiLogStore.append(
            LogCategory.NAVIGATION,
            "android auto: ${distanceMeters}м event=$nextEventType side=$turnSide улица=\"$road\" " +
                "осталось=${totalDistanceMeters}м/${totalTimeSeconds}с"
        )
        if (distanceMeters < 0 && nextEventType == 0) {
            // Headunit clears its snapshot on INSTRUMENT_CLUSTER_STOP and broadcasts the empty state.
            NavigationReceiver.endProviderNavigation(context, NavDataSource.ANDROID_AUTO, "android auto: стоп")
            return
        }
        val maneuverDistance = ProviderNavFormat.meters(distanceMeters).ifBlank { actionText }
        NavigationReceiver.applyProviderUpdate(
            context = context,
            source = NavDataSource.ANDROID_AUTO,
            action = ACTION_NAVIGATION_UPDATE,
            update = ProviderNavUpdate(
                maneuverType = maneuverKey(nextEventType, turnSide, turnNumber),
                maneuverDistance = maneuverDistance,
                street = road,
                remainingDistance = ProviderNavFormat.meters(totalDistanceMeters),
                remainingTime = ProviderNavFormat.seconds(totalTimeSeconds),
                arrival = estimatedArrival,
                exitNumber = turnNumber.takeIf { nextEventType in 12..13 && it in 1..19 }
            ),
            touchTimeout = true
        )
    }

    private fun readLongOrInt(intent: Intent, key: String): Long {
        @Suppress("DEPRECATION")
        return when (val value = intent.extras?.get(key)) {
            is Long -> value
            is Int -> value.toLong()
            else -> -1L
        }
    }

    private fun normalize(value: String?): String =
        value.orEmpty().replace(' ', ' ').replace(Regex("\\s+"), " ").trim()

    companion object {
        private const val TAG = "AndroidAutoNavProvider"

        const val ACTION_NAVIGATION_UPDATE = "com.andrerinas.headunitrevived.NAVIGATION_UPDATE"
        private const val EXTRA_DISTANCE_METERS = "distance_meters"
        private const val EXTRA_ROAD = "road"
        private const val EXTRA_NEXT_EVENT_TYPE = "next_event_type"
        private const val EXTRA_ACTION_TEXT = "action_text"
        private const val EXTRA_TURN_SIDE = "turn_side"
        private const val EXTRA_TOTAL_DISTANCE_METERS = "total_distance_meters"
        private const val EXTRA_TOTAL_TIME_SECONDS = "total_time_seconds"
        private const val EXTRA_ESTIMATED_ARRIVAL = "estimated_arrival"
        private const val TURN_SIDE_LEFT = 1
        private const val TURN_SIDE_RIGHT = 2
        private const val TURN_SIDE_UNSPECIFIED = 3

        /**
         * AA NextTurnDetail.NextEvent wire values -> context_ra_* drawable keys. Unspecified side
         * falls back to right-hand traffic (U-turns go left, exits/merges go right).
         */
        internal fun maneuverKey(nextEventType: Int, turnSide: Int, turnNumber: Int = -1): String {
            val left = turnSide == TURN_SIDE_LEFT
            val right = turnSide == TURN_SIDE_RIGHT
            fun sided(leftKey: String, rightKey: String, default: String) = when {
                left -> leftKey
                right -> rightKey
                else -> default
            }
            return when (nextEventType) {
                1, 2, 14 -> "context_ra_forward" // DEPART, NAME_CHANGE, STRAIGHT
                3 -> sided("context_ra_take_left", "context_ra_take_right", "context_ra_forward") // SLIGHT_TURN
                4 -> sided("context_ra_turn_left", "context_ra_turn_right", "context_ra_turn_right") // TURN
                5 -> sided("context_ra_hard_turn_left", "context_ra_hard_turn_right", "context_ra_hard_turn_right")
                6 -> sided("context_ra_turn_back_left", "context_ra_turn_back_right", "context_ra_turn_back_left")
                7, 8 -> sided("context_ra_exit_left", "context_ra_exit_right", "context_ra_exit_right") // ON/OFF_RAMP
                9, 10 -> sided("context_ra_take_left", "context_ra_take_right", "context_ra_take_right") // FORK, MERGE
                11 -> "context_ra_in_circular_movement"
                13 -> if (turnNumber > 0) "context_ra_out_circular_movement" else "context_ra_in_circular_movement"
                12 -> "context_ra_out_circular_movement" // ROUNDABOUT_EXIT
                16, 17 -> "context_ra_boardferry" // FERRY_BOAT, FERRY_TRAIN
                18, 19 -> "context_ra_finish" // DESTINATION (18 in the HUR proto, 19 in later AA specs)
                else -> "context_ra_via"
            }
        }
    }
}
