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
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YandexVisualReceiverInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val receiver = NavigationReceiver()

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
