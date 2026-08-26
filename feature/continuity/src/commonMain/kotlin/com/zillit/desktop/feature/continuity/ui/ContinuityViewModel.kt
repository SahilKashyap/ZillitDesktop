@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.continuity.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityRepository
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityTransfer
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.SceneDraft

/**
 * Continuity: the production's photo, video and document board by scene
 * number — uploads into the crew member's own department, forwarded to
 * All Departments when the rest of the unit should see them.
 */
class ContinuityViewModel(
    private val repository: ContinuityRepository,
    private val transfer: ContinuityTransfer,
    private val resolveViewer: () -> ContinuityViewer,
    private val departmentName: (String) -> String?,
    private val newUniqueId: () -> String,
    private val nowMillis: () -> Long,
    private val onSegmentViewed: (segment: String, level1: String, level2: String?) -> Unit = { _, _, _ -> },
) : ZillitViewModel<ContinuityUiState, ContinuityEvent, ContinuityEffect>(ContinuityUiState()) {

    fun start() {
        val viewer = resolveViewer()
        // My own department's name is known up front; the others arrive with the All-board picks.
        val mine = viewer.departmentId.takeIf { it.isNotBlank() }?.let { id -> departmentName(id)?.let { id to it } }
        setState {
            copy(viewer = viewer, departmentNames = if (mine != null) departmentNames + mine else departmentNames)
        }
        refresh()
        listenOnce()
    }

    /**
     * Refetches what is on screen when the socket announces another
     * client's continuity change — the web refetches its folder grid and
     * open scene list on the same four events (`ContinuityModal.jsx:242-295`,
     * `IntraDepartment.jsx:576-658`). Guarded so a second Start (the window
     * reopening) does not stack collectors.
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                refresh()
                state.value.open?.let(::load)
            }
        }
    }

    private var listening = false

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out.
    override fun onEvent(event: ContinuityEvent) {
        when (event) {
            is ContinuityEvent.SelectTab -> {
                setState { copy(tab = event.tab, open = null, pick = null) }
                refresh()
            }
            ContinuityEvent.Refresh -> refresh()
            ContinuityEvent.DismissError -> setState { copy(error = null) }
            is ContinuityEvent.SearchFolders -> setState { copy(folderQuery = event.query) }
            is ContinuityEvent.OpenFolder -> openFolder(event.sceneFolder)
            is ContinuityEvent.OpenDepartment -> openDepartment(event.department)
            ContinuityEvent.ClosePick -> setState { copy(pick = null) }
            ContinuityEvent.CloseFolder -> {
                setState { copy(open = null) }
                refresh()
            }
            ContinuityEvent.LoadMore -> loadMore()
            is ContinuityEvent.SearchCards -> setState { copy(open = open?.copy(query = event.query)) }
            is ContinuityEvent.View -> setState { copy(viewing = event.scene) }
            ContinuityEvent.CloseView -> setState { copy(viewing = null) }
            is ContinuityEvent.ShowDetails -> setState { copy(details = event.scene) }
            ContinuityEvent.CloseDetails -> setState { copy(details = null) }
            is ContinuityEvent.Download -> download(event.scene)
            ContinuityEvent.ToggleSelecting -> setState {
                copy(open = open?.copy(selecting = !open.selecting, selected = emptySet()))
            }
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
            ContinuityEvent.ForwardSelected -> if (state.value.open?.selected.orEmpty().isNotEmpty()) {
                setState { copy(confirmForward = true) }
            }
            ContinuityEvent.ConfirmForward -> forwardSelected()
            ContinuityEvent.CancelForward -> setState { copy(confirmForward = false) }
            is ContinuityEvent.RequestDelete -> setState { copy(confirmDelete = event.scene) }
            ContinuityEvent.ConfirmDelete -> deleteConfirmed()
            ContinuityEvent.CancelDelete -> setState { copy(confirmDelete = null) }
            ContinuityEvent.PickFiles -> guardPost { sendEffect(ContinuityEffect.PickFiles) }
            is ContinuityEvent.FilesPicked -> filesPicked(event)
            is ContinuityEvent.Edit -> guardPost {
                setState { copy(editor = SceneEditor(editingId = event.scene.id, draft = event.scene.toDraft())) }
            }
            is ContinuityEvent.DraftChanged -> setState { copy(editor = editor?.copy(draft = event.draft)) }
            is ContinuityEvent.NewDetailChanged -> setState {
                copy(editor = editor?.copy(newLabel = event.label, newValue = event.value))
            }
            ContinuityEvent.AddDetail -> setState {
                val e = editor ?: return@setState this
                if (e.newLabel.isBlank() && e.newValue.isBlank()) return@setState this
                copy(editor = e.copy(draft = e.draft.withDetail(e.newLabel, e.newValue), newLabel = "", newValue = ""))
            }
            is ContinuityEvent.RemoveDetail -> setState {
                val e = editor ?: return@setState this
                val kept = e.draft.talentInfo.filterIndexed { i, _ -> i != event.index }
                copy(editor = e.copy(draft = e.draft.copy(talentInfo = kept)))
            }
            ContinuityEvent.Save -> save()
            ContinuityEvent.CancelEdit -> setState { copy(editor = null) }
        }
    }

    private fun refresh() {
        val tab = state.value.tab
        setState { copy(loading = true) }
        launch {
            when (val result = repository.folders(tab)) {
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
            onSegmentViewed(tab.readSegment, sceneFolder, null)
            load(OpenFolder(tab, sceneFolder))
        }
    }

    private fun openDepartment(department: ContinuityDepartment) {
        val pick = state.value.pick ?: return
        onSegmentViewed(state.value.tab.readSegment, pick.sceneFolder, department.id)
        setState { copy(pick = null) }
        load(OpenFolder(ContinuityTab.AllDepartments, pick.sceneFolder, department))
    }

    private fun load(folder: OpenFolder) {
        setState { copy(open = folder) }
        launch {
            val result = repository.scenes(folder.tab, folder.sceneFolder, folder.department?.id, nowMillis())
            setState {
                when (result) {
                    is ZillitResult.Failure -> copy(
                        open = open?.copy(loading = false),
                        error = result.error.localised(),
                    )
                    is ZillitResult.Success -> copy(
                        open = open?.copy(
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
        if (open.loadingMore || open.exhausted || open.scenes.isEmpty()) return
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

    private fun forwardSelected() {
        val open = state.value.open ?: return
        val ids = open.selected.toList()
        setState { copy(confirmForward = false, busy = true) }
        launch {
            when (val result = repository.share(ids, open.sceneFolder)) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, open = this.open?.copy(selecting = false, selected = emptySet())) }
                    sendEffect(ContinuityEffect.Notice("Forwarded to All Departments"))
                    reloadOpen()
                }
            }
        }
    }

    private fun deleteConfirmed() {
        val scene = state.value.confirmDelete ?: return
        val open = state.value.open ?: return
        setState { copy(confirmDelete = null, busy = true) }
        launch {
            when (val result = repository.delete(open.tab, scene.id)) {
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.localised()) }
                is ZillitResult.Success -> {
                    setState {
                        val remaining = this.open?.scenes.orEmpty().filterNot { it.id == scene.id }
                        copy(
                            busy = false,
                            open = if (remaining.isEmpty()) null else this.open?.copy(scenes = remaining),
                        )
                    }
                    sendEffect(ContinuityEffect.Notice("Removed"))
                    if (state.value.open == null) refresh()
                }
            }
        }
    }

    private fun download(scene: ContinuityScene) {
        if (!state.value.viewer.canDownload && !state.value.viewer.isAdmin) {
            setState { copy(error = "You do not have download rights for Continuity") }
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

    private fun filesPicked(event: ContinuityEvent.FilesPicked) {
        if (event.files.isEmpty()) return
        val open = state.value.open
        setState {
            copy(
                editor = SceneEditor(
                    files = event.files,
                    draft = SceneDraft(
                        sceneNumber = open?.takeIf { it.tab == ContinuityTab.MyDepartment }?.sceneFolder.orEmpty(),
                    ),
                ),
            )
        }
    }

    private fun save() {
        val editor = state.value.editor ?: return
        val problem = validate(editor.draft, editor.isNew, state.value.viewer)
        if (problem != null) {
            setState { copy(error = problem) }
            return
        }
        setState { copy(editor = editor.copy(saving = true), busy = true) }
        launch {
            val id = editor.editingId
            if (id != null) {
                when (val result = repository.update(id, editor.draft)) {
                    is ZillitResult.Failure -> setState {
                        copy(busy = false, editor = editor.copy(saving = false), error = result.error.localised())
                    }
                    is ZillitResult.Success -> {
                        setState { copy(busy = false, editor = null) }
                        sendEffect(ContinuityEffect.Notice("Scene updated"))
                        reloadOpen()
                    }
                }
            } else {
                createAll(editor)
            }
        }
    }

    /** The web's form rules, or null when the draft may be sent. */
    private fun validate(draft: SceneDraft, isNew: Boolean, viewer: ContinuityViewer): String? {
        val scene = draft.sceneNumber.trim()
        return when {
            scene.isBlank() -> "A scene number is required"
            !scene.first().isDigit() -> "The scene number must start with a digit"
            scene.length > SCENE_MAX -> "The scene number is too long"
            scene.any { it in FORBIDDEN } -> "The scene number has characters that are not allowed"
            viewer.isTelevision && isNew && draft.episode.isBlank() -> "An episode number is required"
            draft.episode.isNotBlank() && !draft.episode.all(Char::isDigit) -> "Episodes are digits only"
            else -> null
        }
    }

    /** One upload and one record per file, all awaited before the board refreshes (the web races these). */
    private suspend fun createAll(editor: SceneEditor) {
        var done = 0
        for (file in editor.files) {
            val stored = when (val up = transfer.upload(file)) {
                is ZillitResult.Failure -> {
                    setState { copy(busy = false, editor = editor.copy(saving = false), error = up.error.localised()) }
                    return
                }
                is ZillitResult.Success -> up.data
            }
            when (val created = repository.create(editor.draft, stored, newUniqueId())) {
                is ZillitResult.Failure -> {
                    setState {
                        copy(busy = false, editor = editor.copy(saving = false), error = created.error.localised())
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
        sendEffect(ContinuityEffect.Notice(if (done == 1) "Scene added" else "$done scenes added"))
        refresh()
        reloadOpen()
    }

    private fun reloadOpen() {
        val open = state.value.open ?: return
        load(OpenFolder(open.tab, open.sceneFolder, open.department))
    }

    private inline fun guardPost(block: () -> Unit) {
        val viewer = state.value.viewer
        if (viewer.canPost || viewer.isAdmin) {
            block()
        } else {
            setState { copy(error = "You do not have posting rights for Continuity") }
        }
    }

    private fun ContinuityScene.toDraft() = SceneDraft(sceneNumber, episode, notes, talentInfo)

    private companion object {
        const val SCENE_MAX = 15
        const val FORBIDDEN = "!@#$%^&*(),.?\":{}|<>"
    }
}
