package com.zillit.desktop.feature.budget.ui

import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetActivityRow
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDepartment
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetRules
import com.zillit.desktop.feature.budget.domain.BudgetViewer

/**
 * What the host knows about the reader and the production that the tool
 * needs: who is asking, their department, and the production's shape.
 */
data class BudgetContext(
    val userId: String = "",
    val departmentId: String = "",
    val departmentName: String = "",
    /** `project_sub_type` contains `television_label` — budgets file per episode. */
    val isTelevision: Boolean = false,
    /** Box storage pins different stock thumbnails than S3 does. */
    val isBox: Boolean = false,
    val departments: List<BudgetDepartment> = emptyList(),
) {
    fun departmentName(id: String): String =
        departments.firstOrNull { it.id == id }?.name?.takeIf { it.isNotBlank() } ?: UNKNOWN_DEPARTMENT

    companion object {
        /** The web's fallback when the catalogue has no such department. */
        const val UNKNOWN_DEPARTMENT = "Unknown Department"
    }
}

/** A person on the production, as the crew list knows them. */
data class BudgetPerson(
    val userId: String,
    val fullName: String,
    val designation: String = "",
    val isAdmin: Boolean = false,
    val deviceId: String = "",
    val hasLeft: Boolean = false,
)

/**
 * The unread counts a budget wears, off the notification ledger.
 *
 * `budget_has_created` rows are [documents], by document; every other row
 * under the budget label is a chat line, grouped by document and then by the
 * sender (a private thread) or the room (`BadgeDB.js:getBudgetChatBadgesFromDB`).
 * [departments] is the department directory's per-row total.
 */
data class BudgetUnread(
    val documents: Map<String, Int> = emptyMap(),
    val chats: Map<String, Map<String, Int>> = emptyMap(),
    val departments: Map<String, Int> = emptyMap(),
) {
    /** Everything waiting on one document: its own row plus its conversations. */
    fun ofDocument(documentId: String): Int =
        (documents[documentId] ?: 0) + chats[documentId]?.values?.sum().orEmpty()

    fun ofChat(documentId: String, key: String): Int = chats[documentId]?.get(key) ?: 0

    private fun Int?.orEmpty(): Int = this ?: 0

    companion object {
        val None = BudgetUnread()
    }
}

/** One row of the department directory. */
data class BudgetDirectoryRow(
    val department: BudgetDepartment,
    val unread: Int = 0,
)

/** A file chosen for upload, before the date that names it is known. */
data class BudgetUploadDraft(
    val fileName: String,
    val bytes: ByteArray,
    /** The department the upload belongs to; blank for the production's budget. */
    val departmentId: String = "",
    val departmentName: String = "",
    /** What the date field holds, as typed — a partial date must survive the keystroke. */
    val dateText: String = "",
    val dateMillis: Long? = null,
    val episode: String = "",
    val complaint: String? = null,
) {
    val sizeBytes: Long get() = bytes.size.toLong()
}

/** The "Add member" / "Create group" dialog (`MembersModal.jsx`). */
data class BudgetMembersDialog(
    val kind: Kind,
    val loading: Boolean = true,
    val search: String = "",
    /** Everyone offered — the budget's audience, minus oneself (and, for a member, minus those already listed). */
    val candidates: List<BudgetPerson> = emptyList(),
    val picked: Set<String> = emptySet(),
    val groupName: String = "",
    val complaint: String? = null,
    val saving: Boolean = false,
) {
    enum class Kind(val title: String) {
        Member("Add member"),
        Group("Create group"),
    }

    val visible: List<BudgetPerson>
        get() {
            val needle = search.trim().lowercase()
            if (needle.isEmpty()) return candidates
            return candidates.filter {
                it.fullName.lowercase().contains(needle) || it.designation.lowercase().contains(needle)
            }
        }
}

/** The "who has viewed / downloaded" sheet (`ScriptDrawer.jsx`). */
data class BudgetActivityDialog(
    val activity: BudgetActivity,
    val loading: Boolean = true,
    val search: String = "",
    val rows: List<BudgetActivityRow> = emptyList(),
)

