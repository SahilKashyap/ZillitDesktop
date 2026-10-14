package com.zillit.desktop.feature.calls.domain

import com.zillit.desktop.core.common.ZillitResult

/**
 * Where a declined call's "can't talk right now" goes.
 *
 * A seam rather than a call into chat, for the same reason
 * [CallRecordingShare] is one: the reply is a chat message, and this module
 * knows nothing about chat. The host supplies the one implementation.
 *
 * The message is what actually reaches the caller. Android attaches the text
 * to Line 3's `declineCall` frame as well, but documents that no server relays
 * it (`CallSignaling.decline`), and Lines 1 and 2 carry no field for it at
 * all — so on every line the chat post is the delivery, not a consolation
 * copy of it. This client therefore leaves the decline's own wire shape alone.
 */
fun interface CallQuickReply {

    /** Posts [text] into [target]'s thread. */
    suspend fun send(target: CallChatTarget, text: String): ZillitResult<Unit>
}

/**
 * Which thread a decline's message belongs in — Android's rule
 * ([CallState.declineReplyTarget] on Line 3, `CallViewModel.declineWithMessage`
 * on Lines 1 and 2), which the two clients state differently and agree on:
 *
 *  1. Someone added us to a call already running → reply to *them*. They are
 *     who wants to know; the original caller may not even know we were rung.
 *  2. A genuine group call → the room it was placed from, so the answer is
 *     where the question was asked.
 *  3. Anything else → the caller, one to one.
 *
 * A random or calendar call never takes route 2 even when the wire calls it a
 * group: its room is server-side and hidden, and Android's QA found a reply
 * posted there invisible to both ends. Null when the session names nobody to
 * reply to, which is the one case the UI hides the Message action for.
 */
fun CallSession.declineReplyTarget(): CallChatTarget? {
    addedByUserId.takeIf(String::isNotBlank)?.let {
        return CallChatTarget(receiverId = it, isGroup = false)
    }
    // A real room, in the sense that both ends can read it: a random call's
    // room is server-side and hidden, and a calendar call's belongs to the
    // event rather than to this conversation.
    val roomIsReadable = mode == CallMode.Group && !isRandomCall && !isCalendarCall
    chatRoomId.takeIf { roomIsReadable && it.isNotBlank() }?.let {
        return CallChatTarget(receiverId = it, isGroup = true)
    }
    val caller = callerUserId.ifBlank { displayUserId }.takeIf(String::isNotBlank) ?: return null
    return CallChatTarget(receiverId = caller, isGroup = false)
}
