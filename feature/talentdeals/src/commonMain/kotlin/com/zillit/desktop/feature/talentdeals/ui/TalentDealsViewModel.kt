package com.zillit.desktop.feature.talentdeals.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.talentdeals.domain.TalentAttachment
import com.zillit.desktop.feature.talentdeals.domain.TalentContract
import com.zillit.desktop.feature.talentdeals.domain.TalentContractBody
import com.zillit.desktop.feature.talentdeals.domain.TalentCountryCurrency
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsRepository
import com.zillit.desktop.feature.talentdeals.domain.TalentDealsViewer
import com.zillit.desktop.feature.talentdeals.domain.TalentExhibitG
import com.zillit.desktop.feature.talentdeals.domain.TalentFileSlot
import com.zillit.desktop.feature.talentdeals.domain.TalentForm
import com.zillit.desktop.feature.talentdeals.domain.TalentIds
import com.zillit.desktop.feature.talentdeals.domain.TalentRoles
import com.zillit.desktop.feature.talentdeals.domain.TalentSaved
import com.zillit.desktop.feature.talentdeals.domain.TalentTerritories
import com.zillit.desktop.feature.talentdeals.domain.TrancheDraft
import com.zillit.desktop.feature.talentdeals.domain.ExpenseDraft
import kotlinx.coroutines.Job
import kotlin.random.Random

/**
 * Talent Deals: the register of executed talent contracts, one contract read-only
 * with its Exhibit G panel, and the Add / Edit form — the web's
 * `TalentDealsModule` and the three pages under it.
 *
 * One view model for all four faces, holding the route itself (the tool's
 * window has one path, as Deal Memo's does). Every handler re-checks the
 * viewer's rights as the screen does: the screen hides what a viewer may not
 * do, and the handler is what an event dispatched directly would reach.
 */