/** The drawer of departments that have no budget yet (`ShowDrawerForDepartments.jsx`). */
data class BudgetDepartmentDrawer(
    val search: String = "",
    val departments: List<BudgetDepartment> = emptyList(),
) {
    val visible: List<BudgetDepartment>
        get() {
            val needle = search.trim().lowercase()
            if (needle.isEmpty()) return departments
            return departments.filter { it.name.lowercase().contains(needle) }
        }
}

@Suppress("LongParameterList") // One screen's whole state; every field is read by the screen.
data class BudgetUiState(
    val mode: BudgetMode = BudgetMode.Main,
    val viewer: BudgetViewer = BudgetViewer(),
    val context: BudgetContext = BudgetContext(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,

    // -- the department directory (department mode) ------------------------
    /** True while the directory is what the rail shows — the web's `ShowStatus == false`. */
    val showingDirectory: Boolean = false,
    val directory: List<BudgetDepartment> = emptyList(),
    val directorySearch: String = "",
    val drawer: BudgetDepartmentDrawer? = null,
    /** The department whose versions are on screen; null on the main budget. */
    val openDepartment: BudgetDepartment? = null,

    // -- episodes (television productions) ---------------------------------
    val episodes: Map<String, List<BudgetDocument>> = emptyMap(),
    val showingEpisodes: Boolean = false,
    val selectedEpisode: String = "",
    val episodeSearch: String = "",

    // -- the versions of the budget on screen ------------------------------
    /** Newest first, deleted rows gone. */
    val documents: List<BudgetDocument> = emptyList(),
    val selectedId: String? = null,
    val versionSearch: String = "",
    val versionMenuOpen: Boolean = false,
    val moreMenuOpen: Boolean = false,
    val chatMenuOpen: Boolean = false,

    // -- the conversations beside it ---------------------------------------
    val chats: List<BudgetChatEntry> = emptyList(),
    val chatsLoading: Boolean = false,
    val selectedChat: BudgetChatEntry? = null,
    /** Who may discuss this budget — the `/budget-users` answer, as ids. */
    val audienceIds: Set<String> = emptySet(),
    val unread: BudgetUnread = BudgetUnread.None,

    // -- dialogs -----------------------------------------------------------
    val upload: BudgetUploadDraft? = null,
    val members: BudgetMembersDialog? = null,
    val activity: BudgetActivityDialog? = null,
) {

    val selected: BudgetDocument? get() = documents.firstOrNull { it.id == selectedId } ?: documents.firstOrNull()

    /** The newest version — the only one a discussion may be opened on. */
    val latest: BudgetDocument? get() = BudgetRules.latest(documents)

    val hasDocuments: Boolean get() = documents.isNotEmpty()

    /**
     * Whether a new discussion may be started here: the web's
     * `currentBudgetId == latestBudgetDocId` (`CommonBudget.jsx:2135`). Older
     * versions can be read and their threads reopened, not added to.
     */
    val canChat: Boolean get() = hasDocuments && selected?.id == latest?.id

    val canPost: Boolean get() = viewer.canPost(mode)

    /** What the rail is showing right now. */
    val stage: BudgetStage
        get() = when {
            context.isTelevision && showingEpisodes -> BudgetStage.Episodes
            mode == BudgetMode.Department && showingDirectory -> BudgetStage.Directory
            else -> BudgetStage.Versions
        }

    /** The department the versions on screen belong to, or the reader's own on the main tile. */
    val scopeDepartmentId: String
        get() = when (mode) {
            BudgetMode.Main -> context.departmentId
            BudgetMode.Department -> openDepartment?.id ?: context.departmentId
        }

    /** The versions the picker offers after the uploader search, still newest first. */
    fun versionsMatching(nameOf: (String) -> String?): List<BudgetDirectoryVersion> =
        BudgetRules.versionsByUploader(documents, versionSearch, nameOf).map { document ->
            BudgetDirectoryVersion(
                document = document,
                // The open version wears no badge: reading it is what clears it.
                unread = if (document.id == selected?.id) 0 else unread.ofDocument(document.id),
            )
        }

    /** The unread waiting on versions other than the open one — the picker's own badge. */
    val otherVersionsUnread: Int
        get() = documents.filter { it.id != selected?.id }.sumOf { unread.ofDocument(it.id) }

    /** The directory rows that match the search, each wearing the ledger's count for it. */
    val directoryVisible: List<BudgetDirectoryRow>
        get() {
            val needle = directorySearch.trim().lowercase()
            return directory
                .filter { needle.isEmpty() || it.name.lowercase().contains(needle) }
                .map { BudgetDirectoryRow(it, unread.departments[it.id] ?: 0) }
        }

    val episodesVisible: List<String>
        get() {
            val needle = episodeSearch.trim().lowercase()
            return episodes.keys.filter { needle.isEmpty() || it.lowercase().contains(needle) }
        }

    /** Everything waiting under one episode, across its budgets. */
    fun episodeUnread(episode: String): Int = episodes[episode].orEmpty().sumOf { unread.ofDocument(it.id) }

    /** Whether a selected person may still be written to — the web's `chatNotAllowedForDepartmentRevokedUser`. */
    val selectedChatRevoked: Boolean
        get() = (selectedChat as? BudgetChatEntry.Person)
            ?.let { it.userId !in audienceIds && audienceIds.isNotEmpty() } == true
}

/** A version row with its badge, for the picker. */
data class BudgetDirectoryVersion(
    val document: BudgetDocument,
    val unread: Int,
)

enum class BudgetStage { Episodes, Directory, Versions }

sealed interface BudgetEvent {
    data object Load : BudgetEvent
    data object Refresh : BudgetEvent

    // directory
    data class DirectorySearch(val query: String) : BudgetEvent
    data class OpenDepartment(val departmentId: String) : BudgetEvent
    data object BackToDirectory : BudgetEvent
    data object OpenDrawer : BudgetEvent
    data object CloseDrawer : BudgetEvent
    data class DrawerSearch(val query: String) : BudgetEvent

    // episodes
    data class EpisodeSearch(val query: String) : BudgetEvent
    data class OpenEpisode(val episode: String) : BudgetEvent
    data object BackToEpisodes : BudgetEvent

    // versions
    data class SelectVersion(val documentId: String) : BudgetEvent
    data class VersionSearch(val query: String) : BudgetEvent
    data class VersionMenu(val open: Boolean) : BudgetEvent
    data class MoreMenu(val open: Boolean) : BudgetEvent
    data class ChatMenu(val open: Boolean) : BudgetEvent
    data object ViewFile : BudgetEvent
    data object DownloadFile : BudgetEvent
    data class ShowActivity(val activity: BudgetActivity) : BudgetEvent
    data class ActivitySearch(val query: String) : BudgetEvent
    data object DismissActivity : BudgetEvent

    // upload
    /** Picks a file for [departmentId] (blank: the budget on screen). */
    data class UploadRequested(val departmentId: String = "") : BudgetEvent
    /** The field's text and, when it parses to a date, its midnight. */
    data class UploadDateChanged(val text: String, val millis: Long?) : BudgetEvent
    data class UploadEpisodeChanged(val episode: String) : BudgetEvent
    data object UploadConfirm : BudgetEvent
    data object UploadCancel : BudgetEvent

    // chats
    data class OpenChat(val entry: BudgetChatEntry) : BudgetEvent
    data object CloseChat : BudgetEvent
    data object RefreshChats : BudgetEvent
    data class ShowMembers(val kind: BudgetMembersDialog.Kind) : BudgetEvent
    data class MembersSearch(val query: String) : BudgetEvent
    data class TogglePick(val userId: String) : BudgetEvent
    data object PickAll : BudgetEvent
    data class GroupNameChanged(val name: String) : BudgetEvent
    data object ConfirmMembers : BudgetEvent
    data object DismissMembers : BudgetEvent

    data object DismissMessage : BudgetEvent
}

sealed interface BudgetEffect {
    /** The chosen document's file, for the host to open or save. */
    data class Open(val document: BudgetDocument, val save: Boolean) : BudgetEffect
}
