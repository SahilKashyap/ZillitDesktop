package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.core.config.EventCallLine
import com.zillit.desktop.feature.boxschedule.data.eventWire
import com.zillit.desktop.feature.boxschedule.data.parseEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** A Box Schedule event's `line`: optional on read, and sent only for a type that can have a meeting. */
class DiaryCallLineTest {

    private fun event(extra: String = "") = parseEvent(
        Json.parseToJsonElement(
            """{"_id":"e1","eventType":"event","title":"Sprint Review","callType":"video",
               "cncCallGroupId":"sprint-review-9rhy"$extra}""",
        ).jsonObject,
    )!!

    private fun draft(kind: DiaryKind = DiaryKind.Event, callType: String, line: EventCallLine) = DiaryDraft(
        kind = kind, title = "Sprint Review", body = "", date = 10, startDateTime = 11, endDateTime = 12,
        fullDay = false, callType = callType, line = line,
    )

    @Test
    fun `only the exact line_1 is Line 1, and a missing key is Line 2`() {
        assertEquals(EventCallLine.Line2, event().line)
        assertEquals(EventCallLine.Line2, event(""","line":null""").line)
        assertEquals(EventCallLine.Line2, event(""","line":"line_2"""").line)
        assertEquals(EventCallLine.Line1, event(""","line":"line_1"""").line)
        assertEquals("sprint-review-9rhy", event(""","line":"line_1"""").cncCallGroupId)
    }

    @Test
    fun `the three meeting types send their line`() {
        listOf("audio", "video", "meet_in_person_call").forEach { type ->
            assertEquals("line_1", eventWire(draft(callType = type, line = EventCallLine.Line1), create = true)["line"]
                ?.jsonPrimitive?.content, type)
            assertEquals("line_2", eventWire(draft(callType = type, line = EventCallLine.Line2), create = false)["line"]
                ?.jsonPrimitive?.content, type)
        }
    }

    @Test
    fun `other types and notes carry no line`() {
        listOf("meet_in_person", "", "none").forEach { type ->
            val body = eventWire(draft(callType = type, line = EventCallLine.Line1), create = true)
            assertFalse(body.containsKey("line"), type)
        }
        val note = draft(kind = DiaryKind.Note, callType = "video", line = EventCallLine.Line1)
        assertFalse(eventWire(note, create = true).containsKey("line"))
    }
}
