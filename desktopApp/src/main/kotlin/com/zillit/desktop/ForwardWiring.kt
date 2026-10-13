package com.zillit.desktop

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.config.ZillitService
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.forward.ForwardChoice
import com.zillit.desktop.core.workspace.forward.ForwardGroup
import com.zillit.desktop.core.workspace.forward.ForwardItem
import com.zillit.desktop.core.workspace.forward.ForwardKind
import com.zillit.desktop.core.workspace.forward.ForwardPick
import com.zillit.desktop.core.workspace.forward.ForwardReach
import com.zillit.desktop.core.workspace.forward.ForwardSection
import com.zillit.desktop.core.workspace.forward.ForwardStep
import com.zillit.desktop.core.workspace.forward.ForwardStepOutcome
import com.zillit.desktop.feature.castboard.data.CastingRepositoryImpl
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.chat.data.ChatRepository
import com.zillit.desktop.feature.chat.domain.ChatLocation
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupRoom
import com.zillit.desktop.feature.chat.domain.asChatAttachment
import com.zillit.desktop.feature.email.domain.StoredFile
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.home.data.HomeFeedRepositoryImpl
import com.zillit.desktop.feature.home.domain.HomeFeedRepository
import com.zillit.desktop.feature.home.domain.HomeUnit
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.domain.asNotice
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.UUID

/**
 * Everywhere a message can be forwarded to, assembled where every tool is
 * visible at once — the host.
 *
 * This is the desktop's shape of the web's forward modal
 * (`ForwardMsgModal.jsx`): its list is Home Tab, Info Tab, Confidential Info
 * Tab, Chat User, Group Chat and Email, plus the film tools the message's
 * type and the viewer's rights allow (`addToolsForForward`). Each surface
 * contributes the destinations it owns (a board its own units, a thread its
 * own people and rooms) and asks this for the rest.
 *
 * **Where the forms live.** The notes boards that take a *text* message —
 * Script Notes, Camera & Sound Report, Production Report Chat — are ordinary
 * boards on another service, and the web posts a forwarded text line
 * straight into them (`handleSendScriptNotes`, `handleSendReports`,
 * `handleSendProductionReport`: `case 'text'` sends, everything else opens a
 * form). So text reaches them as ordinary board rows here. Every destination
 * whose forward *is* a form — a document to those same boards, the
 * distributions, the budgets, continuity, location, casting and wardrobe —
 * is a [ForwardTool], which answers a click with the questions that tool
 * asks and posts the answers through the tool's own repository.
 */
