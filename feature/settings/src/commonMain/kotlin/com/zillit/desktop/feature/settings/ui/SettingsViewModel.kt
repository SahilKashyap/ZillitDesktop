package com.zillit.desktop.feature.settings.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.units.UnitRepository
import com.zillit.desktop.feature.settings.account.AccountPage
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Application settings.
 *
 * ## Only what actually does something
 *
 * The preference store declares more than this screen offers — language, app
 * lock, workspace layout persistence — and none of those are read anywhere yet.
 * A settings screen is a promise that a control has an effect, and a toggle
 * that silently does nothing is worse than an absent one: it costs the user
 * time working out why their choice was ignored. They belong here the moment
 * they are wired, and not before.
 */
class SettingsViewModel(
    private val setTheme: (ThemeMode) -> Unit,
    private val setScale: (Int) -> Unit,
    private val notifications: NotificationSettings = NotificationSettings(),
    private val signOut: suspend () -> Unit,
    /** Null while no production is open — there are no units to choose from. */
    private val unitRepository: UnitRepository? = null,
    /** Re-reads the profile after a change, so the rest of the app follows. */
    private val onUnitChanged: suspend () -> Unit = {},
    unitContext: Flow<UnitContext> = flowOf(UnitContext()),
    /**
     * Who is signed in, and whether they run this production.
     *
     * A flow, not a value in [initial]: the profile arrives a second or so
     * after the app does, and admin rights are granted and revoked while people
     * are signed in. Read once at construction it was blank forever — the
     * account card had no name on it and `isAdmin` never became true, which is
     * why nothing that gates on it was ever offered.
     */
    account: Flow<AccountSummary> = flowOf(AccountSummary()),
    initial: SettingsUiState = SettingsUiState(),
) : ZillitViewModel<SettingsUiState, SettingsEvent, SettingsEffect>(initial) {

    init {
        // Mirrors the stored value rather than holding its own copy: the
        // reminder scheduler reads the same preference, and a screen that
        // disagreed with what is actually muting notifications would be a
        // switch pointing the wrong way.
        launch {
            notifications.muted.collect { muted -> setState { copy(muteNotifications = muted) } }
        }
        launch { notifications.messages.collect { on -> setState { copy(notifyMessages = on) } } }
        launch { notifications.mail.collect { on -> setState { copy(notifyMail = on) } } }
        launch { notifications.updates.collect { on -> setState { copy(notifyUpdates = on) } } }
        launch { notifications.calls.collect { on -> setState { copy(notifyCalls = on) } } }
        launch { unitContext.collect(::onUnitContext) }
        // Only once there is something to show. The profile loads after the
        // window does, and an empty summary arriving first would blank a card
        // that a project switch is about to repopulate.
        launch {
            account.collect { summary ->
                if (summary.hasAccount || summary.isAdmin) setState { copy(account = summary) }
            }
        }
    }

    /** The production whose units are loaded, so a refresh is not a reload. */
    private var unitsLoadedFor: String? = null

    /**
     * Follows the open production.
     *
     * Units belong to a production: asking for them before one is open gets a
     * rejection from a server with no idea who is asking, and keeping the
     * previous production's list after a switch would offer units the user
     * cannot be on.
     */
    private fun onUnitContext(context: UnitContext) {
        if (context.projectId != unitsLoadedFor) {
            unitsLoadedFor = context.projectId
            if (context.projectId == null) setState { copy(unit = UnitSelection()) } else loadUnits()
        }

        // The server's answer replaces the optimistic one — but not while the
        // save is still in flight, or the picker would snap back mid-change.
        if (!currentState.unit.isSaving && context.joinUnitId != null) {
            setState { copy(unit = unit.copy(selectedId = context.joinUnitId)) }
        }
    }

    // Exhaustive dispatch over the sealed event set — the branch count is the
    // pattern, not a complexity problem (see ChatViewModel's onEvent).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ThemeChanged -> {
                setState { copy(themeMode = event.mode) }
                setTheme(event.mode)
            }

            is SettingsEvent.ScaleChanged -> applyScale(currentState.uiScalePercent + event.by)
            SettingsEvent.ScaleReset -> applyScale(SettingsUiState.DEFAULT_SCALE)

            is SettingsEvent.MuteNotificationsChanged -> {
                setState { copy(muteNotifications = event.muted) }
                notifications.setMuted(event.muted)
            }
            is SettingsEvent.NotifyMessagesChanged -> {
                setState { copy(notifyMessages = event.on) }
                notifications.setMessages(event.on)
            }
            is SettingsEvent.NotifyMailChanged -> {
                setState { copy(notifyMail = event.on) }
                notifications.setMail(event.on)
            }
            is SettingsEvent.NotifyUpdatesChanged -> {
                setState { copy(notifyUpdates = event.on) }
                notifications.setUpdates(event.on)
            }
            is SettingsEvent.NotifyCallsChanged -> {
                setState { copy(notifyCalls = event.on) }
                notifications.setCalls(event.on)
            }

            is SettingsEvent.UnitChanged -> changeUnit(event.unitId)

            SettingsEvent.OpenSignatures -> sendEffect(SettingsEffect.OpenSignatures)

            is SettingsEvent.OpenEntry -> open(event.destination)

            is SettingsEvent.AdminSearchChanged ->
                setState { copy(admin = admin.copy(query = event.query)) }

            is SettingsEvent.ApprovalsCounted -> setState {
                copy(
                    admin = admin.copy(
                        pendingNewCrew = event.newCrew,
                        pendingProfileChanges = event.profileChanges,
                    ),
                )
            }

            SettingsEvent.AskSignOut -> setState { copy(isConfirmingSignOut = true) }
            SettingsEvent.DismissSignOut -> setState { copy(isConfirmingSignOut = false) }
            SettingsEvent.ConfirmSignOut -> {
                setState { copy(isConfirmingSignOut = false) }
                launch {
                    signOut()
                    sendEffect(SettingsEffect.SignedOut)
                }
            }
        }
    }

    /**
     * Follows a listing row.
     *
     * Rows whose destination has not reached desktop yet are not clickable —
     * they carry [EntryStatus.Planned], and the list does not attach a click to
     * them — so anything arriving here without a case is a row that was made
     * openable without being wired. Ignored rather than crashed: a settings row
     * is not worth taking the window down for, and the missing case is visible
     * the moment someone clicks it.
     */
    private fun open(destination: SettingsDestination) {
        when (destination) {
            SettingsDestination.Help -> sendEffect(SettingsEffect.OpenExternal(ZILLIT_HELP_URL))
            SettingsDestination.SetupNotes -> sendEffect(SettingsEffect.OpenExternal(SETUP_NOTES_URL))

            SettingsDestination.ApproveNewCrew ->
                sendEffect(SettingsEffect.OpenApprovals(ApprovalQueue.NewCrew))

            SettingsDestination.ApproveProfileChanges ->
                sendEffect(SettingsEffect.OpenApprovals(ApprovalQueue.ProfileChanges))

            SettingsDestination.EditProfile ->
                sendEffect(SettingsEffect.OpenAccountPage(AccountPage.EditProfile))

            SettingsDestination.RecoveryEmail ->
                sendEffect(SettingsEffect.OpenAccountPage(AccountPage.RecoveryEmail))

            SettingsDestination.LinkedDevices ->
                sendEffect(SettingsEffect.OpenAccountPage(AccountPage.LinkedDevices))

            SettingsDestination.InviteCrew ->
                sendEffect(SettingsEffect.OpenAccountPage(AccountPage.InviteCrew))

            // A dialog over the listing, not a page — see LeaveProductionDialog.
            SettingsDestination.LeaveProduction -> sendEffect(SettingsEffect.AskLeaveProduction)

            // Everything else is an administration page. Resolved through
            // AdminDestination rather than named here, so the row-to-page map
            // lives in one place and a row with no page yet is simply inert.
            else -> AdminDestination.of(destination)?.let { page ->
                sendEffect(SettingsEffect.OpenAdminPage(page))
            }
        }
    }

    /**
     * Loads the units this user could be on.
     *
     * Failure is silent. The unit row simply does not appear — a settings
     * screen that opens with an error about something the user was not looking
     * for is worse than one that quietly offers less.
     */
    private fun loadUnits() {
        val repository = unitRepository ?: return

        setState { copy(unit = unit.copy(isLoading = true)) }
        launch {
            val loaded = repository.joinUnits().getOrNull().orEmpty()
            setState { copy(unit = unit.copy(options = loaded, isLoading = false)) }
        }
    }

    /**
     * Moves the user to another unit.
     *
     * Applied optimistically and rolled back on failure. The alternative — a
     * spinner on a dropdown — makes a one-field change feel like a form
     * submission, and this one is usually made in passing.
     */
    private fun changeUnit(unitId: String) {
        val repository = unitRepository ?: return
        val previous = currentState.unit.selectedId
        if (unitId == previous) return

        setState { copy(unit = unit.copy(selectedId = unitId, isSaving = true, error = null)) }

        launch {
            when (val saved = repository.setJoinUnit(unitId)) {
                is ZillitResult.Success -> {
                    setState { copy(unit = unit.copy(isSaving = false)) }
                    // The unit decides which notices and call sheets arrive, so
                    // the rest of the app has to be told, not left to notice.
                    onUnitChanged()
                }

                is ZillitResult.Failure -> setState {
                    copy(
                        unit = unit.copy(
                            selectedId = previous,
                            isSaving = false,
                            error = saved.error.unitMessage,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Clamped rather than validated.
     *
     * The buttons already disable at the ends, so a value outside the range
     * means something else went wrong — and refusing it would leave the
     * interface at a size the user cannot read to fix it.
     */
    private fun applyScale(percent: Int) {
        val clamped = percent.coerceIn(SettingsUiState.MIN_SCALE, SettingsUiState.MAX_SCALE)
        setState { copy(uiScalePercent = clamped) }
        setScale(clamped)
    }
}

/** Says the change did not take, rather than what the server called it. */
private val ZillitError.unitMessage: String
    get() = "Could not change your unit. ${userMessage}"
