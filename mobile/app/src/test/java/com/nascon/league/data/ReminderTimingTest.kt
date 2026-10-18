package com.nascon.league.data

import com.nascon.league.notifications.reminderAt
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReminderTimingTest {
    private val now = Instant.parse("2026-10-18T10:00:00Z").toEpochMilli()

    @Test fun scheduledExactlyTenMinutesBeforeKickoff() {
        assertEquals(Instant.parse("2026-10-18T10:20:00Z").toEpochMilli(), reminderAt("2026-10-18T10:30:00Z", now))
    }

    @Test fun lateSubscriptionNotifiesSoonRatherThanSchedulingInThePast() {
        assertEquals(now + 1000, reminderAt("2026-10-18T10:05:00Z", now))
    }

    @Test fun rejectsPastOrInvalidDates() {
        assertNull(reminderAt("2026-10-18T09:59:00Z", now))
        assertNull(reminderAt("invalid", now))
    }

    @Test fun timezoneOffsetProducesSameInstant() {
        assertEquals(reminderAt("2026-10-18T10:30:00Z", now), reminderAt("2026-10-18T15:30:00+05:00", now))
    }
}
