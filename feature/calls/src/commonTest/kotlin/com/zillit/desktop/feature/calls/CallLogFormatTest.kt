package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.readCallLog
import com.zillit.desktop.feature.calls.ui.callTimeLabel
import com.zillit.desktop.feature.calls.ui.formatDuration
import com.zillit.desktop.feature.calls.ui.subtitle
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** How a call-log row reads. */
class CallLogFormatTest {

    private val utc = TimeZone.UTC

    /** 2026-08-06 14:11:00 UTC. */
    private val now = 1_785_939_060_000L

    @Test
    fun `call_duration is milliseconds`() {
        // Every value this production returns, read as milliseconds. Read as
        // seconds these were "24h 48m" and "17h 13m" — the bug this fixes.
        assertEquals("1m 29s", formatDuration(89_289))
        assertEquals("1m 2s", formatDuration(62_009))
        assertEquals("14s", formatDuration(14_209))
        assertEquals("50s", formatDuration(50_271))
    }

    @Test
    fun `longer calls read in hours and minutes`() {
        assertEquals("2m 14s", formatDuration(134_000))
        assertEquals("1h 0m", formatDuration(3_600_000))
        assertEquals("1h 30m", formatDuration(5_400_000))
    }

    @Test
    fun `a call with no duration says so rather than showing zero`() {
        assertEquals("No answer", formatDuration(0))
        assertEquals("No answer", formatDuration(-1))
        // Connected but sub-second is still a connected call.
        assertEquals("0s", formatDuration(400))
    }

    @Test
    fun `the time column follows the chat listing's convention`() {
        // Same day → a clock.
        assertEquals("09:30", callTimeLabel(now - (4 * 3_600_000L) - (41 * 60_000L), now, utc))
        // Same year → day, month and clock.
        val earlier = now - (30L * 24 * 3_600_000L)
        assertTrue(earlier.let { callTimeLabel(it, now, utc) }.contains("Jul"))
        // The epoch is not a time.
        assertEquals("", callTimeLabel(0, now, utc))
    }

    @Test
    fun `the subtitle leads with what happened, then when`() {
        val answered = readCallLog(
            Json.parseToJsonElement("""{"call_uuid":"u1","call_duration":134000,"start_time":$now}"""),
            "me",
        )
        // 134_000 ms, started "now" → today, so a bare clock.
        val line = answered?.subtitle(now, utc).orEmpty()
        assertTrue(line.startsWith("2m 14s · "), "was: $line")
        assertTrue(line.substringAfter("· ").matches(Regex("""\d{2}:\d{2}""")), "was: $line")
    }

    @Test
    fun `a missed row shows no duration at all`() {
        val missed = readCallLog(
            Json.parseToJsonElement(
                """{"call_uuid":"u1","missedCall":true,"call_duration":0,"start_time":$now}""",
            ),
            "me",
        )
        val line = missed?.subtitle(now, utc).orEmpty()
        assertTrue(line.startsWith("Missed"))
        assertTrue(!line.contains("No answer"))
    }
}
