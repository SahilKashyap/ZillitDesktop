package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallSession
import com.zillit.desktop.feature.calls.domain.declineReplyTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a declined call's message is posted — Android's routing rule, which
 * its own QA found three ways to get wrong (a group reply in the initiator's
 * personal chat, a mid-call-add reply to the original host, and a random
 * call's reply into a hidden room where neither side could see it).
 */
class DeclineReplyTargetTest {

    private fun ring(
        mode: CallMode = CallMode.Private,
        chatRoomId: String = "",
        addedByUserId: String = "",
        callerUserId: String = "caller",
        isRandomCall: Boolean = false,
        isCalendarCall: Boolean = false,
    ) = CallSession(
        callUuid = "u1",
        mode = mode,
        chatRoomId = chatRoomId,
        callerUserId = callerUserId,
        addedByUserId = addedByUserId,
        isRandomCall = isRandomCall,
        isCalendarCall = isCalendarCall,
        selfUserId = "me",
    )

    @Test
    fun `a one to one ring answers its caller`() {
        val target = ring().declineReplyTarget()
        assertEquals("caller", target?.receiverId)
        assertEquals(false, target?.isGroup)
    }

    @Test
    fun `a group ring answers the room it was placed from`() {
        val target = ring(mode = CallMode.Group, chatRoomId = "room-7").declineReplyTarget()
        assertEquals("room-7", target?.receiverId)
        assertTrue(target?.isGroup == true)
    }

    @Test
    fun `whoever pulled us into a running call is who hears back`() {
        val target = ring(mode = CallMode.Group, chatRoomId = "room-7", addedByUserId = "host")
            .declineReplyTarget()
        assertEquals("host", target?.receiverId)
        assertEquals(false, target?.isGroup)
    }

    @Test
    fun `a random call replies to the caller, never into its hidden room`() {
        val target = ring(mode = CallMode.Group, chatRoomId = "hidden", isRandomCall = true)
            .declineReplyTarget()
        assertEquals("caller", target?.receiverId)
        assertEquals(false, target?.isGroup)
    }

    @Test
    fun `a calendar call is a conversation with its caller, not with the event's room`() {
        val target = ring(mode = CallMode.Group, chatRoomId = "event-room", isCalendarCall = true)
            .declineReplyTarget()
        assertEquals("caller", target?.receiverId)
    }

    @Test
    fun `a ring naming nobody has nowhere to reply`() {
        assertNull(ring(callerUserId = "").declineReplyTarget())
    }
}
