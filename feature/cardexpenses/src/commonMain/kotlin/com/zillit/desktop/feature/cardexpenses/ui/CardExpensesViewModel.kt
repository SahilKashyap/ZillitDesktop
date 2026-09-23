package com.zillit.desktop.feature.cardexpenses.ui

import com.zillit.desktop.core.badges.TabBadgeSource
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.socket.SocketEventBus
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cardexpenses.data.cardRefreshes
import com.zillit.desktop.feature.cardexpenses.domain.BulkAction
import com.zillit.desktop.feature.cardexpenses.domain.BulkCoding
import com.zillit.desktop.feature.cardexpenses.domain.CardAttachmentUploader
import com.zillit.desktop.feature.cardexpenses.domain.CardPerson
import com.zillit.desktop.feature.cardexpenses.domain.CardRepository
import com.zillit.desktop.feature.cardexpenses.domain.CardRules
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.domain.DraftCardReceipt
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptLine
import com.zillit.desktop.feature.cardexpenses.domain.ReceiptScope
import kotlinx.coroutines.Job

/**
 * The card tool's one view model.
 *
 * Same shape as the cash module's, and for the same reasons — see
 * `CashExpensesViewModel`, whose account of per-destination loading and
 * reload-after-mutation applies here unchanged.
 *
 * Two host seams rather than repository calls, because neither belongs to this
 * service: [people] is the open production's crew, and [uploader] is the
 * machine's file picker and the project's object store. Both default to doing
 * nothing, which degrades to a form that cannot pick a holder or a file rather
 * than to a crash.
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

    // -- the seams the two collaborators work through ----------------------
    //
    // `setState`, `launch` and `sendEffect` are protected on the base class,
    // so a collaborator cannot reach them. These four are the whole surface
    // they need, named for what they do rather than for the machinery.

    internal val repo: CardRepository get() = repository

    internal val current: CardUiState get() = currentState

    internal fun update(reducer: CardUiState.() -> CardUiState) = setState(reducer)

    internal fun fail(message: String) = sendEffect(CardEffect.Failed(message))

    internal fun run(block: suspend () -> Unit) = launch { block() }

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
     * viewer changes here; the open page and its data are already right.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState { copy(viewer = resolved) }
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

    @Suppress("LongMethod", "CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: CardEvent) {
        when (event) {
            CardEvent.Refresh -> load(currentState.destination)

            is CardEvent.Open -> {
                setState {
                    copy(
                        destination = event.destination,
                        search = "",
                        statusFilter = ALL_STATUSES,
                        selection = emptySet(),
                        selectedReceiptId = null,
                        selectedCardId = null,
                        selectedTransactionId = null,
                        openTopUpId = null,
                        cardDetail = null,
                        coding = null,
                        receiptHistory = emptyList(),
                        error = null,
                    )
                }
                load(event.destination)
                readPage(event.destination)
            }

            is CardEvent.Search -> setState { copy(search = event.query) }
            is CardEvent.FilterStatus -> setState { copy(statusFilter = event.status) }
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

            CardEvent.BulkPost -> bulkPost()

            is CardEvent.OpenImport -> statements.open(event.importId)
            CardEvent.ProcessImportRows -> statements.processRows()
            CardEvent.SubmitRowsToHolders -> statements.submitRows()

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
                CardDestination.Overview -> loadOverview()

                CardDestination.CardRegister ->
                    repository.cards(mineOnly = false).mapState { copy(cards = it) }

                CardDestination.MyCards, CardDestination.CardExtension ->
                    loadMyCard()

                CardDestination.CardsForApproval ->
                    repository.cards(mineOnly = false).mapState { copy(cards = it) }

                CardDestination.ImportStatement -> statements.load()

                CardDestination.BulkProcess ->
                    repository.bulkProcessable().mapState { copy(bulkItems = it) }

                CardDestination.ReceiptInbox -> loadInbox()

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

                CardDestination.Analytics -> {
                    val range = currentState.analyticsRange
                    repository.analytics(range.fromOrNull, range.toOrNull)
                        .mapState { copy(analytics = it) }
                }

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
     * The dashboard's figures, with the register behind its card rows.
     *
     * `/overview` projects each card to a thinner field set: no status, no
     * currency, no holder name, and `last4` where the register says
     * `last_four`. Rendered as-is that gave a dashboard of raw ObjectIds,
     * every status reading "Unknown", and a limits total that added yen to
     * pounds. The web hit this as ZL-20582 and reads the amounts and the code
     * from `/cards` keyed by id, which is what this does — the overview row
     * supplies *which* cards, and the register supplies what they are.
     *
     * A failed register read is not fatal: the counts and totals are the point
     * of the page, and thin card rows are better than no page.
     */
    private suspend fun loadOverview(): ZillitResult<CardUiState.() -> CardUiState> {
        val overview = repository.overview()
        if (overview is ZillitResult.Failure) return overview
        val dashboard = (overview as ZillitResult.Success).data
        val register = repository.cards(mineOnly = false).getOrNull().orEmpty().associateBy { it.id }
        val merged = dashboard.cards.map { row -> register[row.id] ?: row }
        return ZillitResult.Success { copy(overview = dashboard.copy(cards = merged), cards = merged) }
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

    /**
     * The inbox needs the statement side as well as the receipts.
     *
     * Its work is reconciliation, and the transactions are what a receipt is
     * being reconciled *against* — the screen names the statement line a
     * receipt is flagged against, and offers the accountant's own
     * flag-personal on that line rather than on the receipt.
     */
    private suspend fun loadInbox(): ZillitResult<CardUiState.() -> CardUiState> {
        val receipts = repository.receipts(ReceiptScope.All)
        if (receipts is ZillitResult.Failure) return receipts
        val rows = (receipts as ZillitResult.Success).data
        val imports = repository.imports().getOrNull().orEmpty()
        val transactions = repository.transactions().getOrNull().orEmpty()
        return ZillitResult.Success { copy(receipts = rows, imports = imports, transactions = transactions) }
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

    private fun bulkPost() {
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(CardEffect.Failed(str(S.desktop_nothing_is_selected)))
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
                            notice = if (outcome.failed > 0) {
                                str(S.desktop_card_posted_count_failed, outcome.succeeded, outcome.failed)
                            } else {
                                str(S.desktop_card_posted_count, outcome.succeeded)
                            },
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
                    str(S.desktop_card_splits_do_not_add_up),
                ),
            )
            return
        }
        if (draft.lines.any { it.nominalCode.isBlank() }) {
            sendEffect(CardEffect.Failed(str(S.desktop_card_split_needs_nominal)))
            return
        }
        act(str(S.desktop_card_splits_saved)) { repository.saveReceiptLines(draft.receiptId, draft.lines) }
        setState { copy(splits = null) }
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
                    sendEffect(CardEffect.Failed(str(S.desktop_card_enter_full_number)))
                    setState { copy(prompt = prompt) }
                    return
                }
                act(str(S.ah_physical_card_assigned_toast)) {
                    repository.assignPhysicalCard(prompt.targetId, digits)
                }
            }
        }
    }

    /**
     * The right each confirmable action needs, independent of the screen.
     *
     * This dispatch went straight to the repository, so a prompt arriving here
     * approved or posted money with no check of its own — the screens gated it
     * and the handler trusted them.
     *
     * The card lifecycle, posting and deleting are an accountant's; approving
     * a receipt is an approver's; approving a card is either. The claimant's
     * own steps — submit, match, flag, dismiss, top-up — are left alone: a
     * person acting on their own receipt is not something any screen refuses.
     */
    private fun CardConfirmAction.permitted(viewer: CardViewer): Boolean = when (this) {
        CardConfirmAction.ApproveCard -> viewer.isApprover || viewer.isAccountant
        CardConfirmAction.ApproveReceipt -> viewer.isApprover
        CardConfirmAction.ActivateCard,
        CardConfirmAction.SuspendCard,
        CardConfirmAction.ReactivateCard,
        CardConfirmAction.PostReceipt,
        CardConfirmAction.PostTransaction,
        CardConfirmAction.FlagTransactionPersonal,
        CardConfirmAction.DeleteTransaction,
        CardConfirmAction.BulkDeleteTransactions,
        CardConfirmAction.RerunMatching,
        -> viewer.isAccountant

        // Overriding skips other people's approvals, so it needs the grant the
        // module's own metadata carries — being an accountant is not enough.
        CardConfirmAction.OverrideCard, CardConfirmAction.OverrideReceipt ->
            viewer.isAccountant && viewer.metadata.canOverride

        else -> true
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per confirmable action.
    private fun resolveConfirm(prompt: CardPrompt.Confirm) {
        val id = prompt.targetId
        if (!prompt.action.permitted(currentState.viewer)) {
            sendEffect(CardEffect.Failed(str(S.desktop_po_no_rights_on_project)))
            return
        }
        when (prompt.action) {
            CardConfirmAction.ApproveCard -> act(str(S.ah_card_approved_toast)) { repository.approveCard(id, null) }
            CardConfirmAction.OverrideCard -> act(str(S.desktop_card_overridden_toast)) { repository.overrideCard(id) }
            CardConfirmAction.ActivateCard -> act(str(S.ah_card_activated_toast)) { repository.activateCard(id, null) }
            CardConfirmAction.SuspendCard -> act(str(S.ah_card_suspended_toast)) { repository.suspendCard(id) }
            CardConfirmAction.ReactivateCard -> act(str(S.ah_card_reactivated_toast)) { repository.reactivateCard(id) }
            CardConfirmAction.DeleteCard -> register.delete(id)
            CardConfirmAction.ApproveReceipt ->
                act(str(S.desktop_card_receipt_approved)) { repository.approveReceipt(id, null) }

            CardConfirmAction.OverrideReceipt ->
                act(str(S.desktop_card_receipt_overridden)) { repository.overrideReceipt(id) }

            CardConfirmAction.PostReceipt -> postReceipt(id)
            CardConfirmAction.SubmitReceiptForApproval ->
                act(str(S.ah_sent_for_approval_toast)) { repository.submitReceiptForApproval(id) }

            CardConfirmAction.ConfirmMatch ->
                act(str(S.desktop_card_match_confirmed)) { repository.confirmReceiptMatch(id) }

            CardConfirmAction.UnmatchReceipt -> act(str(S.desktop_card_match_removed)) { repository.unmatchReceipt(id) }
            CardConfirmAction.FlagPersonal ->
                act(str(S.desktop_card_flagged_personal)) { repository.flagReceiptPersonal(id) }

            CardConfirmAction.DismissDuplicate ->
                act(str(S.ah_duplicate_dismissed_toast)) { repository.dismissDuplicate(id) }

            CardConfirmAction.DismissPersonal ->
                act(str(S.desktop_card_personal_flag_cleared)) { repository.dismissPersonal(id) }

            CardConfirmAction.DeleteReceipt -> act(str(S.ah_receipt_deleted_toast)) { repository.deleteReceipt(id) }
            CardConfirmAction.CompleteTopUp -> act(str(S.desktop_card_topup_completed)) { repository.completeTopUp(id) }
            CardConfirmAction.SkipTopUp -> act(str(S.ah_topup_skipped_toast)) { repository.skipTopUp(id) }
            CardConfirmAction.DismissAlert -> act(str(S.ah_alert_dismissed_toast)) { repository.dismissAlert(id) }
            CardConfirmAction.InvestigateAlert ->
                act(str(S.desktop_card_marked_for_investigation)) { repository.investigateAlert(id) }

            CardConfirmAction.BulkApprove -> bulk(BulkAction.Approve)
            CardConfirmAction.BulkReject -> bulk(BulkAction.Reject)
            CardConfirmAction.PostTransaction ->
                act(str(S.desktop_card_transaction_posted)) { repository.postTransaction(id) }

            CardConfirmAction.FlagTransactionPersonal ->
                act(str(S.desktop_card_flagged_personal)) { repository.flagTransactionPersonal(id) }

            CardConfirmAction.DeleteTransaction -> deleteTransaction(id)
            CardConfirmAction.BulkDeleteTransactions -> bulkDeleteTransactions()
            CardConfirmAction.RerunMatching -> act(str(S.desktop_card_matching_rerun)) { repository.rerunMatching(id) }
        }
    }

    private fun resolveReason(prompt: CardPrompt.WithReason) {
        val reason = prompt.reason.trim()
        if (reason.isEmpty()) {
            sendEffect(CardEffect.Failed(str(S.desktop_a_reason_is_required)))
            setState { copy(prompt = prompt) }
            return
        }
        when (prompt.action) {
            CardReasonAction.RejectCard ->
                act(str(S.ah_card_rejected_toast)) { repository.rejectCard(prompt.targetId, reason) }

            CardReasonAction.RejectReceipt ->
                act(str(S.desktop_card_receipt_rejected)) { repository.rejectReceipt(prompt.targetId, reason) }

            CardReasonAction.QueryTransaction ->
                act(str(S.ah_query_sent_toast)) { repository.queryTransaction(prompt.targetId, reason) }

            CardReasonAction.RejectTransaction ->
                act(str(S.desktop_card_transaction_rejected)) { repository.rejectTransaction(prompt.targetId, reason) }

            CardReasonAction.ResolveAlert ->
                act(str(S.ah_alert_resolved_toast)) { repository.resolveAlert(prompt.targetId, reason) }

        }
    }

    private fun resolveAmount(prompt: CardPrompt.WithAmount) {
        val amount = prompt.amount.trim().toDoubleOrNull()
        if (amount == null || amount <= 0) {
            sendEffect(CardEffect.Failed(str(S.desktop_card_amount_greater_than_zero)))
            setState { copy(prompt = prompt) }
            return
        }
        when (prompt.action) {
            CardAmountAction.RequestTopUp -> act(str(S.desktop_card_topup_requested)) {
                repository.requestTopUp(prompt.targetId, amount, prompt.note.takeIf(String::isNotBlank))
            }

            CardAmountAction.PartialTopUp -> act(str(S.desktop_card_topup_recorded)) {
                repository.partialTopUp(prompt.targetId, amount)
            }
        }
    }

    private fun postReceipt(receiptId: String) {
        val receipt = currentState.receipts.firstOrNull { it.id == receiptId }
        if (receipt != null && !currentState.viewer.canPost(receipt.amount)) {
            sendEffect(
                CardEffect.Failed(str(S.desktop_card_above_posting_limit)),
            )
            return
        }
        act(str(S.desktop_card_receipt_posted)) { repository.postReceipt(receiptId) }
    }

    private fun deleteTransaction(transactionId: String) {
        act(str(S.desktop_card_transaction_deleted)) { repository.deleteTransaction(transactionId) }
        setState { copy(selectedTransactionId = null) }
    }

    /**
     * Deletes the ticked statement lines.
     *
     * The counts come back from the server rather than from what was asked
     * for: ids it does not recognise are skipped instead of failing the batch,
     * so "deleted 12" when 14 were ticked is a fact worth showing.
     */
    private fun bulkDeleteTransactions() {
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(CardEffect.Failed(str(S.desktop_nothing_is_selected)))
            return
        }
        launch {
            setState { copy(busy = true) }
            when (val result = repository.bulkDeleteTransactions(ids)) {
                is ZillitResult.Success -> {
                    val outcome = result.data
                    setState {
                        copy(
                            busy = false,
                            selection = emptySet(),
                            selectedTransactionId = null,
                            notice = if (outcome.failed > 0) {
                                str(S.desktop_card_deleted_count_skipped, outcome.succeeded, outcome.failed)
                            } else {
                                str(S.desktop_card_deleted_count, outcome.succeeded)
                            },
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

    private fun bulk(action: BulkAction) {
        val ids = currentState.selection.toList()
        if (ids.isEmpty()) {
            sendEffect(CardEffect.Failed(str(S.desktop_nothing_is_selected)))
            return
        }
        val notice = if (action == BulkAction.Approve) {
            str(S.desktop_card_receipts_approved_count, ids.size)
        } else {
            str(S.desktop_card_receipts_rejected_count, ids.size)
        }
        act(notice) {
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
