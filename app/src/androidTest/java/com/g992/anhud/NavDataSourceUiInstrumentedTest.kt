package com.g992.anhud

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavDataSourceUiInstrumentedTest {
    @Test
    fun roundaboutExitNumbersRenderInTheCenterAndKeepSeparateCachedIcons() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val key = "context_ra_out_circular_movement"
        val plain = ProviderNavFormat.maneuverBitmap(context, key)!!
        val first = ProviderNavFormat.maneuverBitmap(context, key, 1)!!
        val last = ProviderNavFormat.maneuverBitmap(context, key, 19)!!
        assertSame(first, ProviderNavFormat.maneuverBitmap(context, key, 1))
        assertNotSame(first, last)
        fun centerChanges(icon: Bitmap): Int {
            var changes = 0
            for (y in 44..84) for (x in 36..76) {
                if (icon.getPixel(x, y) != plain.getPixel(x, y)) changes++
            }
            return changes
        }
        assertTrue(centerChanges(first) > 30)
        assertTrue(centerChanges(last) > 30)
        val preview = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(preview)
        canvas.drawColor(Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(first, 16f, 8f, paint)
        canvas.drawBitmap(last, 176f, 8f, paint)
        canvas.drawBitmap(first, null, Rect(48, 160, 112, 224), paint)
        canvas.drawBitmap(last, null, Rect(208, 160, 272, 224), paint)
        File(context.filesDir, "roundabout-exits.png").outputStream().use {
            assertTrue(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test
    fun switchesAreExclusivePersistAndCardsFollowActualData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val original = NavDataSourcePrefs.source(context)
        try {
            NavDataSourcePrefs.setSource(context, NavDataSource.NONE)
            ActivityScenario.launch<SettingsActivity>(Intent(context, SettingsActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.legacyExperimentalVisibilityToggle).performClick()
                    val carplay = activity.findViewById<SwitchCompat>(R.id.carPlayDataSwitch)
                    val auto = activity.findViewById<SwitchCompat>(R.id.androidAutoDataSwitch)
                    assertEquals("Данные из HUR (Android Auto)", auto.text.toString())
                    assertEquals(0, activity.resources.getIdentifier("carPlayAdbdRootSwitch", "id", activity.packageName))
                    assertEquals(0, activity.resources.getIdentifier("carPlayRestartUiSwitch", "id", activity.packageName))
                    carplay.performClick()
                    assertTrue(carplay.isChecked)
                    assertFalse(auto.isChecked)
                    assertEquals(NavDataSource.CARPLAY, NavDataSourcePrefs.source(context))
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.carPlayPatchPanel).visibility)
                    auto.performClick()
                    assertFalse(carplay.isChecked)
                    assertTrue(auto.isChecked)
                    assertEquals(NavDataSource.ANDROID_AUTO, NavDataSourcePrefs.source(context))
                    assertEquals(View.GONE, activity.findViewById<View>(R.id.carPlayPatchPanel).visibility)
                    auto.performClick()
                    assertEquals(NavDataSource.NONE, NavDataSourcePrefs.source(context))
                    carplay.performClick()
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<SwitchCompat>(R.id.carPlayDataSwitch).isChecked)
                    assertFalse(activity.findViewById<SwitchCompat>(R.id.androidAutoDataSwitch).isChecked)
                }
            }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = activity.findViewById<TextView>(R.id.navDataSourceSummary)
                    val arrow = activity.findViewById<TextView>(R.id.arrowDataSourceSummary)
                    assertEquals(activity.getString(R.string.nav_data_source_waiting_carplay), nav.text)
                    NavigationReceiver.applyProviderUpdate(
                        context, NavDataSource.CARPLAY, "test_carplay",
                        ProviderNavUpdate("context_ra_turn_left", "250 м", "Улица", "12 км", "20 мин", "21:15"), false
                    )
                    assertEquals(activity.getString(R.string.nav_data_source_badge_carplay), nav.text)
                    assertEquals(nav.text, arrow.text)
                    NavigationReceiver.endProviderNavigation(context, NavDataSource.CARPLAY, "test_end")
                    assertEquals(activity.getString(R.string.nav_data_source_waiting_carplay), nav.text)
                }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                NavDataProviders.stopAll(context)
                NavDataSourcePrefs.setSource(context, original)
                NavigationHudStore.reset("test_cleanup")
            }
        }
    }
}
