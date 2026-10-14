package com.zillit.desktop.feature.callsheet.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.ApprovalSection
import com.zillit.desktop.feature.callsheet.domain.BadgeKind
import com.zillit.desktop.feature.callsheet.domain.BadgeSurface
import com.zillit.desktop.feature.callsheet.domain.CallSheetStatus
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.SheetBadges
import com.zillit.desktop.feature.callsheet.domain.SheetMetadata
import com.zillit.desktop.feature.callsheet.domain.SheetQuery
import com.zillit.desktop.feature.callsheet.domain.SheetSyncEvent
import com.zillit.desktop.feature.callsheet.domain.SheetTab
import com.zillit.desktop.feature.callsheet.domain.draftsQuery
import com.zillit.desktop.feature.callsheet.domain.initialLanding
import com.zillit.desktop.feature.callsheet.domain.mergeApprovalRequests
import com.zillit.desktop.feature.callsheet.domain.receivedRows
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate

/**
 * The tabs, the lists and their live updates — the web's lazy tab loader
 * (`CallSheetApp.jsx`), its first landing (`initialLanding`), the socket
 * table and the badge reads, as iOS makes them: one sheet's report or comment
 * badge per open (Published included — `ProjectDetailView.swift:2606, 2617`),
 * an Approvals sub-tab's report rows when it is shown, the orphan sweep after
 * a list lands, and Published drained only at app level, for a viewer who has
 * no Published tab (`useReadBadgesOnEntry`).
 *
 * Divergences, each fixing a web bug: the landing waits for the metadata
 * (B-1, B-30); members are read live, never a one-time snapshot (B-3); no
 * eager Published fetch (B-2, B-33).
 */
@Suppress("TooManyFunctions") // One loader per list the web keeps, and the socket and badge plumbing that feeds them.
internal class ListsController(private val ctx: SheetContext) {

    private var lastLoadKey: String? = null
    private var listening = false
    private var waitingForRights = false
    private var landed = false
    private var metadataRequest: CompletableDeferred<SheetMetadata?>? = null

    /** Published leaves already read, keyed `kind:id:count` — a badge that comes back is read again. */
    private var publishedReads: Set<String> = emptySet()

