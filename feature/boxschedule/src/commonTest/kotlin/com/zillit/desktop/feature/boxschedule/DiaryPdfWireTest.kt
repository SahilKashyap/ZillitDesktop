package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.feature.boxschedule.data.diaryPdfQuery
import com.zillit.desktop.feature.boxschedule.data.parseDiaryPdf
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfLayout
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.PERSONAL_NOTE_TYPE
import com.zillit.desktop.feature.boxschedule.domain.inDiaryOrder
import com.zillit.desktop.feature.boxschedule.domain.isPersonalNote
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Personal Notes contract (box-schedule-personal-notes.md): the slug
 * never changes, the PDF parameter goes out only for "Without", and a day
 * reads Personal Notes first.
 */
class DiaryPdfWireTest {

    private fun entry(id: String, kind: DiaryKind, noteType: String = "") = DiaryEvent(
        id = id, kind = kind, title = id, body = "", date = 0, startDateTime = 0, endDateTime = 0,
        fullDay = true, location = "", color = "", scheduleDayId = "", noteType = noteType, repeatStatus = "",
        occurrenceId = "", masterEventId = "", isRecurringInstance = false, calendarEventId = "",
    )

    @Test
    fun `with personal notes sends no parameter, so the default file is the one the server always sent`() {
        val query = diaryPdfQuery(DiaryPdfOptions(), DiaryPdfAction.Print, "Sahil Kashyap")

        assertEquals(mapOf("watermark" to "Sahil Kashyap", "action" to "print", "format" to "calendar"), query)
        assertFalse(query.containsKey("includePersonalNotes"))
    }

    @Test
    fun `without personal notes sends the one word the server reads`() {
        val query = diaryPdfQuery(
            DiaryPdfOptions(layout = DiaryPdfLayout.List, includePersonalNotes = false),
            DiaryPdfAction.Share,
            watermark = "",
        )

        assertEquals("false", query["includePersonalNotes"])
        assertEquals("list", query["format"])
        assertEquals("share", query["action"])
    }

    @Test
    fun `the staged file is read off the attachment shape, and a keyless answer is no file`() {
        val answer = """{"media":"box/1.pdf","bucket":"b","region":"r","name":"Box Schedule.pdf",""" +
            """"content_type":"application","content_subtype":"pdf","file_size":"1234"}"""
        val pdf = assertNotNull(parseDiaryPdf(Json.parseToJsonElement(answer).jsonObject))

        assertEquals("box/1.pdf", pdf.media)
        assertEquals("Box Schedule.pdf", pdf.name)
        assertEquals("pdf", pdf.contentSubtype)
        assertEquals(1234L, pdf.fileSizeBytes)
        assertNull(parseDiaryPdf(Json.parseToJsonElement("""{"name":"x.pdf"}""").jsonObject))
    }

    @Test
    fun `the slug is still crew_start and only the label changed`() {
        assertEquals("crew_start", PERSONAL_NOTE_TYPE)
        assertTrue(entry("n", DiaryKind.Note, "crew_start").isPersonalNote)
        assertFalse(entry("n", DiaryKind.Note, "general").isPersonalNote)
        assertFalse(entry("n", DiaryKind.Note).isPersonalNote, "an untyped note is General")
        assertFalse(entry("e", DiaryKind.Event, "crew_start").isPersonalNote, "only notes have a type")
    }

    @Test
    fun `a day reads personal notes first, then general notes, then events, each in its own order`() {
        val day = listOf(
            entry("event-1", DiaryKind.Event),
            entry("general-1", DiaryKind.Note, "general"),
            entry("personal-1", DiaryKind.Note, "crew_start"),
            entry("untyped", DiaryKind.Note),
            entry("event-2", DiaryKind.Event),
            entry("personal-2", DiaryKind.Note, "crew_start"),
        )

        assertEquals(
            listOf("personal-1", "personal-2", "general-1", "untyped", "event-1", "event-2"),
            day.inDiaryOrder().map { it.id },
        )
    }
}
