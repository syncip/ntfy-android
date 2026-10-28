package io.heckel.ntfy.msg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * "Mark all as read": removes all message notifications, resets the "new" counters of all topics
 * and, if enabled, marks the messages as read in the web app (see [ReadSync]).
 * Used by the main screen and by the action in the permanent status bar notification.
 */
object MarkAllRead {
    private const val TAG = "NtfyMarkAllRead"

    suspend fun run(context: Context) {
        val repository = Repository.getInstance(context)
        val notifier = NotificationService(context)
        val unread = repository.getUnreadNotifications()
        Log.d(TAG, "Marking ${unread.size} notification(s) as read")
        unread.forEach { notifier.cancel(it.notificationId) }
        unread.groupBy { it.subscriptionId }.forEach { (subscriptionId, notifications) ->
            repository.getSubscription(subscriptionId)?.let {
                ReadSync.onRead(context, it.baseUrl, it.topic, notifications)
            }
            repository.markAllAsRead(subscriptionId)
        }
    }

    class Receiver : BroadcastReceiver() {
        @OptIn(DelicateCoroutinesApi::class)
        override fun onReceive(context: Context, intent: Intent) {
            Log.init(context) // Init in all entrypoints
            val pendingResult = goAsync()
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    run(context.applicationContext)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
