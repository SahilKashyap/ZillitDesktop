package com.zillit.desktop.core.network.tokenauth

import com.zillit.desktop.core.network.RequestModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class TokenScopeTest {

    @Test
    fun `device routes ride the device token, production routes their production's`() {
        assertEquals(TokenScope.Device, RequestModule.Default.tokenScope(projectId = null))
        assertEquals(TokenScope.Project("p1"), RequestModule.ProjectUser.tokenScope("p1"))
        assertEquals(TokenScope.Project("p1"), RequestModule.Project.tokenScope("p1"))
        assertEquals(TokenScope.Project("p1"), RequestModule.NotificationAcknowledge.tokenScope("p1"))
    }

    /**
     * With no production open a project-scoped module rides the **device**
     * token, not `moduledata`.
     *
     * Many genuinely device-level calls use a project variant only because
     * that was the default at the call site — the preset lookups on the
     * create-project screen run before any production exists. Develop now
     * answers `libs_moduledata_not_accepted`, so the old fallback to the
     * legacy header was a guaranteed 401 rather than a graceful degrade.
     */
    @Test
    fun `a production route with no production in context rides the device token`() {
        assertEquals(TokenScope.Device, RequestModule.ProjectUser.tokenScope(null))
        assertEquals(TokenScope.Device, RequestModule.Chat.tokenScope(""))
    }

    /**
     * Except on the routes the server has actually been seen refusing a
     * device token. There the fallback would be a certain 401, so the call
     * takes the legacy credential instead. Suffix-matched, so the host and
     * any query string are irrelevant.
     */
    @Test
    fun `the routes that insist on a project token are known by path`() {
        assertTrue(requiresProjectToken("https://projectapi-dev.zillit.com/api/v2/project/users"))
        assertTrue(requiresProjectToken("https://projectapi-dev.zillit.com/api/v2/project/tools/groups?x=1"))
        assertTrue(requiresProjectToken("https://h/api/v2/account-hub/project-settings/"))
        assertFalse(requiresProjectToken("https://projectapi-dev.zillit.com/api/v2/user/profile"))
        // A near-miss must not match: /project is not /project/users.
        assertFalse(requiresProjectToken("https://projectapi-dev.zillit.com/api/v2/project"))
    }

    @Test
    fun `what the server reads beyond identity, and what has no session, stays on moduledata`() {
        listOf(
            RequestModule.Device,
            RequestModule.ScannerDevice,
            RequestModule.MapRoute,
            RequestModule.SocketHandshake,
            RequestModule.LiveKit,
            RequestModule.SessionBootstrap,
        ).forEach { module -> assertNull(module.tokenScope("p1"), module.name) }
    }
}
