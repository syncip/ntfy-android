package io.heckel.ntfy.msg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class QuietHoursTest {
    private val night = Pair(22 * 60, 7 * 60) // 22:00 - 07:00, wraps around midnight
    private val lunch = Pair(12 * 60, 13 * 60)

    @Test
    fun windowAcrossMidnight() {
        assertTrue(QuietHours.isInWindow(night.first, night.second, LocalTime.of(22, 0)))
        assertTrue(QuietHours.isInWindow(night.first, night.second, LocalTime.of(23, 59)))
        assertTrue(QuietHours.isInWindow(night.first, night.second, LocalTime.of(0, 0)))
        assertTrue(QuietHours.isInWindow(night.first, night.second, LocalTime.of(6, 59)))
        assertFalse(QuietHours.isInWindow(night.first, night.second, LocalTime.of(7, 0)))
        assertFalse(QuietHours.isInWindow(night.first, night.second, LocalTime.of(12, 0)))
        assertFalse(QuietHours.isInWindow(night.first, night.second, LocalTime.of(21, 59)))
    }

    @Test
    fun windowWithinDay() {
        assertTrue(QuietHours.isInWindow(lunch.first, lunch.second, LocalTime.of(12, 30)))
        assertFalse(QuietHours.isInWindow(lunch.first, lunch.second, LocalTime.of(13, 0)))
        assertFalse(QuietHours.isInWindow(lunch.first, lunch.second, LocalTime.of(11, 59)))
    }

    @Test
    fun emptyWindow() {
        assertFalse(QuietHours.isInWindow(8 * 60, 8 * 60, LocalTime.of(8, 0)))
    }
}