@Suppress("TooManyFunctions") // One function per act the tool performs.
class TalentDealsViewModel(
    private val repository: TalentDealsRepository,
    private val resolveViewer: () -> TalentDealsViewer,
    private val files: TalentFileHost = TalentFileHost.Unavailable,
    private val reference: TalentReference = TalentReference.Empty,
    /** Six digits for a new contract's reference. */
    private val randomDigits: () -> Int = { Random.nextInt(REFERENCE_MIN, REFERENCE_MAX) },
) : ZillitViewModel<TalentDealsUiState, TalentDealsEvent, TalentDealsEffect>(
    TalentDealsUiState(reference = ReferenceState(territories = TalentTerritories.wizard)),
) {

    private val ids = TalentIds()
    private var listening = false
    private var referenceLoaded = false

    /** The name the Exhibit G fetch last ran for, so only a real change refetches. */
    private var exhibitFor: String? = null

    private var listJob: Job? = null
    private var detailJob: Job? = null
    private var formJob: Job? = null
    private var unionsJob: Job? = null
    private var unionNameJob: Job? = null
    private var exhibitJob: Job? = null
    private var viewerJob: Job? = null
    private var saveJob: Job? = null

    // -- lifecycle -------------------------------------------------------------------

    /** Runs on every window open: resolves the viewer and refreshes whatever face is showing. */
    fun start() {
        listenForChanges()
        setState { copy(viewer = resolveViewer()) }
        loadReference()
        enforceRoute()
        when (val route = currentState.route) {
            TalentRoute.Register -> loadList(silent = currentState.register.contracts.isNotEmpty())
            is TalentRoute.View -> loadDetail(route.id, silent = currentState.detail.contract != null)
            else -> Unit
        }
    }

    /** The production changed: everything on screen belongs to the previous one. */
    fun onProjectChanged() {
        listOf(listJob, detailJob, formJob, unionsJob, unionNameJob, exhibitJob, viewerJob,
            saveJob).forEach { it?.cancel() }
        exhibitFor = null
        referenceLoaded = false
        setState {
            TalentDealsUiState(
                viewer = TalentDealsViewer(),
                reference = ReferenceState(territories = TalentTerritories.wizard),
            )
        }
    }

    /**
     * Swaps in the real rights once the tool grid has answered. Only the viewer
     * changes — and a face the new rights do not allow gives way to the register.
     */
    fun onRightsChanged() {
        val resolved = resolveViewer()
        setState { copy(viewer = resolved) }
        enforceRoute()
        val register = currentState.register
        if (currentState.route == TalentRoute.Register && register.contracts.isEmpty()) loadList(silent = false)
    }

    private fun listenForChanges() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect { refresh ->
                // The list refetches on any frame; a contract only on its own.
                when (val route = currentState.route) {
                    TalentRoute.Register -> loadList(silent = true)
                    is TalentRoute.View -> if (refresh.contractId == route.id) loadDetail(route.id, silent = true)
                    else -> Unit
                }
            }
        }
    }

    private fun enforceRoute() {
        val s = currentState
        if (s.viewer.rightsLoaded && !s.route.destination.visibleTo(s.viewer)) go(TalentRoute.Register)
    }

    private fun loadReference() {
        if (referenceLoaded) return
        referenceLoaded = true
        launch {
            val countries = reference.countries()
            val currencies = reference.currencies()
            val territories = TalentTerritories.build(countries, TalentCountryCurrency.lookup(currencies.catalogue))
            setState { copy(reference = ReferenceState(territories = territories, currencies = currencies)) }
        }
    }

    // -- events ----------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per act.
    override fun onEvent(event: TalentDealsEvent) {
        when (event) {
            TalentDealsEvent.Retry -> retry()
            is TalentDealsEvent.Search -> setState { copy(register = register.copy(query = event.query)) }
            TalentDealsEvent.AddContract -> guardPost {
                setState { copy(register = register.copy(rolePickerOpen = true)) }
            }
            TalentDealsEvent.CancelRolePicker -> setState { copy(register = register.copy(rolePickerOpen = false)) }
            is TalentDealsEvent.PickRole -> pickRole(event.role)
            is TalentDealsEvent.OpenContract -> go(TalentRoute.View(event.id))
            is TalentDealsEvent.RequestRemove -> guardPost {
                setState { copy(register = register.copy(deleteTarget = event.contract)) }
            }
            TalentDealsEvent.ConfirmRemove -> remove()
            TalentDealsEvent.CancelRemove -> if (!currentState.register.deleting) {
                setState { copy(register = register.copy(deleteTarget = null)) }
            }
            TalentDealsEvent.Back -> back()
            is TalentDealsEvent.OpenEdit -> guardPost { go(TalentRoute.Edit(event.id)) }
            is TalentDealsEvent.FormEdit -> editTalentForm(event.change)
            is TalentDealsEvent.SelectTerritory -> selectTerritory(event.id)
            TalentDealsEvent.PickContractFile -> pickFile(CONTRACT_EXTENSIONS) { copy(contractFile = it) }
            TalentDealsEvent.PickCertificateFile -> pickFile(CERTIFICATE_EXTENSIONS) { copy(certificateFile = it) }
            is TalentDealsEvent.CommitName -> commitName(event.name)
            is TalentDealsEvent.ToggleWeekly -> editTalentForm { it.toggleWeekly(event.on, ids) }
            is TalentDealsEvent.ToggleExpenses -> editTalentForm { it.toggleExpenses(event.on, ids) }
            TalentDealsEvent.AddTranche -> editTalentForm {
                it.copy(tranches = it.tranches + TrancheDraft(ids.next("t")))
            }
            TalentDealsEvent.AddExpense -> editTalentForm {
                it.copy(expenses = it.expenses + ExpenseDraft(ids.next("e")))
            }
            TalentDealsEvent.Save -> save()
            TalentDealsEvent.ToggleExhibit -> toggleExhibit()
            is TalentDealsEvent.PickExhibitRow -> setState {
                copy(exhibit = exhibit.copy(picked = exhibit.picked + (event.dayKey to event.rowKey)))
            }
            is TalentDealsEvent.OpenAttachment -> openAttachment(event.attachment)
            TalentDealsEvent.CloseViewer -> {
                viewerJob?.cancel()
                setState { copy(viewing = null) }
            }
            TalentDealsEvent.DownloadViewed -> downloadViewed()
        }
    }

    private fun retry() {
        when (val route = currentState.route) {
            TalentRoute.Register -> loadList(silent = false)
            is TalentRoute.View -> loadDetail(route.id, silent = false)
            is TalentRoute.Edit -> go(route)
            is TalentRoute.New -> Unit
        }
    }

    // -- rights ----------------------------------------------------------------------

    private fun guardPost(block: () -> Unit) {
        if (currentState.viewer.canPost) block() else notice(str(S.desktop_td_no_post_rights), error = true)
    }

    private fun notice(text: String, error: Boolean) = sendEffect(TalentDealsEffect.Notice(text, error))

    private fun ZillitError.text(): String = localised()

    // -- navigation ------------------------------------------------------------------

    private fun back() {
        when (val route = currentState.route) {
            TalentRoute.Register -> sendEffect(TalentDealsEffect.CloseTool)
            is TalentRoute.View, is TalentRoute.New -> go(TalentRoute.Register)
            is TalentRoute.Edit -> go(TalentRoute.View(route.id))
        }
    }

    /**
     * Opens a face. One the viewer may not enter gives way to the register,
     * where a refusal for lack of rights is the web's redirect.
     */
    private fun go(target: TalentRoute) {
        val viewer = currentState.viewer
        val destination = target.destination
        val allowed = if (viewer.rightsLoaded) destination.visibleTo(viewer) else !destination.requiresPost
        val route = if (allowed) target else TalentRoute.Register

        cancelPageJobs()
        when (route) {
            TalentRoute.Register -> {
                setState { copy(route = route, exhibit = ExhibitState(), viewing = null) }
                loadList(silent = currentState.register.contracts.isNotEmpty())
            }
            is TalentRoute.View -> {
                setState {
                    copy(route = route, detail = DetailState(contractId = route.id), exhibit = ExhibitState(),
                        viewing = null)
                }
                loadDetail(route.id, silent = false)
            }
            is TalentRoute.New -> setState {
                copy(
                    route = route,
                    form = FormState(role = route.role, reference = TalentContractBody.newReference(randomDigits)),
                    exhibit = ExhibitState(),
                    viewing = null,
                )
            }
            is TalentRoute.Edit -> {
                setState {
                    copy(
                        route = route,
                        form = FormState(contractId = route.id, loadingRow = true),
                        exhibit = ExhibitState(),
                        viewing = null,
                    )
                }
                loadForEdit(route.id)
            }
        }
    }

    private fun cancelPageJobs() {
        listOf(detailJob, formJob, unionsJob, unionNameJob, exhibitJob, viewerJob).forEach { it?.cancel() }
        exhibitFor = null
    }

    // -- the register ----------------------------------------------------------------

    private fun loadList(silent: Boolean) {
        val viewer = currentState.viewer
        if (viewer.rightsLoaded && !viewer.canView) return
        listJob?.cancel()
        if (!silent) setState { copy(register = register.copy(loading = true, error = null)) }
        listJob = launch {
            when (val result = repository.contracts()) {
                is ZillitResult.Success -> setState {
                    copy(register = register.copy(contracts = result.data, loading = false, error = null))
                }
                // A failed silent re-read keeps the rows on screen; a first load says what went wrong.
                is ZillitResult.Failure -> if (!silent) {
                    setState { copy(register = register.copy(contracts = emptyList(), loading = false,
                        error = result.error.text())) }
                }
            }
        }
    }

    private fun pickRole(role: String) {
        val trimmed = role.trim()
        if (trimmed.isEmpty()) return
        guardPost {
            setState { copy(register = register.copy(rolePickerOpen = false)) }
            go(TalentRoute.New(trimmed))
        }
    }

    private fun remove() {
        val target = currentState.register.deleteTarget ?: return
        if (currentState.register.deleting) return
        if (!currentState.viewer.canPost) {
            notice(str(S.desktop_td_no_post_rights), error = true)
            return
        }
        setState { copy(register = register.copy(deleting = true)) }
        launch {
            when (val result = repository.remove(target.id)) {
                is ZillitResult.Success -> {
                    notice(result.data?.localisedMessage() ?: str(S.desktop_td_contract_removed), error = false)
                    setState { copy(register = register.copy(deleteTarget = null, deleting = false)) }
                    loadList(silent = true)
                }
                is ZillitResult.Failure -> {
                    notice(result.error.text(), error = true)
                    setState { copy(register = register.copy(deleting = false)) }
                }
            }
        }
    }

    // -- one contract ----------------------------------------------------------------

    private fun loadDetail(id: String, silent: Boolean) {
        val viewer = currentState.viewer
        if (viewer.rightsLoaded && !viewer.canView) return
        detailJob?.cancel()
        if (!silent) setState { copy(detail = detail.copy(contractId = id, loading = true, error = null)) }
        detailJob = launch {
            when (val result = repository.contract(id)) {
                is ZillitResult.Success -> {
                    if (currentState.route != TalentRoute.View(id)) return@launch
                    setState { copy(detail = detail.copy(contract = result.data, loading = false, error = null)) }
                    loadUnionName(result.data)
                    syncExhibit()
                }
                // A failed socket re-read keeps the contract on screen rather than blanking it.
                is ZillitResult.Failure -> if (!silent) {
                    setState { copy(detail = detail.copy(contract = null, loading = false,
                        error = result.error.text())) }
                }
            }
        }
    }

    /** The union's display name, from the same lookup the form reads — the row stores only the identifier. */
    private fun loadUnionName(contract: TalentContract) {
        unionNameJob?.cancel()
        if (contract.territoryCode.isBlank() || contract.unionIdentifier.isBlank()) {
            setState { copy(detail = detail.copy(unionName = "")) }
            return
        }
        unionNameJob = launch {
            val name = (repository.unions("all", contract.territoryCode) as? ZillitResult.Success)
                ?.data?.firstOrNull { it.identifier == contract.unionIdentifier }?.name.orEmpty()
            setState { copy(detail = detail.copy(unionName = name)) }
        }
    }

    // -- the form --------------------------------------------------------------------

    private fun loadForEdit(id: String) {
        formJob?.cancel()
        formJob = launch {
            when (val result = repository.contract(id)) {
                is ZillitResult.Success -> {
                    if (currentState.route != TalentRoute.Edit(id)) return@launch
                    val row = result.data
                    val form = TalentContractBody.fromContract(row, ids)
                    setState {
                        copy(
                            form = FormState(
                                role = row.role,
                                reference = row.reference,
                                status = row.status.ifBlank { TalentContractBody.ISSUED },
                                contractId = id,
                                form = form,
                                // A saved name is already committed — Exhibit G opens on it.
                                lookupName = row.person.trim(),
                            ),
                        )
                    }
                    loadUnions(row.role, row.territoryCode)
                }
                is ZillitResult.Failure -> {
                    notice(result.error.text(), error = true)
                    go(TalentRoute.View(id))
                }
            }
        }
    }

    /** Edits the form when a form is what is showing. */
    private fun editForm(change: TalentDealsUiState.() -> TalentDealsUiState) {
        if (currentState.route !is TalentRoute.New && currentState.route !is TalentRoute.Edit) return
        if (!currentState.viewer.canPost) return
        setState(change)
        syncExhibit()
    }

    private fun editTalentForm(change: (TalentForm) -> TalentForm) =
        editForm { copy(form = form.copy(form = change(form.form))) }

    private fun selectTerritory(id: String) {
        editForm {
            val code = reference.territories.firstOrNull { it.id == id }?.currency.orEmpty()
            val known = reference.currencyOptions(form.form.currency).any { it.code == code }
            // A territory carries its default currency, but only when the picker can show it.
            val next = form.form.copy(territory = id,
                currency = if (code.isNotBlank() && known) code else form.form.currency)
            copy(form = form.copy(form = next))
        }
        loadUnions(currentState.form.role, id)
    }

    /**
     * Unions per territory and crew type; none until a territory is picked, and a
     * failed lookup never strands the form.
     */
    private fun loadUnions(role: String, territory: String) {
        unionsJob?.cancel()
        if (territory.isBlank()) {
            setState { copy(form = form.copy(unions = emptyList(), unionsLoading = false)) }
            return
        }
        setState { copy(form = form.copy(unionsLoading = true)) }
        unionsJob = launch {
            val unions = (repository.unions(TalentRoles.crewTypeFor(role),
                territory) as? ZillitResult.Success)?.data.orEmpty()
            setState { copy(form = form.copy(unions = unions, unionsLoading = false)) }
        }
    }

    private fun pickFile(extensions: List<String>, place: TalentForm.(TalentFileSlot) -> TalentForm) {
        if (!currentState.viewer.canPost) return
        launch {
            val picked = files.pick(extensions) ?: return@launch
            editTalentForm { it.place(TalentFileSlot.Picked(picked)) }
        }
    }

    private fun commitName(name: String) {
        editForm {
            val prev = form.lookupName
            val next = if (TalentExhibitG.nameKey(prev) == TalentExhibitG.nameKey(name)) prev else name.trim()
            copy(form = form.copy(lookupName = next))
        }
    }

    private fun save() {
        val s = currentState
        val route = s.route
        if (route !is TalentRoute.New && route !is TalentRoute.Edit) return
        if (s.form.saving || s.form.loadingRow) return
        if (!s.viewer.canPost) {
            notice(str(S.desktop_td_no_post_rights), error = true)
            return
        }
        setState { copy(form = form.copy(attempted = true)) }
        if (s.form.errors.isNotEmpty()) return

        setState { copy(form = form.copy(saving = true)) }
        saveJob = launch {
            val outcome = submit(s)
            setState { copy(form = form.copy(saving = false)) }
            when (outcome) {
                is ZillitResult.Failure -> notice(outcome.error.text(), error = true)
                is ZillitResult.Success -> {
                    notice(outcome.data.message?.localisedMessage() ?: str(S.desktop_td_contract_saved), error = false)
                    go(if (route is TalentRoute.Edit) TalentRoute.View(route.id) else TalentRoute.Register)
                }
            }
        }
    }

    /**
     * Upload FIRST, then post the envelopes. On Edit a slot may still hold the
     * STORED envelope — that is sent back as it is; only a freshly picked file is
     * uploaded. The certificate is only kept while a pre-authorised rate is.
     */
    private suspend fun submit(s: TalentDealsUiState): ZillitResult<TalentSaved> {
        val form = s.form.form
        val contract = when (val settled = settle(form.contractFile)) {
            is ZillitResult.Failure -> return settled
            is ZillitResult.Success -> settled.data
        }
        val certificateSlot = form.certificateFile.takeIf { form.withholding && form.withholdingAuthorised }
        val certificate = when (val settled = settle(certificateSlot)) {
            is ZillitResult.Failure -> return settled
            is ZillitResult.Success -> settled.data
        }
        val body = TalentContractBody.build(
            form = form.copy(currency = s.activeCurrency),
            contract = contract,
            certificate = certificate,
            reference = s.form.reference,
            role = s.form.role,
            status = s.form.status,
        )
        return s.form.contractId?.let { repository.update(it, body) } ?: repository.create(body)
    }

    private suspend fun settle(slot: TalentFileSlot?): ZillitResult<TalentAttachment?> = when (slot) {
        null -> ZillitResult.Success(null)
        is TalentFileSlot.Stored -> ZillitResult.Success(slot.attachment)
        is TalentFileSlot.Picked -> when (val uploaded = files.upload(slot.file)) {
            is ZillitResult.Failure -> uploaded
            is ZillitResult.Success ->
                if (uploaded.data.isPresent) {
                    ZillitResult.Success(uploaded.data)
                } else {
                    ZillitResult.Failure(uploadFailed())
                }
        }
    }

    private fun uploadFailed() = ZillitError.Validation(str(S.desktop_hub_upload_failed_please_try_again))

    /** A stored signature image for Exhibit G's signature cell; null when it cannot be read. */
    suspend fun signatureImage(attachment: TalentAttachment): ByteArray? =
        (files.fetch(attachment) as? ZillitResult.Success)?.data

    // -- Exhibit G -------------------------------------------------------------------

    private fun toggleExhibit() {
        setState { copy(exhibit = if (exhibit.open) ExhibitState() else exhibit.copy(open = true)) }
        syncExhibit()
    }

    /**
     * Fetches while the panel is open, for a name — the saved one on the
     * read-only page, the committed one on the form — and only when that name
     * really changed. An answer that lands after the name has moved on is dropped.
     */
    private fun syncExhibit() {
        val s = currentState
        val (applies, name) = when (s.route) {
            is TalentRoute.View ->
                s.detail.contract?.let { TalentRoles.exhibitGApplies(it.role,
                    it.unionIdentifier) to it.person } ?: (false to "")
            is TalentRoute.New, is TalentRoute.Edit -> s.form.exhibitApplies to s.form.lookupName
            TalentRoute.Register -> false to ""
        }
        val wanted = name.trim().takeIf { s.exhibit.open && applies && it.isNotEmpty() }
        if (wanted == exhibitFor) return
        exhibitFor = wanted
        exhibitJob?.cancel()
        if (wanted == null) {
            setState { copy(exhibit = exhibit.copy(loading = false, error = null, days = emptyList())) }
            return
        }
        setState { copy(exhibit = exhibit.copy(loading = true, error = null)) }
        exhibitJob = launch {
            val result = repository.exhibitG(wanted)
            if (exhibitFor != wanted) return@launch
            when (result) {
                is ZillitResult.Success -> setState { copy(exhibit = exhibit.copy(loading = false, error = null,
                    days = result.data)) }
                is ZillitResult.Failure ->
                    setState { copy(exhibit = exhibit.copy(loading = false, error = result.error.text(),
                        days = emptyList())) }
            }
        }
    }

    // -- files -----------------------------------------------------------------------

    private fun openAttachment(attachment: TalentAttachment) {
        if (!attachment.isPresent) return
        viewerJob?.cancel()
        setState { copy(viewing = FileViewState(attachment = attachment)) }
        viewerJob = launch {
            val bytes = when (val fetched = files.fetch(attachment)) {
                is ZillitResult.Failure -> {
                    setState { copy(viewing = FileViewState(attachment, loading = false,
                        error = fetched.error.text())) }
                    return@launch
                }
                is ZillitResult.Success -> fetched.data
            }
            val view = when {
                attachment.isPdf -> when (val pages = files.renderPages(bytes, PDF_WIDTH_PX)) {
                    is ZillitResult.Success -> FileViewState(attachment, loading = false, pages = pages.data)
                    is ZillitResult.Failure -> FileViewState(attachment, loading = false, unsupported = true)
                }
                attachment.isImage -> FileViewState(attachment, loading = false, image = bytes)
                else -> FileViewState(attachment, loading = false, unsupported = true)
            }
            setState { copy(viewing = view) }
        }
    }

    /** Download is shown on `download_access` alone — hiding it is no security boundary, so the handler checks too. */
    private fun downloadViewed() {
        val viewing = currentState.viewing ?: return
        if (!currentState.viewer.canDownload) return
        launch {
            val bytes = when (val fetched = files.fetch(viewing.attachment)) {
                is ZillitResult.Failure -> return@launch notice(fetched.error.text(), error = true)
                is ZillitResult.Success -> fetched.data
            }
            (files.saveAndOpen(viewing.attachment.displayName, bytes) as? ZillitResult.Failure)
                ?.let { notice(it.error.text(), error = true) }
        }
    }

    private companion object {
        const val REFERENCE_MIN = 100_000
        const val REFERENCE_MAX = 1_000_000
        const val PDF_WIDTH_PX = 1100
        val CONTRACT_EXTENSIONS = listOf("pdf", "doc", "docx")
        val CERTIFICATE_EXTENSIONS = listOf("pdf", "jpg", "jpeg", "png")
    }
}
