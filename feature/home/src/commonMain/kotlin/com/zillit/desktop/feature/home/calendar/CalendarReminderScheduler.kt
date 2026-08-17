package com.zillit.desktop.feature.home.calendar

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.onFailure
import com.zillit.desktop.core.common.onSuccess
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.notifications.DesktopNotification
import com.zillit.desktop.core.notifications.Notifier
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Fires desktop notifications for calendar reminders.
 *
 * ## Why this polls rather than sleeping until each reminder
 *
 * The obvious design — one timer per reminder, sleeping until its moment — is
 * wrong on a laptop. A machine suspended at lunch does not run timers while it
 * is shut, and wakes owing every alert it missed. Timers also drift when the
 * clock is corrected or the user changes timezone mid-flight, which on a
 * production that shoots across territories is a Tuesday.
 *
 * So this wakes on a short tick and asks a plain question against the wall
 * clock: what is due *now*? Sleeping through a tick costs nothing, because the
 * next tick asks the same question and gets the right answer. [dueReminders]
 * then refuses anything more than a few minutes stale, so waking from a long
 * sleep produces silence rather than a pile of alerts for the morning.
 *
 * ## Why it loads its own events
 *
 * Not from whatever the calendar screen has on display: reminders have to fire
 * while the user is reading their mail, looking at next month, or has never
 * opened the calendar this session. It keeps a rolling window of the next two
 * days, which is comfortably past the longest reminder the form offers.
 */
class CalendarReminderScheduler(
    private val repository: CalendarRepository,
    private val notifier: Notifier,
    private val preferences: PreferenceStore,
    private val nowMillis: () -> Long,
    private val tickMillis: Long = TICK_MILLIS,
    private val refreshMillis: Long = REFRESH_MILLIS,
) {
    private var events: List<CalendarEvent> = emptyList()
    private var loadedAtMillis: Long = 0
    private var hasLoadedOnce = false

    private var fired: MutableSet<String> = mutableSetOf()
    private var hasReadFired = false

    /** Runs until the surrounding scope is cancelled. */
    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            tick()
            delay(tickMillis)
        }
    }

    /**
     * One pass: refresh if stale, then deliver whatever is due.
     *
     * Internal so a test can step it deterministically instead of waiting on
     * real seconds to pass.
     */
    internal suspend fun tick() {
        // Muted before anything else — a muted client should also be a quiet
        // one on the network, not one that keeps polling to discard the answer.
        if (preferences.get(ZillitPreferences.MuteNotifications)) return

        val now = nowMillis()
        refreshIfStale(now)

        val due = dueReminders(events, now, readFired(now))
        if (due.isEmpty()) return

        due.forEach { reminder ->
            notifier.post(
                DesktopNotification(title = reminder.headline(now), body = reminder.body),
            )
            fired += reminder.key
        }
        writeFired(now)
    }

    /**
     * Reloads the window when it has aged out.
     *
     * A failed load keeps the events already in hand rather than clearing them:
     * a network blip must not silently switch reminders off. The attempt is
     * still stamped, so a server that is down is retried on the refresh
     * interval rather than on every tick.
     */
    private suspend fun refreshIfStale(now: Long) {
        if (hasLoadedOnce && now - loadedAtMillis < refreshMillis) return
        loadedAtMillis = now

        repository.events(
            // Back to the edge of the grace window: an event whose reminder is
            // still deliverable must still be in the window it is read from.
            fromMillis = now - REMINDER_GRACE_MILLIS,
            toMillis = now + HORIZON_MILLIS,
        ).onSuccess { loaded ->
            events = loaded
            hasLoadedOnce = true
        }.onFailure { error ->
            ZillitLog.w(TAG) { "Could not refresh reminders: $error" }
        }
    }

    /**
     * The occurrences already notified about.
     *
     * Persisted, so restarting the app inside a reminder's grace window does
     * not deliver it a second time. Read once and then kept in memory — this
     * runs every tick, and re-reading a preference to answer the same question
     * is work for nothing.
     */
    private suspend fun readFired(now: Long): Set<String> {
        if (!hasReadFired) {
            fired = parseFiredKeys(preferences.get(ZillitPreferences.CalendarRemindersFired), now)
            hasReadFired = true
        }
        return fired
    }

    private suspend fun writeFired(now: Long) {
        fired = parseFiredKeys(fired.joinToString(KEY_SEPARATOR), now)
        preferences.set(ZillitPreferences.CalendarRemindersFired, fired.joinToString(KEY_SEPARATOR))
    }

    private companion object {
        const val TAG = "CalendarReminders"
    }
}

/**
 * Reads back the fired-reminder keys, dropping the ones that no longer matter.
 *
 * Pruning on every read is what keeps this from growing without bound: a key is
 * only needed while its occurrence is recent enough to still be deliverable,
 * and [FIRED_RETENTION_MILLIS] is far past that. Keys whose start cannot be
 * read are dropped rather than kept forever — a value this one client writes
 * and reads is not worth a migration.
 */
internal fun parseFiredKeys(raw: String, nowMillis: Long): MutableSet<String> {
    val oldest = nowMillis - FIRED_RETENTION_MILLIS
    return raw.split(KEY_SEPARATOR)
        .filter { it.isNotBlank() }
        .filter { key -> (key.reminderKeyStartMillis() ?: Long.MIN_VALUE) >= oldest }
        .toMutableSet()
}

/** How far ahead reminders are loaded. Comfortably past the longest one offered. */
private const val HORIZON_MILLIS = 48 * 60 * 60 * 1000L

/** How often the wall clock is consulted. */
private const val TICK_MILLIS = 30 * 1000L

/** How often the event window is reloaded. */
private const val REFRESH_MILLIS = 5 * 60 * 1000L

/** How long a fired key is remembered. Well past the grace window. */
private const val FIRED_RETENTION_MILLIS = 60 * 60 * 1000L

/** Mongo ids are hex, so neither separator can appear inside a key. */
private const val KEY_SEPARATOR = ";"
