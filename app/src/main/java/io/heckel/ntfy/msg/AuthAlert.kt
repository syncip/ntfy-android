package io.heckel.ntfy.msg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.User
import io.heckel.ntfy.ui.Colors
import io.heckel.ntfy.ui.SettingsActivity
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.shortUrl

/**
 * Warns once when the server rejects the stored token or password, because without it messages of
 * protected topics stop arriving and nothing else would tell the user. The state is also shown by
 * the status dot on the main screen. It clears itself as soon as the server accepts the login again.
 */
object AuthAlert {
    private const val TAG = "NtfyAuthAlert"
    private const val CHANNEL_ID = "ntfy-connection-alert" // Same as the connection-lost alert
    private const val NOTIFICATION_ID = 2588

    fun onRejected(context: Context, user: User) {
        val repository = Repository.getInstance(context)
        val failed = repository.getAuthFailedBaseUrls()
        if (user.baseUrl in failed) return
        Log.w(TAG, "Login of ${user.username} on ${user.baseUrl} was rejected")
        repository.setAuthFailedBaseUrls(failed + user.baseUrl)
        show(context, user)
    }

    fun onAccepted(context: Context, user: User) {
        val repository = Repository.getInstance(context)
        val failed = repository.getAuthFailedBaseUrls()
        if (user.baseUrl !in failed) return
        val remaining = failed - user.baseUrl
        repository.setAuthFailedBaseUrls(remaining)
        if (remaining.isEmpty()) notificationManager(context).cancel(NOTIFICATION_ID)
    }

    private fun show(context: Context, user: User) {
        val manager = notificationManager(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_connection_alert_name), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val contentIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID, Intent(context, SettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = context.getString(R.string.auth_alert_text, user.username, shortUrl(user.baseUrl))
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(Colors.notificationIcon(context))
            .setContentTitle(context.getString(R.string.auth_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun notificationManager(context: Context) =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
}
