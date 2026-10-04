package com.g992.anhud

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YandexVisualReceiverInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val receiver = NavigationReceiver()

    private var originalSource = NavDataSource.NONE

    @Before fun prepare() {
        originalSource = NavDataSourcePrefs.source(context)
        NavDataSourcePrefs.setSource(context, NavDataSource.NONE)
    }

    @After fun cleanup() {
        YandexVisualStore.endRoute()
        NavDataSourcePrefs.setSource(context, originalSource)
    }

    @Test
    fun externalSourceClearsCachedVisualsAndRejectsFramesWhileWaitingForRoute() {
        val source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        val jpeg = ByteArrayOutputStream().use { stream ->
            source.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            stream.toByteArray()
        }
        YandexVisualStore.acceptLanes(source)
        YandexVisualStore.acceptLaneQueue("1,0,1")
        YandexVisualStore.acceptMinimap(jpeg, true)
        NavDataSourcePrefs.setSource(context, NavDataSource.CARPLAY)
        assertEquals(YandexVisualSnapshot(), YandexVisualStore.snapshot())
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_LANE_SIGN).putExtra("lanes", "1,1,1"))
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_MINIMAP)
            .putExtra("minimap_jpeg", jpeg).putExtra("minimap_has_route", true))
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_JAM_IMAGE).putExtra("jam_bitmap", source))
        // Store also rejects a late frame delivered directly by an asynchronous caller.
        YandexVisualStore.acceptMinimap(jpeg, true)
        YandexVisualStore.acceptLanes(source)
        Thread.sleep(200)
        assertEquals(YandexVisualSnapshot(), YandexVisualStore.snapshot())
        NavDataSourcePrefs.setSource(context, NavDataSource.ANDROID_AUTO)
        assertEquals(YandexVisualSnapshot(), YandexVisualStore.snapshot())
        NavDataSourcePrefs.setSource(context, NavDataSource.NONE)
        assertEquals(YandexVisualSnapshot(), YandexVisualStore.snapshot())
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_LANE_SIGN).putExtra("lanes", "1,0,0"))
        assertEquals("1,0,0", YandexVisualStore.snapshot().laneQueue)
    }

    @Test
    fun receivesImagesAndClearsThemAtRouteEnd() {
        YandexVisualStore.endRoute()
        val source = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val jpeg = ByteArrayOutputStream().use { stream ->
            assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 90, stream))
            stream.toByteArray()
        }

        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_MINIMAP).apply {
            putExtra("minimap_jpeg", jpeg)
            putExtra("minimap_has_route", true)
        })
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_JAM_IMAGE).apply {
            putExtra("jam_bitmap", source)
        })
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_LANE_SIGN).apply {
            putExtra("lanes", "1,0,1")
        })
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_LANE_DIST).apply {
            putExtra("dist", "350")
            putExtra("metrics", "м")
        })
        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_LANES_BITMAP).apply {
            putExtra("lanes_bitmap", source)
        })

        val deadline = System.currentTimeMillis() + 3000
        while (YandexVisualStore.snapshot().minimap == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        val active = YandexVisualStore.snapshot()
        assertEquals(32, active.minimap?.width)
        assertEquals(24, active.minimap?.height)
        assertNotNull(active.jams)
        assertNotNull(active.lanes)
        assertEquals("1,0,1", active.laneQueue)
        assertEquals("350м", active.laneDistance)
        assertTrue(active.hasRoute)

        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_NAVIGATION_ENDED).apply {
            putExtra("source", "yandex_lane_clear")
            putExtra("route_gone", true)
        })
        val afterJunction = YandexVisualStore.snapshot()
        assertNotNull(afterJunction.minimap)
        assertNotNull(afterJunction.jams)
        assertNull(afterJunction.lanes)
        assertTrue(afterJunction.hasRoute)

        receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_NAVIGATION_ENDED).apply {
            putExtra("source", "yandex_route_end")
            putExtra("route_gone", true)
        })
        val ended = YandexVisualStore.snapshot()
        assertNull(ended.minimap)
        assertNull(ended.jams)
        assertNull(ended.lanes)
        assertFalse(ended.hasRoute)
    }
}
