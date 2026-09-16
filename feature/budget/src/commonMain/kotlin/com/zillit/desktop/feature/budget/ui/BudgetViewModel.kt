package com.zillit.desktop.feature.budget.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.feature.budget.data.BUDGET_CHAT_LIST_EVENTS
import com.zillit.desktop.feature.budget.data.BUDGET_SYNC_EVENTS
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDepartment
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetRules
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetUpload
import com.zillit.desktop.feature.budget.domain.BudgetViewer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest

/**
 * One budget tile: the production's budget (`FullBudget.jsx`) or the
 * department budgets (`DepartmentBudget.jsx`), over the web's shared
 * `CommonBudget.jsx` body.
 *
 * Two instances exist, one per tile, because the two differ in what they
 * load first — one budget's versions, or a department directory — and in
 * which rights row gates them; everything after the first click is the same.
 *
 * The viewer and the context arrive as lambdas because view models are built
 * once per graph, before any production is open, and both come with the
 * production.
 */
@Suppress("TooManyFunctions", "LargeClass") // One handler per user verb, as the web has one per button.
class BudgetViewModel(
    val mode: BudgetMode,
    private val repository: BudgetRepository,
    private val viewer: () -> BudgetViewer = { BudgetViewer() },
    private val host: BudgetHost = BudgetHost.None,
    private val badges: BudgetBadges = BudgetBadges.None,
    private val events: SocketEventBus? = null,
    private val rights: RightsRequestBus? = null,
    private val nowMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) : ZillitViewModel<BudgetUiState, BudgetEvent, BudgetEffect>(BudgetUiState(mode = mode)) {

    private var chatsJob: Job? = null

    init {
        launch { badges.unread(mode).collectLatest { counts -> setState { copy(unread = counts) } } }
        events?.let { bus ->
            // Reload rather than patch: a save can add a version or a whole
            // department, and which list it lands in depends on the mode.
            launch { bus.onAny(BUDGET_SYNC_EVENTS).collect { refresh() } }
            launch { bus.onAny(BUDGET_CHAT_LIST_EVENTS).collect { if (currentState.hasDocuments) loadChats() } }
        }
    }

    @Suppress("CyclomaticComplexMethod") // A flat dispatch table, one line per event.
    override fun onEvent(event: BudgetEvent) {
        when (event) {
            BudgetEvent.Load -> load()
            BudgetEvent.Refresh -> refresh()

            is BudgetEvent.DirectorySearch -> setState { copy(directorySearch = event.query) }
            is BudgetEvent.OpenDepartment -> openDepartment(event.departmentId)
            BudgetEvent.BackToDirectory -> backToDirectory()
            BudgetEvent.OpenDrawer -> openDrawer()
            BudgetEvent.CloseDrawer -> setState { copy(drawer = null) }
            is BudgetEvent.DrawerSearch -> setState { copy(drawer = drawer?.copy(search = event.query)) }

            is BudgetEvent.EpisodeSearch -> setState { copy(episodeSearch = event.query) }
            is BudgetEvent.OpenEpisode -> openEpisode(event.episode)
            BudgetEvent.BackToEpisodes -> backToEpisodes()

            is BudgetEvent.SelectVersion -> selectVersion(event.documentId)
            is BudgetEvent.VersionSearch -> setState { copy(versionSearch = event.query) }
            is BudgetEvent.VersionMenu -> setState { copy(versionMenuOpen = event.open, versionSearch = "") }
            is BudgetEvent.MoreMenu -> setState { copy(moreMenuOpen = event.open) }
            is BudgetEvent.ChatMenu -> setState { copy(chatMenuOpen = event.open) }
            BudgetEvent.ViewFile -> openFile(save = false)
            BudgetEvent.DownloadFile -> openFile(save = true)
            is BudgetEvent.ShowActivity -> showActivity(event.activity)
            is BudgetEvent.ActivitySearch -> setState { copy(activity = activity?.copy(search = event.query)) }
            BudgetEvent.DismissActivity -> setState { copy(activity = null) }

            is BudgetEvent.UploadRequested -> requestUpload(event.departmentId)
            is BudgetEvent.UploadDateChanged ->
                setState {
                    copy(upload = upload?.copy(dateText = event.text, dateMillis = event.millis, complaint = null))
                }
            is BudgetEvent.UploadEpisodeChanged ->
                setState { copy(upload = upload?.copy(episode = event.episode, complaint = null)) }
            BudgetEvent.UploadConfirm -> confirmUpload()
            BudgetEvent.UploadCancel -> setState { copy(upload = null) }

            is BudgetEvent.OpenChat -> openChat(event.entry)
            BudgetEvent.CloseChat -> setState { copy(selectedChat = null) }
            BudgetEvent.RefreshChats -> loadChats()
            is BudgetEvent.ShowMembers -> showMembers(event.kind)
            is BudgetEvent.MembersSearch -> setState { copy(members = members?.copy(search = event.query)) }
            is BudgetEvent.TogglePick -> togglePick(event.userId)
            BudgetEvent.PickAll -> pickAll()
            is BudgetEvent.GroupNameChanged ->
                setState { copy(members = members?.copy(groupName = event.name, complaint = null)) }
            BudgetEvent.ConfirmMembers -> confirmMembers()
            BudgetEvent.DismissMembers -> setState { copy(members = null) }

            BudgetEvent.DismissMessage -> setState { copy(error = null, notice = null) }
        }
    }

    // -- loading -------------------------------------------------------------

    /**
     * Rights first, then the list the tile starts on.
     *
     * The web bounces a reader with no view right back to the tools grid
     * (`FullBudget.jsx:64`); here the screen says so in place.
     */
    private fun load() {
        val rights = viewer()
        val context = host.context()
        chatsJob?.cancel()
        // The web mounts the tool afresh on every open and clears the current
        // chat (`BudgetChatDataShow.jsx:198-201`); this view model outlives the
        // tab, so the last visit's department, version and thread are dropped
        // here — only the rights, the context and the ledger's counts carry.
        setState {
            BudgetUiState(mode = mode, viewer = rights, context = context, unread = unread, loading = true)
        }
        if (rights.resolved && !rights.canView(mode)) {
            setState { copy(loading = false) }
            return
        }
        when (mode) {
            BudgetMode.Main -> loadMain(initial = true)
            BudgetMode.Department -> loadDirectory(initial = true)
        }
    }

    /** A live event or an upload: the same lists again, keeping what is open. */
    private fun refresh() {
        setState { copy(viewer = viewer(), context = host.context()) }
        when (mode) {
            BudgetMode.Main -> loadMain(initial = false)
            BudgetMode.Department -> {
                loadDirectory(initial = false)
                currentState.openDepartment?.let { loadDepartmentVersions(it, keepSelection = true) }
            }
        }
    }

    /** `FullBudget.jsx:getBudgetListData` — `GET budget/main/{ownDepartment}`. */
    private fun loadMain(initial: Boolean) {
        val context = currentState.context
        launch {
            when (val answer = repository.documentsOf(BudgetType.Main, context.departmentId)) {
                is ZillitResult.Success -> {
                    val documents = answer.data.map(::named)
                    if (context.isTelevision) {
                        val episodes = BudgetRules.byEpisode(documents)
                        setState { copy(loading = false, episodes = episodes) }
                        val stillOpen = currentState.selectedEpisode.takeIf { it in episodes.keys }
                        if (stillOpen != null && !initial) {
                            showVersions(episodes.getValue(stillOpen), keepSelection = true)
                        } else {
                            // No episodes yet: the (empty) versions face and its upload button,
                            // as the web falls through to `setShowStatus(true)`.
                            setState {
                                copy(
                                    showingEpisodes = episodes.isNotEmpty(),
                                    documents = emptyList(),
                                    selectedId = null,
                                )
                            }
                        }
                    } else {
                        setState { copy(loading = false) }
                        showVersions(documents, keepSelection = !initial)
                    }
                }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    /** `DepartmentBudget.jsx:getBudgetListData` — the whole list, department rows only. */
    private fun loadDirectory(initial: Boolean) {
        launch {
            when (val answer = repository.documents()) {
                is ZillitResult.Success -> {
                    // The department catalogue arrives with the production, possibly
                    // after the first load — re-read it before naming rows.
                    val context = host.context()
                    setState { copy(context = context) }
                    val documents = answer.data.map(::named)
                    if (context.isTelevision) {
                        val episodes = BudgetRules.byEpisode(documents.filter { it.type == BudgetType.Department })
                        setState { copy(loading = false, episodes = episodes) }
                        val stillOpen = currentState.selectedEpisode.takeIf { it in episodes.keys }
                        if (stillOpen != null && !initial) {
                            setDirectory(episodes.getValue(stillOpen))
                        } else {
                            setState {
                                copy(
                                    showingEpisodes = episodes.isNotEmpty(),
                                    showingDirectory = episodes.isEmpty(),
                                    directory = if (episodes.isEmpty()) directoryRows(emptyList()) else directory,
                                )
                            }
                        }
                    } else {
                        setState { copy(loading = false) }
                        setDirectory(documents)
                        if (initial) setState { copy(showingDirectory = true) }
                    }
                }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    private fun setDirectory(documents: List<BudgetDocument>) {
        setState { copy(directory = directoryRows(documents)) }
    }

    /**
     * The directory's rows: the departments with a budget, cut to what this
     * reader may see (`AddAndShowDepartmentList.jsx:updateDepartment`).
     */
    private fun directoryRows(documents: List<BudgetDocument>): List<BudgetDepartment> {
        val state = currentState
        val withBudgets = BudgetRules.departmentsWithBudgets(documents).map { it.departmentId }
        val visible = BudgetRules.visibleDepartments(withBudgets, state.viewer, state.context.departmentId)
        return visible.map { id -> BudgetDepartment(id, state.context.departmentName(id)) }
    }

    /** A document wearing the production's own name for its department. */
    private fun named(document: BudgetDocument): BudgetDocument =
        if (document.departmentName.isNotBlank() || document.departmentId.isBlank()) {
            document
        } else {
            document.copy(departmentName = currentState.context.departmentName(document.departmentId))
        }

    // -- the directory ---------------------------------------------------------

    /** `CommonBudget.jsx:getDepartmentClickData` — one department's versions. */
    private fun openDepartment(departmentId: String) {
        val context = host.context()
        setState { copy(context = context) }
        val department = BudgetDepartment(departmentId, context.departmentName(departmentId))
        setState {
            copy(
                openDepartment = department,
                showingDirectory = false,
                documents = emptyList(),
                selectedId = null,
                chats = emptyList(),
                selectedChat = null,
                loading = true,
            )
        }
        loadDepartmentVersions(department, keepSelection = false)
    }

    private fun loadDepartmentVersions(department: BudgetDepartment, keepSelection: Boolean) {
        launch {
            when (val answer = repository.documentsOf(BudgetType.Department, department.id)) {
                is ZillitResult.Success -> {
                    val episode = currentState.selectedEpisode
                    val documents = answer.data
                        .map { it.copy(departmentName = department.name) }
                        // Only that episode's, when one is open (`:1032-1034`).
                        .filter { !currentState.context.isTelevision || episode.isBlank() || it.episode == episode }
                    setState { copy(loading = false) }
                    showVersions(documents, keepSelection)
                }

                is ZillitResult.Failure ->
                    setState { copy(loading = false, error = answer.error.localised()) }
            }
        }
    }

    /** `CommonBudget.jsx:clearDepartmentData` — the breadcrumb's "Department List". */
    private fun backToDirectory() {
        chatsJob?.cancel()
        setState {
            copy(
                showingDirectory = true,
                openDepartment = null,
                documents = emptyList(),
                selectedId = null,
                chats = emptyList(),
                selectedChat = null,
                versionSearch = "",
                versionMenuOpen = false,
                moreMenuOpen = false,
                chatMenuOpen = false,
            )
        }
    }

    /** The departments with nothing uploaded yet — the "+" drawer (`ShowDrawerForDepartments.jsx`). */
    private fun openDrawer() {
        if (refusesPost()) return
        val listed = currentState.directory.map { it.id }.toSet()
        val remaining = currentState.context.departments.filterNot { it.id in listed }
        setState { copy(drawer = BudgetDepartmentDrawer(departments = remaining)) }
    }

    // -- episodes --------------------------------------------------------------

    /** `CommonBudget.jsx:handleEpisodeClick`. */
    private fun openEpisode(episode: String) {
        val documents = currentState.episodes[episode].orEmpty()
        setState { copy(selectedEpisode = episode, showingEpisodes = false, episodeSearch = "") }
        when (mode) {
            BudgetMode.Main -> showVersions(documents, keepSelection = false)
            BudgetMode.Department -> {
                setDirectory(documents)
                setState { copy(showingDirectory = true, openDepartment = null) }
            }
        }
    }

    /** The breadcrumb's "Episode List". */
    private fun backToEpisodes() {
        chatsJob?.cancel()
        setState {
            copy(
                showingEpisodes = true,
                selectedEpisode = "",
                showingDirectory = false,
                openDepartment = null,
                documents = emptyList(),
                selectedId = null,
                chats = emptyList(),
                selectedChat = null,
            )
        }
    }

    // -- versions --------------------------------------------------------------

    /**
     * Puts a budget's versions on screen, newest first, and opens the
     * newest — or keeps the open one across a refresh, as the web's
     * `currentSelectedBudget` survives a `budget_saved`.
     */
    private fun showVersions(documents: List<BudgetDocument>, keepSelection: Boolean) {
        val sorted = BudgetRules.sorted(documents)
        val kept = currentState.selectedId?.takeIf { keepSelection && sorted.any { d -> d.id == it } }
        val chosen = kept ?: sorted.firstOrNull()?.id
        val changed = chosen != currentState.selectedId
        setState {
            copy(
                documents = sorted,
                selectedId = chosen,
                showingDirectory = false,
                selectedChat = if (changed) null else selectedChat,
                chats = if (changed) emptyList() else chats,
            )
        }
        if (chosen != null) {
            if (changed) markDocumentRead(chosen)
            loadChats()
            loadAudience()
        } else {
            chatsJob?.cancel()
            setState { copy(chats = emptyList(), chatsLoading = false) }
        }
    }

    /** `CommonBudget.jsx:selectChangeHandler`. */
    private fun selectVersion(documentId: String) {
        if (documentId == currentState.selectedId) {
            setState { copy(versionMenuOpen = false) }
            return
        }
        setState {
            copy(
                selectedId = documentId,
                versionMenuOpen = false,
                versionSearch = "",
                selectedChat = null,
                chats = emptyList(),
            )
        }
        markDocumentRead(documentId)
        loadChats()
        // Only the full budget remembers the last-visited version (`:1530`).
        if (mode == BudgetMode.Main) launch { repository.markVisited(documentId) }
    }

    private fun markDocumentRead(documentId: String) {
        badges.markDocumentRead(mode, documentId, currentState.scopeDepartmentId)
    }

    /**
     * View or download the open version. The record call answers the
     * document with its file, which the web opens in preference to the row
     * it already had (`handelPDFView`); a failed record still opens the row.
     */
    private fun openFile(save: Boolean) {
        val document = currentState.selected ?: return
        setState { copy(moreMenuOpen = false) }
        if (document.file?.isPresent != true) {
            setState { copy(error = "There is no file on this budget.") }
            return
        }
        setState { copy(viewer = viewer()) }
        if (save && !currentState.viewer.canDownload(mode)) {
            rights?.ask(mode.title, RightsKind.Download)
            setState {
                copy(
                    error = "You do not have download rights for this budget" +
                        if (rights == null) "." else " — asking an administrator.",
                )
            }
            return
        }
        launch {
            val activity = if (save) BudgetActivity.Download else BudgetActivity.View
            val recorded = (repository.record(document.id, activity) as? ZillitResult.Success)?.data
            val toOpen = recorded?.takeIf { it.file?.isPresent == true }?.let { fresh ->
                // Keep what the list knew and the record did not — the name
                // the reader saw is the name the file should save under.
                document.copy(file = fresh.file)
            } ?: document
            sendEffect(BudgetEffect.Open(toOpen, save))
        }
    }

    /** The admin-only "View count" / "Download count" sheet. */
    private fun showActivity(activity: BudgetActivity) {
        val document = currentState.selected ?: return
        setState { copy(moreMenuOpen = false, activity = BudgetActivityDialog(activity)) }
        launch {
            when (val answer = repository.activity(document.id, activity)) {
                is ZillitResult.Success -> setState {
                    copy(activity = this.activity?.copy(loading = false, rows = answer.data))
                }

                is ZillitResult.Failure -> setState {
                    copy(activity = null, error = answer.error.localised())
                }
            }
        }
    }

    // -- upload ----------------------------------------------------------------

    /**
     * Refuses a write on this tile, and offers the one thing that changes it.
     * The web opens its permission-request modal on the same press
     * (`CommonBudget.jsx:2235`, `AddAndShowDepartmentList.jsx:error`).
     */
    private fun refusesPost(): Boolean {
        // Rights resolve after the tools call lands, which can be after Load.
        setState { copy(viewer = viewer()) }
        if (currentState.canPost) return false
        rights?.ask(mode.title, RightsKind.Post)
        setState {
            copy(
                error = "You do not have posting rights for ${mode.title}" +
                    if (rights == null) "." else " — asking an administrator.",
            )
        }
        return true
    }

    /** The picker, then the dialog that names the upload by its date. */
    private fun requestUpload(departmentId: String) {
        if (refusesPost()) return
        setState { copy(chatMenuOpen = false) }
        launch {
            val picked = host.pickPdf() ?: return@launch
            if (!BudgetRules.acceptsFile(picked.name)) {
                setState { copy(error = "Please select a PDF document.") }
                return@launch
            }
            val state = currentState
            val target = when {
                departmentId.isNotBlank() -> departmentId
                mode == BudgetMode.Department -> state.openDepartment?.id ?: state.context.departmentId
                else -> ""
            }
            setState {
                copy(
                    drawer = null,
                    upload = BudgetUploadDraft(
                        fileName = picked.name,
                        bytes = picked.bytes,
                        departmentId = target,
                        departmentName = if (target.isBlank()) "" else context.departmentName(target),
                        episode = selectedEpisode,
                    ),
                )
            }
        }
    }

    /** The dialog's refusals, in the web's order: `Daterequired`, then `episode_number_required`. */
    private fun uploadComplaint(draft: BudgetUploadDraft, context: BudgetContext): String? {
        val date = draft.dateMillis
        return when {
            date == null -> "Date is required."
            !BudgetRules.dateIsAllowed(date, nowMillis()) -> "The date cannot be after today."
            context.isTelevision && draft.episode.isBlank() -> "Episode number is required."
            else -> null
        }
    }

    /** `CommonBudget.jsx:changeHandler` / `AddAndShowDepartmentList.jsx:changeHandler`. */
    private fun confirmUpload() {
        val draft = currentState.upload ?: return
        val context = currentState.context
        val date = draft.dateMillis
        val complaint = uploadComplaint(draft, context)
        if (complaint != null || date == null) {
            setState { copy(upload = draft.copy(complaint = complaint)) }
            return
        }
        setState { copy(upload = null, busy = true, error = null) }
        launch {
            val stored = when (val put = host.store(draft.fileName, draft.bytes)) {
                is ZillitResult.Success -> put.data
                is ZillitResult.Failure -> {
                    setState { copy(busy = false, error = put.error.localised()) }
                    return@launch
                }
            }
            val file = stored.copy(
                name = stored.name.ifBlank { draft.fileName },
                sizeBytes = if (stored.sizeBytes > 0) stored.sizeBytes else draft.sizeBytes,
                thumbnail = stored.thumbnail.ifBlank { BudgetRules.stockThumbnail(draft.fileName, context.isBox) },
            )
            val upload = BudgetUpload(
                type = mode.type,
                file = file,
                title = BudgetRules.uploadTitle(mode.type, draft.departmentName, BudgetRules.dateLabel(date)),
                departmentId = draft.departmentId,
                episode = if (context.isTelevision) draft.episode.trim() else "",
            )
            when (val answer = repository.post(upload)) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false, notice = "Budget uploaded.", selectedChat = null) }
                    if (draft.sizeBytes > OVERSIZE_BYTES) host.announceOversize(draft.fileName, draft.sizeBytes)
                    afterUpload(answer.data, draft)
                }

                is ZillitResult.Failure ->
                    setState { copy(busy = false, error = answer.error.localised()) }
            }
        }
    }

    /**
     * Where the reader lands after an upload. The web puts the new document
     * on screen alone (`setDocDocDetails([addDeprtmentName])`) and lets the
     * socket's `budget_saved` bring the rest; here the list is re-read at
     * once and the new version opened, which reads the same a second later.
     */
    private fun afterUpload(saved: BudgetDocument, draft: BudgetUploadDraft) {
        setState {
            copy(
                selectedId = saved.id,
                selectedEpisode = if (context.isTelevision) draft.episode.trim() else selectedEpisode,
            )
        }
        when (mode) {
            BudgetMode.Main -> loadMain(initial = false)
            BudgetMode.Department -> {
                val department = BudgetDepartment(draft.departmentId, draft.departmentName)
                loadDirectory(initial = false)
                if (currentState.openDepartment?.id == department.id) {
                    loadDepartmentVersions(department, keepSelection = true)
                }
            }
        }
    }

    // -- chats -----------------------------------------------------------------

    /** `CommonBudget.jsx:getChatUserList` — the people and rooms of the open version. */
    private fun loadChats() {
        val document = currentState.selected ?: return
        chatsJob?.cancel()
        setState { copy(chatsLoading = true) }
        chatsJob = launch {
            val answer = repository.chats(mode, currentState.scopeDepartmentId, document.id)
            if (currentState.selected?.id != document.id) return@launch
            when (answer) {
                is ZillitResult.Success -> {
                    val crew = host.crew().associateBy { it.userId }
                    val me = currentState.context.userId
                    val entries = answer.data
                        // Oneself is never a row (`getUsersDetails` skips `user_id == me`).
                        .filterNot { it is BudgetChatEntry.Person && it.userId == me }
                        .map { entry ->
                            if (entry is BudgetChatEntry.Person) entry.named(crew[entry.userId]) else entry
                        }
                    setState {
                        // A row this desktop just made — a member picked, a
                        // room created — stays until the server lists it;
                        // any other selected row that is gone (a room
                        // removed) closes.
                        val kept = selectedChat?.takeIf { open ->
                            entries.none { it.key == open.key } && chats.any { it.key == open.key }
                        }
                        copy(
                            chatsLoading = false,
                            chats = if (kept == null) entries else entries + kept,
                            selectedChat = selectedChat?.takeIf { open ->
                                (entries + listOfNotNull(kept)).any { it.key == open.key }
                            },
                        )
                    }
                }

                is ZillitResult.Failure -> setState { copy(chatsLoading = false) }
            }
        }
    }

    /** The row wearing the crew list's name and designation, which the ack does not always carry. */
    private fun BudgetChatEntry.Person.named(person: BudgetPerson?): BudgetChatEntry.Person =
        if (person == null) {
            this
        } else {
            copy(
                name = person.fullName.ifBlank { name },
                designation = person.designation.ifBlank { designation },
                isAdmin = isAdmin || person.isAdmin,
                deviceId = deviceId.ifBlank { person.deviceId },
                hasLeft = hasLeft || person.hasLeft,
            )
        }

    /** `CommonBudget.jsx:fetchUserAllList` — main users, plus the department's on that tile. */
    private fun loadAudience() {
        launch {
            val answer = repository.members(currentState.scopeDepartmentId)
            if (answer is ZillitResult.Success) {
                val ids = when (mode) {
                    BudgetMode.Main -> answer.data.main
                    BudgetMode.Department -> answer.data.main + answer.data.department
                }.map { it.userId }.toSet()
                setState { copy(audienceIds = ids) }
            }
        }
    }

    private fun openChat(entry: BudgetChatEntry) {
        val document = currentState.selected ?: return
        setState { copy(selectedChat = entry, chatMenuOpen = false) }
        badges.markChatRead(mode, document.id, entry.key)
    }

    // -- members dialog --------------------------------------------------------

    /** `CommonBudget.jsx:handleMembderModal` → `MembersModal.jsx`. */
    private fun showMembers(kind: BudgetMembersDialog.Kind) {
        if (!currentState.canChat) return
        setState { copy(chatMenuOpen = false, members = BudgetMembersDialog(kind)) }
        launch {
            val answer = repository.members(currentState.scopeDepartmentId)
            val ids = when (answer) {
                is ZillitResult.Success -> when (mode) {
                    BudgetMode.Main -> answer.data.main
                    BudgetMode.Department -> answer.data.main + answer.data.department
                }.map { it.userId }.distinct()

                is ZillitResult.Failure -> emptyList()
            }
            val crew = host.crew().associateBy { it.userId }
            val me = currentState.context.userId
            val listed = currentState.chats.filterIsInstance<BudgetChatEntry.Person>().map { it.userId }.toSet()
            val candidates = ids
                .filter { it != me }
                .filter { kind == BudgetMembersDialog.Kind.Group || it !in listed }
                .mapNotNull { crew[it] }
            setState {
                copy(
                    audienceIds = ids.toSet(),
                    members = members?.copy(loading = false, candidates = candidates),
                )
            }
        }
    }

    private fun togglePick(userId: String) {
        val dialog = currentState.members ?: return
        when (dialog.kind) {
            // A member is not picked — they are opened (`MembersModal.jsx:handleCheck`).
            BudgetMembersDialog.Kind.Member -> {
                val person = dialog.candidates.firstOrNull { it.userId == userId } ?: return
                val entry = BudgetChatEntry.Person(
                    userId = person.userId,
                    name = person.fullName,
                    designation = person.designation,
                    isAdmin = person.isAdmin,
                    deviceId = person.deviceId,
                    hasLeft = person.hasLeft,
                )
                setState {
                    copy(
                        members = null,
                        chats = if (chats.any { it.key == entry.key }) chats else chats + entry,
                        selectedChat = entry,
                    )
                }
            }

            BudgetMembersDialog.Kind.Group -> setState {
                val picked = if (userId in dialog.picked) dialog.picked - userId else dialog.picked + userId
                copy(members = dialog.copy(picked = picked, complaint = null))
            }
        }
    }

    private fun pickAll() {
        val dialog = currentState.members ?: return
        val all = dialog.visible.map { it.userId }.toSet()
        setState {
            val picked = if (dialog.picked.containsAll(all)) emptySet() else all
            copy(members = dialog.copy(picked = picked, complaint = null))
        }
    }

    /** `MembersModal.jsx:handleCreateGroup` → `CommonBudget.jsx:createGroup`. */
    private fun confirmMembers() {
        val dialog = currentState.members ?: return
        if (dialog.kind != BudgetMembersDialog.Kind.Group) return
        val document = currentState.selected ?: return
        val complaint = BudgetRules.groupComplaint(dialog.groupName, dialog.picked)
        if (complaint != null) {
            setState { copy(members = dialog.copy(complaint = complaint)) }
            return
        }
        setState { copy(members = dialog.copy(saving = true)) }
        launch {
            val made = repository.createRoom(
                mode = mode,
                departmentId = currentState.scopeDepartmentId,
                documentId = document.id,
                name = dialog.groupName.trim(),
                memberIds = dialog.picked.toList(),
            )
            when (made) {
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            members = null,
                            notice = "Group created successfully.",
                            chats = if (chats.any { it.key == made.data.key }) chats else chats + made.data,
                            selectedChat = made.data,
                        )
                    }
                    loadChats()
                }

                is ZillitResult.Failure -> setState {
                    copy(members = dialog.copy(saving = false, complaint = made.error.localised()))
                }
            }
        }
    }

    private companion object {
        /** The web's `checkFileLength` threshold — 25 MB. */
        const val OVERSIZE_BYTES = 25L * 1024 * 1024
    }
}
