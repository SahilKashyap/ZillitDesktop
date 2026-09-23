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
    /** For the profile picture; blank before the profile arrives. */
    val userId: String = "",
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
    /** The stored language preference: a code, or blank for "follow the system". */
    val language: String = "",
    val uiScalePercent: Int = DEFAULT_SCALE,
    val account: AccountSummary = AccountSummary(),
    /** Silences calendar reminders without unsetting them on the events. */
    val muteNotifications: Boolean = false,
    /** Desktop banners per source; the calendar rides [muteNotifications]. */
    val notifyMessages: Boolean = true,
    val notifyMail: Boolean = true,
    val notifyUpdates: Boolean = true,
    val notifyCalls: Boolean = true,
    /** The ringtone while a call rings this device. */
    val ringOnIncomingCall: Boolean = true,
    val notifyActivity: Boolean = true,
    val callWidget: Boolean = true,
    val messageWidget: Boolean = true,
    val closeToTray: Boolean = true,
    val startAtLogin: Boolean = false,
    /** The desktop widgets and whether each is on screen — see [WidgetToggle]. */
    val widgets: List<WidgetToggle> = emptyList(),
    val startAtLoginAvailable: Boolean = true,
    val unit: UnitSelection = UnitSelection(),
    /** Asked before signing out — it drops the local cache with it. */
    val isConfirmingSignOut: Boolean = false,
    /**
     * Work done offline that has not reached the server, plus drafts kept on
     * this computer. Signing out deletes both, so the question says so.
     */
    val unsentChanges: Int = 0,
    /** The administration page, reached from this one. */
    val admin: AdminSettingsUiState = AdminSettingsUiState(),
    /** Which build this is, and what the last update check said. */
    val about: AboutInfo = AboutInfo(),
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
 * What this build is, for the About row.
 *
 * Supplied by the app, which is the only module that knows: the version is a
 * compile-time constant generated from `zillit.version`, and the git sha rides
 * with it. Neither is fetched. The phones show the same line under their
 * settings (`SettingPage.kt` prints `BuildConfig.VERSION_NAME`; the web's side
 * menu shows `web_app_version`), and it is the first thing support asks for.
 */
data class AboutInfo(
    /** `1.0.2`. Empty only in a test that never set one. */
    val version: String = "",
    /** The short git sha the build was cut from; empty for a build without git. */
    val build: String = "",
    /** `macOS 26.5`, `Windows 11` — what the JVM reports, for the same bug report. */
    val platform: String = "",
    val updateCheck: UpdateCheck = UpdateCheck.Idle,
) {
    /** `1.0.2 (2dbe8ff)`, or just the version. */
    val versionLabel: String
        get() = if (build.isBlank()) version else "$version ($build)"
}

/**
 * The manual "Check for updates" on the About row, as one closed set.
 *
 * Distinct from the shell's banner, which polls on its own clock and shows
 * only when there is something to say. This is the reader asking, and a
 * reader who asked deserves an answer even when the answer is "you are
 * current" or "could not reach the server" — the two the banner never shows.
 */
sealed interface UpdateCheck {
    /** Not asked yet this session. */
    data object Idle : UpdateCheck
    data object Checking : UpdateCheck
    data object UpToDate : UpdateCheck

    /** A newer build exists. [downloadUrl] null when no https link was published. */
    data class Available(val version: String, val downloadUrl: String?, val mandatory: Boolean) : UpdateCheck

    /** No answer — unconfigured, offline, or nothing published for desktop. */
    data object Unavailable : UpdateCheck
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

    /** A language code, or blank to follow the system. */
    data class LanguageChanged(val code: String) : SettingsEvent

    /** Steps the interface size. [by] is a percentage delta. */
    data class ScaleChanged(val by: Int) : SettingsEvent
    data object ScaleReset : SettingsEvent

