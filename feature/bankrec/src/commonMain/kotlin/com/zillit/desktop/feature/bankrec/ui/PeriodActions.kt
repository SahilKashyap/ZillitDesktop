package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.StatementUploader

/**
 * Bringing a statement in, and taking a period back out.
 *
 * The delete is the most destructive act in the module: a period takes its
 * transactions, exceptions, fraud alerts and FX variances with it, and
 * un-matches every ledger entry that referenced them. The server refuses one
 * that has been signed off; this screen says so before the server has to.
 */
internal class PeriodActions(
    private val vm: BankRecViewModel,
    private val uploader: StatementUploader?,
) {

    private val state: ImportState get() = vm.ui.import

    private fun edit(reducer: ImportState.() -> ImportState) =
        vm.update { copy(import = import.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            BankRecEvent.ComposeImport -> edit {
                ImportState(
                    open = true,
                    bankAccountId = vm.ui.bankAccounts.firstOrNull()?.id.orEmpty(),
                    periodId = vm.ui.currentPeriod?.id.orEmpty(),
                )
            }

            is BankRecEvent.EditImport ->
                edit { copy(bankAccountId = event.bankAccountId, periodId = event.periodId) }

            BankRecEvent.DismissImport -> edit { ImportState() }
            BankRecEvent.ChooseStatement -> importStatement()
            is BankRecEvent.AskDeletePeriods -> askDelete(event.periods)
            BankRecEvent.DismissDeletePeriods -> vm.update { copy(deleting = emptyList()) }
            BankRecEvent.ConfirmDeletePeriods -> confirmDelete()
            else -> return false
        }
        return true
    }

    /**
     * Picks the file, puts it in storage, then hands the service the pointer.
     *
     * The service takes no upload of its own: it is given somewhere to read
     * the statement from, which is why the host does the storing.
     */
    private fun importStatement() {
        val source = uploader ?: return vm.refuse("This installation cannot upload files.")
        val bankAccountId = state.bankAccountId
        val periodId = state.periodId
        edit { copy(uploading = true) }
        vm.launchWork {
            when (val chosen = source.upload()) {
                is ZillitResult.Failure -> {
                    edit { copy(uploading = false) }
                    vm.report(chosen.error)
                }

                is ZillitResult.Success -> {
                    val upload = chosen.data
                    if (upload == null) {
                        // A cancelled picker, which is not a failure.
                        edit { copy(uploading = false) }
                        return@launchWork
                    }
                    edit { copy(fileName = upload.fileName) }
                    vm.runResult(
                        { vm.repo.importStatement(upload, bankAccountId, periodId) },
                        {
                            edit { ImportState() }
                            vm.notify("${upload.fileName} imported.")
                            vm.loadPeriods()
                            vm.reload(vm.ui.tab, force = true)
                        },
                        { error ->
                            edit { copy(uploading = false) }
                            vm.report(error)
                        },
                    )
                }
            }
        }
    }

    private fun askDelete(periods: List<BankPeriod>) {
        val deletable = periods.filter { it.isOpen }
        if (deletable.isEmpty()) {
            return vm.refuse("A signed-off period cannot be deleted.")
        }
        vm.update { copy(deleting = deletable) }
    }

    private fun confirmDelete() {
        val periods = vm.ui.deleting
        if (periods.isEmpty()) return
        vm.update { copy(deleting = emptyList()) }
        vm.runResult({ vm.repo.deletePeriods(periods.map { it.id }) }, {
            vm.notify(
                if (periods.size == 1) {
                    "Period deleted, with everything the reconciliation produced for it."
                } else {
                    "${periods.size} periods deleted, with everything the reconciliation produced for them."
                },
            )
            vm.loadPeriods()
            vm.reload(vm.ui.tab, force = true)
        }, vm::report)
    }
}
