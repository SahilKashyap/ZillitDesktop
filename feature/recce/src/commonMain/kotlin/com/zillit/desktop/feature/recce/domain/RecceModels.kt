package com.zillit.desktop.feature.recce.domain

import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * A recce: one location-scout day — a date, a rendezvous, an ordered run
 * of stops, and who is coming.
 *
 * Nothing here is uploaded: the web stores no photos and no place ids, only
 * the human strings and, where known, a coordinate pair. That is what makes
 * a map-less desktop port fully wire-compatible.
 */
data class Recce(
    val id: String,
    /** Client-generated at create, carried through every edit. */
    val uniqueId: String,
    val title: String,
    /** A join-unit id; legacy records may carry the unit's NAME instead. */
    val unit: String,
    /** Local midnight of the recce day, epoch ms; 0 when a draft has no date. */
    val dateMs: Long,
    val station: String,
    val weather: String,
    val crewNote: String,
    val rdv: RecceStop,
    val itinerary: List<RecceStop>,
    val personnel: List<ReccePerson>,
    val status: RecceStatus,
    val version: Int,
) {
    val isPublished: Boolean get() = status == RecceStatus.Published

    /** Lunch is a stop on the timeline but not a location — the web counts it out. */
    val locationCount: Int get() = itinerary.count { it.kind != StopKind.Lunch }

    /** Rows the web would show: legacy records carry blank phantom rows. */
    val realPersonnel: List<ReccePerson>
        get() = personnel.filter { p ->
            listOf(p.name, p.role, p.contact, p.note).any { it.isNotBlank() }
        }
}

enum class RecceStatus(val wire: String, val label: String) {
    Draft("draft", "Draft"),
    Published("published", "Published"),
    ;

    companion object {
        fun fromWire(value: String?): RecceStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Draft
    }
}

/**
 * What a stop is for. The wire carries the label itself, capitalised;
 * [Rendezvous] and [Start] share the brand colour, [End] is the green flag.
 */
enum class StopKind(val wire: String) {
    Rendezvous("Rendezvous"),
    Start("Start"),
    Continue("Continue"),
    Lunch("Lunch"),
    End("End"),
    ;

    companion object {
        fun fromWire(value: String?): StopKind =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Continue
    }
}

/**
 * The rendezvous and each itinerary stop share one shape. The rendezvous
 * never has a kind, an end time, or the notes — those fields stay empty.
 */
data class RecceStop(
    /** Recce day + wall clock as epoch ms; 0 when no time is set. */
    val timeMs: Long = 0L,
    val endTimeMs: Long = 0L,
    val kind: StopKind = StopKind.Continue,
    val place: String = "",
    val address: String = "",
    /** What3Words, the bare words without `///`. */
    val w3w: String = "",
    val lat: Double? = null,
    val long: Double? = null,
    val description: String = "",
    val contact: String = "",
    /** The travel note to the NEXT stop, drawn between the timeline nodes. */
    val travel: String = "",
) {
    val hasPin: Boolean get() = lat != null && long != null && lat.isFinite() && long.isFinite()

    /** The hand-off every maps app opens. */
    val mapsUrl: String? get() = if (hasPin) "https://www.google.com/maps?q=$lat,$long" else null

    val w3wUrl: String? get() = w3w.takeIf { it.isNotBlank() }?.let { "https://what3words.com/$it" }
}

data class ReccePerson(
    /** Set when picked from the crew; null for a typed-in guest. */
    val userId: String? = null,
    val name: String = "",
    val role: String = "",
    val email: String = "",
    val contact: String = "",
    val note: String = "",
)

/** One crew member offered by the personnel picker (`GET recce/crew`). */
data class RecceCrewMember(
    val userId: String,
    val name: String,
    val role: String,
    val email: String,
    val contact: String,
)

/** The recce report the server renders — a stored file to fetch and open. */
data class RecceReport(
    /** A directly usable URL, when the storage serves one. */
    val url: String?,
    val media: String,
    val bucket: String,
    val region: String,
    val name: String,
)

/**
 * The tool's rights, read from `recce_tool`.
 *
 * The web is soft-permissive until rights load, then hides nothing — buttons
 * open a request-access prompt instead. The desktop keeps the buttons and
 * gates the *action*, which is the same experience without a flash of
 * unusable chrome; admins pass every gate, as they do in every other tool.
 */
data class RecceViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = false,
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayEdit: Boolean get() = isAdmin || canPost
    val mayDownload: Boolean get() = isAdmin || canDownload

    companion object {
        const val TOOL_IDENTIFIER = "recce_tool"

        fun from(permissions: ProjectPermissions, userId: String): RecceViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return RecceViewer(userId = userId, ready = false)
            }
            return RecceViewer(
                userId = userId,
                canView = permissions.canView(TOOL_IDENTIFIER),
                canPost = permissions.canPost(TOOL_IDENTIFIER),
                canDownload = permissions.canDownload(TOOL_IDENTIFIER),
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}
