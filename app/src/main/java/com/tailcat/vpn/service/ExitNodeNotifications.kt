package com.tailcat.vpn.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.tailcat.vpn.R
import com.tailcat.vpn.ui.MainActivity

/** The three notifications sharing uses: ongoing status, a heads-up request alert, and the ending note. */
class ExitNodeNotifications(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        manager.createNotificationChannel(
            NotificationChannel(
                STATUS_CHANNEL,
                context.getString(R.string.share_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.share_channel_desc)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL,
                context.getString(R.string.share_alert_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = context.getString(R.string.share_alert_channel_desc) }
        )
    }

    fun ongoing(text: String): Notification {
        val stop = PendingIntent.getService(
            context,
            REQUEST_STOP,
            ExitNodeService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(R.drawable.ic_vpn_status)
            .setContentTitle(ExitNodeNotificationText.TITLE)
            .setContentText(text)
            .setContentIntent(openShare())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Counts and times only, but still not for the lock screen.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Stopping sharing only reduces exposure, so it needs no unlock.
            .addAction(R.drawable.ic_vpn_status, "Stop sharing", stop)
            .build()
    }

    fun update(text: String) {
        manager.notify(ONGOING_ID, ongoing(text))
    }

    fun alertPending() {
        manager.notify(
            ALERT_ID,
            NotificationCompat.Builder(context, ALERT_CHANNEL)
                .setSmallIcon(R.drawable.ic_vpn_status)
                .setContentTitle(ExitNodeNotificationText.PENDING_TITLE)
                .setContentText(ExitNodeNotificationText.PENDING_TEXT)
                .setContentIntent(openShare())
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setVisibility(NotificationCompat.VISIBILITY_SECRET)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
    }

    fun clearPendingAlert() = manager.cancel(ALERT_ID)

    fun ended(reason: ExitNodeEnd, message: String) {
        manager.notify(
            ENDED_ID,
            NotificationCompat.Builder(context, STATUS_CHANNEL)
                .setSmallIcon(R.drawable.ic_vpn_status)
                .setContentTitle(ExitNodeNotificationText.endedTitle(reason))
                .setContentText(message)
                .setContentIntent(openShare())
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        )
    }

    fun clearEnded() = manager.cancel(ENDED_ID)

    private fun openShare(): PendingIntent = PendingIntent.getActivity(
        context,
        REQUEST_OPEN,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(MainActivity.EXTRA_SCREEN, MainActivity.SCREEN_SHARE)
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    companion object {
        const val STATUS_CHANNEL = "tailcat_share_status"
        const val ALERT_CHANNEL = "tailcat_share_alert"
        const val ONGOING_ID = 1002
        const val ALERT_ID = 1003
        const val ENDED_ID = 1004
        private const val REQUEST_STOP = 10
        private const val REQUEST_OPEN = 11
    }
}
