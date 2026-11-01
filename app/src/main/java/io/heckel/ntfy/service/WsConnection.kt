package io.heckel.ntfy.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.CustomHeader
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.msg.ApiService
import io.heckel.ntfy.msg.NotificationParser
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.topicShortUrl
import io.heckel.ntfy.util.topicUrlWs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Connect to ntfy server via WebSockets. This connection represents a single connection to a server, with
 * one or more topics. When the topics are changed, the connection is recreated by the service.
 *
 * The connection re-connects on failure, indefinitely. It reports limited status via the stateChangeListener,
 * and forwards incoming messages via the notificationListener.
 *
 * The original class is taken from the fantastic Gotify project (MIT). Thank you:
 * https://github.com/gotify/android/blob/master/app/src/main/java/com/github/gotify/service/WebSocketConnection.java
 */
class WsConnection(
    private val connectionId: ConnectionId,
    private val repository: Repository,
    private val httpClient: OkHttpClient,
    private val user: User?,
    private val customHeaders: List<CustomHeader>,
    private val connectionDetailsListener: (String, ConnectionState, Throwable?, Long) -> Unit,
    private val notificationListener: (Subscription, Notification) -> Unit,
    private val alarmManager: AlarmManager,
    private val context: Context
) : Connection {
    private val parser = NotificationParser()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var errorCount = 0
    private var webSocket: WebSocket? = null
    private var state: State? = null
    private var closed = false

    private val globalId = GLOBAL_ID.incrementAndGet()
    private val listenerId = AtomicLong(0)

    private val baseUrl = connectionId.baseUrl
    private val topicsToSubscriptionIds = connectionId.topicsToSubscriptionIds
    private val topicsStr = topicsToSubscriptionIds.keys.joinToString(separator = ",")
    private val shortUrl = topicShortUrl(baseUrl, topicsStr)

    init {
        Log.d(TAG, "$shortUrl (gid=$globalId): New connection with global ID $globalId")
    }

    @Synchronized
    override fun start() {
        if (closed || state == State.Connecting || state == State.Connected) {
            Log.d(TAG,"$shortUrl (gid=$globalId): Not (re-)starting, because connection is marked closed/connecting/connected")
            return
        }
        if (webSocket != null) {
            webSocket!!.close(WS_CLOSE_NORMAL, "")
        }
        state = State.Connecting
        val nextListenerId = listenerId.incrementAndGet()
        val since = repository.getLastNotificationId(topicsToSubscriptionIds.values) ?: ApiService.SINCE_NONE
        val urlWithSince = topicUrlWs(baseUrl, topicsStr, since)
        val request = HttpUtil.requestBuilder(urlWithSince, user, customHeaders).build()
        Log.d(TAG, "$shortUrl (gid=$globalId): Opening $urlWithSince with listener ID $nextListenerId ...")
        webSocket = httpClient.newWebSocket(request, Listener(nextListenerId))
    }

    @Synchronized
    override fun close() {
        closed = true
        SCHEDULED.remove(globalId)
        scope.cancel()
        if (webSocket == null) {
            Log.d(TAG,"$shortUrl (gid=$globalId): Not closing existing connection, because there is no active web socket")
            return
        }
        Log.d(TAG, "$shortUrl (gid=$globalId): Closing connection")
        state = State.Disconnected
        webSocket!!.close(WS_CLOSE_NORMAL, "")
        webSocket = null
    }

    @Synchronized
    fun scheduleReconnect(seconds: Int) {
        if (closed || state == State.Connecting || state == State.Connected) {
            Log.d(TAG,"$shortUrl (gid=$globalId): Not rescheduling connection, because connection is marked closed/connecting/connected")
            return
        }
        state = State.Scheduled
        Log.d(TAG,"$shortUrl (gid=$globalId): Scheduling a restart in $seconds seconds (via alarm manager)")
        val reconnectTime = Calendar.getInstance()
        reconnectTime.add(Calendar.SECOND, seconds)
        // Must fire in Doze too: a plain exact alarm would wait for the next maintenance window, and
        // until then there is no connection at all. Only the PendingIntent variant can do that.
        SCHEDULED[globalId] = this
        val intent = Intent(context, ReconnectReceiver::class.java).putExtra(EXTRA_GLOBAL_ID, globalId)
        val pendingIntent = PendingIntent.getBroadcast(context, globalId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || alarmManager.canScheduleExactAlarms()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reconnectTime.timeInMillis, pendingIntent)
        } else {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reconnectTime.timeInMillis, pendingIntent)
        }
    }

    private fun reconnectAfterClose() {
        if (closed || state == State.Scheduled) return
        state = State.Disconnected
        errorCount++
        val retrySeconds = RETRY_SECONDS.getOrNull(errorCount-1) ?: RETRY_SECONDS.last()
        connectionDetailsListener(baseUrl, ConnectionState.CONNECTING, null, System.currentTimeMillis() + (retrySeconds * 1000L))
        scheduleReconnect(retrySeconds)
    }

    /** Fired by the reconnect alarm, see [scheduleReconnect]. */
    class ReconnectReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val connection = SCHEDULED.remove(intent.getLongExtra(EXTRA_GLOBAL_ID, 0L)) ?: return
            val pendingResult = goAsync()
            // start() accesses the database, so we dispatch to a background thread
            connection.scope.launch {
                try {
                    connection.start()
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private inner class Listener(private val id: Long) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronize("onOpen") {
                Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Opened connection")
                state = State.Connected
                if (errorCount > 0) {
                    errorCount = 0
                }
                connectionDetailsListener(baseUrl, ConnectionState.CONNECTED, null, 0L)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronize("onMessage") {
                Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Received message: $text")
                val notificationWithTopic = parser.parseWithTopic(text, subscriptionId = 0, baseUrl = baseUrl)
                if (notificationWithTopic == null) {
                    Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Irrelevant or unknown message. Discarding.")
                    return@synchronize
                }
                val topic = notificationWithTopic.topic
                val notification = notificationWithTopic.notification
                val subscriptionId = topicsToSubscriptionIds[topic] ?: return@synchronize
                val subscription = repository.getSubscription(subscriptionId) ?: return@synchronize
                val notificationWithSubscriptionId = notification.copy(subscriptionId = subscription.id)
                notificationListener(subscription, notificationWithSubscriptionId)
            }
        }

        // The peer (server or proxy) closed the connection. Nothing arrives on this socket anymore, but
        // without acting here we would still consider ourselves connected until a ping fails.
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronize("onClosing") {
                Log.w(TAG, "$shortUrl (gid=$globalId, lid=$id): Closed by peer (code $code): $reason")
                webSocket.close(WS_CLOSE_NORMAL, "")
                reconnectAfterClose()
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronize("onClosed") {
                Log.w(TAG, "$shortUrl (gid=$globalId, lid=$id): Closed connection")
                reconnectAfterClose()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronize("onFailure") {
                if (response == null) {
                    Log.e(TAG, "$shortUrl (gid=$globalId, lid=$id): Connection failed (response is null): ${t.message}", t)
                } else {
                    Log.e(TAG, "$shortUrl (gid=$globalId, lid=$id): Connection failed (response code ${response.code}, message: ${response.message}): ${t.message}", t)
                }
                if (closed) {
                    Log.d(TAG, "$shortUrl (gid=$globalId, lid=$id): Connection marked as closed. Not retrying.")
                    return@synchronize
                }
                state = State.Disconnected
                errorCount++
                val retrySeconds = RETRY_SECONDS.getOrNull(errorCount-1) ?: RETRY_SECONDS.last()
                val nextRetryTime = System.currentTimeMillis() + (retrySeconds * 1000L)
                
                // Special cases:
                // - Ignore broken connections in the UI, we don't want to show warning icons
                // - Handle authentication errors
                // - Handle servers that do not support WebSockets
                val error = when {
                    isConnectionBrokenException(t) -> null
                    isProtocolException(t) && response != null -> WebSocketNotSupportedException(response.code, response.message, t)
                    isResponseCode(response, 401, 403) && response != null -> NotAuthorizedException(response.code, response.message, t)
                    else -> t
                }
                connectionDetailsListener(baseUrl, ConnectionState.CONNECTING, error, nextRetryTime)
                scheduleReconnect(retrySeconds)
            }
        }

        private fun synchronize(tag: String, fn: () -> Unit) {
            synchronized(this) {
                if (listenerId.get() == id) {
                    fn()
                } else {
                    Log.w(TAG, "$shortUrl (gid=$globalId, lid=$id): Skipping synchronized block '$tag', because listener ID does not match ${listenerId.get()}")
                }
            }
        }
    }

    internal enum class State {
        Scheduled, Connecting, Connected, Disconnected
    }

    companion object {
        private const val TAG = "NtfyWsConnection"
        private const val EXTRA_GLOBAL_ID = "globalId"
        private const val WS_CLOSE_NORMAL = 1000
        private val RETRY_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 120)
        private val GLOBAL_ID = AtomicLong(0)
        private val SCHEDULED = ConcurrentHashMap<Long, WsConnection>() // Connections waiting for their reconnect alarm
    }
}
