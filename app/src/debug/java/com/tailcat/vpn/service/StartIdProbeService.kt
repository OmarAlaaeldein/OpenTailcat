package com.tailcat.vpn.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/** Tests Android's start-id ordering assumption without a gateway or VPN consent. */
class StartIdProbeService : Service() {
    private val instance = nextInstance.incrementAndGet()
    private var firstStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (firstStartId == 0) firstStartId = startId
        events.add(Event(instance, "start", intent?.action, startId))
        when (intent?.action) {
            STOP_CURRENT -> stopSelf(startId)
            STOP_FIRST -> stopSelf(firstStartId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        events.add(Event(instance, "destroy", null, 0))
        super.onDestroy()
    }

    data class Event(val instance: Int, val kind: String, val action: String?, val startId: Int)

    companion object {
        const val KEEP = "keep"
        const val STOP_CURRENT = "stop-current"
        const val STOP_FIRST = "stop-first"
        val events = LinkedBlockingQueue<Event>()
        private val nextInstance = AtomicInteger()
    }
}
