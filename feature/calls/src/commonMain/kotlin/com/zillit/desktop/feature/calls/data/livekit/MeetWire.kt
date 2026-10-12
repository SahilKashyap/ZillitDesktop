package com.zillit.desktop.feature.calls.data.livekit

import com.zillit.desktop.feature.calls.domain.CallType
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/*
 * Line 1 MEETS — a Calendar or Box Schedule event whose `line` is "line_1".
 *
 * The event's room id (`cnc_group_id` / `cncCallGroupId`) IS the meetId, and
 * the meetId IS the callId: one string everywhere. A meet rings nobody; people
 * walk into it through the lobby, which reads the four answers below.
 */

/** Who may start the room: only its owner, or anyone allowed in. */
enum class MeetStartPolicy { Owner, Anyone }

/** `GET /v1/meet/:meetId` — the meeting itself. */
data class MeetInfo(
    val meetId: String,
    val ownerId: String = "",
    val ownerName: String = "",
    val startPolicy: MeetStartPolicy = MeetStartPolicy.Anyone,
    /** False is a CANCELLED meeting, not an unstarted one. */
    val active: Boolean = true,
    /** Null when the answer did not say — the event's own call type then stands. */
    val callType: CallType? = null,
    val title: String = "",
    val allowedUserIds: List<String> = emptyList(),
    /** Absolute epoch ms, UTC. Shown, never used to refuse a join — the server does not gate on the window. */
    val startsAt: Long? = null,
    val endsAt: Long? = null,
    /** The organiser's zone, to show their intent; times render in the viewer's. */
    val timezone: String = "",
)

/** What resolving the link found. */
sealed interface MeetLookup {
    data class Found(val info: MeetInfo) : MeetLookup

    /** 404 / `no_such_meet` — the link is dead. */
    data object NoSuchMeet : MeetLookup

    /** Anything else went wrong: logged, and the join carries on without it. */
    data class Unknown(val detail: String) : MeetLookup
}

/**
 * `GET /v1/calls/:meetId/me` — this user against the room. [inCall] is media
 * truth and prefix-matches `userId#deviceId`, so it is true when ANY of this
 * user's devices is in the room — the cue for "Switch here".
 */
data class MeetPresence(val active: Boolean, val ongoing: Boolean, val inCall: Boolean)

/** `GET /v1/calls/:meetId/active` — whether the room exists (a hidden pre-warm makes it true with nobody in it). */
data class MeetActive(val active: Boolean, val ongoing: Boolean)

/** One person already in the meeting, for the lobby's strip. */
data class MeetAttendee(
    val userId: String,
    val displayName: String,
    /** The picture's key, with its bucket and region — the bucket is per environment. */
    val picture: String = "",
    val pictureBucket: String = "",
    val pictureRegion: String = "",
    val designation: String = "",
    val isGuest: Boolean = false,
)

/** A roster read that SUCCEEDED. A failed one is null, never an empty list. */
data class MeetRosterRead(val inCall: List<MeetAttendee>)

/** Why a meet cannot be joined — the UI words each one; [detail] carries the server's word where it has one. */
enum class MeetProblem {
    NoMeeting,
    LinkInvalid,
    Cancelled,
    NotAllowed,
    AlreadyOnAnotherCall,
    NotStarted,
    /** A non-owner on an owner-started meet, with no socket to knock on. */
    AskHost,
    NoJoinDetails,
    Network,
    Other,
    KnockDeclined,
    KnockEnded,
    KnockLinkDisabled,
    KnockRefused,
    MicrophoneDenied,
}

/** The socket's `startCall` answer for a meet — step 3 of the join. */
sealed interface MeetStart {
    data class Creds(val url: String, val token: String) : MeetStart

    /** Not an error: a non-owner on an owner-started meet knocks, silently. */
    data object MustKnock : MeetStart

    data class Refused(val problem: MeetProblem, val detail: String = "") : MeetStart

    /** Socket down, timed out, refused for a reason REST may not share, or an ack with no room: PATH B. */
    data class UseRest(val why: String) : MeetStart
}

/** How a knock ended. */
sealed interface KnockAnswer {
    /** The host let us in. Joined verbatim — the admit IS the access control. */
    data class Admitted(val url: String, val token: String) : KnockAnswer

    data class Refused(val problem: MeetProblem) : KnockAnswer
}

/** `POST /v1/meet/:meetId/join`. [started] is copy only — who started the session — never a branch. */
data class MeetJoined(val started: Boolean, val credentials: LiveKitCredentials?)

