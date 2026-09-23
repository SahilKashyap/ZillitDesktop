package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.BadgeKind
import com.zillit.desktop.feature.productionreport.domain.BadgeSurface
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportQuery
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportSyncEvent
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.receivedRows
import com.zillit.desktop.feature.productionreport.domain.resolveSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/**
 * The workspace, the lists and their live updates — the web's lazy tab
 * loader, its loaders, the socket table (`reportSocketState.js`,
 * `useCloseOnReportGone`) and the badge reads the rows and tabs fire.
 */
@Suppress("TooManyFunctions") // One loader per list the web keeps, and the socket and badge plumbing that feeds them.
internal class ListsController(private val ctx: ReportContext) {

    private var lastLoadKey: String? = null
    private var listening = false
    private var waitingForRights = false
    private var metadataRequest: CompletableDeferred<SheetMetadata?>? = null

    /** Published leaves already read (`kind:id:count`) — a leaf is read again only when its count changes. */
    private var publishedRead: Set<String> = emptySet()

    /** Comment frames go to the open thread; the VM sets this. */
    var onCommentEvent: (ReportSyncEvent) -> Unit = {}

    fun start() {
        val viewer = ctx.viewer()
        ctx.update {
            copy(
                viewer = viewer,
                members = ctx.members(),
                canDistribute = ctx.services.publishing.canDistribute(),
                workspace = if (!hasChat && workspace == Workspace.Chat) Workspace.Manage else workspace,
            )
        }
        if (!listening) {
            listening = true
            listen()
        }
        bootstrap()
        if (viewer.ready) clampWorkspace() else awaitRights()
        lastLoadKey = null
        loadCurrentTab()
    }

    fun onEvent(event: ListEvent) {
        when (event) {
            is ListEvent.SetWorkspace -> {
                ctx.update { copy(workspace = event.workspace) }
                loadCurrentTab()
            }
            is ListEvent.OpenTab -> {
                ctx.update { copy(tab = event.tab, workspace = Workspace.Manage) }
                loadCurrentTab()
            }
            is ListEvent.OpenSection -> {
                ctx.update { copy(section = event.section) }
                loadCurrentTab()
            }
            is ListEvent.SetDraftChip -> ctx.update { copy(draftChip = event.chip) }
            is ListEvent.SetDraftsView -> ctx.update { copy(draftsView = event.view) }
            is ListEvent.SetApprovalsView -> ctx.update { copy(approvalsView = event.view) }
            ListEvent.ToggleOlderPublished -> ctx.update { copy(showOlderPublished = !showOlderPublished) }
            ListEvent.Retry -> {
                lastLoadKey = null
                loadCurrentTab()
            }
            else -> Unit
        }
    }

    /** Permissions answer after the window opens; ask again until they do, as the web waits on `rightsLoaded`. */
    private fun awaitRights() {
        if (waitingForRights) return
        waitingForRights = true
        ctx.launchWork {
            repeat(RIGHTS_POLLS) {
                delay(RIGHTS_POLL_MS)
                val viewer = ctx.viewer()
                if (viewer.ready) {
                    ctx.update { copy(viewer = viewer) }
                    waitingForRights = false
                    clampWorkspace()
                    lastLoadKey = null
                    loadCurrentTab()
                    return@launchWork
                }
            }
            waitingForRights = false
        }
    }

    /** E1 + E9: never leave a tab or section the user no longer has on screen. */
    private fun clampWorkspace() {
        ctx.update {
            val tabs = manageTabs
            val nextWorkspace = if (tabs.isEmpty() && hasChat) Workspace.Chat else workspace
            copy(
                workspace = nextWorkspace,
                tab = if (tab in tabs || tabs.isEmpty()) tab else tabs.first(),
                section = resolveSection(sections, section),
            )
        }
        drainPublishedBadges()
    }

    private fun bootstrap() {
        ctx.launchWork {
            refreshMetadata()
            when (val stock = ctx.repository.stockTemplates()) {
                is ZillitResult.Success -> ctx.update { copy(stockTemplates = stock.data) }
                is ZillitResult.Failure -> Unit
            }
            refreshSavedTemplates()
        }
    }

