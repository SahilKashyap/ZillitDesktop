package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.data.decodeVersionChanges
import com.zillit.desktop.feature.drive.data.decodeVersionHistory
import com.zillit.desktop.feature.drive.domain.DriveChangeKind
import com.zillit.desktop.feature.drive.domain.DriveChangeStatus
import com.zillit.desktop.feature.drive.domain.DriveGroupStatus
import com.zillit.desktop.feature.drive.domain.DriveSaveType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two version-history payloads, pinned against the shapes the web reads.
 *
 * **Not captured responses.** Every other wire test in this module quotes a
 * body seen from the server; these quote the web's own reading of them
 * (`versionHistory` + `driveApi.js` at `d0723d0c4`), because no response from
 * these four routes has been seen. The routes themselves are real — probed
 * unauthenticated, 401 against a 404 control — so what is unverified is the
 * shape, and this test is what a first live reply should be checked against.
 *
 * The trap it exists for: `changes.sheets` is a **count** in the history and
 * an **array** in the changes route. Reading either as the other throws at the
 * top level and empties the panel.
 */
class DriveVersionHistoryWireTest {

    @Test
    @Suppress("LongMethod") // One captured-shape body and the assertions that pin it.
    fun `the history arrives as sessions, with the caller's rights on the file`() {
        val history = decodeVersionHistory(
            """
            {
              "file": { "file_name": "Budget.xlsx" },
              "permissions": { "can_edit": true, "can_download": false },
              "truncated": true,
              "sessions": [
                {
                  "id": "s1",
                  "started_at": 1758800000000,
                  "ended_at": 1758803600000,
                  "editors": ["u-riya", "u-aviral"],
                  "versions": [
                    {
                      "_id": "v3",
                      "version_number": 3,
                      "name": "Final draft",
                      "saved_at": 1758803600000,
                      "saved_by": "u-riya",
                      "editors": ["u-riya", "u-aviral"],
                      "save_type": "exit",
                      "is_current": true,
                      "changes": {
                        "status": "done",
                        "cells": 4,
                        "sheets": 2,
                        "activity": {
                          "ops": 1617,
                          "items": [{ "type": "column_width", "count": 2, "text": "Resized columns F, G" }],
                          "people": [
                            {
                              "user_id": "u-riya",
                              "name": "Riya",
                              "items": [{ "type": "column_width", "count": 2, "text": "Resized columns F, G" }]
                            }
                          ]
                        }
                      }
                    },
                    {
                      "_id": "v2",
                      "version_number": 2,
                      "saved_at": 1758800000000,
                      "saved_by": "u-riya",
                      "save_type": "autosave",
                      "restored_from_number": null,
                      "changes": { "status": "pending" }
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Budget.xlsx", history.fileName)
        assertTrue(history.canEdit)
        assertTrue(!history.canDownload)
        assertTrue(history.truncated)
        assertEquals(1, history.namedCount)

        val session = history.sessions.single()
        assertEquals(listOf("u-riya", "u-aviral"), session.editorIds)
        assertEquals(2, session.size)

        val newest = session.versions.first()
        assertEquals("v3", newest.id)
        assertEquals(DriveSaveType.Exit, newest.saveType)
        assertTrue(newest.isCurrent)
        assertEquals("Final draft", newest.name)
        // The history's `changes` is the summary: counts, never the list.
        assertEquals(DriveChangeStatus.Done, newest.changes?.status)
        assertEquals(4, newest.changes?.cells)
        assertEquals(2, newest.changes?.sheets)
        assertEquals(emptyList(), newest.changes?.groups)
        assertEquals("Resized columns F, G", newest.changes?.activity?.items?.single()?.text)
        assertEquals("Riya", newest.changes?.activity?.people?.single()?.name)

        val older = session.versions.last()
        assertEquals(DriveSaveType.Autosave, older.saveType)
        assertNull(older.restoredFromNumber)
        assertEquals(DriveChangeStatus.Pending, older.changes?.status)
        // No activity at all is different from an empty one: it means the save
        // happened outside a live session, so formatting is unknown.
        assertNull(older.changes?.activity)
    }

    /** A stamp arrives as a number on some rows and a quoted string on others. */
    @Test
    fun `a session with no versions is dropped, and a quoted stamp still reads`() {
        val history = decodeVersionHistory(
            """
            {
              "sessions": [
                { "id": "empty", "versions": [] },
                {
                  "_id": "s2",
                  "started_at": "1758800000000",
                  "ended_at": "1758800000000",
                  "versions": [{ "id": "v1", "version_number": 1, "created_on": "1758800000000" }]
                }
              ]
            }
            """.trimIndent(),
        )

        val session = history.sessions.single()
        assertEquals("s2", session.id)
        assertEquals(1758800000000, session.startedAt)
        assertEquals(1758800000000, session.versions.single().savedAt)
        // No permissions object: nothing is offered rather than everything.
        assertTrue(!history.canEdit && !history.canDownload)
    }

    @Test
    fun `the changes route answers sheets as a list, each change in short keys`() {
        val changes = decodeVersionChanges(
            """
            {
              "version_id": "v2",
              "status": "done",
              "total": 3,
              "truncated": false,
              "sheets": [
                {
                  "name": "Action Vehicle",
                  "status": "changed",
                  "total": 2,
                  "changes": [
                    { "c": "P2", "k": "added", "b": "", "a": "vidya", "by": [{ "user_id": "u1", "name": "Priya" }] },
                    {
                      "c": "AD3",
                      "k": "changed",
                      "b": "1",
                      "a": "2",
                      "by": [{ "user_id": "u2", "name": "Riya" }, { "user_id": "u1", "name": "Priya" }]
                    }
                  ]
                },
                { "name": "Sheet2", "status": "added", "total": 1, "changes": [{ "c": "B1", "k": "formula", "a": "=SUM(A1:A9)" }] }
              ],
              "activity": null
            }
            """.trimIndent(),
        )

        assertEquals(DriveChangeStatus.Done, changes.status)
        assertEquals(3, changes.total)
        assertEquals(2, changes.groups.size)
        assertNull(changes.activity)

        val first = changes.groups.first()
        assertEquals("Action Vehicle", first.name)
        assertEquals(DriveGroupStatus.Changed, first.status)
        assertEquals(DriveChangeKind.Added, first.changes.first().kind)
        assertEquals("vidya", first.changes.first().after)
        // Last writer first, which is what the row shows with "+1" behind it.
        assertEquals(listOf("Riya", "Priya"), first.changes.last().by.map { it.name })

        val second = changes.groups.last()
        assertEquals(DriveGroupStatus.Added, second.status)
        assertEquals(DriveChangeKind.Formula, second.changes.single().kind)
        assertEquals("", second.changes.single().before)
        assertEquals(emptyList(), second.changes.single().by)
    }

    /** The first version of a file: nothing to compare it with. */
    @Test
    fun `a skipped or first-version comparison keeps its reason`() {
        val none = decodeVersionChanges("""{ "status": "none", "total": 0, "sheets": [] }""")
        assertEquals(DriveChangeStatus.None, none.status)
        assertEquals(0, none.total)

        val skipped = decodeVersionChanges(
            """{ "status": "skipped", "reason": "too_large", "total": 0, "sheets": [] }""",
        )
        assertEquals(DriveChangeStatus.Skipped, skipped.status)
        assertEquals("too_large", skipped.reason)
    }

    /** A populated `user_id` — the share routes do it, so the author may too. */
    @Test
    fun `an author's id reads whether it is a string or a populated document`() {
        val changes = decodeVersionChanges(
            """
            {
              "status": "done",
              "total": 1,
              "sheets": [
                {
                  "name": "Main",
                  "changes": [
                    { "c": "A1", "k": "added", "a": "x", "by": [{ "user_id": { "_id": "u9", "full_name": "Ada" }, "name": "Ada" }] }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("u9", changes.groups.single().changes.single().by.single().userId)
        // A group without its own total counts the changes it carries.
        assertEquals(1, changes.groups.single().total)
    }
}
