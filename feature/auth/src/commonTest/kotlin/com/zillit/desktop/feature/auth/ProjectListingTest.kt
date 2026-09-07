package com.zillit.desktop.feature.auth

import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.domain.ProjectFilter
import com.zillit.desktop.feature.auth.domain.filterProjects
import com.zillit.desktop.feature.auth.domain.highlightRanges
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Searching, filtering and ordering the production list.
 *
 * Pure functions, so this is the cheap place to pin behaviour the screen relies
 * on. The web does the same work inline in an 809-line component where none of
 * it is reachable from a test.
 */
class ProjectListingTest {

    private fun project(
        name: String,
        code: String = "CODE",
        favourite: Boolean = false,
        pending: Boolean = false,
        personal: Boolean = false,
        parent: String? = null,
    ) = Project(
        id = name.lowercase().replace(" ", "-"),
        name = name,
        code = code,
        type = if (personal) "personal" else "entertainment",
        region = null,
        isFavourite = favourite,
        parentName = parent,
        isPending = pending,
    )

    private val all = listOf(
        project("Blade Runner 2049"),
        project("Arrival", favourite = true),
        project("Dune", pending = true),
        project("Family Chat", personal = true),
        project("Sicario", code = "SIC-77", parent = "Villeneuve Slate"),
    )

    @Test
    fun `an empty query returns everything the filter allows`() {
        assertEquals(all.size, all.filterProjects("", ProjectFilter.All).size)
    }

    @Test
    fun `search matches the name`() {
        val result = all.filterProjects("dune", ProjectFilter.All)

        assertEquals(listOf("Dune"), result.map { it.name })
    }

    @Test
    fun `search matches the project code`() {
        // A coordinator who has only the code should not need the title.
        val result = all.filterProjects("SIC-77", ProjectFilter.All)

        assertEquals(listOf("Sicario"), result.map { it.name })
    }

    @Test
    fun `search matches the parent production`() {
        val result = all.filterProjects("Villeneuve", ProjectFilter.All)

        assertEquals(listOf("Sicario"), result.map { it.name })
    }

    @Test
    fun `search ignores case and surrounding space`() {
        assertEquals(1, all.filterProjects("  ArRiVaL  ", ProjectFilter.All).size)
    }

    @Test
    fun `the personal filter splits on project_type_id`() {
        val personal = all.filterProjects("", ProjectFilter.Personal)
        val entertainment = all.filterProjects("", ProjectFilter.Entertainment)

        assertEquals(listOf("Family Chat"), personal.map { it.name })
        assertTrue(entertainment.none { it.name == "Family Chat" })
        assertEquals(all.size, personal.size + entertainment.size, "every project lands in exactly one")
    }

    @Test
    fun `the favourites filter shows only starred productions`() {
        val result = all.filterProjects("", ProjectFilter.Favourites)

        assertEquals(listOf("Arrival"), result.map { it.name })
    }

    @Test
    fun `favourites sort first`() {
        val result = all.filterProjects("", ProjectFilter.All)

        assertEquals("Arrival", result.first().name)
    }

    @Test
    fun `pending productions sink to the bottom`() {
        // They cannot be opened. Interleaved, the one production a user can
        // actually enter may sit below three they cannot.
        val result = all.filterProjects("", ProjectFilter.All)

        assertEquals("Dune", result.last().name)
    }

    @Test
    fun `the rest sort alphabetically, ignoring case`() {
        val result = listOf(project("zulu"), project("Alpha"), project("beta"))
            .filterProjects("", ProjectFilter.All)

        assertEquals(listOf("Alpha", "beta", "zulu"), result.map { it.name })
    }

    @Test
    fun `filter and search compose`() {
        val result = all.filterProjects("a", ProjectFilter.Favourites)

        assertEquals(listOf("Arrival"), result.map { it.name })
    }

    @Test
    fun `a query that matches nothing returns empty rather than everything`() {
        assertTrue(all.filterProjects("nonexistent", ProjectFilter.All).isEmpty())
    }

    // -- highlighting -----------------------------------------------------

    @Test
    fun `highlight finds every occurrence`() {
        //          a n _ b a n a n a
        // index    0 1 2 3 4 5 6 7 8
        assertEquals(listOf(0..1, 4..5, 6..7), highlightRanges("an banana", "an"))
    }

    @Test
    fun `highlight is case-insensitive but keeps the original offsets`() {
        val ranges = highlightRanges("Blade Runner", "RUNNER")

        assertEquals(listOf(6..11), ranges)
    }

    @Test
    fun `an empty query highlights nothing`() {
        assertTrue(highlightRanges("Dune", "").isEmpty())
        assertTrue(highlightRanges("Dune", "   ").isEmpty())
    }

    @Test
    fun `overlapping matches cannot be produced`() {
        // "aa" in "aaaa" must yield 0..1 and 2..3, never 1..2 — a range that
        // starts inside the previous one would crash AnnotatedString.
        val ranges = highlightRanges("aaaa", "aa")

        assertEquals(listOf(0..1, 2..3), ranges)
        ranges.zipWithNext().forEach { (a, b) ->
            assertTrue(b.first > a.last, "ranges overlap: $a then $b")
        }
    }
}
