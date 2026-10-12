package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * A Line 1 MEET's join on the wire — the steps the lobby takes, each one call:
 * resolve the link, ask about a second device, the socket's `startCall` with
 * `callMode: "meet"`, the knock, and the REST mint for when the socket cannot.
 *
 * Owned by [LiveKitLine], whose presence connection it borrows ([peer]) and
 * which routes `guestAdmitted` / `guestDeclined` here. The ORDER of the steps
 * is the lobby's (`MeetLobby`); nothing here decides it.
 */
class LiveKitMeets internal constructor(
    private val api: LiveKitApi,
    private val peer: () -> LiveKitPeer?,
    private val identity: () -> LiveKitIdentity?,
) {
    /** Step 1 of a meet join, and the lobby's opening read: what the link is. */
    suspend fun meetInfo(meetId: String): MeetLookup {
        val me = identity()
        return when (val answer = api.meet(meetId, me?.projectId, me?.userId)) {
            is ZillitResult.Success -> readMeetInfo(meetId, answer.data)?.let { MeetLookup.Found(it) }
                ?: MeetLookup.Unknown("the meet answer was not an object")
            is ZillitResult.Failure -> {
                val http = answer.error as? ZillitError.Http
                if (http?.status == HTTP_NOT_FOUND || http?.serverMessage == "no_such_meet") {
                    MeetLookup.NoSuchMeet
                } else {
                    ZillitLog.w(TAG) { "meet $meetId lookup failed: ${answer.error.technical}" }
                    MeetLookup.Unknown(answer.error.technical.orEmpty())
                }
            }
        }
    }

    /** `GET …/me` for us, or null when it could not be read. */
    suspend fun meetPresence(meetId: String): MeetPresence? {
        val me = identity() ?: return null
        return when (val answer = api.callPresence(meetId, me.userId, me.projectId)) {
            is ZillitResult.Success -> readMeetPresence(answer.data)
            is ZillitResult.Failure -> null
        }
    }

    /**
     * Who is IN the meeting, over HTTP — safe before we are a member. Null on
     * any failure, a 503 `roster_unavailable` included: the caller keeps what
     * it already shows rather than flipping to "nobody here".
     */
    suspend fun meetRoster(meetId: String): MeetRosterRead? {
        val me = identity() ?: return null
        return when (val answer = api.roster(meetId, me.userId, me.projectId)) {
            is ZillitResult.Success -> readMeetAttendees(answer.data)
            is ZillitResult.Failure -> null
        }
    }

    /** After a presence reconnect, once: re-seed the roster so the next read is current. */
    suspend fun refreshMeetRoster(meetId: String) {
        val me = identity() ?: return
        api.refreshRoster(meetId, me.userId, me.projectId)
    }

    /**
     * PATH A: one `startCall` with `callMode: "meet"`, which both starts an
     * empty room and joins a running one, enforces the start policy, pins the
     * media node and hands back the room in its ack. Rings nobody.
     *
     * Only on the presence socket; anything this path cannot settle is
     * [MeetStart.UseRest]. Never run beside the REST path.
     */
    suspend fun startMeet(meetId: String, video: Boolean): MeetStart {
        val live = peer() ?: return MeetStart.UseRest("presence socket down")
        val me = identity() ?: return MeetStart.UseRest("signed out")
        return runCatching {
            withTimeout(START_TIMEOUT_MILLIS) { live.request("startCall", startFields(meetId, video, me)) }
        }.fold(onSuccess = ::acked, onFailure = { refusedStart(it.message.orEmpty()) })
    }

    /** The ack's room, or REST when it carried none. */
    private fun acked(ack: JsonElement?): MeetStart {
        val creds = (ack as? JsonObject)?.readLiveKitCredentials()
        return if (creds != null && creds.isUsable) {
            MeetStart.Creds(creds.url, creds.token)
        } else {
            MeetStart.UseRest("startCall acked without a room")
        }
    }

    /** `not_allowed_to_start` is a knock, a known refusal is stated, and anything else (a timeout too) is REST. */
    private fun refusedStart(message: String): MeetStart = when {
        message.contains("not_allowed_to_start") -> MeetStart.MustKnock
        else -> meetProblemOf(REFUSALS.firstOrNull { message.contains(it) })
            ?.let { MeetStart.Refused(it, message) }
            ?: MeetStart.UseRest("startCall: $message")
    }

    private fun startFields(meetId: String, video: Boolean, me: LiveKitIdentity) = buildJsonObject {
        put("callId", JsonPrimitive(meetId))
        put("calleeIds", JsonArray(emptyList()))
        put("callMode", JsonPrimitive(CALL_MODE_MEET))
        put("callType", JsonPrimitive(if (video) CallType.Video.wire else CallType.Audio.wire))
        put("callerName", JsonPrimitive(me.displayName))
        put("callerId", JsonPrimitive(me.userId))
        me.projectId.takeIf { it.isNotBlank() }?.let { put("projectId", JsonPrimitive(it)) }
        me.projectName.takeIf { it.isNotBlank() }?.let { put("projectName", JsonPrimitive(it)) }
    }

    private var knockWaiter: CompletableDeferred<KnockAnswer>? = null
    private var knockCallId = ""

    /**
     * STEP 4: asks the people in the meeting to let us in, and waits — with
     * no timeout, because the server has none. Cancelling the caller IS the
     * cancel: nothing is sent (no such verb exists), and the waiter is gone,
     * so an admit that arrives afterwards finds nobody and is dropped.
     */
    suspend fun knock(meetId: String, displayName: String): KnockAnswer {
        val live = peer() ?: return KnockAnswer.Refused(MeetProblem.Network)
        val waiter = CompletableDeferred<KnockAnswer>()
        knockWaiter?.cancel()
        knockWaiter = waiter
        knockCallId = meetId
        try {
            val sent = runCatching {
                live.request(
                    "knockCall",
                    buildJsonObject {
                        put("callId", JsonPrimitive(meetId))
                        put("displayName", JsonPrimitive(displayName))
                        put("guest", JsonPrimitive(true))
                    },
                )
            }
            if (sent.isFailure) {
                ZillitLog.w(TAG) { "knockCall refused: ${sent.exceptionOrNull()?.message}" }
                return KnockAnswer.Refused(MeetProblem.Network)
            }
            ZillitLog.i(TAG) { "knocking on $meetId" }
            return waiter.await()
        } finally {
            if (knockWaiter === waiter) {
                knockWaiter = null
                knockCallId = ""
            }
        }
    }

    /** `guestAdmitted` / `guestDeclined`, for the knock waiting on [callId] — and nothing else. */
    internal fun onKnockAnswer(callId: String, answer: KnockAnswer) {
        val waiter = knockWaiter
        if (waiter == null || (callId.isNotBlank() && callId != knockCallId)) {
            ZillitLog.i(TAG) { "knock answer for ${callId.ifBlank { "?" }} with no knock waiting; dropped" }
            return
        }
        ZillitLog.i(TAG) { "knock on $knockCallId answered: ${answer::class.simpleName}" }
        waiter.complete(answer)
    }

    /** The presence connection went: a knock lives on it, and the server forgets it with it. */
    internal fun failKnock() {
        knockWaiter?.complete(KnockAnswer.Refused(MeetProblem.Network))
    }

    /**
     * PATH B, only when the socket cannot carry the join: mint over REST, and
     * only for a room that is RUNNING. An unstarted meet has no pinned node,
     * so a mint would fall back to the default region and a socket joiner who
     * later pins it elsewhere would sit in a same-named room on another
     * LiveKit instance — the two never hear each other. Never after a knock.
     */
    suspend fun joinMeetOverRest(meetId: String): MeetRest {
        val me = identity() ?: return MeetRest.Refused(MeetProblem.Other, "signed out")
        val projectId = me.projectId.takeIf { it.isNotBlank() }
        when (val state = api.activeState(meetId, projectId, me.userId)) {
            is ZillitResult.Failure -> {
                ZillitLog.w(TAG) { "meet $meetId active check failed: ${state.error.technical}" }
                return refused(state.error)
            }
            is ZillitResult.Success -> if (!state.data.active) return MeetRest.Refused(MeetProblem.NotStarted)
        }
        return when (val minted = api.mintToken(meetId, me.userId, me.displayName, projectId)) {
            is ZillitResult.Success -> if (minted.data.isUsable) {
                MeetRest.Creds(minted.data.url, minted.data.token)
            } else {
                MeetRest.Refused(MeetProblem.NoJoinDetails)
            }
            is ZillitResult.Failure -> {
                ZillitLog.w(TAG) { "meet $meetId mint refused: ${minted.error.technical}" }
                refused(minted.error)
            }
        }
    }

    /**
     * One media-recovery attempt for a meet we were IN: `GET …/me` first, and
     * a token only when it says the room is running and we are out of it.
     * `inCall: true` means the SDK's own reconnect still holds our place, and
     * minting over it would put a second identity in the room.
     */
    suspend fun recover(meetId: String): MeetRecovery {
        val me = identity() ?: return MeetRecovery.Failed
        val presence = meetPresence(meetId) ?: return MeetRecovery.Failed
        return when {
            !presence.active -> MeetRecovery.Ended
            presence.inCall -> MeetRecovery.SdkReconnecting
            else -> {
                val minted = api.mintToken(meetId, me.userId, me.displayName, me.projectId.ifBlank { null })
                val creds = (minted as? ZillitResult.Success)?.data?.takeIf { it.isUsable }
                creds?.let { MeetRecovery.Reconnect(it.url, it.token) } ?: MeetRecovery.Failed
            }
        }
    }

    private fun refused(error: ZillitError): MeetRest.Refused {
        val http = error as? ZillitError.Http ?: return MeetRest.Refused(MeetProblem.Network)
        return MeetRest.Refused(meetProblemOf(http.serverMessage) ?: MeetProblem.Other, http.serverMessage.orEmpty())
    }

    private companion object {
        const val TAG = "LiveKitMeets"
        const val CALL_MODE_MEET = "meet"

        /** The contract's ceiling on a meet's `startCall` ack before the REST path. */
        const val START_TIMEOUT_MILLIS = 5_000L
        const val HTTP_NOT_FOUND = 404

        /** The refusals a meet's `startCall` can end in, matched inside the error text. */
        val REFUSALS = listOf(
            "meet_inactive", "no_such_meet", "meet_not_allowed", "identity_mismatch",
            "libs_moduledata_not_accepted", "caller_busy",
        )
    }
}
