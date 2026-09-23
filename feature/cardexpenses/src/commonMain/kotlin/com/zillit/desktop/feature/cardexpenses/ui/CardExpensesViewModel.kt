package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.data.cardRefreshes
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachmentUploader
import com.zillit.desktop.feature.cardexpenses.domain.CardBank
import com.zillit.desktop.feature.cardexpenses.domain.CardFiles
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.TransactionFilters
import kotlinx.coroutines.Job

/**
 * The card tool's one view model.
 *
 * Same shape as the cash module's, and for the same reasons — see
 * `CashExpensesViewModel`, whose account of per-destination loading and
 * reload-after-mutation applies here unchanged.
 *
 * Host seams rather than repository calls where the thing does not belong to
 * this service: [people] is the open production's crew, [uploader] the
 * machine's file picker and the project's object store, [files] where an
 * export lands. Each defaults to doing nothing, which degrades to a form that
 * cannot pick a holder, a file or a destination rather than to a crash.
 */
@Suppress("TooManyFunctions") // One handler per user action; the alternative is a 300-line when.
class CardExpensesViewModel(
    private val repository: CardRepository,
    /**
     * Live changes from other clients; null keeps the tool load-once.
     *
     * Ahead of [viewer] deliberately: `viewer` is the trailing lambda at call
     * sites, and a parameter added after it would capture that lambda instead.
     */
    private val events: SocketEventBus? = null,
    /** The crew, for the holder picker. Read at start, not at construction. */
    private val people: suspend () -> List<CardPerson> = { emptyList() },
    /** Picks a file and stores it; null leaves every attach button disabled. */
    private val uploader: CardAttachmentUploader? = null,
    /** The ledger's rows for this tool per page key, and the page read. */
    private val badges: TabBadgeSource = TabBadgeSource.None,
    /** Saves and opens an export; null leaves the exports refusing with a reason. */
    files: CardFiles? = null,
    /** Now, in epoch millis — the process editor's default ledger date and the export stamp. */
    private val today: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** The production's bank accounts, for fund requests; they belong to the hub, not this service. */
    banks: suspend () -> List<CardBank> = { emptyList() },
    /** Read at start, not at construction — see the cash module's equivalent. */
    private val viewer: () -> CardViewer,
) : ZillitViewModel<CardUiState, CardEvent, CardEffect>(
    CardUiState(
        viewer = viewer(),
        destination = CardDestination.landing(viewer()),
        canAttachFiles = uploader != null,
    ),
) {

    private var loadJob: Job? = null
    private var started = false
    private var listening = false
    private var watchingBadges = false

    /** Whether this production's rights have been read once; see [pendingPage]. */
    private var resolvedOnce = false

    /** A page asked for before the rights that allow it had arrived. */
    private var pendingPage: CardDestination? = null

    /** The page on screen is its read — every key it is filed under that has rows. */
    private fun readPage(destination: CardDestination) {
        destination.badgeKeys.filter { (currentState.unread[it] ?: 0) > 0 }.forEach(badges::read)
    }

    /** The register's own actions; see [CardRegisterActions]. */
    private val register = CardRegisterActions(this)

    /** Saving the production's configuration; see [CardSettingsActions]. */
    private val configuration = CardSettingsActions(this)

    /** One receipt: choosing, attaching, uploading, coding. */
    private val receipts = CardReceiptActions(this, uploader)

    /** Importing a statement and reviewing what came off it. */
    private val statements = CardStatementActions(this, uploader)

    /** Answered confirmations and the bulk bars; see [CardPromptActions]. */
    private val prompts = CardPromptActions(this, deleteCard = register::delete)

    /** The accountant's process editor; see [CardProcessActions]. */
    private val processing = CardProcessActions(this, today)

    /** Activation and the exports; see [CardLifecycleActions]. */
    private val lifecycle = CardLifecycleActions(this, files, today)

    /** Query threads and fund requests; see [CardQueryFundActions]. */
    private val extras = CardQueryFundActions(this, banks)

    /** What each page reads; see [CardPageLoader]. */
    private val loader = CardPageLoader(this, statements::load)

    // -- the seams the collaborators work through --------------------------
    //
    // `setState`, `launch` and `sendEffect` are protected on the base class,
    // so a collaborator cannot reach them. These are the whole surface they
    // need, named for what they do rather than for the machinery.

    internal val repo: CardRepository get() = repository

    internal val current: CardUiState get() = currentState

    internal fun update(reducer: CardUiState.() -> CardUiState) = setState(reducer)

    internal fun fail(message: String) = sendEffect(CardEffect.Failed(message))

    internal fun run(block: suspend () -> Unit) = launch { block() }

    /** Re-reads the page on screen. */
    internal fun reload() = load(currentState.destination)

    internal fun act(success: String, block: suspend () -> ZillitResult<Unit>) =
        act(success, clearDraft = false, block = block)

    /**
     * Uploads the drafted receipts and empties the form on success.
     *
     * Here rather than in [CardReceiptActions] only because clearing the draft
     * is part of the same state write as the notice; the gate that decides
     * whether to call it is there, with the rest of the receipt's rules.
     */
    internal fun submitDraftReceipts(state: CardUiState) =
        act(str(S.desktop_card_receipts_uploaded), clearDraft = true) {
            repository.submitReceipts(state.myCard, state.draft)
        }

    /** Resolves who this is, then opens their landing page. Idempotent. */
    fun start() {
        if (started) return
        started = true
        startInternal()
        if (!watchingBadges) {
            watchingBadges = true
            launch {
                badges.counts.collect { counts ->
                    setState { copy(unread = counts) }
                    // A row landing on the open page is read as it lands.
                    readPage(currentState.destination)
                }
            }
        }

        // Somebody else's approval, coding or import. Only the page on screen
        // reloads. `listening` outlives `started`, which onProjectChanged
        // resets, so a production switch does not stack a second collector.
        val bus = events
        if (bus != null && !listening) {
            listening = true
            launch {
                cardRefreshes(bus).collect { currentState.destination.let(::load) }
            }
        }
    }

    /** Re-reads the viewer when the open production changes. */
    fun onProjectChanged() {
        started = false
        resolvedOnce = false
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on. Only the
     * identity changes here — the module's own metadata and the door the tool
     * was entered by are this view model's, and are kept.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState { copy(viewer = resolved.copy(metadata = viewer.metadata, enteredAsTool = viewer.enteredAsTool)) }
    }

    private fun startInternal() = launch {
        val identity = viewer()
        // Metadata and settings are settled independently: a failing /settings
        // must not blank the approval rights, and vice versa. The web learnt
        // this when a settings outage silently demoted every approver.
        val metadata = repository.metadata().getOrNull()
        setState {
            // The door is this view model's, not the supplier's: the host
            // resolves who the viewer is and knows nothing of which window
            // asked. A deep link that arrived before the rights did is
            // honoured now if they allow it; the page on screen stays if the
            // viewer may see it.
            val settled = identity.copy(
                metadata = metadata ?: identity.metadata,
                enteredAsTool = viewer.enteredAsTool,
            )
            val page = pendingPage?.takeIf { it.visibleTo(settled) }
                ?: destination.takeIf { it.visibleTo(settled) }
                ?: CardDestination.landing(settled)
            copy(viewer = settled, destination = page)
        }
        pendingPage = null
        resolvedOnce = true
        // Providers only. `coding_required` belongs to /metadata — it is a
        // fact about this viewer's department, not a project-wide switch —
        // and copying it off /settings made every coordinator's coding queue
        // appear or vanish with a setting nobody had touched.
        repository.settings().getOrNull()?.let { loaded ->
            setState {
                copy(
                    settings = loaded,
                    settingsDraft = settingsDraft ?: loaded,
                    viewer = viewer.copy(metadata = viewer.metadata.copy(cardProviders = loaded.providers)),
                )
            }
        }
        // Read here rather than at construction: the crew belongs to the open
        // production, which does not exist when this is built. Resolved
        // outside the reducer — `setState` takes a plain lambda.
        val crew = people()
        setState { copy(people = crew) }
        load(currentState.destination)
    }

    override fun onEvent(event: CardEvent) {
        if (processing.handle(event) || lifecycle.handle(event) || extras.handle(event)) return
        route(event)
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    private fun route(event: CardEvent) {
        when (event) {
            is CardEvent.Enter -> enter(event.asTool)

            CardEvent.Refresh -> load(currentState.destination)

            is CardEvent.Open -> {
                // A page this viewer may not see is not opened — a deep link or
                // a stale event from the other door reaches here as readily as
                // a sidebar click, and the sidebar is not the gate.
                if (!event.destination.visibleTo(currentState.viewer)) {
                    // Rights not in yet: the deep link waits for them.
                    if (!resolvedOnce) pendingPage = event.destination
                    return
                }
                openPage(event.destination)
            }

            is CardEvent.Search -> setState { copy(search = event.query) }
            is CardEvent.FilterStatus -> setState { copy(statusFilter = event.status) }
            is CardEvent.FilterInboxSection -> setState { copy(inboxSection = event.section) }
            is CardEvent.SetTransactionFilters -> {
                setState { copy(transactionFilters = event.filters, selection = emptySet()) }
                if (currentState.destination == CardDestination.AllTransactions) reload()
            }
            is CardEvent.SelectReceipt -> receipts.select(event.receiptId)
            is CardEvent.SelectCard -> register.select(event.cardId)
            is CardEvent.SelectTransaction -> setState { copy(selectedTransactionId = event.transactionId) }
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
            CardEvent.ConfirmPrompt -> prompts.resolve()

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

            is CardEvent.AttachDraftReceipt -> receipts.attach(event.index)
            is CardEvent.ClearDraftAttachment -> receipts.clearAttachment(event.index)

            CardEvent.SubmitDraftReceipts -> receipts.submitDraft()

            is CardEvent.OpenNewCard -> register.compose(event.holderId)
            is CardEvent.EditNewCard -> setState { copy(newCard = event.draft) }
            CardEvent.CloseNewCard -> setState { copy(newCard = null) }
            CardEvent.SubmitNewCard -> register.submit()

            is CardEvent.OpenCardEdit -> register.openEdit(event.cardId)
            is CardEvent.EditCardDraft -> setState { copy(cardEdit = event.draft) }
            CardEvent.CloseCardEdit -> setState { copy(cardEdit = null) }
            CardEvent.SaveCardEdit -> register.saveEdit()

            is CardEvent.EditBsCode -> setState {
                copy(cardDetail = cardDetail?.copy(bsControlCode = event.code))
            }

            is CardEvent.SaveBsCode -> register.saveBsCode(event.cardId)

            is CardEvent.MatchReceipt -> act(str(S.desktop_card_receipt_matched)) {
                repository.matchReceipt(event.receiptId, event.transactionId)
            }

            is CardEvent.EditCoding -> setState { copy(coding = event.draft) }
            is CardEvent.OpenTopUpHistory -> openTopUpHistory(event.topUpId)
            CardEvent.SaveCodingDraft -> receipts.commitCoding(
                // A holder coding their own receipt has one button, and it
                // advances the receipt; a coordinator's identical-looking
                // "Save draft" deliberately does not.
                if (currentState.destination == CardDestination.MyTransactions) {
                    CodingCommit.Own
                } else {
                    CodingCommit.Draft
                },
            )
            CardEvent.SubmitCoding -> receipts.commitCoding(CodingCommit.Submit)
            CardEvent.ApproveAndSubmitCoding -> receipts.commitCoding(CodingCommit.ApproveAndSubmit)

            // The effect and the host's handler shipped with this module;
            // nothing raised it, so an accountant could read a receipt's
            // figures but never look at the receipt.
            is CardEvent.ViewReceipt -> sendEffect(CardEffect.OpenAttachment(event.attachmentKey))

            CardEvent.ImportStatement -> statements.import()
            is CardEvent.EditStatementCurrency -> setState { copy(statementCurrency = event.currency) }

            is CardEvent.EditSettings -> setState { copy(settingsDraft = event.settings) }
            is CardEvent.SaveSettings -> configuration.save(event.section)
            CardEvent.DiscardSettings -> setState { copy(settingsDraft = settings) }
            is CardEvent.SetAnalyticsRange -> {
                setState { copy(analyticsRange = event.range) }
                load(CardDestination.Analytics)
            }

            is CardEvent.EditBulkCoding -> setState { copy(bulkCoding = event.coding) }
            CardEvent.SelectAllBulk -> setState {
                val selectable = selectableBulkItems.map { it.id }.toSet()
                // Second press clears, so the control is its own undo.
                copy(selection = if (selection.containsAll(selectable)) emptySet() else selectable)
            }

            CardEvent.BulkPost -> prompts.bulkPost()

            is CardEvent.OpenImport -> statements.open(event.importId)
            CardEvent.ProcessImportRows -> statements.processRows()
            CardEvent.SubmitRowsToHolders -> statements.submitRows()

            // Owned by the collaborators routed in onEvent.
            else -> Unit
        }
    }

    /**
     * Moves the view model to the door the next events come through.
     *
     * An accountant who opened the tile is a crew member here, and the same
     * accountant inside the hub is the console: every rights check reads
     * `viewer.isAccountant`, which carries the door, so switching it is what
     * gates the handlers the same way as the layout. The page follows only
     * when the new viewer may not see it; an editor or form open for the other
     * door is closed rather than carried across.
     */
    private fun enter(asTool: Boolean) {
        val viewer = currentState.viewer
        if (viewer.enteredAsTool == asTool) return
        val moved = viewer.copy(enteredAsTool = asTool)
        val page = currentState.destination
        setState {
            copy(
                viewer = moved,
                process = null,
                activation = null,
                prompt = null,
                cardEdit = null,
                funds = null,
                query = null,
            )
        }
        if (!page.visibleTo(moved)) {
            val landing = CardDestination.landing(moved)
            if (started) openPage(landing) else setState { copy(destination = landing) }
        }
    }

    private fun openPage(destination: CardDestination) {
        setState {
            copy(
                destination = destination,
                search = "",
                statusFilter = ALL_STATUSES,
                inboxSection = null,
                selection = emptySet(),
                selectedReceiptId = null,
                selectedCardId = null,
                selectedTransactionId = null,
                openTopUpId = null,
                cardDetail = null,
                coding = null,
                receiptHistory = emptyList(),
                process = null,
                processTab = ProcessTab.Processing,
                // All Transactions opens on the last month of spend, as the
                // web's does; the filters say so and one press clears them.
                transactionFilters = if (destination == CardDestination.AllTransactions) {
                    TransactionFilters.lastMonth(today())
                } else {
                    transactionFilters
                },
                error = null,
            )
        }
        load(destination)
        readPage(destination)
    }

    // -- loading -----------------------------------------------------------

    private fun load(destination: CardDestination) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (val outcome = loader.fetch(destination)) {
                is ZillitResult.Success -> setState { outcome.data(this).copy(loading = false) }
                is ZillitResult.Failure -> setState { copy(loading = false, error = outcome.error) }
            }
        }
    }

    /**
     * Opens one top-up's trail, and closes the one that was open.
     *
     * A second press on the same row closes it, so the control is its own
     * undo — the funding queue is a list of decisions and a row stuck open is
     * a row hiding the next one.
     */
    private fun openTopUpHistory(topUpId: String?) {
        val next = topUpId.takeIf { it != currentState.openTopUpId }
        setState { copy(openTopUpId = next, topUpHistory = emptyList()) }
        if (next == null) return
        launch {
            repository.topUpHistory(next).getOrNull()?.let { trail ->
                if (currentState.openTopUpId == next) setState { copy(topUpHistory = trail) }
            }
        }
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
}
