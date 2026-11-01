package io.heckel.ntfy.msg

import android.content.Context
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository

/** Read state of single messages. Must be called on a background thread. */
object MessageActions {
    /** Marks [notifications] as read, removes their popups and tells the web app (see [ReadSync]). */
    fun markRead(context: Context, notifications: List<Notification>) {
        val repository = Repository.getInstance(context)
        val notifier = NotificationService(context)
        val unread = notifications.filter { it.notificationId != 0 }
        unread.forEach { notifier.cancel(it) }
        unread.groupBy { it.subscriptionId }.forEach { (subscriptionId, messages) ->
            repository.getSubscription(subscriptionId)?.let {
                ReadSync.onRead(context, it.baseUrl, it.topic, messages)
            }
        }
        unread.forEach { repository.markAsRead(it.id) }
    }

    /** Only local: the web app has no way of being told that something is unread again. */
    fun markUnread(context: Context, notifications: List<Notification>) {
        val repository = Repository.getInstance(context)
        notifications.forEach { repository.markAsUnread(it.id) }
    }
}
