package com.zillit.desktop.feature.recce.domain

import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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

    /** "Published · v2" or "Draft" — the web's `StatusTag`. */
    val statusLabel: String get() =
        if (isPublished) str(S.recce_status_published, "v${version.coerceAtLeast(1)}") else str(S.recce_status_draft)
}

enum class RecceStatus(val wire: String, private val labelKey: String) {
    Draft("draft", S.recce_status_draft),
    Published("published", S.cs_published),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromWire(value: String?): RecceStatus =
            entries.firstOrNull { it.wire.equals(value, ignoreCase = true) } ?: Draft
    }
}

/**
 * What a stop is for. The wire carries the label itself, capitalised;
 * [Rendezvous] and [Start] share the brand colour, [End] is the green flag.
 */
enum class StopKind(val wire: String, private val labelKey: String) {
    Rendezvous("Rendezvous", S.recce_label_rendezvous),
    Start("Start", S.start),
    Continue("Continue", S.continue_text),
    Lunch("Lunch", S.desktop_recce_stop_lunch),
    End("End", S.end),
    ;

    /** What the reader sees for the kind; [wire] is what the server stores. */
    val label: String get() = str(labelKey)

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

    val pin: LatLng? get() = if (hasPin) LatLng(lat!!, long!!) else null

    /** The hand-off every maps app opens — the web's `gmapsLink`. */
    val mapsUrl: String? get() = pin?.let { mapsLink(it) }

    val w3wUrl: String? get() = w3w.takeIf { it.isNotBlank() }?.let { "https://what3words.com/$it" }
}

/** A coordinate pair; the wire spells longitude `long`, Google spells it `lng`. */
data class LatLng(val lat: Double, val lng: Double)

/** The shareable Google Maps URL for a pin — the web's `gmapsLink(lat, long)`. */
fun mapsLink(pin: LatLng): String = "https://www.google.com/maps?q=${pin.lat},${pin.lng}"

data class ReccePerson(
    /** Set when picked from the crew; null for a typed-in guest. */
    val userId: String? = null,
    val name: String = "",
    val role: String = "",
    val email: String = "",
    val contact: String = "",
    val note: String = "",
) {
    /** The web hides the number for "Production" (a placeholder, not a phone). */
    val hasPhone: Boolean get() = contact.isNotBlank() && contact != "Production"
}

/** One crew member offered by the personnel picker (`GET recce/crew`). */
data class RecceCrewMember(
    val userId: String,
    val name: String,
    /** The designation — an i18n label key on the wire, translated for display. */
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
 * The web's `useRecceRights` is soft-permissive until the rights payload has
 * loaded (`SOFT_DEFAULT`), then keeps every button on screen — a press
 * without the right opens the "ask an admin" flow. The desktop does the
 * same: [mayPost] and [mayDownload] answer true while [ready] is false, so a
 * viewer who opens the tool before `project/tools` has landed is never
 * refused; admins pass every gate, as in every other tool.
 */
data class RecceViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canPost: Boolean = true,
    val canDownload: Boolean = true,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    /** The web bounces a view-less user back to Film Tools once rights resolve. */
    val isBlocked: Boolean get() = ready && !canView && !isAdmin
    val mayPost: Boolean get() = !ready || isAdmin || canPost
    val mayDownload: Boolean get() = !ready || isAdmin || canDownload

    companion object {
        const val TOOL_IDENTIFIER = "recce_tool"

        /** What an admin reads in the request message — the web's tool label. */
        val MODULE_LABEL: String get() = str(S.recce_title)

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
