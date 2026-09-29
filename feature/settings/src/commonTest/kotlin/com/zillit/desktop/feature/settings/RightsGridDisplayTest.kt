package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.admin.domain.AccessType
import com.zillit.desktop.feature.settings.admin.domain.RightsSection
import com.zillit.desktop.feature.settings.admin.domain.ToolRights
import com.zillit.desktop.feature.settings.admin.domain.isEditable
import com.zillit.desktop.feature.settings.admin.domain.shownAs
import com.zillit.desktop.feature.settings.admin.ui.RightsToggle
import com.zillit.desktop.feature.settings.admin.ui.withDealMemoDownload
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the three boxes on a rights row draw as.
 *
 * Neither answer is simply the row's own flag: a department budget's rights are
 * held through the main budget, the Account Hub column is nobody's to set, and
 * an administrator's boxes are read-only bar one. Each of these was a box that
 * lied on screen — disabled and empty where the right was in fact granted, or
 * offering a switch the server would refuse.
 */
class RightsGridDisplayTest {

    private fun tool(
        name: String,
        view: Boolean = false,
        post: Boolean = false,
        download: Boolean = false,
        viewLocked: Boolean = false,
        postLocked: Boolean = false,
        downloadLocked: Boolean = false,
    ) = ToolRights(
        toolIdentifier = name,
        toolName = name,
        unitId = "unit-1",
        section = RightsSection.Tools,
        canView = view,
        canPost = post,
        canDownload = download,
        viewLocked = viewLocked,
        postLocked = postLocked,
        downloadLocked = downloadLocked,
    )

    // -- what the box shows ------------------------------------------------

    @Test
    fun `an ordinary tool shows its own rights`() {
        val budget = tool("budget_tool", view = true)
        assertTrue(budget.shownAs(AccessType.View, listOf(budget)))
        assertFalse(budget.shownAs(AccessType.Post, listOf(budget)))
    }

    /**
     * The row's own flag can say false while the right is held: the cascade may
     * not have propagated, or the server may have refused the direct write
     * because it is the main budget's to give. Drawing the raw row leaves the
     * box disabled *and* empty, which reads as "no rights".
     */
    @Test
    fun `a department budget shows the main budget's viewing`() {
        val main = tool("main_budget_label", view = true)
        val department = tool("department_budget_label")

        assertTrue(department.shownAs(AccessType.View, listOf(main, department)))
    }

    /** Posting through the main budget means full access to it, not view alone. */
    @Test
    fun `a department budget shows posting only for full main budget access`() {
        val viewOnly = tool("main_budget_label", view = true)
        val full = tool("main_budget_label", view = true, post = true)
        val department = tool("department_budget_label")

        assertFalse(department.shownAs(AccessType.Post, listOf(viewOnly, department)))
        assertTrue(department.shownAs(AccessType.Post, listOf(full, department)))
    }

    @Test
    fun `a department budget shows the main budget's downloading`() {
        val main = tool("main_budget_label", download = true)
        val department = tool("department_budget_label")

        assertTrue(department.shownAs(AccessType.Download, listOf(main, department)))
    }

    @Test
    fun `a department budget keeps its own rights when the main budget has none`() {
        val main = tool("main_budget_label")
        val department = tool("department_budget_label", view = true)

        assertTrue(department.shownAs(AccessType.View, listOf(main, department)))
        assertFalse(department.shownAs(AccessType.Post, listOf(main, department)))
    }

    /** No main budget row at all is not evidence that the right is held. */
    @Test
    fun `a department budget alone shows only its own rights`() {
        val department = tool("department_budget_label")
        assertFalse(department.shownAs(AccessType.View, listOf(department)))
    }

    // -- whether the box moves ---------------------------------------------

    @Test
    fun `an unlocked right is editable`() {
        val budget = tool("budget_tool")
        AccessType.entries.forEach { assertTrue(budget.isEditable(it, isAdmin = false), it.name) }
    }

    @Test
    fun `the server's lock is honoured`() {
        val locked = tool("budget_tool", viewLocked = true)
        assertFalse(locked.isEditable(AccessType.View, isAdmin = false))
        assertTrue(locked.isEditable(AccessType.Post, isAdmin = false))
    }

