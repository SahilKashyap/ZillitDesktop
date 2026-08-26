package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.BulkItem
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptLine
import com.zillit.desktop.feature.cardexpenses.domain.StatementRow
import com.zillit.desktop.feature.cardexpenses.domain.CardAlert
import com.zillit.desktop.feature.cardexpenses.domain.CardAnalytics
import com.zillit.desktop.feature.cardexpenses.domain.CardOverview
import com.zillit.desktop.feature.cardexpenses.domain.CardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.cardexpenses.domain.CardSettings
import com.zillit.desktop.feature.cardexpenses.domain.CardTopUp
import com.zillit.desktop.feature.cardexpenses.domain.CardTransaction
import com.zillit.desktop.feature.cardexpenses.domain.CardType
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ExpenseCard
import com.zillit.desktop.feature.cardexpenses.domain.NewCardRequest
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptScope
import com.zillit.desktop.feature.cardexpenses.domain.StatementImport
import com.zillit.desktop.feature.cardexpenses.domain.UploadHeadroom
import kotlinx.coroutines.Job

/** Everything the card tool is showing. */
data class CardUiState(
    val viewer: CardViewer,
    val destination: CardDestination,
    val loading: Boolean = false,
    val error: ZillitError? = null,
    val busy: Boolean = false,
    val notice: String? = null,

    val overview: CardOverview? = null,
    val analytics: CardAnalytics? = null,
    val cards: List<ExpenseCard> = emptyList(),
    val transactions: List<CardTransaction> = emptyList(),
    val receipts: List<CardReceipt> = emptyList(),
    val matchCandidates: List<CardTransaction> = emptyList(),
    val topUps: List<CardTopUp> = emptyList(),
    val alerts: List<CardAlert> = emptyList(),
    val imports: List<StatementImport> = emptyList(),
    val settings: CardSettings? = null,
    val settingsDraft: CardSettings? = null,
    val bulkItems: List<BulkItem> = emptyList(),
    val bulkCoding: BulkCoding = BulkCoding(),
    /** The statement whose rows are open for review, and those rows. */
    val openImportId: String? = null,
    val importRows: List<StatementRow> = emptyList(),
    /** The receipt whose splits are open, if any. */
    val splits: SplitDraft? = null,

    val search: String = "",
    val selectedReceiptId: String? = null,
    val selectedCardId: String? = null,
    /** Rows ticked for a bulk approve or reject. */
    val selection: Set<String> = emptySet(),
    val draft: List<DraftCardReceipt> = listOf(DraftCardReceipt()),
    val prompt: CardPrompt? = null,
) {
    /** This viewer's own card, for the cardholder screens. */
    val myCard: ExpenseCard?
        get() = cards.firstOrNull { it.holderId == viewer.userId } ?: cards.firstOrNull()

    val headroom: UploadHeadroom get() = UploadHeadroom.of(myCard)

    val draftTotal: Double get() = draft.sumOf { it.amount.trim().toDoubleOrNull() ?: 0.0 }

    val selectedReceipt: CardReceipt? get() = receipts.firstOrNull { it.id == selectedReceiptId }

    val selectedCard: ExpenseCard? get() = cards.firstOrNull { it.id == selectedCardId }

    /** Bulk rows this viewer is allowed to tick. */
    val selectableBulkItems: List<BulkItem>
        get() = bulkItems.filter { it.selectableBy(viewer.userId) }

    /** What the ticked bulk rows come to. */
    val bulkSelectedTotal: Double
        get() = bulkItems.filter { it.id in selection }.sumOf { it.amount }

    /** The destinations this viewer may open, in sidebar order. */
    val destinations: List<CardDestination>
        get() = CardDestination.entries.filter { it.visibleTo(viewer) }
}

/**
 * A receipt's splits, open for editing.
 *
 * Card splits are simpler than the cash module's: no parent/child tree, just
 * a flat set of coded portions that has to add up to the receipt. The
 * constraint is the same, and so is the reason for showing it live.
 */
