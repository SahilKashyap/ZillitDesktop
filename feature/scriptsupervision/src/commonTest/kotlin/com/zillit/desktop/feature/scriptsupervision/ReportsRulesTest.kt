package com.zillit.desktop.feature.scriptsupervision

import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.Reports
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The audit trail's two rules.
 *
 * Restore is the one destructive thing on that screen — it writes over
 * whatever is there now — so offering it where the service cannot do it
 * would be an error a reader only discovers by pressing it.
 */
class ReportsRulesTest {

    private fun entry(module: String, withPrevious: Boolean) = Rec(
        buildJsonObject {
            put("_id", "a1")
            put("module", module)
            put("action", "delete")
            if (withPrevious) put("previous_state", buildJsonObject { put("notes", "old") })
        },
    )

    @Test
    fun `restore needs both a previous state and a kind the service can rebuild`() {
        // Both halves, as the web has them.
        assertTrue(Reports.canRestore(entry("sound_report", withPrevious = true)))
        // Nothing to restore FROM.
        assertFalse(Reports.canRestore(entry("sound_report", withPrevious = false)))
        // The service answers "Unknown module" for a kind it has no model for.
        assertFalse(Reports.canRestore(entry("template", withPrevious = true)))
        assertFalse(Reports.canRestore(entry("character", withPrevious = true)))
    }

    @Test
    fun `scenes and takes are restorable even though they are not audited`() {
        // They are absent from AUDIT_MODULES — the trail does not record them
        // — but the service can still put one back if something else did.
        assertFalse("scene" in Reports.AUDIT_MODULES)
        assertFalse("take" in Reports.AUDIT_MODULES)
        assertTrue(Reports.canRestore(entry("scene", withPrevious = true)))
        assertTrue(Reports.canRestore(entry("take", withPrevious = true)))
    }

    @Test
    fun `an entry with nothing recorded restores nothing and lists nothing`() {
        val bare = Rec(JsonObject(emptyMap()))

        assertFalse(Reports.canRestore(bare))
        assertEquals(emptyList(), Reports.changes(bare))
    }

    @Test
    fun `a change reads as from then to, with an unset side shown as a dash`() {
        val changed = Rec(
            buildJsonObject {
                put(
                    "changes",
                    buildJsonObject {
                        put("notes", buildJsonObject { put("from", "old"); put("to", "new") })
                        // A field that was empty before: the web shows a dash
                        // rather than a blank, so the row still reads.
                        put("camera", buildJsonObject { put("from", ""); put("to", "B") })
                    },
                )
            },
        )

        val changes = Reports.changes(changed)

        assertEquals(2, changes.size)
        val notes = changes.single { it.field == "notes" }
        assertEquals("old", notes.from)
        assertEquals("new", notes.to)
        val camera = changes.single { it.field == "camera" }
        assertEquals(Reports.EMPTY_VALUE, camera.from)
        assertEquals("B", camera.to)
    }

    @Test
    fun `every export's label key is derived, not hand-written`() {
        // A hyphenated kind becomes an underscored key; a mismatch here is a
        // button labelled with its own key on screen.
        assertEquals("export_editor_log", Reports.DATA_EXPORTS.single { it.kind == "editor-log" }.labelKey)
        assertEquals("export_ale", Reports.DATA_EXPORTS.single { it.kind == "ale" }.labelKey)
        assertEquals("report_daily_progress", Reports.reportLabelKey("daily-progress"))
    }

    @Test
    fun `the ALE is the one export the screen pushes forward`() {
        // The cutting room asks for it, so the web makes it the primary.
        val highlighted = Reports.DATA_EXPORTS.filter { it.highlight }

        assertEquals(listOf("ale"), highlighted.map { it.kind })
    }
}
