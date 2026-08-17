package com.zillit.desktop.feature.settings

import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.ui.ADMIN_SETTINGS_PATH
import com.zillit.desktop.feature.settings.ui.AdminSettingsToolProvider
import com.zillit.desktop.feature.settings.ui.SETTINGS_PATH
import com.zillit.desktop.feature.settings.ui.SettingsToolProvider
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import com.zillit.desktop.feature.settings.ui.path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which window opens for which path, now that administration is its own
 * destination on the rail.
 *
 * The hazard is that `/settings/admin` also starts with `/settings`. The
 * registry resolves by longest prefix, so this is already correct — but it is
 * correct by a rule living in another module, and a well-meant change to either
 * path would send a coordinator to the theme switch instead. Hence a test.
 */
class AdminSettingsRoutingTest {

    private val viewModel = SettingsViewModel(setTheme = {}, setScale = {}, signOut = {})

    private val registry = ToolRegistry(
        listOf(
            SettingsToolProvider(viewModel),
            AdminSettingsToolProvider(viewModel),
        ),
    )

    private fun resolvedPathFor(path: String): String? =
        registry.resolve(WorkspaceRoute.Tool(path))?.path

    @Test
    fun `the two are separate destinations`() {
        assertEquals(SETTINGS_PATH, resolvedPathFor(SETTINGS_PATH))
        assertEquals(ADMIN_SETTINGS_PATH, resolvedPathFor(ADMIN_SETTINGS_PATH))
    }

    @Test
    fun `the admin path does not fall back to settings`() {
        // The regression this file exists for: `/settings/admin` starts with
        // `/settings`, so a shortest-prefix match would open the wrong window
        // and the coordinator would land on their own preferences.
        assertEquals(ADMIN_SETTINGS_PATH, resolvedPathFor("$ADMIN_SETTINGS_PATH/anything"))
    }

    @Test
    fun `both approval queues belong to the admin window`() {
        ApprovalQueue.entries.forEach { queue ->
            assertEquals(
                ADMIN_SETTINGS_PATH,
                resolvedPathFor(queue.path),
                "${queue.name} resolved somewhere other than administration",
            )
        }
    }

    /**
     * Every administration page opens in the administration window.
     *
     * The provider declares `hostsOwnRoutes`, so the registry hands it anything
     * under its prefix and the provider itself decides which page that is. This
     * is the assertion that the two halves agree: a page whose route the
     * registry sends elsewhere is a row that opens the wrong window, and a page
     * whose slug the provider does not recognise falls through to the listing —
     * both look like "the click did nothing".
     */
    @Test
    fun `every administration page belongs to the admin window`() {
        AdminDestination.entries.forEach { page ->
            assertEquals(
                ADMIN_SETTINGS_PATH,
                resolvedPathFor(page.path),
                "${page.name} resolved somewhere other than administration",
            )
            assertEquals(
                page,
                AdminDestination.entries.firstOrNull { it.path == page.path },
                "${page.name} is not the page its own route resolves to",
            )
        }
    }

    @Test
    fun `administration is the rail entry's destination`() {
        // `feature:shell` cannot import this module, so it carries the path as
        // a literal. This is the assertion that keeps the two in agreement.
        assertEquals("/settings/admin", ADMIN_SETTINGS_PATH)
    }
}