data class SplitDraft(
    val receiptId: String,
    val receiptGross: Double,
    val currency: String?,
    val lines: List<ReceiptLine>,
) {
    val total: Double get() = lines.sumOf { it.gross }

    val remaining: Double get() = receiptGross - total

    val balances: Boolean get() = kotlin.math.abs(remaining) < PENNY

    private companion object {
        const val PENNY = 0.005
    }
}

/** A question asked before something irreversible. */
sealed interface CardPrompt {
    data class Confirm(
        val action: CardConfirmAction,
        val targetId: String,
        val title: String,
        val message: String,
    ) : CardPrompt

    data class WithReason(
        val action: CardReasonAction,
        val targetId: String,
        val title: String,
        val label: String,
        val reason: String = "",
    ) : CardPrompt

    data class WithAmount(
        val action: CardAmountAction,
        val targetId: String,
        val title: String,
        val label: String,
        val amount: String = "",
        val note: String = "",
    ) : CardPrompt

    /** Attaching a physical card number to a live digital card. */
    data class WithCardNumber(
        val targetId: String,
        val title: String,
        val number: String = "",
    ) : CardPrompt
}

enum class CardConfirmAction {
    ApproveCard,
    OverrideCard,
    ActivateCard,
    SuspendCard,
    ReactivateCard,
    ApproveReceipt,
    OverrideReceipt,
    PostReceipt,
    SubmitReceiptForApproval,
    ConfirmMatch,
    UnmatchReceipt,
    FlagPersonal,
    DismissDuplicate,
    DismissPersonal,
    DeleteReceipt,
    CompleteTopUp,
    SkipTopUp,
    DismissAlert,
    InvestigateAlert,
    BulkApprove,
    BulkReject,
    PostTransaction,
    FlagTransactionPersonal,
}

enum class CardReasonAction { RejectCard, RejectReceipt, QueryTransaction, RejectTransaction, ResolveAlert }

enum class CardAmountAction { RequestTopUp, PartialTopUp }

/** Everything the user can do in the card tool. */
sealed interface CardEvent {
    data object Refresh : CardEvent
    data class Open(val destination: CardDestination) : CardEvent
    data class Search(val query: String) : CardEvent
    data class SelectReceipt(val receiptId: String?) : CardEvent
    data class SelectCard(val cardId: String?) : CardEvent
    data class ToggleSelection(val id: String) : CardEvent
    data object ClearSelection : CardEvent
    data object ClearNotice : CardEvent

    data class Ask(val prompt: CardPrompt) : CardEvent
    data class UpdatePrompt(val prompt: CardPrompt) : CardEvent
    data object DismissPrompt : CardEvent
    data object ConfirmPrompt : CardEvent

    data object AddDraftReceipt : CardEvent
    data class RemoveDraftReceipt(val index: Int) : CardEvent
    data class EditDraftReceipt(val index: Int, val receipt: DraftCardReceipt) : CardEvent
    data object SubmitDraftReceipts : CardEvent

    data class RequestCard(val limit: String, val type: CardType, val reason: String) : CardEvent
    data class MatchReceipt(val receiptId: String, val transactionId: String) : CardEvent
    data class CodeReceipt(val receiptId: String, val code: String, val description: String?) : CardEvent
    data class UpdateBsCode(val cardId: String, val code: String) : CardEvent
    data class ImportStatement(val attachmentKey: String) : CardEvent

    /** Opens a receipt's stored image or PDF through the host's file layer. */
    data class ViewReceipt(val attachmentKey: String) : CardEvent

    data class EditSettings(val settings: CardSettings) : CardEvent
    data object SaveSettings : CardEvent

    // -- bulk processing ---------------------------------------------------

    data class EditBulkCoding(val coding: BulkCoding) : CardEvent
    data object SelectAllBulk : CardEvent
    data object BulkPost : CardEvent

    // -- statement review --------------------------------------------------

    data class OpenImport(val importId: String?) : CardEvent
    data object ProcessImportRows : CardEvent
    data object SubmitRowsToHolders : CardEvent

    // -- receipt splits ----------------------------------------------------

    data class OpenSplits(val receiptId: String) : CardEvent
    data object CloseSplits : CardEvent
    data class EditSplit(val index: Int, val line: ReceiptLine) : CardEvent
    data object AddSplit : CardEvent
    data class RemoveSplit(val index: Int) : CardEvent
    data object SaveSplits : CardEvent
}

