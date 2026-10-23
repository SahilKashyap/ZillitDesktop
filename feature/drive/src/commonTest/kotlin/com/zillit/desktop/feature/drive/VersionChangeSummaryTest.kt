package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.DriveActivityItem
import com.zillit.desktop.feature.drive.domain.DriveActivityPerson
import com.zillit.desktop.feature.drive.domain.DriveCellChange
import com.zillit.desktop.feature.drive.domain.DriveChangeAuthor
import com.zillit.desktop.feature.drive.domain.DriveChangeGroup
import com.zillit.desktop.feature.drive.domain.DriveChangeKind
import com.zillit.desktop.feature.drive.domain.DriveChangeStatus
import com.zillit.desktop.feature.drive.domain.DriveFileKind
import com.zillit.desktop.feature.drive.domain.DriveVersionActivity
import com.zillit.desktop.feature.drive.domain.DriveVersionChanges
import com.zillit.desktop.feature.drive.domain.changeAuthorName
import com.zillit.desktop.feature.drive.domain.changeAuthors
import com.zillit.desktop.feature.drive.domain.changesByPerson
import com.zillit.desktop.feature.drive.domain.describeActivity
import com.zillit.desktop.feature.drive.domain.DriveFileVersion
import com.zillit.desktop.feature.drive.domain.DriveSaveType
import com.zillit.desktop.feature.drive.domain.saveTypeLabel
import com.zillit.desktop.feature.drive.domain.sessionSummary
import com.zillit.desktop.feature.drive.domain.describeChanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a version's changes are called — the cases from the web's
 * `versionHistoryUtils.test.js` (`describeChanges`, the activity and the
 * per-cell attribution).
 */
class VersionChangeSummaryTest {

    private fun done(cells: Int, sheets: Int, activity: DriveVersionActivity? = null) = DriveVersionChanges(
        status = DriveChangeStatus.Done,
        cells = cells,
        sheets = sheets,
        total = cells,
        activity = activity,
    )

    @Test
    fun `a finished comparison is summarised`() {
        assertEquals("1 cell changed", describeChanges(done(1, 1)))
        assertEquals("1,200 cells changed in 3 sheets", describeChanges(done(1_200, 3)))
        assertEquals("No cell changes", describeChanges(done(0, 0)))
    }

    @Test
    fun `the other states explain themselves, and say nothing when there is nothing to say`() {
        assertEquals(
            "Working out what changed…",
            describeChanges(DriveVersionChanges(status = DriveChangeStatus.Pending)),
        )
        assertTrue(
            describeChanges(DriveVersionChanges(status = DriveChangeStatus.Failed)).startsWith("Couldn't compare"),
        )
        assertEquals(
            "Too large to compare cell by cell",
            describeChanges(DriveVersionChanges(status = DriveChangeStatus.Skipped, reason = "too_large")),
        )
        assertEquals(
            "Saved in a new file format",
            describeChanges(DriveVersionChanges(status = DriveChangeStatus.Skipped, reason = "format_changed")),
        )
        assertEquals(
            "",
            describeChanges(DriveVersionChanges(status = DriveChangeStatus.Skipped, reason = "unsupported_type")),
        )
        // A status this client has not heard of says nothing rather than guessing.
        assertEquals("", describeChanges(DriveVersionChanges(status = DriveChangeStatus.of("brand-new"))))
        assertEquals("", describeChanges(null))
    }

    @Test
    fun `documents and decks talk about paragraphs and slides, not cells`() {
        assertEquals("3 paragraphs changed", describeChanges(done(3, 1), DriveFileKind.Document))
        assertEquals(
            "4 paragraphs changed in 3 slides",
            describeChanges(done(4, 3), DriveFileKind.Presentation),
        )
        assertEquals("No text changes", describeChanges(done(0, 0), DriveFileKind.Document))
        // A document's body is one flowing thing; its "sections" are not counted.
        assertEquals("5 paragraphs changed", describeChanges(done(5, 4), DriveFileKind.Document))
        assertEquals(
            "Too large to compare",
            describeChanges(
                DriveVersionChanges(status = DriveChangeStatus.Skipped, reason = "too_large"),
                DriveFileKind.Document,
            ),
        )
    }

