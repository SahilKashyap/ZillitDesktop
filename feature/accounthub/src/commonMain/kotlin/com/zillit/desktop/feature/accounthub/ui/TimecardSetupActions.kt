package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * Time Card Entry Setup — the web's `TimecardSetupDetail`.
 *
 * The control model and hybrid default are read from the payroll service's
 * config when the modal opens, and written back with the cadence it carried.
 * Nothing is editable until a read has landed: a modal on defaults is one Save
 * away from overwriting the stored configuration.
 */
internal class TimecardSetupActions(private val vm: AccountHubViewModel) {

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.EditTimecardSetup -> vm.update {
                copy(setup = setup.copy(timecardSetup = setup.timecardSetup.edit(event.value)))
            }
            else -> return false
        }
        return true
    }

    /** Reads the config; the shell shows its loading and error states around it. */
    fun read() {
        vm.runResult(vm.repo::timecardSetup, { value ->
            vm.update {
                val open = setup.modal?.takeIf { it.modal == SetupModal.TimeCards }
                copy(
                    setup = setup.copy(
                        timecardSetup = SectionEdit(value),
                        modal = open?.copy(loading = false, loadError = null) ?: setup.modal,
                    ),
                )
            }
        }, { error ->
            vm.update {
                val open = setup.modal?.takeIf { it.modal == SetupModal.TimeCards }
                val failed = open?.copy(loading = false, loadError = error.localised()) ?: setup.modal
                copy(setup = setup.copy(modal = failed))
            }
        })
    }

    fun revert() = vm.update { copy(setup = setup.copy(timecardSetup = setup.timecardSetup.reverted())) }

    fun save() {
        val section = vm.setupState.setup.timecardSetup
        if (!section.dirty || section.saving || !vm.mayEdit()) return
        vm.update { copy(setup = setup.copy(timecardSetup = section.copy(saving = true))) }
        vm.runResult({ vm.repo.saveTimecardSetup(section.edited) }, { saved ->
            vm.update {
                copy(setup = setup.copy(timecardSetup = SectionEdit(saved)), notice = str(S.desktop_hub_tc_saved))
            }
        }, { error ->
            vm.update { copy(setup = setup.copy(timecardSetup = setup.timecardSetup.copy(saving = false))) }
            vm.report(error)
        })
    }
}
