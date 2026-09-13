package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.CommentScope
import com.zillit.desktop.feature.productionreport.domain.DraftChip
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.StockTemplate
import com.zillit.desktop.feature.productionreport.domain.approvalSections
import com.zillit.desktop.feature.productionreport.domain.hasApprovalInvolvement
import com.zillit.desktop.feature.productionreport.domain.manageTabs
import com.zillit.desktop.feature.productionreport.domain.resolveSection
import com.zillit.desktop.feature.productionreport.domain.visibleBadgeCount

/** The two workspaces: the unit chat, and the report manager. */
enum class Workspace { Chat, Manage }

/** Table or cards, per list. */
enum class ListView { Table, Cards }

/** One list and whether its fetch has settled — "loading" and "empty" read differently. */
data class ReportList(
    val rows: List<ReportSummary> = emptyList(),
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

data class ReportLists(
    val drafts: ReportList = ReportList(),
    val sent: ReportList = ReportList(),
    val received: ReportList = ReportList(),
    val finalized: ReportList = ReportList(),
    val published: ReportList = ReportList(),
) {
    /** Drops a report from every list — a delete, or a voided send. */
    fun without(id: String): ReportLists = copy(
        drafts = drafts.copy(rows = drafts.rows.filterNot { it.id == id }),
        sent = sent.copy(rows = sent.rows.filterNot { it.id == id }),
        received = received.copy(rows = received.rows.filterNot { it.id == id }),
        finalized = finalized.copy(rows = finalized.rows.filterNot { it.id == id }),
        published = published.copy(rows = published.rows.filterNot { it.id == id }),
    )
}

data class ReportUiState(
    val kind: ReportKind = ReportKind.Production,
    val viewer: ReportViewer = ReportViewer(),
    val members: List<SheetMember> = emptyList(),
    val metadata: SheetMetadata = SheetMetadata(),
    val workspace: Workspace = Workspace.Chat,
    val tab: ManageTab = ManageTab.Drafts,
    /** The stored section; [activeSection] is what is on screen. */
    val section: ApprovalSection = ApprovalSection.Sent,
    val lists: ReportLists = ReportLists(),
    val badges: ReportBadges = ReportBadges(),
    /** Reports where I owe a current-round signature; null while the probe is out. */
    val approverReportCount: Int? = null,
    val approverProbeFailed: Boolean = false,
    val stockTemplates: List<StockTemplate> = emptyList(),
    val savedTemplates: List<SavedTemplate> = emptyList(),
    val draftChip: DraftChip = DraftChip.All,
    val draftsView: ListView = ListView.Table,
    val approvalsView: ListView = ListView.Table,
    val showOlderPublished: Boolean = false,
    /** Whose history is being fetched — the button on that row alone reads "Loading…". */
    val historyLoadingId: String? = null,
    val editor: EditorState? = null,
    val dialog: ReportDialog? = null,
    val pdf: PdfOverlay? = null,
    /** A workflow call in flight: dialog buttons disable, the second click does nothing. */
    val busy: Boolean = false,
    /** The host has a chat to show in the Chat workspace. */
    val hasChat: Boolean = true,
    /** Document Distribution posting rights (or admin), read when the lists open. */
    val canDistribute: Boolean = false,
) {
    val me: String get() = viewer.userId

    /** Posting rights or project admin — the web's `is2ndAD`. */
    val isPoster: Boolean get() = viewer.canAuthor

    val isFinalApprover: Boolean get() = me.isNotBlank() && me in metadata.finalApproverIds

    val hasApprovalAccess: Boolean
        get() = isPoster || hasApprovalInvolvement(
            userId = me,
            assignedReportCount = approverReportCount,
            probeFailed = approverProbeFailed,
            defaultApproverIds = metadata.finalApproverIds,
            internalReceiverIds = metadata.internalReceiverIds,
        )

    val manageTabs: List<ManageTab> get() = manageTabs(isPoster, hasApprovalAccess)

    val canManage: Boolean get() = manageTabs.isNotEmpty()

    val sections: List<ApprovalSection> get() = approvalSections(isPoster, isFinalApprover)

    val activeSection: ApprovalSection get() = resolveSection(sections, section)

    /** Which comment map the open list reads. */
    val commentScope: CommentScope?
        get() = when {
            workspace != Workspace.Manage -> null
            tab == ManageTab.Drafts -> CommentScope.Drafts
            tab == ManageTab.Published -> CommentScope.Published
            else -> when (activeSection) {
                ApprovalSection.Sent -> CommentScope.Sent
                ApprovalSection.Received -> CommentScope.Received
                ApprovalSection.Finalized -> CommentScope.Finalized
            }
        }

    fun sectionBadge(section: ApprovalSection): Int = when (section) {
        ApprovalSection.Sent -> visibleBadgeCount(badges.sent, lists.sent.rows.size, lists.sent.loaded)
        ApprovalSection.Received -> visibleBadgeCount(badges.received, lists.received.rows.size, lists.received.loaded)
        ApprovalSection.Finalized -> visibleBadgeCount(
            badges.finalized,
            lists.finalized.rows.size,
            lists.finalized.loaded,
        )
    }

    /**
     * The Approvals chip: each section's guarded count, the unmapped units,
     * and status comments no sub-tab claims (the util's stated intent — the
     * web's own recomputation dropped them).
     */
    val approvalsBadge: Int
        get() = ApprovalSection.entries.sumOf { sectionBadge(it) } + badges.unmapped +
            (badges.commentStatus - badges.commentSent - badges.commentReceived).coerceAtLeast(0)

    fun tabBadge(tab: ManageTab): Int = when (tab) {
        ManageTab.Drafts -> badges.drafts
        ManageTab.Approvals -> approvalsBadge
        ManageTab.Published -> badges.published
    }

    val manageBadge: Int get() = manageTabs.sumOf { tabBadge(it) }

    fun member(userId: String?): SheetMember? = userId?.let { id -> members.firstOrNull { it.userId == id } }

    val currentMember: SheetMember? get() = member(me)
}

/** The PDF viewer: generating, then pages. */
data class PdfOverlay(
    val reportId: String,
    val title: String,
    val loading: Boolean = true,
    val pages: List<com.zillit.desktop.feature.productionreport.domain.SheetPdfPage> = emptyList(),
    val bytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PdfOverlay && other.reportId == reportId && other.loading == loading && other.pages == pages
    override fun hashCode(): Int = reportId.hashCode() * 31 + pages.size
}

/** The signature a final approver drew and confirmed, as PNG bytes. */
class SignatureImage(val png: ByteArray)

/** Every dialog the tool can show; one at a time. */
sealed interface ReportDialog {

    /** The shared confirm: a warning disc, a title, a message, Cancel · confirm [· secondary]. */
    data class Confirm(
        val action: ConfirmAction,
        val title: String,
        val message: String,
        val confirmLabel: String,
        val danger: Boolean,
        val secondaryLabel: String? = null,
    ) : ReportDialog

    data class TemplatePicker(val templates: List<StockTemplate>, val selected: Int) : ReportDialog

    /** "Save As" — the draft's name. */
    data class DraftName(val name: String) : ReportDialog

    /** Send for comments (recipients) — or, from the editor, the comments/signature chooser first. */
    data class SendPicker(
        val reportId: String?,
        val fromEditor: Boolean,
        val choosing: Boolean,
        val selected: Set<String>,
        val initial: Set<String>,
        val search: String = "",
        /** Set while the removal prompt replaces the picker. */
        val pendingRemoval: List<SheetMember>? = null,
    ) : ReportDialog

    data class Publish(
        val report: ReportSummary,
        val choosingDestination: Boolean = true,
        val destination: PublishDestination = PublishDestination.InApp,
        val continuation: Boolean? = null,
    ) : ReportDialog

    data class Comments(
        val reportId: String,
        val reportName: String,
        val readOnly: Boolean,
        val comments: List<com.zillit.desktop.feature.productionreport.domain.ReportComment> = emptyList(),
        val loading: Boolean = true,
        val draft: String = "",
        val sending: Boolean = false,
        val editingId: String? = null,
        val editText: String = "",
        val savingEdit: Boolean = false,
        val deletingId: String? = null,
        /** The comment whose delete is waiting on "Delete" in the inline confirm. */
        val confirmDeleteId: String? = null,
        /** Edited in this session — "Edited" shows at once, whatever `updated_on` says. */
        val editedIds: Set<String> = emptySet(),
    ) : ReportDialog

    data class History(
        val title: String,
        val entries: List<com.zillit.desktop.feature.productionreport.domain.HistoryEntry>,
        val emptyText: String = "No history found.",
    ) : ReportDialog

    data class Approve(
        val report: ReportSummary,
        val request: ApprovalRequest,
        val uploading: Boolean = false,
    ) : ReportDialog

    data class Reject(val report: ReportSummary, val request: ApprovalRequest, val reason: String = "") : ReportDialog

    data class ReminderCompose(val report: ReportSummary, val message: String = "") : ReportDialog

    data class Reminders(val reminders: List<com.zillit.desktop.feature.productionreport.domain.ReportReminder>) :
        ReportDialog

    data class ChatPicker(val title: String, val userIds: List<String>) : ReportDialog

    /** Document Distribution: confirm, then a success note naming the file. */
    data class DocDistConfirm(
        val report: ReportSummary,
        val fileName: String,
        val fromDraft: Boolean,
        val alreadyPublished: Boolean = false,
    ) : ReportDialog

    data class DocDistDone(val fileName: String) : ReportDialog
}

/** Where a publish goes. */
enum class PublishDestination(val label: String, val hint: String, val needsDocDist: Boolean) {
    InApp("Publish in App", "Appears in the Published tab.", false),
    DocDist("Publish via Document Distribution", "Sends the PDF to the library only.", true),
    Both("Publish on Both", "Published in the app and copied to the library.", true),
}

/** What a confirm does when accepted. */
sealed interface ConfirmAction {
    data class DeleteReport(val report: ReportSummary) : ConfirmAction
    data class DeleteTemplate(val template: SavedTemplate) : ConfirmAction
    data object NoApprovers : ConfirmAction
    data class RestartReview(val then: SaveIntent) : ConfirmAction

    /** Unsaved changes on Back: confirm discards, secondary saves. */
    data object LeaveEditor : ConfirmAction
}

/** The saves the review-restart guard can hold. */
enum class SaveIntent { Save, SaveAsNew, SaveThenLeave }
