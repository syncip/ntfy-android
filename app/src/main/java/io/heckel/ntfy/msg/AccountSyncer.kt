package io.heckel.ntfy.msg

import android.content.Context
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.db.User
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.randomSubscriptionId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Date

/**
 * Keeps the local subscriptions in sync with the subscriptions stored in the ntfy accounts
 * of all users configured in the app (GET /v1/account, the same list the web app uses).
 *
 * - Topics in an account but not in the app are added (instant delivery, history is fetched silently)
 * - Topics that were added by a previous sync and are no longer in the account are removed
 * - Topics added/removed in the app are mirrored to the account of the user for that server
 *
 * Which topics came from which account is tracked in the shared preferences, so manually added
 * subscriptions are never removed by a sync. If an account cannot be fetched (e.g. no network),
 * nothing is removed for that account.
 */
class AccountSyncer(private val context: Context) {
    private val repository = Repository.getInstance(context)
    private val api = ApiService(context)
    private val poller = Poller(api, repository)
    private val gson = Gson()

    data class Result(val added: Int, val removed: Int)

    /** Like [sync], but skipped if the last sync was less than [MIN_SYNC_INTERVAL_MILLIS] ago. */
    suspend fun syncIfDue(): Result? {
        if (System.currentTimeMillis() - lastSyncMillis < MIN_SYNC_INTERVAL_MILLIS) return null
        return sync()
    }