internal class AppForwardReach(
    private val boards: List<ForwardBoard>,
    /** The film tools, each with the form it asks for and the wire it posts to. */
    private val tools: List<ForwardTool> = emptyList(),
    /** The viewer's rights, read live — a tool switched off mid-session stops being offered. */
    private val permissions: () -> ProjectPermissions = { ProjectPermissions.Empty },
    private val crew: () -> List<CrewContact>,
    private val rooms: suspend () -> List<GroupRoom>,
    private val chat: () -> ChatRepository?,
    private val isAdmin: () -> Boolean,
    private val nowMillis: () -> Long,
    /** Hands the item to the mailbox composer; null leaves Email out. */
    private val email: ((ForwardItem) -> String?)?,
) : ForwardReach {

    override suspend fun sections(item: ForwardItem, from: String?): List<ForwardSection> = buildList {
        // One heading for every board, the asking surface's own units first
        // (it adds them itself, under this same title). A heading per board,
        // each with a single row under it, is unreadable — and a caption
        // repeating that heading, which is what it looked like, is worse.
        // Several of these boards fetch their units over the wire. In turn
        // that is three round trips before the picker can show anything;
        // together it is one, and the list is in the order the boards were
        // declared either way.
        val boardEntries = coroutineScope {
            boards
                .filter { it.key != from && it.takes(item) }
                .map { board -> async { board.entries(isAdmin()) } }
                .awaitAll()
                .flatten()
        }
        if (boardEntries.isNotEmpty()) add(ForwardSection(str(S.desktop_forward_boards), boardEntries))
        if (from != CHAT_SURFACE_KEY) chatSections().forEach(::add)
        toolSection(item)?.let(::add)
        email?.let {
            add(
                ForwardSection(
                    title = str(S.email),
                    entries = listOf(
                        ForwardStep(
                            id = EMAIL_TARGET,
                            label = str(S.desktop_forward_as_email),
                            icon = ZillitIcons.Mail,
                        ),
                    ),
                ),
            )
        }
    }

    /**
     * The film tools this message may go into, rights and kind applied — the
     * web's `addToolsForForward`.
     *
     * A tool with several destinations inside it (a distribution's tabs)
     * becomes a list that opens, which is what the web's `isSubMenu` tools
     * do; a tool with one becomes a single row.
     */
    private fun toolSection(item: ForwardItem): ForwardSection? {
        val offered = tools.filter { it.takes(item) && canPost(it.identifier) }
        if (offered.isEmpty()) return null
        val entries = buildList {
            offered.groupBy { it.group }.forEach { (group, inGroup) ->
                if (group == null) {
                    inGroup.forEach { add(it.entry()) }
                } else {
                    add(
                        ForwardGroup(
                            id = "toolgroup:$group",
                            label = group,
                            icon = ZillitIcons.Tools,
                            children = inGroup.map { it.entry() },
                        ),
                    )
                }
            }
        }
        return ForwardSection(str(S.tools), entries)
    }

    /** The web's gate on every tool entry: enabled, viewable and postable. */
    private fun canPost(identifier: String): Boolean {
        val access = permissions().access(identifier)
        return access.enabled && access.canView && access.canPost
    }

    /**
     * The chat halves, for a surface that is not the chat — each a list that
     * opens, as the web lists "Chat User" and "Group Chat" as two entries
     * that open their own pickers. A production's crew is long enough that
     * spilling it into the dialog would bury everything under it.
     */
    private suspend fun chatSections(): List<ForwardSection> {
        if (chat() == null) return emptyList()
        val people = crew().filter { it.inDirectory && !it.hasLeft }
        val roomList = rooms()
        val entries = buildList {
            if (roomList.isNotEmpty()) {
                add(
                    ForwardGroup(
                        id = "chat-rooms",
                        label = str(S.groups_txt),
                        icon = ZillitIcons.Users,
                        caption = str(S.desktop_forward_n_groups, roomList.size),
                        children = roomList.map { room ->
                            ForwardPick(
                                id = "$CHAT_ROOM_TARGET${room.id}",
                                label = room.displayName,
                                icon = ZillitIcons.Users,
                            )
                        },
                    ),
                )
            }
            if (people.isNotEmpty()) {
                add(
                    ForwardGroup(
                        id = "chat-people",
                        label = str(S.section_people),
                        icon = ZillitIcons.User,
                        caption = str(S.dd_n_people, people.size),
                        children = people.map { person ->
                            ForwardPick(
                                id = "$CHAT_USER_TARGET${person.userId}",
                                label = person.fullName,
                                // Role and department both, because the
                                // picker searches the caption — the chat's
                                // own `searchCrew` matched all three fields.
                                caption = listOfNotNull(person.designation, person.department)
                                    .distinct()
                                    .joinToString(" · ")
                                    .takeIf { it.isNotBlank() },
                                avatarUserId = person.userId,
                            )
                        },
                    ),
                )
            }
        }
        return if (entries.isEmpty()) emptyList() else listOf(ForwardSection(str(S.desktop_chat_calls), entries))
    }

    override suspend fun deliver(targetIds: List<String>, item: ForwardItem): ZillitResult<Unit> {
        var refusal: ZillitError? = null
        targetIds.forEach { id ->
            val answer = when {
                id.startsWith(BOARD_TARGET) -> deliverToBoard(id, item)
                id.startsWith(CHAT_USER_TARGET) -> deliverToChat(id.removePrefix(CHAT_USER_TARGET), false, item)
                id.startsWith(CHAT_ROOM_TARGET) -> deliverToChat(id.removePrefix(CHAT_ROOM_TARGET), true, item)
                else -> ZillitResult.Failure(ZillitError.Unknown("unknown forward destination"))
            }
            if (answer is ZillitResult.Failure) refusal = answer.error
        }
        return refusal?.let { ZillitResult.Failure(it) } ?: ZillitResult.Success(Unit)
    }

    /** `POST <board>/chat` on the destination board's own service. */
    private suspend fun deliverToBoard(targetId: String, item: ForwardItem): ZillitResult<Unit> {
        val rest = targetId.removePrefix(BOARD_TARGET)
        val boardKey = rest.substringBefore(':')
        val unitId = rest.substringAfter(':', missingDelimiterValue = "")
        val board = boards.firstOrNull { it.key == boardKey }
            ?: return ZillitResult.Failure(ZillitError.Unknown("unknown board"))
        if (unitId.isBlank()) return ZillitResult.Failure(ZillitError.Unknown("no unit"))
        return board.repository.forwardNotice(unitId, item.asNotice(), UUID.randomUUID().toString())
    }

    /** The thread's own send, with a fresh unique id — the web's forward payload. */
    private suspend fun deliverToChat(
        receiverId: String,
        isGroup: Boolean,
        item: ForwardItem,
    ): ZillitResult<Unit> {
        val repository = chat() ?: return ZillitResult.Failure(ZillitError.Unknown("no chat"))
        return repository.send(
            receiverId = receiverId,
            body = item.text,
            uniqueId = UUID.randomUUID().toString(),
            nowMillis = nowMillis(),
            isGroup = isGroup,
            attachment = item.file?.asChatAttachment(),
            location = item.place?.let { ChatLocation(lat = it.latitude, lng = it.longitude) },
        )
    }

    override suspend fun open(targetId: String, item: ForwardItem): ForwardStepOutcome {
        if (targetId == EMAIL_TARGET) {
            val refusal = email?.invoke(item) ?: str(S.desktop_email_id_not_available)
            return refusal?.let(ForwardStepOutcome::Refused) ?: ForwardStepOutcome.Handled
        }
        val tool = tools.firstOrNull { it.id == targetId }
            ?: return ForwardStepOutcome.Refused(str(S.desktop_forward_destination_unavailable))
        return tool.ask(item)
    }

    override suspend fun submit(
        targetId: String,
        item: ForwardItem,
        answers: Map<String, String>,
    ): ZillitResult<Unit> {
        val tool = tools.firstOrNull { it.id == targetId }
            ?: return ZillitResult.Failure(ZillitError.Unknown(str(S.desktop_forward_destination_unavailable)))
        return tool.send(item, answers)
    }
}

