package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.callsheet.domain.SheetSyncEvent
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.draftsQuery
import com.zillit.desktop.feature.callsheet.domain.mergeApprovalRequests
import com.zillit.desktop.feature.callsheet.domain.receivedRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/**
 * The tabs, the lists and their live updates — the web's lazy tab loader
 * (`CallSheetApp.jsx:1648-1686`), its first landing, the socket table
 * (`:1873-1921`) and the badge reads a tab fires on entry.
 *
 * Divergences, each fixing a web bug: the landing waits for the metadata and
 * a view-only user's Received probe (B-1, B-30); members are read live, never
 * a one-time snapshot (B-3); no eager Published fetch (B-2, B-33).
 */
@Suppress("TooManyFunctions") // One loader per list the web keeps, and the socket and badge plumbing that feeds them.
internal class ListsController(private val ctx: SheetContext) {

    private var lastLoadKey: String? = null
    private var listening = false
    private var waitingForRights = false
    private var landed = false
    private var metadataSettled = false
    private var receivedProbeSettled = false
    private var metadataRequest: CompletableDeferred<SheetMetadata?>? = null

    /** Comment frames go to the open thread; the VM sets this. */
    var onCommentEvent: (SheetSyncEvent) -> Unit = {}

    /** The Permission tab loads its own page; the VM sets this. */
    var onPermissionTab: () -> Unit = {}

