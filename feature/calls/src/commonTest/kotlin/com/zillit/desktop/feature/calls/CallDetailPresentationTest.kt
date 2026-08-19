package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallLine
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallLogParticipant
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.clock12h
import com.zillit.desktop.feature.calls.ui.detailDuration
import com.zillit.desktop.feature.calls.ui.detailParticipants
import com.zillit.desktop.feature.calls.ui.detailSubtitle
import com.zillit.desktop.feature.calls.ui.directionLabel
import com.zillit.desktop.feature.calls.ui.formatMillis
import com.zillit.desktop.feature.calls.ui.participantsHeading
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Call activity sheet's words, against Android's `CallActivityDetailSheet`. */
class CallDetailPresentationTest {

    private val names = mapOf("me" to "Sahil", "u-aisha" to "Aisha Khan", "u-vivek" to "Vivek Mishra")

    private fun row(
        mode: CallMode = CallMode.Private,
        type: CallType = CallType.Audio,
        line: CallLine = CallLine.One,
        missed: Boolean = false,
        outgoing: Boolean = true,
        duration: Long = 0,
    ) = CallLogEntry(
        callUuid = "c1",
        direction = if (outgoing) CallLogDirection.Outgoing else CallLogDirection.Incoming,
        mode = mode,
        type = type,
        missed = missed,
        durationMillis = duration,
        startedAtMillis = 0,
        peerUserId = if (outgoing) "u-aisha" else "me",
        line = line,
        callerUserId = "me",
        calleeUserId = "u-aisha",
    )

    @Test
    fun `the header names the kind and the line`() {
        assertEquals("Audio call · Line 1", row().detailSubtitle())
        assertEquals("Video call · Line 2", row(type = CallType.Video, line = CallLine.Two).detailSubtitle())
        assertEquals("Audio call · Line 3", row(line = CallLine.Three).detailSubtitle())
    }

    @Test
    fun `the direction chip - a miss outranks the direction`() {
        assertEquals("Outgoing", row().directionLabel())
        assertEquals("Incoming", row(outgoing = false).directionLabel())
        assertEquals("Missed", row(outgoing = false, missed = true).directionLabel())
    }

    @Test
    fun `the legacy roster - caller first as Host, statuses as badges, the callee derived`() {
        val sheet = row(duration = 5_000)
            .copy(callUsers = listOf(CallLogParticipant("u-aisha", status = "declined")))
            .detailParticipants("me", names::get)
        assertEquals(listOf("me", "u-aisha"), sheet.map { it.userId })
        assertEquals("You", sheet[0].name)
        assertEquals("Host", sheet[0].badge)
        assertEquals("Started the call", sheet[0].subLabel)
        assertEquals("Aisha Khan", sheet[1].name)
        assertEquals("Declined", sheet[1].badge)
        assertNull(sheet[1].subLabel)
    }

    @Test
    fun `the legacy roster falls back to what the row proves`() {
        val missed = row(missed = true).detailParticipants("me", names::get)
        assertEquals("Missed", missed[1].badge)
        val connected = row(duration = 9_000).detailParticipants("me", names::get)
        assertEquals("Left", connected[1].badge)
        val nothing = row().detailParticipants("me", names::get)
        assertNull(nothing[1].badge)
        // A group row does not invent a callee.
        val group = row(mode = CallMode.Group).detailParticipants("me", names::get)
        assertEquals(listOf("me"), group.map { it.userId })
    }

    @Test
    fun `the rich roster - names, guests, invites, attendance and the server's missed verdict`() {
        val sheet = row(line = CallLine.Three).copy(
            participants = listOf(
                CallLogParticipant("me", status = "caller", joinCount = 1, totalMillis = 65_000),
                CallLogParticipant(
                    "g1", status = "ringing", displayName = "Guest Sam", isGuest = true,
                    invitedBy = "me", joinCount = 3, leaveCount = 2, totalMillis = 45_000,
                ),
                CallLogParticipant("u-vivek", status = "ringing", missed = true),
                CallLogParticipant("u-aisha", status = "", totalMillis = 3_000),
            ),
        ).detailParticipants("me", names::get)

        assertEquals("You", sheet[0].name)
        assertEquals("Host", sheet[0].badge)
        assertEquals("1m 5s", sheet[0].meta, "a single clean join is not counted out loud")

        assertEquals("Guest Sam", sheet[1].name)
        assertEquals(true, sheet[1].isGuest)
        // The inviter is named from the directory, as Android does — not as "You".
        assertEquals("Added by Sahil", sheet[1].subLabel)
        assertEquals("Ringing", sheet[1].badge)
        assertEquals("45s · 3 joins · 2 leaves", sheet[1].meta)

        assertEquals("Vivek Mishra", sheet[2].name)
        assertEquals("Missed", sheet[2].badge, "the verdict beats a status still reading ringing")
        assertEquals("Not joined", sheet[2].meta)

        assertEquals("Left", sheet[3].badge, "time in the call with no status reads as left")
    }

    @Test
    fun `a missed call's roster carries no attendance`() {
        val sheet = row(line = CallLine.Three, missed = true)
            .copy(participants = listOf(CallLogParticipant("me", status = "caller", totalMillis = 500)))
            .detailParticipants("me", names::get)
        assertNull(sheet[0].meta)
    }

    @Test
    fun `the duration chip - the row's own on Lines 1 and 2, rebuilt from answers on Line 3`() {
        assertNull(row().detailDuration())
        assertEquals("1m 30s", row(duration = 90_000).detailDuration())
        // Line 3: start 1000, duration 60000 → end 61000; first answer 11000 → 50s live.
        val live = row(line = CallLine.Three, duration = 60_000).copy(
            startedAtMillis = 1_000,
            participants = listOf(
                CallLogParticipant("me", status = "caller", answeredAtMillis = 11_000),
                CallLogParticipant("u-aisha", status = "left", answeredAtMillis = 21_000, totalMillis = 40_000),
            ),
        )
        assertEquals("50s", live.detailDuration())
        // No end stamped (negative duration): the longest single stay.
        val legacy = live.copy(durationMillis = -1_000)
        assertEquals("40s", legacy.detailDuration())
        // Nobody answered: nothing to say.
        val unanswered = row(line = CallLine.Three)
            .copy(participants = listOf(CallLogParticipant("me", status = "caller")))
        assertNull(unanswered.detailDuration())
    }

    @Test
    fun `formatMillis and the twelve-hour clock`() {
        assertNull(formatMillis(0))
        assertEquals("0s", formatMillis(400))
        assertEquals("1h 2m 3s", formatMillis(3_723_000))
        val utc = TimeZone.UTC
        assertEquals("12:05 AM", clock12h(5 * 60_000L, utc))
        assertEquals("03:45 PM", clock12h((15 * 3_600L + 45 * 60L) * 1_000L, utc))
        assertEquals("12:00 PM", clock12h(12 * 3_600_000L, utc))
        assertNull(clock12h(0, utc))
    }

    @Test
    fun `the roster heading counts`() {
        assertEquals("1 Participant", participantsHeading(1))
        assertEquals("3 Participants", participantsHeading(3))
    }
}
