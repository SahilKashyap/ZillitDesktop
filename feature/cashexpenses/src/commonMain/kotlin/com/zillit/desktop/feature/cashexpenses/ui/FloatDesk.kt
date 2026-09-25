package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashReturn
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUps
import com.zillit.desktop.feature.cashexpenses.domain.FloatStatus
import kotlin.math.abs

/**
 * The float lifecycle, top-ups and fund requests — the web's Active Floats,
 * Top-Ups and Funds surfaces.
 *
 * Each transition is offered on exactly one status, as on the web
 * (`PCFloatsPage.jsx:748-785`), and every handler checks that status as well
 * as the accountant grant — an awaiting-approval float can no longer be
 * "issued" past its chain, and Close is for a spent float only.
 */
@Suppress("TooManyFunctions") // One handler per float action.
internal class FloatDesk(private val host: CashHost) {

    /** The float [floatId] from any list that might hold it. */
    fun float(floatId: String?): CashFloat? = floatId?.let { id ->
        (host.state.activeFloats + host.state.floatApprovals + host.state.myFloats).firstOrNull { it.id == id }
    }

    fun approve(floatId: String) {
        val float = float(floatId) ?: return
        if (!CashRules.mayApprove(host.state.viewer, float)) return host.noRights()
        val step = CashRules.approvalStep(host.state.viewer, float.departmentId, float.requestedAmount, float.approvals)
        host.act(str(S.desktop_ce_float_approved), after = { readApproval(floatId) }) {
            host.repository.approveFloat(floatId, step)
        }
    }

    /**
     * A float decided on the approval queue is read — only after the server
     * agreed, so a failed decision leaves the chip lit (ZL-20775,
     * `PCApprovalPage.jsx:1080-1095`).
     */
    fun readApproval(floatId: String) = host.readEntity(CashBadges.FLOAT_APPROVAL, floatId, CashBadges.KIND_FLOAT)

    // -- float details --------------------------------------------------------------

    /**
     * Opens the Float Details dialog: reads the float's `pc_float` row and
     * fetches `/details` (`PreviousFloatDetailModal.jsx:150-175`).
     */
    fun openDetail(floatId: String) {
        host.update { copy(floatDetail = FloatDetailState(floatId = floatId), floatDetailClaims = emptyMap()) }
        host.readEntity(CashBadges.PC_FLOAT, floatId, CashBadges.KIND_FLOAT)
        loadDetail(floatId)
    }

    fun closeDetail() = host.update { copy(floatDetail = null, floatDetailClaims = emptyMap()) }

    /** A posted batch in the details dialog, opened onto its receipts (`PreviousFloatDetailModal.jsx:177-193`). */
    fun toggleDetailBatch(batchId: String) {
        val open = host.state.floatDetailClaims
        if (batchId in open) return host.update { copy(floatDetailClaims = floatDetailClaims - batchId) }
        host.update { copy(floatDetailClaims = floatDetailClaims + (batchId to null)) }
        host.work {
            val claims = (host.repository.batch(batchId) as? ZillitResult.Success)?.data?.claims.orEmpty()
            host.update {
                if (batchId !in floatDetailClaims) return@update this
                copy(floatDetailClaims = floatDetailClaims + (batchId to claims))
            }
        }
    }

    /**
     * Corrects a float's BS code — `{bs_code}` and nothing else. Refused for
     * anyone but an accountant, on a float past approval with nothing spent
     * against it (`canEditFloatBsCode`); a blank code is refused, since the
     * code is the float's clearing account.
     */
    @Suppress("ReturnCount") // One refusal per rule.
    fun saveBsCode(floatId: String, bsCode: String) {
        val float = host.state.floatDetail?.details?.float?.takeIf { it.id == floatId } ?: float(floatId)
        if (float == null || !float.bsCodeEditable(host.state.viewer.isAccountant)) return host.noRights()
        val code = bsCode.trim()
        if (code.isEmpty()) return host.refuse(str(S.ah_err_field_required, str(S.desktop_ce_bs_code)))
        host.update { copy(floatDetail = floatDetail?.copy(savingBsCode = true)) }
        host.work {
            val saved = host.repository.updateFloatBsCode(floatId, code)
            host.update { copy(floatDetail = floatDetail?.copy(savingBsCode = false)) }
            when (saved) {
                is ZillitResult.Success -> {
                    host.update { copy(notice = str(S.ah_saved_toast)) }
                    if (host.state.floatDetail?.floatId == floatId) loadDetail(floatId)
                }

                is ZillitResult.Failure -> host.report(saved.error)
            }
        }
    }

