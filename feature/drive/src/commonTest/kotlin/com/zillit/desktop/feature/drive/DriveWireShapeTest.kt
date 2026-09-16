package com.zillit.desktop.feature.drive

import com.zillit.desktop.feature.drive.data.decodeActivityPage
import com.zillit.desktop.feature.drive.data.decodeFavouriteIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two drive routes whose envelope shapes are **inverted**, pinned against
 * captured dev responses.
 *
 * Both were wrong on the first pass and neither was caught by anything but live
 * traffic (2026-08-11): `/favorites/ids` answers a bare array, `/drive/activity`
 * answers `{ items, total }`. Reading either as the other throws
 * `JsonDecodingException` at the top level — which surfaces as "the server sent
 * something unexpected" on a page that is otherwise fine.
 *
 * The bodies below are the shapes the web reads (`DriveManagement.jsx` line
 * ~1413 for favourites, `ActivityLogDrawer.jsx` line ~134 for activity), which
 * is the same contract this client is on.
 */
class DriveWireShapeTest {

    @Test
    fun `favourite ids arrive as a bare array, not a wrapper object`() {
        val ids = decodeFavouriteIds("""["64f1a2b3c4d5e6f708192a3b","64f1a2b3c4d5e6f708192a3c"]""")

        assertEquals(
            setOf("64f1a2b3c4d5e6f708192a3b", "64f1a2b3c4d5e6f708192a3c"),
            ids,
        )
    }

    @Test
    fun `no favourites is an empty array, not an absent body`() {
        assertTrue(decodeFavouriteIds("[]").isEmpty())
    }

    @Test
    fun `activity arrives wrapped in items with a total`() {
        val page = decodeActivityPage(
            """
            {
              "items": [
                {
                  "_id": "a1",
                  "action": "file.uploaded",
                  "item_name": "Camera Report Day 12.pdf",
                  "user_id": "u-1",
                  "created_on": 1754000000000
                }
              ],
              "total": 1
            }
            """.trimIndent(),
        )

        assertEquals(1, page.size)
        assertEquals("Camera Report Day 12.pdf", page.single().itemName)
        // `file.uploaded` → "File uploaded": the server sends dotted action keys
        // and the label dictionaries do not carry this service's set.
        assertEquals("File uploaded", page.single().label)
        // `created_on`, and a bare JSON number rather than a quoted string —
        // both of which the first pass got wrong.
        assertEquals(1_754_000_000_000, page.single().at)
        assertEquals("u-1", page.single().userId)
        // No name on the wire; the repository fills it from the crew list.
        assertEquals("Unknown", page.single().displayName)
    }

    @Test
    fun `an activity page with no items decodes to nothing rather than failing`() {
        assertTrue(decodeActivityPage("""{"items":[],"total":0}""").isEmpty())
        // The `total` key is absent on some responses; its absence must not take
        // the whole page down.
        assertTrue(decodeActivityPage("""{"items":[]}""").isEmpty())
    }

    @Test
    fun `an activity row's details is an object, not a string`() {
        // Declaring `details` as String threw `Expected JsonPrimitive, but had
        // JsonObject` and took the whole page down with it — one decoration
        // field costing every row.
        val page = decodeActivityPage(
            """
            {"items":[{
              "_id": "a1",
              "action": "file.moved",
              "user_id": "u-1",
              "details": {"from": "/Camera", "to": "/Camera/Day 12"}
            }]}
            """.trimIndent(),
        )

        assertEquals("from: /Camera · to: /Camera/Day 12", page.single().detail)
    }

    @Test
    fun `an unreadable details shape costs one cell, not the row`() {
        val page = decodeActivityPage(
            """{"items":[{"_id":"a1","action":"file.tagged","details":{"tags":["a","b"]}}]}""",
        )

        // The nested array has no readable primitive; the row still arrives.
        assertEquals(1, page.size)
        assertEquals("", page.single().detail)
    }

    @Test
    fun `a plain string details still reads through`() {
        val page = decodeActivityPage(
            """{"items":[{"_id":"a1","action":"file.renamed","details":"Renamed to Day 13"}]}""",
        )

        assertEquals("Renamed to Day 13", page.single().detail)
    }

    @Test
    fun `an activity row with no id is dropped, not rendered blank`() {
        val page = decodeActivityPage(
            """{"items":[{"action":"file.deleted"},{"_id":"a2","action":"file.moved"}]}""",
        )

        assertEquals(listOf("a2"), page.map { it.id })
    }
}