/** The REST fallback's answer — PATH B. */
sealed interface MeetRest {
    data class Creds(val url: String, val token: String) : MeetRest

    data class Refused(val problem: MeetProblem, val detail: String = "") : MeetRest
}

fun readMeetInfo(meetId: String, body: JsonElement?): MeetInfo? {
    val obj = body as? JsonObject ?: return null
    return MeetInfo(
        meetId = obj.text("meetId") ?: meetId,
        ownerId = obj.text("ownerId").orEmpty(),
        ownerName = obj.text("ownerName").orEmpty(),
        startPolicy = if (obj.text("startPolicy") == "owner") MeetStartPolicy.Owner else MeetStartPolicy.Anyone,
        // Absent is not cancelled.
        active = obj.bool("active") ?: true,
        callType = when (obj.text("callType")) {
            CallType.Video.wire -> CallType.Video
            CallType.Audio.wire -> CallType.Audio
            else -> null
        },
        title = obj.text("title").orEmpty(),
        allowedUserIds = (obj["allowedUserIds"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
        startsAt = (obj["startsAt"] as? JsonPrimitive)?.longOrNull,
        endsAt = (obj["endsAt"] as? JsonPrimitive)?.longOrNull,
        timezone = obj.text("timezone").orEmpty(),
    )
}

fun readMeetJoined(body: JsonElement?): MeetJoined? {
    val obj = body as? JsonObject ?: return null
    return MeetJoined(started = obj.bool("started") == true, credentials = obj.readLiveKitCredentials())
}

fun readMeetPresence(body: JsonElement?): MeetPresence? {
    val obj = body as? JsonObject ?: return null
    return MeetPresence(
        active = obj.bool("active") ?: false,
        ongoing = obj.bool("ongoing") ?: false,
        inCall = obj.bool("inCall") ?: false,
    )
}

/**
 * The roster's people who are IN the meeting — `state == "in_call"` and
 * nothing else. The same answer carries `available` rows (project members
 * never invited, there for the in-call Add user panel); unfiltered, the lobby
 * claims half the company is in the meeting.
 */
fun readMeetAttendees(body: JsonElement?): MeetRosterRead? {
    val root = body as? JsonObject ?: return null
    val states = root["states"] as? JsonArray ?: return MeetRosterRead(emptyList())
    return MeetRosterRead(
        states.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            if (obj.text("state") != IN_CALL) return@mapNotNull null
            val userId = obj.text("userId") ?: return@mapNotNull null
            val pic = obj["profilePic"]
            MeetAttendee(
                userId = userId,
                displayName = obj.text("displayName").orEmpty(),
                picture = when (pic) {
                    is JsonPrimitive -> pic.contentOrNull.orEmpty()
                    is JsonObject -> pic.text("media").orEmpty()
                    else -> ""
                },
                pictureBucket = obj.text("profilePicBucket") ?: (pic as? JsonObject)?.text("bucket").orEmpty(),
                pictureRegion = obj.text("profilePicRegion") ?: (pic as? JsonObject)?.text("region").orEmpty(),
                designation = obj.text("designation").orEmpty(),
                isGuest = obj.bool("isGuest") ?: userId.startsWith(GUEST_PREFIX),
            )
        },
    )
}

/**
 * A server error word as the problem the lobby states. Shared by the socket
 * ack and the REST mint so the two paths say the same thing for the same word.
 */
fun meetProblemOf(word: String?): MeetProblem? = when (word?.trim()) {
    null, "" -> null
    "no_such_meet", "no_such_call" -> MeetProblem.LinkInvalid
    "meet_inactive" -> MeetProblem.Cancelled
    "meet_not_allowed", "identity_mismatch", "libs_moduledata_not_accepted",
    "not_authorized", "not_project_member",
    -> MeetProblem.NotAllowed
    "caller_busy" -> MeetProblem.AlreadyOnAnotherCall
    // The socket's answer is a knock, checked before this; only REST, which cannot knock, gets here.
    "not_allowed_to_start" -> MeetProblem.AskHost
    else -> null
}

/** `guestDeclined.reason` → the three distinct refusals, never collapsed. */
fun knockProblemOf(reason: String?): MeetProblem = when (reason?.trim()?.lowercase()) {
    "declined" -> MeetProblem.KnockDeclined
    "ended" -> MeetProblem.KnockEnded
    "linkdisabled" -> MeetProblem.KnockLinkDisabled
    else -> MeetProblem.KnockRefused
}

private const val IN_CALL = "in_call"
