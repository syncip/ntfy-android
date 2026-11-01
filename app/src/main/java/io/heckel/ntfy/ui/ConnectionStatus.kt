package io.heckel.ntfy.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.heckel.ntfy.R
import io.heckel.ntfy.db.ConnectionState
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.db.Subscription
import io.heckel.ntfy.msg.AccountSyncer
import io.heckel.ntfy.msg.ConnectionCheck
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.service.SubscriberServiceManager
import io.heckel.ntfy.util.formatDateWithSeconds
import io.heckel.ntfy.util.isIgnoringBatteryOptimizations
import io.heckel.ntfy.util.isNetworkAvailable
import io.heckel.ntfy.util.shortUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The status dot on the main screen: what it shows and the details behind it. */
object ConnectionStatus {
    private const val COLOR_GREEN = 0xFF2E9E5B.toInt()
    private const val COLOR_YELLOW = 0xFFF2B01E.toInt()
    private const val COLOR_RED = 0xFFD93025.toInt()
    private const val COLOR_NONE = 0xFF9E9E9E.toInt()
    private const val RED_AFTER_MILLIS = 2 * 60 * 1000L // Short drops are normal, the connection recovers by itself

    enum class Level(val color: Int, val title: Int) {
        NONE(COLOR_NONE, R.string.status_title_none),
        OK(COLOR_GREEN, R.string.status_title_ok),
        WARNING(COLOR_YELLOW, R.string.status_title_warning),
        CONNECTING(COLOR_YELLOW, R.string.status_title_connecting),
        ERROR(COLOR_RED, R.string.status_title_error)
    }

    fun level(context: Context, repository: Repository, subscriptions: List<Subscription>): Level {
        val baseUrls = instantBaseUrls(subscriptions)
        if (baseUrls.isEmpty()) return Level.NONE
        if (!isNetworkAvailable(context) || repository.getAuthFailedBaseUrls().any { it in baseUrls }) return Level.ERROR
        if (!SubscriberService.running) return Level.CONNECTING
        val details = repository.getConnectionDetails()
        val now = System.currentTimeMillis()
        if (baseUrls.any { details[it]?.let { d -> d.disconnectedSince > 0L && now - d.disconnectedSince > RED_AFTER_MILLIS } == true }) {
            return Level.ERROR
        }
        if (baseUrls.any { details[it]?.state != ConnectionState.CONNECTED }) return Level.CONNECTING
        return if (isIgnoringBatteryOptimizations(context)) Level.OK else Level.WARNING
    }

    fun showDetails(activity: AppCompatActivity, repository: Repository, subscriptions: List<Subscription>) {
        activity.lifecycleScope.launch(Dispatchers.IO) {
            val lastMessage = repository.getLastNotificationTimestamp()
            val message = detailsText(activity, repository, subscriptions, lastMessage)
            activity.runOnUiThread { showDialog(activity, repository, subscriptions, message) }
        }
    }

    private fun showDialog(activity: AppCompatActivity, repository: Repository, subscriptions: List<Subscription>, message: String) {
        val baseUrls = instantBaseUrls(subscriptions)
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(level(activity, repository, subscriptions).title)
            .setMessage(message)
            .setNegativeButton(R.string.status_action_close, null)
            .setPositiveButton(R.string.status_action_reconnect) { _, _ ->
                baseUrls.forEach { repository.incrementConnectionForceReconnectVersion(it) }
                SubscriberServiceManager.refresh(activity)
                activity.lifecycleScope.launch(Dispatchers.IO) { ConnectionCheck.check(activity.applicationContext) }
            }
        if (repository.getAuthFailedBaseUrls().any { it in baseUrls }) {
            builder.setNeutralButton(R.string.status_action_login) { _, _ ->
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
        } else if (!isIgnoringBatteryOptimizations(activity)) {
            builder.setNeutralButton(R.string.status_action_battery) { _, _ -> openBatterySettings(activity) }
        }
        builder.show()
    }

    private fun detailsText(context: Context, repository: Repository, subscriptions: List<Subscription>, lastMessage: Long): String {
        val lines = mutableListOf<String>()
        val details = repository.getConnectionDetails()
        val authFailed = repository.getAuthFailedBaseUrls()
        if (!isNetworkAvailable(context)) lines.add(context.getString(R.string.status_no_network))
        if (!SubscriberService.running) lines.add(context.getString(R.string.status_service_stopped))
        instantBaseUrls(subscriptions).forEach { baseUrl ->
            if (lines.isNotEmpty()) lines.add("")
            lines.add(shortUrl(baseUrl))
            val d = details[baseUrl]
            if (baseUrl in authFailed) lines.add(context.getString(R.string.status_auth_failed))
            if (d != null && d.connectedSince > 0L) lines.add(context.getString(R.string.status_connected_since, time(d.connectedSince)))
            if (d != null && d.disconnectedSince > 0L) lines.add(context.getString(R.string.status_disconnected_since, time(d.disconnectedSince)))
            if (d != null && d.nextRetryTime > System.currentTimeMillis()) lines.add(context.getString(R.string.status_next_retry, time(d.nextRetryTime)))
            d?.error?.message?.let { lines.add(context.getString(R.string.status_error, it)) }
        }
        lines.add("")
        val protocol = if (repository.getConnectionProtocol() == Repository.CONNECTION_PROTOCOL_WS) "WebSocket" else "JSON stream"
        lines.add(context.getString(R.string.status_protocol, protocol))
        val never = context.getString(R.string.status_never)
        lines.add(context.getString(R.string.status_last_message, if (lastMessage > 0L) formatDateWithSeconds(lastMessage) else never))
        lines.add(context.getString(R.string.status_last_check, ConnectionCheck.lastCheckMillis.let { if (it > 0L) time(it) else never }))
        lines.add(context.getString(R.string.status_last_sync, AccountSyncer.lastSyncMillis.let { if (it > 0L) time(it) else never }))
        if (!isIgnoringBatteryOptimizations(context)) {
            lines.add("")
            lines.add(context.getString(R.string.status_battery_warning))
        }
        return lines.joinToString("\n")
    }

    private fun openBatterySettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri()))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun instantBaseUrls(subscriptions: List<Subscription>) =
        subscriptions.filter { it.instant }.map { it.baseUrl }.distinct()

    private fun time(millis: Long) = formatDateWithSeconds(millis / 1000)
}