    /** The Approvals sub-tab on screen when the lists last loaded — its entry read fires once per appearance. */
    private var sectionOnScreen: ApprovalSection? = null

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
            )
        }
        if (!listening) {
            listening = true
            listen()
        }
        bootstrap()
        if (viewer.ready) maybeLand() else awaitRights()
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
                    maybeLand()
                    refreshCurrent()
                    return@launchWork
                }
            }
            waitingForRights = false
        }
    }

    /**
     * The first landing, once, and only once the tab set is real: a poster
     * stays on Drafts; a viewer lands on Approvals → Received when they have
     * it, else on their first tab — never on a tab the bar does not show.
     */
    private fun maybeLand() {
        val state = ctx.state
        val settled = state.viewer.ready && (state.isPoster || state.metadataSettled)
        if (landed || !settled) return
        landed = true
        val landing = initialLanding(state.isPoster, state.tabs) ?: return
        ctx.update {
            copy(tab = landing, section = if (landing == SheetTab.Approvals) ApprovalSection.Received else section)
        }
        refreshCurrent()
    }

    private fun bootstrap() {
        ctx.launchWork {
            refreshMetadata()
            maybeLand()
            readPublishedBadges()
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
     * One metadata request at a time — the backend flagged bursts. A settled
     * FAILURE is recorded (a viewer's tabs then fail open); in flight is not
     * failure, so nothing is ever retracted.
     */
    suspend fun refreshMetadata(): SheetMetadata? {
        metadataRequest?.let { return it.await() }
        val project = ctx.projectId() ?: return null
        val request = CompletableDeferred<SheetMetadata?>()
        metadataRequest = request
        val meta = when (val result = ctx.repository.metadata(project)) {
            is ZillitResult.Success -> result.data
            is ZillitResult.Failure -> null
        }
        ctx.update {
            if (meta != null) {
                copy(metadata = meta, metadataLoaded = true, metadataSettled = true, metadataFailed = false)
            } else {
                copy(metadataSettled = true, metadataFailed = !metadataLoaded)
            }
        }
        request.complete(meta)
        metadataRequest = null
        return meta
    }

    /** Load the tab on screen, once per tab + section + rights answer. */
    fun loadCurrentTab() {
        val state = ctx.state
        if (ctx.projectId() == null || state.editor != null || !state.viewer.ready) return
        readPublishedBadges()
        val tab = state.activeTab
        if (tab !in state.tabs) return
        readSectionOnEntry()
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
     * Sent: the PROJECT's signature phase — `buildSentQuery` — never scoped by
     * creator: the sub-tab is already poster-gated, and a second author could
     * not see a colleague's sent sheet. The listing may omit the requests;
     * they are filled in from the approver-scoped listing.
     */
    fun loadSent() {
        val project = ctx.projectId() ?: return
        val me = ctx.state.me.takeIf { it.isNotBlank() }
        ctx.update { copy(lists = lists.copy(sent = lists.sent.copy(loading = true, error = null))) }
        ctx.launchWork {
            val query = SheetQuery(projectId = project, statuses = CallSheetStatus.SIGNATURE_PHASE)
            when (val result = ctx.repository.sheets(query)) {
                is ZillitResult.Success -> {
                    var rows = result.data
                    if (me != null && rows.any { !it.approvalsIncluded }) {
                        val mine = ctx.repository.sheets(SheetQuery(approverId = me))
                        if (mine is ZillitResult.Success) rows = mergeApprovalRequests(rows, mine.data)
                    }
                    ctx.update { copy(lists = lists.copy(sent = SheetList(rows = rows, loaded = true))) }
                    sweepOrphans()
                }
                is ZillitResult.Failure -> ctx.update {
                    copy(
                        lists = lists.copy(
                            sent = lists.sent.copy(loaded = true, loading = false, error = result.error.localised()),
                        ),
                    )
                }
            }
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
            return
        }
        ctx.launchWork { refreshMetadata() }
        load(
            SheetQuery(approverId = me, statuses = CallSheetStatus.SIGNATURE_PHASE),
            select = { received },
            store = { copy(received = it) },
        ) { fetched, _ -> receivedRows(fetched, me) }
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
                    sweepOrphans()
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
                ctx.update { copy(badges = SheetBadges.from(leaves)) }
                readPublishedBadges()
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

    // Badge reads ------------------------------------------------------------------------------------

    /**
     * Opening a sheet (View, Edit, History, the dialogs) reads its REPORT
     * badges on the list on screen; opening its thread reads its COMMENT
     * badges — only when it has some, and never unit-wide. A poster off
     * `final_approver_ids` has no Received section yet can hold a request on
     * their own sheet: opening it from Sent clears those received leaves too,
     * or they would stay on the tile for good.
     */
    fun readRowBadge(sheetId: String, kind: BadgeKind) {
        val state = ctx.state
        if (!state.viewer.ready) return
        val surface = state.surface ?: return
        val source = ctx.services.badges
        if (state.badges.count(surface, kind, sheetId) > 0) source.read(surface, kind, sheetId)
        val receivedHidden = ApprovalSection.Received !in state.sections
        if (surface == BadgeSurface.Sent && receivedHidden &&
            state.badges.count(BadgeSurface.Received, kind, sheetId) > 0
        ) {
            source.read(BadgeSurface.Received, kind, sheetId)
        }
    }

    fun readReport(sheetId: String) = readRowBadge(sheetId, BadgeKind.Report)

    /**
     * An Approvals sub-tab appearing reads its REPORT rows for every sheet
     * (iOS `handleBadgeRead(subTab:)` from each sub-tab's `onAppear`,
     * `ProjectDetailView.swift:2249-2290`) — once per appearance: it fires
     * when the sub-tab on screen changes, not on every reload. The read keeps
     * its level scoping; iOS's per-sheet LOCAL clear dropping `level_1` and
     * `level_2` (`ProjectDetailView.swift:842-846`) is not copied.
     */
    private fun readSectionOnEntry() {
        val state = ctx.state
        val section = if (state.activeTab == SheetTab.Approvals) state.activeSection else null
        if (section == sectionOnScreen) return
        sectionOnScreen = section
        section?.let { ctx.services.badges.readSection(it.surface) }
    }

    private val ApprovalSection.surface: BadgeSurface
        get() = when (this) {
            ApprovalSection.Sent -> BadgeSurface.Sent
            ApprovalSection.Received -> BadgeSurface.Received
            ApprovalSection.Finalized -> BadgeSurface.Finalized
        }

    /**
     * After a list lands, rows that name no sheet any loaded list holds are
     * marked read here once they are a week old (iOS
     * `csSweepOrphanCommentRecords`, `ProjectDetailView.swift:344-352, 905-924`):
     * the sheet was deleted, or sits where this user cannot reach it, and
     * nothing would ever clear them. iOS judges every unit against whatever
     * happens to be loaded; here a unit is judged only once every list that
     * shows it has answered (a unit whose tab this user lacks has no such
     * list — its rows are unreachable by definition), and only while the
     * tab set is known: with no tabs yet, every unit would look hidden.
     */
    private fun sweepOrphans() {
        val state = ctx.state
        if (!state.viewer.ready || !(state.isPoster || state.metadataSettled)) return
        val lists = state.lists
        val keep = listOf(lists.drafts, lists.sent, lists.received, lists.finalized, lists.published)
            .flatMap { list -> list.rows.map { it.id } }
            .filter { it.isNotBlank() }
            .toSet()
        // Nothing loaded yet — every row would look orphaned (`ProjectDetailView.swift:346-348`).
        if (keep.isEmpty()) return
        val sections = state.sections.map { section ->
            when (section) {
                ApprovalSection.Sent -> lists.sent
                ApprovalSection.Received -> lists.received
                ApprovalSection.Finalized -> lists.finalized
            }
        }
        val shown = mapOf(
            SheetBadges.UNIT_DRAFTS to (SheetTab.Drafts to listOf(lists.drafts)),
            SheetBadges.UNIT_APPROVAL to (SheetTab.Approvals to sections),
            SheetBadges.UNIT_PUBLISHED to (SheetTab.Published to listOf(lists.published)),
        )
        val units = shown.filterValues { (tab, feeding) ->
            tab !in state.tabs || feeding.all { it.loaded && !it.loading && it.error == null }
        }.keys
        if (units.isNotEmpty()) ctx.services.badges.sweepOrphans(units, keep)
    }

    /**
     * Published is read sheet by sheet, on View and on Comments (iOS
     * `ProjectDetailView.swift:2606, 2617`) — [readRowBadge] does that like any
     * list. What is left here is the app-level drain: a user who has no
     * Published tab has its rows read as they arrive, report and comment, per
     * id, or they would sit on the tile unread forever. A tab set still
     * unknown reads nothing: a poster's tab must not be drained before it is
     * drawn.
     */
    fun readPublishedBadges() {
        val state = ctx.state
        if (!state.viewer.ready || state.editor != null) return
        val tabs = state.tabs
        val tabSetKnown = state.isPoster || state.metadataSettled
        if (!tabSetKnown) return
        if (SheetTab.Published in tabs) return
        val leaves = state.badges.leavesOf(BadgeSurface.Published)
        val keys = leaves.map { (kind, id, count) -> "${kind.wire}:$id:$count" }.toSet()
        leaves.forEach { (kind, id, count) ->
            if ("${kind.wire}:$id:$count" !in publishedReads) {
                ctx.services.badges.read(BadgeSurface.Published, kind, id)
            }
        }
        publishedReads = keys
    }

    private companion object {
        const val REMINDER = "callsheet:approval:reminder:sent"
        const val RIGHTS_POLLS = 30
        const val RIGHTS_POLL_MS = 1_000L
    }
}
