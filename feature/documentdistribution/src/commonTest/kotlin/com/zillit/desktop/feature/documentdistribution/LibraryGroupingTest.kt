package com.zillit.desktop.feature.documentdistribution

import com.zillit.desktop.feature.documentdistribution.domain.LibraryDocument
import com.zillit.desktop.feature.documentdistribution.domain.LibraryGrouping
import com.zillit.desktop.feature.documentdistribution.domain.formatBytes
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The date bucketing behind the library listing.
 *
 * Pinned because it is the one part of this module whose output a reader can
 * be wrong about without noticing: a heading that says "Today" on yesterday's
 * call sheet is read as fact.
 */
class LibraryGroupingTest {

    private val today = LocalDate(2026, 8, 11)

    @Test
    fun `today and yesterday are named, older days are not`() {
        assertEquals("Today · Aug 11, 2026", LibraryGrouping.heading("2026-08-11", today))
        assertEquals("Yesterday · Aug 10, 2026", LibraryGrouping.heading("2026-08-10", today))
        assertEquals("Sunday, Aug 9, 2026", LibraryGrouping.heading("2026-08-09", today))
    }

    @Test
    fun `a blank or unparseable date is undated rather than an error`() {
        // The server files documents with no production date under an empty
        // key, and that bucket is a normal part of every library.
        assertEquals(LibraryGrouping.UNDATED, LibraryGrouping.heading("", today))
        assertEquals(LibraryGrouping.UNDATED, LibraryGrouping.heading("not-a-date", today))
    }

    @Test
    fun `buckets run newest first with undated last`() {
        val groups = LibraryGrouping.group(
            documents = listOf(
                document("a", ""),
                document("b", "2026-08-09"),
                document("c", "2026-08-11"),
                document("d", "2026-08-10"),
            ),
            counts = emptyMap(),
            today = today,
        )

        assertEquals(
            listOf("2026-08-11", "2026-08-10", "2026-08-09", ""),
            groups.map { it.key },
        )
    }

    @Test
    fun `ascending still puts undated last`() {
        val groups = LibraryGrouping.group(
            documents = listOf(document("a", ""), document("b", "2026-08-09"), document("c", "2026-08-11")),
            counts = emptyMap(),
            today = today,
            ascending = true,
        )

        // The undated bucket is residue whichever way the dates run — at the
        // top of a listing it hides the day the user came for.
        assertEquals(listOf("2026-08-09", "2026-08-11", ""), groups.map { it.key })
    }

    @Test
    fun `a bucket reports the server's total, not the loaded count`() {
        val groups = LibraryGrouping.group(
            documents = listOf(document("a", "2026-08-11"), document("b", "2026-08-11")),
            counts = mapOf("2026-08-11" to 80),
            today = today,
        )

        // Two rows loaded of eighty that exist. Counting the window would tell
        // the user this day holds two documents.
        assertEquals(2, groups.single().documents.size)
        assertEquals(80, groups.single().total)
    }

    @Test
    fun `a bucket the server did not count falls back to what is loaded`() {
        val groups = LibraryGrouping.group(
            documents = listOf(document("a", "2026-08-11")),
            counts = emptyMap(),
            today = today,
        )

        assertEquals(1, groups.single().total)
    }

    @Test
    fun `sizes read two decimals below ten and none above`() {
        assertEquals("—", formatBytes(0))
        assertEquals("840 B", formatBytes(840))
        assertEquals("9.00 KB", formatBytes(1024 * 9))
        assertEquals("1.00 KB", formatBytes(1024))
        assertEquals("1.00 MB", formatBytes(1024L * 1024))
        assertTrue(formatBytes(1024L * 1024 * 500).endsWith(" MB"))
    }

    private fun document(id: String, date: String) =
        LibraryDocument(id = id, name = "$id.pdf", documentDate = date)
}
