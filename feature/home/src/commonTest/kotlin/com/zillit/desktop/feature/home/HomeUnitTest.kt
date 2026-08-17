package com.zillit.desktop.feature.home

import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.Notice
import com.zillit.desktop.feature.home.domain.forDisplay
import com.zillit.desktop.feature.home.domain.visibleTabs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which Home tab renders what, and in what order the board reads.
 *
 * The web decides both inline inside a 566-line component, where neither is
 * reachable from a test.
 */
class HomeUnitTest {

    private fun unit(
        identifier: String,
        name: String = "general_label",
        view: Boolean = true,
        post: Boolean = false,
        enabled: Boolean = true,
    ) = HomeUnit("u-$identifier", identifier, name, canView = view, canPost = post, enabled = enabled)

    @Test
    fun `the calendar unit is matched on its name`() {
        // Its identifier is not stable across productions; `unit_name` is.
        assertEquals(HomeUnitKind.Calendar, unit("anything", name = "calendar_label").kind)
    }

    @Test
    fun `call sheet is matched by substring, not equality`() {
        // Productions carry `call_sheet_tool`, `call_sheet_label` and per-unit
        // variants — all of them are call sheets.
        listOf("call_sheet", "call_sheet_tool", "main_unit_call_sheet_label").forEach {
            assertEquals(HomeUnitKind.CallSheet, unit(it).kind, "not recognised: $it")
        }
    }

    @Test
    fun `anything else is a notice board`() {
        assertEquals(HomeUnitKind.Notices, unit("general_tool").kind)
        assertEquals(HomeUnitKind.Notices, unit("").kind)
    }

    @Test
    fun `the calendar wins over a call-sheet-looking identifier`() {
        // A calendar unit whose identifier mentions call sheets must still be a
        // calendar; the branch order is the contract.
        assertEquals(
            HomeUnitKind.Calendar,
            unit("call_sheet_calendar", name = "calendar_label").kind,
        )
    }

    @Test
    fun `unviewable and disabled units are dropped, not disabled`() {
        // An unopenable tab invites the question "why does this do nothing".
        val tabs = listOf(
            unit("a"),
            unit("b", view = false),
            unit("c", enabled = false),
        ).visibleTabs()

        assertEquals(listOf("a"), tabs.map { it.identifier })
    }

    @Test
    fun `tab labels humanise the translation key`() {
        assertEquals("General", unit("x", name = "general_label").label)
        assertEquals("Call Sheet", unit("x", name = "call_sheet_label").label)
    }

    @Test
    fun `posting rights are separate from viewing`() {
        val readOnly = unit("a", view = true, post = false)

        assertTrue(readOnly.canView)
        assertFalse(readOnly.canPost, "the composer must not appear on a read-only board")
    }

    // -- feed order -------------------------------------------------------

    private fun notice(id: String, at: Long, pinned: Boolean = false) =
        Notice(id = id, body = "b", authorName = "A", createdAtMillis = at, isPinned = pinned)

    @Test
    fun `the board reads oldest to newest, like a conversation`() {
        val feed = listOf(notice("c", 300), notice("a", 100), notice("b", 200)).forDisplay()

        assertEquals(listOf("a", "b", "c"), feed.map { it.id })
    }

    @Test
    fun `pinned notices sit at the top regardless of age`() {
        val feed = listOf(notice("new", 300), notice("old", 100), notice("pin", 50, pinned = true))
            .forDisplay()

        assertEquals("pin", feed.first().id)
        assertEquals(listOf("old", "new"), feed.drop(1).map { it.id })
    }

    @Test
    fun `a notice never prints its body`() {
        // Boards carry production-confidential text and this reaches logs.
        val text = notice("x", 1).copy(body = "cast list leaked").toString()

        assertFalse(text.contains("cast list leaked"))
    }
}
