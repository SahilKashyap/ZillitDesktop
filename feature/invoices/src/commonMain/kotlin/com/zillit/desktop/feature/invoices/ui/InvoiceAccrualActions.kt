package com.zillit.desktop.feature.invoices.ui

import com.zillit.desktop.core.common.ZillitResult

/**
 * Accruals — the web's `AccrualsPage`: the list, Regenerate All, the Sort and
 * Dept selects, and the detail a row opens (`GET /invoices/accruals/:id`).
 *
 * Regenerate is silent either way, as the web's `handleRegenerate` is: it
 * recomputes and reads the list again, and a failure only leaves the list
 * as it was.
 */
internal class InvoiceAccrualActions(private val vm: InvoicesViewModel) {

    /** True when [event] was this page's own. */
    fun onEvent(event: InvoicesEvent): Boolean {
        when (event) {
            InvoicesEvent.RegenerateAccruals -> regenerate()
            is AccrualEvent.SelectSort -> ui { copy(sort = event.sort) }
            is AccrualEvent.SelectDepartment -> ui { copy(departmentId = event.departmentId) }
            is AccrualEvent.Open -> open(event.accrualId)
            AccrualEvent.Close -> ui { copy(detailId = null, detail = null, detailLoading = false) }
            else -> return false
        }
        return true
    }

    /** The list, and the department directory the Dept select offers. */
    fun load() {
        vm.update { copy(loading = false, accrualsLoading = true) }
        vm.fillDirectories()
        vm.run {
            // A failed read empties the list without a banner — the web's `catch { setAccruals([]) }`.
            val rows = (vm.repo.accruals() as? ZillitResult.Success)?.data.orEmpty()
            vm.update { copy(accrualsLoading = false, accruals = rows) }
        }
    }

    private fun regenerate() {
        if (vm.state.value.busy) return
        vm.update { copy(busy = true) }
        vm.run {
            val result = vm.repo.regenerateAccruals()
            vm.update { copy(busy = false) }
            if (result is ZillitResult.Success) load()
        }
    }

    /** "Loading…", then the record — or "Accrual not found" when the read fails. */
    private fun open(id: String) {
        ui { copy(detailId = id, detail = null, detailLoading = true) }
        vm.run {
            val detail = (vm.repo.accrualDetail(id) as? ZillitResult.Success)?.data
            ui { if (detailId == id) copy(detail = detail, detailLoading = false) else this }
        }
    }

    private fun ui(change: AccrualsUi.() -> AccrualsUi) = vm.update { copy(accrualsPage = accrualsPage.change()) }
}