    // -- the activity (what the editors did) ---------------------------------

    private val resize = DriveActivityItem("column_width", 2, "Resized columns F, G")
    private val rows = DriveActivityItem("row_height", 1, "Resized row 3")
    private val edited = DriveActivityItem("values", 4, "Edited 4 cells")
    private val inserted = DriveActivityItem("rows_inserted", 2, "Inserted 2 rows")
    private val solo = DriveVersionActivity(
        items = listOf(resize, rows),
        people = listOf(DriveActivityPerson("u1", "Priya", listOf(resize, rows))),
    )

    @Test
    fun `a resize-only version is not No cell changes`() {
        assertEquals(
            "Resized columns F, G · Resized row 3",
            describeChanges(done(0, 0, solo)),
        )
    }

    @Test
    fun `No cell changes only when the comparison and the activity are both empty`() {
        assertEquals("No cell changes", describeChanges(done(0, 0, DriveVersionActivity())))
        assertEquals("No cell changes", describeChanges(done(0, 0, null)))
    }

    @Test
    fun `the activity follows the cell count without repeating the cell edits`() {
        val activity = DriveVersionActivity(
            items = listOf(edited, resize, inserted),
            people = listOf(DriveActivityPerson("u1", "Priya", listOf(edited, resize, inserted))),
        )

        assertEquals(
            "4 cells changed · Resized columns F, G · Inserted 2 rows",
            describeChanges(done(4, 1, activity)),
        )
    }

    @Test
    fun `the activity shows while the comparison is still running`() {
        assertEquals(
            "Resized columns F, G · Resized row 3",
            describeChanges(DriveVersionChanges(status = DriveChangeStatus.Pending, activity = solo)),
        )
        assertEquals(
            "Working out what changed…",
            describeChanges(DriveVersionChanges(status = DriveChangeStatus.Pending)),
        )
    }

    @Test
    fun `more than one editor is described per person`() {
        val activity = DriveVersionActivity(
            items = listOf(resize, inserted),
            people = listOf(
                DriveActivityPerson("u1", "Priya", listOf(resize)),
                DriveActivityPerson("u2", "", listOf(inserted)),
            ),
        )

        assertEquals(
            "Priya: Resized columns F, G · Riya: Inserted 2 rows",
            describeActivity(activity) { id -> if (id == "u2") "Riya" else "" },
        )
    }

    @Test
    fun `the line stays short`() {
        val many = listOf("a", "b", "c", "d", "e").map { DriveActivityItem("other", 0, it) }

        assertEquals("a · b · c · 2 more", describeActivity(DriveVersionActivity(items = many)))
    }

    // -- who changed each cell ----------------------------------------------

    private val priya = DriveChangeAuthor("u1", "Priya")
    private val riya = DriveChangeAuthor("u2", "Riya")
    private val groups = listOf(
        DriveChangeGroup(
            name = "S1",
            total = 2,
            changes = listOf(
                DriveCellChange(cell = "A1", by = listOf(priya)),
                DriveCellChange(cell = "A2", by = listOf(riya, priya)),
            ),
        ),
        DriveChangeGroup(
            name = "S2",
            total = 2,
            changes = listOf(
                DriveCellChange(cell = "B1", by = listOf(riya)),
                DriveCellChange(cell = "B2"),
            ),
        ),
    )

    @Test
    fun `people are listed by changes made, each change once per person`() {
        assertEquals(
            listOf("Priya" to 2, "Riya" to 2),
            changeAuthors(groups).map { it.name to it.count },
        )
    }

    @Test
    fun `filtering to one person drops the groups left empty`() {
        val mine = changesByPerson(groups, "u1")

        assertEquals(listOf("S1"), mine.map { it.name })
        assertEquals(2, mine.single().total)
        assertEquals(groups, changesByPerson(groups, null))
        assertEquals(groups, changesByPerson(groups, ""))
    }

