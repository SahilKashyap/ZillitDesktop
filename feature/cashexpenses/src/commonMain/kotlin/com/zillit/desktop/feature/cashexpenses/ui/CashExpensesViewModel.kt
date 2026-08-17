package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.cashexpenses.domain.CashQueue
import com.zillit.desktop.feature.cashexpenses.domain.CashRepository
import com.zillit.desktop.feature.cashexpenses.domain.CashSettings
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.DraftReceipt
import com.zillit.desktop.feature.cashexpenses.domain.EditorLine
import com.zillit.desktop.feature.cashexpenses.domain.LineItemEditor
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
) : ZillitViewModel<CashUiState, CashEvent, CashEffect>(
    CashUiState(
        viewer = viewer(),
        destination = CashDestination.landing(viewer(), ExpenseType.PettyCash),
    ),
) {

    private var loadJob: Job? = null
    private var started = false

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
        launch {
            val identity = viewer()
            when (val metadata = repository.metadata()) {
                is ZillitResult.Success -> setState {
                    val resolved = identity.copy(metadata = metadata.data)
                    copy(
                        viewer = resolved,
                        destination = CashDestination.landing(resolved, pipeline),
                    )
                }

                is ZillitResult.Failure -> setState {
                    copy(viewer = identity, destination = CashDestination.landing(identity, pipeline))
                }
            }
            load(currentState.destination)
        }
    }

    /** Re-reads the viewer when the open production changes. */
    fun onProjectChanged() {
        started = false
        start()
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: CashEvent) {
        when (event) {
            CashEvent.Refresh -> load(currentState.destination)

            is CashEvent.Open -> {
                setState {
                    copy(
                        destination = event.destination,
                        pipeline = if (event.destination.section == CashSection.Shared) {
                            pipeline
                        } else {
                            event.destination.expenseType
                        },
                        selectedBatchId = null,
                        selectedFloatId = null,
                        search = "",
                        error = null,
                    )
                }
                load(event.destination)
            }

            is CashEvent.SwitchPipeline -> {
                val landing = CashDestination.landing(currentState.viewer, event.pipeline)
                setState { copy(pipeline = event.pipeline, destination = landing, error = null) }
                load(landing)
            }

            is CashEvent.Search -> setState { copy(search = event.query) }
            is CashEvent.SelectBatch -> setState { copy(selectedBatchId = event.batchId) }
            is CashEvent.SelectFloat -> setState { copy(selectedFloatId = event.floatId) }
            CashEvent.ClearNotice -> setState { copy(notice = null) }

            is CashEvent.Ask -> setState { copy(prompt = event.prompt) }
            is CashEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            CashEvent.DismissPrompt -> setState { copy(prompt = null) }
            CashEvent.ConfirmPrompt -> resolvePrompt()

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
            CashEvent.SubmitReceipts -> submitReceipts()

            is CashEvent.EditFloatRequest -> setState { copy(floatDraft = event.draft) }
            CashEvent.SubmitFloatRequest -> submitFloatRequest()

            is CashEvent.CodeClaim -> act("Coding saved") {
                repository.codeClaim(event.batchId, event.claimId, event.costCode, event.description)
            }

            is CashEvent.OpenCoding -> openCoding(event.batchId, event.claimId)
            CashEvent.CloseCoding -> setState { copy(coding = null) }

            is CashEvent.EditCodingLine -> setState {
                val open = coding ?: return@setState this
                copy(
                    coding = open.copy(
                        lines = open.lines.mapIndexed { index, line ->
                            if (index == event.index) event.line else line
                        },
                    ),
                )
            }

            CashEvent.AddCodingLine -> setState {
                val open = coding ?: return@setState this
                copy(
                    coding = open.copy(
                        lines = open.lines + EditorLine(
                            id = newLineId(),
                            // The new line starts at whatever is still uncoded,
                            // which is the amount someone adding a line almost
                            // always means to enter.
                            unitPrice = open.remaining.coerceAtLeast(0.0),
                        ),
                    ),
                )
            }

            is CashEvent.RemoveCodingLine -> setState {
                val open = coding ?: return@setState this
                copy(coding = open.copy(lines = LineItemEditor.remove(open.lines, event.id)))
            }

            is CashEvent.SplitCodingLine -> setState {
                val open = coding ?: return@setState this
                copy(
                    coding = open.copy(
                        lines = LineItemEditor.split(open.lines, event.id, event.ways, newLineId),
                    ),
                )
            }

            CashEvent.SaveCoding -> saveCoding()

            is CashEvent.EditSettings -> setState { copy(settingsDraft = event.settings) }
            CashEvent.SaveSettings -> saveSettings()
        }
    }

    // -- loading -----------------------------------------------------------

    /**
     * Fetches what [destination] renders, and nothing else.
     *
     * Cancels any load already running: switching tabs quickly otherwise lands
     * two responses in either order, and the loser overwrites the page the user
     * is actually looking at.
     */
    @Suppress("CyclomaticComplexMethod") // A dispatch table; splitting it hides the mapping.
    private fun load(destination: CashDestination) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val outcome: ZillitResult<CashUiState.() -> CashUiState> = when (destination) {
                CashDestination.PettyCashOverview ->
                    repository.pettyCashOverview().mapState { copy(pettyCashOverview = it) }

                CashDestination.OutOfPocketOverview ->
                    repository.outOfPocketOverview().mapState { copy(outOfPocketOverview = it) }

                CashDestination.MyOverview ->
                    repository.myOverview().mapState { copy(myOverview = it, myFloats = it.floats) }

                CashDestination.ActiveFloats ->
                    repository.activeFloats().mapState { copy(activeFloats = it) }

                CashDestination.TopUps ->
                    repository.topUps().mapState { copy(topUps = it) }

                CashDestination.FloatRequest, CashDestination.CashExtension ->
                    repository.myFloats().mapState { copy(myFloats = it) }

                CashDestination.SubmitReceipts, CashDestination.OutOfPocketSubmit ->
                    loadSubmitScreen()

                CashDestination.ReceiptsHistory, CashDestination.OutOfPocketHistory ->
                    repository.myBatches().mapState { copy(myBatches = it) }

                CashDestination.ApprovalQueue -> loadApprovalQueue()

                CashDestination.CodingQueue ->
                    repository.queue(CashQueue.Coding, null).mapState { copy(queueBatches = it) }

                CashDestination.AuditQueue ->
                    repository.queue(CashQueue.Audit, null).mapState { copy(queueBatches = it) }

                CashDestination.ClaimReview ->
                    repository.queue(CashQueue.Approval, ExpenseType.OutOfPocket)
                        .mapState { copy(queueBatches = it) }

                CashDestination.PettyCashSignOff ->
                    repository.queue(CashQueue.SignOff, ExpenseType.PettyCash)
                        .mapState { copy(queueBatches = it) }

                CashDestination.OutOfPocketSignOff ->
                    repository.queue(CashQueue.SignOff, ExpenseType.OutOfPocket)
                        .mapState { copy(queueBatches = it) }

                CashDestination.History ->
                    repository.queue(CashQueue.History, null).mapState { copy(queueBatches = it) }

                CashDestination.PostLedger ->
                    repository.queue(CashQueue.SignOff, ExpenseType.PettyCash)
                        .mapState { copy(queueBatches = it) }

                CashDestination.OutOfPocketPost ->
                    repository.queue(CashQueue.SignOff, ExpenseType.OutOfPocket)
                        .mapState { copy(queueBatches = it) }

                CashDestination.PaymentRouting ->
                    repository.paymentRouting().mapState { copy(paymentRouting = it) }

                CashDestination.CashReconciliation -> loadReconciliation()

                CashDestination.DepartmentOverview -> loadDepartmentOverview()

                CashDestination.Settings ->
                    repository.settings().mapState { copy(settings = it, settingsDraft = it) }
            }

            when (outcome) {
                is ZillitResult.Success -> setState { outcome.data(this).copy(loading = false) }
                is ZillitResult.Failure -> setState { copy(loading = false, error = outcome.error) }
            }
        }
    }

    /**
     * The Submit Receipts screen needs both the float and the pending batches.
     *
     * Both, because the settlement maths floors the headroom with the live
     * pending total — see `FloatSettlement`. Fetching only the float would let a
     * stale `receipts_commits` overstate what the float can absorb, and quietly
     * under-reimburse the person submitting.
     */
    private suspend fun loadSubmitScreen(): ZillitResult<CashUiState.() -> CashUiState> {
        val floats = repository.myFloats()
        if (floats is ZillitResult.Failure) return floats
        val batches = repository.myBatches()
        if (batches is ZillitResult.Failure) return batches
        val loadedFloats = (floats as ZillitResult.Success).data
        val loadedBatches = (batches as ZillitResult.Success).data
        return ZillitResult.Success { copy(myFloats = loadedFloats, myBatches = loadedBatches) }
    }

    /**
     * The approval queue holds two different things — float requests and
     * receipt batches — which the server serves from two endpoints.
     *
     * A failure in either is reported; a partial queue would look like an empty
     * one and leave work unapproved with nobody aware of it.
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
        // The book balance is advisory — it is what the ledger thinks the cash
        // should be. A failure there must not hide the reconciliation list.
        val book = repository.computeBookBalance().getOrNull()
        return ZillitResult.Success { copy(reconciliations = loadedRows, bookBalance = book) }
    }

    private suspend fun loadDepartmentOverview(): ZillitResult<CashUiState.() -> CashUiState> {
        val departmentId = currentState.viewer.metadata
            .let { currentState.departmentOverview?.departmentId }
            ?: currentState.myFloats.firstNotNullOfOrNull { it.departmentId }
            ?: return ZillitResult.Success { copy(departmentOverview = null) }
        return repository.departmentOverview(departmentId).mapState { copy(departmentOverview = it) }
    }

    // -- actions -----------------------------------------------------------

    private fun submitReceipts() {
        val state = currentState
        val request = NewClaimBatch(
            expenseType = state.destination.expenseType,
            floatId = state.submittableFloat?.id,
            receipts = state.draft.receipts,
            // The settlement decides itself: whether the batch reduces the float
            // or is reimbursed is arithmetic, not a choice the submitter makes.
            settlementType = if (state.destination.expenseType == ExpenseType.OutOfPocket) {
                REIMBURSE
            } else if (state.settlement.reimburses) {
                REIMBURSE
            } else {
                REDUCE_FLOAT
            },
            notes = state.draft.notes.takeIf { it.isNotBlank() },
        )

        val invalid = request.validationError()
        if (invalid != null) {
            sendEffect(CashEffect.Failed(invalid))
            return
        }

        act("Receipts submitted", resetDraft = true) { repository.submitReceipts(request) }
    }

    private fun submitFloatRequest() {
        val draft = currentState.floatDraft
        val amount = draft.amount.trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            sendEffect(CashEffect.Failed("Enter the amount of cash you need."))
            return
        }
        if (draft.purpose.isBlank()) {
            sendEffect(CashEffect.Failed("Say what the float is for."))
            return
        }

        val request = NewFloatRequest(
            amount = amount,
            currency = currentState.myFloats.firstOrNull()?.currency,
            purpose = draft.purpose.trim(),
            departmentId = draft.departmentId.takeIf { it.isNotBlank() },
            duration = draft.duration.takeIf { it.isNotBlank() },
            durationType = draft.durationType,
        )
        act("Float requested") { repository.requestFloat(request) }
        setState { copy(floatDraft = FloatRequestDraft()) }
    }

    /**
     * Opens a receipt's coding, seeded from whatever it already carries.
     *
     * A receipt with no lines yet gets one covering its whole amount — that is
     * the common case on a freshly submitted batch, and starting from an empty
     * grid makes the coder type a figure the screen already knows.
     */
    private fun openCoding(batchId: String, claimId: String) {
        val batch = currentState.queueBatches.firstOrNull { it.id == batchId } ?: return
        val claim = batch.claims.firstOrNull { it.id == claimId } ?: return
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

    /**
     * Saves the open coding, refusing one that does not reach the receipt.
     *
     * Blocked here rather than left to the server: coding that does not balance
     * is the commonest reason a batch comes back from audit, and a round trip
     * through two queues to learn it is a day lost.
     */
    private fun saveCoding() {
        val draft = currentState.coding ?: return
        if (!draft.balances) {
            sendEffect(
                CashEffect.Failed(
                    "The coding comes to a different amount from the receipt. " +
                        "Adjust the lines so they add up before saving.",
                ),
            )
            return
        }
        if (draft.lines.filterNot { it.autoDeduction }.any { it.account.isBlank() }) {
            sendEffect(CashEffect.Failed("Every line needs a cost code."))
            return
        }

        val lines = LineItemEditor.toWire(draft.lines, newLineId)
        act("Coding saved") { repository.saveClaimLines(draft.batchId, draft.claimId, lines) }
        setState { copy(coding = null) }
    }

    private fun saveSettings() {
        val draft = currentState.settingsDraft ?: return
        launch {
            setState { copy(busy = true) }
            when (val saved = repository.updateSettings(draft)) {
                is ZillitResult.Success -> setState {
                    copy(
                        busy = false,
                        settings = saved.data,
                        settingsDraft = saved.data,
                        notice = "Settings saved",
                    )
                }

                is ZillitResult.Failure -> {
                    setState { copy(busy = false) }
                    sendEffect(CashEffect.Failed(saved.error.userMessage))
                }
            }
        }
    }

    /** Runs the pending [CashPrompt] and clears it. */
    @Suppress("CyclomaticComplexMethod") // A dispatch table over the prompt's own actions.
    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }

        when (prompt) {
            is CashPrompt.Confirm -> resolveConfirm(prompt)

            is CashPrompt.WithReason -> {
                val reason = prompt.reason.trim()
                if (reason.isEmpty()) {
                    sendEffect(CashEffect.Failed("A reason is required."))
                    setState { copy(prompt = prompt) }
                    return
                }
                when (prompt.action) {
                    ReasonedAction.RejectFloat ->
                        act("Float rejected") { repository.rejectFloat(prompt.targetId, reason) }

                    ReasonedAction.RejectBatch ->
                        act("Batch rejected") { repository.rejectBatch(prompt.targetId, reason) }

                    ReasonedAction.QueryBatch ->
                        act("Query sent") { repository.queryBatch(prompt.targetId, reason) }

                    ReasonedAction.EscalateBatch ->
                        act("Escalated") { repository.escalateBatch(prompt.targetId, reason) }
                }
            }

            is CashPrompt.WithAmount -> {
                val amount = prompt.amount.trim().toDoubleOrNull()
                if (amount == null || amount <= 0) {
                    sendEffect(CashEffect.Failed("Enter an amount greater than zero."))
                    setState { copy(prompt = prompt) }
                    return
                }
                when (prompt.action) {
                    AmountAction.PartialTopUp ->
                        act("Top-up recorded") { repository.partialTopUp(prompt.targetId, amount) }

                    AmountAction.RecordCashReturn -> act("Cash return recorded") {
                        repository.recordCashReturn(prompt.targetId, amount, prompt.note.takeIf(String::isNotBlank))
                    }

                    AmountAction.RequestFloatTopUp -> act("Top-up requested") {
                        repository.requestFloatTopUp(prompt.targetId, amount, prompt.note.takeIf(String::isNotBlank))
                    }

                    AmountAction.CreateReconciliation -> act("Reconciliation started") {
                        repository.createReconciliation(amount, prompt.note.takeIf(String::isNotBlank))
                            .toUnit()
                    }
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per confirmable action.
    private fun resolveConfirm(prompt: CashPrompt.Confirm) {
        val id = prompt.targetId
        when (prompt.action) {
            ConfirmAction.ApproveFloat -> act("Float approved") { repository.approveFloat(id, null) }
            ConfirmAction.OverrideFloat -> act("Float overridden") { repository.overrideFloat(id) }
            ConfirmAction.IssueFloat -> act("Float issued") { repository.issueFloat(id) }
            ConfirmAction.ReadyToCollect -> act("Marked ready to collect") {
                repository.markFloatReadyToCollect(id, floatCompany(id))
            }

            ConfirmAction.CollectFloat -> act("Collection recorded") { repository.collectFloat(id) }
            ConfirmAction.CloseFloat -> act("Float closed") { repository.closeFloat(id) }
            ConfirmAction.ApproveBatch -> act("Batch approved") { repository.approveBatch(id, null) }
            ConfirmAction.OverrideBatch -> act("Batch overridden") { repository.overrideBatch(id) }
            ConfirmAction.PostBatch -> postBatch(id)
            ConfirmAction.SubmitForReview -> act("Sent for review") { repository.submitBatchForReview(id) }
            ConfirmAction.SaveAndVerify -> act("Verified") { repository.saveAndVerify(id) }
            ConfirmAction.SaveAndSubmitCoded -> act("Coding submitted") { repository.saveAndSubmitCoded(id) }
            ConfirmAction.CompleteTopUp -> act("Top-up completed") { repository.completeTopUp(id) }
            ConfirmAction.SkipTopUp -> act("Top-up skipped") { repository.skipTopUp(id) }
            ConfirmAction.SignOffReconciliation -> act("Reconciliation signed off") {
                repository.signOffReconciliation(id, null)
            }
        }
    }

    /**
     * Posts a batch, refusing above this person's ceiling.
     *
     * Checked here as well as on the server: the server is the authority, but a
     * button that fails after the click teaches accountants to ignore the limit
     * rather than route the batch to someone who can post it.
     */
    private fun postBatch(batchId: String) {
        val batch = currentState.queueBatches.firstOrNull { it.id == batchId }
        val amount = batch?.totalGross ?: 0.0
        if (!currentState.viewer.canPost(amount)) {
            sendEffect(
                CashEffect.Failed(
                    "This batch is above your posting limit. Escalate it for senior sign-off instead.",
                ),
            )
            return
        }
        act("Batch posted") { repository.postBatch(batchId, null) }
    }

    /** The company a float already carries, which the transition requires. */
    private fun floatCompany(floatId: String): String? =
        (currentState.activeFloats + currentState.floatApprovals)
            .firstOrNull { it.id == floatId }
            ?.companyId

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
        resetDraft: Boolean = false,
        block: suspend () -> ZillitResult<Unit>,
    ) = launch {
        setState { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                setState {
                    copy(
                        busy = false,
                        notice = success,
                        draft = if (resetDraft) SubmitDraft() else draft,
                    )
                }
                load(currentState.destination)
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(CashEffect.Failed(result.error.userMessage))
            }
        }
    }

    private fun <T> ZillitResult<T>.mapState(
        transform: CashUiState.(T) -> CashUiState,
    ): ZillitResult<CashUiState.() -> CashUiState> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success({ transform(data) })
        is ZillitResult.Failure -> this
    }

    private fun <T> ZillitResult<T>.toUnit(): ZillitResult<Unit> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success(Unit)
        is ZillitResult.Failure -> this
    }

    private companion object {
        const val REIMBURSE = "REIMBURSE"
        const val REDUCE_FLOAT = "REDUCE_FLOAT"

        /**
         * The fallback id source.
         *
         * Only ever produces ids this client keeps to itself: every one is
         * replaced with a server id on save — see `LineItemEditor.toWire`.
         */
        private var lineCounter = 0
    }
}

/** The message shown to the user for a failed call. */
internal val ZillitError.displayMessage: String get() = userMessage
