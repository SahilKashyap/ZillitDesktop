package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.data.publicationWire
import com.zillit.desktop.feature.documentdistribution.domain.PublishDraft
import com.zillit.desktop.feature.documentdistribution.domain.PublishMode
import kotlinx.serialization.json.jsonArray
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
        assertEquals(setOf("category", "document_ids", "note"), keys("info"))
    }

    @Test
    fun `a blank note is left out rather than sent empty`() {
        assertFalse("note" in keys("info", draft.copy(note = "   ")))
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
        val first = draft.copy(replaceChatIds = emptyList())

        assertFalse("mode" in keys("call_sheet_unit", first))
        assertFalse("replace_chat_id" in keys("call_sheet_unit", first))
    }

    @Test
    fun `a replace names its mode and its targets`() {
        val body = publicationWire(
            "call_sheet_unit",
            draft.copy(mode = PublishMode.Replace, replaceChatIds = listOf("c1", "c2")),
        )

        assertEquals("replace", body["mode"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("c1", "c2"),
            body["replace_chat_id"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `an add alongside sends its own wire word, not replace`() {
        val body = publicationWire(
            "production_report",
            draft.copy(mode = PublishMode.Add, replaceChatIds = listOf("c1")),
        )

        assertEquals("addition", body["mode"]?.jsonPrimitive?.content)
    }

    /**
     * The server answers `publication_mode_not_supported` for a mode sent
     * anywhere else, so the target's own flag gates it — not the draft's.
     */
    @Test
    fun `a mode never reaches a destination that does not republish`() {
        val replacing = draft.copy(mode = PublishMode.Replace, replaceChatIds = listOf("c1"))

        assertFalse("mode" in keys("info", replacing))
        assertFalse("replace_chat_id" in keys("info", replacing))
        assertFalse("mode" in keys("schedule_full", replacing))
    }

    @Test
    fun `an unknown category yields the bare body rather than throwing`() {
        val body = publicationWire("made_up", draft)

        assertEquals(setOf("category", "document_ids"), body.keys)
        assertTrue(body["category"]?.jsonPrimitive?.content == "made_up")
    }
}