    @Test
    fun `the production's name wins, falling back to the one the edit carried`() {
        assertEquals("Priya Kapoor", changeAuthorName(priya) { "Priya Kapoor" })
        // "Someone" is the lookup's own fallback, so the edit's name is better.
        assertEquals("Priya", changeAuthorName(priya) { "Someone" })
        assertEquals("Someone", changeAuthorName(DriveChangeAuthor()))
    }

    /** A change the edit log could not attribute is attributed to nobody. */
    @Test
    fun `an unattributed change belongs to no one`() {
        val anonymous = listOf(DriveChangeGroup(name = "S", changes = listOf(DriveCellChange(cell = "A1"))))

        assertEquals(emptyList(), changeAuthors(anonymous))
    }

    /** A person the log named without an id is still one person across changes. */
    @Test
    fun `an author with no id is keyed by name`() {
        val named = DriveChangeAuthor(name = "Priya")
        val rows = listOf(
            DriveChangeGroup(
                name = "S",
                changes = listOf(
                    DriveCellChange(cell = "A1", by = listOf(named)),
                    DriveCellChange(cell = "A2", by = listOf(named)),
                ),
            ),
        )

        assertEquals(listOf("name:Priya" to 2), changeAuthors(rows).map { it.key to it.count })
        assertEquals(2, changesByPerson(rows, "name:Priya").single().changes.size)
    }

    @Test
    fun `a change kind this client has not heard of reads as changed`() {
        assertEquals(DriveChangeKind.Changed, DriveChangeKind.of("something-new"))
        assertEquals(DriveChangeKind.Formula, DriveChangeKind.of("formula"))
    }

    // -- a session's own line (web PR #6530) --------------------------------

    private fun save(
        id: String,
        changes: DriveVersionChanges?,
        saveType: DriveSaveType = DriveSaveType.Manual,
    ) = DriveFileVersion(id = id, changes = changes, saveType = saveType)

    @Test
    fun `a session says what changed only when every save that changed something agrees`() {
        // The newest save's own line was printed as the session's: a session
        // read "2 saves · Made 1 edit" while the other save held eleven.
        val disagreeing = listOf(save("a", done(1, 1)), save("b", done(11, 1)))
        assertEquals("", sessionSummary(disagreeing), "expand the session to read them one by one")

        val agreeing = listOf(save("a", done(4, 1)), save("b", done(4, 1)))
        assertEquals("4 cells changed", sessionSummary(agreeing))
    }

    @Test
    fun `a baseline save holds nothing of its own, so it does not spoil the agreement`() {
        // An upload, a New file and an old record only state what was there.
        val withBaseline = listOf(
            save("new", null, DriveSaveType.Created),
            save("up", done(9, 1), DriveSaveType.Upload),
            save("old", done(9, 1), DriveSaveType.Legacy),
            save("edit", done(3, 1)),
        )
        assertEquals("3 cells changed", sessionSummary(withBaseline))
    }

    @Test
    fun `a save that changed nothing is not a line to agree with`() {
        val nothingAndSomething = listOf(save("a", done(0, 0)), save("b", done(2, 1)))
        assertEquals("2 cells changed", sessionSummary(nothingAndSomething))
        assertEquals("", sessionSummary(emptyList()))
    }

    @Test
    fun `a document's generic edit line is dropped once its paragraphs are counted`() {
        val activity = DriveVersionActivity(
            items = listOf(DriveActivityItem(type = "doc_edits", text = "Made 1 edit")),
        )
        val changes = DriveVersionChanges(
            status = DriveChangeStatus.Done,
            cells = 3,
            sheets = 1,
            total = 3,
            activity = activity,
        )
        assertEquals("3 paragraphs changed", describeChanges(changes, DriveFileKind.Document))
    }

    @Test
    fun `a file made with New says Created`() {
        assertEquals("Created", saveTypeLabel(save("new", null, DriveSaveType.Created)))
    }
}