/**
 * One board a forward can land on: what it is called, which units it has, and
 * the repository that posts into them.
 *
 * [kinds] is the web's `toolsToBeIncluded` for this destination — null means
 * "any message", which is what Home, Info and Confidential Info are.
 */
internal class ForwardBoard(
    val key: String,
    val title: () -> String,
    val units: suspend () -> List<HomeUnit>,
    val repository: HomeFeedRepository,
    val kinds: Set<ForwardKind>? = null,
    /** The tool identifier its rights are read from; blank for a board every production has. */
    val identifier: String = "",
) {
    fun takes(item: ForwardItem): Boolean = kinds == null || item.kind in kinds

    /** This board's units as picker rows, rights and all. */
    suspend fun entries(isAdmin: Boolean): List<ForwardPick> {
        val name = title()
        return units()
            .filter { it.enabled && (it.canView || isAdmin) }
            // The calendar is not a board: there is nothing to post to.
            .filter { it.kind != HomeUnitKind.Calendar }
            .map { unit ->
                ForwardPick(
                    id = boardTarget(key, unit.id),
                    label = unit.label,
                    // A warning first, then the board's name where it says
                    // something the unit's own name does not — "Selected
                    // Takes" under Script Notes, but not "Info" under "Info".
                    caption = if (!unit.canPost && !isAdmin) {
                        str(S.desktop_board_no_posting_rights_chip)
                    } else {
                        name.takeIf { !it.equals(unit.label, ignoreCase = true) }
                    },
                    icon = ZillitIcons.Chat,
                )
            }
    }
}

