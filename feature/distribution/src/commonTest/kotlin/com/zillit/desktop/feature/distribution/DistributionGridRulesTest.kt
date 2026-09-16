package com.zillit.desktop.feature.distribution

import com.zillit.desktop.feature.distribution.domain.DistributionPerson
import com.zillit.desktop.feature.distribution.domain.DistributionSection
import com.zillit.desktop.feature.distribution.domain.DistributionUnit
import com.zillit.desktop.feature.distribution.domain.DistributionUser
import com.zillit.desktop.feature.distribution.domain.columns
import com.zillit.desktop.feature.distribution.domain.displayName
import com.zillit.desktop.feature.distribution.domain.paged
import com.zillit.desktop.feature.distribution.domain.subtitle
import com.zillit.desktop.feature.distribution.domain.visibleRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The web grid's rules (`DistributionAcessgrid.jsx`), held still. */
class DistributionGridRulesTest {

    private val bulletin = DistributionUnit("h1", "bulletin_label", toEnabled = true, isHome = true)
    private val alerts = DistributionUnit("h2", "alerts_label", isHome = true)
    private val location = DistributionUnit("t1", "location_tool_label", isTool = true)

    private val gaffer = DistributionUser("u1", "Gaffer", status = "accepted", units = listOf(bulletin, location))
    private val pending = DistributionUser("u2", "Newcomer", status = "pending", units = listOf(alerts))
    private val vendor = DistributionUser("u3", "Grip Hire", userType = "external", units = listOf(alerts, bulletin))
    private val left = DistributionUser("u4", "Left crew", status = "removed")

    private val people = mapOf(
        "u1" to DistributionPerson("u1", fullName = "Aisha Khan", designation = "gaffer_label", status = "accepted"),
        "u3" to DistributionPerson("u3", fullName = "Grip Hire Ltd", email = "hire@grip.example", isExternal = true),
        // The directory says accepted even though the row says nothing.
        "u4" to DistributionPerson("u4", fullName = "Late Joiner", status = "accepted"),
    )

    private val translate: (String) -> String = { key ->
        mapOf(
            "bulletin_label" to "Bulletin",
            "alerts_label" to "Alerts",
            "location_tool_label" to "Location",
            "gaffer_label" to "Gaffer",
        )[key] ?: key
    }

    @Test
    fun `rows are accepted crew or externals, in the server's order`() {
        val rows = listOf(gaffer, pending, vendor, left).visibleRows(people, query = "", externalOnly = false)
        // `left` stays: the directory calls them accepted, as the web's `user?.status` check does.
        assertEquals(listOf("u1", "u3", "u4"), rows.map { it.userId })
    }

    @Test
    fun `externals only keeps the outsiders`() {
        val rows = listOf(gaffer, pending, vendor).visibleRows(people, query = "", externalOnly = true)
        assertEquals(listOf("u3"), rows.map { it.userId })
    }

    @Test
    fun `search matches the wire name, the literal outsider, and the directory's full name`() {
        val all = listOf(gaffer, vendor.copy(outsider = "outsider"))
        assertEquals(listOf("u1"), all.visibleRows(people, "gaff", false).map { it.userId })
        assertEquals(listOf("u3"), all.visibleRows(people, "OUTSIDER", false).map { it.userId })
        assertEquals(listOf("u1"), all.visibleRows(people, "aisha", false).map { it.userId })
        assertTrue(all.visibleRows(people, "nobody", false).isEmpty())
    }

    @Test
    fun `columns are the section's units, first appearance names them, sorted by translation`() {
        val home = listOf(gaffer, vendor).columns(DistributionSection.Home, translate)
        assertEquals(listOf("Alerts", "Bulletin"), home.map { translate(it.unitName) })
        val tools = listOf(gaffer, vendor).columns(DistributionSection.Tools, translate)
        assertEquals(listOf("t1"), tools.map { it.unitId })
    }

    @Test
    fun `the name prefers the directory, the subtitle the designation then the email`() {
        assertEquals("Aisha Khan", gaffer.displayName(people["u1"]))
        assertEquals("Newcomer", pending.displayName(null))
        assertEquals("Gaffer", gaffer.subtitle(people["u1"], translate, "No details"))
        assertEquals("hire@grip.example", vendor.subtitle(people["u3"], translate, "No details"))
        assertEquals("No details", pending.subtitle(null, translate, "No details"))
    }

    @Test
    fun `a cell is found in its own section only`() {
        assertEquals(true, gaffer.cell("h1", DistributionSection.Home)?.toEnabled)
        assertEquals(null, gaffer.cell("h1", DistributionSection.Tools))
        assertFalse(gaffer.cell("t1", DistributionSection.Tools)?.toEnabled ?: true)
    }

    @Test
    fun `pages cut at the size and a page past the end lands on the last`() {
        val rows = (1..23).toList()
        val first = rows.paged(page = 1, pageSize = 10)
        assertEquals(1..10, first.firstIndex..first.lastIndex)
        assertEquals(3, first.pageCount)
        assertTrue(first.canGoForward)
        assertFalse(first.canGoBack)

        val last = rows.paged(page = 9, pageSize = 10)
        assertEquals(3, last.page)
        assertEquals(listOf(21, 22, 23), last.rows)
        assertEquals(23, last.total)

        val empty = emptyList<Int>().paged(1, 10)
        assertEquals(0, empty.firstIndex)
        assertEquals(1, empty.pageCount)
    }
}
