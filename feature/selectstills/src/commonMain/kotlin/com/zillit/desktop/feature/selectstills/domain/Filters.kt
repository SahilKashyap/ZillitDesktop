package com.zillit.desktop.feature.selectstills.domain

/**
 * The gallery's filters → the query the service takes — the web's
 * `lib/filters.js`.
 *
 * Group size is a refinement of Group, not a row of its own: "group" alone is
 * too blunt on a shoot, but eight pills in one row compete for attention.
 */
enum class PhotoKind(val wire: String) {
    All("all"),
    Solo("solo"),
    Group("group"),
    Unknown("unknown"),
    NoFaces("nofaces"),
    /** What only production is offered: still being processed, and failures. */
    Processing("processing"),
    Failed("failed"),
}

/** The kinds everybody is offered, in the web's order. */
val STILLS_KINDS: List<PhotoKind> =
    listOf(PhotoKind.All, PhotoKind.Solo, PhotoKind.Group, PhotoKind.Unknown, PhotoKind.NoFaces)

/** The two more a posting user gets. */
val STILLS_POSTING_KINDS: List<PhotoKind> = listOf(PhotoKind.Processing, PhotoKind.Failed)

val STILLS_GROUP_SIZES: List<String> = listOf("2", "3", "4", "5", "6+")

/**
 * What the gallery is narrowed to.
 *
 * `agent` is production-only — the service refuses it from anybody else.
 */
data class PhotoFilters(
    val state: PublicState? = null,
    val member: String? = null,
    val agent: String? = null,
    val kind: PhotoKind = PhotoKind.All,
    /** With [kind] Group: null (any), "2".."5", "6+". */
    val size: String? = null,
    val shoot: String = "",
) {
    val isFiltered: Boolean
        get() = state != null || !member.isNullOrBlank() || !agent.isNullOrBlank() ||
            shoot.trim().isNotEmpty() || kind != PhotoKind.All
}

/**
 * The filters as query parameters.
 *
 * One branch per kind, as the service names them; a table would hide that
 * Group alone, Group with a size and Group of six-or-more are three different
 * parameters.
 */
@Suppress("CyclomaticComplexMethod")
fun PhotoFilters.toQuery(): Map<String, Any?> {
    val query = mutableMapOf<String, Any?>()
    state?.let { query["state"] = it.wire }
    member?.takeIf { it.isNotBlank() }?.let { query["member"] = it }
    agent?.takeIf { it.isNotBlank() }?.let { query["agent"] = it }
    shoot.trim().takeIf { it.isNotEmpty() }?.let { query["shoot"] = it }

    when (kind) {
        PhotoKind.Solo -> query["people"] = 1
        PhotoKind.Group -> when {
            size == "6+" -> query["people_min"] = SIX
            !size.isNullOrBlank() -> query["people"] = size.toIntOrNull()
            else -> query["group"] = true
        }
        PhotoKind.Unknown -> query["has"] = "unknown"
        PhotoKind.NoFaces -> query["has"] = "nofaces"
        PhotoKind.Failed -> query["has"] = "failed"
        PhotoKind.Processing -> query["has"] = "processing"
        PhotoKind.All -> Unit
    }
    return query
}

/** How many photos a kind / size holds, from `GET /summary`; null when it cannot say. */
fun countFor(summary: StillsSummary?, kind: PhotoKind, size: String? = null): Int? {
    if (summary == null) return null
    val sections = summary.bySection
    return when (kind) {
        PhotoKind.All -> summary.total
        PhotoKind.Solo -> sections["1"] ?: 0
        PhotoKind.Group -> if (!size.isNullOrBlank()) {
            sections[size] ?: 0
        } else {
            STILLS_GROUP_SIZES.sumOf { sections[it] ?: 0 }
        }
        PhotoKind.Unknown -> summary.needsNames
        PhotoKind.NoFaces -> summary.noPeople
        PhotoKind.Failed -> summary.failed
        PhotoKind.Processing -> summary.processing
    }
}

private const val SIX = 6