/**
 * The reach as the app wires it: the three notice boards every production
 * has, the notes boards a text line can reach, the chat halves, and the
 * mailbox.
 *
 * Rights ride each board's own units call — the same one its tool uses — so
 * a tool that is off, or that this user cannot post to, contributes nothing
 * or contributes units marked as such.
 */
internal fun AppGraph.Ready.forwardReach(
    permissions: () -> ProjectPermissions,
    email: ((ForwardItem) -> String?)?,
): ForwardReach {
    val admin = { projectContext?.context?.value?.isAdmin == true }
    val boards = buildList {
        add(
            ForwardBoard(
                key = HOME_BOARD,
                title = { Labels.translate("home_label") },
                units = { homeBoardUnits() },
                repository = homeFeedRepository,
            ),
        )
        add(toolBoard("info", "info_label", "info_tool", permissions))
        add(
            toolBoard(
                board = "confidentialinfo",
                labelKey = "confidential_info_label",
                identifier = "confidential_info_tool",
                permissions = permissions,
            ),
        )
        // Text only, for now — see the note on AppForwardReach.
        add(
            serviceBoard(
                board = "script-notes",
                labelKey = "script_notes_label",
                identifier = "script_notes_tool",
                unitsRoute = "script-notes/unit",
                permissions = permissions,
            ),
        )
        add(
            serviceBoard(
                board = "reports",
                labelKey = "reports_label",
                identifier = "reports_tool",
                unitsRoute = "reports/unit/",
                permissions = permissions,
            ),
        )
        add(productionReportChatBoard(permissions))
    }
    return AppForwardReach(
        boards = boards,
        tools = forwardTools(boards, permissions),
        permissions = permissions,
        crew = { forwardCrew() },
        rooms = { forwardRooms() },
        chat = { chatRepository },
        isAdmin = admin,
        nowMillis = System::currentTimeMillis,
        email = email,
    )
}

/**
 * The crew as the picker lists them — the same shape the chat's own people
 * list uses, so a name reads the same in both (a private name or a pending
 * join keeps someone out of the directory, and the picker respects that).
 */
private fun AppGraph.Ready.forwardCrew(): List<CrewContact> =
    projectContext?.context?.value?.users.orEmpty()
        .filter { it.fullName.isNotBlank() }
        .map { user ->
            CrewContact(
                userId = user.userId,
                fullName = user.fullName,
                designation = user.designationText(),
                department = user.departmentText(),
                inDirectory = !user.keepNamePrivate && user.hasJoined(),
                hasLeft = user.status == "left" || user.status == "removed",
            )
        }

/** The production's rooms, or none when the listing refuses — a picker is not the place to say so. */
private suspend fun AppGraph.Ready.forwardRooms(): List<GroupRoom> =
    when (val answer = chatRepository.rooms()) {
        is ZillitResult.Success -> answer.data
        is ZillitResult.Failure -> emptyList()
    }

/**
 * The film tools a message can be forwarded into: the distributions, the
 * notes boards for anything that is not words, the budgets, continuity,
 * location, casting and wardrobe — the whole of `addToolsForForward`'s
 * table. See [ForwardTool] for what each one asks and where it posts.
 */
