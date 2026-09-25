package com.zillit.desktop.feature.cashexpenses.ui

import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.CashFloatOrder
import com.zillit.desktop.feature.cashexpenses.domain.CashTopUp

/**
 * What the Top-Ups, Cash Extension, Fund Requests and Cash Recon pages ask
 * for — one event ([CashEvent.Funds]) so the shared event list grows by one.
 *
 * The web fires these without a confirmation step (`PCTopUpsPage.jsx:239-277`,
 * `RequestCashFundsModal.jsx:288-312`, `PCCashReconPage.jsx:463-490`), so none
 * of them goes through a [CashPrompt].
 */
sealed interface FundsAction {
    // -- the accountant's top-up inbox --
    data class CompleteTopUp(val topUpId: String) : FundsAction

    data class SkipTopUp(val topUpId: String) : FundsAction

    /** Opens the Partial Top-Up dialog, prefilled with the full amount (`PCTopUpsPage.jsx:501`). */
    data class OpenPartial(val topUpId: String) : FundsAction

    data class EditPartial(val amount: String, val note: String) : FundsAction

    data object ClosePartial : FundsAction

    data object SubmitPartial : FundsAction

    data object DismissLimitAlert : FundsAction

    // -- the crew's Cash Extension --
    data class SelectExtensionFloat(val floatId: String) : FundsAction

    data object RetryExtension : FundsAction

    data object OpenRequest : FundsAction

    data class EditRequest(val amount: String, val reason: String) : FundsAction

    data object CloseRequest : FundsAction

    data object SubmitRequest : FundsAction

    // -- fund requests --
    data class ReceiveFunds(val requestId: String) : FundsAction

    data class CancelFunds(val requestId: String) : FundsAction

    /** Prefills the form from a cancelled request — no endpoint (`RequestCashFundsModal.jsx:316-320`). */
    data class DuplicateFunds(val requestId: String) : FundsAction

    // -- cash reconciliation --
    data class CreateReconciliation(
        val openingBalance: String,
        val year: Int,
        val month: Int,
        val currency: String,
    ) : FundsAction

    data object SubmitReconciliation : FundsAction

    data object SignOffReconciliation : FundsAction
}

/** The Top-Ups and Cash Extension pages' own state — one field on [CashUiState]. */
data class FundsUiState(
    val extension: ExtensionState = ExtensionState(),
    /** The Request Top-up dialog, while open. */
    val request: TopUpRequestDraft? = null,
    /** The Partial Top-Up dialog, while open. */
    val partial: PartialTopUpDraft? = null,
    /** The "Top-up exceeds float limit" alert, while shown. */
    val limitAlert: LimitAlert? = null,
)

/** Cash Extension: the float being topped up and the top-ups raised against it. */
data class ExtensionState(
    val floatId: String? = null,
    val rows: List<CashTopUp> = emptyList(),
    val loading: Boolean = false,
    /** The list could not be read — the web's "Couldn't load top-ups. Retry". */
    val failed: Boolean = false,
)

data class TopUpRequestDraft(val amount: String = "", val reason: String = "")

data class PartialTopUpDraft(val topUpId: String, val amount: String, val note: String = "")

data class LimitAlert(val title: String, val message: String)

/**
 * The handlers behind [FundsAction].
 *
 * The top-up and fund writes themselves stay on [FloatDesk] and the
 * reconciliation's on [ReconDesk]; this desk owns the Cash Extension's own
 * loading and the dialogs' drafts.
 */
