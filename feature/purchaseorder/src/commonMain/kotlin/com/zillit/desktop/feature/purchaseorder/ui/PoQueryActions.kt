package com.zillit.desktop.feature.purchaseorder.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrder
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository

/**
 * An order's query thread — the web's `QueryPanel` on `purchase_order`.
 *
 * Who may open it is the web's rule, restated in [mayQuery] and checked in the
 * handler as well as on the button: an accountant's console action or the
 * order's own raiser, never from the department Approval Queue (ZL-20913 — an
 * approver opens an order there to decide it, not to query it).
 */
internal class PoQueryActions(
    private val vm: PurchaseOrderViewModel,
    private val repository: PurchaseOrderRepository,
) {
    fun onEvent(event: PoEvent) {
        when (event) {
            is PoEvent.OpenQuery -> open(event.orderId)
            is PoEvent.EditQuery -> vm.update { copy(query = query?.copy(draft = event.text)) }
            PoEvent.SendQuery -> send()
            PoEvent.CloseQuery -> vm.update { copy(query = null) }
            else -> Unit
        }
    }

    private fun open(orderId: String) {
        val order = vm.ui.orderById(orderId) ?: return
        if (!vm.ui.mayQuery(order)) {
            vm.fail(str(S.desktop_po_no_rights_on_project))
            return
        }
        vm.update {
            copy(query = PoQueryState(orderId = orderId, title = order.number.ifBlank { str(S.purchase_order) }))
        }
        vm.launchWork {
            val answer = repository.queryThread(orderId)
            if (vm.ui.query?.orderId != orderId) return@launchWork
            when (answer) {
                is ZillitResult.Success -> vm.update {
                    copy(query = query?.copy(thread = answer.data, loading = false))
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(query = query?.copy(loading = false)) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }

    /** The first message opens the thread; every later one is appended to it. */
    private fun send() {
        val query = vm.ui.query ?: return
        val text = query.draft.trim()
        if (text.isEmpty() || query.sending) return
        vm.update { copy(query = query.copy(sending = true)) }
        vm.launchWork {
            when (val answer = repository.sendQuery(query.orderId, query.thread?.id, text)) {
                is ZillitResult.Success -> vm.update {
                    copy(
                        query = this.query?.copy(
                            thread = answer.data ?: this.query.thread,
                            draft = "",
                            sending = false,
                        ),
                    )
                }

                is ZillitResult.Failure -> {
                    vm.update { copy(query = this.query?.copy(sending = false)) }
                    vm.fail(answer.error.localised())
                }
            }
        }
    }
}

/**
 * Whether the viewer may open [order]'s query thread — the web's
 * `allowQuery && (isAccountant || isCreator)`, where `isAccountant` is the
 * senior designation and `allowQuery` is false on the Approval Queue.
 */
internal fun PoUiState.mayQuery(order: PurchaseOrder): Boolean {
    if (order.isLocalOnly || destination == PoDestination.ApprovalQueue) return false
    val isCreator = order.raisedBy != null && order.raisedBy == viewer.userId
    // The processing page offers Query to whoever is processing — the web's
    // POEntry shows it when the coding does not balance.
    val processing = entry?.orderId == order.id
    return viewer.isSeniorAccountant || isCreator || processing
}