private fun AppGraph.Ready.forwardTools(
    boards: List<ForwardBoard>,
    permissions: () -> ProjectPermissions,
): List<ForwardTool> =
    distributionForwardTools(
        repository = com.zillit.desktop.feature.pagedistribution.data.DistributionRepositoryImpl(
            apiClient = apiClient,
            config = config,
            newUniqueId = { UUID.randomUUID().toString() },
            nowMillis = System::currentTimeMillis,
        ),
        isTelevision = { isTelevisionProduction() },
        nowMillis = System::currentTimeMillis,
    ) +
        notesBoardForwardTools(boards) { UUID.randomUUID().toString() } +
        budgetForwardTools(
            post = { upload -> budgetRepository().post(upload).map { } },
            departments = { forwardDepartments() },
            isTelevision = { isTelevisionProduction() },
        ) +
        continuityForwardTool(
            create = { draft, attachment, uniqueId ->
                com.zillit.desktop.feature.continuity.data.ContinuityRepositoryImpl(
                    apiClient,
                    config,
                    localise = { it.localised() },
                    bus = socketEvents,
                ).create(draft, attachment, uniqueId)
            },
            newUniqueId = { UUID.randomUUID().toString() },
        ) +
        locationForwardTools(
            create = { draft, attachment ->
                com.zillit.desktop.feature.location.data.LocationRepositoryImpl(
                    apiClient,
                    config,
                    newUniqueId = { UUID.randomUUID().toString() },
                    bus = socketEvents,
                    currentProjectId = { projectContext?.context?.value?.project?.projectId },
                ).create(draft, attachment, linkPreview = null).map { }
            },
        ) +
        castBoardForwardTools(BoardTool.Casting, permissions) +
        castBoardForwardTools(BoardTool.Wardrobe, permissions)

/**
 * One cast board's twelve destinations — two lists, three shortlists each,
 * for casting and for wardrobe.
 *
 * The unit id comes from the viewer's own rights, which is where every one of
 * this board's routes gets it ([CastingViewer] reads the same field for the
 * board itself), so a list this person was not granted contributes a refusal
 * rather than a request the service would answer with `unit_chat_unit_not_exist`.
 */
private fun AppGraph.Ready.castBoardForwardTools(
    board: BoardTool,
    permissions: () -> ProjectPermissions,
): List<ForwardTool> {
    val repository = CastingRepositoryImpl(apiClient, config, board)
    return castBoardForwardTools(
        board = board,
        unitId = { identifier -> permissions().access(identifier).unitId?.takeIf { it.isNotBlank() } },
        create = { unitId, draft -> repository.create(unitId, draft) },
        casts = { unitId ->
            when (val answer = repository.casts(unitId)) {
                is ZillitResult.Success -> answer.data
                is ZillitResult.Failure -> emptyList()
            }
        },
        isTelevision = { isTelevisionProduction() },
        newUniqueId = { UUID.randomUUID().toString() },
    )
}

/** The production's departments, as the department budget's picker lists them. */
private suspend fun AppGraph.Ready.forwardDepartments(): List<ForwardChoice> {
    val projectId = projectContext?.context?.value?.project?.projectId ?: return emptyList()
    return when (val answer = projectRepository.departments(projectId)) {
        is ZillitResult.Success -> answer.data.map { ForwardChoice(it.id, Labels.translate(it.name)) }
        is ZillitResult.Failure -> emptyList()
    }
}

/** A television production asks for an episode on every document it files. */
private fun AppGraph.Ready.isTelevisionProduction(): Boolean =
    projectContext?.context?.value?.project?.subType?.contains("television", ignoreCase = true) == true

/** Home's own tab strip, read the way its board reads it. */
private suspend fun AppGraph.Ready.homeBoardUnits(): List<HomeUnit> =
    when (val answer = homeFeedRepository.loadUnits()) {
        is ZillitResult.Success -> answer.data
        is ZillitResult.Failure -> emptyList()
    }

/**
 * The Production Report's chat, as a forward destination.
 *
 * Since the October 2026 split the row forwarded to is still the Production
 * Report's (its `unit_id` addresses the chat), but **posting rights come from
 * `production_report_chat_tool`** — the chat routes answer 403 without them
 * — and the label shown is the chat tool's
 * (`production_report_tool_chat_label`), not the report row's unit name. The
 * web does exactly this in `addToolsForForward` via `resolvePrChatRights`,
 * and there is no admin bypass in forwarding.
 *
 * Text only here, for the reason the notes boards are: anything else opens
 * the continuation-or-new question and a document form, which is not built.
 */
