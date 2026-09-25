package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.forms.FormField
import com.zillit.desktop.core.forms.customValues
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.BankDetailsDraft
import com.zillit.desktop.feature.cashexpenses.domain.CashFloatOrder
import com.zillit.desktop.feature.cashexpenses.domain.CashFormFields
import com.zillit.desktop.feature.cashexpenses.domain.CrewDates
import com.zillit.desktop.feature.cashexpenses.domain.CrewRequestCap
import com.zillit.desktop.feature.cashexpenses.domain.ExpenseType
import com.zillit.desktop.feature.cashexpenses.domain.ExtraBankField
import com.zillit.desktop.feature.cashexpenses.domain.FollowUp
import com.zillit.desktop.feature.cashexpenses.domain.NewClaimBatch
import com.zillit.desktop.feature.cashexpenses.domain.NewFloatRequest
import com.zillit.desktop.feature.cashexpenses.domain.ReimbursementMethod
import com.zillit.desktop.feature.cashexpenses.domain.RequestCap
import com.zillit.desktop.feature.cashexpenses.domain.SettlementDetails
import kotlinx.coroutines.Job
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.math.roundToLong
import kotlin.time.Clock

/**
 * The crew's pages — Submit Receipts, Receipts History, Float Request — and
 * the web's rules for each (`PCSubmitClaimPage`, `OOPSubmitPage`,
 * `PCMyClaimsPage`, `PCFloatRequestPage`).
 */
