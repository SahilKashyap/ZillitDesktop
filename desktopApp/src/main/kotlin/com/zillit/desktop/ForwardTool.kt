package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.forward.ForwardChoice
import com.zillit.desktop.core.workspace.forward.ForwardField
import com.zillit.desktop.core.workspace.forward.ForwardForm
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardStep
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.core.workspace.forward.ForwardFile
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetRules
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetUpload
import com.zillit.desktop.feature.continuity.domain.ContinuityAttachment
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.location.domain.LocationDraft
import com.zillit.desktop.feature.location.domain.LocationStatus
import com.zillit.desktop.feature.location.domain.MediaAttachment
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import com.zillit.desktop.feature.home.domain.asNotice
import com.zillit.desktop.feature.pagedistribution.domain.DistributionRepository
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTab
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.domain.ListMode
import com.zillit.desktop.feature.pagedistribution.domain.PageColour
import com.zillit.desktop.feature.pagedistribution.domain.ScheduleType
import com.zillit.desktop.feature.pagedistribution.domain.StoredPdf
import com.zillit.desktop.feature.pagedistribution.domain.TabKind
import com.zillit.desktop.feature.pagedistribution.domain.UploadDraft

/**
 * The film tools a message can be forwarded INTO, and the questions each one
 * asks first.
 *
 * The web opens a modal per tool — `DocumentModal` for the distributions and
 * the budgets, `AddScene` for continuity, each tool's own create dialog for
 * casting, wardrobe and location — and posts the answers to that tool's own
 * route (`forwardUtils.js`: `generateForwardPayload` + `forwardDocument`).
 * Here each tool is one [ForwardTool]: what it is called, what it accepts,
 * the form it asks for, and the repository call that takes the answers.
 *
 * Rights and message kind decide whether a tool is offered at all, exactly as
 * `addToolsForForward` decides it: a picture may go to casting, wardrobe,
 * location and continuity; a PDF to the distributions and the budgets; and
 * nothing is offered on a tool this person cannot post to.
 */
internal class ForwardTool(
    val id: String,
    val label: () -> String,
    /** The tool identifier the viewer's rights are read from. */
    val identifier: String,
    /** Which message kinds this destination takes — the web's `toolsToBeIncluded`. */
    val kinds: Set<ForwardKind>,
    /** The questions, built when the entry is clicked (some read a server list). */
    val ask: suspend (ForwardItem) -> ForwardStepOutcome,
    /** The answers, on their way to the tool's own route. */
    val send: suspend (ForwardItem, Map<String, String>) -> ZillitResult<Unit>,
    /** The heading this tool sits under when it has siblings — the web's submenu. */
    val group: String? = null,
    /**
     * A document tool that takes PDFs alone. The web splits `document` in
     * two: a PDF is offered the distributions and the budgets, anything else
     * only the notes boards (`addToolsForForward`, `content_subtype === 'pdf'`),
     * so a .docx must not even be listed for them.
     */
    val pdfOnly: Boolean = false,
) {
    fun takes(item: ForwardItem): Boolean =
        item.kind in kinds && (!pdfOnly || item.file?.isPdf == true)

    /** The picker's row for this tool. */
    fun entry(): ForwardStep = ForwardStep(id = id, label = label())
}

/**
 * The distributions — D.O.D, Script (Full Script, Pages) and Schedule
 * (Schedule Full, Pages, One Line).
 *
 * All three are one engine on the desktop ([DistributionRepository]), and a
 * forward is the upload it already knows how to do with the file left where
 * it is: `upload(tab, draft, stored, now)` sends the same body the tool's own
 * dialog sends, which is why the fields below are [UploadDraft]'s and not a
 * second copy of the wire.
 */
internal fun distributionForwardTools(
    repository: DistributionRepository,
    isTelevision: () -> Boolean,
    nowMillis: () -> Long,
): List<ForwardTool> = buildList {
    DistributionTool.ScheduleDod.tabs.forEach { tab ->
        add(distributionTool(DistributionTool.ScheduleDod, tab, repository, isTelevision, nowMillis))
    }
    DistributionTool.ScriptDistribution.tabs.forEach { tab ->
        add(
            distributionTool(DistributionTool.ScriptDistribution, tab, repository, isTelevision, nowMillis),
        )
    }
    DistributionTool.ScheduleDistribution.tabs.forEach { tab ->
        add(
            distributionTool(DistributionTool.ScheduleDistribution, tab, repository, isTelevision, nowMillis),
        )
    }
}