private fun AppGraph.Ready.productionReportChatBoard(
    permissions: () -> ProjectPermissions,
): ForwardBoard {
    val units: suspend () -> List<HomeUnit> = {
        val report = permissions().access(PRODUCTION_REPORT_TOOL)
        val chat = permissions().access(PRODUCTION_REPORT_CHAT_TOOL)
        val unitId = report.unitId.orEmpty()
        // The chat tool's own row decides, admin flag and all: the web reads
        // `enabled && view_access && posting_access` off it and nothing else.
        val mayPost = chat.enabled && chat.canView && chat.canPost
        if (unitId.isBlank() || !mayPost) {
            emptyList()
        } else {
            listOf(
                HomeUnit(
                    id = unitId,
                    identifier = PRODUCTION_REPORT_CHAT_TOOL,
                    unitName = PRODUCTION_REPORT_CHAT_LABEL,
                    canView = true,
                    canPost = true,
                    enabled = true,
                ),
            )
        }
    }
    return ForwardBoard(
        key = "production-report",
        title = { Labels.translate(PRODUCTION_REPORT_CHAT_LABEL) },
        units = units,
        // `production-report/` with no `chat/` segment, on the report service
        // — the routes the report chat actually answers.
        repository = boardRepository(
            board = "production-report",
            service = ZillitService.ProductionReport,
            chatSegment = "",
        ),
        kinds = setOf(ForwardKind.Text),
        identifier = PRODUCTION_REPORT_CHAT_TOOL,
    )
}

/**
 * A board on the unit service whose one unit comes from the production's tool
 * list — Info and Confidential Info, exactly as their own tools resolve them.
 */
private fun AppGraph.Ready.toolBoard(
    board: String,
    labelKey: String,
    identifier: String,
    permissions: () -> ProjectPermissions,
    kinds: Set<ForwardKind>? = null,
): ForwardBoard {
    val units: suspend () -> List<HomeUnit> = {
        val access = permissions().access(identifier)
        val unitId = access.unitId.orEmpty()
        if (unitId.isBlank()) {
            emptyList()
        } else {
            listOf(
                HomeUnit(
                    id = unitId,
                    identifier = access.identifier,
                    unitName = access.unitName.orEmpty(),
                    canView = access.canView || permissions().isAdmin,
                    canPost = access.canPost || permissions().isAdmin,
                    enabled = access.enabled,
                ),
            )
        }
    }
    return ForwardBoard(
        key = board,
        title = { Labels.translate(labelKey) },
        units = units,
        repository = boardRepository(board),
        kinds = kinds,
    )
}

/**
 * A board whose units come from its own service — Script Notes and Camera &
 * Sound Report, both on the script-notes host.
 *
 * Offered for a text line only: the web opens a document form for anything
 * else (`showNewOrContinuationPopup`), which is not built here yet.
 */
private fun AppGraph.Ready.serviceBoard(
    board: String,
    labelKey: String,
    identifier: String,
    unitsRoute: String,
    permissions: () -> ProjectPermissions,
): ForwardBoard {
    val source = com.zillit.desktop.feature.home.data.ReportUnitsSource(
        apiClient,
        config,
        ZillitService.ScriptNotes,
        unitsRoute,
    )
    val units: suspend () -> List<HomeUnit> = {
        val access = permissions().access(identifier)
        if (!access.enabled || !(access.canPost || permissions().isAdmin)) {
            emptyList()
        } else {
            when (val answer = source.units()) {
                is ZillitResult.Success -> answer.data.map { row ->
                    HomeUnit(
                        id = row.id,
                        identifier = row.identifier ?: identifier,
                        unitName = row.name,
                        canView = true,
                        canPost = true,
                        enabled = true,
                    )
                }
                is ZillitResult.Failure -> emptyList()
            }
        }
    }
    return ForwardBoard(
        key = board,
        title = { Labels.translate(labelKey) },
        units = units,
        repository = boardRepository(board, ZillitService.ScriptNotes),
        kinds = setOf(ForwardKind.Text),
        identifier = identifier,
    )
}

