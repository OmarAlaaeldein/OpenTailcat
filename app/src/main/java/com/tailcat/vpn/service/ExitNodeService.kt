package com.tailcat.vpn.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
    private var runJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var renewWakeLockAtMs = Long.MAX_VALUE
    @Volatile private var userStopped = false
    private lateinit var notifications: ExitNodeNotifications

    private val controller get() = TailcatApplication.instance.exitNodeController

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = ExitNodeNotifications(this)
        notifications.createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
        // Android allows five seconds between startForegroundService and
        // startForeground, so this comes before anything that can block.
        enterForeground(ExitNodeNotificationText.STARTING)
        if (runJob?.isActive == true) return
        userStopped = false

        val ttl: Long? = if (intent.getBooleanExtra(EXTRA_NO_LIMIT, false)) null
        else intent.getLongExtra(EXTRA_TTL_SECONDS, 0L)
        val admission = when (intent.getStringExtra(EXTRA_ADMISSION)) {
            ADMISSION_OPEN -> ExitNodeAdmission.OPEN
            else -> ExitNodeAdmission.APPROVAL
        }
        val config = runCatching { ExitNodeConfig(ttl, admission) }.getOrElse {
            controller.fail(it.message ?: "Invalid sharing time")
            finish()
            return
        }

        notifications.clearEnded()
        acquireWakeLock(ttl)
        runJob = scope.launch { run(config) }
    }

    private suspend fun run(config: ExitNodeConfig) {
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
            finish()
            return
        }

        var lastPending = 0
        var lastText = ""
        while (scope.isActive && controller.isActive) {
            delay(POLL_MS)
            renewWakeLockIfDue()
            controller.refresh()
            val sharing = controller.state.value as? ExitNodeUiState.Sharing ?: break
            val text = ExitNodeNotificationText.sharingText(sharing.stats, System.currentTimeMillis() / 1000)
            if (text != lastText) {
                notifications.update(text)
                lastText = text
            }
            val pending = sharing.stats.pending.size
            if (pending > lastPending) notifications.alertPending()
            if (pending == 0 && lastPending > 0) notifications.clearPendingAlert()
            lastPending = pending
        }
        finish()
    }

    private fun stopSharing() {
        // Ending the controller session also ends the poll loop, which then
        // finishes the service. Done off the main thread: native close blocks.
        userStopped = true
        enterForeground(ExitNodeNotificationText.TITLE)
        scope.launch {
            controller.stop()
            finish()
        }
    }

    /** Satisfies the foreground contract for an intent we then decline. */
    private fun promoteThenStop() {
        enterForeground(ExitNodeNotificationText.TITLE)
        finish()
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

    private fun finish() {
        releaseWakeLock()
        notifications.clearPendingAlert()
        // Someone who pressed Stop knows; anyone else (expiry, a failure, the
        // VPN taking over) may not be looking at the app.
        if (!userStopped) {
            (controller.state.value as? ExitNodeUiState.Ended)?.let { notifications.ended(it.reason, it.message) }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock(ttlSeconds: Long?) {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        // The timeout bounds the lock even if this class never reaches
        // finish(): a timed session's lock cannot outlive it by more than a
        // minute. A session without a limit holds it in renewed chunks, so a
        // poll loop that stops renewing (a hung process) lets it lapse.
        val timeoutMs = if (ttlSeconds != null) (ttlSeconds + WAKE_LOCK_SLACK_SECONDS) * 1000 else WAKE_LOCK_CHUNK_MS
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            acquire(timeoutMs)
        }
        renewWakeLockAtMs = if (ttlSeconds == null) SystemClock.elapsedRealtime() + WAKE_LOCK_CHUNK_MS / 2 else Long.MAX_VALUE
    }

    private fun renewWakeLockIfDue() {
        val lock = wakeLock ?: return
        if (SystemClock.elapsedRealtime() < renewWakeLockAtMs) return
        // Not reference counted, so this replaces the timeout rather than
        // stacking a second hold.
        lock.acquire(WAKE_LOCK_CHUNK_MS)
        renewWakeLockAtMs = SystemClock.elapsedRealtime() + WAKE_LOCK_CHUNK_MS / 2
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        // A destroyed service must not leave a live node with nobody watching.
        // Native close can take seconds, so it runs off the main thread.
        val c = controller
        CoroutineScope(Dispatchers.IO).launch { runCatching { c.stop() } }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.tailcat.vpn.ACTION_SHARE_START"
        const val ACTION_STOP = "com.tailcat.vpn.ACTION_SHARE_STOP"
        const val EXTRA_TTL_SECONDS = "ttlSeconds"
        const val EXTRA_NO_LIMIT = "noTimeLimit"
        const val EXTRA_ADMISSION = "admission"
        const val ADMISSION_OPEN = "open"
        const val ADMISSION_APPROVAL = "approval"

        private const val POLL_MS = 1_000L
        private const val WAKE_LOCK_TAG = "OpenTailcat:ExitNode"
        private const val WAKE_LOCK_SLACK_SECONDS = 60L
        private const val WAKE_LOCK_CHUNK_MS = 60 * 60 * 1000L

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
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ExitNodeService::class.java).setAction(ACTION_STOP)

        fun stop(context: Context) {
            ContextCompat.startForegroundService(context, stopIntent(context))
        }
    }
}
