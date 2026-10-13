package com.zillit.desktop.feature.castboard.domain

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
     * [label]: "the project calls it nothing" and "the production calls it
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
 * The wire spells them `selected`, `shortlisted`, `finalized`
 * (`CommonCasting.jsx:819-825`, `CommonWardrobe.jsx`, and the forward
 * submenu's `getLCWEnums`, which returns `finalized` for these two boards
 * and `published` for location alone).
 *
 * The retired `/film-tools/casting` wrapper still maps its third segment to
 * `published` (`CastingPage.jsx:99-106`); the live `main_casting` and
 * `extra_casting` routes do not, and a read with `published` answers
 * nothing — which is what this client used to send.
 */
enum class CastingStatus(val wire: String, private val labelKey: String) {
    Selected("selected", S.selected),
    Shortlisted("shortlisted", S.shortlisted),
    Finalized("finalized", S.finalize),
    ;

    val label: String get() = str(labelKey)
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

    /** Only the list a File Cabinet row names — each row there is one unit, not the board. */
    fun onlyUnit(identifier: String): CastingViewer =
        copy(units = units.filter { it.kind.identifier == identifier })

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
 * A new record on one of a board's lists.
 *
 * Casting and wardrobe post to the same route under their own noun
 * (`POST /v2/{segment}/{unitId}`) with a body each builds its own way —
 * casting's `createCastingModal(value, 'create')` and wardrobe's
 * `handleCreateWardrobe` payload. Both are this draft; which fields travel
 * is [BoardTool.showsScenes]'s to decide, because a costume files under a
 * character that already exists while a candidate *is* the character.
 */
data class BoardRecordDraft(
    val uniqueId: String,
    val status: CastingStatus,
    /** Casting: the character being cast. Wardrobe: carried only when [castId] is blank. */
    val characterName: String = "",
    val talentName: String = "",
    val gender: String = "",
    val characterNumber: String = "",
    val description: String = "",
    /** Wardrobe: the scene this costume is worn in. */
    val sceneNumber: String = "",
    /** Wardrobe: which character on the cast list this costume belongs to. */
    val castId: String = "",
    /** A television production's episodes; create sends them as an array. */
    val episodes: List<String> = emptyList(),
    val attachment: BoardAttachment? = null,
)

/** A still or clip already in the production's storage, as a record's attachment. */
data class BoardAttachment(
    val media: String,
    val thumbnail: String = "",
    val contentType: String = "",
    val contentSubtype: String = "",
    val name: String = "",
    val bucket: String = "",
    val region: String = "",
    val fileSize: String = "",
)

/**
 * One character on a wardrobe list's cast
 * (`GET /v2/wardrobe/get-casts/{unitId}`), which is what a costume files
 * under — the web's `CastPublishDataModal` picker.
 */
data class BoardCast(
    val castId: String,
    val characterName: String,
    val talentName: String = "",
) {
    /** "Rani — Priya Menon", or the character alone where no one is cast yet. */
    val label: String
        get() = listOf(characterName, talentName)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
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