    suspend fun refreshSavedTemplates() {
        when (val saved = ctx.repository.savedTemplates()) {
            is ZillitResult.Success -> ctx.update { copy(savedTemplates = saved.data) }
            is ZillitResult.Failure -> Unit
        }
    }

    /**
     * One metadata request at a time — the backend flagged bursts. The
     * answer settles a viewer's tab set; a failure is recorded so the tabs
     * fail OPEN rather than reading "names nobody".
     */
    suspend fun refreshMetadata(): SheetMetadata? {
        metadataRequest?.let { return it.await() }
        val project = ctx.projectId() ?: return null
        val request = CompletableDeferred<SheetMetadata?>()
        metadataRequest = request
        val meta = (ctx.repository.metadata(project) as? ZillitResult.Success)?.data
        ctx.update {
            copy(
                metadata = meta ?: metadata,
                metadataSettled = true,
                metadataFailed = meta == null,
            )
        }
        clampWorkspace()
        if (meta != null) loadCurrentTab()
        request.complete(meta)
        metadataRequest = null
        return meta
    }

    /** E10: load the tab on screen, once per tab + section + rights answer. */
    fun loadCurrentTab() {
        val state = ctx.state
        if (state.workspace != Workspace.Manage || ctx.projectId() == null) return
        if (state.tab !in state.manageTabs) return
        val key = "${state.tab}_${if (state.tab == ManageTab.Approvals) state.activeSection else ""}:${state.isPoster}"
        if (key == lastLoadKey) return
        lastLoadKey = key
        when (state.tab) {
            ManageTab.Drafts -> loadDrafts()
            ManageTab.Approvals -> when (state.activeSection) {
                ApprovalSection.Sent -> loadSent()
                ApprovalSection.Received -> loadApproverSheets(received = true)
                ApprovalSection.Finalized -> refreshFinalized()
            }
            ManageTab.Published -> loadPublished()
        }
        drainPublishedBadges()
    }

    /** Drafts: the whole project for authors, approver-scoped for everyone else. */
    fun loadDrafts() {
        val state = ctx.state
        val project = ctx.projectId() ?: return
        if (!state.isPoster && state.me.isBlank()) {
            ctx.update { copy(lists = lists.copy(drafts = ReportList(loaded = true))) }
            return
        }
        val query = ReportQuery(
            projectId = project,
            statuses = ReportStatus.DRAFT_TAB,
            approverId = if (state.isPoster) null else state.me,
        )
        load(query, select = { drafts }, store = { list -> copy(drafts = list) }) { fetched, previous ->
            // A row saved a moment ago may not be in a stale answer yet.
            val fetchedIds = fetched.map { it.id }.toSet()
            previous.filter { it.id !in fetchedIds } + fetched
        }
    }

    /**
     * Sent is the PROJECT's signature phase, not "reports I made": the
     * sub-tab is already poster-gated, so a second author sees a colleague's
     * sent report too (the creator scope hid it).
     */
    fun loadSent() {
        val project = ctx.projectId() ?: return
        load(
            ReportQuery(projectId = project, statuses = ReportStatus.SIGNATURE_PHASE),
            select = { sent },
            store = { list -> copy(sent = list) },
        )
    }

    /**
     * The approver-scoped fetch behind Received (and a view-only user's
     * Finalized). Received keeps only signature-phase reports where I hold a
     * current-round FINAL request.
     */
    fun loadApproverSheets(received: Boolean) {
        ctx.launchWork { refreshMetadata() }
        val me = ctx.state.me
        if (me.isBlank()) {
            ctx.update {
                copy(
                    lists = if (received) {
                        lists.copy(received = ReportList(loaded = true))
                    } else {
                        lists.copy(finalized = ReportList(loaded = true))
                    },
                )
            }
            return
        }
        val statuses = if (received) ReportStatus.SIGNATURE_PHASE else ReportStatus.FINALIZED_TAB
        if (received) {
            load(
                ReportQuery(approverId = me, statuses = statuses),
                select = { this.received },
                store = { copy(received = it) },
            ) { fetched, _ -> receivedRows(fetched, me) }
        } else {
            load(
                ReportQuery(approverId = me, statuses = statuses),
                select = { finalized },
                store = { copy(finalized = it) },
            )
        }
    }

