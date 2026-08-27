package com.zillit.desktop.feature.castboard.domain

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.ProjectPermissions

/**
 * One list on this production.
 *
 * The server issues each list as a tool with a unit id, and the unit id — not
 * the identifier — is what every board endpoint takes
 * (`CommonCasting.jsx:634`, `CommonWardrobe.jsx:532`).
 */
data class CastingUnit(
    val kind: BoardUnitKind,
    val unitId: String,
    val canPost: Boolean,
    /**
     * What this production calls the list — the server's own `unit_name`,
     * translated, which is the words on the tile the reader clicked. This
     * deployment calls its wardrobe lists "Costume (Main Cast)", and a screen
     * headed "Wardrobe" would be naming something they have never seen.
     *
     * Null where the server sent no name, which is why it is kept apart from
     * [label]: "the production calls it nothing" and "the production calls it
     * what we do" want different answers when titling the page.
     */
    val serverLabel: String? = null,
) {
    /** The words to show: the production's, or ours where it sent none. */
    val label: String get() = serverLabel?.takeIf { it.isNotBlank() } ?: kind.label
}

/**
 * Where a candidate has got to.
 *
 * The segmented control reads Select / Shortlist / Publish, and the wire
 * spells them `selected`, `shortlisted`, `published`
 * (`CastingPage.jsx:83-89`).
 */
enum class CastingStatus(val wire: String, val label: String) {
    Selected("selected", "Selected"),
    Shortlisted("shortlisted", "Shortlist"),
    Published("published", "Publish"),
}

/** The photo (or clip) that belongs to a casting entry. */
data class CastingMedia(
    val media: String,
    val fileName: String = "",
    val contentType: String = "",
    val bucket: String = "",
    val region: String = "",
)

/**
 * One character, and whoever is up for it.
 *
 * `talent_name` is a *list* on the wire: a character carries every candidate
 * still in the running (`CommonCasting.jsx:1031-1042`), which is the whole
 * point of a shortlist.
 */
data class CastingEntry(
    val id: String,
    val characterName: String,
    val talentNames: List<String> = emptyList(),
    val episode: String = "",
    val hierarchy: String = "",
    val gender: String = "",
    /** Wardrobe only: the scenes this costume is worn in. */
    val scenes: String = "",
    val media: CastingMedia? = null,
)

/** What this viewer may do with casting. */
data class CastingViewer(
    val units: List<CastingUnit> = emptyList(),
    /** False until `project/tools` answers — not a denial. */
    val resolved: Boolean = false,
) {

    val hasNoAccess: Boolean get() = resolved && units.isEmpty()

    companion object {

        /**
         * The units this viewer can see.
         *
         * The web gates the casting page on `casting_tool`
         * (`CastingPage.jsx:76-80`), which this production does not issue at
         * all — it issues the two unit tools instead. Gating on what is
         * actually granted shows a caster their lists rather than bouncing
         * them to the grid.
         */
        fun from(permissions: ProjectPermissions, board: BoardTool): CastingViewer {
            if (permissions.tools.isEmpty()) return CastingViewer()
            val units = board.units.mapNotNull { kind ->
                permissions.tools
                    .firstOrNull { it.identifier == kind.identifier && it.canView }
                    ?.let { access ->
                        access.unitId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { unitId ->
                                CastingUnit(
                                    kind = kind,
                                    unitId = unitId,
                                    canPost = access.canPost,
                                    // The same lookup the tools grid makes
                                    // for the tile (`ToolCatalogue.kt:205`).
                                    serverLabel = access.unitName
                                        ?.takeIf { name -> name.isNotBlank() }
                                        ?.localised(),
                                )
                            }
                    }
            }
            return CastingViewer(units = units, resolved = true)
        }
    }
}

/**
 * One line in an entry's discussion.
 *
 * Casting, wardrobe and location run this thread through one component on the
 * web (`pages/FilmTools/casting/CastingChat.jsx`), dispatching by route to
 * each tool's own service. Bodies travel AES-encrypted, as chat and the
 * boards do.
 */
data class BoardMessage(
    val id: String,
    val senderId: String,
    val body: String,
    val sentAtMillis: Long,
    val isMine: Boolean,
)
