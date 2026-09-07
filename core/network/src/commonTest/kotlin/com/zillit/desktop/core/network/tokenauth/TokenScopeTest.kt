package com.zillit.desktop.core.network.tokenauth

import com.zillit.desktop.core.network.RequestModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TokenScopeTest {

    @Test
    fun `device routes ride the device token, production routes their production's`() {
        assertEquals(TokenScope.Device, RequestModule.Default.tokenScope(projectId = null))
        assertEquals(TokenScope.Project("p1"), RequestModule.ProjectUser.tokenScope("p1"))
        assertEquals(TokenScope.Project("p1"), RequestModule.Project.tokenScope("p1"))
        assertEquals(TokenScope.Project("p1"), RequestModule.NotificationAcknowledge.tokenScope("p1"))
    }

    @Test
    fun `a production route with no production in context stays legacy`() {
        assertNull(RequestModule.ProjectUser.tokenScope(null))
        assertNull(RequestModule.Chat.tokenScope(""))
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
