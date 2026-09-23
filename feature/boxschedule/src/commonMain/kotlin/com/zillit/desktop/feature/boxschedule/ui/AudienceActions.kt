package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.AudienceMode
import com.zillit.desktop.feature.boxschedule.domain.GuestEmails
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * An entry's audience — `SelectInviteesModal` — and its external guests —
 * calendar V3's `ExternalEmailModal`.
 */
internal class AudienceActions(private val vm: BoxScheduleViewModel) {

    /** True when the event was the picker's or the guest list's. */
    @Suppress("CyclomaticComplexMethod") // Event fan-out: one line per control.
    fun onEvent(event: EntryEvent): Boolean {
        when (event) {
            EntryEvent.OpenAudience -> open()
            is EntryEvent.AudienceTab -> picker { copy(tab = event.mode) }
            is EntryEvent.AudienceToggleUser -> picker {
                setUsers(if (event.id in userIds) userIds - event.id else userIds + event.id)
            }
            is EntryEvent.AudienceSetUsers -> picker { setUsers(event.ids) }
            is EntryEvent.AudienceToggleDepartment -> picker {
                setDepartments(if (event.id in departmentIds) departmentIds - event.id else departmentIds + event.id)
            }
            is EntryEvent.AudienceSetDepartments -> picker { setDepartments(event.ids) }
            is EntryEvent.AudiencePreset -> picker { copy(presetId = event.id).keepingOnly(AudienceMode.Presets) }
            EntryEvent.AudienceToggleAllDepartments -> picker {
                val on = !allDepartments
                copy(allDepartments = on).let { if (on) it.keepingOnly(AudienceMode.AllDepartments) else it }
            }
            EntryEvent.AudienceToggleSelf -> picker {
                val on = !self
                copy(self = on).let { if (on) it.keepingOnly(AudienceMode.Self) else it }
            }
            is EntryEvent.AudienceQuery -> picker {
                when (tab) {
                    AudienceMode.Departments -> copy(departmentQuery = event.text)
                    AudienceMode.Presets -> copy(presetQuery = event.text)
                    else -> copy(userQuery = event.text)
                }
            }
            is EntryEvent.AudienceMembers -> picker { copy(membersOf = event.presetId) }
            EntryEvent.AudienceDone -> done()
            EntryEvent.CloseAudience -> form { copy(audiencePicker = null) }
            EntryEvent.OpenGuests -> form { copy(guestsDialog = GuestsDialog(emails = guests)) }
            is EntryEvent.GuestDraft -> guests { copy(draft = event.text, error = null) }
            EntryEvent.AddGuest -> guests { add() }
            is EntryEvent.RemoveGuest -> guests { copy(emails = emails - event.mail) }
            EntryEvent.GuestsDone -> form {
                val dialog = guestsDialog ?: return@form this
                copy(guests = dialog.emails, guestsDialog = null, errors = errors - EntryField.Audience)
            }
            EntryEvent.CloseGuests -> form { copy(guestsDialog = null) }
            else -> return false
        }
        return true
    }

    private fun form(change: EntryForm.() -> EntryForm) = vm.updateOverlays { copy(entryForm = entryForm?.change()) }

    private fun picker(change: AudiencePicker.() -> AudiencePicker) = form {
        copy(audiencePicker = audiencePicker?.change())
    }

    private fun guests(change: GuestsDialog.() -> GuestsDialog) = form { copy(guestsDialog = guestsDialog?.change()) }

    /** Picking users clears every other tab; clearing them leaves the rest alone. */
    private fun AudiencePicker.setUsers(ids: List<String>): AudiencePicker =
        copy(userIds = ids.distinct()).let { if (ids.isNotEmpty()) it.keepingOnly(AudienceMode.Users) else it }

    private fun AudiencePicker.setDepartments(ids: List<String>): AudiencePicker =
        copy(departmentIds = ids.distinct()).let {
            if (ids.isNotEmpty()) it.keepingOnly(AudienceMode.Departments) else it
        }

    /**
     * Opens on the entry's own tab, and reads the departments and the saved
     * presets fresh each time — a preset made a moment ago must be offered.
     */
    private fun open() {
        if (vm.currentState.overlays.entryForm == null) return
        form {
            copy(audiencePicker = AudiencePicker.from(audience).copy(departmentsLoading = true, presetsLoading = true))
        }
        vm.work {
            coroutineScope {
                val departments = async { vm.host.directory.departments() }
                val presets = async { vm.repo.presets() }
                val departmentList = departments.await()
                val presetList = presets.await()
                picker {
                    copy(
                        departments = (departmentList as? ZillitResult.Success)?.data.orEmpty(),
                        departmentsLoading = false,
                        presets = (presetList as? ZillitResult.Success)?.data.orEmpty(),
                        presetsLoading = false,
                    )
                }
                if (presetList is ZillitResult.Failure) vm.notice(str(S.bs_preset_failed))
                if (departmentList is ZillitResult.Failure) vm.notice(departmentList.error.localised())
            }
        }
    }

    private fun done() {
        val picker = vm.currentState.overlays.entryForm?.audiencePicker ?: return
        if (picker.doneCount(vm.currentState.people.size) == 0) return
        form { copy(audience = picker.result(), audiencePicker = null, errors = errors - EntryField.Audience) }
    }

    private fun GuestsDialog.add(): GuestsDialog {
        val email = draft.trim().lowercase()
        return when {
            email.isEmpty() -> this
            !GuestEmails.isValid(email) -> copy(error = str(S.ah_err_email_invalid))
            email in emails -> copy(error = str(S.desktop_bs_email_already_added))
            else -> copy(emails = emails + email, draft = "", error = null)
        }
    }
}