    suspend fun sync(): Result = mutex.withLock {
        lastSyncMillis = System.currentTimeMillis()
        if (!repository.getAccountSyncEnabled()) {
            return Result(0, 0)
        }
        val previous = repository.getAccountSyncedTopics().mapNotNull { SyncedTopic.parse(it) }.toSet()
        val current = mutableSetOf<SyncedTopic>()
        val newSubscriptions = mutableListOf<Subscription>()
        var removed = 0

        repository.getUsers().forEach { storedUser ->
            val user = migrateToToken(storedUser)
            val account = try {
                fetchAccount(user)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot fetch account for ${user.username} on ${user.baseUrl}, keeping previous state: ${e.message}")
                null
            }
            if (account == null) {
                current.addAll(previous.filter { it.accountBaseUrl == user.baseUrl })
                return@forEach
            }
            removeTokenExpiry(user, account)
            account.subscriptions.orEmpty().forEach { remote ->
                val baseUrl = remote.baseUrl?.trimEnd('/')?.ifEmpty { null } ?: user.baseUrl
                val topic = remote.topic ?: return@forEach
                current.add(SyncedTopic(user.baseUrl, baseUrl, topic))
                val existing = repository.getSubscription(baseUrl, topic)
                if (existing == null) {
                    Log.d(TAG, "Adding $baseUrl/$topic from account ${user.username}")
                    val subscription = newSubscription(baseUrl, topic, remote.displayName)
                    repository.addSubscription(subscription)
                    newSubscriptions.add(subscription)
                } else if (existing.displayName.isNullOrEmpty() && !remote.displayName.isNullOrEmpty()) {
                    repository.updateSubscription(existing.copy(displayName = remote.displayName))
                }
            }
        }

        // Only remove topics this syncer added itself and that are gone from a successfully fetched account
        (previous - current).forEach { synced ->
            if (current.any { it.baseUrl == synced.baseUrl && it.topic == synced.topic }) return@forEach
            val subscription = repository.getSubscription(synced.baseUrl, synced.topic) ?: return@forEach
            if (subscription.upAppId != null) return@forEach // Never touch UnifiedPush subscriptions
            Log.d(TAG, "Removing ${synced.baseUrl}/${synced.topic}, no longer in account")
            removeLocal(subscription)
            removed++
        }
        repository.setAccountSyncedTopics(current.map { it.serialize() }.toSet())

        if (newSubscriptions.isNotEmpty() || removed > 0) {
            SubscriberServiceManager.refresh(context)
        }
        // Fetch cached messages without showing popups (same as subscribing manually)
        newSubscriptions.forEach { subscription ->
            try {
                poller.poll(subscription).forEach { notification ->
                    if (notification.icon != null) {
                        DownloadManager.enqueue(context, notification.id, userAction = false, DownloadType.ICON)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to fetch notifications for ${subscription.topic}: ${e.message}")
            }
        }
        Log.d(TAG, "Account sync finished: ${newSubscriptions.size} added, $removed removed")
        return Result(newSubscriptions.size, removed)
    }

    /** Called after the user subscribed to a topic in the app; stores it in the account for that server. */
    suspend fun onLocalSubscribe(baseUrl: String, topic: String) = mutex.withLock {
        if (!repository.getAccountSyncEnabled()) return
        val user = repository.getUser(baseUrl) ?: return
        try {
            val body = gson.toJson(RemoteSubscription(baseUrl, topic, null))
            val request = HttpUtil.requestBuilder(accountSubscriptionUrl(baseUrl), user, repository.getCustomHeaders(baseUrl))
                .post(body.toRequestBody())
                .build()
            HttpUtil.defaultClient(context, baseUrl).newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Unexpected response ${response.code}")
            }
            val synced = repository.getAccountSyncedTopics() + SyncedTopic(baseUrl, baseUrl, topic).serialize()
            repository.setAccountSyncedTopics(synced)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to add $baseUrl/$topic to account: ${e.message}")
        }
    }

    /** Called before a subscription is deleted in the app; removes it from the account it came from. */
    suspend fun onLocalUnsubscribe(baseUrl: String, topic: String) = mutex.withLock {
        val all = repository.getAccountSyncedTopics().mapNotNull { SyncedTopic.parse(it) }
        val matching = all.filter { it.baseUrl == baseUrl && it.topic == topic }
        if (matching.isEmpty()) return
        matching.forEach { synced ->
            val user = repository.getUser(synced.accountBaseUrl) ?: return@forEach
            try {
                val request = HttpUtil.requestBuilder(accountSubscriptionUrl(synced.accountBaseUrl), user, repository.getCustomHeaders(synced.accountBaseUrl))
                    .addHeader("X-BaseURL", baseUrl)
                    .addHeader("X-Topic", topic)
                    .delete()
                    .build()
                HttpUtil.defaultClient(context, synced.accountBaseUrl).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("Unexpected response ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Unable to remove $baseUrl/$topic from account: ${e.message}")
            }
        }
        repository.setAccountSyncedTopics((all - matching.toSet()).map { it.serialize() }.toSet())
    }

    /** Users stored before token login still have a password; swap it for a token once. */
    private suspend fun migrateToToken(user: User): User {
        if (AccountTokens.isToken(user.password)) return user
        return try {
            val userWithToken = AccountTokens(context).exchangePassword(user)
            if (userWithToken.password != user.password) {
                repository.updateUser(userWithToken)
                SubscriberServiceManager.refresh(context)
            }
            userWithToken
        } catch (e: Exception) {
            Log.w(TAG, "Unable to replace password with token for ${user.username}: ${e.message}")
            user
        }
    }

    /** Tokens of older versions expire after 72 hours; make them permanent while they still work. */
    private suspend fun removeTokenExpiry(user: User, account: AccountResponse) {
        val token = account.tokens.orEmpty().firstOrNull { it.token == user.password } ?: return
        if ((token.expires ?: 0L) == 0L) return
        try {
            AccountTokens(context).removeExpiry(user)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to remove token expiry for ${user.username}: ${e.message}")
        }
    }

    private suspend fun fetchAccount(user: User): AccountResponse? {
        val request = HttpUtil.requestBuilder(accountUrl(user.baseUrl), user, repository.getCustomHeaders(user.baseUrl)).build()
        HttpUtil.defaultClient(context, user.baseUrl).newCall(request).execute().use { response ->
            if (response.code == 404) {
                Log.d(TAG, "Server ${user.baseUrl} has no account API, skipping")
                return AccountResponse(emptyList(), null) // Nothing to sync, but the request itself succeeded
            }
            if (response.code == 401) {
                AuthAlert.onRejected(context, user)
                throw ApiService.UnauthorizedException(user)
            }
            if (!response.isSuccessful) {
                throw Exception("Unexpected response ${response.code}")
            }
            AuthAlert.onAccepted(context, user)
            return gson.fromJson(response.body.string(), AccountResponse::class.java)
        }
    }

    private suspend fun removeLocal(subscription: Subscription) {
        repository.removeSubscription(subscription)
        if (subscription.icon != null) {
            try {
                context.contentResolver.delete(subscription.icon.toUri(), null, null)
            } catch (_: Exception) {
                // Don't care
            }
        }
    }

    private fun newSubscription(baseUrl: String, topic: String, displayName: String?) = Subscription(
        id = randomSubscriptionId(),
        baseUrl = baseUrl,
        topic = topic,
        instant = true, // Synced topics should arrive immediately, like on the web app
        dedicatedChannels = false,
        mutedUntil = 0,
        minPriority = Repository.MIN_PRIORITY_USE_GLOBAL,
        autoDelete = Repository.AUTO_DELETE_USE_GLOBAL,
        insistent = Repository.INSISTENT_MAX_PRIORITY_USE_GLOBAL,
        lastNotificationId = null,
        icon = null,
        upAppId = null,
        upConnectorToken = null,
        displayName = displayName?.ifEmpty { null },
        totalCount = 0,
        newCount = 0,
        lastActive = Date().time / 1000
    )

    private data class SyncedTopic(val accountBaseUrl: String, val baseUrl: String, val topic: String) {
        fun serialize() = listOf(accountBaseUrl, baseUrl, topic).joinToString(SEPARATOR)

        companion object {
            fun parse(s: String): SyncedTopic? {
                val parts = s.split(SEPARATOR)
                return if (parts.size == 3) SyncedTopic(parts[0], parts[1], parts[2]) else null
            }
        }
    }

    private data class AccountResponse(
        val subscriptions: List<RemoteSubscription>?,
        val tokens: List<RemoteToken>?
    )

    private data class RemoteToken(val token: String?, val expires: Long?)

    private data class RemoteSubscription(
        @SerializedName("base_url") val baseUrl: String?,
        val topic: String?,
        @SerializedName("display_name") val displayName: String?
    )

    companion object {
        private const val TAG = "NtfyAccountSyncer"
        private const val SEPARATOR = "\t"
        private val mutex = Mutex()
        private const val MIN_SYNC_INTERVAL_MILLIS = 5 * 60 * 1000L
        @Volatile var lastSyncMillis = 0L
            private set

        private fun accountUrl(baseUrl: String) = "$baseUrl/v1/account"
        private fun accountSubscriptionUrl(baseUrl: String) = "$baseUrl/v1/account/subscription"
    }
}
