package com.zillit.desktop.feature.calls.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * A group thread header's call slot — the web's `ChatScreenGroups` and
 * Android's `ChatAndGroupPage.handleJoinCallVisibility`: while the room has a
 * live call, the start-call buttons give way to Join Call (Switch here when
 * we are in it on another device, Return when it is open here), and the same
 * confirm as the Calls tab asks before going in. No live call and
 * [callButtons] draw as they always did.
 *
 * Every line: LiveKit off the presence socket's live list, Lines 2 and 3 off
 * the active-group-calls list the call socket keeps current — so the button
 * comes and goes with the call without polling.
 */
@Composable
fun RoomCallJoin(
    chatRoomId: String,
    source: OngoingCallsSource,
    selfUserId: String?,
    /** Names for the confirm's roster — a Line 2/3 row carries ids, not always names. */
    nameFor: (String) -> String? = { null },
    callButtons: @Composable () -> Unit,
) {
    val calls by source.activeCalls.collectAsState(emptyList())
    val groupCalls by source.groupCalls.collectAsState(emptyList())
    val liveHere by source.liveCallId.collectAsState(null)
    val live = remember(calls, groupCalls, liveHere, selfUserId, chatRoomId) {
        ongoingCalls(calls, liveHere, selfUserId.orEmpty(), projectId = null, groupCalls).inRoom(chatRoomId)
    }
    // Opening a group asks again: the Line 2/3 list moves on socket events,
    // and one missed while the thread was shut would leave the header wrong.
    LaunchedEffect(chatRoomId) { source.refresh() }
    // Keyed on the room: a confirm opened in one group must not survive into the next.
    var confirming by remember(chatRoomId) { mutableStateOf<OngoingCall?>(null) }

    if (live == null) {
        callButtons()
    } else {
        OngoingButton(
            verb = live.verb,
            // "Join Call" where there is room for it, as the web's group header spells it.
            text = if (live.verb == OngoingVerb.Join) str(S.join_call) else live.verb.label,
            onClick = {
                if (live.verb == OngoingVerb.Return) source.onReturn() else confirming = live
            },
        )
    }

    confirming?.let { call ->
        WindowOverlay(onDismiss = { confirming = null }) {
            JoinOngoingDialog(
                call = call,
                selfUserId = selfUserId,
                nameFor = nameFor,
                onConfirm = {
                    confirming = null
                    source.onJoin(call)
                },
                onDismiss = { confirming = null },
            )
        }
    }
}