    data class MuteNotificationsChanged(val muted: Boolean) : SettingsEvent
    data class NotifyMessagesChanged(val on: Boolean) : SettingsEvent
    data class NotifyMailChanged(val on: Boolean) : SettingsEvent
    data class NotifyUpdatesChanged(val on: Boolean) : SettingsEvent
    data class NotifyCallsChanged(val on: Boolean) : SettingsEvent
    /** The ringtone switch — `ZillitPreferences.RingOnIncomingCall`. */
    data class RingtoneChanged(val on: Boolean) : SettingsEvent
    data class NotifyActivityChanged(val on: Boolean) : SettingsEvent
    /** The Desktop section: four switches with one shape, so one branch can route them. */
    sealed interface DesktopSwitch : SettingsEvent {
        val on: Boolean
    }
    data class CallWidgetChanged(override val on: Boolean) : DesktopSwitch
    data class MessageWidgetChanged(override val on: Boolean) : DesktopSwitch
    data class CloseToTrayChanged(override val on: Boolean) : DesktopSwitch
    data class StartAtLoginChanged(override val on: Boolean) : DesktopSwitch

    /** One of the desktop widgets was switched on or off. [id] is a `ZillitWidget` name. */
    data class WidgetChanged(val id: String, override val on: Boolean) : DesktopSwitch

    /** Attaches the user to a different production unit. */
    data class UnitChanged(val unitId: String) : SettingsEvent


    /** A row on either listing was clicked. */
    data class OpenEntry(val destination: SettingsDestination) : SettingsEvent

    /** Filters the administration page. */
    data class AdminSearchChanged(val query: String) : SettingsEvent

    /** The approval queues reported how much is waiting in them. */
    data class ApprovalsCounted(val newCrew: Int, val profileChanges: Int) : SettingsEvent

    data object AskSignOut : SettingsEvent
    data object ConfirmSignOut : SettingsEvent
    data object DismissSignOut : SettingsEvent

    /** The About row's button. */
    data object CheckForUpdates : SettingsEvent

    /** The Download that follows a successful check. [url] is what the check returned. */
    data class DownloadUpdate(val url: String) : SettingsEvent
}

sealed interface SettingsEffect {
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
     * Opens Zillit Help — the app's own guide page, in this same window.
     *
     * Named rather than routed, like [OpenAccountPage]: the route belongs to
     * the provider, and this module's view model has no reason to know the
     * string the workspace navigates by.
     */
    data object OpenHelp : SettingsEffect

    /**
     * Opens another tool's window — Production Setup, which lives in the
     * Account Hub.
     *
     * Android reaches Production Setup from Admin Settings even though the
     * screen belongs to the accounts console; a coordinator setting a
     * production up looks here, not under a finance tool. Carried as a route
     * rather than a module reference: this module knows no other feature.
     */
    data class OpenTool(val path: String) : SettingsEffect

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
    val ringtone: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val activity: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val callWidget: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val messageWidget: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val closeToTray: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(true),
    val widgets: kotlinx.coroutines.flow.Flow<List<WidgetToggle>> =
        kotlinx.coroutines.flow.flowOf(emptyList()),
    val startAtLogin: kotlinx.coroutines.flow.Flow<Boolean> = kotlinx.coroutines.flow.flowOf(false),
    /** False under a development run, where there is no installed app for the OS to start. */
    val startAtLoginAvailable: Boolean = true,
    val setMuted: (Boolean) -> Unit = {},
    val setMessages: (Boolean) -> Unit = {},
    val setMail: (Boolean) -> Unit = {},
    val setUpdates: (Boolean) -> Unit = {},
    val setCalls: (Boolean) -> Unit = {},
    val setRingtone: (Boolean) -> Unit = {},
    val setActivity: (Boolean) -> Unit = {},
    val setCallWidget: (Boolean) -> Unit = {},
    val setMessageWidget: (Boolean) -> Unit = {},
    val setCloseToTray: (Boolean) -> Unit = {},
    val setStartAtLogin: (Boolean) -> Unit = {},
    val setWidget: (String, Boolean) -> Unit = { _, _ -> },
)

/**
 * One desktop widget's switch, as Settings shows it.
 *
 * A list rather than a field per widget: the widgets are a set that grows, and
 * every one of them is the same question — is this small window on screen.
 */
data class WidgetToggle(
    /** The `ZillitWidget` entry's name, which is what an event carries back. */
    val id: String,
    val label: String,
    val detail: String,
    val on: Boolean,
)