/** A board's repository, with nothing but the segment and host to tell them apart. */
private fun AppGraph.Ready.boardRepository(
    board: String,
    service: ZillitService = ZillitService.Units,
    chatSegment: String = "chat",
): HomeFeedRepository = HomeFeedRepositoryImpl(
    apiClient = apiClient,
    config = config,
    decrypt = noticeDecryptor,
    isAdmin = { projectContext?.context?.value?.isAdmin == true },
    nowMillis = System::currentTimeMillis,
    board = board,
    service = service,
    chatSegment = chatSegment,
)

/**
 * Forwarding to Email — the web's sixth destination, which opens the
 * composer with the message in it.
 *
 * The same hand-off the chat menu's Share does
 * ([shareChatMessageAsEmail]), in the terms the picker speaks: the words as
 * HTML, the file as a stored attachment, and the mailbox window opened. A
 * profile with no mailbox gets the web's own refusal.
 */
internal fun AppGraph.Ready.emailForwarding(mail: EmailViewModel?): ((ForwardItem) -> String?)? {
    if (mail == null) return null
    return { item ->
        val mailbox = projectContext?.context?.value?.profile?.mailboxAddress
        if (mailbox.isNullOrBlank()) {
            str(S.desktop_email_id_not_available)
        } else {
            mail.composeRequests.post(
                addressedTo = "",
                bodyHtml = item.asEmailHtml(),
                attachments = listOfNotNull(
                    item.file?.takeIf { item.place == null && it.media.isNotBlank() }?.let { file ->
                        StoredFile(
                            media = file.media,
                            bucket = file.bucket.orEmpty(),
                            region = file.region.orEmpty(),
                            fileName = file.name,
                            contentType = file.contentType.orEmpty(),
                            sizeBytes = file.sizeBytes,
                        )
                    },
                ),
            )
            // No navigator here: a view model's picker is nowhere near a
            // window. The frame leaves its opener in [toolOpener] once the
            // workspace exists — the same trick `driveHolder` uses in
            // `main.kt` for the same reason.
            toolOpener.get()?.invoke(EMAIL_FORWARD_ROUTE)
            null
        }
    }
}

/** The words with newlines kept, or a place as its link — `buildEmailFromMessages`. */
private fun ForwardItem.asEmailHtml(): String {
    val place = place
    if (place != null) {
        val label = text.ifBlank { "${place.latitude}, ${place.longitude}" }
        return """<a href="https://www.google.com/maps?q=${place.latitude},${place.longitude}" """ +
            """target="_blank">${label.escapedForEmail()}</a>"""
    }
    return text.escapedForEmail().replace("\n", "<br>")
}

private fun String.escapedForEmail(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

/**
 * How anything without a navigator opens a tool window. Set by the frame
 * once the workspace view model exists; null before then, and in tests.
 */
internal val toolOpener = java.util.concurrent.atomic.AtomicReference<((String) -> Unit)?>(null)

/** The route the forwarded mail opens in. */
private const val EMAIL_FORWARD_ROUTE = "/email"

/** The board every production has, and the surface key its own feed answers to. */
internal const val HOME_BOARD = "home"

private const val PRODUCTION_REPORT_TOOL = "production_report_tool"
private const val PRODUCTION_REPORT_CHAT_TOOL = "production_report_chat_tool"
private const val PRODUCTION_REPORT_CHAT_LABEL = "production_report_tool_chat_label"

/** How the chat names itself to the reach — matches the chat module's own constant. */
private const val CHAT_SURFACE_KEY = "chat"

private const val BOARD_TARGET = "board:"
private const val CHAT_USER_TARGET = "chatuser:"
private const val CHAT_ROOM_TARGET = "chatroom:"
private const val EMAIL_TARGET = "email"

private fun boardTarget(boardKey: String, unitId: String) = "$BOARD_TARGET$boardKey:$unitId"
