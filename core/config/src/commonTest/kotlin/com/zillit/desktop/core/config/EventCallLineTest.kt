package com.zillit.desktop.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The event `line` contract: only the exact `"line_1"` is Line 1, and one condition says who carries a line. */
class EventCallLineTest {

    @Test
    fun onlyTheExactWireWordIsLineOne() {
        assertEquals(EventCallLine.Line1, EventCallLine.of("line_1"))
        assertEquals(EventCallLine.Line2, EventCallLine.of("line_2"))
    }

    @Test
    fun missingNullAndAnythingElseAreLineTwo() {
        listOf(null, "", " ", "LINE_1", "line_1 ", "line1", "1", "livekit", "line_3").forEach { raw ->
            assertEquals(EventCallLine.Line2, EventCallLine.of(raw), "'$raw' must read as Line 2")
        }
    }

    @Test
    fun aNewEventStartsOnLineOneButThatIsNotAReadDefault() {
        assertEquals(EventCallLine.Line1, EventCallLine.CREATE_DEFAULT)
        // The read side never defaults to Line 1: an old event has no key.
        assertEquals(EventCallLine.Line2, EventCallLine.of(null))
    }

    @Test
    fun theThreeMeetingTypesCarryALine() {
        listOf("audio", "video", "meet_in_person_call").forEach { assertTrue(EventCallLine.applies(it), it) }
    }

    @Test
    fun noMeetingMeansNoLine() {
        listOf(null, "", "none", "meet_in_person", "note").forEach { assertFalse(EventCallLine.applies(it), "$it") }
    }

    @Test
    fun aPersonalEventNeverCarriesALine() {
        assertFalse(EventCallLine.applies("video", personal = true))
    }

    @Test
    fun wireWordsAndEngines() {
        assertEquals("line_1", EventCallLine.Line1.wire)
        assertEquals("line_2", EventCallLine.Line2.wire)
        assertEquals(CallEngines.LIVEKIT, EventCallLine.Line1.engine)
        assertEquals(CallEngines.AGORA, EventCallLine.Line2.engine)
    }
}
