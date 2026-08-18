package com.zillit.desktop.feature.continuity.ui

import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.domain.TalentInfo

/** The cards of one scene folder (and, on the All board, one department). */
data class OpenFolder(
    val tab: ContinuityTab,
    val sceneFolder: String,
    val department: ContinuityDepartment? = null,
    val scenes: List<ContinuityScene> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
    /** Selection mode: the ids ticked for forwarding. */
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val query: String = "",
) {
    /** The web's search: starts-with on scene number, notes or the legacy actor name. */
    val shown: List<ContinuityScene>
        get() {
            val needle = query.trim().lowercase()
            val visible = scenes.filter { it.shownOn(tab) }.sortedWith(compareBy { it.sceneNumber.lowercase() })
            if (needle.isEmpty()) return visible
            return visible.filter { s ->
                s.sceneNumber.lowercase().startsWith(needle) || s.notes.lowercase().startsWith(needle) ||
                    s.actorName.lowercase().startsWith(needle)
            }
        }
}

/** The department picker of a scene folder on the All board. */
data class DepartmentPick(val sceneFolder: String, val departments: List<ContinuityDepartment>, val loading: Boolean)

/** The Add / Edit dialog. */
data class SceneEditor(
    val editingId: String? = null,
    val files: List<PickedContinuityFile> = emptyList(),
    val draft: SceneDraft = SceneDraft(),
    val newLabel: String = "",
    val newValue: String = "",
    val saving: Boolean = false,
    /** Uploads done out of [files]. */
    val progress: Int = 0,
) {
    val isNew: Boolean get() = editingId == null
}

data class ContinuityUiState(
    val viewer: ContinuityViewer = ContinuityViewer(),
    val tab: ContinuityTab = ContinuityTab.MyDepartment,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val folders: List<String> = emptyList(),
    val folderQuery: String = "",
    val departmentNames: Map<String, String> = emptyMap(),
    val pick: DepartmentPick? = null,
    val open: OpenFolder? = null,
    val viewing: ContinuityScene? = null,
    val details: ContinuityScene? = null,
    val editor: SceneEditor? = null,
    val confirmDelete: ContinuityScene? = null,
    val confirmForward: Boolean = false,
) {
    val shownFolders: List<String>
        get() = folders.filter { folderQuery.isBlank() || it.contains(folderQuery.trim()) }
            .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.MAX_VALUE }, { it }))
}

sealed interface ContinuityEvent {
    data class SelectTab(val tab: ContinuityTab) : ContinuityEvent
    data object Refresh : ContinuityEvent
    data object DismissError : ContinuityEvent
    data class SearchFolders(val query: String) : ContinuityEvent
    data class OpenFolder(val sceneFolder: String) : ContinuityEvent
    data class OpenDepartment(val department: ContinuityDepartment) : ContinuityEvent
    data object ClosePick : ContinuityEvent
    data object CloseFolder : ContinuityEvent
    data object LoadMore : ContinuityEvent
    data class SearchCards(val query: String) : ContinuityEvent

    data class View(val scene: ContinuityScene) : ContinuityEvent
    data object CloseView : ContinuityEvent
    data class ShowDetails(val scene: ContinuityScene) : ContinuityEvent
    data object CloseDetails : ContinuityEvent
    data class Download(val scene: ContinuityScene) : ContinuityEvent

    data object ToggleSelecting : ContinuityEvent
    data class ToggleSelect(val id: String) : ContinuityEvent
    data object ForwardSelected : ContinuityEvent
    data object ConfirmForward : ContinuityEvent
    data object CancelForward : ContinuityEvent

    data class RequestDelete(val scene: ContinuityScene) : ContinuityEvent
    data object ConfirmDelete : ContinuityEvent
    data object CancelDelete : ContinuityEvent

    /** Opens the OS picker; the host answers with [FilesPicked]. */
    data object PickFiles : ContinuityEvent
    data class FilesPicked(val files: List<PickedContinuityFile>) : ContinuityEvent
    data class Edit(val scene: ContinuityScene) : ContinuityEvent
    data class DraftChanged(val draft: SceneDraft) : ContinuityEvent
    data class NewDetailChanged(val label: String, val value: String) : ContinuityEvent
    data object AddDetail : ContinuityEvent
    data class RemoveDetail(val index: Int) : ContinuityEvent
    data object Save : ContinuityEvent
    data object CancelEdit : ContinuityEvent
}

sealed interface ContinuityEffect {
    data class Notice(val text: String) : ContinuityEffect
    data object PickFiles : ContinuityEffect
}

/** Helpers for the editor's detail rows. */
internal fun SceneDraft.withDetail(label: String, value: String): SceneDraft =
    copy(talentInfo = talentInfo + TalentInfo(label.trim(), value.trim()))
