package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.feature.productionreport.domain.ApprovalRequest
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.BadgeSurface
import com.zillit.desktop.feature.productionreport.domain.DraftChip
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReplaceTarget
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.domain.SavedTemplate
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.StockTemplate
import com.zillit.desktop.feature.productionreport.domain.approvalSections
import com.zillit.desktop.feature.productionreport.domain.isListedMember
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
    /**
     * Has the project-metadata read finished, and did it fail? Since Sep
     * 2026 its two lists ARE a view-only user's tab set, so "not fetched yet"
     * and "fetched, names nobody" have to be told apart.
     */
    val metadataSettled: Boolean = false,
    val metadataFailed: Boolean = false,
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

    /** On the project's `final_approver_ids` — the Received section, and a viewer's Approvals tab. */
    val isFinalApprover: Boolean get() = isListedMember(metadata.finalApproverIds, me)

    /** On `internal_distribution_receivers` — may post in every thread, and a viewer's Drafts tab. */
    val isInternalReceiver: Boolean get() = isListedMember(metadata.internalReceiverIds, me)

    /**
     * A viewer's whole workspace comes out of the metadata, so it answers
     * only once that read has settled; posting rights alone release a poster
     * from that wait. Visibility is additive — nothing is retracted.
     */
    val manageTabs: List<ManageTab>
        get() = if (!isPoster && !metadataSettled) {
            emptyList()
        } else {
            manageTabs(isPoster, isFinalApprover, isInternalReceiver, metadataFailed)
        }

    val canManage: Boolean get() = manageTabs.isNotEmpty()

    val sections: List<ApprovalSection> get() = approvalSections(isPoster, isFinalApprover)

    val activeSection: ApprovalSection get() = resolveSection(sections, section)

    /** The badge surface the open list is — where its row badges and reads go. */
    val badgeSurface: BadgeSurface?
        get() = when {
            workspace != Workspace.Manage -> null
            tab == ManageTab.Drafts -> BadgeSurface.Drafts
            tab == ManageTab.Published -> BadgeSurface.Published
            else -> when (activeSection) {
                ApprovalSection.Sent -> BadgeSurface.Sent
                ApprovalSection.Received -> BadgeSurface.Received
                ApprovalSection.Finalized -> BadgeSurface.Finalized
            }
        }

    /** A row's unread of one kind on the open list: REPORT beside the name, COMMENT on the kebab. */
    fun rowBadge(row: ReportSummary, kind: BadgeKind): Int = badges.count(badgeSurface, kind, row.id)

    /** ZL-20659 kept: a count on a section known to be empty is hidden. */
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
     * The Approvals chip sums only the sub-tabs this user can see (the web's
     * `approvalsBadgeCount`): a count on a hidden sub-tab could never be cleared.
     */
    val approvalsBadge: Int get() = sections.sumOf { sectionBadge(it) }

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

    /**
     * The shared confirm: a warning disc, a title, a message, Cancel · confirm
     * [· secondary]. [busy] keeps it up, spinning, until its request settles;
     * [reportId] lets a remote delete of that report close it.
     */
    data class Confirm(
        val action: ConfirmAction,
        val title: String,
        val message: String,
        val confirmLabel: String,
        val danger: Boolean,
        val secondaryLabel: String? = null,
        val busy: Boolean = false,
        val reportId: String? = null,
    ) : ReportDialog

    data class TemplatePicker(val templates: List<StockTemplate>, val selected: Int) : ReportDialog

    /** "Save As" — its own input, never the live draft name (ZL-21540). */
    data class DraftName(val name: String) : ReportDialog

    /** Send for comments: the recipient picker. From the editor it saves first, then sends. */
    data class SendPicker(
        val reportId: String?,
        val fromEditor: Boolean,
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
        val type: PublishType? = null,
        /** The unit chat's live documents; empty hides the Replace card (no empty picker on a first publish). */
        val replaceOptions: List<ReplaceTarget> = emptyList(),
        val replaceChatId: String = "",
    ) : ReportDialog

    /** ZL-21415 "Send for Chat": one crew member gets the PDF in a 1:1 chat. */
    data class SendForChat(
        val report: ReportSummary,
        val selected: String? = null,
        val search: String = "",
        val sending: Boolean = false,
    ) : ReportDialog

    data class Comments(
        val reportId: String,
        val reportName: String,
        /** No composer: a locked report, or a viewer who is neither the creator nor a comment recipient. */
        val readOnly: Boolean,
        /** Why the composer is missing, under the thread. */
        val closedNote: String = "Comments are closed on a report approved for publishing.",
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

    /**
     * ZL-21512 — Approve asks first: the chooser (with / without signature),
     * then, on [sign], the signing screen locked to "Approve with Signature".
     * Closes only on success, so a failed approve can be retried.
     */
    data class Approve(
        val report: ReportSummary,
        val request: ApprovalRequest,
        val sign: Boolean = false,
        val uploading: Boolean = false,
    ) : ReportDialog

    data class Reject(val report: ReportSummary, val request: ApprovalRequest, val reason: String = "") : ReportDialog

    data class ReminderCompose(val report: ReportSummary, val message: String = "") : ReportDialog

    data class Reminders(
        val reportId: String,
        val reminders: List<com.zillit.desktop.feature.productionreport.domain.ReportReminder>,
    ) : ReportDialog

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

/**
 * How a publish reaches the unit chat — the composer's own three answers.
 * The publish API knows two values, so a Replace travels as NEW and is told
 * apart by the target it names; the wipe flag follows the CHOICE, never the
 * presence of a target (a Replace with an empty target must append).
 */
enum class PublishType(val wire: String, val label: String, val hint: String) {
    Continuation(
        "CONTINUATION",
        "Continuation",
        "Keep the existing report in the chat and add this version alongside it.",
    ),
    New("NEW", "New", "Replace every previous report in the chat with this new version."),
    Replace("NEW", "Replace", "Swap one document in the chat for this version; the rest stay."),
    ;

    val wipesChat: Boolean get() = this == New
}

/** What a confirm does when accepted. */
sealed interface ConfirmAction {
    data class DeleteReport(val report: ReportSummary) : ConfirmAction
    data class DeleteTemplate(val template: SavedTemplate) : ConfirmAction
    data object NoApprovers : ConfirmAction

    /** ZL-21398: a Crew Call / Unit Wrap line is empty — the send has not happened. */
    data object MissingCallTimes : ConfirmAction

    /** Nothing published for the shoot day; the report can still be filled in. */
    data object NoPublishedCallSheet : ConfirmAction
    data class RestartReview(val then: SaveIntent) : ConfirmAction

    /** Unsaved changes on Back: confirm discards, secondary saves. */
    data object LeaveEditor : ConfirmAction
}

/** The saves the review-restart guard can hold. */
enum class SaveIntent { Save, SaveAsNew, SaveThenLeave }