    private fun loadDetail(floatId: String) {
        host.work {
            val loaded = host.repository.floatDetails(floatId)
            host.update {
                val open = floatDetail?.takeIf { it.floatId == floatId } ?: return@update this
                copy(
                    floatDetail = when (loaded) {
                        is ZillitResult.Success -> open.copy(loading = false, details = loaded.data, error = null)
                        is ZillitResult.Failure -> open.copy(loading = false, error = loaded.error)
                    },
                )
            }
        }
    }

    // -- an Active Floats row's batches, and a float's history ------------------------

    /**
     * Opens a row onto the batches spent against it — `GET /claims?float_request_id=`
     * — or closes it. Re-opening keeps what was fetched, as the web's row does.
     */
    fun toggleBatches(floatId: String) {
        val open = host.state.floatExpansions[floatId]
        if (open != null) {
            host.update { copy(floatExpansions = floatExpansions - floatId) }
            return
        }
        host.update { copy(floatExpansions = floatExpansions + (floatId to FloatExpansion())) }
        host.work {
            val rows = (host.repository.floatBatches(floatId) as? ZillitResult.Success)?.data.orEmpty()
            editExpansion(floatId) { copy(batches = rows) }
        }
    }

    /** Picks a batch beside the list and fetches its receipts once; picking it again clears the pick. */
    fun selectBatch(floatId: String, batchId: String) {
        val open = host.state.floatExpansions[floatId] ?: return
        if (open.selectedBatchId == batchId) return editExpansion(floatId) { copy(selectedBatchId = null) }
        editExpansion(floatId) { copy(selectedBatchId = batchId) }
        if (batchId in open.claims) return
        host.work {
            val claims = (host.repository.batch(batchId) as? ZillitResult.Success)?.data?.claims.orEmpty()
            editExpansion(floatId) { copy(claims = this.claims + (batchId to claims)) }
        }
    }

    /** The float's audit trail in a drawer (`PCFloatsPage.jsx:959-966`); null closes it. */
    fun showHistory(floatId: String?, reference: String?) {
        if (floatId == null) return host.update { copy(floatHistory = null) }
        host.update { copy(floatHistory = FloatHistoryPanel(floatId, reference)) }
        host.work {
            when (val loaded = host.repository.floatHistory(floatId)) {
                is ZillitResult.Success -> host.update {
                    val open = floatHistory?.takeIf { it.floatId == floatId } ?: return@update this
                    copy(floatHistory = open.copy(entries = loaded.data))
                }

                is ZillitResult.Failure -> {
                    host.update {
                        copy(floatHistory = floatHistory?.takeIf { it.floatId == floatId }?.copy(entries = emptyList()))
                    }
                    host.report(loaded.error)
                }
            }
        }
    }

    private fun editExpansion(floatId: String, change: FloatExpansion.() -> FloatExpansion) = host.update {
        val open = floatExpansions[floatId] ?: return@update this
        copy(floatExpansions = floatExpansions + (floatId to open.change()))
    }

    fun collect(floatId: String) = transition(floatId, FloatStatus.ReadyToCollect, S.desktop_ce_collection_recorded) {
        host.repository.collectFloat(floatId)
    }

    /** Close Float is offered on a spent float only; a pending return is recorded, not closed. */
    fun close(floatId: String) = transition(floatId, FloatStatus.Spent, S.desktop_ce_float_closed) {
        host.repository.closeFloat(floatId)
    }

    /** Ready to Collect needs the company; the BS code rides along when given. */
    fun readyToCollect(prompt: CashPrompt.ReadyToCollect): Boolean {
        val float = float(prompt.floatId) ?: return true
        val ready = float.status == FloatStatus.Approved || float.status == FloatStatus.AcctOverride
        if (!host.state.viewer.isAccountant || !ready) {
            host.noRights()
            return true
        }
        if (prompt.companyId.isBlank()) {
            host.refuse(str(S.desktop_ce_pick_a_company))
            return false
        }
        host.act(str(S.desktop_ce_marked_ready_to_collect)) {
            host.repository.markFloatReadyToCollect(prompt.floatId, prompt.companyId, prompt.bsCode)
        }
        return true
    }

