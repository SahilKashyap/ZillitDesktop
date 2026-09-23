package com.zillit.desktop.feature.settings.account

import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.approvals.ApprovalPresets
import com.zillit.desktop.feature.settings.approvals.CrewDepartment
import com.zillit.desktop.feature.settings.approvals.CrewRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Who this person is on this production, as the form should open.
 *
 * Supplied rather than fetched: the session already holds every field, and a
 * profile form that re-reads the profile is a form that can show empty boxes
 * over values the user is about to overwrite.
 */
data class ProfileSeed(
    /** For the profile picture beside the name fields. */
    val userId: String = "",
    val firstName: String = "",
    val lastName: String = "",
    val email: String = "",
    /**
     * Where this person sits, as the profile endpoint reports it.
     *
     * Ids, because the profile carries them. This was built against the crew
     * list first, matching `department` by name — and it silently never
     * matched: `project/users` names a department differently from the
     * department catalogue, so both pickers opened blank on a user who plainly
     * had a department. Found live on dev, 2026-08-12.
     *
     * [designationName] is the untranslated key (`producer_label`) and stays,
     * because the privacy toggle is gated on it rather than on the id.
     */
    val departmentId: String? = null,
    val designationId: String? = null,
    val designationName: String? = null,
    val keepNamePrivate: Boolean = false,
    /** Consent to show the Zillit mailbox address on the crew list; the server's default is ON. */
    val showMailboxInCrewList: Boolean = true,
    /** The Zillit mailbox, when one exists — the consent row is offered only then. */
    val mailboxAddress: String? = null,
    /** A personal production has no crew list to consent to. */
    val isPersonal: Boolean = false,
    val isAdmin: Boolean = false,
    /** Shown on the invite page, and the only thing that page needs. */
    val productionName: String = "",
    val productionCode: String = "",
)

/** The profile form, as the user has it now. */
data class ProfileFormState(
    val firstName: String = "",
    val lastName: String = "",
    val departmentId: String? = null,
    val designationId: String? = null,
    val keepNamePrivate: Boolean = false,
    val showMailboxInCrewList: Boolean = true,
    val mailboxAddress: String? = null,
    val isPersonal: Boolean = false,
    val departments: List<CrewDepartment> = emptyList(),
    val isLoadingDepartments: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    /** Set once the server has taken it, so the page can say which way it went. */
    val outcome: ProfileSaveOutcome? = null,
) {
    val roles: List<CrewRole>
        get() = departments.firstOrNull { it.id == departmentId }?.roles.orEmpty()

    val department: CrewDepartment? get() = departments.firstOrNull { it.id == departmentId }
    val role: CrewRole? get() = roles.firstOrNull { it.id == designationId }

    /**
     * Both names, and a role belonging to the department that is chosen.
     *
     * The last clause is not paranoia. Changing department empties the role —
     * it has to, the old one belongs to the department being left — and a
     * submit in that gap would seat someone in a department under another
     * department's job title. A department with no roles at all is allowed to
     * pass, because there is nothing there to choose.
     */
    val canSave: Boolean
        get() = !isSaving &&
            firstName.isNotBlank() &&
            lastName.isNotBlank() &&
            (roles.isEmpty() || roles.any { it.id == designationId })

    /**
     * Whether the privacy toggle applies to the role currently chosen.
     *
     * Read off the picked role rather than the seed, so choosing "Producer"
     * offers it immediately instead of after a save and a reload.
     */
    fun offersPrivateName(seedKey: String?): Boolean {
        val chosen = role?.name ?: seedKey
        return allowsPrivateName(chosen)
    }


}

data class RecoveryEmailState(
    val email: String = "",
    val isSaving: Boolean = false,
    val error: String? = null,
    val isSaved: Boolean = false,
) {
    /**
     * Checked here rather than by the server.
     *
     * A typo'd recovery address is only discovered on the day it is needed,
     * which is the day the user has no other way in.
     */
    val isValid: Boolean get() = EMAIL.matches(email.trim())

    val canSave: Boolean get() = isValid && !isSaving

    private companion object {
        val EMAIL = Regex("^[^@\\s]+@[^@\\s.]+\\.[^@\\s]+$")
    }
}

data class DevicesState(
    val devices: List<LinkedDevice> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** The device an "are you sure" is currently open for. */
    val confirming: LinkedDevice? = null,
    val unlinkingId: String? = null,
) {
    val hasLoaded: Boolean get() = devices.isNotEmpty() || (!isLoading && error == null)
}

data class LeaveState(
    val isConfirming: Boolean = false,
    val isLeaving: Boolean = false,
    val error: String? = null,
)

