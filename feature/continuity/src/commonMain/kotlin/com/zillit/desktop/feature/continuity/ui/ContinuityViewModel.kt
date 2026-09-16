@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.continuity.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.feature.continuity.domain.ContinuityBadges
import com.zillit.desktop.feature.continuity.domain.ContinuityCrewMember
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityForwarder
import com.zillit.desktop.feature.continuity.domain.ContinuityRepository
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.SceneDraft

/**
 * Continuity: the production's photo, video and document board by scene
 * number — uploads into the crew member's own department, forwarded to
 * All Departments (or to chosen crew, as chat messages) when the rest of
 * the unit should see them. The web's `IntraDepartment.jsx` +
 * `ContinuityModal.jsx`, one state.
 */
@Suppress("LongParameterList") // Every seam the board needs; a holder would rename, not reduce.
class ContinuityViewModel(
    private val repository: ContinuityRepository,
    private val transfer: ContinuityTransfer,
    private val resolveViewer: () -> ContinuityViewer,
    private val departmentName: (String) -> String?,
    private val newUniqueId: () -> String,
    private val nowMillis: () -> Long,
    /** The crew who can be forwarded a card; read once per start. */
    private val crew: () -> List<ContinuityCrewMember> = { emptyList() },
    private val badges: ContinuityBadges = ContinuityBadges.None,
    private val forwarder: ContinuityForwarder = ContinuityForwarder { _, _, _ ->
        ZillitResult.Failure(ZillitError.Unknown("forwarding to users is not wired"))
    },
    /**
     * Where "ask an admin for this right" goes; null leaves the plain refusal.
     *
     * The frame answers it with the admin picker and sends the request as a
     * chat message — the phones' flow, hosted once. See `RightsRequestSurface`.
     */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<ContinuityUiState, ContinuityEvent, ContinuityEffect>(ContinuityUiState()) {

    fun start() {
        val viewer = resolveViewer()
        // My own department's name is known up front; the others arrive with the All-board picks.
        val mine = viewer.departmentId.takeIf { it.isNotBlank() }?.let { id -> departmentName(id)?.let { id to it } }
        setState {
            copy(
                viewer = viewer,
                crew = crew(),
                departmentNames = if (mine != null) departmentNames + mine else departmentNames,
            )
        }
        refresh()
        listenOnce()
    }

    /**
     * Refetches what is on screen when the socket announces another
     * client's continuity change — the web refetches its folder grid and
     * open scene list on the same four events (`ContinuityModal.jsx:242-295`,
     * `IntraDepartment.jsx:576-658`) — and redraws the unread chips as the
     * ledger moves. Guarded so a second Start (the window reopening) does
     * not stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                refresh()
                state.value.open?.let { reloadOpen(quiet = true) }
            }
        }
        launch { badges.unread.collect { counts -> setState { copy(unread = counts) } } }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out.
    override fun onEvent(event: ContinuityEvent) {
        when (event) {
            is ContinuityEvent.SelectTab -> {
                setState { copy(tab = event.tab, open = null, pick = null, folderQuery = "") }
                refresh()
            }
            ContinuityEvent.Refresh -> refresh()
            ContinuityEvent.DismissError -> setState { copy(error = null) }
            is ContinuityEvent.SearchFolders -> setState { copy(folderQuery = event.query) }
            is ContinuityEvent.OpenFolder -> openFolder(event.sceneFolder)
            is ContinuityEvent.OpenDepartment -> openDepartment(event.department)
            is ContinuityEvent.SearchDepartments -> setState { copy(pick = pick?.copy(query = event.query)) }
            ContinuityEvent.ClosePick -> setState { copy(pick = null) }
            ContinuityEvent.CloseFolder -> {
                setState { copy(open = null, forward = null, forwardIntent = false) }
                refresh()
            }
            ContinuityEvent.LoadMore -> loadMore()
            is ContinuityEvent.SearchCards -> setState { copy(open = open?.copy(query = event.query)) }
            is ContinuityEvent.View -> setState { copy(viewing = event.scene) }
            ContinuityEvent.CloseView -> setState { copy(viewing = null) }
            is ContinuityEvent.ShowDetails -> setState { copy(details = event.scene) }
            ContinuityEvent.CloseDetails -> setState { copy(details = null) }
            is ContinuityEvent.Download -> download(event.scene)
            is ContinuityEvent.Open -> open(event.scene)

            ContinuityEvent.RequestForward -> guardPost { setState { copy(forwardIntent = true) } }
            ContinuityEvent.ConfirmForwardIntent -> setState {
                copy(forwardIntent = false, open = open?.copy(selecting = true, selected = emptySet()))
            }
            ContinuityEvent.CancelForwardIntent -> setState { copy(forwardIntent = false) }
            is ContinuityEvent.ToggleSelect -> setState {
                copy(
                    open = open?.copy(
                        selected = if (event.id in open.selected) {
                            open.selected - event.id
                        } else {
                            open.selected + event.id
                        },
                    ),
                )
            }
            ContinuityEvent.CancelSelecting -> setState {
                copy(open = open?.copy(selecting = false, selected = emptySet()))
            }

            ContinuityEvent.ForwardSelected -> guardPost {
                if (state.value.open?.selected.orEmpty().isNotEmpty()) setState { copy(forward = ForwardSheet()) }
            }
            ContinuityEvent.ForwardToAllDepartments -> guardPost { forwardToAll() }
            ContinuityEvent.ForwardChooseUsers -> setState {
                copy(forward = forward?.copy(step = ForwardSheet.Step.Users))
            }
            ContinuityEvent.ForwardBack -> setState { copy(forward = forward?.copy(step = ForwardSheet.Step.Options)) }
            is ContinuityEvent.SearchCrew -> setState { copy(forward = forward?.copy(userQuery = event.query)) }
            is ContinuityEvent.ToggleCrew -> setState {
                val sheet = forward ?: return@setState this
                val picked = if (event.userId in sheet.selectedUsers) {
                    sheet.selectedUsers - event.userId
                } else {
                    sheet.selectedUsers + event.userId
                }
                copy(forward = sheet.copy(selectedUsers = picked))
            }
            ContinuityEvent.ToggleAllCrew -> setState {
                val sheet = forward ?: return@setState this
                val everyone = shownCrew.map { it.userId }.toSet()
                val picked = if (sheet.selectedUsers == everyone) emptySet() else everyone
                copy(forward = sheet.copy(selectedUsers = picked))
            }
            ContinuityEvent.SendForward -> guardPost { forwardToUsers() }
            ContinuityEvent.CloseForward -> setState { copy(forward = null) }

            is ContinuityEvent.RequestDelete -> guardPost { setState { copy(confirmDelete = event.scene) } }
            ContinuityEvent.ConfirmDelete -> guardPost { deleteConfirmed() }
            ContinuityEvent.CancelDelete -> setState { copy(confirmDelete = null) }

            is ContinuityEvent.PickFiles -> guardPost { sendEffect(ContinuityEffect.PickFiles(event.kind)) }
            is ContinuityEvent.FilesPicked -> guardPost { filesPicked(event) }
            is ContinuityEvent.Edit -> guardPost {
                setState { copy(editor = SceneEditor(editingId = event.scene.id, draft = event.scene.toDraft())) }
            }
            is ContinuityEvent.DraftChanged -> setState {
                copy(editor = editor?.copy(draft = event.draft, error = null))
            }
            ContinuityEvent.Save -> guardPost { save() }
            ContinuityEvent.CancelEdit -> setState { if (editor?.saving == true) this else copy(editor = null) }

            is ContinuityEvent.OpenDetail -> setState {
                val e = editor ?: return@setState this
                val row = e.draft.talentInfo.getOrNull(event.index)
                copy(editor = e.copy(detail = DetailEditor(event.index, row?.label.orEmpty(), row?.value.orEmpty())))
            }
            is ContinuityEvent.DetailChanged -> setState {
                copy(editor = editor?.copy(detail = editor.detail?.copy(label = event.label, value = event.value)))
            }
            ContinuityEvent.SaveDetail -> setState {
                val e = editor ?: return@setState this
                val d = e.detail ?: return@setState this
                if (!d.canSave) return@setState this
                copy(editor = e.copy(draft = e.draft.withDetail(d.index, d.label, d.value), detail = null))
            }
            ContinuityEvent.CancelDetail -> setState { copy(editor = editor?.copy(detail = null)) }
            is ContinuityEvent.RemoveDetail -> setState {
                val e = editor ?: return@setState this
                val kept = e.draft.talentInfo.filterIndexed { i, _ -> i != event.index }
                copy(editor = e.copy(draft = e.draft.copy(talentInfo = kept)))
            }
        }
    }

    private fun refresh() {
        val tab = state.value.tab
        setState { copy(loading = true) }
        launch {
            val result = repository.folders(tab)
            // A tab switched while the answer was in flight is not this tab's answer.
            if (state.value.tab != tab) return@launch
            when (result) {
                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error.localised()) }
                is ZillitResult.Success -> setState { copy(loading = false, folders = result.data.distinct()) }
            }
        }
    }

    private fun openFolder(sceneFolder: String) {
        val tab = state.value.tab
        if (tab == ContinuityTab.AllDepartments) {
            setState { copy(pick = DepartmentPick(sceneFolder, emptyList(), loading = true)) }
            launch {
                when (val result = repository.departments(sceneFolder)) {
                    is ZillitResult.Failure -> setState { copy(pick = null, error = result.error.localised()) }
                    is ZillitResult.Success -> {
                        val names = result.data.associate { it.id to (departmentName(it.id) ?: it.name) }
                        setState {
                            copy(
                                departmentNames = departmentNames + names,
                                pick = pick?.copy(departments = result.data, loading = false),
                            )
                        }
                    }
                }
            }
        } else {
            badges.markRead(tab, sceneFolder)
            load(OpenFolder(tab, sceneFolder))
        }
    }

    private fun openDepartment(department: ContinuityDepartment) {
        val pick = state.value.pick ?: return
        badges.markRead(ContinuityTab.AllDepartments, pick.sceneFolder, department.id)
        setState { copy(pick = null) }
        load(OpenFolder(ContinuityTab.AllDepartments, pick.sceneFolder, department))
    }

    private fun load(folder: OpenFolder) {
        setState { copy(open = folder) }
        launch {
            val result = repository.scenes(folder.tab, folder.sceneFolder, folder.department?.id, nowMillis())
            setState {
                val current = open ?: return@setState this
                if (current.sceneFolder != folder.sceneFolder || current.department?.id != folder.department?.id) {
                    return@setState this
                }
                when (result) {
                    is ZillitResult.Failure -> copy(
                        open = current.copy(loading = false),
                        error = result.error.localised(),
                    )
                    is ZillitResult.Success -> copy(
                        open = current.copy(
                            scenes = keepMine(result.data),
                            loading = false,
                            exhausted = result.data.isEmpty(),
                        ),
                    )
                }
            }
        }
    }

    private fun loadMore() {
        val open = state.value.open ?: return
        val fetching = open.loading || open.loadingMore
        if (fetching || open.exhausted || open.scenes.isEmpty()) return
        val cursor = open.scenes.minOf { it.cursorMs }
        setState { copy(open = this.open?.copy(loadingMore = true)) }
        launch {
            val result = repository.scenes(open.tab, open.sceneFolder, open.department?.id, cursor)
            setState {
                val current = this.open ?: return@setState this
                when (result) {
                    is ZillitResult.Failure -> copy(
                        open = current.copy(loadingMore = false),
                        error = result.error.localised(),
                    )
                    is ZillitResult.Success -> {
                        val known = current.scenes.map { it.id }.toSet()
                        val fresh = keepMine(result.data).filter { it.id !in known }
                        copy(
                            open = current.copy(
                                scenes = current.scenes + fresh,
                                loadingMore = false,
                                exhausted = fresh.isEmpty(),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** My Department shows my department only — the web keeps this filter even though the server scopes. */
    private fun keepMine(rows: List<ContinuityScene>): List<ContinuityScene> {
        val viewer = state.value.viewer
        return if (state.value.tab == ContinuityTab.MyDepartment && viewer.departmentId.isNotBlank()) {
            rows.filter { it.departmentId.isBlank() || it.departmentId == viewer.departmentId }
        } else {
            rows
        }
    }

    /** `PUT share/scenes {visibility: all}` for the ticked cards (`ContinuityDrawer.jsx:135-153`). */
    private fun forwardToAll() {
        val open = state.value.open ?: return
        val ids = open.selected.toList()
        if (ids.isEmpty()) return
        setState { copy(busy = true, forward = forward?.copy(sending = true)) }
        launch {
            when (val result = repository.share(ids, open.sceneFolder)) {
                is ZillitResult.Failure -> setState {
                    copy(busy = false, forward = null, error = result.error.localised())
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, forward = null, open = this.open?.doneSelecting()) }
                    sendEffect(ContinuityEffect.Notice("Scene(s) have been shared successfully."))
                    reloadOpen()
                }
            }
        }
    }

    /**
     * One chat message per card per chosen person (`ContinuityDrawer.jsx:85-133`).
     * Every send is attempted; one refusal names itself without hiding the
     * rest, and the sheet closes only when all went through.
     */
    private fun forwardToUsers() {
        val open = state.value.open ?: return
        val sheet = state.value.forward ?: return
        val scenes = open.selectedScenes.filter { it.attachment != null }
        val people = sheet.selectedUsers.toList()
        if (scenes.isEmpty() || people.isEmpty()) return
        val isTelevision = state.value.viewer.isTelevision
        setState { copy(busy = true, forward = sheet.copy(sending = true)) }
        launch {
            var failed: ZillitError? = null
            for (userId in people) {
                for (scene in scenes) {
                    val sent = forwarder.forward(scene, userId, scene.forwardCaption(isTelevision))
                    if (sent is ZillitResult.Failure) failed = sent.error
                }
            }
            val problem = failed
            if (problem == null) {
                setState { copy(busy = false, forward = null, open = this.open?.doneSelecting()) }
                sendEffect(ContinuityEffect.Notice("Message forwarded successfully"))
            } else {
                setState { copy(busy = false, forward = sheet.copy(sending = false, error = problem.localised())) }
            }
        }
    }

    private fun deleteConfirmed() {
        val scene = state.value.confirmDelete ?: return
        val open = state.value.open ?: return
        setState { copy(confirmDelete = null, busy = true) }
        launch {
            when (val result = repository.delete(open.tab, scene.id)) {
                is ZillitResult.Failure -> {
                    // `continuity_action_not_allowed` — someone else's upload; the web warns, not errors.
                    setState { copy(busy = false) }
                    sendEffect(ContinuityEffect.Notice(result.error.localised(), success = false))
                }
                is ZillitResult.Success -> {
                    setState {
                        val remaining = this.open?.scenes.orEmpty().filterNot { it.id == scene.id }
                        copy(
                            busy = false,
                            open = if (remaining.none { it.shownOn(open.tab) }) {
                                null
                            } else {
                                this.open?.copy(scenes = remaining)
                            },
                        )
                    }
                    sendEffect(ContinuityEffect.Notice("Media deleted successfully"))
                    refresh()
                }
            }
        }
    }

    private fun download(scene: ContinuityScene) {
        val viewer = state.value.viewer
        if (!viewer.mayDownload) {
            rights?.ask(MODULE_LABEL, RightsKind.Download)
            setState { copy(error = rightsRefusalMessage(MODULE_LABEL, RightsKind.Download, asked = rights != null)) }
            return
        }
        val attachment = scene.attachment ?: return
        setState { copy(busy = true) }
        launch {
            val outcome = when (val bytes = transfer.fetch(attachment, preview = false)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> transfer.saveAndOpen(attachment.name.ifBlank { "continuity" }, bytes.data)
            }
            when (outcome) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = outcome.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(ContinuityEffect.Notice("Saved to Downloads"))
                }
            }
        }
    }

    /** Plays a video / opens a document in the system's app — a view, so no download right is asked. */
    private fun open(scene: ContinuityScene) {
        val attachment = scene.attachment ?: return
        setState { copy(busy = true) }
        launch {
            val outcome = when (val bytes = transfer.fetch(attachment, preview = false)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> transfer.open(attachment.name.ifBlank { "continuity" }, bytes.data)
            }
            setState { copy(busy = false, error = (outcome as? ZillitResult.Failure)?.error?.localised() ?: error) }
        }
    }

    private fun filesPicked(event: ContinuityEvent.FilesPicked) {
        val (accepted, refused) = event.files.partition { it.isAccepted }
        if (refused.isNotEmpty()) {
            sendEffect(ContinuityEffect.Notice("Only photos, videos and documents can be uploaded.", success = false))
        }
        if (accepted.isEmpty()) return
        val open = state.value.open
        setState {
            copy(
                editor = SceneEditor(
                    files = accepted,
                    draft = SceneDraft(
                        sceneNumber = open?.takeIf { it.tab == ContinuityTab.MyDepartment }?.sceneFolder.orEmpty(),
                    ),
                ),
            )
        }
    }

    private fun save() {
        val editor = state.value.editor ?: return
        if (editor.saving) return
        val problem = validate(editor.draft, state.value.viewer)
        if (problem != null) {
            setState { copy(editor = editor.copy(error = problem)) }
            return
        }
        setState { copy(editor = editor.copy(saving = true, error = null), busy = true) }
        launch {
            val id = editor.editingId
            if (id != null) {
                when (val result = repository.update(id, editor.draft)) {
                    is ZillitResult.Failure -> setState {
                        copy(busy = false, editor = editor.copy(saving = false, error = result.error.localised()))
                    }
                    is ZillitResult.Success -> {
                        setState { copy(busy = false, editor = null) }
                        sendEffect(ContinuityEffect.Notice("Scene updated successfully"))
                        refresh()
                        reloadOpen(quiet = true)
                    }
                }
            } else {
                createAll(editor)
            }
        }
    }

    /**
     * The web's form rules (`AddScene.jsx:57-141`, `EditScene.jsx`): a scene
     * number that starts with a digit, no punctuation, at most 15
     * characters; on television an episode of digits only, required.
     */
    private fun validate(draft: SceneDraft, viewer: ContinuityViewer): String? {
        val scene = draft.sceneNumber.trim()
        val episode = draft.episode.trim()
        return when {
            scene.isBlank() -> "Fill the Scene Number"
            scene.any { it in FORBIDDEN } -> "Special character not allow!"
            !scene.first().isDigit() ->
                "Scene number cannot submit without a number. Please fill-in valid scene number."
            scene.length > FIELD_MAX -> "Scene Number not greater then 15 Number"
            viewer.isTelevision && episode.isBlank() -> "Episode Number is required"
            episode.isNotBlank() && !episode.all(Char::isDigit) -> "Episode Number should be a number"
            episode.length > FIELD_MAX -> "Episode Number not greater then 15 Number"
            else -> null
        }
    }

    /** One upload and one record per file, all awaited before the board refreshes (the web races these). */
    private suspend fun createAll(editor: SceneEditor) {
        var done = 0
        for (file in editor.files) {
            val stored = when (val up = transfer.upload(file)) {
                is ZillitResult.Failure -> {
                    setState { copy(busy = false, editor = editor.copy(saving = false, error = up.error.localised())) }
                    return
                }
                is ZillitResult.Success -> up.data
            }
            when (val created = repository.create(editor.draft, stored, newUniqueId())) {
                is ZillitResult.Failure -> {
                    setState {
                        copy(busy = false, editor = editor.copy(saving = false, error = created.error.localised()))
                    }
                    return
                }
                is ZillitResult.Success -> {
                    done++
                    setState { copy(editor = this.editor?.copy(progress = done)) }
                }
            }
        }
        setState { copy(busy = false, editor = null) }
        sendEffect(ContinuityEffect.Notice("Continuity media has been uploaded."))
        refresh()
        reloadOpen(quiet = true)
    }

    /**
     * Refetches the open folder. [quiet] keeps the cards on screen while the
     * answer is in flight — a socket pulse or a save must not blank the grid.
     */
    private fun reloadOpen(quiet: Boolean = false) {
        val open = state.value.open ?: return
        if (quiet) {
            launch {
                val result = repository.scenes(open.tab, open.sceneFolder, open.department?.id, nowMillis())
                if (result is ZillitResult.Success) {
                    setState {
                        val current = this.open ?: return@setState this
                        if (current.sceneFolder != open.sceneFolder) return@setState this
                        current.copy(scenes = keepMine(result.data), loading = false, exhausted = result.data.isEmpty())
                            .let { copy(open = it) }
                    }
                }
            }
        } else {
            load(OpenFolder(open.tab, open.sceneFolder, open.department))
        }
    }

    private inline fun guardPost(block: () -> Unit) {
        if (state.value.viewer.mayPost) {
            block()
        } else {
            rights?.ask(MODULE_LABEL, RightsKind.Post)
            setState { copy(error = rightsRefusalMessage(MODULE_LABEL, RightsKind.Post, asked = rights != null)) }
        }
    }

    private fun ContinuityScene.toDraft() = SceneDraft(sceneNumber, episode, notes, talentInfo)

    private fun OpenFolder.doneSelecting() = copy(selecting = false, selected = emptySet())

    private companion object {
        const val MODULE_LABEL = "Continuity"
        const val FIELD_MAX = 15
        const val FORBIDDEN = "!@#$%^&*(),.?\":{}|<>"
    }
}