/** One tab of one distribution tool, as a forward destination. */
private fun distributionTool(
    tool: DistributionTool,
    tab: DistributionTab,
    repository: DistributionRepository,
    isTelevision: () -> Boolean,
    nowMillis: () -> Long,
): ForwardTool = ForwardTool(
    id = "tool:${tool.toolIdentifier}:${tab.key}",
    label = { tab.label },
    identifier = tool.toolIdentifier,
    // Only a PDF: every one of these tabs holds documents, and the web
    // offers them for `content_subtype === 'pdf'` alone.
    kinds = setOf(ForwardKind.Document),
    // D.O.D has one tab, so its entry stands alone; the other two carry
    // their tabs under the tool's own name, as the web's submenus do.
    group = tool.title.takeIf { tool.tabs.size > 1 },
    pdfOnly = true,
    ask = { item ->
        if (!item.isPdf()) {
            ForwardStepOutcome.Refused(str(S.desktop_forward_pdf_only))
        } else {
            ForwardStepOutcome.Asks(
                ForwardForm(
                    targetId = "tool:${tool.toolIdentifier}:${tab.key}",
                    title = "${tool.title} · ${tab.label}",
                    note = str(S.desktop_forward_tool_note, tool.title),
                    fields = distributionFields(tool, tab, isTelevision()),
                ),
            )
        }
    },
    send = { item, answers ->
        val stored = item.file?.asStoredPdf()
        if (stored == null) {
            ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_forward_pdf_only)))
        } else {
            repository.upload(
                tab = tab,
                draft = UploadDraft(
                    fileName = stored.name,
                    bytes = ByteArray(0),
                    dateYmd = answers[FIELD_DATE].orEmpty(),
                    episode = answers[FIELD_EPISODE].orEmpty(),
                    sceneNumber = answers[FIELD_SCENE].orEmpty(),
                    pageNumber = answers[FIELD_PAGE].orEmpty(),
                    colour = PageColour.entries
                        .firstOrNull { it.name == answers[FIELD_COLOUR] } ?: PageColour.White,
                    scheduleType = ScheduleType.entries
                        .firstOrNull { it.name == answers[FIELD_SCHEDULE_TYPE] },
                    name = answers[FIELD_NAME].orEmpty(),
                    nameFromPick = answers[FIELD_NAME_PICKED] == "1",
                ),
                stored = stored,
                nowMs = nowMillis(),
            ).map { Unit }
        }
    },
)

/**
 * The questions one tab asks — the web's `DocumentModal` with the flags that
 * tab sets: a folder tab wants a scene, a page number and a colour; a single
 * tab wants the document's name and its date; D.O.D wants a folder name; and
 * a television production wants the episode everywhere.
 */
private fun distributionFields(
    tool: DistributionTool,
    tab: DistributionTab,
    television: Boolean,
): List<ForwardField> = buildList {
    when (val kind = tab.kind) {
        is TabKind.Single -> {
            add(ForwardField.Text(key = FIELD_NAME, label = str(S.desktop_document_name), required = true))
            add(ForwardField.Day(key = FIELD_DATE, label = str(S.date)))
        }
        is TabKind.Folders -> {
            if (kind.folderKey == FolderKeyName) {
                add(ForwardField.Text(key = FIELD_NAME, label = str(S.drive_cd_folder), required = true))
            } else {
                add(ForwardField.Text(key = FIELD_SCENE, label = str(S.txt_scene_number), required = true))
                add(ForwardField.Text(key = FIELD_PAGE, label = str(S.desktop_dist_page_number)))
            }
            add(
                ForwardField.Choice(
                    key = FIELD_COLOUR,
                    label = str(S.desktop_dist_page_colour_optional),
                    options = colourOptions(),
                ),
            )
            add(ForwardField.Day(key = FIELD_DATE, label = str(S.date)))
            if (tool.sendsScheduleType && kind.scheduleTypeChoice) {
                add(
                    ForwardField.Choice(
                        key = FIELD_SCHEDULE_TYPE,
                        label = str(S.desktop_dist_schedule_type),
                        options = ScheduleType.entries.map { ForwardChoice(it.name, it.label) },
                    ),
                )
            }
        }
    }
    if (television) {
        add(ForwardField.Text(key = FIELD_EPISODE, label = str(S.txt_episode_number), required = true))
    }
}