    /**
     * `ZL-20803`. Account Hub access comes from the person's department and
     * designation, so a switch here could only ever disagree with the tool they
     * actually see.
     */
    @Test
    fun `the account hub column is nobody's to set`() {
        AccessType.entries.forEach {
            assertFalse(tool("account_hub").isEditable(it, isAdmin = false), it.name)
        }
    }

    /** The columns come from the server, so the suffix is stripped rather than guessed. */
    @Test
    fun `the account hub is recognised however it is spelled`() {
        listOf("account_hub", "account_hub_label", "account_hub_tool").forEach { name ->
            assertFalse(tool(name).isEditable(AccessType.View, isAdmin = false), name)
        }
    }

    @Test
    fun `an administrator's boxes are read-only`() {
        val budget = tool("budget_tool")
        AccessType.entries.forEach { assertFalse(budget.isEditable(it, isAdmin = true), it.name) }
    }

    /** The one exception, and it has no other door. */
    @Test
    fun `an administrator can still be given transportation posting`() {
        val transportation = tool("transportation_label")

        assertTrue(transportation.isEditable(AccessType.Post, isAdmin = true))
        assertFalse(transportation.isEditable(AccessType.View, isAdmin = true))
        assertFalse(transportation.isEditable(AccessType.Download, isAdmin = true))
    }

    /** Even for transportation, the server's own lock still has the last word. */
    @Test
    fun `a locked transportation posting stays locked for an administrator`() {
        val locked = tool("transportation_label", postLocked = true)
        assertFalse(locked.isEditable(AccessType.Post, isAdmin = true))
    }

    // -- the deal memo's own rule ------------------------------------------

    /**
     * `ZL-16376` — granting a deal memo's viewing grants downloading with it.
     *
     * The server does this itself and neither client posts a second time, so
     * all this moves is the moment the tick appears: without it the box waits
     * for the read that follows the write.
     */
    @Test
    fun `granting a deal memo's viewing ticks its download`() {
        val rows = listOf(tool("deal_memo_label"))
        val granted = rows.withDealMemoDownload(
            RightsToggle("deal_memo_label", RightsSection.Tools, AccessType.View, enable = true),
        )

        assertTrue(granted.single().canDownload)
    }

    @Test
    fun `revoking a deal memo's viewing ticks nothing`() {
        val rows = listOf(tool("deal_memo_label", view = true, download = true))
        val revoked = rows.withDealMemoDownload(
            RightsToggle("deal_memo_label", RightsSection.Tools, AccessType.View, enable = false),
        )

        assertTrue(revoked.single().canDownload, "revoking viewing is the server's to cascade, not ours")
    }

    /** Only this tool, and only its viewing column. */
    @Test
    fun `no other tool gains a download from its viewing`() {
        val rows = listOf(tool("budget_tool"))
        val granted = rows.withDealMemoDownload(
            RightsToggle("budget_tool", RightsSection.Tools, AccessType.View, enable = true),
        )

        assertFalse(granted.single().canDownload)
    }

    @Test
    fun `granting a deal memo's posting ticks no download`() {
        val rows = listOf(tool("deal_memo_label"))
        val granted = rows.withDealMemoDownload(
            RightsToggle("deal_memo_label", RightsSection.Tools, AccessType.Post, enable = true),
        )

        assertFalse(granted.single().canDownload)
    }

    /** The dashboard and the grid are separate rights over the same tool. */
    @Test
    fun `the deal memo's other section is left alone`() {
        val rows = listOf(
            tool("deal_memo_label"),
            tool("deal_memo_label").copy(section = RightsSection.Home),
        )
        val granted = rows.withDealMemoDownload(
            RightsToggle("deal_memo_label", RightsSection.Tools, AccessType.View, enable = true),
        )

        assertTrue(granted.first { it.section == RightsSection.Tools }.canDownload)
        assertFalse(granted.first { it.section == RightsSection.Home }.canDownload)
    }
}
