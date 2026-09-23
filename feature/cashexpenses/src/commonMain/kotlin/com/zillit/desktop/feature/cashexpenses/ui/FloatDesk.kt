package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashDates
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashReturn
import com.zillit.desktop.feature.cashexpenses.domain.CashRules
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
        host.act(str(S.desktop_ce_float_approved)) { host.repository.approveFloat(floatId, step) }
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
     * Pays a top-up in full — refused when it would take the float past its
     * limit; the partial top-up is the way to pay less.
     */
    fun completeTopUp(topUpId: String) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        val topUp = host.state.topUps.firstOrNull { it.id == topUpId } ?: return
        val room = roomOn(topUp)
        if (topUp.amount > room + PENNY) {
            return host.refuse(
                str(
                    S.desktop_ce_topup_exceeds_room,
                    Money.format(topUp.amount, topUp.currency),
                    Money.format(room, topUp.currency),
                ),
            )
        }
        host.act(str(S.desktop_card_topup_completed)) { host.repository.completeTopUp(topUpId) }
    }

    fun skipTopUp(topUpId: String) {
        if (!host.state.viewer.isAccountant) return host.noRights()
        host.act(str(S.ah_topup_skipped_toast)) { host.repository.skipTopUp(topUpId) }
    }

    /** A partial top-up needs the amount and the reason for paying less (`PCTopUpsPage.jsx:280-300`). */
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
        if (topUp != null && amount > roomOn(topUp) + PENNY) {
            host.refuse(
                str(
                    S.desktop_ce_topup_exceeds_room,
                    Money.format(amount, topUp.currency),
                    Money.format(roomOn(topUp), topUp.currency),
                ),
            )
            return false
        }
        host.act(str(S.desktop_card_topup_recorded)) { host.repository.partialTopUp(topUpId, amount, note) }
        return true
    }

    // -- fund requests -------------------------------------------------------------

    fun showFunds(open: Boolean) {
        if (!open) return host.update { copy(funds = null) }
        if (!host.state.viewer.isAccountant) return host.noRights()
        // The custodian is the one account every float draws on; it comes from settings.
        val custodian = host.state.settings?.custodianAccount.orEmpty()
        host.update { copy(funds = FundsState(fundAccount = custodian)) }
        loadFunds(refreshCustodian = host.state.settings == null)
    }

    fun submitFunds() {
        val funds = host.state.funds ?: return
        if (!host.state.viewer.isAccountant) return host.noRights()
        val amount = funds.amount.trim().toDoubleOrNull()
        if (funds.fundAccount.isBlank() || amount == null || amount <= 0) {
            return host.refuse(str(S.desktop_ce_funds_need_account_amount))
        }
        runFunds(str(S.desktop_ce_funds_requested), clearAmount = true) {
            host.repository.createFundRequest(funds.fundAccount, funds.currency.ifBlank { null }, amount)
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
        host.work {
            when (val result = block()) {
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

    /** Limit minus current balance — how much more the float can take (`roomFor`). */
    private fun roomOn(topUp: com.zillit.desktop.feature.cashexpenses.domain.CashTopUp): Double =
        (topUp.floatRequestedAmount - topUp.floatBalance).coerceAtLeast(0.0)

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
