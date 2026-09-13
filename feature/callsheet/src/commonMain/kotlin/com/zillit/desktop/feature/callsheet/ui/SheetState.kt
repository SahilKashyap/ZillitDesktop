package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.feature.callsheet.domain.AccessPerson
import com.zillit.desktop.feature.callsheet.domain.ApprovalRequest
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.ApprovalStatusEntry
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.CommentScope
import com.zillit.desktop.feature.callsheet.domain.DraftChip
import com.zillit.desktop.feature.callsheet.domain.HistoryEntry
import com.zillit.desktop.feature.callsheet.domain.MissingTitle
import com.zillit.desktop.feature.callsheet.domain.PickedDocument
import com.zillit.desktop.feature.callsheet.domain.SavedSignature
import com.zillit.desktop.feature.callsheet.domain.SavedTemplate
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.SheetComment
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetPayload
import com.zillit.desktop.feature.callsheet.domain.SheetPdfPage
import com.zillit.desktop.feature.callsheet.domain.SheetReminder
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.StockTemplate
import com.zillit.desktop.feature.callsheet.domain.approvalSections
import com.zillit.desktop.feature.callsheet.domain.memberById
import com.zillit.desktop.feature.callsheet.domain.resolveSection
import com.zillit.desktop.feature.callsheet.domain.sheetTabs
import com.zillit.desktop.feature.callsheet.domain.sheetToolName

/** Table or cards, per list. */
enum class ListView { Table, Cards }

/** One list and whether its fetch has settled — "loading" and "empty" read differently. */
data class SheetList(
    val rows: List<CallSheetSummary> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class SheetLists(
    val drafts: SheetList = SheetList(),
    val sent: SheetList = SheetList(),
    val received: SheetList = SheetList(),
    val finalized: SheetList = SheetList(),
    val published: SheetList = SheetList(),
) {
    /** Drops a sheet from every list — a delete, or a send that moved it on. */
    fun without(id: String): SheetLists = map { list -> list.copy(rows = list.rows.filterNot { it.id == id }) }

    /** The web's socket patch: a sheet the server sent back, merged in place wherever it is listed. */
    fun patched(sheet: CallSheetSummary): SheetLists = map { list ->
        if (list.rows.none { it.id == sheet.id }) {
            list
        } else {
            list.copy(rows = list.rows.map { row -> if (row.id == sheet.id) row.mergedWith(sheet) else row })
        }
    }

    private fun map(change: (SheetList) -> SheetList) = SheetLists(
        drafts = change(drafts),
        sent = change(sent),
        received = change(received),
        finalized = change(finalized),
        published = change(published),
    )

    private fun CallSheetSummary.mergedWith(next: CallSheetSummary) = next.copy(
        shared = next.shared ?: shared,
        approvals = if (next.approvalsIncluded) next.approvals else approvals,
        approvalsIncluded = next.approvalsIncluded || approvalsIncluded,
        reminders = next.reminders.ifEmpty { reminders },
        hasCells = next.hasCells || hasCells,
    )
}

/** The Permission tab: one page of the crew axis, and the cells being written. */
data class PermissionState(
    val people: List<AccessPerson> = emptyList(),
    val total: Int = 0,
    /** 0-based, as the grid endpoint counts. */
    val page: Int = 0,
    val pageSize: Int = DEFAULT_PAGE_SIZE,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: String? = null,
    val search: String = "",
    /** Users whose switch is being written — their checkbox is disabled until the answer. */
    val processing: Set<String> = emptySet(),
) {
    /** The web filters the loaded page by name, designation and department. */
    val visible: List<AccessPerson>
        get() {
            val query = search.trim().lowercase()
            if (query.isEmpty()) return people
            return people.filter { person ->
                person.fullName.lowercase().contains(query) ||
                    person.designation.lowercase().contains(query) ||
                    person.department.lowercase().contains(query)
            }
        }

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
        val PAGE_SIZES = listOf(10, 20, 50, 100)
    }
}