    /**
     * Records a manual cash return, with the web's checks: a float, an amount
     * inside the balance, the whole balance when the reason closes the float,
     * and not the whole balance when it continues.
     *
     * Returns false — keep the dialog open — on a problem the person can fix.
     */
    fun recordReturn(prompt: CashPrompt.RecordReturn): Boolean {
        val float = float(prompt.floatId)
        if (!host.state.viewer.isAccountant || (float != null && float.status !in RETURNABLE)) {
            host.noRights()
            return true
        }
        val problem = returnProblem(prompt, float)
        val received = CashDates.utcMillis(prompt.receivedDate)
        if (problem != null || float == null || received == null) {
            host.refuse(problem ?: str(S.desktop_ce_pick_received_date))
            return false
        }
        val amount = prompt.amount.trim().toDouble()
        val reason = ReturnReasons.wire(prompt.reason, prompt.otherCloses)
        host.act(str(S.desktop_ce_cash_return_recorded)) {
            host.repository.recordCashReturn(float.id, CashReturn(amount, received, reason, prompt.notes))
        }
        return true
    }

    /** The first rule the return breaks, in the web's order, or null. */
    @Suppress("ReturnCount") // One rule per return; combining them loses which failed.
    private fun returnProblem(prompt: CashPrompt.RecordReturn, float: CashFloat?): String? {
        if (float == null) return str(S.desktop_ce_pick_a_float)
        val amount = prompt.amount.trim().toDoubleOrNull()?.takeIf { it > 0 }
            ?: return str(S.desktop_ce_enter_return_amount)
        val balance = float.balance
        val money = { value: Double -> Money.format(value, float.currency) }
        val reason = ReturnReasons.wire(prompt.reason, prompt.otherCloses)
        return when {
            amount > balance + PENNY -> str(S.desktop_ce_return_exceeds_balance, money(amount), money(balance))
            ReturnReasons.closes(reason) && abs(amount - balance) > PENNY ->
                str(S.desktop_ce_return_must_be_balance, money(balance))

            ReturnReasons.continues(reason) && abs(amount - balance) < PENNY ->
                str(S.desktop_ce_return_leaves_nothing, money(balance))

            CashDates.utcMillis(prompt.receivedDate) == null -> str(S.desktop_ce_pick_received_date)
            else -> null
        }
    }

