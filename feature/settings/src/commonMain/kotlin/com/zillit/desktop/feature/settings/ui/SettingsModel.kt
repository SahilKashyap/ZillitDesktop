package com.zillit.desktop.feature.settings.ui

import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.settings.account.AccountPage
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue

/**
 * Who is signed in, for the account section.
 *
 * Supplied rather than fetched: the session already holds all of it, and a
 * settings screen that re-requests a profile is a settings screen that can show
 * a spinner where a name should be.
 */
data class AccountSummary(
    val fullName: String = "",
    val email: String = "",
    val productionName: String = "",
    val isAdmin: Boolean = false,
) {
    val hasAccount: Boolean get() = fullName.isNotBlank() || email.isNotBlank()

    /** Never prints the address: this ends up in logs. */
    override fun toString(): String = "AccountSummary(named=${fullName.isNotBlank()}, admin=$isAdmin)"
}

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.System,
    val uiScalePercent: Int = DEFAULT_SCALE,
    val account: AccountSummary = AccountSummary(),
    /** Silences calendar reminders without unsetting them on the events. */
    val muteNotifications: Boolean = false,
    /** Desktop banners per source; the calendar rides [muteNotifications]. */
    val notifyMessages: Boolean = true,
    val notifyMail: Boolean = true,
    val notifyUpdates: Boolean = true,
    val notifyCalls: Boolean = true,
    val unit: UnitSelection = UnitSelection(),
    /** Asked before signing out — it drops the local cache with it. */
    val isConfirmingSignOut: Boolean = false,
    /** The administration page, reached from this one. */
    val admin: AdminSettingsUiState = AdminSettingsUiState(),
) {
    val canDecreaseScale: Boolean get() = uiScalePercent > MIN_SCALE
    val canIncreaseScale: Boolean get() = uiScalePercent < MAX_SCALE

    companion object {
        const val DEFAULT_SCALE = 100
        const val MIN_SCALE = 80
        const val MAX_SCALE = 150
        const val SCALE_STEP = 10
    }
}

/**
 * Which production unit this user is on.
 *
 * A shoot runs several units at once — main, second, splinter — and the one a
 * crew member is attached to decides whose call sheets and notices reach them.
 * Getting it wrong is not cosmetic: it is being on the wrong call.
 */
data class UnitSelection(
    val options: List<ProductionUnit> = emptyList(),
    /** Null until the profile says, or while no production is open. */
    val selectedId: String? = null,
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    val selected: ProductionUnit? get() = options.firstOrNull { it.id == selectedId }

    /**
     * Hidden entirely when the production has no units to choose between.
     *
     * A picker offering one option is furniture, and one offering none reads as
     * something failing to load.
     */
    val isOfferable: Boolean get() = options.size > 1

    val canChange: Boolean get() = isOfferable && !isSaving
}

/**
 * The administration page.
 *
 * Its own state rather than fields spread through [SettingsUiState]: it is a
 * separate page reached from this one, and the search box belongs to it — a
 * query typed there must not survive into the settings page behind it.
 */
data class AdminSettingsUiState(
    val production: ProductionFacts = ProductionFacts(),
    /**
     * How many people are waiting in each queue.
     *
     * Counted from the queues themselves rather than from the unread endpoint:
     * that one is currently asked with `section=tools_label` — the grid and the
     * rail needed badges first — so it has nothing to say about this page. The
     * approval screens load the real lists, and report their length back here.
     */
    val pendingNewCrew: Int = 0,
    val pendingProfileChanges: Int = 0,
    /** Filters the whole page. Twenty destinations is more than a page scan. */
    val query: String = "",
) {
    /** What the Settings row's badge shows: everything an admin has to decide. */
    val pendingTotal: Int get() = pendingNewCrew + pendingProfileChanges
}

/**
 * What the open production says about this user's unit.
 *
 * Fed in as a flow rather than read once: units belong to a production, so
 * switching production has to replace the list, and the profile reload after a
 * change is what confirms the server took it.
 */
data class UnitContext(
    val projectId: String? = null,
    val joinUnitId: String? = null,
)

sealed interface SettingsEvent {
    data class ThemeChanged(val mode: ThemeMode) : SettingsEvent

    /** Steps the interface size. [by] is a percentage delta. */
    data class ScaleChanged(val by: Int) : SettingsEvent
    data object ScaleReset : SettingsEvent

    data class MuteNotificationsChanged(val muted: Boolean) : SettingsEvent
    data class NotifyMessagesChanged(val on: Boolean) : SettingsEvent
    data class NotifyMailChanged(val on: Boolean) : SettingsEvent
    data class NotifyUpdatesChanged(val on: Boolean) : SettingsEvent
    data class NotifyCallsChanged(val on: Boolean) : SettingsEvent

    /** Attaches the user to a different production unit. */
    data class UnitChanged(val unitId: String) : SettingsEvent

    /** Opens the email signature manager, which is its own window. */
    data object OpenSignatures : SettingsEvent

    /** A row on either listing was clicked. */
    data class OpenEntry(val destination: SettingsDestination) : SettingsEvent

    /** Filters the administration page. */
    data class AdminSearchChanged(val query: String) : SettingsEvent

    /** The approval queues reported how much is waiting in them. */
    data class ApprovalsCounted(val newCrew: Int, val profileChanges: Int) : SettingsEvent

    data object AskSignOut : SettingsEvent
    data object ConfirmSignOut : SettingsEvent
    data object DismissSignOut : SettingsEvent
}

sealed interface SettingsEffect {
    data object OpenSignatures : SettingsEffect
    data object SignedOut : SettingsEffect

    /** Moves the administration window to one of the two approval queues. */
    data class OpenApprovals(val queue: ApprovalQueue) : SettingsEffect

    /**
     * Moves the administration window to one of its pages.
     *
     * The page is named rather than the route, for the same reason
     * [OpenAccountPage] names one: routes are the provider's business.
     */
    data class OpenAdminPage(val page: AdminDestination) : SettingsEffect

    /**
     * Moves this window to one of the reader's own pages.
     *
     * The page is named rather than the route: routes are the provider's
     * business, and this module's view model has no reason to know the string
     * the workspace navigates by.
     */
    data class OpenAccountPage(val page: AccountPage) : SettingsEffect

    /**
     * Asks the leave-production dialog to open.
     *
     * The dialog belongs to the account view model, which owns the call behind
     * it — this one only knows that a row was clicked.
     */
    data object AskLeaveProduction : SettingsEffect

    /**
     * Hands a documentation link to the browser.
     *
     * Carried as an effect rather than opened here: this module is common code
     * and has no browser, and the launcher that does refuses anything that is
     * not http(s) — which is the check worth having in exactly one place.
     */
    data class OpenExternal(val url: String) : SettingsEffect
}

/**
 * The notification switches, as one thing.
 *
 * Grouped rather than six more constructor parameters: they are read and
 * written together, and a settings screen that took each one loose would grow
 * a parameter per source forever.
 */
@Suppress("LongParameterList")
class NotificationSettings(
    val muted: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    val messages: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val mail: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val updates: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val calls: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val setMuted: (Boolean) -> Unit = {},
    val setMessages: (Boolean) -> Unit = {},
    val setMail: (Boolean) -> Unit = {},
    val setUpdates: (Boolean) -> Unit = {},
    val setCalls: (Boolean) -> Unit = {},
)
