package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.data.publicationWire
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The publish body: only the keys the destination reads, and no others.
 *
 * The receiving endpoints refuse in both directions — a `mode` where none is
 * supported, a page publish with no scene — so an over-eager body is as
 * broken as a thin one, and neither failure says anything useful to the
 * person who filled the form in.
 */
class PublicationWireTest {

    private val draft = PublishDraft(
        documentIds = listOf("d1", "d2"),
        note = "For Monday",
        sceneNumber = " 12A ",
        scheduleDate = 1_772_755_200_000,
        scheduleType = "full_schedule_pages",
        episode = " 101 ",
        name = " DOD v2 ",
    )

    private fun keys(category: String, from: PublishDraft = draft) =
        publicationWire(category, from).keys

    @Test
    fun `every body names its category and documents`() {
        val body = publicationWire("info", draft)

        assertEquals("info", body["category"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("d1", "d2"),
            body["document_ids"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `a plain destination sends a note and nothing else`() {
        assertEquals(setOf("category", "folder_id", "document_ids", "note"), keys("info"))
    }

    /**
     * The web spreads `{ note }` whenever the destination has the field, so
     * an untouched Note goes as `""` rather than being left out. Matched
     * deliberately: the two notice boards and the two chat targets read the
     * key, and "absent" and "empty" are not the same thing to them.
     */
    @Test
    fun `a destination with the field sends its note even when empty`() {
        assertTrue("note" in keys("info", draft.copy(note = "")))
    }

    /** The tool destinations have no note field; sending one is noise at best. */
    @Test
    fun `the tool destinations never carry a note`() {
        assertFalse("note" in keys("schedule_full"))
        assertFalse("note" in keys("schedule_dod"))
    }

    @Test
    fun `pages carry a trimmed scene number`() {
        val body = publicationWire("page_distribution", draft)

        assertEquals("12A", body["scene_number"]?.jsonPrimitive?.content)
        assertFalse("schedule_type" in body.keys, "that is the schedule tool's page, not this one")
    }

    @Test
    fun `schedule pages carry both the scene and the schedule type`() {
        val body = publicationWire("schedule_page", draft)

        assertEquals("12A", body["scene_number"]?.jsonPrimitive?.content)
        assertEquals("full_schedule_pages", body["schedule_type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `schedule full carries its date and no scene`() {
        val body = publicationWire("schedule_full", draft)

        assertEquals(1_772_755_200_000, body["schedule_date"]?.jsonPrimitive?.content?.toLong())
        assertFalse("scene_number" in body.keys)
    }

    @Test
    fun `an unpicked schedule date is omitted rather than sent as zero`() {
        assertFalse("schedule_date" in keys("schedule_full", draft.copy(scheduleDate = null)))
    }

    @Test
    fun `dod carries a trimmed name`() {
        assertEquals("DOD v2", publicationWire("schedule_dod", draft)["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the episode is trimmed and only sent where it is read`() {
        assertEquals("101", publicationWire("schedule_dod", draft)["episode"]?.jsonPrimitive?.content)
        assertFalse("episode" in keys("info"), "a notice board has no episodes")
        assertFalse("episode" in keys("call_sheet_unit"))
    }

    @Test
    fun `a blank episode is omitted, so a feature sends none`() {
        assertFalse("episode" in keys("schedule_dod", draft.copy(episode = "")))
    }

    // -- republishing ---------------------------------------------------------

    @Test
    fun `a first publish sends no mode at all`() {
        val first = draft.copy(mode = null)

        assertFalse("mode" in keys("call_sheet_unit", first))
        assertFalse("replace_chat_id" in keys("call_sheet_unit", first))
    }

    /**
     * One live file for one new one. A list here reaches the backend as an id
     * it cannot match, and the publish quietly lands as an addition.
     */
    @Test
    fun `a replace names its mode and its one target, as a string`() {
        val body = publicationWire(
            "call_sheet_unit",
            draft.copy(mode = PublishMode.Replace, replaceChatId = "c1"),
        )

        assertEquals("replace", body["mode"]?.jsonPrimitive?.content)
        assertEquals("c1", body["replace_chat_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an add alongside sends its own wire word, and no replace target`() {
        val body = publicationWire(
            "production_report",
            draft.copy(mode = PublishMode.Add, replaceChatId = "c1"),
        )

        assertEquals("addition", body["mode"]?.jsonPrimitive?.content)
        assertFalse("replace_chat_id" in body.keys)
    }

    @Test
    fun `a resend names the third mode`() {
        val body = publicationWire("production_report", draft.copy(mode = PublishMode.Resend))

        assertEquals("resend", body["mode"]?.jsonPrimitive?.content)
    }

    /**
     * The server answers `publication_mode_not_supported` for a mode sent
     * anywhere else, so the target's own flag gates it — not the draft's.
     */
    @Test
    fun `a mode never reaches a destination that does not republish`() {
        val replacing = draft.copy(mode = PublishMode.Replace, replaceChatId = "c1")

        assertFalse("mode" in keys("info", replacing))
        assertFalse("replace_chat_id" in keys("info", replacing))
        assertFalse("mode" in keys("schedule_full", replacing))
    }

    // -- documents vs document_ids -------------------------------------------

    /**
     * ZL-20245: the two republishing destinations take `documents`, so each
     * file can carry an optional display name. Never both shapes in one body.
     */
    @Test
    fun `a republishing destination sends documents, not document_ids`() {
        val body = publicationWire(
            "call_sheet_unit",
            draft.copy(displayNames = mapOf("d1" to "  Day 12 call sheet  ")),
        )

        assertFalse("document_ids" in body.keys)
        val documents = body["documents"]?.jsonArray.orEmpty()
        assertEquals("d1", documents.first().jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("Day 12 call sheet", documents.first().jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a file with no display name carries only its id`() {
        val body = publicationWire("production_report", draft)

        assertEquals(setOf("id"), body["documents"]?.jsonArray?.first()?.jsonObject?.keys)
    }

    @Test
    fun `every other destination keeps document_ids`() {
        val body = publicationWire("info", draft.copy(displayNames = mapOf("d1" to "ignored")))

        assertFalse("documents" in body.keys)
        assertEquals(listOf("d1", "d2"), body["document_ids"]?.jsonArray?.map { it.jsonPrimitive.content })
    }

    /** The web sends `folder_id` either way; a publish from the root says so with a null. */
    @Test
    fun `the folder rides along, and the root sends an explicit null`() {
        assertEquals(
            "f1",
            publicationWire("info", draft.copy(folderId = "f1"))["folder_id"]?.jsonPrimitive?.content,
        )
        assertTrue(publicationWire("info", draft)["folder_id"] is JsonNull)
    }

    @Test
    fun `an unknown category yields the bare body rather than throwing`() {
        val body = publicationWire("made_up", draft)

        assertEquals(setOf("category", "folder_id", "document_ids"), body.keys)
        assertTrue(body["category"]?.jsonPrimitive?.content == "made_up")
    }
}
