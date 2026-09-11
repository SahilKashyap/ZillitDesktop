package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.NewVendor
import com.zillit.desktop.feature.accounthub.domain.Vendor
import com.zillit.desktop.feature.accounthub.domain.validationError

/**
 * The vendor register's actions.
 *
 * Its own collaborator for the reason [SetupSections] is: the view model had
 * grown past what one class should hold, and the register is the most
 * self-contained slice of it — a list, a form, and four writes that all end in
 * the same reload.
 */
internal class VendorActions(private val vm: AccountHubViewModel) {

    fun load() {
        vm.update { copy(vendors = vendors.copy(loading = true)) }
        vm.runResult({ vm.repo.vendors(vm.setupState.vendors.search) }, { rows ->
            vm.update { copy(vendors = vendors.copy(rows = rows, loading = false)) }
        }, { error ->
            vm.update { copy(vendors = vendors.copy(loading = false)) }
            vm.report(error)
        })
    }

    @Suppress("CyclomaticComplexMethod") // One branch per action.
    fun onEvent(event: AccountHubEvent) {
        when (event) {
            is AccountHubEvent.SearchVendors -> {
                vm.update { copy(vendors = vendors.copy(search = event.term)) }
                vm.debounced(::load)
            }
            is AccountHubEvent.FilterVendors -> vm.update {
                // No refetch: the rows are already here and the flag is on them.
                copy(vendors = vendors.copy(filter = event.filter))
            }
            is AccountHubEvent.SelectVendor -> selectVendor(event.id)
            is AccountHubEvent.ComposeVendor -> composeVendor(event)
            is AccountHubEvent.UpdateVendorDraft -> vm.update {
                copy(vendors = vendors.copy(form = vendors.form?.copy(draft = event.draft)))
            }
            AccountHubEvent.DismissVendorForm -> vm.update { copy(vendors = vendors.copy(form = null)) }
            AccountHubEvent.SaveVendor -> saveVendor()
            AccountHubEvent.SaveAndVerifyVendor -> saveVendor(thenVerify = true)
            is AccountHubEvent.VerifyVendor -> verifyVendor(event.id)
            is AccountHubEvent.DeleteVendor -> deleteVendor(event.id)
            else -> vm.onApprovalEvent(event)
        }
    }

    private fun selectVendor(id: String?) {
        vm.update { copy(vendors = vendors.copy(selectedId = id, history = emptyList())) }
        val vendorId = id ?: return
        vm.update { copy(vendors = vendors.copy(historyLoading = true)) }
        vm.runResult({ vm.repo.vendorHistory(vendorId) }, { rows ->
            vm.update { copy(vendors = vendors.copy(history = rows, historyLoading = false)) }
        }, { error ->
            vm.update { copy(vendors = vendors.copy(historyLoading = false)) }
            vm.report(error)
        })
    }

    private fun composeVendor(event: AccountHubEvent.ComposeVendor) {
        if (!vm.mayEdit()) return
        val editing = event.editing
        vm.update {
            copy(
                vendors = vendors.copy(
                    form = VendorForm(
                        editingId = editing?.id,
                        draft = editing?.let(NewVendor::from) ?: NewVendor(),
                    ),
                ),
            )
        }
    }

    /**
     * Saves the open form, and on [thenVerify] verifies the same vendor.
     *
     * The verify is a second call the web makes too — there is no combined
     * endpoint — and it runs only after the save succeeds, so a rejected edit
     * cannot leave a vendor verified against details that were never stored.
     */
    private fun saveVendor(thenVerify: Boolean = false) {
        val form = vm.setupState.vendors.form ?: return
        val problem = form.draft.validationError()
        if (problem != null) {
            vm.sendSideEffect(AccountHubEffect.Failed(problem))
            return
        }
        if (thenVerify && !vm.mayActAsAccountant()) return
        vm.update { copy(vendors = vendors.copy(form = form.copy(saving = true))) }
        vm.runResult(
            { form.editingId?.let { vm.repo.updateVendor(it, form.draft) } ?: vm.repo.createVendor(form.draft) },
            {
                val id = form.editingId
                if (thenVerify && id != null) {
                    verifyAfterSave(id)
                } else {
                    vm.update { copy(vendors = vendors.copy(form = null), notice = "Vendor saved.") }
                    load()
                }
            },
            { error ->
                vm.update { copy(vendors = vendors.copy(form = form.copy(saving = false))) }
                vm.report(error)
            },
        )
    }

    /**
     * The verify half of Save & Verify.
     *
     * A failure here closes the form and reports: the edit *did* save, so
     * leaving the form open would invite the user to save it twice.
     */
    private fun verifyAfterSave(id: String) {
        vm.runResult({ vm.repo.verifyVendor(id) }, {
            vm.update { copy(vendors = vendors.copy(form = null), notice = "Vendor saved and verified.") }
            load()
        }, { error ->
            vm.update { copy(vendors = vendors.copy(form = null), notice = "Vendor saved.") }
            load()
            vm.report(error)
        })
    }

    private fun verifyVendor(id: String) {
        // Not `requireEdit`: verification is one of the two operations the
        // service reserves for the accounts department.
        if (!vm.mayActAsAccountant()) return
        vm.runResult({ vm.repo.verifyVendor(id) }, {
            vm.update { copy(notice = "Vendor verified.") }
            load()
        }, vm::report)
    }

    private fun deleteVendor(id: String) {
        if (!vm.mayEdit()) return
        vm.runResult({ vm.repo.deleteVendor(id) }, {
            vm.update { copy(vendors = vendors.copy(selectedId = null), notice = "Vendor removed.") }
            load()
        }, vm::report)
    }
}
