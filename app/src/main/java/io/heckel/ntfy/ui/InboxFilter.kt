package io.heckel.ntfy.ui

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import io.heckel.ntfy.R
import io.heckel.ntfy.db.Notification
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.decodeMessage
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * What the "all messages" page shows. Topics are stored as "not selected", so that new topics
 * appear by themselves. It only affects this page, never which notifications are shown.
 */
data class InboxFilter(
    val excludedTopics: Set<Long> = emptySet(),
    val status: String = STATUS_ALL,
    val priorities: Set<Int> = emptySet(), // Empty = all
    val period: String = PERIOD_ALL,
    val from: Long = 0L, // Unix seconds, only for PERIOD_CUSTOM
    val to: Long = 0L,
    val timeFrom: Int = NO_TIME, // Minutes of the day; a window like 22:00-06:00 spans midnight
    val timeTo: Int = NO_TIME,
    val text: String = "",
    val withAttachment: Boolean = false,
    val withTags: Boolean = false,
    val withActions: Boolean = false
) {
    val isActive get() = this != InboxFilter()

    fun matches(notification: Notification, now: Long = System.currentTimeMillis() / 1000): Boolean {
        val unread = notification.notificationId != 0
        return notification.subscriptionId !in excludedTopics
                && (status == STATUS_ALL || (status == STATUS_UNREAD) == unread)
                && (priorities.isEmpty() || notification.priority in priorities)
                && inPeriod(notification.timestamp, now)
                && inTimeWindow(notification.timestamp)
                && (text.isBlank() || containsText(notification))
                && (!withAttachment || notification.attachment != null)
                && (!withTags || notification.tags.isNotEmpty())
                && (!withActions || !notification.actions.isNullOrEmpty())
    }

    private fun inPeriod(timestamp: Long, now: Long): Boolean {
        return when (period) {
            PERIOD_TODAY -> timestamp >= startOfToday()
            PERIOD_24H -> timestamp >= now - 24 * ONE_HOUR
            PERIOD_7D -> timestamp >= now - 7 * 24 * ONE_HOUR
            PERIOD_30D -> timestamp >= now - 30 * 24 * ONE_HOUR
            PERIOD_CUSTOM -> timestamp in from..to
            else -> true
        }
    }

    private fun inTimeWindow(timestamp: Long): Boolean {
        if (timeFrom == NO_TIME && timeTo == NO_TIME) return true
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp * 1000 }
        val minutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return when {
            timeFrom == NO_TIME -> minutes <= timeTo
            timeTo == NO_TIME -> minutes >= timeFrom
            timeFrom <= timeTo -> minutes in timeFrom..timeTo
            else -> minutes >= timeFrom || minutes <= timeTo
        }
    }

    private fun containsText(notification: Notification): Boolean {
        return listOf(notification.title, decodeMessage(notification), notification.tags).any { it.contains(text.trim(), ignoreCase = true) }
    }

    /** One line for the bar above the list. */
    fun summary(context: Context): String {
        val parts = mutableListOf<String>()
        when (status) {
            STATUS_UNREAD -> parts.add(context.getString(R.string.inbox_status_unread))
            STATUS_READ -> parts.add(context.getString(R.string.inbox_status_read))
        }
        when (period) {
            PERIOD_TODAY -> parts.add(context.getString(R.string.inbox_period_today))
            PERIOD_24H -> parts.add(context.getString(R.string.inbox_period_24h))
            PERIOD_7D -> parts.add(context.getString(R.string.inbox_period_7d))
            PERIOD_30D -> parts.add(context.getString(R.string.inbox_period_30d))
            PERIOD_CUSTOM -> parts.add(customPeriodLabel())
        }
        if (timeFrom != NO_TIME || timeTo != NO_TIME) {
            parts.add(listOf(timeFrom, timeTo).joinToString("–") { if (it == NO_TIME) "…" else formatTime(it) })
        }
        if (excludedTopics.isNotEmpty()) parts.add(context.getString(R.string.inbox_filter_section_topics))
        if (priorities.isNotEmpty()) parts.add(context.getString(R.string.inbox_summary_priority, priorities.sorted().joinToString(",")))
        if (withAttachment) parts.add(context.getString(R.string.inbox_more_attachment))
        if (withTags) parts.add(context.getString(R.string.inbox_more_tags))
        if (withActions) parts.add(context.getString(R.string.inbox_more_actions))
        if (text.isNotBlank()) parts.add("\"${text.trim()}\"")
        return parts.joinToString(" · ")
    }

    fun customPeriodLabel(): String {
        val format = DateFormat.getDateInstance(DateFormat.SHORT)
        return "${format.format(Date(from * 1000))}–${format.format(Date(to * 1000))}"
    }

    companion object {
        const val STATUS_ALL = "all"
        const val STATUS_UNREAD = "unread"
        const val STATUS_READ = "read"
        const val PERIOD_ALL = "all"
        const val PERIOD_TODAY = "today"
        const val PERIOD_24H = "24h"
        const val PERIOD_7D = "7d"
        const val PERIOD_30D = "30d"
        const val PERIOD_CUSTOM = "custom"
        const val NO_TIME = -1
        private const val ONE_HOUR = 60 * 60L

        private val gson = Gson()

        fun load(repository: Repository): InboxFilter {
            val json = repository.getInboxFilter() ?: return InboxFilter()
            return try {
                gson.fromJson(json, InboxFilter::class.java) ?: InboxFilter()
            } catch (_: Exception) {
                InboxFilter()
            }
        }

        fun save(repository: Repository, filter: InboxFilter) {
            repository.setInboxFilter(gson.toJson(filter))
        }

        fun loadViews(repository: Repository): List<InboxView> {
            val json = repository.getInboxViews() ?: return emptyList()
            return try {
                gson.fromJson(json, object : TypeToken<List<InboxView>>() {}.type) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        fun saveViews(repository: Repository, views: List<InboxView>) {
            repository.setInboxViews(gson.toJson(views))
        }

        fun formatTime(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

        private fun startOfToday(): Long {
            return Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis / 1000
        }
    }
}

/** A filter saved under its own name, shown as a quick view. */
data class InboxView(val name: String, val filter: InboxFilter)
