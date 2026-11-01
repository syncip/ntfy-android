package io.heckel.ntfy.msg

import android.content.Context
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.Log

/**
 * Safety net for the live connection, which cannot always tell that it is dead (e.g. when a proxy
 * or NAT silently drops it): fetches missed messages, shows their notifications, and treats every
 * message that had to be fetched for an instant subscription as proof that its connection is
 * broken, so it is rebuilt right away instead of waiting for a ping to fail.
 */
object ConnectionCheck {
    private const val TAG = "NtfyConnectionCheck"
    private const val MIN_INTERVAL_MILLIS = 30 * 1000L
    @Volatile var lastCheckMillis = 0L
        private set

    /** Like [check], but skipped if the last check was less than [MIN_INTERVAL_MILLIS] ago. */
    suspend fun checkIfDue(context: Context) {
        if (System.currentTimeMillis() - lastCheckMillis < MIN_INTERVAL_MILLIS) return
        check(context)
    }

    suspend fun check(context: Context) {
        lastCheckMillis = System.currentTimeMillis()
        val repository = Repository.getInstance(context)
        if (repository.getAppQuit()) return
        val dispatcher = NotificationDispatcher(context, repository)
        val poller = Poller(ApiService(context), repository)
        val missed = mutableMapOf<Subscription, List<Notification>>()
        repository.getSubscriptions().forEach { subscription ->
            try {
                val notifications = poller.poll(subscription)
                notifications.forEach { dispatcher.dispatch(subscription, it) }
                if (notifications.isNotEmpty()) missed[subscription] = notifications
            } catch (e: Exception) {
                Log.w(TAG, "Failed checking messages: ${e.message}")
            }
        }
        reconnectIfMissed(context, missed.keys)
    }

    /** Rebuilds the connections of [subscriptions], for which messages were fetched instead of pushed. */
    fun reconnectIfMissed(context: Context, subscriptions: Collection<Subscription>) {
        val repository = Repository.getInstance(context)
        val baseUrls = subscriptions.filter { it.instant }.map { it.baseUrl }.distinct()
        if (baseUrls.isEmpty()) return
        Log.w(TAG, "Messages were missed by the live connection, reconnecting: $baseUrls")
        baseUrls.forEach { repository.incrementConnectionForceReconnectVersion(it) }
        SubscriberServiceManager.refresh(context)
    }
}