data class SheetUiState(
    val viewer: CallSheetViewer = CallSheetViewer(),
    val members: List<SheetMember> = emptyList(),
    val metadata: SheetMetadata = SheetMetadata(),
    val metadataLoaded: Boolean = false,
    val tab: SheetTab = SheetTab.Drafts,
    /** The stored section; [activeSection] is what is on screen. */
    val section: ApprovalSection = ApprovalSection.Sent,
    val lists: SheetLists = SheetLists(),
    val badges: SheetBadges = SheetBadges(),
    val stockTemplates: List<StockTemplate> = emptyList(),
    val savedTemplates: List<SavedTemplate> = emptyList(),
    val draftChip: DraftChip = DraftChip.All,
    val draftsView: ListView = ListView.Table,
    val approvalsView: ListView = ListView.Table,
    val showOlderPublished: Boolean = false,
    /** Whose history is being fetched — that row's button alone reads "Loading…". */
    val historyLoadingId: String? = null,
    /** Whose approval status is being fetched (a Sent row without its requests). */
    val statusLoadingId: String? = null,
    val editor: EditorState? = null,
    val dialog: SheetDialog? = null,
    val pdf: PdfOverlay? = null,
    /** A workflow call in flight: dialog buttons disable, the second click does nothing. */
    val busy: Boolean = false,
    /** Document Distribution posting rights (or admin), read when the lists open. */
    val canDistribute: Boolean = false,
    val permission: PermissionState = PermissionState(),
    /** The host can list the approver's saved signatures. */
    val canUseSavedSignatures: Boolean = false,
) {
    val me: String get() = viewer.userId

    /** Posting rights on the call sheet — the web's `is2ndAD`. No admin bypass. */
    val isPoster: Boolean get() = viewer.isPoster

    val isFinalApprover: Boolean get() = me.isNotBlank() && me in metadata.finalApproverIds

    /** `isApproverForTabs`: a final approver, or someone the Received list already names. */
    val isApprover: Boolean get() = isFinalApprover || lists.received.rows.isNotEmpty()

    /** Nothing until the rights have answered — the web holds the bar back to avoid a flicker. */
    val tabs: List<SheetTab>
        get() = if (!viewer.ready) emptyList() else sheetTabs(isPoster, viewer.canViewGrid, isApprover)

    /** The tab on screen: the stored one when offered, else the first. */
    val activeTab: SheetTab get() = tab.takeIf { it in tabs } ?: tabs.firstOrNull() ?: SheetTab.Drafts

    val sections: List<ApprovalSection> get() = approvalSections(isPoster, isFinalApprover)

    val activeSection: ApprovalSection get() = resolveSection(sections, section)

    /** "Call Sheet Creation" for posting users, "Drafts Call Sheet" for everyone else. */
    val toolTitle: String get() = sheetToolName(isPoster)

    /** Which comment map the open list reads. */
    val commentScope: CommentScope?
        get() = when (activeTab) {
            SheetTab.Drafts -> CommentScope.Drafts
            SheetTab.Published -> CommentScope.Published
            SheetTab.Permission -> null
            SheetTab.Approvals -> when (activeSection) {
                ApprovalSection.Sent -> CommentScope.Sent
                ApprovalSection.Received -> CommentScope.Received
                ApprovalSection.Finalized -> CommentScope.Finalized
            }
        }

    fun sectionBadge(section: ApprovalSection): Int = when (section) {
        ApprovalSection.Sent -> badges.sent
        ApprovalSection.Received -> badges.received
        ApprovalSection.Finalized -> badges.finalized
    }

    fun tabBadge(tab: SheetTab): Int = when (tab) {
        SheetTab.Drafts -> badges.drafts
        SheetTab.Approvals -> badges.approvals
        SheetTab.Published -> badges.published
        SheetTab.Permission -> 0
    }

    fun unreadComments(sheetId: String): Int = badges.commentsFor(commentScope, sheetId)

    fun member(userId: String?): SheetMember? = members.memberById(userId)

    val currentMember: SheetMember? get() = member(me)
}

/** The PDF viewer: generating, then pages. */
data class PdfOverlay(
    val sheetId: String,
    val title: String,
    val loading: Boolean = true,
    val pages: List<SheetPdfPage> = emptyList(),
    val bytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PdfOverlay && other.sheetId == sheetId && other.loading == loading && other.pages == pages

    override fun hashCode(): Int = sheetId.hashCode() * HASH + pages.size

    private companion object {
        const val HASH = 31
    }
}

/** A drawn or saved signature the approver confirmed, as PNG bytes. */
class SignatureImage(val png: ByteArray)

/** The publish wizard's two steps. */
enum class PublishStep { Destination, Type }

/** Every dialog the tool can show; one at a time. */
sealed interface SheetDialog {

    /** The shared confirm: a warning disc, a title, a message, Cancel · confirm [· secondary]. */
    data class Confirm(
        val action: ConfirmAction,
        val title: String,
        val message: String,
        val confirmLabel: String,
        val danger: Boolean,
        val secondaryLabel: String? = null,
    ) : SheetDialog

    data class TemplatePicker(val templates: List<StockTemplate>, val selected: Int) : SheetDialog

    /** "Save As" — the draft's name. */
    data class DraftName(val name: String) : SheetDialog

    /** "Section name required": default sections saved nameless. */
    data class MissingTitles(val items: List<MissingTitle>) : SheetDialog

