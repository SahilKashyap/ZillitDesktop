package com.zillit.desktop.feature.home

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.datastore.PreferenceKey
import com.zillit.desktop.core.datastore.PreferenceScope
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.notifications.DesktopNotification
import com.zillit.desktop.core.notifications.Notifier
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarReminderScheduler
import com.zillit.desktop.feature.home.calendar.CalendarRepository
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.EventTimes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scheduler that turns due reminders into notifications.
 *
 * Stepped by hand rather than left to run: a test that waited on real ticks
 * would take minutes and still be flaky. [CalendarReminderScheduler.tick] is
 * internal for exactly this.
 */
class ReminderSchedulerTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun call(id: String = "e1", startsIn: Long = 15 * minute) = CalendarEvent(
        id = id,
        title = "Unit call",
        startMillis = now + startsIn,
        endMillis = now + startsIn + 60 * minute,
        reminderMinutes = 15,
    )

    private fun scheduler(
        repository: CalendarRepository,
        notifier: Notifier,
        preferences: PreferenceStore,
        clock: () -> Long = { now },
        refreshMillis: Long = 5 * 60 * 1000L,
    ) = CalendarReminderScheduler(
        repository = repository,
        notifier = notifier,
        preferences = preferences,
        nowMillis = clock,
        refreshMillis = refreshMillis,
    )

    @Test
    fun `a due reminder is delivered`() = runTest {
        val calendar = FakeCalendar(listOf(call()))
        val posted = RecordingNotifier()

        scheduler(calendar, posted, FakePreferences()).tick()

        assertEquals(1, posted.notes.size)
        assertEquals("Starts in 15 minutes", posted.notes.first().title)
        assertEquals("Unit call", posted.notes.first().body)
    }

    @Test
    fun `it is delivered once, however many ticks pass`() = runTest {
        // The tick is every 30 seconds and the grace window is five minutes, so
        // without this the same reminder would arrive ten times.
        val calendar = FakeCalendar(listOf(call()))
        val posted = RecordingNotifier()
        val scheduler = scheduler(calendar, posted, FakePreferences())

        repeat(4) { scheduler.tick() }

        assertEquals(1, posted.notes.size)
    }

    @Test
    fun `restarting the app does not deliver it again`() = runTest {
        // Two schedulers, one preference store: the second is the app after a
        // restart inside the same grace window.
        val preferences = FakePreferences()
        val posted = RecordingNotifier()

        scheduler(FakeCalendar(listOf(call())), posted, preferences).tick()
        scheduler(FakeCalendar(listOf(call())), posted, preferences).tick()

        assertEquals(1, posted.notes.size)
    }

    @Test
    fun `muted delivers nothing, and asks the server for nothing`() = runTest {
        val calendar = FakeCalendar(listOf(call()))
        val posted = RecordingNotifier()
        val preferences = FakePreferences().apply { set(ZillitPreferences.MuteNotifications, true) }

        scheduler(calendar, posted, preferences).tick()

        assertTrue(posted.notes.isEmpty())
        assertEquals(0, calendar.loads, "a muted client should also be quiet on the network")
    }

    @Test
    fun `a failed refresh keeps the reminders already in hand`() = runTest {
        // A network blip must not silently switch reminders off.
        val calendar = FakeCalendar(listOf(call(startsIn = 20 * minute)))
        val posted = RecordingNotifier()
        var clock = now
        val scheduler = scheduler(
            calendar,
            posted,
            FakePreferences(),
            clock = { clock },
            refreshMillis = minute,
        )

        scheduler.tick() // loads; nothing due yet
        assertTrue(posted.notes.isEmpty())

        calendar.fails = true
        clock = now + 5 * minute // the reminder is now due, but the server is down
        scheduler.tick()

        assertEquals(1, posted.notes.size)
    }

    @Test
    fun `the window is reloaded as it ages`() = runTest {
        val calendar = FakeCalendar(emptyList())
        var clock = now
        val scheduler = scheduler(
            calendar,
            RecordingNotifier(),
            FakePreferences(),
            clock = { clock },
            refreshMillis = minute,
        )

        scheduler.tick()
        scheduler.tick() // still fresh — no second load
        assertEquals(1, calendar.loads)

        clock = now + 2 * minute
        scheduler.tick()
        assertEquals(2, calendar.loads)
    }

    @Test
    fun `an event created after the last load is picked up on the next one`() = runTest {
        val calendar = FakeCalendar(emptyList())
        val posted = RecordingNotifier()
        var clock = now
        val scheduler = scheduler(
            calendar,
            posted,
            FakePreferences(),
            clock = { clock },
            refreshMillis = minute,
        )

        scheduler.tick()
        calendar.events = listOf(call())
        clock = now + 2 * minute

        scheduler.tick()

        assertEquals(1, posted.notes.size)
    }

    // -- fakes -------------------------------------------------------------

    private class RecordingNotifier : Notifier {
        val notes = mutableListOf<DesktopNotification>()
        override fun post(note: DesktopNotification) {
            notes += note
        }
    }

    private class FakeCalendar(var events: List<CalendarEvent>) : CalendarRepository {
        var loads = 0
        var fails = false

        override suspend fun events(
            fromMillis: Long,
            toMillis: Long,
        ): ZillitResult<List<CalendarEvent>> {
            loads++
            return if (fails) {
                ZillitResult.Failure(ZillitError.Http(500, "nope"))
            } else {
                ZillitResult.Success(events)
            }
        }


        override suspend fun invitations(
            status: com.zillit.desktop.feature.home.calendar.InvitationStatus,
            cursor: String?,
        ) = ZillitResult.Success(com.zillit.desktop.feature.home.calendar.InvitationPage(emptyList()))
        override suspend fun accept(eventId: String, startMillis: Long) = ZillitResult.Success(Unit)
        override suspend fun decline(eventId: String, startMillis: Long, reason: String?) =
            ZillitResult.Success(Unit)
        override suspend fun delete(eventId: String) = ZillitResult.Success(Unit)

        override suspend fun timezones() =
            ZillitResult.Success(emptyList<com.zillit.desktop.feature.home.calendar.TimezoneOption>())
        override suspend fun save(draft: EventDraft, times: EventTimes, zone: TimeZone) =
            ZillitResult.Success(Unit)
    }

    /** In memory, but honest about scope and defaults. */
    private class FakePreferences : PreferenceStore {
        private val values = MutableStateFlow(mapOf<String, Any>())

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any> observe(key: PreferenceKey<T>): Flow<T> =
            values.map { it[key.name] as? T ?: key.default }

        @Suppress("UNCHECKED_CAST")
        override suspend fun <T : Any> get(key: PreferenceKey<T>): T =
            values.value[key.name] as? T ?: key.default

        override suspend fun <T : Any> set(key: PreferenceKey<T>, value: T) {
            values.value = values.value + (key.name to value)
        }

        override suspend fun <T : Any> remove(key: PreferenceKey<T>) {
            values.value = values.value - key.name
        }

        override suspend fun setActiveProject(projectId: String?) = Unit

        override suspend fun clear(scope: PreferenceScope) {
            values.value = emptyMap()
        }
    }
}
