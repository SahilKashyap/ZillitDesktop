package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.forms.FormTemplate
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.data.cashFormRefreshes
import com.zillit.desktop.feature.cashexpenses.data.cashMetadataRefreshes
import com.zillit.desktop.feature.cashexpenses.data.cashRefreshes
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.CashCurrencies
import com.zillit.desktop.feature.cashexpenses.domain.CashReferenceSources
import com.zillit.desktop.feature.cashexpenses.domain.CashDepartments
import com.zillit.desktop.feature.cashexpenses.domain.CashFloatOrder
import com.zillit.desktop.feature.cashexpenses.domain.CashQueue
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The cash tool's one view model.
 *
 * ## Loading is per destination, not per screen
 *
 * [load] decides what a page needs and fetches exactly that. Screens never
 * fetch, which is what lets an approval on one page correct the counts on
 * another: every mutation ends by reloading the current destination, and
 * switching to a stale page reloads it on arrival.
 *
 * ## The viewer is resolved before anything else
 *
 * Rights decide which tabs exist, so [start] fetches metadata first and only
 * then loads a page. Rendering tabs from a default-empty [CashViewer] would
 * flash the crew view at an accountant on every open.
 *
 * ## Handlers live in desks
 *
 * Each area's actions and their rights checks sit in a collaborator —
 * [BatchDesk], [FloatDesk], [ReconDesk], [SettingsDesk], [ExportDesk], with
 * [PromptDesk] routing every answered dialog to the one that owns it. This
 * class dispatches and loads.
 */
