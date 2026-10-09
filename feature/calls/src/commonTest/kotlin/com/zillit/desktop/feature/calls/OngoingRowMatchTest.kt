package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.livekit.LiveKitActiveCall
import com.zillit.desktop.feature.calls.domain.CallLogDirection
import com.zillit.desktop.feature.calls.domain.CallLogEntry
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.OngoingVerb
import com.zillit.desktop.feature.calls.ui.inRoom
import com.zillit.desktop.feature.calls.ui.ongoingByRow
import com.zillit.desktop.feature.calls.ui.ongoingCalls
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which history row and which group header offer a live call's Join / Switch here / Return. */
class OngoingRowMatchTest {

    private fun row(uuid: String, mode: CallMode = CallMode.Private, room: String = "") = CallLogEntry(
        callUuid = uuid,
        direction = CallLogDirection.Incoming,
        mode = mode,
        type = CallType.Audio,
        missed = false,
        durationMillis = 0,
        startedAtMillis = 0,
        roomId = room,
    )

    private fun live(id: String, room: String = "", inCall: List<String> = listOf("ann")) = LiveKitActiveCall(
        callId = id,
        inCallUserIds = inCall,
        callMode = if (room.isBlank()) CallMode.Private else CallMode.Group,
        chatRoomId = room,
        inCallUsers = inCall.map { it to it },
    )

    private fun ongoing(vararg calls: LiveKitActiveCall, here: String? = null, me: String = "me") =
        ongoingCalls(calls.toList(), here, me, projectId = null)

    @Test
    fun `the live call's own row carries the button`() {
        val rows = listOf(row("c2"), row("c1"))
        val matched = ongoingByRow(rows, ongoing(live("c1")))
        assertEquals(setOf("c1"), matched.keys)
    }

    @Test
    fun `a group call not yet in the history lands on the room's newest row only`() {
        val rows = listOf(
            row("new", CallMode.Group, room = "r1"),
            row("old", CallMode.Group, room = "r1"),
            row("other", CallMode.Group, room = "r2"),
        )
        val matched = ongoingByRow(rows, ongoing(live("c9", room = "r1")))
        assertEquals(setOf("new"), matched.keys)
    }

    @Test
    fun `a 1to1 call never matches by room`() {
        val rows = listOf(row("x", CallMode.Private, room = "r1"))
        assertEquals(emptyMap(), ongoingByRow(rows, ongoing(live("c9", room = "r1"))).mapValues { it.value.callId })
    }

    @Test
    fun `the row says Switch here when I am in it elsewhere and Return when it is open here`() {
        val rows = listOf(row("c1"), row("c2"))
        val matched = ongoingByRow(rows, ongoing(live("c1", inCall = listOf("me")), live("c2"), here = "c2"))
        assertEquals(OngoingVerb.Switch, matched.getValue("c1").verb)
        assertEquals(OngoingVerb.Return, matched.getValue("c2").verb)
    }

    @Test
    fun `the group header finds its room's call and nothing for another room`() {
        val calls = ongoing(live("c1", room = "r1"))
        assertEquals("c1", calls.inRoom("r1")?.callId)
        assertEquals(OngoingVerb.Join, calls.inRoom("r1")?.verb)
        assertNull(calls.inRoom("r2"))
        assertNull(calls.inRoom(""))
    }
}
