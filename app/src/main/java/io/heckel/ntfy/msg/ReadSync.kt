package io.heckel.ntfy.msg

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.HttpUtil
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.topicUrl
import kotlinx.coroutines.delay
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Syncs the read state from this app to the web app and other devices, using ntfy's built-in
 * "clear" feature (PUT /<topic>/<sequence_id>/clear). The server turns this into a message_clear
 * event, which the web app and other Android devices use to mark the message as read and dismiss
 * its notification. No server changes needed.
 *
 * The other direction (web app -> this app) arrives as message_clear events, which the app already
 * handles. Clears are only sent on user actions, never when a message_clear is received, so there
 * are no loops. Sending happens in a WorkManager job that waits for network.
 */
object ReadSync {
    private const val TAG = "NtfyReadSync"
    private const val MAX_PER_ACTION = 50 // Newest only; avoids hitting the server's request rate limit with old history
    private const val CHUNK_SIZE = 25 // WorkManager input data is limited to 10 KB

    /** Called when [notifications] (all of one subscription) were read in the app. Only unread ones are sent. */
    fun onRead(context: Context, baseUrl: String, topic: String, notifications: List<Notification>) {
        val sequenceIds = notifications
            .filter { it.notificationId != 0 && !it.deleted }
            .sortedByDescending { it.timestamp }
            .map { it.sequenceId.ifEmpty { it.id } }
            .distinct()
            .take(MAX_PER_ACTION)
        enqueue(context, baseUrl, topic, sequenceIds)
    }

    fun enqueue(context: Context, baseUrl: String, topic: String, sequenceIds: List<String>) {
        if (sequenceIds.isEmpty() || !Repository.getInstance(context).getReadSyncEnabled()) return
        Log.d(TAG, "Queueing clear of ${sequenceIds.size} message(s) in ${topicUrl(baseUrl, topic)}")
        val workManager = WorkManager.getInstance(context)
        sequenceIds.chunked(CHUNK_SIZE).forEach { chunk ->
            val request = OneTimeWorkRequestBuilder<ClearWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(
                    ClearWorker.INPUT_BASE_URL to baseUrl,
                    ClearWorker.INPUT_TOPIC to topic,
                    ClearWorker.INPUT_SEQUENCE_IDS to chunk.toTypedArray()
                ))
                .build()
            workManager.enqueue(request)
        }
    }

    class ClearWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
        init {
            Log.init(context) // Init in all entrypoints
        }

        override suspend fun doWork(): Result {
            val baseUrl = inputData.getString(INPUT_BASE_URL) ?: return Result.failure()
            val topic = inputData.getString(INPUT_TOPIC) ?: return Result.failure()
            val sequenceIds = inputData.getStringArray(INPUT_SEQUENCE_IDS) ?: return Result.failure()
            val repository = Repository.getInstance(applicationContext)
            val user = repository.getUser(baseUrl)
            val customHeaders = repository.getCustomHeaders(baseUrl)
            val client = HttpUtil.defaultClient(applicationContext, baseUrl)
            sequenceIds.forEachIndexed { index, sequenceId ->
                val url = "${topicUrl(baseUrl, topic)}/${URLEncoder.encode(sequenceId, "UTF-8")}/clear"
                val request = HttpUtil.requestBuilder(url, user, customHeaders).put("".toRequestBody()).build()
                try {
                    client.newCall(request).execute().use { response ->
                        when {
                            response.isSuccessful -> Unit
                            response.code == 429 -> {
                                Log.w(TAG, "Rate limited while clearing, retrying later")
                                return retryOrGiveUp()
                            }
                            // 401/403: no write access to this topic, 404: old server; nothing we can do
                            else -> Log.w(TAG, "Unable to clear $sequenceId in $topic: HTTP ${response.code}")
                        }
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Network error while clearing, retrying later: ${e.message}")
                    return retryOrGiveUp()
                }
                if (index < sequenceIds.lastIndex) delay(SEND_INTERVAL_MILLIS) // Be gentle with the rate limiter
            }
            return Result.success()
        }

        // Clears are idempotent, so a retry may safely resend the ones that already went through
        private fun retryOrGiveUp() = if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()

        companion object {
            const val INPUT_BASE_URL = "baseUrl"
            const val INPUT_TOPIC = "topic"
            const val INPUT_SEQUENCE_IDS = "sequenceIds"
            private const val SEND_INTERVAL_MILLIS = 200L
            private const val MAX_ATTEMPTS = 5
        }
    }
}