    fun start() {
        val viewer = ctx.viewer()
        ctx.update {
            copy(
                viewer = viewer,
                members = ctx.members(),
                canDistribute = ctx.services.publishing.canDistribute(),
                canUseSavedSignatures = ctx.services.signatures != null,
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
            is ListEvent.OpenTab -> {
                ctx.update { copy(tab = event.tab) }
                refreshMembers()
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
            ListEvent.Retry -> refreshCurrent()
            else -> Unit
        }
    }

    /** The web's `refreshAll()`: the list on screen, whatever was loaded before. */
    fun refreshCurrent() {
        lastLoadKey = null
        loadCurrentTab()
    }

    /** The crew list can arrive after the window opens; every tab change reads it again. */
    private fun refreshMembers() {
        val members = ctx.members()
        if (members != ctx.state.members) ctx.update { copy(members = members) }
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
                    ctx.update { copy(viewer = viewer, members = ctx.members()) }
                    waitingForRights = false
                    onRightsReady()
                    refreshCurrent()
                    return@launchWork
                }
            }
            waitingForRights = false
        }
    }

    private fun onRightsReady() {
        if (!ctx.state.isPoster && !receivedProbeSettled) {
            // A view-only user's Approvals tab depends on whether Received names them.
            loadReceived()
        } else {
            receivedProbeSettled = true
        }
        maybeLand()
    }

    /**
     * The first landing, once: a view-only user who the signature flow
     * reaches lands on Approvals → Received; everyone else stays on Drafts.
     */
    private fun maybeLand() {
        val state = ctx.state
        val settled = state.viewer.ready && metadataSettled && receivedProbeSettled
        if (landed || !settled) return
        landed = true
        if (!state.isPoster && state.isApprover) {
            ctx.update { copy(tab = SheetTab.Approvals, section = ApprovalSection.Received) }
            refreshCurrent()
        }
    }

    private fun bootstrap() {
        ctx.launchWork {
            refreshMetadata()
            metadataSettled = true
            maybeLand()
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
        if (meta != null) ctx.update { copy(metadata = meta, metadataLoaded = true) }
        request.complete(meta)
        metadataRequest = null
        return meta
    }

    /** Load the tab on screen, once per tab + section + rights answer. */
    fun loadCurrentTab() {
        val state = ctx.state
        if (ctx.projectId() == null || state.editor != null || !state.viewer.ready) return
        val tab = state.activeTab
        val section = if (tab == SheetTab.Approvals) state.activeSection.name else ""
        val key = "${tab}_$section:${state.isPoster}"
        if (key == lastLoadKey) return
        lastLoadKey = key
        when (tab) {
            SheetTab.Drafts -> loadDrafts()
            SheetTab.Approvals -> when (state.activeSection) {
                ApprovalSection.Sent -> loadSent()
                ApprovalSection.Received -> loadReceived()
                ApprovalSection.Finalized -> loadFinalized()
            }
            SheetTab.Published -> loadPublished()
            SheetTab.Permission -> onPermissionTab()
        }
        readVisibleBadges()
    }

    /** Drafts: the whole project's pre-signature phase for posters, approver-scoped for everyone else. */
    fun loadDrafts() {
        val state = ctx.state
        val project = ctx.projectId() ?: return
        val query = draftsQuery(project, state.isPoster, state.me)
        if (query == null) {
            ctx.update { copy(lists = lists.copy(drafts = SheetList(loaded = true))) }
            return
        }
        load(query, select = { drafts }, store = { list -> copy(drafts = list) })
    }

    /**
     * Sent: my own sheets in the signature phase. The listing may omit the
     * requests; they are filled in from the approver-scoped listing.
     */
    fun loadSent() {
        val project = ctx.projectId() ?: return
        val me = ctx.state.me.takeIf { it.isNotBlank() }
        if (me == null) {
            ctx.update { copy(lists = lists.copy(sent = SheetList(loaded = true))) }
            return
        }
        ctx.update { copy(lists = lists.copy(sent = lists.sent.copy(loading = true, error = null))) }
        ctx.launchWork {
            val query = SheetQuery(projectId = project, createdById = me, statuses = CallSheetStatus.SIGNATURE_PHASE)
            when (val result = ctx.repository.sheets(query)) {
                is ZillitResult.Success -> {
                    var rows = result.data
                    if (rows.any { !it.approvalsIncluded }) {
                        val mine = ctx.repository.sheets(SheetQuery(approverId = me))
                        if (mine is ZillitResult.Success) rows = mergeApprovalRequests(rows, mine.data)
                    }
                    ctx.update { copy(lists = lists.copy(sent = SheetList(rows = rows, loaded = true))) }
                }
                is ZillitResult.Failure -> ctx.update {
                    copy(
                        lists = lists.copy(
                            sent = lists.sent.copy(loaded = true, loading = false, error = result.error.localised()),
                        ),
                    )
                }
            }
            readVisibleBadges()
        }
    }

    /**
     * Received: sheets in the signature phase where I hold a current-round
     * FINAL request — `approver_id` also matches old comment requests.
     */
    fun loadReceived() {
        val me = ctx.state.me
        if (me.isBlank()) {
            ctx.update { copy(lists = lists.copy(received = SheetList(loaded = true))) }
            settleReceivedProbe()
            return
        }
        ctx.launchWork { refreshMetadata() }
        load(
            SheetQuery(approverId = me, statuses = CallSheetStatus.SIGNATURE_PHASE),
            select = { received },
            store = { copy(received = it) },
            onSettled = ::settleReceivedProbe,
        ) { fetched, _ -> receivedRows(fetched, me) }
    }

    private fun settleReceivedProbe() {
        if (receivedProbeSettled) return
        receivedProbeSettled = true
        maybeLand()
    }

    /** Finalized under the caller's own scope — a view-only user never gets the project list. */
    fun loadFinalized() {
        val state = ctx.state
        if (state.isPoster) {
            val project = ctx.projectId() ?: return
            load(
                SheetQuery(projectId = project, statuses = CallSheetStatus.FINALIZED_TAB),
                select = { finalized },
                store = { copy(finalized = it) },
            )
        } else if (state.me.isBlank()) {
            ctx.update { copy(lists = lists.copy(finalized = SheetList(loaded = true))) }
        } else {
            load(
                SheetQuery(approverId = state.me, statuses = CallSheetStatus.FINALIZED_TAB),
                select = { finalized },
                store = { copy(finalized = it) },
            )
        }
    }

    fun loadPublished() {
        val project = ctx.projectId() ?: return
        load(
            SheetQuery(projectId = project, statuses = listOf(CallSheetStatus.Published)),
            select = { published },
            store = { copy(published = it) },
        )
    }

    private fun load(
        query: SheetQuery,
        select: SheetLists.() -> SheetList,
        store: SheetLists.(SheetList) -> SheetLists,
        onSettled: () -> Unit = {},
        merge: (fetched: List<CallSheetSummary>, previous: List<CallSheetSummary>) -> List<CallSheetSummary> =
            { fetched, _ -> fetched },
    ) {
        ctx.update { copy(lists = lists.store(lists.select().copy(loading = true, error = null))) }
        ctx.launchWork {
            when (val result = ctx.repository.sheets(query)) {
                is ZillitResult.Success -> {
                    val previous = ctx.state.lists.select().rows
                    val rows = merge(result.data, previous)
                    ctx.update { copy(lists = lists.store(SheetList(rows = rows, loaded = true))) }
                }
                is ZillitResult.Failure -> ctx.update {
                    copy(
                        lists = lists.store(
                            lists.select().copy(loaded = true, loading = false, error = result.error.localised()),
                        ),
                    )
                }
            }
            onSettled()
            readVisibleBadges()
        }
    }

    private fun listen() {
        ctx.launchWork {
            ctx.repository.events.collect { event -> onSync(event) }
        }
        ctx.launchWork {
            ctx.services.badges.leaves.conflate().collect { leaves ->
                ctx.update { copy(badges = SheetBadges.from(leaves)) }
                readVisibleBadges()
            }
        }
    }

    /**
     * The web's `handleSocketSheetUpdate`: a reminder reloads Received; every
     * workflow event reloads the list on screen; comment frames go to the
     * open thread.
     */
    private fun onSync(event: SheetSyncEvent) {
        if (ctx.projectId() == null) return
        if (event.isComment) {
            onCommentEvent(event)
            return
        }
        when (event.name) {
            REMINDER -> loadReceived()
            else -> if (ctx.state.editor == null) refreshCurrent()
        }
    }

    /**
     * The reads a tab fires on entry: Approvals clears its section's approval
     * units; Published clears its whole comment tab (its rows have no thread).
     */
    fun readVisibleBadges() {
        val state = ctx.state
        if (state.editor != null) return
        val badges = state.badges
        val source = ctx.services.badges
        when (state.activeTab) {
            SheetTab.Approvals -> when (state.activeSection) {
                ApprovalSection.Finalized -> if (badges.finalized > 0) source.readUnit(SheetBadges.UNIT_APPROVED)
                ApprovalSection.Sent -> {
                    if (badges.sentUnits > 0) source.readUnit(SheetBadges.UNIT_REJECTION)
                    if (badges.approvedStatus > 0) source.readUnitLevel(SheetBadges.UNIT_APPROVED, "status")
                }
                ApprovalSection.Received -> if (badges.receivedUnits > 0) {
                    source.readUnit(SheetBadges.UNIT_APPROVAL)
                    source.readUnit(SheetBadges.UNIT_REMINDER)
                }
            }
            SheetTab.Published -> if (badges.published > 0) source.readCommentTab("published")
            SheetTab.Drafts, SheetTab.Permission -> Unit
        }
    }

    private companion object {
        const val REMINDER = "callsheet:approval:reminder:sent"
        const val RIGHTS_POLLS = 30
        const val RIGHTS_POLL_MS = 1_000L
    }
}