    /** Finalized under the caller's own scope — a view-only user never gets the project list. */
    fun refreshFinalized() {
        if (ctx.state.isPoster) {
            val project = ctx.projectId() ?: return
            load(
                ReportQuery(projectId = project, statuses = ReportStatus.FINALIZED_TAB),
                select = { finalized },
                store = { copy(finalized = it) },
            )
        } else {
            loadApproverSheets(received = false)
        }
    }

    fun loadPublished() {
        val project = ctx.projectId() ?: return
        load(
            ReportQuery(projectId = project, statuses = listOf(ReportStatus.Published)),
            select = { published },
            store = { copy(published = it) },
        )
    }

    private fun load(
        query: ReportQuery,
        select: ReportLists.() -> ReportList,
        store: ReportLists.(ReportList) -> ReportLists,
        merge: (fetched: List<ReportSummary>, previous: List<ReportSummary>) -> List<ReportSummary> =
            { fetched, _ -> fetched },
    ) {
        ctx.update { copy(lists = lists.store(lists.select().copy(loading = true, error = null))) }
        ctx.launchWork {
            when (val result = ctx.repository.reports(query)) {
                is ZillitResult.Success -> {
                    val mine = result.data.filter { ctx.kind.owns(it.reportType) }
                    val previous = ctx.state.lists.select().rows
                    val rows = merge(mine, previous)
                    ctx.update { copy(lists = lists.store(ReportList(rows = rows, loaded = true))) }
                }
                is ZillitResult.Failure -> ctx.update {
                    copy(
                        lists = lists.store(
                            lists.select().copy(loaded = true, loading = false, error = result.error.localised()),
                        ),
                    )
                }
            }
        }
    }

    private fun listen() {
        ctx.launchWork {
            ctx.repository.events.collect { event -> onSync(event) }
        }
        ctx.launchWork {
            ctx.services.badges.leaves.conflate().collect { leaves ->
                ctx.update { copy(badges = ReportBadges.from(leaves)) }
                drainPublishedBadges()
            }
        }
    }

    /** The web's `handleSocketReportUpdate` table — targeted reloads, never a full refresh. */
    @Suppress("CyclomaticComplexMethod") // One branch per wire event, as the web's table reads.
    private fun onSync(event: ReportSyncEvent) {
        if (ctx.projectId() == null) return
        if (event.isComment) {
            onCommentEvent(event)
            return
        }
        when (event.name) {
            ReportSyncEvent.DELETED -> event.reportId?.let { id ->
                ctx.update { copy(lists = lists.without(id)) }
                closeGone(reportId = id, requestIds = emptyList(), notice = REPORT_DELETED_NOTICE)
            }
            ReportSyncEvent.VOIDED -> {
                event.reportId?.let { id ->
                    ctx.update {
                        copy(
                            lists = lists.copy(
                                received = lists.received.copy(rows = lists.received.rows.filterNot { it.id == id }),
                            ),
                        )
                    }
                }
                closeGone(reportId = null, requestIds = event.requestIds, notice = REQUEST_VOIDED_NOTICE)
                loadDrafts()
                loadSent()
                loadApproverSheets(received = true)
            }
            REMINDER -> {
                loadSent()
                loadApproverSheets(received = true)
            }
            else -> onTransition(event)
        }
    }

    private fun onTransition(event: ReportSyncEvent) {
        when {
            event.status == null && event.name == APPROVED -> {
                loadSent()
                loadApproverSheets(received = true)
                refreshFinalized()
            }
            event.status == null && event.name == REJECTED -> {
                loadSent()
                loadApproverSheets(received = true)
            }
            event.status == ReportStatus.Draft -> loadDrafts()
            event.status == ReportStatus.Published -> {
                loadSent()
                refreshFinalized()
                loadPublished()
            }
            event.status == ReportStatus.ApprovedForPublish -> {
                loadSent()
                loadApproverSheets(received = true)
                refreshFinalized()
            }
            else -> {
                loadDrafts()
                loadSent()
                loadApproverSheets(received = true)
            }
        }
    }

