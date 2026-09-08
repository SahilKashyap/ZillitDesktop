package com.zillit.desktop.core.permissions

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asking an admin for a right.
 *
 * The message is the whole feature: there is no permission-request endpoint on
 * any client, so what lands in the admin's chat has to name the module and the
 * exact route through the rights grid. Pinned against Android's
 * `requestPermissionMessage` (`utils/Extensions.kt:282`) and the four string
 * resources it joins — a coordinator reading requests from a mixed crew should
 * see one sentence, not three dialects.
 */
class RightsRequestTest {

    @Test
    fun `a posting request reads exactly as the phones send it`() {
        val message = rightsRequestMessage(
            RightsRequest("Document Distribution", RightsKind.Post),
        )

        assertEquals(
            "Please grant me permission to post in Document Distribution. " +
                "Go to ‘Settings’ > ‘Admin Settings’ > ‘Viewing & Posting Rights Grid’ > " +
                "Select User > Tools > Select Document Distribution to grant this request.",
            message,
        )
    }

    @Test
    fun `a download request swaps only the verb`() {
        val post = rightsRequestMessage(RightsRequest("Continuity", RightsKind.Post))
        val download = rightsRequestMessage(RightsRequest("Continuity", RightsKind.Download))

        assertEquals(post.replace("permission to post", "permission to download"), download)
    }

    /**
     * The grid is split by area and the message names the tab. An admin sent
     * to the wrong one reasonably reports the request as impossible.
     */
    @Test
    fun `a home unit sends the admin to the Home tab, not Tools`() {
        val message = rightsRequestMessage(
            RightsRequest("Main Unit", RightsKind.Post, RightsArea.Home),
        )

        assertTrue(message.contains("Select User > Home > Select Main Unit"), message)
    }

    /** The module's own words reach the admin verbatim — never an identifier. */
    @Test
    fun `the module label is carried through untouched`() {
        val message = rightsRequestMessage(RightsRequest("Call Sheet", RightsKind.Download))

        assertTrue(message.contains("download in Call Sheet."), message)
        assertTrue(message.contains("Select Call Sheet to grant"), message)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the bus carries what a module asked for`() = runTest {
        val bus = RightsRequestBus()
        val seen = mutableListOf<RightsRequest>()

        val collector = launch { bus.requests.take(2).toList(seen) }
        // A subscriber has to be attached before a hot flow emits; the bus is
        // deliberately not replaying, because a request is a click and a
        // replayed one would reopen the dialog on the next screen.
        kotlinx.coroutines.yield()

        bus.ask("Drive", RightsKind.Download)
        bus.ask(RightsRequest("Home", RightsKind.Post, RightsArea.Home))
        collector.join()

        assertEquals(
            listOf(
                RightsRequest("Drive", RightsKind.Download, RightsArea.Tools),
                RightsRequest("Home", RightsKind.Post, RightsArea.Home),
            ),
            seen,
        )
    }

    // -- the gate on a control that stays visible --------------------------

    @Test
    fun `a granted press runs the action and asks for nothing`() {
        var ran = 0
        var denied = 0
        gatedClick(granted = true, onDenied = { denied++ }) { ran++ }.invoke()

        assertEquals(1, ran)
        assertEquals(0, denied)
    }

    /**
     * The whole point of keeping the button: the press is answered, and it is
     * answered by asking rather than by writing something the server refuses.
     */
    @Test
    fun `a denied press asks, and never reaches the action`() {
        var ran = 0
        var denied = 0
        gatedClick(granted = false, onDenied = { denied++ }) { ran++ }.invoke()

        assertEquals(0, ran, "a denied press reached the action")
        assertEquals(1, denied)
    }

    @Test
    fun `the refusal promises an ask only when one went out`() {
        assertEquals(
            "You do not have download rights on Drive — asking an administrator.",
            rightsRefusalMessage("Drive", RightsKind.Download, asked = true),
        )
        assertEquals(
            "You do not have post rights on Drive.",
            rightsRefusalMessage("Drive", RightsKind.Post, asked = false),
        )
    }
}
