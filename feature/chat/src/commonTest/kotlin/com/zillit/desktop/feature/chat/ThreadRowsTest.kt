package com.zillit.desktop.feature.chat

import com.zillit.desktop.feature.chat.domain.ChatMessage
import com.zillit.desktop.feature.chat.domain.chatDayLabel
import com.zillit.desktop.feature.chat.ui.ThreadRow
import com.zillit.desktop.feature.chat.ui.threadRows
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The day chips a thread hangs over each day's first message.
 *
 * The ordering is the part worth pinning: the list renders reversed
 * (newest at the visual bottom), so a chip built in the wrong place shows
 * up *under* its day instead of over it — plausible in review, wrong on
 * screen.
 */
class ThreadRowsTest {

    private val zone = TimeZone.of("Asia/Kolkata")

    // 2026-08-12 ~09:30 IST.
    private val now = 1_786_507_000_000L

    private fun message(id: String, atMillis: Long, mine: Boolean = false) = ChatMessage(
        id = id,
        uniqueId = id,
        senderId = if (mine) "me" else "them",
        receiverId = if (mine) "them" else "me",
        body = "hello $id",
        timestampMillis = atMillis,
        isMine = mine,
    )

    private val hourMillis = 3_600_000L
    private val dayMillis = 24 * hourMillis

    // -- the labels ----------------------------------------------------------

    @Test
    fun `today and yesterday are words, older days are dates`() {
        assertEquals("Today", chatDayLabel(now - hourMillis, now, zone))
        assertEquals("Yesterday", chatDayLabel(now - dayMillis, now, zone))
        // 2026-08-05 is a Wednesday, and this year needs no year printed.
        assertEquals("Wed 5 Aug", chatDayLabel(now - 7 * dayMillis, now, zone))
        assertEquals("12 Aug 2025", chatDayLabel(now - 365 * dayMillis, now, zone))
    }

    @Test
    fun `the epoch gets no label`() {
        assertEquals("", chatDayLabel(0, now, zone))
    }

    // -- the ordering ---------------------------------------------------------

    @Test
    fun `each day's first message carries the chip, and the list comes reversed`() {
        val rows = threadRows(
            listOf(
                message("m1", now - dayMillis - hourMillis),
                message("m2", now - dayMillis, mine = true),
                message("m3", now - hourMillis),
            ),
            now,
            zone,
        )

        // Newest first — what a reverseLayout list renders bottom-up — and in
        // that order each chip sits *after* its day's messages, which the
        // reversed list draws as "above".
        assertEquals(
            listOf("m3", "day-today", "m2", "m1", "day-yesterday"),
            rows.map { row ->
                when (row) {
                    is ThreadRow.Message -> row.message.id
                    is ThreadRow.DayMark -> if (row.label == "Today") "day-today" else "day-yesterday"
                }
            },
        )
    }

    @Test
    fun `one day, one chip, however many messages`() {
        val rows = threadRows(
            (1..5).map { message("m$it", now - hourMillis + it * 60_000L) },
            now,
            zone,
        )
        assertEquals(1, rows.count { it is ThreadRow.DayMark })
        assertEquals(5, rows.count { it is ThreadRow.Message })
    }

    @Test
    fun `a message with no clock breaks no day`() {
        val rows = threadRows(
            listOf(
                message("m1", now - hourMillis),
                message("pending", 0, mine = true),
            ),
            now,
            zone,
        )
        // The clockless message still renders — newest first — but mints no
        // "1 Jan 1970" chip.
        assertEquals(1, rows.count { it is ThreadRow.DayMark })
        assertTrue(rows.first() is ThreadRow.Message)
    }

    @Test
    fun `chip keys are the dates, so recomposition reuses them`() {
        val rows = threadRows(
            listOf(message("m1", now - dayMillis), message("m2", now)),
            now,
            zone,
        )
        val marks = rows.filterIsInstance<ThreadRow.DayMark>().map(ThreadRow.DayMark::key)
        assertEquals(marks.toSet().size, marks.size)
        assertTrue(marks.all { it.startsWith("day-") }, marks.toString())
    }
}
