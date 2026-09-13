package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.data.deleteQuery
import com.zillit.desktop.feature.boxschedule.data.eventWire
import com.zillit.desktop.feature.boxschedule.data.parseBlock
import com.zillit.desktop.feature.boxschedule.data.parseEvent
import com.zillit.desktop.feature.boxschedule.data.parseHistory
import com.zillit.desktop.feature.boxschedule.data.parsePresets
import com.zillit.desktop.feature.boxschedule.data.parseRevisions
import com.zillit.desktop.feature.boxschedule.data.updateQuery
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.DiaryAudience
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryHistory
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.GuestEmail
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The diary's wire: what is read off `/events`, `/activity-log`, `/revisions` and `/user-preset`, and what is sent. */
class DiaryWireTest {

    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `an event reads its audience, guests, author and an ISO creation time`() {
        val event = assertNotNull(
            parseEvent(
                json(
                    """{"_id":"e1","eventType":"event","title":"Tech recce","startDateTime":1789000000,
                    "endDateTime":"1789003600000","distributeTo":"users","distributeUserIds":["u1",{"_id":"u2"}],
                    "userPresetId":null,"externalEmails":[{"_id":"g1","mail":"A@B.co"},{"email":"c@d.co"}],
                    "createdBy":{"first_name":"Asha","last_name":"Rao"},"createdAt":"2026-09-01T10:00:00Z",
                    "reminder":"15min","textColor":"#C0392B","repeatEndDate":0,"organizerExcluded":true,
                    "scheduleDayId":{"_id":"day-1"}}""",
                ).jsonObject,
            ),
        )
        assertEquals(1_789_000_000_000L, event.startDateTime, "seconds are scaled")
        assertEquals(1_789_003_600_000L, event.endDateTime)
        assertEquals(DiaryAudience(AudienceMode.Users, userIds = listOf("u1", "u2")), event.audience)
        assertEquals(listOf(GuestEmail("g1", "a@b.co"), GuestEmail("", "c@d.co")), event.externalEmails)
        assertEquals("Asha Rao", event.createdByName)
        assertEquals(1_788_256_800_000L, event.createdAt)
        assertEquals("day-1", event.scheduleDayId, "a populated day is read by its id")
        assertTrue(event.organizerExcluded)
    }

    @Test
    fun `the event body carries the whole audience and the guests, and the preset id only for presets`() {
        val base = DiaryDraft(
            kind = DiaryKind.Event, title = "Standup", body = "", date = 10, startDateTime = 11, endDateTime = 12,
            fullDay = false, audience = DiaryAudience(AudienceMode.Presets, presetId = "p1"),
            externalEmails = listOf(GuestEmail("g1", "a@b.co")), reminder = "1hr", textColor = "#000000",
        )
        val body = eventWire(base, create = true)
        assertEquals("presets", body["distributeTo"]?.jsonPrimitive?.content)
        assertEquals("p1", body["userPresetId"]?.jsonPrimitive?.content)
        assertEquals(JsonArray(emptyList()), body["distributeUserIds"])
        assertEquals(
            "a@b.co",
            ((body["externalEmails"] as JsonArray).first() as JsonObject)["mail"]?.jsonPrimitive?.content,
        )
        assertEquals("1hr", body["reminder"]?.jsonPrimitive?.content)
        assertEquals("true", body["advancedEnabled"]?.jsonPrimitive?.content)
        assertNull(body["createEventInCalendar"], "not asked yet: nothing sent")

        val users = eventWire(
            base.copy(audience = DiaryAudience(AudienceMode.Users, userIds = listOf("u1"), presetId = "stale")),
            create = false,
        )
        assertEquals(JsonNull, users["userPresetId"])

        val note = eventWire(base.copy(kind = DiaryKind.Note, audience = DiaryAudience()), create = true)
        assertEquals("", note["distributeTo"]?.jsonPrimitive?.content)
        assertNull(note["externalEmails"], "a note has no guests")
    }

    @Test
    fun `recurring scopes on the query string`() {
        assertEquals(emptyMap(), updateQuery(RecurrenceScope.All, 5L), "a whole-series edit sends nothing")
        assertEquals(mapOf("update_type" to "single", "occurrence_date" to 5L), updateQuery(RecurrenceScope.Single, 5L))
        assertEquals(emptyMap(), deleteQuery(null, 5L), "a plain delete sends nothing")
        assertEquals(mapOf("delete_type" to "all"), deleteQuery(RecurrenceScope.All, 5L))
        assertEquals(
            mapOf("delete_type" to "this_and_following", "occurrence_date" to 5L),
            deleteQuery(RecurrenceScope.ThisAndFollowing, 5L),
        )
    }

    @Test
    fun `history rows pair with the revision written beside them`() {
        val entries = parseHistory(
            json(
                """{"logs":[{"_id":"l1","action":"updated","targetType":"schedule_day","targetId":"d1",
                "targetTitle":"Prep","performedBy":{"userId":"u9","full_name":"Dev Patel"},
                "createdAt":1789000005000}]}""",
            ),
        )
        val entry = entries.single()
        assertEquals("CHANGED", entry.actionLabel)
        assertEquals("Schedule", entry.targetLabel)
        assertEquals("u9", entry.performedById)
        assertEquals("Dev Patel", entry.performedByName)

        val revisions = parseRevisions(
            json(
                """[{"targetId":"d1","createdAt":1789000000000,"revisionNumber":3,
                "snapshot":{"title":"Prep","calendarDays":[1789000000000],"typeId":{"_id":"t1"}}},
                {"targetId":"d1","createdAt":1788000000000,"revisionNumber":2}]""",
            ),
        )
        val paired = assertNotNull(DiaryHistory.revisionFor(entry, revisions))
        assertEquals(3, paired.number)
        assertEquals("t1", paired.snapshot.typeId)
        assertEquals(listOf(1_789_000_000_000L), paired.snapshot.calendarDays)
    }

    @Test
    fun `a block's day count and version are counts, not seconds`() {
        val block = assertNotNull(
            parseBlock(
                json(
                    """{"_id":"b1","typeId":{"_id":"t1","title":"Prep","color":"#3498DB"},"calendarDays":[1789000000],
                    "startDate":1789000000,"endDate":1789000000,"numberOfDays":5,"version":3}""",
                ).jsonObject,
            ),
        )
        assertEquals(5, block.numberOfDays)
        assertEquals(3, block.version)
        assertEquals(listOf(1_789_000_000_000L), block.calendarDays, "the dates themselves still scale")
        assertEquals("Prep", block.typeName, "a populated type names the block")
    }

    @Test
    fun `presets read their members and unwrap a wrapped designation`() {
        val preset = parsePresets(
            json(
                """[{"_id":"p1","preset_name":"Camera",
                "users":[{"user_id":"u1","full_name":"Ivy","designation":"{designation:dop_label}"}]}]""",
            ),
        ).single()
        assertEquals("Camera", preset.name)
        assertEquals(1, preset.memberCount)
        assertEquals("dop_label", preset.members.single().designation)
    }
}