@Suppress("TooManyFunctions") // One handler per crew action.
internal class CrewDesk(
    private val host: CashHost,
    /** Opens another page of the module, as the web's `onNavigate` does after a submit. */
    private val navigate: (CashDestination) -> Unit,
) {

    private var queryJob: Job? = null
    private var batchesJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // One branch per crew event.
    fun handle(event: CrewEvent) {
        when (event) {
            is CrewEvent.PickSubmitFloat -> pickFloat(event.floatId)
            is CrewEvent.ToggleFollowUp -> editCrew {
                copy(followUp = if (followUp == event.followUp) null else event.followUp)
            }
            is CrewEvent.PickReimbursement -> editCrew { copy(reimbursementMethod = event.method) }
            is CrewEvent.EditBank -> editCrew { copy(bank = event.bank) }
            CrewEvent.AddBankExtra -> editCrew { copy(bank = bank.copy(extras = bank.extras + ExtraBankField())) }
            is CrewEvent.EditBankExtra -> editCrew {
                val extras = bank.extras.mapIndexed { i, row -> if (i == event.index) event.row else row }
                copy(bank = bank.copy(extras = extras))
            }
            is CrewEvent.RemoveBankExtra -> editCrew {
                copy(bank = bank.copy(extras = bank.extras.filterIndexed { i, _ -> i != event.index }))
            }
            is CrewEvent.PickSubmitCurrency -> editCrew { copy(submitCurrency = event.code) }
            is CrewEvent.FilterHistory -> editCrew { copy(historyFilter = event.filter) }
            is CrewEvent.OpenClaim -> openClaim(event.claimId)
            CrewEvent.CloseClaim -> editCrew { copy(openClaimId = null) }
            is CrewEvent.ShowQuery -> showQuery(event.open)
            CrewEvent.SendQuery -> sendQuery()
            is CrewEvent.ShowFloatForm -> editCrew {
                copy(floatFormOpen = event.open, floatErrors = emptyMap(), floatSubmitError = null)
            }
        }
    }

    /** Arriving on a page: what the web's page starts with on mount. */
    fun onOpen(destination: CashDestination) {
        host.update {
            copy(
                crew = crew.copy(
                    openClaimId = null,
                    submitError = null,
                    historyFilter = HistoryFilter.ALL,
                    floatErrors = emptyMap(),
                    floatSubmitError = null,
                    // Accountants land straight on the form; crew on their floats.
                    floatFormOpen = viewer.isAccountant,
                ),
            )
        }
        when (destination) {
            // Read afresh on each visit — by `loadSubmit`, which also covers arriving without `open`.
            CashDestination.SubmitReceipts, CashDestination.OutOfPocketSubmit ->
                host.update { copy(crew = crew.copy(cashSettings = null)) }
            // The accountant's cap is on `/settings`; crew read theirs off `/metadata`.
            CashDestination.FloatRequest -> if (host.state.viewer.isAccountant) readSettings()
            else -> Unit
        }
    }

    // -- loading ---------------------------------------------------------------

    /**
     * Submit Receipts: the crew member's floats and, for the one picked, the
     * batches still in the pipeline against it — the freshness guard in the
     * settlement maths. Out of pocket has no float to read.
     */
    suspend fun loadSubmit(type: ExpenseType): ZillitResult<CashUiState.() -> CashUiState> {
        // Landing here without `open` (the first page, a pipeline switch) still reads the payroll switch.
        if (host.state.crew.cashSettings == null) readSettings()
        if (type == ExpenseType.OutOfPocket) return ZillitResult.Success { this }
        val floats = host.repository.myFloats()
        if (floats is ZillitResult.Failure) return floats
        val sorted = CashFloatOrder.oldestFirst((floats as ZillitResult.Success).data)
        val float = CrewRules.submitFloat(sorted, host.state.crew.submitFloatId)
        // A failed read is taken as none pending, as the web's catch does.
        val batches = float?.let {
            (host.repository.myBatches(floatRequestId = it.id) as? ZillitResult.Success)?.data
        }.orEmpty()
        return ZillitResult.Success { copy(myFloats = sorted, myBatches = batches) }
    }

    private fun pickFloat(floatId: String) {
        editCrew { copy(submitFloatId = floatId) }
        batchesJob?.cancel()
        batchesJob = host.work {
            val batches = (host.repository.myBatches(floatRequestId = floatId) as? ZillitResult.Success)?.data
            host.update { if (crew.submitFloatId == floatId) copy(myBatches = batches.orEmpty()) else this }
        }
    }

    /** `GET /settings`, quietly — a crew member is usually refused it, which leaves Payroll off. */
    private fun readSettings() {
        host.work {
            val settings = (host.repository.settings() as? ZillitResult.Success)?.data ?: return@work
            host.update { copy(crew = crew.copy(cashSettings = settings)) }
        }
    }

    // -- submit receipts -------------------------------------------------------

    fun submitReceipts() {
        val state = host.state
        val outOfPocket = state.destination.expenseType == ExpenseType.OutOfPocket
        val float = state.submittableFloat
        if (!outOfPocket && float == null) return
        val reimburses = outOfPocket || state.settlement.reimburses
        val method = state.crew.effectiveMethod
        val details = SettlementDetails(
            includeFollowUp = !outOfPocket,
            followUp = if (outOfPocket) null else state.crew.followUp,
            topUpAmount = if (!outOfPocket && state.crew.followUp == FollowUp.TOP_UP) {
                (state.draft.total * CENTS).roundToLong() / CENTS
            } else {
                null
            },
            paymentMethod = if (reimburses) method else null,
            bankDetails = if (reimburses && method == ReimbursementMethod.Bacs) state.crew.bank else null,
        )
        val request = NewClaimBatch(
            expenseType = state.destination.expenseType,
            floatId = float?.id.takeIf { !outOfPocket },
            receipts = state.draft.receipts,
            // Arithmetic, not a choice: over the float's headroom is a reimbursement.
            settlementType = if (reimburses) REIMBURSE else REDUCE_FLOAT,
            notes = state.draft.notes.takeIf { it.isNotBlank() },
            departmentId = state.viewer.departmentId,
            // Receipts are spent against the float, so they share its currency.
            currency = if (outOfPocket) {
                state.currencies.codeFor(state.crew.submitCurrency)
            } else {
                state.currencies.codeFor(float?.currency)
            },
            settlementDetails = details,
        )

        val invalid = receiptsError(request)
        editCrew { copy(submitError = invalid) }
        if (invalid != null) return

        val next = if (outOfPocket) CashDestination.OutOfPocketHistory else CashDestination.ReceiptsHistory
        host.act(
            success = str(S.desktop_ce_receipts_submitted),
            onSuccess = {
                copy(
                    draft = SubmitDraft(),
                    crew = crew.copy(
                        followUp = null,
                        reimbursementMethod = ReimbursementMethod.Bacs,
                        bank = BankDetailsDraft(),
                        submitCurrency = "",
                        submitError = null,
                    ),
                )
            },
            after = { navigate(next) },
        ) { host.repository.submitReceipts(request) }
    }

    /** `newReceiptsValidationError`, message for message. */
    private fun receiptsError(request: NewClaimBatch): String? {
        val receipts = request.receipts
        return when {
            receipts.isEmpty() -> str(S.desktop_ce_add_one_receipt)
            receipts.any { it.description.isBlank() || (it.amount.trim().toDoubleOrNull() ?: 0.0) <= 0 } ->
                str(S.ah_each_receipt_must_have)
            receipts.any { it.date == null } -> str(S.desktop_ce_receipt_needs_date)
            receipts.any { it.attachment == null && it.attachmentKey.isNullOrBlank() } ->
                str(S.desktop_ce_receipt_needs_attachment)
            else -> null
        }
    }

    // -- receipts history --------------------------------------------------------

    private fun openClaim(claimId: String) {
        val key = host.state.destination.batchBadgeKey
        if (key != null) host.readEntity(key, claimId, CashBadges.KIND_QUERY)
        editCrew { copy(openClaimId = claimId) }
    }

    /**
     * The open batch's query thread, for the person who submitted it — the
     * same `cash_claim` thread, keyed by the batch, the accountant writes to.
     * The batch desk's own query is the accountant's, so this is the crew's.
     */
    private fun showQuery(open: Boolean) {
        val batchId = host.state.panel?.batchId ?: return
        queryJob?.cancel()
        if (!open) return editPanel(batchId) { copy(query = null) }
        editPanel(batchId) { copy(query = QueryPanel()) }
        queryJob = host.work {
            val thread = host.repository.queryThread(batchId)
            if (thread is ZillitResult.Success) readQuery(batchId)
            editPanel(batchId) {
                val open = query ?: return@editPanel this
                copy(query = open.copy(loading = false, thread = (thread as? ZillitResult.Success)?.data))
            }
            (thread as? ZillitResult.Failure)?.let { host.report(it.error) }
        }
    }

    private fun sendQuery() {
        val batchId = host.state.panel?.batchId ?: return
        val query = host.state.panel?.query ?: return
        val text = query.draft.trim()
        if (text.isEmpty() || query.sending) return
        editPanel(batchId) { copy(query = query.copy(sending = true)) }
        host.work {
            when (val sent = host.repository.sendQuery(batchId, query.thread?.id, text)) {
                is ZillitResult.Success -> {
                    editPanel(batchId) {
                        copy(query = this.query?.copy(sending = false, draft = "", thread = sent.data))
                    }
                    readQuery(batchId)
                }
                is ZillitResult.Failure -> {
                    editPanel(batchId) { copy(query = this.query?.copy(sending = false)) }
                    host.report(sent.error)
                }
            }
        }
    }

    private fun readQuery(batchId: String) {
        val key = host.state.destination.batchBadgeKey ?: return
        host.readEntity(key, batchId, CashBadges.KIND_QUERY)
    }

    // -- float request -----------------------------------------------------------

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // The web's rules, in the web's order.
    fun submitFloatRequest() {
        val state = host.state
        val draft = state.floatDraft
        val fields = state.floatRequestFields
        val shown = fields.map { it.label }.toSet()
        fun refuse(message: String, errors: Map<String, String> = emptyMap()) =
            editCrew { copy(floatSubmitError = message, floatErrors = errors) }

        val amount = draft.amount.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: 0.0
        if (amount <= 0) return refuse(str(S.desktop_pc_amount_greater_than_zero))
        val currency = draft.currency.ifBlank { state.currencies.default }
        val overCap = CrewRequestCap.exceeds(state.floatCap, amount, currency, state.currencies.default)
        if (state.floatCapApplies && overCap) {
            val cap = Money.format(state.floatCap?.maxAmount, state.currencies.default)
            return refuse(str(S.desktop_pc_amount_exceeds_cap, cap))
        }
        val days = draft.duration.trim().toIntOrNull()
        val daysShown = draft.durationType == CashFormFields.DAYS && CashFormFields.DURATION in shown
        if (daysShown && (days ?: 0) <= 0) {
            return refuse(str(S.desktop_pc_days_positive))
        }
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault()).toString()
        if (draft.collectDate.isNotBlank() && draft.collectDate < today) {
            return refuse(str(S.desktop_pc_collect_date_past))
        }

        val errors = buildMap {
            fields.forEach { field ->
                if (field.label == CashFormFields.DURATION && draft.durationType != CashFormFields.DAYS) return@forEach
                val value = state.floatValue(field).trim()
                if (field.required && value.isEmpty()) {
                    put(field.label, str(S.recce_required_field, field.name))
                    return@forEach
                }
                if (!field.systemDefault && value.isNotEmpty()) formatError(field, value)?.let { put(field.label, it) }
            }
        }
        if (errors.isNotEmpty()) return refuse(str(S.desktop_pc_fix_highlighted), errors)
        editCrew { copy(floatErrors = emptyMap(), floatSubmitError = null) }

        val onBehalf = state.viewer.isAccountant
        val shownValue = { label: String -> state.floatValueOf(label).takeIf { label in shown && it.isNotBlank() } }
        val request = NewFloatRequest(
            amount = amount,
            currency = currency,
            purpose = shownValue(CashFormFields.PURPOSE).orEmpty().trim(),
            departmentId = state.floatValueOf(CashFormFields.DEPARTMENT).takeIf { it.isNotBlank() },
            duration = draft.duration.trim().takeIf { it.isNotEmpty() },
            durationType = shownValue(CashFormFields.DURATION_TYPE),
            targetUserId = if (onBehalf) state.floatValueOf(CashFormFields.USER).takeIf { it.isNotBlank() } else null,
            customFields = listOfNotNull(
                state.floatForm.customValues(CashFormFields.FLOAT_REQUEST, draft.customFields),
            ),
            collectDate = shownValue(CashFormFields.COLLECT_DATE)?.let(CrewDates::utcMillis),
            episode = shownValue(CashFormFields.EPISODE),
            collectionMethod = shownValue(CashFormFields.COLLECTION_METHOD),
        )
        host.act(
            str(S.desktop_ce_float_requested),
            onSuccess = {
                copy(
                    floatDraft = FloatRequestDraft(),
                    // An accountant stays on the form to raise the next; crew see their floats.
                    crew = crew.copy(floatFormOpen = onBehalf, floatErrors = emptyMap(), floatSubmitError = null),
                )
            },
        ) { host.repository.requestFloat(request) }
    }

    /** A custom field's format check (`PCFloatRequestPage.jsx:513-522`). */
    private fun formatError(field: FormField, value: String): String? = when (field.type) {
        "email" -> str(S.desktop_pc_field_valid_email, field.name).takeUnless { EMAIL.matches(value) }
        "url" -> str(S.desktop_pc_field_valid_url, field.name).takeUnless { URL.matches(value) }
        "number" -> str(S.desktop_pc_field_number, field.name).takeUnless { value.toDoubleOrNull() != null }
        "phone" -> str(S.desktop_pc_field_valid_phone, field.name).takeUnless { PHONE.matches(value) }
        else -> null
    }

    // -- plumbing ----------------------------------------------------------------

    private fun editCrew(edit: CrewState.() -> CrewState) = host.update { copy(crew = crew.edit()) }

    private fun editPanel(batchId: String, edit: BatchPanel.() -> BatchPanel) = host.update {
        val open = panel?.takeIf { it.batchId == batchId } ?: return@update this
        copy(panel = open.edit())
    }

    companion object {
        const val REIMBURSE = "REIMBURSE"
        const val REDUCE_FLOAT = "REDUCE_FLOAT"
        private const val CENTS = 100.0
        private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private val URL = Regex("^.+\\..+")
        private val PHONE = Regex("^[\\d\\s+\\-()]{6,}$")
    }
}

