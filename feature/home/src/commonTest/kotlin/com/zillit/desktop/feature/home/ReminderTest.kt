package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.DueReminder
import com.zillit.desktop.feature.home.calendar.REMINDER_GRACE_MILLIS
import com.zillit.desktop.feature.home.calendar.body
import com.zillit.desktop.feature.home.calendar.dueReminders
import com.zillit.desktop.feature.home.calendar.headline
import com.zillit.desktop.feature.home.calendar.parseFiredKeys
import com.zillit.desktop.feature.home.calendar.reminderDueAt
import com.zillit.desktop.feature.home.calendar.reminderKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which reminders fire, and when.
 *
 * The interesting cases are all about *not* firing: an alert for something
 * cancelled, an alert you already got, or an afternoon's worth of alerts
 * arriving at once because the laptop was shut.
 */
class ReminderTest {

    private val now = 1_800_000_000_000L // a fixed "now"; nothing here reads the clock
    private val minute = 60_000L

    private fun event(
        id: String = "e1",
        startsIn: Long = 15 * minute,
        reminderMinutes: Int = 15,
        status: String? = null,
        location: String? = null,
    ) = CalendarEvent(
        id = id,
        title = "Unit call",
        startMillis = now + startsIn,
        endMillis = now + startsIn + 60 * minute,
        reminderMinutes = reminderMinutes,
        status = status,
        location = location,
    )

    // -- what has a reminder at all ---------------------------------------

    @Test
    fun `a reminder is due its minutes before the start`() {
        val call = event(startsIn = 60 * minute, reminderMinutes = 15)

        assertEquals(call.startMillis - 15 * minute, call.reminderDueAt())
    }

    @Test
    fun `no reminder chosen means nothing to fire`() {
        // 0 is "None" everywhere in this client, not "at the time of the event".
        assertNull(event(reminderMinutes = 0).reminderDueAt())
    }

    @Test
    fun `a cancelled event does not remind`() {
        // Alerting someone to a thing that is not happening is worse than silence.
        assertNull(event(status = "cancelled").reminderDueAt())
    }

    @Test
    fun `a declined event does not remind`() {
        assertNull(event(status = "rejected").reminderDueAt())
    }

    @Test
    fun `a pending invitation still reminds`() {
        // Exactly when a nudge helps: the invitation is still waiting on them.
        assertTrue(event(status = "pending").reminderDueAt() != null)
    }

    // -- the firing window -------------------------------------------------

    @Test
    fun `nothing fires before it is due`() {
        val call = event(startsIn = 60 * minute, reminderMinutes = 15)

        assertTrue(dueReminders(listOf(call), now, fired = emptySet()).isEmpty())
    }

    @Test
    fun `a reminder fires at its moment`() {
        val call = event(startsIn = 15 * minute, reminderMinutes = 15)

        val due = dueReminders(listOf(call), now, fired = emptySet())

        assertEquals(1, due.size)
        assertEquals("e1", due.first().eventId)
    }

    @Test
    fun `a reminder still fires slightly late`() {
        // A tick landing a minute late, or a short suspend, must not lose it.
        val call = event(startsIn = 14 * minute, reminderMinutes = 15)

        assertEquals(1, dueReminders(listOf(call), now, fired = emptySet()).size)
    }

    @Test
    fun `a reminder missed during a long sleep is dropped`() {
        // The failure this exists to prevent: a laptop shut over lunch waking
        // up and delivering the whole afternoon at once.
        val hoursLate = event(startsIn = -4 * 60 * minute, reminderMinutes = 15)

        assertTrue(dueReminders(listOf(hoursLate), now, fired = emptySet()).isEmpty())
    }

    @Test
    fun `the grace window ends where it says it does`() {
        val insideEdge = event(id = "in", startsIn = 15 * minute - REMINDER_GRACE_MILLIS)
        val pastEdge = event(id = "out", startsIn = 15 * minute - REMINDER_GRACE_MILLIS - 1)

        val due = dueReminders(listOf(insideEdge, pastEdge), now, fired = emptySet())

        assertEquals(listOf("in"), due.map { it.eventId })
    }

    @Test
    fun `a reminder already delivered does not repeat`() {
        val call = event()
        val key = reminderKey(call.id, call.startMillis)

        assertTrue(dueReminders(listOf(call), now, fired = setOf(key)).isEmpty())
    }

    @Test
    fun `the soonest event is listed first`() {
        val later = event(id = "later", startsIn = 15 * minute, reminderMinutes = 15)
        val sooner = event(id = "sooner", startsIn = 2 * minute, reminderMinutes = 2)

        val due = dueReminders(listOf(later, sooner), now, fired = emptySet())

        assertEquals(listOf("sooner", "later"), due.map { it.eventId })
    }

    // -- recurring series --------------------------------------------------

    @Test
    fun `each occurrence of a repeat is its own reminder`() {
        // Keyed on the id alone, Monday's reminder would silence Tuesday's —
        // a repeating unit call would remind once and never again.
        val monday = reminderKey("series1", now)
        val tuesday = reminderKey("series1", now + 24 * 60 * minute)

        assertNotEquals(monday, tuesday)
    }

    // -- what the notification says ---------------------------------------

    private fun reminder(startsIn: Long, location: String? = null) =
        DueReminder("e1", "Unit call", now + startsIn, minutesBefore = 15, location = location)

    @Test
    fun `the headline counts from now, not from the setting`() {
        // Fired two minutes late, a 15-minute reminder must not still claim 15.
        assertEquals("Starts in 13 minutes", reminder(13 * minute).headline(now))
    }

    @Test
    fun `a part minute rounds up`() {
        // The delay between coming due and being delivered is not the user's
        // problem to reason about: 14m40s is "15 minutes", not "14".
        assertEquals("Starts in 15 minutes", reminder(14 * minute + 40_000).headline(now))
    }

    @Test
    fun `an event already starting says so`() {
        assertEquals("Starting now", reminder(0).headline(now))
        assertEquals("Starting now", reminder(-30_000).headline(now))
    }

    @Test
    fun `longer waits read in hours and days`() {
        assertEquals("Starts in 1 minute", reminder(minute).headline(now))
        assertEquals("Starts in 2 hours", reminder(120 * minute).headline(now))
        assertEquals("Starts in 1 day", reminder(24 * 60 * minute).headline(now))
    }

    @Test
    fun `the body names the event, and the location when there is one`() {
        assertEquals("Unit call", reminder(minute).body)
        assertEquals("Unit call · Stage 4", reminder(minute, location = "Stage 4").body)
    }

    // -- remembering what was delivered -----------------------------------

    @Test
    fun `recent keys survive a reload and stale ones do not`() {
        val recent = reminderKey("e1", now - 5 * minute)
        val ancient = reminderKey("e2", now - 5 * 60 * minute)

        val kept = parseFiredKeys("$recent;$ancient", now)

        assertEquals(setOf(recent), kept)
    }

    @Test
    fun `an unreadable key is discarded rather than kept forever`() {
        assertEquals(emptySet(), parseFiredKeys("nonsense;;", now))
    }
}