/**
 * The notes boards, for anything that is not plain words.
 *
 * Text goes straight in and they are listed among the boards; a document or
 * a picture is asked about first — the web's `showNewOrContinuationPopup`,
 * where *New* retires what is already on the board and *Continuation* adds
 * to it (`replacePreviousChats`).
 */
internal fun notesBoardForwardTools(boards: List<ForwardBoard>, newLocalId: () -> String): List<ForwardTool> =
    boards.filter { it.kinds == setOf(ForwardKind.Text) }.map { board ->
        ForwardTool(
            id = "notes:${board.key}",
            label = { board.title() },
            identifier = board.identifier,
            // A document, and only a document: the web offers these three
            // for `text` (where the board takes it as a Pick, above) and for
            // `document`, pdf or not — never for a picture or a clip, which
            // go to casting, wardrobe, location and continuity instead
            // (`addToolsForForward`).
            kinds = setOf(ForwardKind.Document),
            ask = { _ ->
                val units = board.units().filter { it.enabled && it.canPost }
                if (units.isEmpty()) {
                    ForwardStepOutcome.Refused(str(S.desktop_board_no_posting_rights_chip))
                } else {
                    ForwardStepOutcome.Asks(
                        ForwardForm(
                            targetId = "notes:${board.key}",
                            title = board.title(),
                            note = str(S.desktop_forward_continuation_question),
                            fields = listOf(
                                ForwardField.Choice(
                                    key = FIELD_CONTINUATION,
                                    label = board.title(),
                                    options = listOf(
                                        ForwardChoice(CONTINUATION, str(S.continuation)),
                                        ForwardChoice(NEW_ONE, str(S.continue_new)),
                                    ),
                                ),
                            ) + unitField(units),
                        ),
                    )
                }
            },
            send = { item, answers ->
                val units = board.units().filter { it.enabled && it.canPost }
                val unitId = answers[FIELD_UNIT]?.takeIf { it.isNotBlank() } ?: units.firstOrNull()?.id
                if (unitId == null) {
                    ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_board_no_posting_rights_chip)))
                } else {
                    board.repository.forwardNotice(
                        unitId = unitId,
                        notice = item.asNotice(),
                        localId = newLocalId(),
                        replacePrevious = answers[FIELD_CONTINUATION] == NEW_ONE,
                    )
                }
            },
        )
    }

/** Which of a board's units, when it has more than one to choose between. */
private fun unitField(units: List<HomeUnit>): List<ForwardField> =
    if (units.size < 2) {
        emptyList()
    } else {
        listOf(
            ForwardField.Choice(
                key = FIELD_UNIT,
                label = str(S.desktop_section_units),
                options = units.map { ForwardChoice(it.id, it.label) },
            ),
        )
    }

/**
 * The budgets — Main and Department.
 *
 * The web asks for a date (and, on a television production, an episode), and
 * for a department on the department budget; the title it saves is built
 * from those (`generateBudgetTitle`), which [BudgetRules.uploadTitle] already
 * spells the same way for the tool's own upload dialog.
 */
internal fun budgetForwardTools(
    post: suspend (BudgetUpload) -> ZillitResult<Unit>,
    departments: suspend () -> List<ForwardChoice>,
    isTelevision: () -> Boolean,
): List<ForwardTool> = listOf(
    budgetTool(BudgetType.Main, "main_budget_tool", post, departments, isTelevision),
    budgetTool(BudgetType.Department, "department_budget_tool", post, departments, isTelevision),
)

