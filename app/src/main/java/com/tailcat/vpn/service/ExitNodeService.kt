package com.tailcat.vpn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.tailcat.vpn.TailcatApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and visible while this phone is sharing.
 *
 * It is an ordinary foreground service: no VpnService, no TUN, no consent
 * dialog. It owns the poll loop (so sharing no longer depends on a screen
 * being open), the ongoing notification, and a wake lock held for the session
 * only. All policy lives in [ExitNodeController]; this class only hosts it.
 *
 * It is not sticky. A killed process loses the session's keys with it, so
 * there is nothing to restore, and a restart must never silently resume
 * sharing.
 */
class ExitNodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wakeLockLock = Any()
    private val notificationLock = Any()
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockGeneration = 0L
    private var renewWakeLockAtMs = Long.MAX_VALUE
    @Volatile private var lastNotificationText = ExitNodeNotificationText.STARTING
    @Volatile private var userStopped = false
    /**
     * Identifies the session allowed to end this instance. [finish] is called
     * from IO coroutines and from a Start that Android queued around a Stop;
     * only the newest start may stop the service, so an older session cannot
     * tear down the one that replaced it.
     */
    @Volatile private var startGeneration = 0L

    /** startId of the last intent handled, for stopSelf(startId). */
    @Volatile private var lastStartId = 0

    private lateinit var notifications: ExitNodeNotifications

    private val controller get() = TailcatApplication.instance.exitNodeController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = ExitNodeNotifications(this)
        notifications.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> startSharing(intent)
            ACTION_STOP -> stopSharing()
            else -> {
                // A null or unknown intent is a system restart or a stray
                // call. Never resume a share from it.
                promoteThenStop()
            }
        }
        return START_NOT_STICKY
    }

    private fun startSharing(intent: Intent) {
        // A duplicate tap while a session is live keeps that session without
        // overwriting the live ongoing notification with "Starting".
        if (runJob?.isActive == true) {
            enterForeground(lastNotificationText)
            return
        }
        // Android allows five seconds between startForegroundService and
        // startForeground, so this comes before anything that can block.
        lastNotificationText = ExitNodeNotificationText.STARTING
        enterForeground(ExitNodeNotificationText.STARTING)
        userStopped = false
        val generation = ++startGeneration

        val ttl: Long? = if (intent.getBooleanExtra(EXTRA_NO_LIMIT, false)) null
        else intent.getLongExtra(EXTRA_TTL_SECONDS, 0L)
        val admission = when (intent.getStringExtra(EXTRA_ADMISSION)) {
            ADMISSION_OPEN -> ExitNodeAdmission.OPEN
            else -> ExitNodeAdmission.APPROVAL
        }
        val config = runCatching {
            val relay = intent.getStringExtra(EXTRA_RELAY_HOSTNAME)?.let { host ->
                ExitNodeRelay(
                    hostname = host,
                    derpPort = intent.getIntExtra(EXTRA_RELAY_DERP_PORT, ExitNodeRelay.DEFAULT_DERP_PORT),
                    stunPort = intent.getIntExtra(EXTRA_RELAY_STUN_PORT, ExitNodeRelay.DEFAULT_STUN_PORT)
                )
            }
            ExitNodeConfig(ttl, admission, relay)
        }.getOrElse {
            controller.fail(it.message ?: "Invalid sharing configuration.")
            finish(generation)
            return
        }

        notifications.clearEnded()
        notifications.clearPendingAlert()
        acquireWakeLock(ttl, generation)
        runJob = scope.launch { run(config, generation) }
    }

    private suspend fun run(config: ExitNodeConfig, generation: Long) {
        // A start that superseded a stop must not race the native close: an
        // old controller.stop() would end the session we are about to build.
        pendingStopJob?.join()
        if (generation != startGeneration) {
            finish(generation)
            return
        }
        // The engine resolves hostnames (the DERP map host) only through the DNS
        // servers Android reported to it. The VPN service pushes a fresh state
        // before it connects; sharing needs the same, because the app's single
        // push at launch can predate the networks being known.
        val app = TailcatApplication.instance
        runCatching { app.tunnelEngine.updateNetworkState(app.networkMonitor.getNetworkStateJSON()) }

        val refusal = controller.start(config)
        if (refusal != null) {
            // The controller records engine failures itself; a refusal made
            // before it began (the VPN is up) is recorded here.
            controller.fail(refusal)
            finish(generation)
            return
        }

        var lastPending = 0
        var lastText = ""
        // A newer start replaces this session; stop polling for the old one
        // instead of running a second loop beside it.
        while (scope.isActive && controller.isActive && generation == startGeneration) {
            delay(POLL_MS)
            renewWakeLockIfDue(generation)
            controller.refresh()
            val sharing = controller.state.value as? ExitNodeUiState.Sharing ?: break
            val text = ExitNodeNotificationText.sharingText(sharing.stats, System.currentTimeMillis() / 1000)
            synchronized(notificationLock) {
                if (generation != startGeneration) return@synchronized
                if (text != lastText) {
                    lastNotificationText = text
                    notifications.update(text)
                    lastText = text
                }
                val pending = sharing.stats.pending.size
                if (pending > lastPending) notifications.alertPending()
                if (pending == 0 && lastPending > 0) notifications.clearPendingAlert()
                lastPending = pending
            }
        }
        finish(generation)
    }

    private fun stopSharing() {
        // Ending the controller session also ends the poll loop, which then
        // finishes the service. Done off the main thread: native close blocks.
        userStopped = true
        lastNotificationText = ExitNodeNotificationText.TITLE
        enterForeground(ExitNodeNotificationText.TITLE)
        // The stop owns the service until a newer start replaces it. Without
        // this generation a start Android queued around this stop would be
        // torn down when the native close finished.
        val generation = ++startGeneration
        pendingStopJob = scope.launch {
            controller.stop()
            finish(generation)
        }
    }

    /** Satisfies the foreground contract for an intent we then decline. */
    private fun promoteThenStop() {
        enterForeground(ExitNodeNotificationText.TITLE)
        finish(++startGeneration)
    }

    private fun enterForeground(text: String) {
        val notification = notifications.ongoing(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                ExitNodeNotifications.ONGOING_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(ExitNodeNotifications.ONGOING_ID, notification)
        }
    }

    private fun finish(generation: Long) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { finish(generation) }
            return
        }
        synchronized(notificationLock) {
            // A newer start owns the service, its notification and its wake lock.
            if (generation != startGeneration) return
            releaseWakeLock(generation)
            notifications.clearPendingAlert()
            // Someone who pressed Stop knows; anyone else (expiry, a failure, the
            // VPN taking over) may not be looking at the app.
            if (!userStopped) {
                (controller.state.value as? ExitNodeUiState.Ended)?.let { notifications.ended(it.reason, it.message) }
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            // Not stopSelf(): a start Android already queued for this instance
            // must reach onStartCommand instead of being dropped. stopSelf(id)
            // keeps the service alive when a newer start id exists.
            stopSelf(lastStartId)
        }
    }

    private fun acquireWakeLock(ttlSeconds: Long?, generation: Long) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // The timeout bounds the lock even if this class never reaches
        // finish(): a timed session's lock cannot outlive it by more than a
        // minute. A session without a limit holds it in renewed chunks, so a
        // poll loop that stops renewing (a hung process) lets it lapse.
        val timeoutMs = if (ttlSeconds != null) (ttlSeconds + WAKE_LOCK_SLACK_SECONDS) * 1000 else WAKE_LOCK_CHUNK_MS
        synchronized(wakeLockLock) {
            wakeLockGeneration = generation
            val lock = wakeLock
            if (lock?.isHeld == true) {
                // A start that superseded a stopping session replaces the old
                // timeout. Not reference counted, so this does not stack holds.
                lock.acquire(timeoutMs)
            } else {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                    setReferenceCounted(false)
                    acquire(timeoutMs)
                }
            }
            renewWakeLockAtMs = if (ttlSeconds == null) SystemClock.elapsedRealtime() + WAKE_LOCK_CHUNK_MS / 2 else Long.MAX_VALUE
        }
    }

    private fun renewWakeLockIfDue(generation: Long) {
        synchronized(wakeLockLock) {
            if (generation != startGeneration || generation != wakeLockGeneration || !scope.isActive) return
            val lock = wakeLock ?: return
            if (SystemClock.elapsedRealtime() < renewWakeLockAtMs) return
            // Not reference counted, so this replaces the timeout rather than
            // stacking a second hold.
            lock.acquire(WAKE_LOCK_CHUNK_MS)
            renewWakeLockAtMs = SystemClock.elapsedRealtime() + WAKE_LOCK_CHUNK_MS / 2
        }
    }

    private fun releaseWakeLock(generation: Long? = null) {
        synchronized(wakeLockLock) {
            if (generation != null && generation != wakeLockGeneration) return
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            renewWakeLockAtMs = Long.MAX_VALUE
        }
    }

    override fun onDestroy() {
        releaseWakeLock(null)
        // A destroyed service must not leave a live node with nobody watching.
        // Scope stop to the controller generation active at destroy time so an
        // already-restarted session cannot be torn down by this cleanup.
        val c = controller
        val ctrlGen = c.currentGeneration()
        pendingStopJob = CoroutineScope(Dispatchers.IO).launch { runCatching { c.stopIfGeneration(ctrlGen) } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.tailcat.vpn.ACTION_SHARE_START"
        const val ACTION_STOP = "com.tailcat.vpn.ACTION_SHARE_STOP"
        const val EXTRA_TTL_SECONDS = "ttlSeconds"
        const val EXTRA_NO_LIMIT = "noTimeLimit"
        const val EXTRA_ADMISSION = "admission"
        const val EXTRA_RELAY_HOSTNAME = "relayHostname"
        const val EXTRA_RELAY_DERP_PORT = "relayDerpPort"
        const val EXTRA_RELAY_STUN_PORT = "relayStunPort"
        const val ADMISSION_OPEN = "open"
        const val ADMISSION_APPROVAL = "approval"

        private const val POLL_MS = 1_000L
        private const val WAKE_LOCK_TAG = "OpenTailcat:ExitNode"
        private const val WAKE_LOCK_SLACK_SECONDS = 60L
        private const val WAKE_LOCK_CHUNK_MS = 60 * 60 * 1000L

        /** Pending native stop across service instances; a new start joins it first. */
        @Volatile
        private var pendingStopJob: Job? = null

        fun start(context: Context, config: ExitNodeConfig) {
            val intent = Intent(context, ExitNodeService::class.java)
                .setAction(ACTION_START)
                .apply {
                    val ttl = config.ttlSeconds
                    if (ttl == null) putExtra(EXTRA_NO_LIMIT, true) else putExtra(EXTRA_TTL_SECONDS, ttl)
                }
                .putExtra(
                    EXTRA_ADMISSION,
                    if (config.admission == ExitNodeAdmission.OPEN) ADMISSION_OPEN else ADMISSION_APPROVAL
                )
                .apply {
                    config.relay?.let { relay ->
                        putExtra(EXTRA_RELAY_HOSTNAME, relay.host)
                        putExtra(EXTRA_RELAY_DERP_PORT, relay.derpPort)
                        putExtra(EXTRA_RELAY_STUN_PORT, relay.stunPort)
                    }
                }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ExitNodeService::class.java).setAction(ACTION_STOP)

        fun stop(context: Context) {
            ContextCompat.startForegroundService(context, stopIntent(context))
        }
    }
}