    /**
     * Mark topped up — pays a top-up in full, at once, as the web does. Past the
     * float's limit it is stopped with the web's alert; a partial top-up is the
     * way to pay less (`PCTopUpsPage.jsx:239-262`).
     */
    fun completeTopUp(topUpId: String) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        val topUp = host.state.topUps.firstOrNull { it.id == topUpId } ?: return
        if (CashTopUps.exceedsRoom(topUp, topUp.amount)) {
            return limitAlert(str(S.ah_topup_exceeds_limit_msg, topUpMoney(topUp, topUp.amount), roomText(topUp)))
        }
        host.act(str(S.ah_topup_marked_toast)) { host.repository.completeTopUp(topUpId) }
    }

    fun skipTopUp(topUpId: String) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        host.act(str(S.ah_topup_skipped_toast)) { host.repository.skipTopUp(topUpId) }
    }

    /**
     * A partial top-up needs the amount and the reason for paying less, and
     * may not take the float past its limit (`PCTopUpsPage.jsx:279-313`). On
     * success the Partial Top-Up dialog closes.
     */
    fun partialTopUp(topUpId: String, amount: Double, note: String): Boolean {
        if (!host.state.viewer.isAccountant) {
            host.noRights()
            return true
        }
        if (note.isBlank()) {
            host.refuse(str(S.desktop_ce_partial_needs_note))
            return false
        }
        val topUp = host.state.topUps.firstOrNull { it.id == topUpId }
        if (topUp != null && CashTopUps.exceedsRoom(topUp, amount)) {
            limitAlert(str(S.desktop_pc_partial_exceeds_room, topUpMoney(topUp, amount), roomText(topUp)))
            return false
        }
        host.act(
            str(S.ah_partial_topup_recorded_toast),
            onSuccess = { copy(fundsUi = fundsUi.copy(partial = null)) },
        ) { host.repository.partialTopUp(topUpId, amount, note) }
        return true
    }

    private fun limitAlert(message: String) = host.update {
        copy(fundsUi = fundsUi.copy(limitAlert = LimitAlert(str(S.ah_topup_exceeds_limit_title), message)))
    }

    private fun topUpMoney(topUp: CashTopUp, amount: Double): String = host.state.formatMoney(amount, topUp.currency)

    private fun roomText(topUp: CashTopUp): String = topUpMoney(topUp, CashTopUps.room(topUp))

    // -- fund requests -------------------------------------------------------------

    fun showFunds(open: Boolean) {
        if (!open) return host.update { copy(funds = null) }
        if (!host.state.viewer.isAccountant) return host.noRights()
        // The custodian is the one account every float draws on; it comes from settings.
        val custodian = host.state.settings?.custodianAccount.orEmpty()
        host.update { copy(funds = FundsState(fundAccount = custodian)) }
        loadFunds(refreshCustodian = host.state.settings == null)
    }

    /**
     * `{fund_account, currency, amount}` — every key, always. The currency is
     * the one picked, else the project default; with neither the request is
     * refused, as the web's Request funds stays disabled
     * (`RequestCashFundsModal.jsx:181-187,273-277`).
     */
    fun submitFunds() {
        val funds = host.state.funds ?: return
        if (!host.state.viewer.isAccountant) return host.noRights()
        val amount = funds.amount.trim().toDoubleOrNull()?.takeIf { it > 0 }
        val currency = fundsCurrency(host.state)
        if (funds.fundAccount.isBlank() || amount == null || currency.isBlank()) {
            return host.refuse(str(S.desktop_ce_funds_need_account_amount))
        }
        runFunds(str(S.desktop_ce_funds_requested), clearAmount = true) {
            host.repository.createFundRequest(funds.fundAccount, currency, amount)
        }
    }

    fun receiveFunds(id: String) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        runFunds(str(S.desktop_ce_funds_received)) { host.repository.receiveFundRequest(id) }
    }

    fun cancelFunds(id: String) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        runFunds(str(S.desktop_ce_funds_cancelled)) { host.repository.cancelFundRequest(id) }
    }

    private fun runFunds(success: String, clearAmount: Boolean = false, block: suspend () -> ZillitResult<Unit>) {
        host.update { copy(busy = true) }
        host.work {
            val result = block()
            host.update { copy(busy = false) }
            when (result) {
                is ZillitResult.Success -> {
                    host.update {
                        copy(
                            notice = success,
                            funds = funds?.let { if (clearAmount) it.copy(amount = "") else it },
                        )
                    }
                    loadFunds(refreshCustodian = false)
                }

                is ZillitResult.Failure -> host.report(result.error)
            }
        }
    }

    private fun loadFunds(refreshCustodian: Boolean) {
        host.work {
            if (refreshCustodian) {
                (host.repository.settings() as? ZillitResult.Success)?.data?.let { settings ->
                    host.update {
                        copy(
                            settings = settings,
                            settingsDraft = settingsDraft ?: settings,
                            funds = funds?.let {
                                if (it.fundAccount.isBlank()) it.copy(fundAccount = settings.custodianAccount) else it
                            },
                        )
                    }
                }
            }
            when (val rows = host.repository.fundRequests()) {
                is ZillitResult.Success -> host.update {
                    copy(funds = funds?.copy(requests = rows.data, loading = false))
                }

                is ZillitResult.Failure -> {
                    host.update { copy(funds = funds?.copy(loading = false)) }
                    host.report(rows.error)
                }
            }
        }
    }

    private fun transition(
        floatId: String,
        from: FloatStatus,
        successKey: String,
        block: suspend () -> ZillitResult<Unit>,
    ) {
        val float = float(floatId) ?: return
        if (!host.state.viewer.isAccountant || float.status != from) return host.noRights()
        host.act(str(successKey), block = block)
    }


    companion object {
        /** Floats a return can be recorded against — the web's selector list. */
        val RETURNABLE = setOf(
            FloatStatus.Collected,
            FloatStatus.Active,
            FloatStatus.Spending,
            FloatStatus.Spent,
            FloatStatus.PendingReturn,
        )

        private const val PENNY = 0.005
    }
}

/** The fund request's currency: the one picked, else the project default; blank when there is neither. */
internal fun fundsCurrency(state: CashUiState): String =
    state.funds?.currency?.trim()?.ifBlank { null } ?: state.currencies.defaultCode.orEmpty().trim()