data class AccountUiState(
    val seed: ProfileSeed = ProfileSeed(),
    val profile: ProfileFormState = ProfileFormState(),
    val recovery: RecoveryEmailState = RecoveryEmailState(),
    val devices: DevicesState = DevicesState(),
    val leave: LeaveState = LeaveState(),
    /**
     * What just happened, as a toast.
     *
     * State rather than an effect: these pages live in a window that can be
     * torn off and resized, and an effect-driven toast replays or vanishes on
     * recomposition depending on who is collecting. The tools that came before
     * this one all carry the same field.
     */
    val notice: String? = null,
)

sealed interface AccountEvent {
    /** The window arrived on a page. Loads only what that page needs. */
    data class Opened(val page: AccountPage) : AccountEvent

    data class FirstNameChanged(val value: String) : AccountEvent
    data class LastNameChanged(val value: String) : AccountEvent
    data class DepartmentChosen(val id: String) : AccountEvent
    data class RoleChosen(val id: String) : AccountEvent
    data class PrivateNameChanged(val on: Boolean) : AccountEvent
    /** "Show my Zillit mailbox address in the crew list" (ZL-21078). */
    data class MailboxConsentChanged(val on: Boolean) : AccountEvent
    data object SaveProfile : AccountEvent
    /** Puts the form back to what the session says, discarding edits. */
    data object ResetProfile : AccountEvent

    data class RecoveryEmailChanged(val value: String) : AccountEvent
    data object SaveRecoveryEmail : AccountEvent

    data object ReloadDevices : AccountEvent
    data class AskUnlink(val device: LinkedDevice) : AccountEvent
    data object DismissUnlink : AccountEvent
    data object ConfirmUnlink : AccountEvent

    data object AskLeave : AccountEvent
    data object DismissLeave : AccountEvent
    data object ConfirmLeave : AccountEvent

    data object ClearNotice : AccountEvent

    /** Puts the production's invite text on the clipboard. */
    data class CopyInvite(val text: String) : AccountEvent
}

sealed interface AccountEffect {
    /**
     * This device was signed out from its own list.
     *
     * The window cannot stay open on a session that no longer exists, so the
     * frame takes it back to sign-in — the same route the Settings sign-out
     * takes.
     */
    data object SignedOutHere : AccountEffect

    /** Left the production; the frame goes back to the production picker. */
    data object LeftProduction : AccountEffect

    data class CopyToClipboard(val text: String) : AccountEffect
}

/**
 * The five account pages.
 *
 * One view model for all of them because they are one window with a back
 * chevron, and because [seed] — who is signed in — is what four of the five
 * open with. Splitting them would mean four subscriptions to the same session
 * flow and four chances for them to disagree about the user's own name.
 *
 * ## Nothing loads until its page is opened
 *
 * [AccountEvent.Opened] is what fetches. The departments list is two hundred
 * rows on a large production and the device list is a live security question;
 * neither is worth a request from a window that opened on the listing and may
 * never leave it.
 */