private fun budgetTool(
    type: BudgetType,
    identifier: String,
    post: suspend (BudgetUpload) -> ZillitResult<Unit>,
    departments: suspend () -> List<ForwardChoice>,
    isTelevision: () -> Boolean,
): ForwardTool = ForwardTool(
    id = "tool:$identifier",
    label = { toolLabel("${identifier}_label") },
    identifier = identifier,
    kinds = setOf(ForwardKind.Document),
    pdfOnly = true,
    ask = { item ->
        when {
            !item.isPdf() -> ForwardStepOutcome.Refused(str(S.desktop_forward_pdf_only))
            else -> {
                val fields = budgetFields(type, departments(), isTelevision())
                if (type == BudgetType.Department && fields.none { it.key == FIELD_DEPARTMENT }) {
                    // The web throws `DepartmentIsRequired`; with no
                    // departments at all there is nothing to answer with.
                    ForwardStepOutcome.Refused(str(S.desktop_cr_no_departments))
                } else {
                    ForwardStepOutcome.Asks(
                        ForwardForm(
                            targetId = "tool:$identifier",
                            title = toolLabel("${identifier}_label"),
                            fields = fields,
                        ),
                    )
                }
            }
        }
    },
    send = { item, answers ->
        val file = item.file
        val dayMillis = answers[FIELD_DATE].orEmpty().startOfDayMillis()
        when {
            file == null -> ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_forward_pdf_only)))
            dayMillis == null -> ZillitResult.Failure(ZillitError.Validation(str(S.date)))
            else -> post(
                BudgetUpload(
                    type = type,
                    file = BudgetFile(
                        media = file.media,
                        name = file.name,
                        contentType = file.contentType.orEmpty(),
                        bucket = file.bucket.orEmpty(),
                        region = file.region.orEmpty(),
                        sizeBytes = file.sizeBytes,
                        thumbnail = file.thumbnail.orEmpty(),
                    ),
                    title = BudgetRules.uploadTitle(
                        type = type,
                        departmentName = answers[FIELD_DEPARTMENT_NAME].orEmpty(),
                        dateLabel = BudgetRules.dateLabel(dayMillis),
                    ),
                    departmentId = answers[FIELD_DEPARTMENT].orEmpty(),
                    episode = answers[FIELD_EPISODE].orEmpty(),
                ),
            )
        }
    },
)

/**
 * What a budget asks for: the department on a department budget, a date
 * always (the web throws `Daterequired` without one), and an episode on a
 * television production (`episode_number_required`).
 */
private fun budgetFields(
    type: BudgetType,
    departments: List<ForwardChoice>,
    television: Boolean,
): List<ForwardField> = buildList {
    if (type == BudgetType.Department && departments.isNotEmpty()) {
        add(
            ForwardField.Choice(
                key = FIELD_DEPARTMENT,
                label = str(S.department),
                options = departments,
                required = true,
            ),
        )
    }
    add(ForwardField.Day(key = FIELD_DATE, label = str(S.date), required = true))
    if (television) {
        add(ForwardField.Text(key = FIELD_EPISODE, label = str(S.txt_episode_number), required = true))
    }
}

/**
 * Continuity — the web's `AddScene` before `handleSendContinuity`: a scene
 * number and the notes that go with it, and the picture or clip becomes that
 * scene's first record.
 */
internal fun continuityForwardTool(
    create: suspend (SceneDraft, ContinuityAttachment, String) -> ZillitResult<Unit>,
    newUniqueId: () -> String,
): ForwardTool = ForwardTool(
    id = "tool:continuity_tool",
    label = { toolLabel("continuity_label") },
    identifier = "continuity_tool",
    // The web offers continuity for pictures and clips alone.
    kinds = setOf(ForwardKind.Image, ForwardKind.Video),
    ask = {
        ForwardStepOutcome.Asks(
            ForwardForm(
                targetId = "tool:continuity_tool",
                title = toolLabel("continuity_label"),
                fields = listOf(
                    ForwardField.Text(key = FIELD_SCENE, label = str(S.txt_scene_number), required = true),
                    ForwardField.Text(key = FIELD_NOTES, label = str(S.desktop_scene_notes)),
                ),
            ),
        )
    },
    send = { item, answers ->
        val file = item.file
        if (file == null) {
            ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_forward_needs_a_file)))
        } else {
            create(
                SceneDraft(
                    sceneNumber = answers[FIELD_SCENE].orEmpty(),
                    notes = answers[FIELD_NOTES].orEmpty(),
                ),
                ContinuityAttachment(
                    media = file.media,
                    thumbnail = file.thumbnail?.takeIf { it.isNotBlank() } ?: file.media,
                    contentType = item.kind.wire,
                    contentSubtype = file.subtype?.trimStart('.')?.lowercase().orEmpty(),
                    name = file.name,
                    bucket = file.bucket.orEmpty(),
                    region = file.region.orEmpty(),
                    fileSize = file.sizeBytes.takeIf { it > 0 }?.toString().orEmpty(),
                ),
                newUniqueId(),
            )
        }
    },
)

/**
 * Location — one destination per shortlist, as the web's submenu offers
 * Selects, Shortlist and Final (`generateLCWSubMenu`), each creating a
 * record there with the forwarded picture on it. Only the name is required,
 * exactly as the tool's own create dialog has it.
 */
