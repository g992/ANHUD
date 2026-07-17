package com.g992.anhud

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CustomScriptRuntimeInstrumentedTest {
    @Test
    fun infiniteScriptTimesOutWithoutKillingHostProcess() {
        val runtime = CustomScriptRuntime(InstrumentationRegistry.getInstrumentation().targetContext)
        val latch = CountDownLatch(1)
        var result: Result<CustomScriptEvaluation>? = null

        runtime.evaluate(
            "(() => { while (true) {} })()",
            CustomScriptEnvironment(now = System.currentTimeMillis())
        ) {
            result = it
            latch.countDown()
        }

        assertTrue("sandbox did not return after timeout", latch.await(4, TimeUnit.SECONDS))
        assertTrue(result?.isFailure == true)
        assertTrue(result?.exceptionOrNull()?.message?.contains("лимит") == true)

        val recoveryLatch = CountDownLatch(1)
        var recovery: Result<CustomScriptEvaluation>? = null
        val now = System.currentTimeMillis()
        runtime.evaluate(
            "car.speed().show()",
            CustomScriptEnvironment(now = now, speed = CustomDataValue(80, now))
        ) {
            recovery = it
            recoveryLatch.countDown()
        }
        assertTrue("sandbox did not recover after timeout", recoveryLatch.await(4, TimeUnit.SECONDS))
        assertTrue(recovery?.getOrNull()?.text == "80")
        runtime.close()
    }
}