sealed interface CardEffect {
    data class Failed(val message: String) : CardEffect
    data class OpenAttachment(val key: String) : CardEffect
}

/**
 * The card tool's one view model.
 *
 * Same shape as the cash module's, and for the same reasons — see
 * `CashExpensesViewModel`, whose account of per-destination loading and
 * reload-after-mutation applies here unchanged.
 */
@Suppress("TooManyFunctions") // One handler per user action; the alternative is a 300-line when.
class CardExpensesViewModel(
    private val repository: CardRepository,
    /** Read at start, not at construction — see the cash module's equivalent. */
    private val viewer: () -> CardViewer,
) : ZillitViewModel<CardUiState, CardEvent, CardEffect>(
    CardUiState(viewer = viewer(), destination = CardDestination.landing(viewer())),
) {

    private var loadJob: Job? = null
    private var started = false

    /** Resolves who this is, then opens their landing page. Idempotent. */
    fun start() {
        if (started) return
        started = true
        startInternal()
    }

    /** Re-reads the viewer when the open production changes. */
    fun onProjectChanged() {
        started = false
        start()
    }

    private fun startInternal() = launch {
        val identity = viewer()
        // Metadata and settings are settled independently: a failing /settings
        // must not blank the approval rights, and vice versa. The web learnt
        // this when a settings outage silently demoted every approver.
        when (val metadata = repository.metadata()) {
            is ZillitResult.Success -> setState {
                val resolved = identity.copy(metadata = metadata.data)
                copy(viewer = resolved, destination = CardDestination.landing(resolved))
            }

            is ZillitResult.Failure -> setState {
                copy(viewer = identity, destination = CardDestination.landing(identity))
            }
        }
        repository.settings().getOrNull()?.let { loaded ->
            setState {
                copy(
                    settings = loaded,
                    settingsDraft = settingsDraft ?: loaded,
                    viewer = viewer.copy(
                        metadata = viewer.metadata.copy(
                            codingRequired = loaded.codingRequired,
                            cardProviders = loaded.providers,
                        ),
                    ),
                )
            }
        }
        load(currentState.destination)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: CardEvent) {
        when (event) {
            CardEvent.Refresh -> load(currentState.destination)

            is CardEvent.Open -> {
                setState {
                    copy(
                        destination = event.destination,
                        search = "",
                        selection = emptySet(),
                        selectedReceiptId = null,
                        selectedCardId = null,
                        error = null,
                    )
                }
                load(event.destination)
            }

            is CardEvent.Search -> setState { copy(search = event.query) }
            is CardEvent.SelectReceipt -> selectReceipt(event.receiptId)
            is CardEvent.SelectCard -> setState { copy(selectedCardId = event.cardId) }
            is CardEvent.ToggleSelection -> setState {
                copy(
                    selection = if (event.id in selection) selection - event.id else selection + event.id,
                )
            }

            CardEvent.ClearSelection -> setState { copy(selection = emptySet()) }
            CardEvent.ClearNotice -> setState { copy(notice = null) }

            is CardEvent.Ask -> setState { copy(prompt = event.prompt) }
            is CardEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            CardEvent.DismissPrompt -> setState { copy(prompt = null) }
            CardEvent.ConfirmPrompt -> resolvePrompt()

            CardEvent.AddDraftReceipt -> setState { copy(draft = draft + DraftCardReceipt()) }
            is CardEvent.RemoveDraftReceipt -> setState {
                val remaining = draft.filterIndexed { index, _ -> index != event.index }
                copy(draft = remaining.ifEmpty { listOf(DraftCardReceipt()) })
            }

            is CardEvent.EditDraftReceipt -> setState {
                copy(
                    draft = draft.mapIndexed { index, row ->
                        if (index == event.index) event.receipt else row
                    },
                )
            }

            CardEvent.SubmitDraftReceipts -> submitDraft()

            is CardEvent.RequestCard -> requestCard(event)
            is CardEvent.MatchReceipt -> act("Receipt matched") {
                repository.matchReceipt(event.receiptId, event.transactionId)
            }

            is CardEvent.CodeReceipt -> act("Coding saved") {
                repository.codeReceipt(event.receiptId, event.code, event.description)
            }

            is CardEvent.UpdateBsCode -> act("Control code updated") {
                repository.updateBsControlCode(event.cardId, event.code)
            }

            // The effect and the host's handler shipped with this module;
            // nothing raised it, so an accountant could read a receipt's
            // figures but never look at the receipt.
            is CardEvent.ViewReceipt -> sendEffect(CardEffect.OpenAttachment(event.attachmentKey))

            is CardEvent.ImportStatement -> act("Statement imported") {
                repository.importStatement(event.attachmentKey)
            }

            is CardEvent.EditSettings -> setState { copy(settingsDraft = event.settings) }
            CardEvent.SaveSettings -> saveSettings()

            is CardEvent.EditBulkCoding -> setState { copy(bulkCoding = event.coding) }
            CardEvent.SelectAllBulk -> setState {
                val selectable = selectableBulkItems.map { it.id }.toSet()
                // Second press clears, so the control is its own undo.
                copy(selection = if (selection.containsAll(selectable)) emptySet() else selectable)
            }

            CardEvent.BulkPost -> bulkPost()

            is CardEvent.OpenImport -> openImport(event.importId)
            CardEvent.ProcessImportRows -> processRows()
            CardEvent.SubmitRowsToHolders -> submitRows()

            is CardEvent.OpenSplits -> openSplits(event.receiptId)
            CardEvent.CloseSplits -> setState { copy(splits = null) }
            is CardEvent.EditSplit -> setState {
                val open = splits ?: return@setState this
                copy(
                    splits = open.copy(
                        lines = open.lines.mapIndexed { index, line ->
                            if (index == event.index) event.line else line
                        },
                    ),
                )
            }

            CardEvent.AddSplit -> setState {
                val open = splits ?: return@setState this
                copy(
                    splits = open.copy(
                        lines = open.lines + ReceiptLine(
                            id = null,
                            description = "",
                            nominalCode = "",
                            // Starts at what is left, which is what someone
                            // adding a split almost always means.
                            net = open.remaining.coerceAtLeast(0.0),
                            taxAmount = 0.0,
                        ),
                    ),
                )
            }

            is CardEvent.RemoveSplit -> setState {
                val open = splits ?: return@setState this
                val remaining = open.lines.filterIndexed { index, _ -> index != event.index }
                copy(splits = open.copy(lines = remaining.ifEmpty { open.lines }))
            }

            CardEvent.SaveSplits -> saveSplits()
        }
    }

    // -- loading -----------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // A dispatch table; splitting it hides the mapping.
    private fun load(destination: CardDestination) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            val outcome: ZillitResult<CardUiState.() -> CardUiState> = when (destination) {
                CardDestination.Overview ->
                    repository.overview().mapState { copy(overview = it, cards = it.cards) }

                CardDestination.CardRegister ->
                    repository.cards(mineOnly = false).mapState { copy(cards = it) }

                CardDestination.MyCards, CardDestination.CardExtension ->
                    loadMyCard()

                CardDestination.CardsForApproval ->
                    repository.cards(mineOnly = false).mapState { copy(cards = it) }

                CardDestination.ImportStatement -> loadImports()

                CardDestination.BulkProcess ->
                    repository.bulkProcessable().mapState { copy(bulkItems = it) }

                CardDestination.ReceiptInbox ->
                    repository.receipts(ReceiptScope.All).mapState { copy(receipts = it) }

                CardDestination.MyTransactions -> loadMyTransactions()

                CardDestination.AllTransactions ->
                    repository.transactions().mapState { copy(transactions = it) }

                CardDestination.PendingCoding, CardDestination.CodingQueue ->
                    repository.receipts(ReceiptScope.PendingCoding).mapState { copy(receipts = it) }

                CardDestination.ApprovalQueue ->
                    repository.approvalQueue().mapState { copy(receipts = it) }

                CardDestination.ProcessQueue ->
                    repository.receipts(ReceiptScope.ProcessQueue).mapState { copy(receipts = it) }

                CardDestination.History ->
                    repository.receipts(ReceiptScope.Posted).mapState { copy(receipts = it) }

                CardDestination.TopUpQueue ->
                    repository.topUps().mapState { copy(topUps = it) }

                CardDestination.Analytics ->
                    repository.analytics(null, null).mapState { copy(analytics = it) }

                CardDestination.Alerts ->
                    repository.alerts().mapState { copy(alerts = it) }

                CardDestination.Settings ->
                    repository.settings().mapState { copy(settings = it, settingsDraft = it) }
            }

            when (outcome) {
                is ZillitResult.Success -> setState { outcome.data(this).copy(loading = false) }
                is ZillitResult.Failure -> setState { copy(loading = false, error = outcome.error) }
            }
        }
    }

    /**
     * The cardholder's own card, plus the top-ups raised against it.
     *
     * Both, because the Card Extension screen shows the balance and the
     * request history side by side, and fetching them on separate visits made
     * a just-raised request appear to have vanished.
     */
    private suspend fun loadMyCard(): ZillitResult<CardUiState.() -> CardUiState> {
        val cards = repository.cards(mineOnly = true)
        if (cards is ZillitResult.Failure) return cards
        val mine = (cards as ZillitResult.Success).data
        val card = mine.firstOrNull { it.holderId == currentState.viewer.userId } ?: mine.firstOrNull()
        val topUps = card?.let { repository.cardTopUps(it.id).getOrNull() }.orEmpty()
        return ZillitResult.Success { copy(cards = mine, topUps = topUps) }
    }

    /**
     * My Transactions needs the card as well as the receipts.
     *
     * The card carries the limit and the committed total, and without those
     * the upload gate cannot be evaluated — so the screen would offer an
     * upload it is about to refuse.
     */
    private suspend fun loadMyTransactions(): ZillitResult<CardUiState.() -> CardUiState> {
        val receipts = repository.receipts(ReceiptScope.Mine)
        if (receipts is ZillitResult.Failure) return receipts
        val mine = (receipts as ZillitResult.Success).data
        val cards = repository.cards(mineOnly = true).getOrNull().orEmpty()
        return ZillitResult.Success { copy(receipts = mine, cards = cards) }
    }

    /** Selecting a receipt also fetches what it might match, for the pane. */
    private fun selectReceipt(receiptId: String?) {
        setState { copy(selectedReceiptId = receiptId, matchCandidates = emptyList()) }
        val receipt = currentState.receipts.firstOrNull { it.id == receiptId } ?: return
        if (receipt.transactionId != null) return
        launch {
            repository.matchCandidates(receipt.id).getOrNull()?.let { candidates ->
                // Guarded: the user may have moved on while this was in flight,
                // and candidates for a receipt nobody is looking at are noise.
                if (currentState.selectedReceiptId == receipt.id) {
                    setState { copy(matchCandidates = candidates) }
                }
            }
        }
    }

    // -- actions -----------------------------------------------------------

    /**
     * Submits the drafted receipts, refusing above the card's headroom.
     *
     * This gate blocks, unlike the cash float's: a card limit is an
     * authorisation the production granted, and exceeding it is an overspend
     * rather than something to route to a reimbursement.
     */
    private fun submitDraft() {
        val state = currentState
        val invalid = state.draft.firstNotNullOfOrNull { receipt ->
            when {
                receipt.description.isBlank() -> "Each receipt needs a description."
                (receipt.amount.trim().toDoubleOrNull() ?: 0.0) <= 0 -> "Each receipt needs an amount."
                receipt.attachmentKey.isNullOrBlank() -> "Each receipt needs its image or PDF attached."
                else -> null
            }
        }
        if (invalid != null) {
            sendEffect(CardEffect.Failed(invalid))
            return
        }

        if (state.headroom.batchExceeds(state.draftTotal)) {
            sendEffect(
                CardEffect.Failed(
                    "This batch is over the card's remaining limit. " +
                        "Ask for a top-up before uploading it.",
                ),
            )
            return
        }

        act("Receipts uploaded", clearDraft = true) {
            repository.submitReceipts(state.myCard?.id, state.draft)
        }
    }

    private fun requestCard(event: CardEvent.RequestCard) {
        val limit = event.limit.trim().toDoubleOrNull()
        if (limit == null || limit <= 0) {
            sendEffect(CardEffect.Failed("Enter the limit the card should carry."))
            return
        }
        val viewer = currentState.viewer
        // The one-card rule, enforced here as well as on the server: a refusal
        // after the form is filled in teaches people to ignore the rule rather
        // than ask for the existing card to be closed.
        val blocking = currentState.cards.firstOrNull {
            it.holderId == viewer.userId &&
                com.zillit.desktop.feature.cardexpenses.domain.CardRules.blocksNewRequest(it)
        }
        if (blocking != null) {
            sendEffect(
                CardEffect.Failed(
                    "You already hold a ${blocking.status.label.lowercase()} card. " +
                        "It has to be closed or suspended before a new one can be issued.",
                ),
            )
            return
        }

        act("Card requested") {
            repository.requestCard(
                NewCardRequest(
                    holderId = viewer.userId,
                    limit = limit,
                    currency = currentState.myCard?.currency,
                    type = event.type,
                    departmentId = null,
                    companyId = null,
                    providerId = viewer.metadata.cardProviders.firstOrNull()?.id,
                    bsControlCode = null,
                    reason = event.reason.takeIf { it.isNotBlank() },
                ),
            )
        }
    }

    /**
     * The imports list, and the open statement's rows alongside it.
     *
     * Both together: the point of the screen is reviewing one statement's
     * rows, and making that a second navigation would put a click between an
     * accountant and the work.
     */
    private suspend fun loadImports(): ZillitResult<CardUiState.() -> CardUiState> {
        val imports = repository.imports()
        if (imports is ZillitResult.Failure) return imports
        val loaded = (imports as ZillitResult.Success).data
        val open = currentState.openImportId ?: loaded.firstOrNull()?.id
        val rows = open?.let { repository.importRows(it).getOrNull() }.orEmpty()
        return ZillitResult.Success { copy(imports = loaded, openImportId = open, importRows = rows) }
    }

    private fun openImport(importId: String?) {
        setState { copy(openImportId = importId, importRows = emptyList(), selection = emptySet()) }
        if (importId == null) return
        launch {
            repository.importRows(importId).getOrNull()?.let { rows ->
                if (currentState.openImportId == importId) setState { copy(importRows = rows) }
            }
        }
    }

    private fun processRows() {
        val importId = currentState.openImportId ?: return
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(CardEffect.Failed("Tick the rows to accept first."))
            return
        }
        act("${ids.size} row(s) accepted") { repository.processImport(importId, ids) }
        setState { copy(selection = emptySet()) }
    }

    /**
     * Sends the ticked rows to their cardholders.
     *
     * A row with nobody on it has nowhere to go, so those are dropped from the
     * send and named rather than silently included.
     */
    private fun submitRows() {
        val ticked = currentState.importRows.filter { it.id in currentState.selection }
        val sendable = ticked.filter { it.canSubmit }
        if (sendable.isEmpty()) {
            sendEffect(
                CardEffect.Failed(
                    "None of the ticked rows has a cardholder on it, so there is nobody to ask.",
                ),
            )
            return
        }
        val skipped = ticked.size - sendable.size
        val message = "${sendable.size} row(s) sent" +
            if (skipped > 0) " · $skipped skipped with no holder" else ""
        act(message) { repository.submitRowsToHolders(sendable.map { it.id }) }
        setState { copy(selection = emptySet()) }
    }

    private fun bulkPost() {
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(CardEffect.Failed("Nothing is selected."))
            return
        }
        val coding = currentState.bulkCoding
        launch {
            setState { copy(busy = true) }
            when (val result = repository.bulkProcess(ids, coding)) {
                is ZillitResult.Success -> {
                    val outcome = result.data
                    setState {
                        copy(
                            busy = false,
                            selection = emptySet(),
                            bulkCoding = BulkCoding(),
                            // Partial failure is reported as such: "posted 38"
                            // when two did not go is how a discrepancy is found
                            // a week later by someone else.
                            notice = "Posted ${outcome.succeeded}" +
                                if (outcome.failed > 0) " · ${outcome.failed} failed" else "",
                        )
                    }
                    load(currentState.destination)
                }

                is ZillitResult.Failure -> {
                    setState { copy(busy = false) }
                    sendEffect(CardEffect.Failed(result.error.localised()))
                }
            }
        }
    }

    /** Opens a receipt's splits, seeded from what it already carries. */
    private fun openSplits(receiptId: String) {
        val receipt = currentState.receipts.firstOrNull { it.id == receiptId } ?: return
        setState {
            copy(
                splits = SplitDraft(
                    receiptId = receiptId,
                    receiptGross = receipt.amount,
                    currency = receipt.currency,
                    lines = listOf(
                        ReceiptLine(
                            id = null,
                            description = receipt.description,
                            nominalCode = receipt.nominalCode.orEmpty(),
                            net = receipt.amount,
                            taxAmount = 0.0,
                            episode = receipt.episode,
                        ),
                    ),
                ),
            )
        }
    }

    private fun saveSplits() {
        val draft = currentState.splits ?: return
        if (!draft.balances) {
            sendEffect(
                CardEffect.Failed(
                    "The splits come to a different amount from the receipt. " +
                        "Adjust them so they add up before saving.",
                ),
            )
            return
        }
        if (draft.lines.any { it.nominalCode.isBlank() }) {
            sendEffect(CardEffect.Failed("Every split needs a nominal code."))
            return
        }
        act("Splits saved") { repository.saveReceiptLines(draft.receiptId, draft.lines) }
        setState { copy(splits = null) }
    }

    private fun saveSettings() {
        val draft = currentState.settingsDraft ?: return
        launch {
            setState { copy(busy = true) }
            when (val saved = repository.updateSettings(draft)) {
                is ZillitResult.Success -> setState {
                    copy(busy = false, settings = saved.data, settingsDraft = saved.data, notice = "Settings saved")
                }

                is ZillitResult.Failure -> {
                    setState { copy(busy = false) }
                    sendEffect(CardEffect.Failed(saved.error.localised()))
                }
            }
        }
    }

    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }
        when (prompt) {
            is CardPrompt.Confirm -> resolveConfirm(prompt)
            is CardPrompt.WithReason -> resolveReason(prompt)
            is CardPrompt.WithAmount -> resolveAmount(prompt)
            is CardPrompt.WithCardNumber -> {
                val digits = prompt.number.filter(Char::isDigit)
                if (digits.length < MIN_CARD_DIGITS) {
                    sendEffect(CardEffect.Failed("Enter the full card number."))
                    setState { copy(prompt = prompt) }
                    return
                }
                act("Physical card assigned") {
                    repository.assignPhysicalCard(prompt.targetId, digits)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per confirmable action.
    private fun resolveConfirm(prompt: CardPrompt.Confirm) {
        val id = prompt.targetId
        when (prompt.action) {
            CardConfirmAction.ApproveCard -> act("Card approved") { repository.approveCard(id, null) }
            CardConfirmAction.OverrideCard -> act("Card overridden") { repository.overrideCard(id) }
            CardConfirmAction.ActivateCard -> act("Card activated") { repository.activateCard(id, null) }
            CardConfirmAction.SuspendCard -> act("Card suspended") { repository.suspendCard(id) }
            CardConfirmAction.ReactivateCard -> act("Card reactivated") { repository.reactivateCard(id) }
            CardConfirmAction.ApproveReceipt -> act("Receipt approved") { repository.approveReceipt(id, null) }
            CardConfirmAction.OverrideReceipt -> act("Receipt overridden") { repository.overrideReceipt(id) }
            CardConfirmAction.PostReceipt -> postReceipt(id)
            CardConfirmAction.SubmitReceiptForApproval ->
                act("Sent for approval") { repository.submitReceiptForApproval(id) }

            CardConfirmAction.ConfirmMatch -> act("Match confirmed") { repository.confirmReceiptMatch(id) }
            CardConfirmAction.UnmatchReceipt -> act("Match removed") { repository.unmatchReceipt(id) }
            CardConfirmAction.FlagPersonal -> act("Flagged personal") { repository.flagReceiptPersonal(id) }
            CardConfirmAction.DismissDuplicate -> act("Duplicate dismissed") { repository.dismissDuplicate(id) }
            CardConfirmAction.DismissPersonal -> act("Personal flag cleared") { repository.dismissPersonal(id) }
            CardConfirmAction.DeleteReceipt -> act("Receipt deleted") { repository.deleteReceipt(id) }
            CardConfirmAction.CompleteTopUp -> act("Top-up completed") { repository.completeTopUp(id) }
            CardConfirmAction.SkipTopUp -> act("Top-up skipped") { repository.skipTopUp(id) }
            CardConfirmAction.DismissAlert -> act("Alert dismissed") { repository.dismissAlert(id) }
            CardConfirmAction.InvestigateAlert -> act("Marked for investigation") { repository.investigateAlert(id) }
            CardConfirmAction.BulkApprove -> bulk(BulkAction.Approve)
            CardConfirmAction.BulkReject -> bulk(BulkAction.Reject)
            CardConfirmAction.PostTransaction -> act("Transaction posted") { repository.postTransaction(id) }
            CardConfirmAction.FlagTransactionPersonal ->
                act("Flagged personal") { repository.flagTransactionPersonal(id) }
        }
    }

    private fun resolveReason(prompt: CardPrompt.WithReason) {
        val reason = prompt.reason.trim()
        if (reason.isEmpty()) {
            sendEffect(CardEffect.Failed("A reason is required."))
            setState { copy(prompt = prompt) }
            return
        }
        when (prompt.action) {
            CardReasonAction.RejectCard -> act("Card rejected") { repository.rejectCard(prompt.targetId, reason) }
            CardReasonAction.RejectReceipt ->
                act("Receipt rejected") { repository.rejectReceipt(prompt.targetId, reason) }

            CardReasonAction.QueryTransaction ->
                act("Query sent") { repository.queryTransaction(prompt.targetId, reason) }

            CardReasonAction.RejectTransaction ->
                act("Transaction rejected") { repository.rejectTransaction(prompt.targetId, reason) }

            CardReasonAction.ResolveAlert -> act("Alert resolved") { repository.resolveAlert(prompt.targetId, reason) }
        }
    }

    private fun resolveAmount(prompt: CardPrompt.WithAmount) {
        val amount = prompt.amount.trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            sendEffect(CardEffect.Failed("Enter an amount greater than zero."))
            setState { copy(prompt = prompt) }
            return
        }
        when (prompt.action) {
            CardAmountAction.RequestTopUp -> act("Top-up requested") {
                repository.requestTopUp(prompt.targetId, amount, prompt.note.takeIf(String::isNotBlank))
            }

            CardAmountAction.PartialTopUp -> act("Top-up recorded") {
                repository.partialTopUp(prompt.targetId, amount)
            }
        }
    }

    private fun postReceipt(receiptId: String) {
        val receipt = currentState.receipts.firstOrNull { it.id == receiptId }
        if (receipt != null && !currentState.viewer.canPost(receipt.amount)) {
            sendEffect(
                CardEffect.Failed("This is above your posting limit. Pass it to a senior accountant."),
            )
            return
        }
        act("Receipt posted") { repository.postReceipt(receiptId) }
    }

    private fun bulk(action: BulkAction) {
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(CardEffect.Failed("Nothing is selected."))
            return
        }
        act("${ids.size} receipt(s) ${if (action == BulkAction.Approve) "approved" else "rejected"}") {
            repository.bulkApproval(action, ids)
        }
        setState { copy(selection = emptySet()) }
    }

    private fun act(
        success: String,
        clearDraft: Boolean = false,
        block: suspend () -> ZillitResult<Unit>,
    ) = launch {
        setState { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                setState {
                    copy(
                        busy = false,
                        notice = success,
                        draft = if (clearDraft) listOf(DraftCardReceipt()) else draft,
                    )
                }
                load(currentState.destination)
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(CardEffect.Failed(result.error.localised()))
            }
        }
    }

    private fun <T> ZillitResult<T>.mapState(
        transform: CardUiState.(T) -> CardUiState,
    ): ZillitResult<CardUiState.() -> CardUiState> = when (this) {
        is ZillitResult.Success -> ZillitResult.Success({ transform(data) })
        is ZillitResult.Failure -> this
    }

    private companion object {
        const val MIN_CARD_DIGITS = 12
    }
}