class AccountViewModel(
    private val repository: AccountRepository,
    /** Departments and their roles, shared with the approval queues. */
    private val presets: ApprovalPresets = ApprovalPresets { ZillitResult.Success(crewPresetsEmpty()) },
    /**
     * Re-reads the profile after a successful save.
     *
     * The name shows on every message this person has sent, so the rest of the
     * app has to be told rather than left to notice on the next launch.
     */
    private val onProfileChanged: suspend () -> Unit = {},
    /** Drops the production and returns to the picker after leaving. */
    private val onLeftProduction: suspend () -> Unit = {},
    seed: Flow<ProfileSeed> = flowOf(ProfileSeed()),
    initial: AccountUiState = AccountUiState(),
) : ZillitViewModel<AccountUiState, AccountEvent, AccountEffect>(initial) {

    init {
        launch {
            seed.collect { fresh ->
                setState {
                    copy(
                        seed = fresh,
                        // Only reseeds a form nobody is part-way through. The
                        // profile flow re-emits on every crew-list refresh, and
                        // overwriting a half-typed surname each time is how a
                        // form becomes impossible to submit. Compared against
                        // the *previous* seed — `this.seed` — because that is
                        // what the form was last filled from.
                        profile = if (profile.isDirtyAgainst(this.seed)) profile else profile.seeded(fresh),
                    )
                }
            }
        }
    }

    // Exhaustive dispatch over the sealed event set — the branch count is the
    // pattern, not a complexity problem (see SettingsViewModel's onEvent).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: AccountEvent) {
        when (event) {
            is AccountEvent.Opened -> onOpened(event.page)

            is AccountEvent.FirstNameChanged ->
                editProfile { copy(firstName = event.value, error = null, outcome = null) }

            is AccountEvent.LastNameChanged ->
                editProfile { copy(lastName = event.value, error = null, outcome = null) }

            // Changing department empties the role: the old one belongs to the
            // department being left, and submitting it seats someone as a
            // grip in the art department.
            is AccountEvent.DepartmentChosen -> editProfile {
                copy(
                    departmentId = event.id,
                    designationId = null,
                    keepNamePrivate = false,
                    error = null,
                    outcome = null,
                )
            }

            is AccountEvent.RoleChosen -> editProfile {
                val chosen = roles.firstOrNull { it.id == event.id }
                copy(
                    designationId = event.id,
                    // A role that cannot withhold a name must not carry the
                    // flag into the save — the server would keep it set.
                    keepNamePrivate = keepNamePrivate && allowsPrivateName(chosen?.name),
                    error = null,
                    outcome = null,
                )
            }

            is AccountEvent.PrivateNameChanged ->
                editProfile { copy(keepNamePrivate = event.on, outcome = null) }
            is AccountEvent.MailboxConsentChanged ->
                editProfile { copy(showMailboxInCrewList = event.on, outcome = null) }

            AccountEvent.SaveProfile -> saveProfile()
            AccountEvent.ResetProfile -> setState { copy(profile = profile.seeded(seed)) }

            is AccountEvent.RecoveryEmailChanged -> setState {
                copy(recovery = recovery.copy(email = event.value, error = null, isSaved = false))
            }
            AccountEvent.SaveRecoveryEmail -> saveRecoveryEmail()

            AccountEvent.ReloadDevices -> loadDevices()
            is AccountEvent.AskUnlink -> setState { copy(devices = devices.copy(confirming = event.device)) }
            AccountEvent.DismissUnlink -> setState { copy(devices = devices.copy(confirming = null)) }
            AccountEvent.ConfirmUnlink -> unlink()

            AccountEvent.AskLeave -> setState { copy(leave = leave.copy(isConfirming = true, error = null)) }
            AccountEvent.DismissLeave -> setState { copy(leave = leave.copy(isConfirming = false)) }
            AccountEvent.ConfirmLeave -> leaveProduction()

            AccountEvent.ClearNotice -> setState { copy(notice = null) }

            is AccountEvent.CopyInvite -> {
                sendEffect(AccountEffect.CopyToClipboard(event.text))
                announce(str(S.desktop_invite_copied))
            }
        }
    }

    /**
     * Loads what the page that just opened needs, and nothing else.
     *
     * The profile form reseeds every time it is opened rather than keeping the
     * last visit's edits: someone who left mid-change and came back expects the
     * page as it is, not as they abandoned it.
     */
    private fun onOpened(page: AccountPage) {
        when (page) {
            AccountPage.EditProfile -> {
                setState { copy(profile = profile.seeded(seed)) }
                loadDepartments()
            }

            AccountPage.RecoveryEmail -> Unit
            AccountPage.LinkedDevices -> loadDevices()
            AccountPage.InviteCrew -> Unit
        }
    }

    /**
     * The department list, once per window.
     *
     * Not refetched on every visit: departments change when an admin adds one,
     * which is rare enough that a stale list costs less than a request on every
     * back-and-forth through the form.
     */
    private fun loadDepartments() {
        if (currentState.profile.departments.isNotEmpty() ||
            currentState.profile.isLoadingDepartments
        ) {
            return
        }

        setState { copy(profile = profile.copy(isLoadingDepartments = true)) }
        launch {
            val loaded = presets.load().getOrNull()?.departments.orEmpty()
            setState { copy(profile = profile.copy(departments = loaded, isLoadingDepartments = false)) }
        }
    }

    private fun saveProfile() {
        val form = currentState.profile
        if (!form.canSave) return

        setState { copy(profile = profile.copy(isSaving = true, error = null, outcome = null)) }
        launch {
            val edit = ProfileEdit(
                firstName = form.firstName.trim(),
                lastName = form.lastName.trim(),
                departmentId = form.departmentId,
                designationId = form.designationId,
                keepNamePrivate = form.keepNamePrivate,
            )

            when (val saved = repository.saveProfile(edit, currentState.seed.isAdmin)) {
                is ZillitResult.Success -> {
                    setState {
                        copy(profile = profile.copy(isSaving = false, outcome = saved.data))
                    }
                    announce(saved.data.announcement)
                    // Only an admin's edit is live; a request changes nothing
                    // yet, and reloading would blank the form back to the old
                    // values as though the change had been refused.
                    if (saved.data == ProfileSaveOutcome.Saved) onProfileChanged()
                }

                is ZillitResult.Failure -> setState {
                    copy(
                        profile = profile.copy(
                            isSaving = false,
                            error = str(S.desktop_could_not_save_profile, saved.error.localised()),
                        ),
                    )
                }
            }
        }
    }

    private fun saveRecoveryEmail() {
        val state = currentState.recovery
        if (!state.canSave) return

        setState { copy(recovery = recovery.copy(isSaving = true, error = null)) }
        launch {
            when (val saved = repository.setRecoveryEmail(state.email)) {
                is ZillitResult.Success -> {
                    setState { copy(recovery = recovery.copy(isSaving = false, isSaved = true)) }
                    announce(str(S.desktop_recovery_email_saved))
                }

                is ZillitResult.Failure -> setState {
                    copy(
                        recovery = recovery.copy(
                            isSaving = false,
                            error = str(S.desktop_could_not_save_address, saved.error.localised()),
                        ),
                    )
                }
            }
        }
    }

    private fun loadDevices() {
        if (currentState.devices.isLoading) return

        setState { copy(devices = devices.copy(isLoading = true, error = null)) }
        launch {
            when (val loaded = repository.linkedDevices()) {
                is ZillitResult.Success -> setState {
                    copy(devices = devices.copy(devices = loaded.data, isLoading = false))
                }

                is ZillitResult.Failure -> setState {
                    copy(devices = devices.copy(isLoading = false, error = loaded.error.localised()))
                }
            }
        }
    }

    /**
     * Signs a device out.
     *
     * The row is dropped from the list on success rather than the list
     * refetched: the server has been seen to answer the next `device/linked`
     * with the row still present for a second or two, which reads as the
     * unlink having failed.
     */
    private fun unlink() {
        val device = currentState.devices.confirming ?: return

        setState { copy(devices = devices.copy(confirming = null, unlinkingId = device.id)) }
        launch {
            when (val done = repository.unlinkDevice(device.id)) {
                is ZillitResult.Success -> {
                    setState {
                        copy(
                            devices = devices.copy(
                                devices = devices.devices.filterNot { it.id == device.id },
                                unlinkingId = null,
                            ),
                        )
                    }
                    if (device.isThisDevice) {
                        sendEffect(AccountEffect.SignedOutHere)
                    } else {
                        announce(str(S.desktop_device_signed_out, device.displayName))
                    }
                }

                is ZillitResult.Failure -> setState {
                    copy(
                        devices = devices.copy(
                            unlinkingId = null,
                            error = str(S.desktop_could_not_sign_device_out, done.error.localised()),
                        ),
                    )
                }
            }
        }
    }

    private fun leaveProduction() {
        if (currentState.leave.isLeaving) return

        setState { copy(leave = leave.copy(isConfirming = false, isLeaving = true, error = null)) }
        launch {
            when (val left = repository.leaveProduction()) {
                is ZillitResult.Success -> {
                    setState { copy(leave = LeaveState()) }
                    onLeftProduction()
                    sendEffect(AccountEffect.LeftProduction)
                }

                is ZillitResult.Failure -> setState {
                    copy(
                        leave = leave.copy(
                            isLeaving = false,
                            error = str(S.desktop_could_not_leave_project, left.error.localised()),
                        ),
                    )
                }
            }
        }
    }

    private fun editProfile(edit: ProfileFormState.() -> ProfileFormState) {
        setState { copy(profile = profile.edit()) }
    }

    private fun announce(message: String) = setState { copy(notice = message) }
}

