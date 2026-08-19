package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.calendar.EventAudience
import com.zillit.desktop.feature.home.calendar.EventDraft
import com.zillit.desktop.feature.home.calendar.effectiveZone
import com.zillit.desktop.feature.home.calendar.validate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * The form's timezone: typed times mean wall time *in the chosen zone*.
 *
 * The web's `.tz(tz, true)` rule — 09:00 for an LA scout is 09:00 in LA,
 * whatever machine it was typed on. Wrong zone maths silently shifts every
 * cross-timezone call sheet, so the epoch values are pinned exactly.
 */
class TimezoneTest {

    private val kolkata = TimeZone.of("Asia/Kolkata")

    /** Well before the event, so the "already passed" rules stay out of it. */
    private val now = Instant.parse("2026-08-01T00:00:00Z")

    private fun draft(timezoneId: String = "") = EventDraft(
        title = "Location scout",
        dateText = "2026-08-10",
        startText = "09:00",
        endText = "10:00",
        timezoneId = timezoneId,
        // Zone maths is the subject; guests and call types are not.
        audience = EventAudience.Personal,
    )

    @Test
    fun `a chosen zone reinterprets the typed times`() {
        // 09:00 on 2026-08-10 in Los Angeles (PDT, UTC-7) = 16:00 UTC.
        val la = draft("America/Los_Angeles").validate(kolkata, now).times!!
        assertEquals(1_786_377_600_000, la.startMillis)

        // The same wall time in the device zone (IST, UTC+5:30) = 03:30 UTC.
        val device = draft().validate(kolkata, now).times!!
        assertEquals(1_786_332_600_000, device.startMillis)

        // The two differ by exactly the zone offset gap (12h30m) — the wall
        // time held still while the zone moved, the web's `.tz(tz, true)`.
        assertEquals(12 * 3600_000L + 1_800_000L, la.startMillis - device.startMillis)
    }

    @Test
    fun `a zone the device does not know falls back to the device zone`() {
        assertEquals(kolkata, draft("Not/AZone").effectiveZone(kolkata))
        assertEquals(kolkata, draft("").effectiveZone(kolkata))
        assertEquals(
            TimeZone.of("America/Los_Angeles"),
            draft("America/Los_Angeles").effectiveZone(kolkata),
        )
    }
}
