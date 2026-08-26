package com.zillit.desktop.feature.calls

import com.zillit.desktop.feature.calls.data.recordingTargets
import com.zillit.desktop.feature.calls.domain.CallChatTarget
import com.zillit.desktop.feature.calls.domain.CallDirection
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallParticipant
import com.zillit.desktop.feature.calls.domain.CallSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Who a finished recording is sent to.
 *
 * The rules are iOS's, because the recording lands in the same threads its
 * recordings do: one message to a group's room, one per person otherwise, and
 * nothing at all addressed to a device id.
 */
class CallRecordingTargetsTest {

    private fun session(
        mode: CallMode = CallMode.Private,
        chatRoomId: String = "",
        participants: List<CallParticipant> = emptyList(),
        callerUserId: String = "",
        receiverUserId: String = "",
        direction: CallDirection = CallDirection.Incoming,
    ) = CallSession(
        callUuid = "u1",
        chatRoomId = chatRoomId,
        mode = mode,
        direction = direction,
        participants = participants,
        callerUserId = callerUserId,
        receiverUserId = receiverUserId,
        selfUserId = "me",
    )

    @Test
    fun `a group call posts once, into the room`() {
        val targets = recordingTargets(
            session(
                mode = CallMode.Group,
                chatRoomId = "room-7",
                participants = listOf(
                    CallParticipant(userId = "me"),
                    CallParticipant(userId = "u2"),
                    CallParticipant(userId = "u3"),
                ),
            ),
            selfUserId = "me",
        )

        // One file, one room. Three messages would be three copies of the
        // same recording in the same conversation.
        assertEquals(listOf(CallChatTarget("room-7", isGroup = true)), targets)
    }

    @Test
    fun `a private call posts to each other person`() {
        val targets = recordingTargets(
            session(
                participants = listOf(
                    CallParticipant(userId = "me"),
                    CallParticipant(userId = "u2"),
                    CallParticipant(userId = "u2"),
                ),
            ),
            selfUserId = "me",
        )

        assertEquals(listOf(CallChatTarget("u2", isGroup = false)), targets)
    }

    @Test
    fun `a roster known only by device falls back to who we called`() {
        // Mediasoup 1:1 hands us the peer's device id and never enriches a
        // user id onto the row. Sending to that device id is not a partial
        // failure — the chat server drops it silently, so the recording just
        // never arrives.
        val targets = recordingTargets(
            session(
                participants = listOf(
                    CallParticipant(userId = "me"),
                    CallParticipant(userId = "", deviceId = "their-device"),
                ),
                callerUserId = "caller",
            ),
            selfUserId = "me",
        )

        assertEquals(listOf(CallChatTarget("caller", isGroup = false)), targets)
    }

    @Test
    fun `a group call with no room still reaches its people`() {
        val targets = recordingTargets(
            session(
                mode = CallMode.Group,
                chatRoomId = "",
                participants = listOf(CallParticipant(userId = "me"), CallParticipant(userId = "u2")),
            ),
            selfUserId = "me",
        )

        assertEquals(listOf(CallChatTarget("u2", isGroup = false)), targets)
    }

    @Test
    fun `a call with nobody else to address sends nothing`() {
        val targets = recordingTargets(
            session(participants = listOf(CallParticipant(userId = "me"))),
            selfUserId = "me",
        )

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `an outgoing call falls back to who we rang`() {
        // Outgoing 1:1 on Line 1: the roster row carries a device id and
        // nothing else, and every "caller" field on the session describes us.
        // Without the receiver we rang, there is no user id anywhere to send
        // the recording to.
        val targets = recordingTargets(
            session(
                direction = CallDirection.Outgoing,
                participants = listOf(CallParticipant(userId = "", deviceId = "their-device")),
                callerUserId = "me",
                receiverUserId = "u2",
            ),
            selfUserId = "me",
        )

        assertEquals(listOf(CallChatTarget("u2", isGroup = false)), targets)
    }

    @Test
    fun `a call that only ever names us sends nothing`() {
        val targets = recordingTargets(
            session(
                direction = CallDirection.Outgoing,
                participants = listOf(CallParticipant(userId = "me")),
                callerUserId = "me",
            ),
            selfUserId = "me",
        )

        // Messaging ourselves would look like a delivered recording and be
        // nothing of the sort.
        assertTrue(targets.isEmpty())
    }
}