// -- the float request's values, shared by the page and the desk --------------

/** The float request's fields as the page renders them — see [CashFormFields.requestFields]. */
internal val CashUiState.floatRequestFields: List<FormField>
    get() = CashFormFields.requestFields(floatForm.visible(CashFormFields.FLOAT_REQUEST))

/** A field's current answer, defaults applied. */
internal fun CashUiState.floatValue(field: FormField): String =
    if (field.systemDefault || field.label in CashFormFields.RENDERED) {
        floatValueOf(field.label, field)
    } else {
        floatDraft.customFields[field.label].orEmpty()
    }

/** A system field's current answer by its key; [field] supplies a select's default. */
internal fun CashUiState.floatValueOf(label: String, field: FormField? = null): String {
    val draft = floatDraft
    val select = { value: String ->
        value.ifBlank { field?.let(CashFormFields::defaultSelect) ?: defaultOption(label) }
    }
    return when (label) {
        CashFormFields.USER -> draft.targetUserId.ifBlank { viewer.userId }
        CashFormFields.DEPARTMENT -> draft.departmentId.ifBlank {
            val self = draft.targetUserId.isBlank() || draft.targetUserId == viewer.userId
            if (self) viewer.departmentId.orEmpty() else ""
        }
        CashFormFields.AMOUNT -> draft.amount
        CashFormFields.PURPOSE -> draft.purpose
        CashFormFields.DURATION -> draft.duration
        CashFormFields.DURATION_TYPE -> draft.durationType
        CashFormFields.COLLECT_DATE -> draft.collectDate
        CashFormFields.EPISODE -> select(draft.episode)
        CashFormFields.COLLECTION_METHOD -> select(draft.collectionMethod)
        else -> draft.customFields[label].orEmpty()
    }
}

private fun defaultOption(label: String): String = CashFormFields.OPTIONS[label]?.firstOrNull()?.first.orEmpty()

/**
 * The project cap this requester is held to: an accountant's from
 * `/settings`, crew's from `/metadata` (`PCFloatRequestPage.jsx:270-282`).
 */
internal val CashUiState.floatCap: RequestCap?
    get() = if (viewer.isAccountant) crew.cashSettings?.requestCap else viewer.metadata.requestCap

/** The cap is the requester's own: raised on someone else's behalf, the server judges it. */
internal val CashUiState.floatCapApplies: Boolean
    get() = floatDraft.targetUserId.isBlank() || floatDraft.targetUserId == viewer.userId