/** What the toast says, which differs by which of the two paths the save took. */
private val ProfileSaveOutcome.announcement: String
    get() = when (this) {
        ProfileSaveOutcome.Saved -> str(S.desktop_profile_saved)
        ProfileSaveOutcome.SentForApproval ->
            str(S.desktop_profile_sent_to_admins)
    }

/** The form as the session says it should be — the state a fresh open shows. */
private fun ProfileFormState.seeded(seed: ProfileSeed): ProfileFormState = copy(
    firstName = seed.firstName,
    lastName = seed.lastName,
    departmentId = seed.departmentId,
    designationId = seed.designationId,
    keepNamePrivate = seed.keepNamePrivate,
    showMailboxInCrewList = seed.showMailboxInCrewList,
    mailboxAddress = seed.mailboxAddress,
    isPersonal = seed.isPersonal,
    error = null,
    outcome = null,
)

/**
 * Whether the user has touched the form since it was seeded.
 *
 * Compared field by field rather than tracked with a flag, because the flag
 * would have to be cleared on every path out of the form and the one path that
 * forgot is the one that throws away a typed name.
 */
private fun ProfileFormState.isDirtyAgainst(seed: ProfileSeed): Boolean =
    firstName != seed.firstName ||
        lastName != seed.lastName ||
        departmentId != seed.departmentId ||
        designationId != seed.designationId ||
        keepNamePrivate != seed.keepNamePrivate

/** Nothing to choose from, for the default that never reaches a real screen. */
private fun crewPresetsEmpty() = com.zillit.desktop.feature.settings.approvals.CrewPresets()
