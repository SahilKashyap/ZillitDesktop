@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.recce.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.RightsRequestBus
import com.zillit.desktop.core.permissions.rightsRefusalMessage
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.socket.SocketEventName
import com.zillit.desktop.core.socket.SocketMessage
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.data.RECCE_SYNC_EVENTS
import com.zillit.desktop.feature.recce.data.eventProjectId
import com.zillit.desktop.feature.recce.data.parseDeletedIds
import com.zillit.desktop.feature.recce.data.parseRecceEvent
import com.zillit.desktop.feature.recce.domain.LatLng
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceHost
import com.zillit.desktop.feature.recce.domain.RecceQuery
import com.zillit.desktop.feature.recce.domain.RecceRepository
import com.zillit.desktop.feature.recce.domain.RecceRoute
import com.zillit.desktop.feature.recce.domain.RecceStatus
import com.zillit.desktop.feature.recce.domain.RecceViewer
import com.zillit.desktop.feature.recce.domain.StopKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Recce: a list of scout days, a detail page, and a form — the web's three
 * routes as one window that pages inside itself. The container logic is the
 * web's `ReccePage.jsx`: server-side list paging with the tab badges kept
 * live off the list responses, the delete confirm, the socket upserts, and
 * the rights ask on every posting or download action.
 */
