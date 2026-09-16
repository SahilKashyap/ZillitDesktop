package com.zillit.desktop.feature.continuity.ui

import com.zillit.desktop.feature.continuity.domain.ContinuityCrewMember
import com.zillit.desktop.feature.continuity.domain.ContinuityDepartment
import com.zillit.desktop.feature.continuity.domain.ContinuityScene
import com.zillit.desktop.feature.continuity.domain.ContinuityTab
import com.zillit.desktop.feature.continuity.domain.ContinuityUnread
import com.zillit.desktop.feature.continuity.domain.ContinuityViewer
import com.zillit.desktop.feature.continuity.domain.PickedContinuityFile
import com.zillit.desktop.feature.continuity.domain.SceneDraft
import com.zillit.desktop.feature.continuity.domain.TalentInfo

/** Which OS picker the upload menu opens — the web's paperclip offers the same three. */
enum class PickKind(val label: String) {
    Photos("Photos"),
    Videos("Videos"),
    Documents("Documents"),
}

/** The cards of one scene folder (and, on the All board, one department) — the web's `ContinuityModal`. */
data class OpenFolder(
    val tab: ContinuityTab,
    val sceneFolder: String,
    val department: ContinuityDepartment? = null,
    val scenes: List<ContinuityScene> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val exhausted: Boolean = false,
    /** Selection mode: the ids ticked for forwarding (`showCheckboxes` + `forwardButton`). */
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

    /** The cards the ticks name, in the order shown. */
    val selectedScenes: List<ContinuityScene> get() = shown.filter { it.id in selected }
}

/** The department picker of a scene folder on the All board — the web's `DepartmentList` modal. */
data class DepartmentPick(
    val sceneFolder: String,
    val departments: List<ContinuityDepartment>,
    val loading: Boolean,
    val query: String = "",
)

/** The "Add more details" sub-dialog: a new row at [index] -1, or the row being edited. */
data class DetailEditor(val index: Int = -1, val label: String = "", val value: String = "") {
    val isNew: Boolean get() = index < 0
    val canSave: Boolean get() = label.isNotBlank() && value.isNotBlank()
}

/** The Add Scene Details / Edit Details dialog. */
data class SceneEditor(
    val editingId: String? = null,
    val files: List<PickedContinuityFile> = emptyList(),
    val draft: SceneDraft = SceneDraft(),
    val saving: Boolean = false,
    /** Uploads done out of [files]. */
    val progress: Int = 0,
    val detail: DetailEditor? = null,
    /**
     * A refused Submit — the form rule it broke, or the upload/save failure.
     * Kept on the editor, not the page: a banner behind the dialog's scrim is
     * unreadable and its Dismiss unreachable. The web shows these under the
     * field / as a toast over the modal.
     */
    val error: String? = null,
) {
    val isNew: Boolean get() = editingId == null
}

/** The forward drawer: All Departments, or a crew pick — the web's `ContinuityDrawer`. */
data class ForwardSheet(
    val step: Step = Step.Options,
    val userQuery: String = "",
    val selectedUsers: Set<String> = emptySet(),
    val sending: Boolean = false,
    /** A refused send, shown inside the sheet for the same reason as [SceneEditor.error]. */
    val error: String? = null,
) {
    enum class Step { Options, Users }
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
    val unread: ContinuityUnread = ContinuityUnread.Empty,
    val crew: List<ContinuityCrewMember> = emptyList(),
    val pick: DepartmentPick? = null,
    val open: OpenFolder? = null,
    val viewing: ContinuityScene? = null,
    val details: ContinuityScene? = null,
    val editor: SceneEditor? = null,
    val confirmDelete: ContinuityScene? = null,
    /** The web's popconfirm before selection mode: "doing this will make your material visible to all". */
    val forwardIntent: Boolean = false,
    val forward: ForwardSheet? = null,
) {
    /** The web's `item.toString().includes(inputValue)`, numerically ordered. */
    val shownFolders: List<String>
        get() = folders.filter { folderQuery.isBlank() || it.contains(folderQuery.trim()) }
            .sortedWith(compareBy({ it.toDoubleOrNull() ?: Double.MAX_VALUE }, { it }))

    /** The department rows the pick shows, narrowed by its search. */
    val shownDepartments: List<ContinuityDepartment>
        get() {
            val pick = pick ?: return emptyList()
            val needle = pick.query.trim().lowercase()
            return pick.departments.filter { d ->
                needle.isEmpty() || (departmentNames[d.id] ?: d.name).lowercase().contains(needle)
            }
        }

    /** Crew who can receive a forward: everyone but me, narrowed by the sheet's search. */
    val shownCrew: List<ContinuityCrewMember>
        get() {
            val query = forward?.userQuery.orEmpty()
            return crew.filter { it.userId != viewer.userId && it.matches(query) }
        }

    val departmentLabel: (String) -> String
        get() = { id -> departmentNames[id] ?: id }
}

