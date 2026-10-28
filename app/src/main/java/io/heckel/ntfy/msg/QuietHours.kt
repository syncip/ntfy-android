package io.heckel.ntfy.msg

import io.heckel.ntfy.db.Repository
import java.time.LocalTime

/**
 * Quiet hours: during a daily time window, notifications below a priority threshold are still
 * shown, but without sound or vibration. Evaluated only when a notification arrives, so it costs
 * no timers, alarms or wakeups.
 */
object QuietHours {
    fun silences(repository: Repository, priority: Int, now: LocalTime = LocalTime.now()): Boolean {
        if (!repository.getQuietHoursEnabled()) return false
        if (priority >= repository.getQuietHoursMinPriority()) return false
        return isInWindow(repository.getQuietHoursStart(), repository.getQuietHoursEnd(), now)
    }

    /** Start and end are minutes of the day; a window like 22:00-07:00 wraps around midnight. */
    fun isInWindow(startMinutes: Int, endMinutes: Int, now: LocalTime): Boolean {
        val minutes = now.hour * 60 + now.minute
        return if (startMinutes <= endMinutes) {
            minutes in startMinutes until endMinutes
        } else {
            minutes >= startMinutes || minutes < endMinutes
        }
    }
}
