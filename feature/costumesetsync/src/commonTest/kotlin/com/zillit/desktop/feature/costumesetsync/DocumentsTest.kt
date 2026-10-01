package com.zillit.desktop.feature.costumesetsync

import com.zillit.desktop.feature.costumesetsync.domain.dateMs
import com.zillit.desktop.feature.costumesetsync.domain.docDayKey
import com.zillit.desktop.feature.costumesetsync.domain.docName
import com.zillit.desktop.feature.costumesetsync.domain.docSceneRows
import com.zillit.desktop.feature.costumesetsync.domain.docSourceKey
import com.zillit.desktop.feature.costumesetsync.domain.isTextDoc
import com.zillit.desktop.feature.costumesetsync.domain.latestOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentsTest {
    @Test
    fun docDayKeyReadsAnIsoStringOrEpochMsAndNothingForUnset() {
        assertEquals("2026-09-30", docDayKey(sceneRec("""{"sheet_date":"2026-09-30"}""")))
        assertEquals("2026-10-02", docDayKey(sceneRec("""{"sheet_date":${dateMs("2026-10-02")}}""")))
        assertEquals("", docDayKey(sceneRec("""{"sheet_date":null}""")))
        assertEquals("", docDayKey(null))
    }

    @Test
    fun textDocsAreFinalDraftFountainAndPlainText() {
        assertTrue(listOf("a.fdx", "a.fountain", "a.TXT", "a.csv", "a.tsv").all(::isTextDoc))
        assertFalse(isTextDoc("a.pdf"))
        assertFalse(isTextDoc("a.xlsx"))
    }

    @Test
    fun namesTheSourceOfADocument() {
        assertEquals("csync_doc_source_upload", docSourceKey(sceneRec("""{"source":"UPLOAD"}""")))
        assertEquals("csync_doc_source_script_distribution", docSourceKey(sceneRec("""{"source":"ZILLIT","kind":"SCRIPT"}""")))
        assertEquals("csync_doc_source_home_callsheet", docSourceKey(sceneRec("""{"source":"ZILLIT","kind":"CALLSHEET"}""")))
    }

    @Test
    fun latestIsTheFlaggedOneElseTheFirst() {
        val docs = listOf(sceneRec("""{"_id":"a"}"""), sceneRec("""{"_id":"b","latest":true}"""))
        assertEquals("b", latestOf(docs)?.id)
        assertEquals("a", latestOf(docs.take(1))?.id)
        assertEquals("f.pdf", docName(sceneRec("""{"attachment":{"name":"f.pdf"}}""")))
    }

    private fun scene(id: String, number: String, intExt: String = "", location: String = "", name: String = "", status: String = "DRAFT", date: String = "") =
        sceneRec(
            """{"_id":"$id","number":"$number","int_ext":"$intExt","location":"$location","name":"$name","status":"$status",
            |"shoot_date":${if (date.isEmpty()) 0 else dateMs(date)}}""".trimMargin(),
        )

    private val scenes = listOf(
        scene("1", "1", "INT", "Kitchen", date = "2026-10-03"),
        scene("2", "2", name = "Chase", date = "2026-10-01"),
        scene("3", "3", "EXT", "Street", status = "OMITTED", date = "2026-10-01"),
        scene("4", "4", "EXT", "Park"),
        scene("5", "5", date = "2026-10-01"),
    )

    @Test
    fun scriptListsEverySceneNotOmittedWithItsLocationLine() {
        val out = docSceneRows("SCRIPT", scenes)
        assertEquals(listOf("1" to "INT. Kitchen", "2" to "Chase", "4" to "EXT. Park", "5" to ""), out.rows.map { it.scene.str("number") to it.note })
        assertEquals(0, out.undated)
    }

    @Test
    fun scheduleListsDatedScenesByDayAndCountsTheUndated() {
        val out = docSceneRows("SCHEDULE", scenes)
        assertEquals(listOf("2" to "2026-10-01", "5" to "2026-10-01", "1" to "2026-10-03"), out.rows.map { it.scene.str("number") to it.note })
        assertEquals(1, out.undated)
    }

    @Test
    fun callSheetListsOnlyTheScenesOnItsDay() {
        assertEquals(listOf("2", "5"), docSceneRows("CALLSHEET", scenes, "2026-10-01").rows.map { it.scene.str("number") })
        assertTrue(docSceneRows("CALLSHEET", scenes, "").rows.isEmpty())
    }
}
