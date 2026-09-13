package com.zillit.desktop.feature.productionreport.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.productionreport.domain.ApprovalSection
import com.zillit.desktop.feature.productionreport.domain.ManageTab
import com.zillit.desktop.feature.productionreport.domain.ReportBadges
import com.zillit.desktop.feature.productionreport.domain.ReportQuery
import com.zillit.desktop.feature.productionreport.domain.ReportStatus
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.ReportSyncEvent
import com.zillit.desktop.feature.productionreport.domain.SheetMetadata
import com.zillit.desktop.feature.productionreport.domain.countApprovalAssignments
import com.zillit.desktop.feature.productionreport.domain.receivedRows
import com.zillit.desktop.feature.productionreport.domain.resolveSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/**
 * The workspace, the lists and their live updates — the web's lazy tab
 * loader (`ProductionReportApp.jsx:1325-1340`), its loaders (`:1351-1532`),
 * the one-off approver probe (`:1289-1308`), the socket table (`:1538-1662`)
 * and the badge reads the tabs fire on entry.
 */
@Suppress("TooManyFunctions") // One loader per list the web keeps, and the socket and badge plumbing that feeds them.
internal class ListsController(private val ctx: ReportContext) {

    private var lastLoadKey: String? = null
    private var probed = false
    private var listening = false
    private var waitingForRights = false
    private var metadataRequest: CompletableDeferred<SheetMetadata?>? = null

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
        if (viewer.ready) onRightsReady() else awaitRights()
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
                    onRightsReady()
                    lastLoadKey = null
                    loadCurrentTab()
                    return@launchWork
                }
            }
            waitingForRights = false
        }
    }

    private fun onRightsReady() {
        clampWorkspace()
        probeApprovals()
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

    /** One metadata request at a time — the backend flagged bursts. */
    suspend fun refreshMetadata(): SheetMetadata? {
        metadataRequest?.let { return it.await() }
        val project = ctx.projectId() ?: return null
        val request = CompletableDeferred<SheetMetadata?>()
        metadataRequest = request
        val meta = (ctx.repository.metadata(project) as? ZillitResult.Success)?.data
        if (meta != null) {
            ctx.update { copy(metadata = meta) }
            clampWorkspace()
        }
        request.complete(meta)
        metadataRequest = null
        return meta
    }

    /**
     * E8: a view-only user learns whether the approval flow reaches them.
     * Once per start, fails OPEN, and never removes a tab it revealed.
     */
    private fun probeApprovals() {
        val state = ctx.state
        val project = ctx.projectId()
        val needless = state.isPoster || state.me.isBlank()
        if (probed || needless || project == null) return
        probed = true
        ctx.launchWork {
            val statuses = ReportStatus.SIGNATURE_PHASE + ReportStatus.PendingInternalApproval
            when (val rows = ctx.repository.reports(ReportQuery(approverId = ctx.state.me, statuses = statuses))) {
                is ZillitResult.Success -> ctx.update {
                    copy(approverReportCount = countApprovalAssignments(rows.data, me), approverProbeFailed = false)
                }
                is ZillitResult.Failure -> ctx.update { copy(approverProbeFailed = true) }
            }
            clampWorkspace()
        }
    }

    /** E10: load the tab on screen, once per tab + section + rights answer. */
    fun loadCurrentTab() {
        val state = ctx.state
        if (state.workspace != Workspace.Manage || ctx.projectId() == null) return
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
        readVisibleBadges()
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

    fun loadSent() {
        val project = ctx.projectId() ?: return
        val me = ctx.state.me.takeIf { it.isNotBlank() } ?: return
        load(
            ReportQuery(projectId = project, createdById = me, statuses = ReportStatus.SIGNATURE_PHASE),
            select = { sent },
            store = { list -> copy(sent = list) },
        )
    }

    /**
     * The approver-scoped fetch behind Received (and a view-only user's
     * Finalized). Received keeps only signature-phase reports where I hold a
     * current-round FINAL request, and updates the involvement count.
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
                    approverReportCount = 0,
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
            ) { fetched, _ ->
                ctx.update { copy(approverReportCount = countApprovalAssignments(fetched, me)) }
                receivedRows(fetched, me)
            }
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
            readVisibleBadges()
        }
    }

    private fun listen() {
        ctx.launchWork {
            ctx.repository.events.collect { event -> onSync(event) }
        }
        ctx.launchWork {
            ctx.services.badges.leaves.conflate().collect { leaves ->
                ctx.update { copy(badges = ReportBadges.from(leaves)) }
                readVisibleBadges()
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
            DELETED -> event.reportId?.let { id -> ctx.update { copy(lists = lists.without(id)) } }
            VOIDED -> {
                event.reportId?.let { id ->
                    ctx.update {
                        copy(
                            lists = lists.copy(
                                received = lists.received.copy(rows = lists.received.rows.filterNot { it.id == id }),
                            ),
                        )
                    }
                }
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
     * The reads the tabs fire on entry: Approvals drains unmapped units and
     * reads the visible section's approval units; Published clears its
     * comment tab and each published report's unread thread.
     */
    fun readVisibleBadges() {
        val state = ctx.state
        if (state.workspace != Workspace.Manage || state.editor != null) return
        val badges = state.badges
        when (state.tab) {
            ManageTab.Approvals -> {
                if (badges.unmapped > 0) ctx.services.badges.readUnits(badges.unmappedUnits)
                val (count, units) = when (state.activeSection) {
                    ApprovalSection.Sent -> badges.approvalSentUnits to ReportBadges.SENT_UNITS
                    ApprovalSection.Received -> badges.approvalReceivedUnits to ReportBadges.RECEIVED_UNITS
                    ApprovalSection.Finalized -> badges.finalized to ReportBadges.FINALIZED_UNITS
                }
                if (count > 0) ctx.services.badges.readUnits(units)
            }
            ManageTab.Published -> {
                if (badges.published > 0) ctx.services.badges.readCommentTab("published")
                state.lists.published.rows
                    .filter { (badges.commentUnreadByReport[it.id] ?: 0) > 0 }
                    .forEach { ctx.services.badges.readCommentThread(it.id) }
            }
            ManageTab.Drafts -> Unit
        }
    }

    private companion object {
        const val DELETED = "production_report:previous_report:deleted"
        const val VOIDED = "productionreport:approval:voided"
        const val REMINDER = "productionreport:approval:reminder:sent"
        const val APPROVED = "productionreport:approval:approved"
        const val REJECTED = "productionreport:approval:rejected"
        const val RIGHTS_POLLS = 30
        const val RIGHTS_POLL_MS = 1_000L
    }
}
