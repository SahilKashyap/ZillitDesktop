package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.forward.ForwardChoice
import com.zillit.desktop.core.workspace.forward.ForwardField
import com.zillit.desktop.core.workspace.forward.ForwardFile
import com.zillit.desktop.core.workspace.forward.ForwardForm
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.feature.castboard.domain.BoardAttachment
import com.zillit.desktop.feature.castboard.domain.BoardCast
import com.zillit.desktop.feature.castboard.domain.BoardRecordDraft
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.domain.BoardUnitKind
import com.zillit.desktop.feature.castboard.domain.CastingStatus

/**
 * Casting and Wardrobe — main and background, three shortlists each.
 *
 * The web lists all four tools with the same submenu as location
 * (`generateLCWSubMenu`: Selects, Shortlist, Final) and opens each tool's own
 * create dialog, which posts a new record on that list with the forwarded
 * still on it (`handleSendLocation` for casting, `handleCreateWardrobe` for
 * wardrobe — one route per service under its own noun).
 *
 * What a record needs differs between the two, and the difference is the
 * point of the boards: a casting record *is* the character, so it is typed;
 * a costume belongs to a character who already exists, so it is picked from
 * that list's cast ([CastingRepository.casts]). Everything required on the
 * shortlists is optional on Selects, exactly as both dialogs have it.
 */
internal fun castBoardForwardTools(
    board: BoardTool,
    /** The list's unit id, from the viewer's rights — null where it is not granted. */
    unitId: (String) -> String?,
    create: suspend (String, BoardRecordDraft) -> ZillitResult<Unit>,
    casts: suspend (String) -> List<BoardCast>,
    isTelevision: () -> Boolean,
    newUniqueId: () -> String,
): List<ForwardTool> {
    val wire = CastBoardWire(unitId, create, casts, isTelevision, newUniqueId)
    return board.units.flatMap { unit ->
        CastingStatus.entries.map { status -> castBoardTool(board, unit, status, wire) }
    }
}

/** What a cast board's forward needs from the host, in one parameter. */
private class CastBoardWire(
    val unitId: (String) -> String?,
    val create: suspend (String, BoardRecordDraft) -> ZillitResult<Unit>,
    val casts: suspend (String) -> List<BoardCast>,
    val isTelevision: () -> Boolean,
    val newUniqueId: () -> String,
)

/** One list of one board at one stage, as a forward destination. */
private fun castBoardTool(
    board: BoardTool,
    unit: BoardUnitKind,
    status: CastingStatus,
    wire: CastBoardWire,
): ForwardTool = ForwardTool(
    id = castBoardTarget(unit, status),
    label = { stageLabel(status) },
    identifier = unit.identifier,
    // The web offers these four for a picture or a clip alone.
    kinds = setOf(ForwardKind.Image, ForwardKind.Video),
    group = toolLabel("${unit.identifier}_label"),
    ask = { item ->
        val unitId = wire.unitId(unit.identifier)
        when {
            item.file == null -> ForwardStepOutcome.Refused(str(S.desktop_forward_needs_a_file))
            unitId == null -> ForwardStepOutcome.Refused(str(S.desktop_forward_tool_not_granted))
            else -> castBoardForm(board, unit, status, unitId, wire)
        }
    },
    send = { item, answers ->
        val unitId = wire.unitId(unit.identifier)
        val file = item.file
        when {
            unitId == null -> ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_forward_tool_not_granted)))
            file == null -> ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_forward_needs_a_file)))
            else -> wire.create(
                unitId,
                castBoardDraft(status, answers, item.kind, file, wire.newUniqueId()),
            )
        }
    },
)

/** The questions, once the list is known — wardrobe's reads its cast first. */
private suspend fun castBoardForm(
    board: BoardTool,
    unit: BoardUnitKind,
    status: CastingStatus,
    unitId: String,
    wire: CastBoardWire,
): ForwardStepOutcome {
    val people = if (board.showsScenes) wire.casts(unitId) else emptyList()
    // A costume files under a character, and the shortlists insist on one.
    if (board.showsScenes && people.isEmpty() && status != CastingStatus.Selected) {
        return ForwardStepOutcome.Refused(str(S.desktop_forward_no_characters))
    }
    return ForwardStepOutcome.Asks(
        ForwardForm(
            targetId = castBoardTarget(unit, status),
            title = "${toolLabel("${unit.identifier}_label")} · ${stageLabel(status)}",
            fields = if (board.showsScenes) {
                wardrobeFields(status, people, wire.isTelevision())
            } else {
                castingFields(status, wire.isTelevision())
            },
        ),
    )
}

