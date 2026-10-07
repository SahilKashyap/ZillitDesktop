package com.zillit.desktop.feature.settings

import com.zillit.desktop.feature.settings.admin.domain.DownloadStatus
import com.zillit.desktop.feature.settings.admin.domain.ProductionTool
import com.zillit.desktop.feature.settings.admin.domain.cabinetModules
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.ui.ProductionFacts
import com.zillit.desktop.feature.settings.ui.SettingsDestination
import com.zillit.desktop.feature.settings.ui.adminSettingsEntries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The File Cabinet against the web's `FileCabinateModal`: which rows, when, and the request's states. */
class FileCabinetTest {

    private fun modules(vararg tools: ProductionTool) =
        cabinetModules(tools.toList(), { it.name }, groupChat = "Group Chat", home = "Home")

    @Test
    fun `a module appears only when its tool is on`() {
        val rows = modules(
            ProductionTool("main_budget_tool", "Budget (Full)", enabled = true),
            ProductionTool("dod_tool", "Schedule D.O.D", enabled = false),
        )

        assertEquals(listOf("main_budget", "group_chat", "home"), rows.map { it.identifier })
    }

    @Test
    fun `rows keep the web's order whatever order the tools arrive in`() {
        val rows = modules(
            ProductionTool("recce_tool", "Recce", enabled = true),
            ProductionTool("accounting_tool", "Accounts", enabled = true),
            ProductionTool("info_tool", "Info", enabled = true),
        )

        assertEquals(listOf("accounting", "group_chat", "home", "info", "recce"), rows.map { it.identifier })
    }

    @Test
    fun `a module is named by its tool's label, not its identifier`() {
        val rows = modules(ProductionTool("accounting_tool", "Accounts", enabled = true))

        assertEquals("Accounts", rows.first { it.identifier == "accounting" }.name)
    }

    @Test
    fun `home and group chat are offered with no tools at all`() {
        assertEquals(listOf("group_chat", "home"), modules().map { it.identifier })
    }

    @Test
    fun `the server's three statuses are read, and an unknown one counts as preparing`() {
        assertEquals(DownloadStatus.Pending, DownloadStatus.of("pending"))
        assertEquals(DownloadStatus.InProgress, DownloadStatus.of("in-progress"))
        assertEquals(DownloadStatus.Completed, DownloadStatus.of("completed"))
        assertEquals(DownloadStatus.Pending, DownloadStatus.of("queued"))
        assertEquals(DownloadStatus.Pending, DownloadStatus.of(null))
    }

    // -- the tile, hidden where the web hides it ---------------------------

    private fun destinations(production: ProductionFacts) =
        adminSettingsEntries(production).flatMap { it.entries }.map { it.destination }

    @Test
    fun `the row is offered outside a production build`() {
        assertTrue(SettingsDestination.FileCabinet in destinations(ProductionFacts()))
    }

    @Test
    fun `the row is hidden on a production build, as the web hides its tile`() {
        assertFalse(SettingsDestination.FileCabinet in destinations(ProductionFacts(showFileCabinet = false)))
        assertFalse(AdminDestination.FileCabinet.availableTo(ProductionFacts(showFileCabinet = false)))
    }

    @Test
    fun `a personal production has no file cabinet`() {
        assertFalse(AdminDestination.FileCabinet.availableTo(ProductionFacts(isPersonal = true)))
    }

    @Test
    fun `the row opens its page`() {
        assertEquals(AdminDestination.FileCabinet, AdminDestination.of(SettingsDestination.FileCabinet))
    }
}
