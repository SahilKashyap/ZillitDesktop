package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.domain.DriveFileKind
import com.zillit.desktop.feature.drive.domain.DriveFileVersion
import com.zillit.desktop.feature.drive.domain.DriveSaveType
import com.zillit.desktop.feature.drive.domain.DriveVersionSession
import com.zillit.desktop.feature.drive.domain.dayLabel
import com.zillit.desktop.feature.drive.domain.fileKindOf
import com.zillit.desktop.feature.drive.domain.findCurrentVersion
import com.zillit.desktop.feature.drive.domain.findVersion
import com.zillit.desktop.feature.drive.domain.groupHistory
import com.zillit.desktop.feature.drive.domain.joinNames
import com.zillit.desktop.feature.drive.domain.saveTypeLabel
import com.zillit.desktop.feature.drive.domain.sessionTimeRange
import com.zillit.desktop.feature.drive.domain.versionDateTime
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The version history panel's wording — the cases from the web's
 * `versionHistoryUtils.test.js`, in the desktop's own date format.
 *
 * Stamps are built from local wall-clock times and compared against a local
 * "now", so the day labels mean the same thing in every CI timezone.
 */
class VersionHistoryTextTest {

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime(2026, 9, day, hour, minute)
            .toInstant(TimeZone.currentSystemDefault())
            .toEpochMilliseconds()

    /** 25 Sep 2026, 6 PM — a Friday. */
    private val now = at(25, 18)

    private fun version(
        id: String,
        savedAt: Long,
        name: String = "",
        type: DriveSaveType = DriveSaveType.Manual,
        from: Int? = null,
    ) = DriveFileVersion(id = id, savedAt = savedAt, name = name, saveType = type, restoredFromNumber = from)

    @Test
    fun `each kind of save is named, and a restore says where from`() {
        assertEquals("Autosaved", saveTypeLabel(version("v", 0, type = DriveSaveType.Autosave)))
        assertEquals("Saved on close", saveTypeLabel(version("v", 0, type = DriveSaveType.Exit)))
        assertEquals("Uploaded", saveTypeLabel(version("v", 0, type = DriveSaveType.Upload)))
        assertEquals("Earlier version", saveTypeLabel(version("v", 0, type = DriveSaveType.Legacy)))
        // An unknown type reads as "Saved", never as a blank line.
        assertEquals("Saved", saveTypeLabel(version("v", 0, type = DriveSaveType.of("something-new"))))
        assertEquals(
            "Restored from version 4",
            saveTypeLabel(version("v", 0, type = DriveSaveType.Restore, from = 4)),
        )
        assertEquals("Restored", saveTypeLabel(version("v", 0, type = DriveSaveType.Restore)))
    }

    @Test
    fun `names join the way people write them`() {
        assertEquals("Priya", joinNames(listOf("Priya")))
        assertEquals("Priya and Sam", joinNames(listOf("Priya", "Sam")))
        assertEquals("Priya, Sam and 1 other", joinNames(listOf("Priya", "Sam", "Ana")))
        assertEquals("Priya, Sam and 2 others", joinNames(listOf("Priya", "Sam", "Ana", "Lee")))
        assertEquals("", joinNames(listOf("", " ")))
    }

    @Test
    fun `today, yesterday and older days are labelled`() {
        assertEquals("Today", dayLabel(at(25, 9), now))
        // Late last night is "Yesterday" by the calendar, not "18 hours ago".
        assertEquals("Yesterday", dayLabel(at(24, 23), now))
        assertEquals("Tuesday, 22 September", dayLabel(at(22, 9), now))
        assertEquals("", dayLabel(0, now))
    }

    @Test
    fun `a stamp reads as the day and the time`() {
        assertEquals("Today, 9:05 AM", versionDateTime(at(25, 9, 5), now))
        assertEquals("22 Sep 2026, 9:00 AM", versionDateTime(at(22, 9), now))
        assertEquals("", versionDateTime(0, now))
    }

    @Test
    fun `a session reads as a time range, sharing AM-PM where it can`() {
        assertEquals(
            "2:03 – 2:41 PM",
            sessionTimeRange(DriveVersionSession("s", startedAt = at(25, 14, 3), endedAt = at(25, 14, 41))),
        )
        assertEquals(
            "11:50 AM – 12:10 PM",
            sessionTimeRange(DriveVersionSession("s", startedAt = at(25, 11, 50), endedAt = at(25, 12, 10))),
        )
        assertEquals(
            "9:00 AM",
            sessionTimeRange(DriveVersionSession("s", startedAt = at(25, 9), endedAt = at(25, 9))),
        )
    }

    private val sessions = listOf(
        DriveVersionSession(
            id = "s3",
            startedAt = at(25, 14),
            endedAt = at(25, 15),
            editorIds = listOf("a"),
            versions = listOf(
                version("v5", at(25, 15), name = "Final"),
                version("v4", at(25, 14)),
            ),
        ),
        DriveVersionSession(
            id = "s2",
            startedAt = at(25, 9),
            endedAt = at(25, 9),
            editorIds = listOf("b"),
            versions = listOf(version("v3", at(25, 9))),
        ),
        DriveVersionSession(
            id = "s1",
            startedAt = at(22, 9),
            endedAt = at(22, 9),
            editorIds = listOf("a"),
            versions = listOf(version("v2", at(22, 9), name = "Draft")),
        ),
    )

    @Test
    fun `sessions sit under day headings, newest first`() {
        val days = groupHistory(sessions, now = now)

        assertEquals(listOf("Today", "Tuesday, 22 September"), days.map { it.label })
        assertEquals(listOf("s3", "s2"), days.first().sessions.map { it.id })
    }

    @Test
    fun `the named filter shows each named version on its own`() {
        val days = groupHistory(sessions, namedOnly = true, now = now)

        assertEquals(listOf("v5", "v2"), days.flatMap { day -> day.sessions.map { it.versions.first().id } })
    }

    @Test
    fun `the current version and any version by id are found`() {
        assertEquals("v5", findCurrentVersion(sessions)?.id)
        assertEquals("v3", findVersion(sessions, "v3")?.id)
        assertNull(findVersion(sessions, "missing"))
        assertNull(findVersion(sessions, null))
        assertNull(findCurrentVersion(emptyList()))
    }

    @Test
    fun `every type the editor opens is known`() {
        assertEquals(DriveFileKind.Spreadsheet, fileKindOf("Budget.XLSX"))
        assertEquals(DriveFileKind.Spreadsheet, fileKindOf("crew.csv"))
        assertEquals(DriveFileKind.Document, fileKindOf("file-sample_100kB.doc"))
        assertEquals(DriveFileKind.Document, fileKindOf("notes.rtf"))
        assertEquals(DriveFileKind.Presentation, fileKindOf("Pitch.pptx"))
        assertEquals(DriveFileKind.Other, fileKindOf("scan.pdf"))
        assertEquals(DriveFileKind.Other, fileKindOf("README"))
        assertEquals(DriveFileKind.Other, fileKindOf(""))
    }

    /** Co-editing: a save lists everyone editing, and falls back to who wrote it. */
    @Test
    fun `a save names its editors, or the writer when it has none`() {
        val shared = DriveFileVersion(id = "v", editorIds = listOf("a", "b"), savedById = "a")
        val alone = DriveFileVersion(id = "v", savedById = "a")

        assertEquals(listOf("a", "b"), shared.people)
        assertEquals(listOf("a"), alone.people)
        assertEquals(emptyList(), DriveFileVersion(id = "v").people)
    }
}