    /** "Send for Comments" — the recipient picker, and the removal prompt when someone was unticked. */
    data class SendPicker(
        val sheetId: String?,
        val fromEditor: Boolean,
        val selected: Set<String>,
        val initial: Set<String>,
        val search: String = "",
        /** Set while the removal prompt replaces the picker. */
        val pendingRemoval: List<SheetMember>? = null,
    ) : SheetDialog

    /** "Send for Chat" — one recipient, the PDF shared one-to-one. */
    data class ChatSend(
        val sheet: CallSheetSummary,
        val selected: String? = null,
        val search: String = "",
        val sending: Boolean = false,
    ) : SheetDialog

    data class Publish(
        val sheet: CallSheetSummary,
        val step: PublishStep = PublishStep.Destination,
        val destination: PublishDestination = PublishDestination.InApp,
        val continuation: Boolean? = null,
        val notes: String = "",
    ) : SheetDialog

    /** A picked PDF with its caption, on its way to the Home call-sheet unit. */
    data class AttachDocument(
        val sheet: CallSheetSummary,
        val document: PickedDocument,
        val caption: String = "",
        val uploading: Boolean = false,
        /** Picked from the publish wizard: the sheet is published as a continuation with it. */
        val withPublish: Boolean = false,
    ) : SheetDialog

    data class Comments(
        val sheetId: String,
        val sheetName: String,
        val readOnly: Boolean,
        val comments: List<SheetComment> = emptyList(),
        val loading: Boolean = true,
        /** The read-only sheet beside the thread; null while it loads. */
        val preview: SheetPayload? = null,
        val previewFailed: Boolean = false,
        val draft: String = "",
        val sending: Boolean = false,
        val editingId: String? = null,
        val editText: String = "",
        val savingEdit: Boolean = false,
        val deletingId: String? = null,
        /** The comment whose delete is waiting on "Delete" in the inline confirm. */
        val confirmDeleteId: String? = null,
        /** Edited in this session — "(edited)" shows at once, whatever `updated_on` says. */
        val editedIds: Set<String> = emptySet(),
    ) : SheetDialog

    data class History(
        val title: String,
        val entries: List<HistoryEntry>,
        val emptyText: String = "No history found.",
    ) : SheetDialog

    data class ApprovalStatus(val title: String, val entries: List<ApprovalStatusEntry>) : SheetDialog

    data class Approve(
        val sheet: CallSheetSummary,
        val request: ApprovalRequest,
        /** The signature confirmed with "Use This Signature" (or picked from the saved ones). */
        val signature: SignatureImage? = null,
        val uploading: Boolean = false,
        /** The saved-signatures picker over the dialog. */
        val savedPicker: SavedSignaturesPicker? = null,
    ) : SheetDialog

    data class Reject(val sheet: CallSheetSummary, val request: ApprovalRequest, val reason: String = "") : SheetDialog

    data class ReminderCompose(val sheet: CallSheetSummary, val message: String = "") : SheetDialog

    /** "Reminder" — the newest reminder addressed to my pending request. */
    data class ReminderView(val reminder: SheetReminder) : SheetDialog

    data class ChatPicker(val title: String, val userIds: List<String>) : SheetDialog

    /** Document Distribution: confirm, then a success note naming the file. */
    data class DocDistConfirm(
        val sheet: CallSheetSummary,
        val fileName: String,
        val fromDraft: Boolean,
        val alreadyPublished: Boolean = false,
    ) : SheetDialog

    data class DocDistDone(val fileName: String) : SheetDialog
}

/** The approver's saved signatures, and the images fetched so far. */
data class SavedSignaturesPicker(
    val loading: Boolean = true,
    val signatures: List<SavedSignature> = emptyList(),
    val images: Map<String, ByteArray> = emptyMap(),
    val error: String? = null,
    /** The one being fetched to confirm. */
    val picking: String? = null,
)

/** Where a publish goes. */
enum class PublishDestination(val label: String, val hint: String, val needsDocDist: Boolean) {
    InApp("Publish in App", "Appears in the Published tab.", false),
    DocDist("Publish via Document Distribution", "Sends the PDF to the library only.", true),
    Both("Publish on Both", "Published in the app and copied to the library.", true),
}

/** What a confirm does when accepted. */
sealed interface ConfirmAction {
    data class DeleteSheet(val sheet: CallSheetSummary) : ConfirmAction
    data class DeleteTemplate(val template: SavedTemplate) : ConfirmAction
    data object NoApprovers : ConfirmAction
    data class RestartReview(val then: SaveIntent) : ConfirmAction

    /** Unsaved changes on Back: confirm discards, secondary saves. */
    data object LeaveEditor : ConfirmAction
}

/** The saves the review-restart guard can hold. */
enum class SaveIntent { Save, SaveAsNew, SaveThenLeave }