class RecceViewModel(
    private val repository: RecceRepository,
    private val host: RecceHost,
    private val units: suspend () -> ZillitResult<List<ProductionUnit>>,
    private val resolveViewer: () -> RecceViewer,
    private val newUniqueId: () -> String,
    private val timezone: () -> String,
    /** The open production — a broadcast for another one never touches this list. */
    private val currentProjectId: () -> String? = { null },
    /**
     * The socket, so a scout day added by somebody else appears without a
     * reopen. Null in tests and on a build with no socket.
     */
    private val events: SocketEventBus? = null,
    /** Where a refused press asks an admin; null leaves the refusal as a toast. */
    private val rights: RightsRequestBus? = null,
) : ZillitViewModel<RecceUiState, RecceEvent, RecceEffect>(RecceUiState()) {

    /** Only the newest list request may write to state — the web's `listSeq`. */
    private var listSeq = 0
    private var searchJob: Job? = null
    private var routeJob: Job? = null
    private val previewsInFlight = mutableSetOf<LatLng>()

    /** The committed search — what the last list request was asked with. */
    private var search = ""

    /** The window's theme, so the map pictures are drawn to match; set by [onEvent]. */
    private var dark = false

    /** Whether the badges have been answered once — before that a zero badge proves nothing. */
    private var countsLoaded = false

    fun start() {
        // The window reopens on the list, as the web's route does — unless a
        // form was closed mid-edit, which keeps the draft rather than losing it.
        setState {
            val keepForm = editor?.dirty == true
            copy(
                viewer = resolveViewer(),
                route = if (keepForm) route else ReccePage.Index,
                selected = if (keepForm) selected else null,
                editor = if (keepForm) editor else null,
                routeMap = if (keepForm) routeMap else null,
                pdf = null,
                deleteTarget = null,
                leavePrompt = false,
            )
        }
        loadList()
        // The initial list request is unfiltered, so it already answers "all".
        loadCounts(covered = RecceFilter.All)
        launch { units().orError()?.let { list -> setState { copy(units = list) } } }
        launch { repository.crew().orError()?.let { crew -> setState { copy(crew = crew) } } }
        listenForChanges()
    }

    /** The rights payload landed after [start] — the web refetches on the rights sockets. */
    fun onRightsChanged() {
        setState { copy(viewer = resolveViewer()) }
    }

    /** Set up once: [start] runs on every visit to the window. */
    private var listening = false

    private fun listenForChanges() {
        val bus = events ?: return
        if (listening) return
        listening = true
        launch {
            bus.onAny(RECCE_SYNC_EVENTS).collect { message ->
                val project = eventProjectId(message.payload)
                val mine = currentProjectId()
                if (project != null && mine != null && project != mine) return@collect
                if (message.event == DELETED) {
                    onDeletedElsewhere(parseDeletedIds(message.payload))
                } else {
                    onUpsertElsewhere(message)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: RecceEvent) {
        when (event) {
            RecceEvent.Refresh -> {
                loadList()
                loadCounts(covered = if (search.isEmpty()) currentState.filter else null)
            }
            is RecceEvent.Filter -> changeFilter(event.filter)
            is RecceEvent.Search -> typeSearch(event.query)
            is RecceEvent.FilterUnit -> setState { copy(unitFilter = event.unit) }
            is RecceEvent.GoToPage -> goToPage(event.page)
            is RecceEvent.PageSize -> resize(event.size)
            is RecceEvent.Open -> open(event.id)
            RecceEvent.Back -> backToIndex()
            RecceEvent.New -> if (!refuses(RightsKind.Post)) {
                setState { copy(route = ReccePage.Form(null), editor = RecceEditor(uniqueId = newUniqueId())) }
            }
            is RecceEvent.Edit -> if (!refuses(RightsKind.Post)) edit(event.id)
            is RecceEvent.Delete -> requestDelete(event.id)
            RecceEvent.ConfirmDelete -> delete()
            RecceEvent.CancelDelete -> if (!currentState.deleting) setState { copy(deleteTarget = null) }
            RecceEvent.GeneratePdf -> generatePdf()
            RecceEvent.PrintPdf -> printPdf()
            RecceEvent.DownloadPdf -> downloadPdf()
            RecceEvent.ClosePdf -> setState { copy(pdf = null) }
            is RecceEvent.OpenUrl -> sendEffect(RecceEffect.OpenUrl(event.url))
            is RecceEvent.NeedPreview -> preview(event.pin)
            is RecceEvent.Theme -> {
                if (dark != event.dark) {
                    dark = event.dark
                    setState { copy(previews = emptyMap()) }
                    currentState.selected?.let { plotRoute(it) }
                }
            }
            is RecceEvent.EditorChanged -> editEditor {
                copy(
                    title = event.title ?: title,
                    unit = if (event.clearUnit) "" else event.unit ?: unit,
                    dateYmd = event.dateYmd ?: dateYmd,
                    station = event.station ?: station,
                    weather = event.weather ?: weather,
                    crewNote = event.crewNote ?: crewNote,
                    rdv = event.rdv ?: rdv,
                    errors = errors - touched(event),
                )
            }
            RecceEvent.AddStop -> editEditor { copy(stops = stops + StopEditor(kind = StopKind.Continue)) }
            is RecceEvent.StopChanged -> editEditor {
                copy(stops = stops.mapIndexed { i, s -> if (i == event.index) event.stop else s })
            }
            is RecceEvent.RemoveStop -> editEditor { copy(stops = stops.filterIndexed { i, _ -> i != event.index }) }
            is RecceEvent.MoveStop -> editEditor { copy(stops = stops.moved(event.index, event.delta)) }
            RecceEvent.AddPerson -> editEditor { copy(personnel = personnel + PersonEditor()) }
            is RecceEvent.AddCrewMember -> addCrew(event.userId)
            is RecceEvent.PersonChanged -> editEditor {
                copy(personnel = personnel.mapIndexed { i, p -> if (i == event.index) event.person else p })
            }
            is RecceEvent.RemovePerson -> editEditor {
                copy(personnel = personnel.filterIndexed { i, _ -> i != event.index })
            }
            RecceEvent.SaveDraft -> save(RecceStatus.Draft)
            RecceEvent.Publish -> save(RecceStatus.Published)
            RecceEvent.RequestCancel -> requestCancel()
            RecceEvent.KeepEditing -> setState { copy(leavePrompt = false) }
            RecceEvent.DiscardChanges -> {
                setState { copy(leavePrompt = false) }
                leaveForm()
            }
            RecceEvent.DismissError -> setState { copy(error = null) }
        }
    }

    // ------------------------------------------------------------------ list

    /**
     * Loads one page. Arguments override the committed state for a request a
     * state change has only just scheduled, as the web's `opts` do; a cleared
     * search must win over the old one, so blanks are meaningful here.
     */
    private fun loadList(
        page: Int = currentState.page,
        limit: Int = currentState.pageSize,
        filter: RecceFilter = currentState.filter,
        query: String = search,
    ) {
        val seq = ++listSeq
        setState { copy(loading = true) }
        launch {
            val request = RecceQuery(page = page, limit = limit, status = filter.status, search = query)
            val result = repository.recces(request)
            if (seq != listSeq) return@launch
            when (result) {
                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error.localised()) }
                is ZillitResult.Success -> setState {
                    // With no search, `total` for this tab IS its project-wide
                    // count — a free badge refresh. With a search it is the
                    // match count, a different number entirely.
                    val badges = if (query.isEmpty()) counts.with(filter, result.data.total) else counts
                    copy(loading = false, recces = result.data.rows, total = result.data.total, counts = badges)
                }
            }
        }
    }

    /**
     * The badges count the whole project, so they need totals the list
     * response cannot always give — `limit: 1` makes each a near-empty
     * response read for `total`. [covered] names the badge the list request
     * just answered for free; drafts is `all − published`. Failures stay
     * silent: the badges are decoration.
     */
    private fun loadCounts(covered: RecceFilter?) {
        launch {
            val all = if (covered != RecceFilter.All) countOf(null) else null
            val published = if (covered != RecceFilter.Published) countOf(RecceStatus.Published) else null
            setState {
                val nextAll = all ?: counts.all
                val nextPublished = published ?: counts.published
                copy(counts = RecceCounts(nextAll, nextPublished, (nextAll - nextPublished).coerceAtLeast(0)))
            }
            countsLoaded = true
        }
    }

    private suspend fun countOf(status: RecceStatus?): Int? =
        (repository.recces(RecceQuery(page = 1, limit = 1, status = status)) as? ZillitResult.Success)?.data?.total

    private fun changeFilter(next: RecceFilter) {
        if (next == currentState.filter) return
        setState { copy(filter = next, page = 1) }
        // A tab whose badge reads 0 has nothing to fetch — the badge is kept
        // live by the list responses, the sockets and every mutation. Only
        // valid with no search, where badge and query agree on "empty".
        if (countsLoaded && search.isEmpty() && currentState.counts.of(next) == 0) {
            listSeq++
            setState { copy(recces = emptyList(), total = 0, loading = false) }
            return
        }
        loadList(page = 1, filter = next)
    }

    private fun typeSearch(typed: String) {
        setState { copy(query = typed) }
        searchJob?.cancel()
        val next = typed.trim()
        if (next == search) return
        searchJob = launch {
            delay(SEARCH_DEBOUNCE_MS)
            search = next
            setState { copy(page = 1) }
            loadList(page = 1, query = next)
        }
    }

    private fun goToPage(page: Int) {
        val target = page.coerceIn(1, currentState.pageCount)
        if (target == currentState.page) return
        setState { copy(page = target) }
        loadList(page = target)
    }

    /** A size change restarts at page 1 — the rows come back a page at a time. */
    private fun resize(size: Int) {
        if (size == currentState.pageSize) return
        setState { copy(pageSize = size, page = 1) }
        loadList(page = 1, limit = size)
    }

    // ---------------------------------------------------------------- detail

    private fun open(id: String) {
        val known = currentState.recces.firstOrNull { it.id == id }
        setState { copy(route = ReccePage.Detail(id), selected = known, detailLoading = true, routeMap = null) }
        known?.let { plotRoute(it) }
        launch {
            when (val result = repository.recce(id)) {
                is ZillitResult.Failure -> {
                    // A failed fetch falls back to the list, as the web does.
                    setState { copy(detailLoading = false, route = ReccePage.Index, selected = null) }
                    sendEffect(RecceEffect.Notice(result.error.localised(), success = false))
                }
                is ZillitResult.Success -> {
                    setState { copy(detailLoading = false, selected = result.data) }
                    plotRoute(result.data)
                }
            }
        }
    }

    private fun backToIndex() {
        setState { copy(route = ReccePage.Index, selected = null, editor = null, routeMap = null) }
    }

    /**
     * Draws the route picture for [recce] unless the one on screen was drawn
     * from the same itinerary — every stop resolved, then one fetch.
     */
    private fun plotRoute(recce: Recce) {
        val signature = "$dark:" + RecceRoute.signature(recce.itinerary)
        if (currentState.routeMap?.signature == signature) return
        routeJob?.cancel()
        setState { copy(routeMap = RouteMapState(signature = signature)) }
        routeJob = launch {
            val pins = RecceRoute.resolve(recce.itinerary) { query, country -> host.geocode(query, country) }
            val image = if (pins.isEmpty()) null else host.routeMap(pins, ROUTE_WIDTH_PX, ROUTE_HEIGHT_PX, dark)
            setState {
                if (routeMap?.signature != signature) {
                    this
                } else {
                    copy(routeMap = RouteMapState(signature, image = image, loading = false, plotted = pins.size))
                }
            }
        }
    }

    private fun preview(pin: LatLng) {
        if (pin in currentState.previews || !previewsInFlight.add(pin)) return
        launch {
            val image = host.previewMap(pin, PREVIEW_WIDTH_PX, PREVIEW_HEIGHT_PX, dark)
            previewsInFlight.remove(pin)
            setState { copy(previews = previews + (pin to image)) }
        }
    }

    // ------------------------------------------------------------------ form

    private fun edit(id: String) {
        val known = currentState.selected?.takeIf { it.id == id } ?: currentState.recces.firstOrNull { it.id == id }
        if (known != null) {
            setState { copy(route = ReccePage.Form(id), editor = RecceEditor.from(known), selected = known) }
        } else {
            setState { copy(route = ReccePage.Form(id), detailLoading = true) }
        }
        launch {
            when (val result = repository.recce(id)) {
                is ZillitResult.Failure -> setState {
                    if (editor == null) {
                        copy(detailLoading = false, route = ReccePage.Index, error = result.error.localised())
                    } else {
                        copy(detailLoading = false)
                    }
                }
                is ZillitResult.Success -> setState {
                    // A fresh record replaces the seed only while it is untouched.
                    val fresh = RecceEditor.from(result.data)
                    val keep = editor?.takeIf { it.id == id && it.dirty }
                    copy(detailLoading = false, editor = keep ?: fresh, selected = result.data)
                }
            }
        }
    }

    private fun addCrew(userId: String) {
        val member = currentState.crew.firstOrNull { it.userId == userId } ?: return
        editEditor {
            if (personnel.any { it.userId == userId }) return@editEditor this
            val row = PersonEditor(
                userId = member.userId,
                name = member.name,
                // The designation is an i18n key; the web stores its translation.
                role = member.role.takeIf { it.isNotBlank() }?.localised().orEmpty(),
                email = member.email,
                contact = member.contact,
            )
            copy(personnel = personnel.filterNot { it.isBlankSeed } + row)
        }
    }

    private fun save(status: RecceStatus) {
        val editor = currentState.editor ?: return
        if (editor.saving != null) return
        if (refuses(RightsKind.Post)) return
        if (status == RecceStatus.Published) {
            val problems = editor.publishProblems()
            if (problems.isNotEmpty()) {
                setState { copy(editor = editor.copy(errors = problems)) }
                sendEffect(RecceEffect.ScrollToTop)
                return
            }
        }
        setState { copy(editor = editor.copy(saving = status, errors = emptyMap()), busy = true, leavePrompt = false) }
        launch {
            val draft = editor.toDraft(status, timezone())
            val id = editor.id
            val outcome = if (id == null) repository.create(draft) else repository.update(id, draft).map { id }
            when (outcome) {
                is ZillitResult.Failure -> {
                    setState { copy(busy = false, editor = editor.copy(saving = null)) }
                    sendEffect(RecceEffect.Notice(outcome.error.localised(), success = false))
                }
                is ZillitResult.Success -> {
                    setState { copy(busy = false, editor = null) }
                    val saved = if (status == RecceStatus.Published) S.recce_published_toast else S.ah_draft_saved_msg
                    sendEffect(RecceEffect.Notice(str(saved)))
                    // A create adds a row and a publish moves one between
                    // tabs — the list re-answers the active badge itself.
                    loadList()
                    loadCounts(covered = if (search.isEmpty()) currentState.filter else null)
                    open(outcome.data)
                }
            }
        }
    }

    private fun requestCancel() {
        val editor = currentState.editor ?: return
        if (editor.dirty) setState { copy(leavePrompt = true) } else leaveForm()
    }

    private fun leaveForm() {
        val editor = currentState.editor
        setState { copy(editor = null, leavePrompt = false) }
        editor?.id?.let { open(it) } ?: backToIndex()
    }

    // ---------------------------------------------------------------- delete

    private fun requestDelete(id: String) {
        if (refuses(RightsKind.Post)) return
        val title = (currentState.selected?.takeIf { it.id == id } ?: currentState.recces.firstOrNull { it.id == id })
            ?.title?.takeIf { it.isNotBlank() } ?: str(S.desktop_recce_this_recce)
        setState { copy(deleteTarget = DeleteTarget(id, title)) }
    }

    private fun delete() {
        val target = currentState.deleteTarget ?: return
        if (currentState.deleting) return
        setState { copy(deleting = true) }
        launch {
            when (val result = repository.delete(target.id)) {
                is ZillitResult.Failure -> {
                    setState { copy(deleting = false, deleteTarget = null) }
                    sendEffect(RecceEffect.Notice(result.error.localised(), success = false))
                }
                is ZillitResult.Success -> afterDelete(target.id)
            }
        }
    }

    /**
     * Drops the row, drops the reader off it if they were on it, and refills
     * the page from the server. Deleting the last row of the last page steps
     * back instead of stranding an empty page.
     */
    private fun afterDelete(id: String) {
        val before = currentState
        val viewingDeleted = before.route.let {
            (it as? ReccePage.Detail)?.id == id || (it as? ReccePage.Form)?.id == id
        }
        val emptied = before.recces.size == 1 && before.recces.single().id == id && before.page > 1
        val nextPage = if (emptied) before.page - 1 else before.page
        setState {
            copy(
                deleting = false,
                deleteTarget = null,
                recces = recces.filterNot { it.id == id },
                page = nextPage,
                route = if (viewingDeleted) ReccePage.Index else route,
                selected = if (viewingDeleted) null else selected,
                editor = if (viewingDeleted) null else editor,
                routeMap = if (viewingDeleted) null else routeMap,
            )
        }
        sendEffect(RecceEffect.Notice(str(S.recce_deleted)))
        loadList(page = nextPage)
        loadCounts(covered = if (search.isEmpty()) currentState.filter else null)
    }

    // ------------------------------------------------------------------- pdf

    private fun generatePdf() {
        val recce = currentState.selected ?: return
        if (refuses(RightsKind.Download)) return
        val name = reportName(recce)
        setState { copy(pdf = ReccePdfViewer(name = name)) }
        launch {
            when (val fetched = fetchReport(recce.id)) {
                is ZillitResult.Failure -> {
                    setState { copy(pdf = null) }
                    sendEffect(RecceEffect.Notice(fetched.error.localised(), success = false))
                }
                is ZillitResult.Success -> {
                    val (fileName, bytes) = fetched.data
                    setState { copy(pdf = pdf?.copy(name = fileName, bytes = bytes)) }
                    sendEffect(RecceEffect.Notice(str(S.desktop_pdf_generated)))
                    when (val pages = host.renderPages(bytes, PDF_PAGE_WIDTH_PX)) {
                        is ZillitResult.Failure -> setState {
                            copy(pdf = pdf?.copy(loading = false, failed = pages.error.localised()))
                        }
                        is ZillitResult.Success -> setState {
                            copy(pdf = pdf?.copy(loading = false, pages = pages.data))
                        }
                    }
                }
            }
        }
    }

    /** Print (ZL-19845) — distinct from Generate PDF; the web opens the print dialog instead of a tab. */
    private fun printPdf() {
        val recce = currentState.selected ?: return
        if (refuses(RightsKind.Download)) return
        val held = currentState.pdf?.bytes
        setState { copy(busy = held == null, pdf = pdf?.copy(printing = true)) }
        launch {
            val outcome = when {
                held != null -> host.printPdf(currentState.pdf?.name ?: reportName(recce), held)
                else -> when (val fetched = fetchReport(recce.id)) {
                    is ZillitResult.Failure -> fetched
                    is ZillitResult.Success -> host.printPdf(fetched.data.first, fetched.data.second)
                }
            }
            setState { copy(busy = false, pdf = pdf?.copy(printing = false)) }
            if (outcome is ZillitResult.Failure) {
                sendEffect(RecceEffect.Notice(outcome.error.localised(), success = false))
            }
        }
    }

    private fun downloadPdf() {
        val viewer = currentState.pdf ?: return
        val bytes = viewer.bytes ?: return
        if (refuses(RightsKind.Download)) return
        setState { copy(pdf = pdf?.copy(downloading = true)) }
        launch {
            val saved = host.savePdf(viewer.name, bytes)
            setState { copy(pdf = pdf?.copy(downloading = false)) }
            when (saved) {
                is ZillitResult.Failure -> sendEffect(RecceEffect.Notice(saved.error.localised(), success = false))
                is ZillitResult.Success -> sendEffect(RecceEffect.Notice(str(S.docusign_signing_attachment_saved)))
            }
        }
    }

    private suspend fun fetchReport(id: String): ZillitResult<Pair<String, ByteArray>> =
        when (val report = repository.report(id)) {
            is ZillitResult.Failure -> report
            is ZillitResult.Success -> when (val bytes = host.fetchReport(report.data)) {
                is ZillitResult.Failure -> bytes
                is ZillitResult.Success -> ZillitResult.Success(pdfName(report.data.name) to bytes.data)
            }
        }

    // --------------------------------------------------------------- sockets

    /**
     * A recce created or updated elsewhere. The list is refetched (it is
     * filtered and paged server-side, so a merge by hand would guess at the
     * row's page) and the open detail is patched from the payload, which
     * carries the full record per the realtime contract.
     */
    private fun onUpsertElsewhere(message: SocketMessage) {
        val recce = parseRecceEvent(message.payload)
        if (recce != null && currentState.selected?.id == recce.id) {
            setState { copy(selected = recce) }
            plotRoute(recce)
        }
        loadList()
        loadCounts(covered = if (search.isEmpty()) currentState.filter else null)
    }

    /** A recce deleted elsewhere — drop it, and drop the reader off a dead record. */
    private fun onDeletedElsewhere(ids: List<String>) {
        if (ids.isEmpty()) return
        val open = (currentState.route as? ReccePage.Detail)?.id ?: (currentState.route as? ReccePage.Form)?.id
        if (open != null && open in ids) {
            setState {
                copy(route = ReccePage.Index, selected = null, editor = null, leavePrompt = false, routeMap = null)
            }
            sendEffect(RecceEffect.Notice(str(S.desktop_recce_was_deleted), success = false))
        }
        setState { copy(recces = recces.filterNot { it.id in ids }) }
        loadList()
        loadCounts(covered = if (search.isEmpty()) currentState.filter else null)
    }

    // ---------------------------------------------------------------- rights

    /**
     * Whether the press is refused for want of [kind]. Controls stay visible
     * for everyone; a press without the right asks an admin and says so —
     * the web's `requestAccess` modal.
     */
    private fun refuses(kind: RightsKind): Boolean {
        val viewer = currentState.viewer
        val granted = when (kind) {
            RightsKind.Post -> viewer.mayPost
            RightsKind.Download -> viewer.mayDownload
        }
        if (granted) return false
        rights?.ask(RecceViewer.MODULE_LABEL, kind)
        val refusal = rightsRefusalMessage(RecceViewer.MODULE_LABEL, kind, asked = rights != null)
        sendEffect(RecceEffect.Notice(refusal, success = false))
        return true
    }

    // --------------------------------------------------------------- helpers

    private fun editEditor(transform: RecceEditor.() -> RecceEditor) {
        setState { copy(editor = editor?.transform()?.copy(dirty = true)) }
    }

    /** The publish errors an edit clears — each field forgives itself once typed into. */
    private fun touched(event: RecceEvent.EditorChanged): Set<RecceField> = buildSet {
        if (event.title != null) add(RecceField.Title)
        if (event.dateYmd != null) add(RecceField.Date)
        if (event.rdv != null) {
            add(RecceField.RdvTime)
            add(RecceField.RdvPlace)
        }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.localised()
            setState { copy(error = message) }
            null
        }
    }

    private fun <T> ZillitResult<T>.map(transform: (T) -> String): ZillitResult<String> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success(transform(data))
        is ZillitResult.Failure -> this
    }

    private fun reportName(recce: Recce): String = pdfName(recce.title.ifBlank { "recce" })

    private fun pdfName(name: String): String {
        val base = name.ifBlank { "recce" }
        return if (base.endsWith(".pdf", ignoreCase = true)) base else "$base.pdf"
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 300L
        val DELETED = SocketEventName("recce:deleted")

        /** Picture sizes, at the retina scale Static Maps allows (`scale=2` doubles them). */
        const val ROUTE_WIDTH_PX = 640
        const val ROUTE_HEIGHT_PX = 400
        const val PREVIEW_WIDTH_PX = 640
        const val PREVIEW_HEIGHT_PX = 220
        const val PDF_PAGE_WIDTH_PX = 1600
    }
}

private fun <T> List<T>.moved(index: Int, delta: Int): List<T> {
    val target = index + delta
    if (index !in indices || target !in indices) return this
    return toMutableList().also { it.add(target, it.removeAt(index)) }
}
