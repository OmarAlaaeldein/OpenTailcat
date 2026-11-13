package com.tailcat.vpn

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tailcat.vpn.service.StartIdProbeService
import com.tailcat.vpn.ui.MainActivity
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the Android assumption used by Lifecycle.PendingStart and finish. */
@RunWith(AndroidJUnit4::class)
class ServiceStartIdInstrumentedTest {
    @Test
    fun stopSelfPreservesQueuedAndAlreadyDeliveredNewerStarts() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        fun intent(action: String) = Intent(context, StartIdProbeService::class.java).setAction(action)
        fun event(): StartIdProbeService.Event =
            requireNotNull(StartIdProbeService.events.poll(10, TimeUnit.SECONDS)) {
                "Android did not deliver a service lifecycle event"
            }
        fun start(action: String) = instrumentation.runOnMainSync {
            context.startService(intent(action))
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            instrumentation.runOnMainSync { context.stopService(intent(StartIdProbeService.KEEP)) }
            instrumentation.waitForIdleSync()
            StartIdProbeService.events.clear()
            try {
                // Both starts receive ids before the main looper can dispatch
                // either onStartCommand. The first stopSelf(id) must preserve
                // the second start Android has queued but not yet delivered.
                instrumentation.runOnMainSync {
                    context.startService(intent(StartIdProbeService.STOP_CURRENT))
                    context.startService(intent(StartIdProbeService.KEEP))
                }
                val first = event()
                val queued = event()
                assertEquals("start", first.kind)
                assertEquals(StartIdProbeService.STOP_CURRENT, first.action)
                assertEquals("start", queued.kind)
                assertEquals(StartIdProbeService.KEEP, queued.action)
                assertEquals("Queued start must retain the instance", first.instance, queued.instance)
                assertTrue(queued.startId > first.startId)

                // A stop with an older, already delivered id must also leave
                // the started service up. A subsequent command is a barrier
                // that detects destruction/recreation between these calls.
                start(StartIdProbeService.STOP_FIRST)
                val oldStop = event()
                assertEquals("start", oldStop.kind)
                assertEquals(first.instance, oldStop.instance)
                start(StartIdProbeService.KEEP)
                val kept = event()
                assertEquals("start", kept.kind)
                assertEquals(first.instance, kept.instance)

                // With no newer id, the current id really does stop it.
                start(StartIdProbeService.STOP_CURRENT)
                assertEquals("start", event().kind)
                val destroyed = event()
                assertEquals("destroy", destroyed.kind)
                assertEquals(first.instance, destroyed.instance)
            } finally {
                instrumentation.runOnMainSync { context.stopService(intent(StartIdProbeService.KEEP)) }
            }
        }
    }
}