sealed interface ContinuityEvent {
    data class SelectTab(val tab: ContinuityTab) : ContinuityEvent
    data object Refresh : ContinuityEvent
    data object DismissError : ContinuityEvent
    data class SearchFolders(val query: String) : ContinuityEvent
    data class OpenFolder(val sceneFolder: String) : ContinuityEvent
    data class OpenDepartment(val department: ContinuityDepartment) : ContinuityEvent
    data class SearchDepartments(val query: String) : ContinuityEvent
    data object ClosePick : ContinuityEvent
    data object CloseFolder : ContinuityEvent
    data object LoadMore : ContinuityEvent
    data class SearchCards(val query: String) : ContinuityEvent

    data class View(val scene: ContinuityScene) : ContinuityEvent
    data object CloseView : ContinuityEvent
    data class ShowDetails(val scene: ContinuityScene) : ContinuityEvent
    data object CloseDetails : ContinuityEvent
    data class Download(val scene: ContinuityScene) : ContinuityEvent

    /** Plays a video or opens a document in the system's own app. */
    data class Open(val scene: ContinuityScene) : ContinuityEvent

    /** "Forward" on a card's menu: the web asks first, then shows the ticks. */
    data object RequestForward : ContinuityEvent
    data object ConfirmForwardIntent : ContinuityEvent
    data object CancelForwardIntent : ContinuityEvent
    data class ToggleSelect(val id: String) : ContinuityEvent
    data object CancelSelecting : ContinuityEvent

    /** The bottom "Forward" button: opens the drawer for the ticked cards. */
    data object ForwardSelected : ContinuityEvent
    data object ForwardToAllDepartments : ContinuityEvent
    data object ForwardChooseUsers : ContinuityEvent
    data object ForwardBack : ContinuityEvent
    data class SearchCrew(val query: String) : ContinuityEvent
    data class ToggleCrew(val userId: String) : ContinuityEvent
    data object ToggleAllCrew : ContinuityEvent
    data object SendForward : ContinuityEvent
    data object CloseForward : ContinuityEvent

    data class RequestDelete(val scene: ContinuityScene) : ContinuityEvent
    data object ConfirmDelete : ContinuityEvent
    data object CancelDelete : ContinuityEvent

    /** Opens the OS picker; the host answers with [FilesPicked]. */
    data class PickFiles(val kind: PickKind = PickKind.Photos) : ContinuityEvent
    data class FilesPicked(val files: List<PickedContinuityFile>) : ContinuityEvent
    data class Edit(val scene: ContinuityScene) : ContinuityEvent
    data class DraftChanged(val draft: SceneDraft) : ContinuityEvent
    data object Save : ContinuityEvent
    data object CancelEdit : ContinuityEvent

    /** "Add more details", or the pencil on an existing row. */
    data class OpenDetail(val index: Int = -1) : ContinuityEvent
    data class DetailChanged(val label: String, val value: String) : ContinuityEvent
    data object SaveDetail : ContinuityEvent
    data object CancelDetail : ContinuityEvent
    data class RemoveDetail(val index: Int) : ContinuityEvent
}

sealed interface ContinuityEffect {
    data class Notice(val text: String, val success: Boolean = true) : ContinuityEffect
    data class PickFiles(val kind: PickKind) : ContinuityEffect
}

/** Helpers for the editor's detail rows. */
internal fun SceneDraft.withDetail(index: Int, label: String, value: String): SceneDraft {
    val row = TalentInfo(label.trim(), value.trim())
    val rows = talentInfo.toMutableList()
    if (index in rows.indices) rows[index] = row else rows += row
    return copy(talentInfo = rows)
}
