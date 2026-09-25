package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.ui.ADMIN_SETTINGS_PATH
import com.zillit.desktop.feature.settings.ui.SETTINGS_PATH
import com.zillit.desktop.feature.settings.ui.SettingsTab
import com.zillit.desktop.feature.settings.ui.SettingsToolProvider
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import com.zillit.desktop.feature.settings.ui.path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which window opens for which path, now that administration is the second
 * tab of Settings — as on the web — rather than a window of its own.
 */
class AdminSettingsRoutingTest {

    private val viewModel = SettingsViewModel(setTheme = {}, setScale = {}, signOut = {})

    private val registry = ToolRegistry(listOf(SettingsToolProvider(viewModel)))

    private fun resolvedPathFor(path: String): String? =
        registry.resolve(WorkspaceRoute.Tool(path))?.path

    @Test
    fun `both tabs are the settings window`() {
        assertEquals(SETTINGS_PATH, resolvedPathFor(SETTINGS_PATH))
        assertEquals(SETTINGS_PATH, resolvedPathFor(ADMIN_SETTINGS_PATH))
    }

    @Test
    fun `both approval queues and every admin page belong to the settings window`() {
        (ApprovalQueue.entries.map { it.path } + AdminDestination.entries.map { it.path }).forEach { path ->
            assertEquals(SETTINGS_PATH, resolvedPathFor(path), "$path resolved somewhere other than Settings")
        }
    }

    @Test
    fun `the route picks the tab`() {
        assertEquals(SettingsTab.Profile, SettingsTab.forPath(SETTINGS_PATH, isAdmin = true))
        assertEquals(SettingsTab.Admin, SettingsTab.forPath(ADMIN_SETTINGS_PATH, isAdmin = true))
    }

    @Test
    fun `a non-admin asking for the admin tab gets their profile`() {
        // As the web's `handleTabChange`: rights are revoked mid-session, and
        // a tab that has vanished cannot stay selected.
        assertEquals(SettingsTab.Profile, SettingsTab.forPath(ADMIN_SETTINGS_PATH, isAdmin = false))
    }

    @Test
    fun `the admin tab keeps the web's path`() {
        assertEquals("/settings/admin", ADMIN_SETTINGS_PATH)
        assertEquals(ADMIN_SETTINGS_PATH, SettingsTab.Admin.path)
    }
}
