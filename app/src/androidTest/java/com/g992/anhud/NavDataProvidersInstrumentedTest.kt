package com.g992.anhud

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavDataProvidersInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var fakeContext: CarPlayContext
    private var originalSource = NavDataSource.NONE
    private var originalNative = false

    @Before
    fun prepare() {
        originalSource = NavDataSourcePrefs.source(context)
        originalNative = OverlayPrefs.nativeNavEnabled(context)
        OverlayPrefs.setNativeNavEnabled(context, false)
        fakeContext = CarPlayContext(context)
        onMain {
            NavDataProviders.stopAll(context)
            NavigationHudStore.reset("test")
            NavDataSourcePrefs.setSource(context, NavDataSource.CARPLAY)
            NavDataProviders.applyWithCarPlayFactory(fakeContext) { CarPlayNavProvider(it) { _, _ -> } }
        }
        await { fakeContext.service.routeCallback != null }
    }

    @After
    fun cleanup() {
        onMain {
            NavDataProviders.stopAll(context)
            NavigationHudStore.reset("test_cleanup")
            NavDataSourcePrefs.setSource(context, originalSource)
            OverlayPrefs.setNativeNavEnabled(context, originalNative)
        }
    }

    @Test
    fun startupAndRetryPatchBeforeBindingAndRestartChecksAgain() {
        onMain { NavDataProviders.stopAll(context) }
        val calls = AtomicInteger()
        val isolated = CarPlayContext(context)
        fun provider() = CarPlayNavProvider(isolated) { _, active ->
            assertTrue(active())
            assertEquals(isolated.binds, calls.get())
            calls.incrementAndGet()
        }
        val first = provider()
        try {
            first.start()
            await { isolated.binds == 1 }
            assertEquals(1, calls.get())
            first.requestPatch()
            await { isolated.binds == 2 }
            assertEquals(2, calls.get())
        } finally {
            first.stop()
        }
        await { isolated.unbinds == 2 }
        val second = provider()
        try {
            second.start()
            await { isolated.binds == 3 }
            assertEquals(3, calls.get())
        } finally {
            second.stop()
        }
    }

    @Test
    fun exactLogDistanceExpiresAndCannotLeakIntoNextTurnOrEndedRoute() {
        onMain { NavDataProviders.stopAll(context) }
        val isolated = CarPlayContext(context)
        val eof = "\u0000eof"
        val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        val provider = CarPlayNavProvider(isolated, openLog = {
            object : CarPlayLogStream {
                override fun readLine(): String? = lines.take().takeIf { it != eof }
                override fun close() { lines.put(eof) }
            }
        }) { _, _ -> }
        fun sample(type: Int, meters: Int): String {
            val now = System.currentTimeMillis()
            return "${now / 1000}.${(now % 1000).toString().padStart(3, '0')} 123 456 I CP-SRV : " +
                "ManeuverType = $type, DestinationName = Тестовая улица, " +
                "DistanceRemainingToNextManeuverStr = $meters, DistanceRemainingToNextManeuverUnits = 2"
        }
        try {
            provider.start()
            await { isolated.service.routeCallback != null }
            isolated.service.route(28, 1)
            await { NavigationHudStore.snapshot().primaryText == "250 м" }
            lines.put(sample(28, 183))
            await { NavigationHudStore.snapshot().primaryText == "183 м" }
            await { NavigationHudStore.snapshot().primaryText == "250 м" }
            lines.put(sample(28, 171))
            await { NavigationHudStore.snapshot().primaryText == "171 м" }
            isolated.service.route(29, 1)
            await { NavigationHudStore.snapshot().primaryText == "250 м" }
            Thread.sleep(1200)
            assertEquals("250 м", NavigationHudStore.snapshot().primaryText)
            lines.put(sample(29, 160))
            await { NavigationHudStore.snapshot().primaryText == "160 м" }
            isolated.service.route(29, 7)
            await { NavigationHudStore.snapshot().routeActive == false }
            Thread.sleep(1200)
            assertFalse(NavigationHudStore.snapshot().routeActive == true)
        } finally { provider.stop() }
    }

    @Test
    fun transientPatchFailureRetriesThreeTimesAndStoppingCancelsRetry() {
        onMain { NavDataProviders.stopAll(context) }
        val attempts = AtomicInteger()
        val isolated = CarPlayContext(context)
        val provider = CarPlayNavProvider(isolated, patchRetryDelayMs = 100) { _, _ ->
            attempts.incrementAndGet()
            throw CarPlayPatchRetryException()
        }
        try {
            provider.start()
            await { attempts.get() == 3 && isolated.binds == 3 }
            Thread.sleep(300)
            assertEquals(3, attempts.get())
        } finally { provider.stop() }
        val stoppedAttempts = AtomicInteger()
        val stopped = CarPlayNavProvider(CarPlayContext(context), patchRetryDelayMs = 500) { _, _ ->
            stoppedAttempts.incrementAndGet()
            throw CarPlayPatchRetryException()
        }
        stopped.start()
        await { stoppedAttempts.get() == 1 }
        stopped.stop()
        Thread.sleep(700)
        assertEquals(1, stoppedAttempts.get())
    }

    @Test
    fun disablingDuringPatchPreventsBindingAndDuplicatePatch() {
        onMain { NavDataProviders.stopAll(context) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val isolated = CarPlayContext(context)
        val calls = AtomicInteger()
        val provider = CarPlayNavProvider(isolated) { _, active ->
            calls.incrementAndGet()
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            assertFalse(active())
            finished.countDown()
        }
        try {
            provider.start()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            provider.requestPatch()
            provider.stop()
            release.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            assertEquals(0, isolated.binds)
        } finally {
            provider.stop()
            release.countDown()
        }
    }

    @Test
    fun decodesBinderRouteRendersArrowAndHonorsEndAndNullPayload() {
        fakeContext.service.route(maneuver = 1, state = 1)
        await { NavigationHudStore.snapshot().source == "carplay" }
        val active = NavigationHudStore.snapshot()
        assertEquals("250 м", active.primaryText)
        assertEquals("Тестовая улица", active.secondaryText)
        assertEquals("12,5 км", active.distance)
        assertEquals("21 мин", active.time)
        assertTrue(active.arrival.matches(Regex("\\d{2}:\\d{2}")))
        assertEquals("context_ra_turn_left", active.maneuverType)
        assertNotNull(active.maneuverBitmap)
        assertTrue(active.routeActive == true)
        assertTrue(NavDataProviders.ownsActiveRoute())
        assertEquals(CarPlayNavProvider.ACTION_SERVICE, fakeContext.bindIntent?.action)
        assertEquals(CarPlayNavProvider.PACKAGE, fakeContext.bindIntent?.`package`)
        assertTrue(fakeContext.service.calls.containsAll(listOf(1, 7, 17)))

        fakeContext.service.route(maneuver = 2, state = 6, road = "", remaining = "")
        await { NavigationHudStore.snapshot().maneuverType == "context_ra_turn_right" }
        assertEquals("", NavigationHudStore.snapshot().secondaryText)
        assertEquals("", NavigationHudStore.snapshot().distance)
        fakeContext.service.route(maneuver = 12, state = 7)
        await { NavigationHudStore.snapshot().routeActive == false }
        assertNull(NavigationHudStore.snapshot().maneuverBitmap)

        fakeContext.service.route(maneuver = 47, state = 2)
        await { NavigationHudStore.snapshot().routeActive == true }
        fakeContext.service.routeNull()
        await { NavigationHudStore.snapshot().routeActive == false }
    }

    @Test
    fun camerasAndYandexCannotReplaceActiveCarPlayRoute() {
        fakeContext.service.route(1, 1)
        await { NavDataProviders.ownsActiveRoute() }
        onMain {
            val receiver = NavigationReceiver()
            receiver.onReceive(context, Intent(NavigationReceiver.ACTION_HUDSPEED_UPDATE).apply {
                putExtra("hasCamera", true)
                putExtra("distance", 500)
                putExtra("limit1", 60)
            })
            receiver.onReceive(context, Intent(NavigationReceiver.ACTION_STRELKA_EVENT_START))
            receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_SPEEDLIMIT).apply {
                putExtra("speedlimit_text", "80")
            })
            receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_NEXT_TEXT).apply {
                putExtra("next_text", "999 м")
            })
            receiver.onReceive(context, Intent(NavigationReceiver.ACTION_YANDEX_NAVIGATION_ENDED).apply {
                putExtra("source", "yandex_route_end")
            })
        }
        assertEquals("carplay", NavigationHudStore.snapshot().source)
        assertEquals("250 м", NavigationHudStore.snapshot().primaryText)
        assertTrue(NavigationHudStore.snapshot().speedLimit.isBlank())
        assertTrue(NavigationHudStore.snapshot().strelkaActive)
        fakeContext.service.session(0)
        await { NavigationHudStore.snapshot().routeActive == false }
        assertTrue(NavigationHudStore.snapshot().strelkaActive)
        assertTrue(NavigationHudStore.snapshot().hudSpeedHasCamera)
    }

    @Test
    fun unbindsAndRejectsLateCallbacksWhenSourceChanges() {
        fakeContext.service.route(2, 1)
        await { NavDataProviders.ownsActiveRoute() }
        onMain {
            NavDataSourcePrefs.setSource(context, NavDataSource.NONE)
            NavDataProviders.apply(fakeContext)
        }
        await { fakeContext.unbinds > 0 }
        assertTrue(fakeContext.service.calls.containsAll(listOf(8, 2)))
        fakeContext.service.route(47, 1)
        instrumentation.waitForIdleSync()
        assertFalse(NavigationHudStore.snapshot().routeActive == true)
    }

    @Test
    fun reconnectsAfterBindingDiesAndRejectsPreviousConnectionCallback() {
        fakeContext.service.route(1, 1)
        await { NavDataProviders.ownsActiveRoute() }
        val oldCallback = fakeContext.service.routeCallback!!
        fakeContext.connection!!.onBindingDied(ComponentName(CarPlayNavProvider.PACKAGE, "Service"))
        await { NavigationHudStore.snapshot().routeActive == false }
        await { fakeContext.binds >= 2 && fakeContext.service.routeCallback !== oldCallback }
        fakeContext.service.route(2, 1)
        await { NavigationHudStore.snapshot().maneuverType == "context_ra_turn_right" }
        fakeContext.service.route(47, 1, callback = oldCallback)
        instrumentation.waitForIdleSync()
        assertEquals("context_ra_turn_right", NavigationHudStore.snapshot().maneuverType)
    }

    @Test
    fun receivesCustomHurBroadcastOnlyWhileSelected() {
        onMain {
            NavDataSourcePrefs.setSource(context, NavDataSource.ANDROID_AUTO)
            NavDataProviders.apply(fakeContext)
        }
        context.sendBroadcast(Intent(AndroidAutoNavProvider.ACTION_NAVIGATION_UPDATE).setPackage(context.packageName).apply {
            putExtra("distance_meters", 450)
            putExtra("next_event_type", 4)
            putExtra("turn_side", 2)
            putExtra("road", "Улица HUR")
            putExtra("total_distance_meters", 2500)
            putExtra("total_time_seconds", 600L)
            putExtra("estimated_arrival", "21:15")
        })
        await { NavigationHudStore.snapshot().source == "headunit" }
        assertEquals("450 м", NavigationHudStore.snapshot().primaryText)
        assertEquals("2,5 км", NavigationHudStore.snapshot().distance)
        assertEquals("10 мин", NavigationHudStore.snapshot().time)
        assertEquals("21:15", NavigationHudStore.snapshot().arrival)
        context.sendBroadcast(Intent(AndroidAutoNavProvider.ACTION_NAVIGATION_UPDATE).setPackage(context.packageName).apply {
            putExtra("distance_meters", -1)
            putExtra("next_event_type", 0)
        })
        await { NavigationHudStore.snapshot().routeActive == false }
        onMain {
            NavDataSourcePrefs.setSource(context, NavDataSource.NONE)
            NavDataProviders.apply(fakeContext)
        }
        context.sendBroadcast(Intent(AndroidAutoNavProvider.ACTION_NAVIGATION_UPDATE).setPackage(context.packageName).apply {
            putExtra("distance_meters", 200)
            putExtra("next_event_type", 4)
        })
        instrumentation.waitForIdleSync()
        assertFalse(NavigationHudStore.snapshot().routeActive == true)
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("Timed out", condition())
    }

    private class CarPlayContext(base: Context) : ContextWrapper(base) {
        val service = FakeCarPlayService()
        @Volatile var binds = 0
        @Volatile var unbinds = 0
        @Volatile var connection: ServiceConnection? = null
        @Volatile var bindIntent: Intent? = null
        override fun getApplicationContext(): Context = this
        override fun bindService(intent: Intent, conn: ServiceConnection, flags: Int): Boolean {
            bindIntent = intent
            connection = conn
            binds++
            conn.onServiceConnected(ComponentName(CarPlayNavProvider.PACKAGE, "Service"), service)
            return true
        }
        override fun unbindService(conn: ServiceConnection) { unbinds++ }
    }

    private class FakeCarPlayService : Binder() {
        val calls = CopyOnWriteArrayList<Int>()
        @Volatile var sessionStatus = 2
        @Volatile var routeCallback: IBinder? = null
        @Volatile var sessionCallback: IBinder? = null
        init { attachInterface(null, CarPlayNavProvider.SERVICE_DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            data.enforceInterface(CarPlayNavProvider.SERVICE_DESCRIPTOR)
            calls += code
            when (code) {
                1 -> sessionCallback = data.readStrongBinder()
                7 -> routeCallback = data.readStrongBinder()
                2, 8 -> data.readStrongBinder()
                17 -> {
                    reply!!.writeNoException()
                    reply.writeInt(sessionStatus)
                    return true
                }
                else -> return false
            }
            reply?.writeNoException()
            return true
        }

        fun route(maneuver: Int, state: Int, road: String = "Тестовая улица", remaining: String = "12,5", callback: IBinder = routeCallback!!) {
            send(callback, CarPlayNavProvider.ROUTE_CALLBACK_DESCRIPTOR, 1) {
                it.writeInt(1) // Parcelable present
                it.writeInt(maneuver)
                it.writeString("250")
                it.writeInt(2)
                it.writeString(road)
                it.writeString(remaining)
                it.writeInt(0)
                it.writeLong(1234)
                it.writeInt(state)
                it.writeInt(3)
                it.writeInt(0)
                it.writeInt(90)
            }
        }
        fun routeNull() = send(routeCallback!!, CarPlayNavProvider.ROUTE_CALLBACK_DESCRIPTOR, 1) { it.writeInt(0) }
        fun session(status: Int) = send(sessionCallback!!, CarPlayNavProvider.SERVICE_CALLBACK_DESCRIPTOR, 2) { it.writeInt(status) }

        private fun send(binder: IBinder, descriptor: String, code: Int, write: (Parcel) -> Unit) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(descriptor)
                write(data)
                assertTrue(binder.transact(code, data, reply, 0))
                reply.readException()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
    }
}
