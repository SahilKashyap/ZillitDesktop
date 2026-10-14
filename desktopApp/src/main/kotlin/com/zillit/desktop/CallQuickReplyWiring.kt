package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.feature.calls.domain.CallQuickReply
import com.zillit.desktop.feature.chat.data.ChatRepository
import java.util.UUID

/**
 * Where a declined call's "can't talk right now" is posted: the caller's
 * thread, as an ordinary chat message.
 *
 * Lives here for the same reason [callRecordingShare] does — the call owns the
 * decline, chat owns the thread — and sends through the same repository the
 * chat screen uses, so the reply arrives on the phones as a message from this
 * person rather than as a call event no client renders.
 *
 * The production is the open one, as it is for a shared recording: a call rung
 * from a widget showing another production would post into the wrong scope,
 * which is a limit of there being one chat repository here, not a decision.
 */
internal fun callQuickReply(
    chat: ChatRepository,
    now: () -> Long = { System.currentTimeMillis() },
    newUniqueId: () -> String = { UUID.randomUUID().toString() },
): CallQuickReply = CallQuickReply { target, text ->
    ZillitLog.i(TAG) { "decline reply to ${target.receiverId} (group=${target.isGroup})" }
    chat.send(
        receiverId = target.receiverId,
        body = text,
        uniqueId = newUniqueId(),
        nowMillis = now(),
        isGroup = target.isGroup,
    )
}

private const val TAG = "CallQuickReply"
