package com.zillit.desktop.feature.purchaseorder.cabinet

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.purchaseorder.domain.PoAttachment
import com.zillit.desktop.feature.purchaseorder.domain.PoProjectSettings
import com.zillit.desktop.feature.purchaseorder.domain.PoSettingsPeople
import com.zillit.desktop.feature.purchaseorder.domain.PoSortDirection
import com.zillit.desktop.feature.purchaseorder.domain.PoStatus
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.domain.PurchaseOrderRepository
import com.zillit.desktop.feature.purchaseorder.ui.PoDestination
import com.zillit.desktop.feature.purchaseorder.ui.PoEvent
import com.zillit.desktop.feature.purchaseorder.ui.PoUiState
import com.zillit.desktop.feature.purchaseorder.ui.orderById
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The File Cabinet's purchase-order list, as a state holder.
 *
 * Not a second [com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel]:
 * that class is the tool's whole write surface (forms, approvals, posting) and
 * an archive view should not carry a route to any of it. This holds the same
 * [PoUiState] the tool's rows and detail draw from, with `readOnly` set, and
 * answers only the handful of events a read-only list can raise — everything
 * else is ignored rather than refused, so a stray control cannot write.
 *
 * ## What the web's cabinet list hides
 *
 * Drafts and pending orders (`HIDDEN_STATUSES` in `FileCabinetPOList.jsx`).
 * Dropped here after the fetch, so the Pending chip that is missing from the
 * strip cannot be recovered by any filter.
 */
internal class PoCabinetModel(
    private val repository: PurchaseOrderRepository,
    private val people: PoSettingsPeople?,
    private val projectSettings: PoProjectSettings?,
    private val openAttachment: (PoAttachment) -> Unit,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(
        PoUiState(
            // Nobody in particular: the archive is not scoped to a person, and a
            // blank id is what keeps "Me" and every ownership check from firing.
            viewer = PoViewer(userId = "", departmentIdentifier = null, designationIdentifier = null),
            destination = PoDestination.AllPos,
            loading = true,
            readOnly = true,
        ),
    )
    val state: StateFlow<PoUiState> = mutableState.asStateFlow()

    private val mutableFailure = MutableStateFlow<String?>(null)

    /** The last thing that went wrong that the list itself could not show (a PDF that would not render). */
    val failure: StateFlow<String?> = mutableFailure.asStateFlow()

    /** Reads the archive, and the names its rows are drawn with. */
    fun start() {
        reload()
        scope.launch { loadVendors() }
        scope.launch { loadPeople() }
    }

    fun clearFailure() {
        mutableFailure.value = null
    }

    @Suppress("CyclomaticComplexMethod") // One branch per control; a flat table reads better.
    fun onEvent(event: PoEvent) {
        when (event) {
            PoEvent.Refresh -> reload()
            is PoEvent.Filter -> mutableState.update { it.copy(quickFilter = event.filter) }
            is PoEvent.FilterDepartment -> mutableState.update { it.copy(departmentFilter = event.departmentId) }
            is PoEvent.SortColumn -> mutableState.update { it.sortedBy(event) }
            is PoEvent.OpenOrder -> openOrder(event.id)
            PoEvent.CloseOrder -> mutableState.update { it.copy(detail = null, history = emptyList()) }
            is PoEvent.OpenAttachment -> openAttachment(event.attachment)
            is PoEvent.ViewPdf -> viewPdf(event.id)
            else -> Unit
        }
    }

    private fun PoUiState.sortedBy(event: PoEvent.SortColumn): PoUiState =
        if (sortColumn == event.column) {
            copy(
                sortDirection = if (sortDirection == PoSortDirection.Ascending) {
                    PoSortDirection.Descending
                } else {
                    PoSortDirection.Ascending
                },
            )
        } else {
            copy(sortColumn = event.column, sortDirection = PoSortDirection.Ascending)
        }

    private fun reload() {
        mutableState.update { it.copy(loading = true, error = null) }
        scope.launch {
            when (val answer = repository.cabinetOrders()) {
                is ZillitResult.Success -> mutableState.update {
                    it.copy(loading = false, orders = answer.data.filterNot { order -> order.status in HIDDEN })
                }

                is ZillitResult.Failure -> mutableState.update { it.copy(loading = false, error = answer.error) }
            }
        }
    }

    /**
     * Names for the Vendor column. A failure leaves it blank rather than hiding
     * the orders, as the web's `.catch(() => ({ data: [] }))` does.
     */
    private suspend fun loadVendors() {
        repository.vendors().getOrNull()?.let { rows -> mutableState.update { it.copy(vendors = rows) } }
    }

    private suspend fun loadPeople() {
        val roster = people
        if (roster != null) {
            runCatching { roster.everyone() }.getOrNull()
                ?.let { rows -> mutableState.update { it.copy(people = rows) } }
        }
        val named = runCatching { people?.departments() }.getOrNull().orEmpty()
            .ifEmpty { runCatching { projectSettings?.departments() }.getOrNull().orEmpty() }
        mutableState.update { it.copy(departments = named) }
    }

    /**
     * Opens one order. The row stands in at once; the record replaces it when
     * the read lands. A read that fails leaves the row — an archived order the
     * server will not serve singly is still a readable row, and the web's
     * modal never refetches at all.
     */
    private fun openOrder(id: String) {
        val row = mutableState.value.orderById(id)
        mutableState.update { it.copy(detail = row, detailLoading = true, history = emptyList()) }
        scope.launch {
            val fresh = repository.order(id).getOrNull()
            mutableState.update { current ->
                if (current.detail?.id != id) current else current.copy(detail = fresh ?: row, detailLoading = false)
            }
        }
        scope.launch {
            repository.history(id).getOrNull()?.let { entries ->
                mutableState.update { current ->
                    if (current.detail?.id == id) current.copy(history = entries) else current
                }
            }
        }
    }

    private fun viewPdf(id: String) {
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            when (val answer = repository.pdf(id, projectName = "", companyName = "")) {
                is ZillitResult.Success -> {
                    mutableState.update { it.copy(busy = false) }
                    openAttachment(answer.data)
                }

                is ZillitResult.Failure -> {
                    mutableState.update { it.copy(busy = false) }
                    mutableFailure.value = answer.error.localised()
                }
            }
        }
    }

    private companion object {
        /** The statuses `FileCabinetPOList.jsx` never shows. */
        val HIDDEN = setOf(PoStatus.Draft, PoStatus.AwaitingApproval)
    }
}