@Suppress("TooManyFunctions") // One handler per action.
internal class FundsDesk(
    private val host: CashHost,
    private val floats: FloatDesk,
    private val recon: ReconDesk,
) {

    @Suppress("CyclomaticComplexMethod") // A dispatch table.
    fun handle(action: FundsAction) {
        when (action) {
            is FundsAction.CompleteTopUp -> floats.completeTopUp(action.topUpId)
            is FundsAction.SkipTopUp -> floats.skipTopUp(action.topUpId)
            is FundsAction.OpenPartial -> openPartial(action.topUpId)
            is FundsAction.EditPartial ->
                ui { copy(partial = partial?.copy(amount = action.amount, note = action.note)) }
            FundsAction.ClosePartial -> ui { copy(partial = null) }
            FundsAction.SubmitPartial -> submitPartial()
            FundsAction.DismissLimitAlert -> ui { copy(limitAlert = null) }

            is FundsAction.SelectExtensionFloat -> selectFloat(action.floatId)
            FundsAction.RetryExtension -> host.state.fundsUi.extension.floatId?.let(::loadRows)
            FundsAction.OpenRequest -> ui { copy(request = TopUpRequestDraft()) }
            is FundsAction.EditRequest -> ui { copy(request = TopUpRequestDraft(action.amount, action.reason)) }
            FundsAction.CloseRequest -> ui { copy(request = null) }
            FundsAction.SubmitRequest -> submitRequest()

            is FundsAction.ReceiveFunds -> floats.receiveFunds(action.requestId)
            is FundsAction.CancelFunds -> floats.cancelFunds(action.requestId)
            is FundsAction.DuplicateFunds -> duplicate(action.requestId)

            is FundsAction.CreateReconciliation -> recon.create(
                CashPrompt.NewReconciliation(
                    openingBalance = action.openingBalance,
                    year = action.year,
                    month = action.month,
                    currency = action.currency,
                ),
            )

            FundsAction.SubmitReconciliation -> recon.submit()
            FundsAction.SignOffReconciliation -> recon.signOff()
        }
    }

    // -- Cash Extension ---------------------------------------------------------

    /**
     * The Cash Extension page's load: the crew member's floats, then the
     * top-ups on the one selected — the first toppable float unless one was
     * already chosen (`TopUpExtensionPanel.jsx:72-111`). A failed top-up read
     * keeps the page and says so; a failed float read fails the page.
     */
    suspend fun loadExtension(): ZillitResult<CashUiState.() -> CashUiState> {
        val floats = host.repository.myFloats()
        if (floats is ZillitResult.Failure) return floats
        val sorted = CashFloatOrder.oldestFirst((floats as ZillitResult.Success).data)
        val selected = pick(toppable(sorted), host.state.fundsUi.extension.floatId)
        val rows = selected?.let { host.repository.floatTopUps(it) }
        val extension = ExtensionState(
            floatId = selected,
            rows = (rows as? ZillitResult.Success)?.data.orEmpty(),
            loading = false,
            failed = rows is ZillitResult.Failure,
        )
        return ZillitResult.Success { copy(myFloats = sorted, fundsUi = fundsUi.copy(extension = extension)) }
    }

    private fun selectFloat(floatId: String) {
        if (toppable(host.state.myFloats).none { it.id == floatId }) return
        loadRows(floatId)
    }

    private fun loadRows(floatId: String) {
        ui { copy(extension = extension.copy(floatId = floatId, loading = true, failed = false)) }
        host.work {
            val rows = host.repository.floatTopUps(floatId)
            ui {
                // A slower answer for a float no longer selected must not land.
                if (extension.floatId != floatId) return@ui this
                copy(
                    extension = extension.copy(
                        rows = (rows as? ZillitResult.Success)?.data ?: emptyList(),
                        loading = false,
                        failed = rows is ZillitResult.Failure,
                    ),
                )
            }
        }
    }

    /**
     * Amount and reason are both required (ZL-20808, `TopUpExtensionPanel.jsx:129-146`);
     * the float's currency is never sent — the server reads it from the float.
     */
    private fun submitRequest() {
        val draft = host.state.fundsUi.request ?: return
        val floatId = host.state.fundsUi.extension.floatId ?: return
        val amount = draft.amount.trim().toDoubleOrNull()
        if (amount == null || amount <= 0) return host.refuse(str(S.desktop_card_amount_greater_than_zero))
        val reason = draft.reason.trim()
        if (reason.isEmpty()) return host.refuse(str(S.desktop_a_reason_is_required))
        host.act(
            str(S.desktop_card_topup_requested),
            onSuccess = { copy(fundsUi = fundsUi.copy(request = null)) },
        ) { host.repository.requestFloatTopUp(floatId, amount, reason) }
    }

    // -- the accountant's top-ups --------------------------------------------------

    private fun openPartial(topUpId: String) {
        val topUp = host.state.topUps.firstOrNull { it.id == topUpId } ?: return
        ui { copy(partial = PartialTopUpDraft(topUpId = topUpId, amount = plain(topUp.amount))) }
    }

    private fun submitPartial() {
        val draft = host.state.fundsUi.partial ?: return
        if (draft.note.isBlank()) return
        val amount = draft.amount.trim().toDoubleOrNull()
        if (amount == null || amount <= 0) return host.refuse(str(S.ah_partial_amount_required))
        floats.partialTopUp(draft.topUpId, amount, draft.note.trim())
    }

    // -- fund requests ---------------------------------------------------------

    private fun duplicate(requestId: String) {
        val funds = host.state.funds ?: return
        val row = funds.requests.firstOrNull { it.id == requestId } ?: return
        val amount = if (row.amount > 0) plain(row.amount) else ""
        host.update { copy(funds = funds.copy(fundAccount = row.fundAccount, amount = amount)) }
    }

    private fun ui(reducer: FundsUiState.() -> FundsUiState) = host.update { copy(fundsUi = fundsUi.reducer()) }

    companion object {
        /** Floats a top-up can be asked on — cash in hand (`SUBMITTABLE_FLOAT_STATUSES`). */
        fun toppable(floats: List<CashFloat>): List<CashFloat> = floats.filter { it.status.isSubmittable }

        private fun pick(floats: List<CashFloat>, current: String?): String? =
            floats.firstOrNull { it.id == current }?.id ?: floats.firstOrNull()?.id

        /** `150`, `12.5` — an amount as a person would type it. */
        fun plain(amount: Double): String =
            if (amount == amount.toLong().toDouble()) {
                amount.toLong().toString()
            } else {
                Money.group(amount, 2).replace(",", "")
            }
    }
}