    /**
     * `useCloseOnReportGone`: a dialog showing a report deleted elsewhere,
     * or acting on a superseded request, closes with one notice — and so
     * does the editor holding that report. A BUSY confirm is this user's own
     * delete in flight and closes itself when that settles.
     */
    private fun closeGone(reportId: String?, requestIds: List<String>, notice: String) {
        val state = ctx.state
        val gone = state.dialog.isGone(reportId, requestIds) || state.pdf.isGone(reportId) ||
            (reportId != null && state.editor?.reportId == reportId)
        if (!gone) return
        ctx.update {
            copy(
                dialog = if (dialog.isGone(reportId, requestIds)) null else dialog,
                pdf = if (pdf.isGone(reportId)) null else pdf,
                editor = if (reportId != null && editor?.reportId == reportId) null else editor,
                busy = if (dialog.isGone(reportId, requestIds)) false else busy,
            )
        }
        ctx.toast(notice, isError = true)
    }

    private fun PdfOverlay?.isGone(reportId: String?): Boolean =
        this != null && reportId != null && this.reportId == reportId

    @Suppress("CyclomaticComplexMethod") // One line per dialog that can name a report or a request.
    private fun ReportDialog?.isGone(reportId: String?, requestIds: List<String>): Boolean = when (this) {
        null -> false
        is ReportDialog.Confirm -> !busy && reportId != null && this.reportId == reportId
        is ReportDialog.Publish -> report.id == reportId
        is ReportDialog.Approve -> report.id == reportId || request.id in requestIds
        is ReportDialog.Reject -> report.id == reportId || request.id in requestIds
        is ReportDialog.ReminderCompose -> report.id == reportId
        is ReportDialog.Reminders -> this.reportId == reportId
        is ReportDialog.Comments -> this.reportId == reportId
        is ReportDialog.SendPicker -> reportId != null && this.reportId == reportId
        is ReportDialog.SendForChat -> !sending && report.id == reportId
        is ReportDialog.DocDistConfirm -> report.id == reportId
        else -> false
    }

    // Badges --------------------------------------------------------------------------------

    /**
     * One report's badges of one kind on the open list, read when the row is
     * opened (View, Edit, History, Approve, Reject, Publish, its comments).
     * A poster off `final_approver_ids` has no Received section yet can hold
     * a request on their own report; opening it from Sent clears those
     * received leaves too, or they stay on the tile for good.
     */
    fun readRowBadge(reportId: String, kind: BadgeKind) {
        val state = ctx.state
        if (!state.viewer.ready) return
        val surface = state.badgeSurface ?: return
        if (state.badges.count(surface, kind, reportId) > 0) {
            ctx.services.badges.readBadge(surface, kind, reportId)
        }
        val receivedHidden = ApprovalSection.Received !in state.sections
        if (surface == BadgeSurface.Sent && receivedHidden &&
            state.badges.count(BadgeSurface.Received, kind, reportId) > 0
        ) {
            ctx.services.badges.readBadge(BadgeSurface.Received, kind, reportId)
        }
    }

    /**
     * Published clears whole, keyed on its unread leaves (`useReadBadgesOnEntry`):
     * while the tab is on screen, and — the app-level drain — whenever the tab
     * is NOT one this user has, since nothing else could ever clear those rows.
     */
    private fun drainPublishedBadges() {
        val state = ctx.state
        if (!state.viewer.ready || (!state.isPoster && !state.metadataSettled)) return
        val onScreen = state.workspace == Workspace.Manage && state.tab == ManageTab.Published && state.editor == null
        val hidden = ManageTab.Published !in state.manageTabs
        if (!onScreen && !hidden) return
        val leaves = state.badges.leaves(BadgeSurface.Published)
        val keys = leaves.map { "${it.kind.wire}:${it.reportId}:${it.unread}" }.toSet()
        leaves.zip(keys).forEach { (leaf, key) ->
            if (key !in publishedRead) ctx.services.badges.readBadge(BadgeSurface.Published, leaf.kind, leaf.reportId)
        }
        publishedRead = keys
    }

    private companion object {
        const val REMINDER = "productionreport:approval:reminder:sent"
        const val APPROVED = "productionreport:approval:approved"
        const val REJECTED = "productionreport:approval:rejected"
        const val RIGHTS_POLLS = 30
        const val RIGHTS_POLL_MS = 1_000L
        const val REPORT_DELETED_NOTICE = "This production report was deleted."
        const val REQUEST_VOIDED_NOTICE = "This approval request is no longer active."
    }
}
