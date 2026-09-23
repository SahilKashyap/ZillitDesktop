package com.zillit.desktop.feature.settings.ui

import com.zillit.desktop.core.appupdate.UpdateStatus
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
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
@Suppress("LongParameterList") // One seam per thing the screen changes; each is a test hook, and all default.
class SettingsViewModel(
    private val setTheme: (ThemeMode) -> Unit,
    private val setScale: (Int) -> Unit,
    /** Writes the language preference; the store that loads the words follows it. */
    private val setLanguage: (String) -> Unit = {},
    /** The stored language preference, mirrored so the row ticks what is actually in force. */
    language: Flow<String> = flowOf(""),
    private val notifications: NotificationSettings = NotificationSettings(),
    private val signOut: suspend () -> Unit,
    /**
     * How much unsent work signing out would delete — the outbox and local
     * drafts. Asked when the question is raised, not before, so it is current.
     */
    private val unsentChanges: suspend () -> Int = { 0 },
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
    /**
     * Asks Firebase whether a newer build exists — the same checker the
     * shell's banner polls. Null when the build cannot check (no app id,
     * unpackaged), in which case the About row says so instead of trying.
     */
    private val checkForUpdates: (suspend () -> UpdateStatus)? = null,
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
        launch { notifications.ringtone.collect { on -> setState { copy(ringOnIncomingCall = on) } } }
        launch { notifications.activity.collect { on -> setState { copy(notifyActivity = on) } } }
        launch { notifications.callWidget.collect { on -> setState { copy(callWidget = on) } } }
        launch { notifications.messageWidget.collect { on -> setState { copy(messageWidget = on) } } }
        launch { notifications.closeToTray.collect { on -> setState { copy(closeToTray = on) } } }
        launch { notifications.startAtLogin.collect { on -> setState { copy(startAtLogin = on) } } }
        launch { notifications.widgets.collect { list -> setState { copy(widgets = list) } } }
        setState { copy(startAtLoginAvailable = notifications.startAtLoginAvailable) }
        launch { unitContext.collect(::onUnitContext) }
        launch { language.collect { code -> setState { copy(language = code) } } }
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

    private fun onThemeChanged(mode: ThemeMode) {
        setState { copy(themeMode = mode) }
        setTheme(mode)
    }

    private fun onLanguageChanged(code: String) {
        setState { copy(language = code) }
        setLanguage(code)
    }

    // Exhaustive dispatch over the sealed event set — the branch count is the
    // pattern, not a complexity problem (see ChatViewModel's onEvent).
    @Suppress("CyclomaticComplexMethod")
    override fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.ThemeChanged -> onThemeChanged(event.mode)
            is SettingsEvent.LanguageChanged -> onLanguageChanged(event.code)

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
            is SettingsEvent.NotifyCallsChanged -> onCallSwitch(banner = event.on)
            is SettingsEvent.RingtoneChanged -> onCallSwitch(ring = event.on)
            is SettingsEvent.NotifyActivityChanged -> {
                setState { copy(notifyActivity = event.on) }
                notifications.setActivity(event.on)
            }

            is SettingsEvent.DesktopSwitch -> onDesktopSwitch(event)
            is SettingsEvent.UnitChanged -> changeUnit(event.unitId)


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

            SettingsEvent.AskSignOut -> {
                setState { copy(isConfirmingSignOut = true, unsentChanges = 0) }
                launch { unsentChanges().let { count -> setState { copy(unsentChanges = count) } } }
            }
            SettingsEvent.DismissSignOut -> setState { copy(isConfirmingSignOut = false) }

            SettingsEvent.CheckForUpdates -> checkForUpdates()
            is SettingsEvent.DownloadUpdate -> sendEffect(SettingsEffect.OpenExternal(event.url))
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
     * One check, one answer, on the About row.
     *
     * A second click while one is in flight is ignored rather than queued:
     * the answer it would get is the one already coming.
     */
    private fun checkForUpdates() {
        if (currentState.about.updateCheck == UpdateCheck.Checking) return
        val check = checkForUpdates
        if (check == null) {
            setState { copy(about = about.copy(updateCheck = UpdateCheck.Unavailable)) }
            return
        }
        setState { copy(about = about.copy(updateCheck = UpdateCheck.Checking)) }
        launch {
            val result = when (val status = check()) {
                UpdateStatus.UpToDate -> UpdateCheck.UpToDate
                is UpdateStatus.Available -> UpdateCheck.Available(status.latestVersion, status.downloadUrl, false)
                is UpdateStatus.Required -> UpdateCheck.Available(status.latestVersion, status.downloadUrl, true)
                UpdateStatus.Unknown -> UpdateCheck.Unavailable
            }
            setState { copy(about = about.copy(updateCheck = result)) }
        }
    }

    /** The two call switches: the banner and the ring are separate decisions, saved separately. */
    private fun onCallSwitch(banner: Boolean? = null, ring: Boolean? = null) {
        banner?.let { on ->
            setState { copy(notifyCalls = on) }
            notifications.setCalls(on)
        }
        ring?.let { on ->
            setState { copy(ringOnIncomingCall = on) }
            notifications.setRingtone(on)
        }
    }

    private fun onDesktopSwitch(event: SettingsEvent.DesktopSwitch) {
        when (event) {
            is SettingsEvent.CallWidgetChanged -> {
                setState { copy(callWidget = event.on) }
                notifications.setCallWidget(event.on)
            }
            is SettingsEvent.MessageWidgetChanged -> {
                setState { copy(messageWidget = event.on) }
                notifications.setMessageWidget(event.on)
            }
            is SettingsEvent.CloseToTrayChanged -> {
                setState { copy(closeToTray = event.on) }
                notifications.setCloseToTray(event.on)
            }
            is SettingsEvent.StartAtLoginChanged -> {
                setState { copy(startAtLogin = event.on) }
                notifications.setStartAtLogin(event.on)
            }
            is SettingsEvent.WidgetChanged -> {
                setState {
                    copy(widgets = widgets.map { if (it.id == event.id) it.copy(on = event.on) else it })
                }
                notifications.setWidget(event.id, event.on)
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
            // The app's own guide, not a web page: it carries the same four
            // links the phones' Zillit Guide does, plus the support call and
            // the mail to support, which a browser tab cannot offer.
            SettingsDestination.Help -> sendEffect(SettingsEffect.OpenHelp)
            SettingsDestination.SetupNotes -> sendEffect(SettingsEffect.OpenExternal(SETUP_NOTES_URL))

            // The page belongs to the Account Hub, which opens on it.
            SettingsDestination.ProductionSetup ->
                sendEffect(SettingsEffect.OpenTool(ACCOUNT_HUB_ROUTE))

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
    get() = str(S.desktop_could_not_change_unit, userMessage)