internal fun locationForwardTools(
    create: suspend (LocationDraft, MediaAttachment) -> ZillitResult<Unit>,
): List<ForwardTool> = LocationStatus.entries.map { status ->
    ForwardTool(
        id = "tool:location_tool:${status.wire}",
        label = { status.label },
        identifier = "location_tool",
        kinds = setOf(ForwardKind.Image, ForwardKind.Video),
        group = toolLabel("location_label"),
        ask = {
            ForwardStepOutcome.Asks(
                ForwardForm(
                    targetId = "tool:location_tool:${status.wire}",
                    title = "${toolLabel("location_label")} · ${status.label}",
                    fields = listOf(
                        ForwardField.Text(
                            key = FIELD_NAME,
                            label = str(S.location_name),
                            required = true,
                        ),
                        ForwardField.Text(key = FIELD_SCENE, label = str(S.txt_scene_number)),
                        ForwardField.Text(key = FIELD_CITY, label = str(S.city)),
                    ),
                ),
            )
        },
        send = { item, answers ->
            val file = item.file
            if (file == null) {
                ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_forward_needs_a_file)))
            } else {
                create(
                    LocationDraft(
                        location = answers[FIELD_NAME].orEmpty(),
                        sceneNumber = answers[FIELD_SCENE].orEmpty(),
                        city = answers[FIELD_CITY].orEmpty(),
                        status = status,
                    ),
                    MediaAttachment(
                        media = file.media,
                        thumbnail = file.thumbnail?.takeIf { it.isNotBlank() } ?: file.media,
                        contentType = item.kind.wire,
                        contentSubtype = file.subtype?.trimStart('.')?.lowercase().orEmpty(),
                        name = file.name,
                        bucket = file.bucket.orEmpty(),
                        region = file.region.orEmpty(),
                        fileSize = file.sizeBytes.takeIf { it > 0 }?.toString().orEmpty(),
                    ),
                )
            }
        },
    )
}

/** `yyyy-MM-dd` as the day's first millisecond here, or null when it is not a day. */
private fun String.startOfDayMillis(zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
    val date = runCatching { LocalDate.parse(trim()) }.getOrNull() ?: return null
    return date.atStartOfDayIn(zone).toEpochMilliseconds()
}

/** The page colours, each with its own dot. */
private fun colourOptions(): List<ForwardChoice> =
    PageColour.entries.map { ForwardChoice(value = it.name, label = it.label, swatch = it.hex) }

/** The forwarded file as the distributions' already-stored PDF. */
private fun ForwardFile.asStoredPdf(): StoredPdf? {
    if (media.isBlank()) return null
    return StoredPdf(
        media = media,
        name = name,
        bucket = bucket.orEmpty(),
        region = region.orEmpty(),
        fileSize = sizeBytes.takeIf { it > 0 }?.toString().orEmpty(),
        thumbnail = thumbnail?.takeIf { it.isNotBlank() } ?: media,
        contentSubtype = subtype?.trimStart('.')?.lowercase() ?: "pdf",
    )
}

/** Whether this is the one kind of file the distributions take. */
private fun ForwardItem.isPdf(): Boolean = file?.isPdf == true

internal const val FIELD_NAME = "name"
internal const val FIELD_NAME_PICKED = "namePicked"
internal const val FIELD_DATE = "date"
internal const val FIELD_EPISODE = "episode"
internal const val FIELD_SCENE = "scene"
internal const val FIELD_PAGE = "page"
internal const val FIELD_COLOUR = "colour"
internal const val FIELD_SCHEDULE_TYPE = "scheduleType"
internal const val FIELD_CONTINUATION = "continuation"
internal const val FIELD_UNIT = "unit"
internal const val FIELD_DEPARTMENT = "department"
internal const val FIELD_DEPARTMENT_NAME = "departmentName"
internal const val FIELD_NOTES = "notes"
internal const val FIELD_CITY = "city"
internal const val FIELD_CHARACTER = "character"
internal const val FIELD_CHARACTER_NUMBER = "characterNumber"
internal const val FIELD_TALENT = "talent"
internal const val FIELD_GENDER = "gender"
internal const val FIELD_CAST = "cast"
internal const val FIELD_DESCRIPTION = "description"
private const val CONTINUATION = "continuation"
private const val NEW_ONE = "new"

/** D.O.D's folders are named, not numbered — see `FolderKey`. */
private val FolderKeyName = com.zillit.desktop.feature.pagedistribution.domain.FolderKey.Name

/** A tool's label from the production's dictionary, where it has one. */
internal fun toolLabel(key: String): String = Labels.translate(key)