/**
 * A candidate, as `CreateAndUpdateDetails` asks for one: the character and
 * who is up for it, their gender, and the character's number. All of it is
 * required from Shortlist on and none of it on Selects, which is where a
 * still with nothing known about it yet belongs.
 */
private fun castingFields(status: CastingStatus, television: Boolean): List<ForwardField> {
    val insisted = status != CastingStatus.Selected
    return buildList {
        add(ForwardField.Text(key = FIELD_CHARACTER, label = str(S.character), required = insisted))
        add(ForwardField.Text(key = FIELD_CHARACTER_NUMBER, label = str(S.character_number)))
        add(ForwardField.Text(key = FIELD_TALENT, label = str(S.talent_name), required = insisted))
        add(
            ForwardField.Choice(
                key = FIELD_GENDER,
                label = str(S.gender),
                options = genderOptions(),
                required = insisted,
            ),
        )
        if (television) {
            add(ForwardField.Text(key = FIELD_EPISODE, label = str(S.txt_episode_number), required = insisted))
        }
        add(ForwardField.Text(key = FIELD_DESCRIPTION, label = str(S.description)))
    }
}

/**
 * A costume, as `CreateAndEditModal` asks for one: whose it is — from that
 * list's own cast, which is what `cast_id` means — and the scene it is worn
 * in. Both are required from Shortlist on.
 */
private fun wardrobeFields(
    status: CastingStatus,
    people: List<BoardCast>,
    television: Boolean,
): List<ForwardField> {
    val insisted = status != CastingStatus.Selected
    return buildList {
        add(
            ForwardField.Choice(
                key = FIELD_CAST,
                label = str(S.select_character),
                options = people.map { ForwardChoice(it.castId, it.label) },
                required = insisted,
            ),
        )
        add(ForwardField.Text(key = FIELD_SCENE, label = str(S.scene_no), required = insisted))
        if (television) {
            add(ForwardField.Text(key = FIELD_EPISODE, label = str(S.txt_episode_number), required = insisted))
        }
        add(ForwardField.Text(key = FIELD_DESCRIPTION, label = str(S.description)))
    }
}

/** The answers as the record the board's own create dialog would have sent. */
private fun castBoardDraft(
    status: CastingStatus,
    answers: Map<String, String>,
    kind: ForwardKind,
    file: ForwardFile,
    uniqueId: String,
): BoardRecordDraft = BoardRecordDraft(
    uniqueId = uniqueId,
    status = status,
    characterName = answers[FIELD_CHARACTER].orEmpty(),
    talentName = answers[FIELD_TALENT].orEmpty(),
    gender = answers[FIELD_GENDER].orEmpty(),
    characterNumber = answers[FIELD_CHARACTER_NUMBER].orEmpty(),
    description = answers[FIELD_DESCRIPTION].orEmpty(),
    sceneNumber = answers[FIELD_SCENE].orEmpty(),
    castId = answers[FIELD_CAST].orEmpty(),
    // A television production types them comma-separated; create sends a list.
    episodes = answers[FIELD_EPISODE].orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() },
    attachment = BoardAttachment(
        media = file.media,
        thumbnail = file.thumbnail?.takeIf { it.isNotBlank() } ?: file.media,
        contentType = kind.wire,
        contentSubtype = file.subtype?.trimStart('.')?.lowercase().orEmpty(),
        name = file.name,
        bucket = file.bucket.orEmpty(),
        region = file.region.orEmpty(),
        fileSize = file.sizeBytes.takeIf { it > 0 }?.toString().orEmpty(),
    ),
)

private fun castBoardTarget(unit: BoardUnitKind, status: CastingStatus): String =
    "tool:${unit.identifier}:${status.wire}"

/** The web's submenu words, which are not the board's own tab words. */
private fun stageLabel(status: CastingStatus): String = when (status) {
    CastingStatus.Selected -> str(S.selects)
    CastingStatus.Shortlisted -> str(S.shortlisted)
    CastingStatus.Finalized -> str(S.finalize)
}

/** The three the casting dialog offers, by the words the wire wants. */
private fun genderOptions(): List<ForwardChoice> = listOf(
    ForwardChoice("male", str(S.male)),
    ForwardChoice("female", str(S.female)),
    ForwardChoice("others", str(S.non_conforming)),
)