@Suppress("TooManyFunctions") // One handler per user action; see detekt.yml.
class CashExpensesViewModel(
    private val repository: CashRepository,
    /**
     * Ids for lines this client invents.
     *
     * Injected because common code has no UUID generator and because the
     * round trip is easier to pin with a counter — see `LineItemEditorTest`.
     */
    private val newLineId: () -> String = { "local-" + (lineCounter++) },
    /**
     * Who is looking, read at start rather than at construction.
     *
     * The view model is built once for the whole app, before any production is
     * open and therefore before the profile that names this person's
     * department exists. Capturing a snapshot here would fix every viewer as
     * "not an accountant" for the life of the process.
     */
    private val viewer: () -> CashViewer,
    /**
     * The production's crew: who a batch may be handed to, and the name for
     * every user id the cash service sends (see `CashPeople`).
     */
    private val assignees: () -> List<AssigneeOption> = { emptyList() },
    /** Live changes from other clients; null keeps the tool load-once. */
    private val events: SocketEventBus? = null,
    /**
     * The form the accountant configured for petty cash — the account hub's
     * document; the default, an empty template, shows every field.
     */
    private val formTemplate: suspend () -> ZillitResult<FormTemplate> = {
        ZillitResult.Success(FormTemplate())
    },
    /**
     * The ledger's rows for this tool: per page key for the tabs, per entity
     * for the rows, and the reads. The tool is chosen per key by the host —
     * see [CashBadges.toolFor].
     */
    private val badges: TabBadgeSource = TabBadgeSource.None,
    /** Where an export is saved; null says the exports cannot save. See [CashFiles]. */
    private val files: CashFiles? = null,
    /**
     * The production's currencies, departments and chart, and the receipt
     * picker — Account Hub documents and a desktop file dialog. See
     * [CashReferenceSources] and [ReferenceDesk].
     */
    reference: CashReferenceSources = CashReferenceSources(),
) : ZillitViewModel<CashUiState, CashEvent, CashEffect>(
    CashUiState(
        viewer = viewer(),
        destination = CashDestination.landing(viewer(), ExpenseType.PettyCash),
    ),
) {

    private var loadJob: Job? = null
    private var started = false
    private var listening = false
    private var watchingBadges = false

    /**
     * Whether the composition on screen opened the tool on its own.
     *
     * False — the console's view — until a composition says otherwise with
     * [CashEvent.Enter]; the tool provider always does, from `LocalHostedBy`.
     */
    private var enteredAsTool = false

    private val host: CashHost = object : CashHost {
        override val state: CashUiState get() = currentState
        override val repository: CashRepository get() = this@CashExpensesViewModel.repository
        override val files: CashFiles? get() = this@CashExpensesViewModel.files
        override fun update(reducer: CashUiState.() -> CashUiState) = setState(reducer)
        override fun refuse(message: String) = sendEffect(CashEffect.Failed(message))
        override fun report(error: ZillitError) = sendEffect(CashEffect.Failed(error.localised()))
        override fun work(block: suspend CoroutineScope.() -> Unit): Job = launch(block)
        override fun act(
            success: String,
            onSuccess: CashUiState.() -> CashUiState,
            after: () -> Unit,
            block: suspend () -> ZillitResult<Unit>,
        ): Job = this@CashExpensesViewModel.act(success, onSuccess, after, block)

        override fun readEntity(level1: String, entityId: String, kind: String) =
            this@CashExpensesViewModel.readEntity(level1, entityId, kind)

        override fun refetchMetadata() = this@CashExpensesViewModel.refetchMetadata(reloadPage = false)
    }

    private val batchDesk: BatchDesk = BatchDesk(host)
    private val floatDesk: FloatDesk = FloatDesk(host)
    private val reconDesk: ReconDesk = ReconDesk(host)
    private val settingsDesk: SettingsDesk = SettingsDesk(host)
    private val exportDesk: ExportDesk = ExportDesk(host)
    private val referenceDesk: ReferenceDesk = ReferenceDesk(host, reference)
    private val promptDesk: PromptDesk = PromptDesk(host, batchDesk, floatDesk, reconDesk)

    // -- crew parity -- Submit Receipts, Receipts History and Float Request.
    private val crewDesk: CrewDesk = CrewDesk(host, navigate = { open(it) })
    // -- funds parity --
    private val fundsDesk: FundsDesk = FundsDesk(host, floatDesk, reconDesk)

    /**
     * Arriving on a page reads nothing — the web marks one entity read as it
     * is opened or acted on ([readEntity]) — except the two pages it reads
     * whole on mount; see [CashDestination.readsWholeTab].
     */
    private fun readTab(destination: CashDestination) {
        if (!destination.readsWholeTab) return
        destination.badgeKeys.filter { (currentState.unread[it] ?: 0) > 0 }.forEach(badges::read)
    }

    /** One entity's rows read, in the bucket [kind] names. */
    private fun readEntity(level1: String, entityId: String, kind: String) {
        if (entityId.isBlank()) return
        badges.readEntity(level1, entityId, kind)
    }

    /** The host's viewer, as the composition on screen entered. */
    private fun identity(): CashViewer = viewer().copy(enteredAsTool = enteredAsTool)

    /**
     * Resolves who this is, then opens their landing page.
     *
     * Called when the tool is first shown, not from `init`: see [viewer].
     * Idempotent, because a torn-off window and the tab in the frame are the
     * same view model shown twice.
     *
     * A failed metadata call is not fatal: the viewer keeps whatever the
     * production profile said (accountant or not) and loses only the grants
     * that live in this module's settings. That degrades to fewer tabs, never
     * to more — a tab this person may not use is worse than one missing.
     */
    fun start() {
        if (started) return
        started = true
        // The production's own: a switch must not carry the last one's companies.
        setState {
            copy(
                companies = emptyList(),
                lockedThrough = null,
                chartAccounts = null,
                departments = emptyList(),
                currencies = CashCurrencies(),
                floatDetail = null,
            )
        }
        loadFormTemplate()
        loadLock()
        referenceDesk.load()
        if (!watchingBadges) {
            watchingBadges = true
            launch {
                badges.counts.collect { counts ->
                    setState { copy(unread = counts) }
                    // A row landing on a page read whole is read as it lands.
                    readTab(currentState.destination)
                }
            }
            launch { badges.entityCounts.collect { counts -> setState { copy(unreadByEntity = counts) } } }
        }
        launch {
            val identity = identity()
            val metadata = (repository.metadata() as? ZillitResult.Success)?.data
            setState {
                val resolved = metadata?.let { identity.copy(metadata = it) } ?: identity
                copy(viewer = resolved, destination = CashDestination.landing(resolved, pipeline))
            }
            load(currentState.destination)
        }

        // A float issued, a batch escalated, a claim verified elsewhere. Only
        // the page on screen reloads. `listening` outlives `started`, which
        // onProjectChanged resets, so a switch does not stack a collector.
        val bus = events
        if (bus != null && !listening) {
            listening = true
            launch { cashRefreshes(bus).collect { load(currentState.destination) } }
            // The accountant changed which fields the float request has.
            launch { cashFormRefreshes(bus).collect { loadFormTemplate() } }
            // Settings, an approval chain or an assignment rule changed who
            // this viewer is here; a chain change moves rows between queues too.
            launch { cashMetadataRefreshes(bus).collect { chain -> refetchMetadata(reloadPage = chain) } }
        }
    }

    /**
     * Re-pulls `/metadata` and reseats the viewer on it — the web's
     * `ah:cash:metadata` refetch. The tabs follow from the viewer; a page the
     * new rights close gives way to the landing. [reloadPage] also reloads a
     * page that stays, which an approval-chain change needs — never Settings,
     * where it would throw away edits in other sections.
     */
    private fun refetchMetadata(reloadPage: Boolean) {
        launch {
            val metadata = (repository.metadata() as? ZillitResult.Success)?.data ?: return@launch
            val before = currentState.destination
            reseat { it.copy(metadata = metadata) }
            val stayed = currentState.destination == before
            if (reloadPage && stayed && before != CashDestination.Settings) load(before)
        }
    }

    /** Re-reads the viewer when the open production changes. */
    fun onProjectChanged() {
        started = false
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered — only the
     * viewer changes; a page the new rights close is left for the landing.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = identity()
        reseat { resolved.copy(metadata = it.metadata) }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: CashEvent) {
        when (event) {
            CashEvent.Refresh -> load(currentState.destination)

            is CashEvent.Enter -> enter(event.asTool)

            is CashEvent.ViewReceipt -> sendEffect(CashEffect.OpenAttachment(event.receiptUrl))

            is CashEvent.AssignPickUser -> setState {
                val open = prompt as? CashPrompt.Assign ?: return@setState this
                copy(prompt = open.copy(selectedUserId = event.userId))
            }

            is CashEvent.AssignReason -> setState {
                val open = prompt as? CashPrompt.Assign ?: return@setState this
                copy(prompt = open.copy(reason = event.text))
            }

            is CashEvent.Open -> open(event.destination)

            is CashEvent.SwitchPipeline -> {
                val landing = CashDestination.landing(currentState.viewer, event.pipeline)
                setState { copy(pipeline = event.pipeline, destination = landing, error = null, panel = null) }
                load(landing)
            }

            is CashEvent.Search -> setState { copy(search = event.query) }
            is CashEvent.SelectBatch -> batchDesk.open(event.batchId)
            is CashEvent.SelectFloat -> setState { copy(selectedFloatId = event.floatId) }
            is CashEvent.OpenFloatDetail -> floatDesk.openDetail(event.floatId)
            CashEvent.CloseFloatDetail -> floatDesk.closeDetail()
            is CashEvent.SaveFloatBsCode -> floatDesk.saveBsCode(event.floatId, event.bsCode)
            CashEvent.OpenApprovalLevels -> sendEffect(CashEffect.Navigate(CASH_APPROVERS_ROUTE))
            CashEvent.LoadChartAccounts -> referenceDesk.loadChart()
            CashEvent.ClearNotice -> setState { copy(notice = null) }

            is CashEvent.Ask -> ask(event.prompt)
            is CashEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            CashEvent.DismissPrompt -> setState { copy(prompt = null) }
            CashEvent.ConfirmPrompt -> currentState.prompt?.let(promptDesk::resolve)

            CashEvent.AddReceipt -> setState {
                copy(draft = draft.copy(receipts = draft.receipts + DraftReceipt()))
            }

            is CashEvent.RemoveReceipt -> setState {
                // Never empty: a form with no rows offers nothing to fill in and
                // no obvious way back to a row.
                val remaining = draft.receipts.filterIndexed { index, _ -> index != event.index }
                copy(draft = draft.copy(receipts = remaining.ifEmpty { listOf(DraftReceipt()) }))
            }

            is CashEvent.EditReceipt -> setState {
                copy(
                    draft = draft.copy(
                        receipts = draft.receipts.mapIndexed { index, receipt ->
                            if (index == event.index) event.receipt else receipt
                        },
                    ),
                )
            }

            is CashEvent.EditSubmitNotes -> setState { copy(draft = draft.copy(notes = event.notes)) }
            is CashEvent.AttachReceipt -> referenceDesk.attachReceipt(event.index)
            CashEvent.SubmitReceipts -> crewDesk.submitReceipts()

            is CashEvent.EditFloatRequest -> setState { copy(floatDraft = event.draft) }
            CashEvent.SubmitFloatRequest -> crewDesk.submitFloatRequest()
            is CrewEvent -> crewDesk.handle(event)
            CashEvent.RaiseFloatForCrew -> open(CashDestination.FloatRequest)

            is CashEvent.CodeClaim -> act(str(S.desktop_card_coding_saved)) {
                repository.codeClaim(event.batchId, event.claimId, event.costCode, event.description)
            }

            is CashEvent.OpenCoding -> openCoding(event.batchId, event.claimId)
            CashEvent.CloseCoding -> setState { copy(coding = null) }
            is CashEvent.EditCodingLine -> editCoding {
                copy(lines = lines.mapIndexed { index, line -> if (index == event.index) event.line else line })
            }

            CashEvent.AddCodingLine -> editCoding {
                // The new line starts at whatever is still uncoded, which is
                // the amount someone adding a line almost always means to enter.
                copy(lines = lines + EditorLine(id = newLineId(), unitPrice = remaining.coerceAtLeast(0.0)))
            }

            is CashEvent.RemoveCodingLine -> editCoding { copy(lines = LineItemEditor.remove(lines, event.id)) }
            is CashEvent.SplitCodingLine -> editCoding {
                copy(lines = LineItemEditor.split(lines, event.id, event.ways, newLineId))
            }

            CashEvent.SaveCoding -> saveCoding()

            is CashEvent.EditSettings -> setState { copy(settingsDraft = event.settings) }
            CashEvent.SaveSettings -> saveSettings()
            is CashEvent.EditTeamMember -> settingsDesk.editMember(event.draft)
            CashEvent.SaveTeamMember -> settingsDesk.saveMember()
            is CashEvent.RemoveTeamMember -> settingsDesk.removeMember(event.index)
            is CashEvent.EditRequestCap -> setState { copy(capDraft = event.cap) }
            CashEvent.SaveRequestCap -> settingsDesk.saveCap()
            is CashEvent.EditAssignmentRules -> setState { copy(rulesDraft = event.rules) }
            CashEvent.SaveAssignmentRules -> settingsDesk.saveRules()
            // -- settings parity --
            is SettingsEvent -> settingsDesk.handle(event)

            is CashEvent.EditEffectiveDate -> batchDesk.editDate(event.ymd)
            is CashEvent.EditSeniorNotes -> batchDesk.editNotes(event.text)
            is CashEvent.ToggleClaim -> batchDesk.toggleClaim(event.claimId)
            is CashEvent.SelectAllClaims -> batchDesk.selectAll(event.selected)
            is CashEvent.ToggleVerify -> batchDesk.toggleVerify(event.claimId)
            is CashEvent.ShowHistory -> batchDesk.showHistory(event.open)
            is CashEvent.ShowQuery -> batchDesk.showQuery(event.open)
            is CashEvent.EditQuery -> batchDesk.editQuery(event.text)
            CashEvent.SendQuery -> batchDesk.sendQuery()

            is CashEvent.OpenReconciliation -> reconDesk.open(event.id)
            is CashEvent.EditReconciliation -> reconDesk.edit(event.draft)
            CashEvent.CloseReconciliation -> reconDesk.close()
            CashEvent.SaveReconciliation -> reconDesk.save()

            is CashEvent.ShowFunds -> floatDesk.showFunds(event.open)
            is CashEvent.EditFunds -> setState { copy(funds = event.state) }
            CashEvent.SubmitFunds -> floatDesk.submitFunds()

            is CashEvent.Export -> exportDesk.export(event.register, event.format)

            // -- batch parity --
            is BatchEvent -> batchDesk.handle(event)
            // -- floats parity --
            is CashEvent.ActNow -> promptDesk.resolve(event.prompt)
            is CashEvent.ToggleFloatBatches -> floatDesk.toggleBatches(event.floatId)
            is CashEvent.SelectFloatBatch -> floatDesk.selectBatch(event.floatId, event.batchId)
            is CashEvent.ShowFloatHistory -> floatDesk.showHistory(event.floatId, event.reference)
            is CashEvent.ToggleDetailBatch -> floatDesk.toggleDetailBatch(event.batchId)
            // -- funds parity --
            is CashEvent.Funds -> fundsDesk.handle(event.action)
        }
    }

    // -- entry and navigation --------------------------------------------------

    /**
     * The composition on screen says how the tool was opened.
     *
     * An accountant's standalone entry is the crew view; inside the hub it is
     * the console. The viewer every handler checks is swapped with it, and a
     * page the new view does not offer gives way to its landing.
     */
    private fun enter(asTool: Boolean) {
        if (asTool == enteredAsTool) return
        enteredAsTool = asTool
        reseat { it.copy(enteredAsTool = asTool) }
    }

    /** Replaces the viewer, and moves off a page it can no longer open. */
    private fun reseat(change: (CashViewer) -> CashViewer) {
        val next = change(currentState.viewer)
        val stays = currentState.destination.openableBy(next)
        val destination = if (stays) currentState.destination else CashDestination.landing(next, currentState.pipeline)
        setState {
            copy(
                viewer = next,
                destination = destination,
                pipeline = if (destination.section == CashSection.Shared) pipeline else destination.expenseType,
                selectedBatchId = if (stays) selectedBatchId else null,
                panel = if (stays) panel else null,
            )
        }
        if (!stays && started) load(destination)
    }

    /**
     * Opens [destination] — or, when this viewer may not be there, the page
     * they land on. A deep link re-checks, as the web's bounce does
     * (`CashExpensesModule.jsx:551-571`); the tab list alone did not stop one.
     */
    private fun open(requested: CashDestination) {
        val destination = if (requested.openableBy(currentState.viewer)) {
            requested
        } else {
            CashDestination.landing(currentState.viewer, currentState.pipeline)
        }
        setState {
            copy(
                destination = destination,
                pipeline = if (destination.section == CashSection.Shared) pipeline else destination.expenseType,
                selectedBatchId = null,
                selectedFloatId = null,
                panel = null,
                recon = null,
                funds = null,
                floatDetail = null,
                search = "",
                error = null,
            )
        }
        crewDesk.onOpen(destination)
        load(destination)
        readTab(destination)
    }

    /** Opens a dialog, filling what it needs from what is loaded. */
    private fun ask(prompt: CashPrompt) {
        if (prompt is CashPrompt.ReadyToCollect && currentState.companies.isEmpty()) loadCompanies()
        setState { copy(prompt = prompt) }
    }

    // -- loading -----------------------------------------------------------

    /**
     * Fetches what [destination] renders, and nothing else.
     *
     * Cancels any load already running: switching tabs quickly otherwise lands
     * two responses in either order, and the loser overwrites the page the user
     * is actually looking at.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod") // A dispatch table; splitting it hides the mapping.
    private fun load(destination: CashDestination) {
        loadJob?.cancel()
        // The crew is re-read with every page: it belongs to the open
        // production, and every name on these pages is looked up in it.
        val crew = CashDepartments.withIds(assignees(), currentState.departments)
        setState { copy(loading = true, error = null, assignees = crew) }
        if (destination.codes && currentState.settings == null) loadQuickCodes()
        if (destination.codes) referenceDesk.loadChart()
        loadJob = launch {
            val outcome: ZillitResult<CashUiState.() -> CashUiState> = when (destination) {
                CashDestination.PettyCashOverview ->
                    repository.pettyCashOverview().mapState { copy(pettyCashOverview = it) }

                CashDestination.OutOfPocketOverview ->
                    repository.outOfPocketOverview().mapState { copy(outOfPocketOverview = it) }

                // The overview's own `floats` are not the float list: the
                // web keeps `myFloats` from `listMyFloats`, sorted, for every
                // crew page, and the overview never overwrites it.
                CashDestination.MyOverview -> withMyFloats(repository.myOverview().mapState { copy(myOverview = it) })

                CashDestination.ActiveFloats ->
                    repository.activeFloats().mapState { copy(activeFloats = it) }

                CashDestination.TopUps ->
                    repository.topUps().mapState { copy(topUps = it) }

                CashDestination.FloatRequest ->
                    repository.myFloats().mapState { copy(myFloats = CashFloatOrder.oldestFirst(it)) }

                // The floats, then the selected float's top-ups (funds parity).
                CashDestination.CashExtension -> fundsDesk.loadExtension()

                CashDestination.SubmitReceipts, CashDestination.OutOfPocketSubmit ->
                    crewDesk.loadSubmit(destination.expenseType)

                CashDestination.ReceiptsHistory, CashDestination.OutOfPocketHistory ->
                    withMyFloats(
                        repository.myBatches(expenseType = destination.expenseType.wire)
                            .mapState { copy(myBatches = it) },
                    )

                CashDestination.ApprovalQueue -> loadApprovalQueue()

                CashDestination.CodingQueue -> queue(CashQueue.Coding, null)

                CashDestination.AuditQueue -> queue(CashQueue.Audit, null)

                CashDestination.ClaimReview -> queue(CashQueue.Approval, ExpenseType.OutOfPocket)

                CashDestination.PettyCashSignOff -> queue(CashQueue.SignOff, ExpenseType.PettyCash)

                CashDestination.OutOfPocketSignOff -> queue(CashQueue.SignOff, ExpenseType.OutOfPocket)

                CashDestination.History -> queue(CashQueue.History, null)

                // The sign-off queue also carries escalated batches, which are
                // the senior's; Post & Ledger lists what is postable
                // (`PCPostLedgerPage.jsx:1305`).
                CashDestination.PostLedger -> queue(CashQueue.SignOff, ExpenseType.PettyCash, postable = true)

                CashDestination.OutOfPocketPost -> queue(CashQueue.SignOff, ExpenseType.OutOfPocket, postable = true)

                CashDestination.PaymentRouting ->
                    repository.paymentRouting().mapState { copy(paymentRouting = it) }

                CashDestination.CashReconciliation -> loadReconciliation()

                CashDestination.DepartmentOverview -> loadDepartmentOverview()

                CashDestination.Settings ->
                    // A socket-driven reload leaves an edit in progress alone.
                    repository.settings().mapState { settingsReloaded(it) }
            }

            when (outcome) {
                is ZillitResult.Success -> setState { outcome.data(this).copy(loading = false).keepingSelection() }
                is ZillitResult.Failure -> setState { copy(loading = false, error = outcome.error) }
            }
        }
    }

    private suspend fun queue(
        queue: CashQueue,
        type: ExpenseType?,
        postable: Boolean = false,
    ): ZillitResult<CashUiState.() -> CashUiState> =
        repository.queue(queue, type).mapState { rows ->
            copy(queueBatches = if (postable) rows.filter { it.status in CashRules.POSTABLE } else rows)
        }

    /** A reload keeps the open batch open while it is still listed, and closes it once it has moved on. */
    private fun CashUiState.keepingSelection(): CashUiState {
        val open = selectedBatchId ?: return this
        val listed = (queueBatches + myBatches).any { it.id == open }
        return if (listed) this else copy(selectedBatchId = null, panel = null)
    }

    /**
     * [page], plus the crew member's floats beside it. The floats are advisory
     * here: a failed read keeps what was there rather than failing the page.
     */
    private suspend fun withMyFloats(
        page: ZillitResult<CashUiState.() -> CashUiState>,
    ): ZillitResult<CashUiState.() -> CashUiState> {
        if (page is ZillitResult.Failure) return page
        val applyPage = (page as ZillitResult.Success).data
        val floats = (repository.myFloats() as? ZillitResult.Success)?.data?.let(CashFloatOrder::oldestFirst)
        return ZillitResult.Success { applyPage().let { if (floats != null) it.copy(myFloats = floats) else it } }
    }

    /**
     * The approval queue holds two different things — float requests and
     * receipt batches — which the server serves from two endpoints. A failure
     * in either is reported; a partial queue would look like an empty one.
     */
    private suspend fun loadApprovalQueue(): ZillitResult<CashUiState.() -> CashUiState> {
        val batches = repository.queue(CashQueue.Approval, null)
        if (batches is ZillitResult.Failure) return batches
        val floats = repository.floatApprovalQueue()
        if (floats is ZillitResult.Failure) return floats
        val loadedBatches = (batches as ZillitResult.Success).data
        val loadedFloats = (floats as ZillitResult.Success).data
        return ZillitResult.Success { copy(queueBatches = loadedBatches, floatApprovals = loadedFloats) }
    }

    private suspend fun loadReconciliation(): ZillitResult<CashUiState.() -> CashUiState> {
        val rows = repository.reconciliations()
        if (rows is ZillitResult.Failure) return rows
        val loadedRows = (rows as ZillitResult.Success).data
        // funds parity: no bare compute-book here — the web asks for a book
        // balance only from a period's count, with its opening balance and month.
        return ZillitResult.Success { copy(reconciliations = loadedRows) }
    }

    private suspend fun loadDepartmentOverview(): ZillitResult<CashUiState.() -> CashUiState> {
        // The viewer's own department first, as the web asks
        // (`currentUser.department_id`, `PCDeptViewPage.jsx:31-34`).
        val departmentId = currentState.viewer.departmentId?.takeIf { it.isNotBlank() }
            ?: currentState.departmentOverview?.departmentId
            ?: currentState.myFloats.firstNotNullOfOrNull { it.departmentId }
            ?: return ZillitResult.Success { copy(departmentOverview = null) }
        return repository.departmentOverview(departmentId).mapState { copy(departmentOverview = it) }
    }

    /**
     * The coding editor's shortcuts live in the settings document, which used
     * to be read only when Settings opened — so the editor offered none.
     * Quietly: a coordinator who may not read settings still codes by hand.
     */
    private fun loadQuickCodes() {
        launch {
            (repository.settings() as? ZillitResult.Success)?.data?.let { loaded ->
                setState { copy(settings = settings ?: loaded) }
            }
        }
    }

    /** The cost-report lock that bounds every ledger date. No lock is the answer to a failed read. */
    private fun loadLock() {
        launch {
            val lock = (repository.lockedThrough() as? ZillitResult.Success)?.data
            setState { copy(lockedThrough = lock) }
        }
    }

    private fun loadCompanies() {
        launch {
            when (val companies = repository.companies()) {
                is ZillitResult.Success -> setState { copy(companies = companies.data) }
                is ZillitResult.Failure -> host.report(companies.error)
            }
        }
    }

    /** Reads the float request form's configuration; a failure leaves every field showing. */
    private fun loadFormTemplate() {
        launchResult(formTemplate, { template -> setState { copy(formTemplate = template) } }, { })
    }

    // -- actions -----------------------------------------------------------

    /**
     * Opens a receipt's coding, seeded from whatever it already carries — or
     * one line for the whole receipt, the common case on a fresh batch.
     */
    private fun openCoding(batchId: String, claimId: String) {
        if (!currentState.canCode) return host.noRights()
        val batch = currentState.queueBatches.firstOrNull { it.id == batchId } ?: return
        val claim = currentState.panelClaims.firstOrNull { it.id == claimId }
            ?: batch.claims.firstOrNull { it.id == claimId }
            ?: return
        val existing = LineItemEditor.fromWire(claim.lineItems)
        setState {
            copy(
                coding = CodingDraft(
                    batchId = batchId,
                    claimId = claimId,
                    receiptGross = claim.grossAmount,
                    currency = batch.currency,
                    lines = existing.ifEmpty {
                        listOf(
                            EditorLine(
                                id = newLineId(),
                                description = claim.description,
                                unitPrice = claim.netAmount.takeIf { it > 0 } ?: claim.grossAmount,
                                account = claim.costCode.orEmpty(),
                                taxRatePercent = LineItemEditor.normaliseTaxRate(claim.taxRate),
                                taxType = claim.taxType.orEmpty(),
                            ),
                        )
                    },
                ),
            )
        }
    }

    private fun editCoding(change: CodingDraft.() -> CodingDraft) = setState {
        val open = coding ?: return@setState this
        copy(coding = open.change())
    }

    /**
     * Saves the open coding, refusing one that does not reach the receipt —
     * the commonest reason a batch comes back from audit.
     */
    private fun saveCoding() {
        val draft = currentState.coding ?: return
        if (!currentState.canCode) return host.noRights()
        if (!draft.balances) {
            sendEffect(CashEffect.Failed(str(S.desktop_ce_coding_does_not_add_up)))
            return
        }
        if (draft.lines.filterNot { it.autoDeduction }.any { it.account.isBlank() }) {
            sendEffect(CashEffect.Failed(str(S.desktop_ce_line_needs_cost_code)))
            return
        }

        val lines = LineItemEditor.toWire(draft.lines, newLineId)
        // The editor closes only on a saved coding; a failed save keeps the
        // hand-entered lines open for a retry instead of discarding them.
        act(str(S.desktop_card_coding_saved), onSuccess = { copy(coding = null) }) {
            repository.saveClaimLines(draft.batchId, draft.claimId, lines)
        }
    }

    private fun saveSettings() {
        val draft = currentState.settingsDraft ?: return
        if (!currentState.viewer.canOpenSettings) return host.noRights()
        launch {
            setState { copy(busy = true) }
            when (val saved = repository.updateSettings(draft)) {
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            busy = false,
                            settings = saved.data,
                            settingsDraft = saved.data,
                            notice = str(S.desktop_ce_settings_saved),
                        )
                    }
                    // The save changes the grants `/metadata` reports; the
                    // saver re-pulls without waiting for the socket's echo.
                    refetchMetadata(reloadPage = false)
                }

                is ZillitResult.Failure -> {
                    setState { copy(busy = false) }
                    sendEffect(CashEffect.Failed(saved.error.localised()))
                }
            }
        }
    }

    /**
     * Runs a mutation, then reloads the page it happened on.
     *
     * The reload is the point: these actions change what the row *is* — an
     * approved batch leaves the approval queue — and patching the local list
     * instead would drift from the server's own idea of which queue it belongs
     * to now.
     */
    private fun act(
        success: String,
        // Applied only once the server has said yes — a submit clears its
        // draft here, never before the round trip, so a failed save leaves the
        // typing where the user can retry it.
        onSuccess: CashUiState.() -> CashUiState = { this },
        after: () -> Unit = {},
        block: suspend () -> ZillitResult<Unit>,
    ): Job = launch {
        setState { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                setState { onSuccess().copy(busy = false, notice = success) }
                after()
                load(currentState.destination)
                // A batch still open re-reads its receipts, so what was just
                // saved — a coding, a verify — is what it shows.
                batchDesk.refresh()
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(CashEffect.Failed(result.error.localised()))
            }
        }
    }

    private fun <T> ZillitResult<T>.mapState(
        transform: CashUiState.(T) -> CashUiState,
    ): ZillitResult<CashUiState.() -> CashUiState> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success({ transform(data) })
        is ZillitResult.Failure -> this
    }

    private companion object {
        /**
         * The fallback id source — only ever ids this client keeps to itself,
         * each replaced with a server id on save; see `LineItemEditor.toWire`.
         */
        private var lineCounter = 0
    }
}

/** Pages where receipts are coded, so the editor wants the quick codes. */
private val CashDestination.codes: Boolean
    get() = this == CashDestination.CodingQueue || this == CashDestination.AuditQueue || isPostLedger

/** The message shown to the user for a failed call. */
internal val ZillitError.displayMessage: String get() = userMessage
