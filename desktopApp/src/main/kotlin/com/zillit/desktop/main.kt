@file:Suppress("TooManyFunctions") // The application's own assembly: one function per wiring step.

package com.zillit.desktop

import java.io.PrintWriter
import java.io.StringWriter
import com.zillit.desktop.core.common.ZillitLogging
import androidx.compose.foundation.isSystemInDarkTheme
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.currentPlatform
import com.zillit.desktop.core.badges.BadgeCounts
import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.PreferenceStoreFactory
import com.zillit.desktop.core.datastore.WindowGeometry
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.datastore.loadWindowGeometry
import com.zillit.desktop.core.datastore.saveWindowGeometry
import kotlinx.coroutines.delay
import com.zillit.desktop.core.datastore.observeAs
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.workspace.FileWorkspaceSessionStore
import com.zillit.desktop.core.workspace.ToolRegistry
import com.zillit.desktop.core.workspace.WorkspaceShortcuts
import com.zillit.desktop.core.workspace.WorkspaceEvent
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.home.domain.ToolPresentation
import com.zillit.desktop.feature.home.ui.ToolSection
import com.zillit.desktop.core.workspace.WorkspaceViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.feature.auth.ui.AuthEffect
import com.zillit.desktop.feature.auth.ui.AuthEvent
import com.zillit.desktop.feature.auth.ui.JoinEffect
import com.zillit.desktop.feature.auth.ui.JoinProductionViewModel
import com.zillit.desktop.feature.auth.ui.CreateProductionViewModel
import com.zillit.desktop.feature.auth.ui.AuthScreen
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import com.zillit.desktop.feature.shell.AppShell
import com.zillit.desktop.feature.shell.RailItem
import com.zillit.desktop.feature.shell.DefaultRailItems
import com.zillit.desktop.feature.home.data.boardRealtime
import com.zillit.desktop.core.appupdate.UPDATE_CHECK_INTERVAL_MILLIS
import com.zillit.desktop.core.appupdate.UpdateStatus
import com.zillit.desktop.feature.shell.UpdateNotice
import com.zillit.desktop.feature.shell.railItemsFor
import com.zillit.desktop.feature.shell.AdminRailItem
import com.zillit.desktop.feature.sos.data.SosRepositoryImpl
import com.zillit.desktop.feature.sos.domain.SosCrewMember
import com.zillit.desktop.feature.sos.domain.SosViewer
import com.zillit.desktop.feature.sos.ui.SosToolProvider
import com.zillit.desktop.feature.sos.ui.SosViewModel
import com.zillit.desktop.feature.settings.ui.AdminSettingsToolProvider
import com.zillit.desktop.feature.notifications.ui.NotificationsViewModel
import com.zillit.desktop.feature.notifications.ui.NOTIFICATIONS_PATH
import com.zillit.desktop.feature.notifications.ui.NotificationsToolProvider
import com.zillit.desktop.feature.settings.ui.HelpToolProvider
import com.zillit.desktop.feature.home.domain.HomeUnitKind
import com.zillit.desktop.feature.home.ui.HomeFeedEvent
import com.zillit.desktop.feature.home.ui.HomeFeedViewModel
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventInvitee
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.zillit.desktop.core.session.ProjectContext
import com.zillit.desktop.feature.email.domain.ContactSource
import com.zillit.desktop.feature.email.domain.EmailContact
import com.zillit.desktop.feature.email.domain.EmailDraft
import com.zillit.desktop.feature.email.domain.EmailMessage
import com.zillit.desktop.feature.email.data.DownloadsAttachmentStore
import com.zillit.desktop.feature.email.data.FilePicker
import com.zillit.desktop.feature.email.data.InMemoryMailboxCache
import com.zillit.desktop.feature.email.data.Mailbox
import com.zillit.desktop.feature.email.data.SqlMailboxCache
import com.zillit.desktop.feature.email.ui.AttachmentDownloader
import com.zillit.desktop.feature.email.ui.Composing
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.FolderEditor
import com.zillit.desktop.feature.email.ui.MailSearch
import com.zillit.desktop.feature.email.ui.SignatureToolProvider
import com.zillit.desktop.feature.settings.account.AccountViewModel
import com.zillit.desktop.feature.settings.account.ProfileSeed
import com.zillit.desktop.feature.settings.admin.ui.AdminDestination
import com.zillit.desktop.feature.settings.admin.ui.AdminViewModel
import com.zillit.desktop.feature.settings.ui.path
import com.zillit.desktop.feature.settings.approvals.ApprovalPresets
import com.zillit.desktop.feature.settings.approvals.ApprovalQueue
import com.zillit.desktop.feature.settings.approvals.ApprovalsEvent
import com.zillit.desktop.feature.settings.approvals.ApprovalsViewModel
import com.zillit.desktop.feature.settings.approvals.KnownCrewMember
import com.zillit.desktop.feature.settings.ui.AccountSummary
import com.zillit.desktop.feature.settings.ui.AdminSettingsUiState
import com.zillit.desktop.feature.settings.ui.NotificationSettings
import com.zillit.desktop.feature.settings.ui.productionFacts
import com.zillit.desktop.feature.settings.ui.SettingsToolProvider
import com.zillit.desktop.feature.settings.ui.SettingsEvent
import com.zillit.desktop.feature.settings.ui.SettingsUiState
import com.zillit.desktop.feature.settings.ui.UnitContext
import com.zillit.desktop.feature.settings.ui.UnitSelection
import com.zillit.desktop.feature.settings.ui.SettingsViewModel
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.home.ui.decodeImageBitmap
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.ChatToolProvider
import com.zillit.desktop.feature.calls.domain.CallMode
import com.zillit.desktop.feature.calls.domain.CallProvider
import com.zillit.desktop.feature.calls.domain.CallType
import com.zillit.desktop.feature.calls.ui.CallEvent
import com.zillit.desktop.feature.calls.ui.CallOverlay
import com.zillit.desktop.feature.calls.ui.CallViewModel
import com.zillit.desktop.feature.chat.ui.ChatViewModel
import com.zillit.desktop.feature.email.domain.decodeBase64Default
import com.zillit.desktop.feature.email.ui.EmailContactsToolProvider
import com.zillit.desktop.feature.email.ui.EmailSettingsToolProvider
import com.zillit.desktop.feature.email.ui.EmailToolProvider
import com.zillit.desktop.feature.email.ui.EmailViewModel
import com.zillit.desktop.feature.home.ui.HomeViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import com.zillit.desktop.feature.home.ui.HomeUiState
import com.zillit.desktop.feature.home.ui.HomeEvent
import com.zillit.desktop.core.sync.SyncStatus
import com.zillit.desktop.feature.home.data.pdfThumbnailJpeg
import com.zillit.desktop.feature.email.domain.StorageKind
import com.zillit.desktop.feature.email.domain.storageKindOf
import com.zillit.desktop.feature.home.data.videoThumbnailJpeg
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.zillit.desktop.feature.home.data.ClipAudioPlayer
import com.zillit.desktop.feature.home.data.JvmAudioRecorder
import com.zillit.desktop.feature.home.domain.GeoPoint
import com.zillit.desktop.feature.home.domain.NoticeAttachment
import com.zillit.desktop.feature.home.domain.PickedMedia
import com.zillit.desktop.feature.home.domain.UploadedNoticeMedia
import com.zillit.desktop.feature.home.ui.HomeBoardContext
import com.zillit.desktop.feature.cardexpenses.domain.CardViewer
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesToolProvider
import com.zillit.desktop.feature.cardexpenses.ui.CardExpensesViewModel
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesToolProvider
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.dealmemo.domain.DealViewer
import com.zillit.desktop.feature.dealmemo.ui.DealMemoToolProvider
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.ui.DocDistToolProvider
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.ui.AccountHubToolProvider
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderToolProvider
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderViewModel
import com.zillit.desktop.feature.callsheet.data.CallSheetRepositoryImpl
import com.zillit.desktop.feature.callsheet.domain.CallSheetViewer
import com.zillit.desktop.feature.callsheet.domain.CompanySeed
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.ui.CallSheetToolProvider
import com.zillit.desktop.feature.callsheet.ui.CallSheetViewModel
import com.zillit.desktop.feature.esignature.data.EsignRepositoryImpl
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.ui.EsignToolProvider
import com.zillit.desktop.feature.esignature.ui.EsignViewModel
import com.zillit.desktop.feature.productionreport.data.ReportRepositoryImpl
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.domain.ReportViewer
import com.zillit.desktop.feature.productionreport.ui.ProductionReportToolProvider
import com.zillit.desktop.feature.productionreport.ui.ReportViewModel
import com.zillit.desktop.feature.boxschedule.data.BoxScheduleRepositoryImpl
import com.zillit.desktop.feature.boxschedule.ui.BOX_SCHEDULE_PATH
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleToolProvider
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleViewModel
import com.zillit.desktop.feature.boxschedule.ui.PRE_PRODUCTION_PATH
import com.zillit.desktop.feature.maps.data.MapRepositoryImpl
import com.zillit.desktop.feature.maps.ui.MapToolProvider
import com.zillit.desktop.feature.pagedistribution.domain.DistributionTool
import com.zillit.desktop.feature.pagedistribution.ui.DistributionToolProvider
import com.zillit.desktop.feature.pagedistribution.ui.DistributionViewModel
import com.zillit.desktop.feature.recce.ui.RecceToolProvider
import com.zillit.desktop.feature.location.ui.LocationViewModel
import com.zillit.desktop.feature.continuity.ui.ContinuityViewModel
import com.zillit.desktop.feature.costreport.ui.CostReportViewModel
import com.zillit.desktop.feature.invoices.ui.InvoicesViewModel
import com.zillit.desktop.feature.draft.ui.DRAFT_PATH
import com.zillit.desktop.feature.draft.ui.DraftToolProvider
import com.zillit.desktop.feature.draft.ui.DraftViewModel
import com.zillit.desktop.feature.transportation.data.TransportRepositoryImpl
import com.zillit.desktop.feature.transportation.domain.TransportViewer
import com.zillit.desktop.feature.transportation.ui.TransportToolProvider
import com.zillit.desktop.feature.transportation.ui.TransportViewModel
import com.zillit.desktop.feature.recce.ui.RecceViewModel
import com.zillit.desktop.feature.maps.ui.MapViewModel
import com.zillit.desktop.feature.permissiongrid.data.PermissionGridRepositoryImpl
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridToolProvider
import com.zillit.desktop.feature.permissiongrid.ui.PermissionGridViewModel
import com.zillit.desktop.feature.sides.data.SidesRepositoryImpl
import com.zillit.desktop.feature.sides.domain.SidesViewer
import com.zillit.desktop.feature.sides.ui.SidesToolProvider
import com.zillit.desktop.feature.sides.ui.SidesViewModel
import com.zillit.desktop.feature.formsignature.data.FormSignatureRepositoryImpl
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.formsignature.domain.FormSignatureViewer
import com.zillit.desktop.feature.formsignature.ui.FormSignatureToolProvider
import com.zillit.desktop.feature.formsignature.ui.FormSignatureViewModel
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DriveToolProvider
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.ui.PayrollToolProvider
import com.zillit.desktop.feature.payroll.ui.PayrollViewModel
import com.zillit.desktop.feature.purchaseorder.domain.PoViewer
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderToolProvider
import com.zillit.desktop.feature.purchaseorder.ui.PurchaseOrderViewModel
import com.zillit.desktop.feature.timecard.domain.TimecardViewer
import com.zillit.desktop.feature.timecard.ui.TimecardToolProvider
import com.zillit.desktop.feature.timecard.ui.TimecardViewModel
import com.zillit.desktop.feature.home.ui.BoardToolProvider
import com.zillit.desktop.feature.home.ui.HomeToolProvider
import com.zillit.desktop.feature.home.ui.MediaCapture
import com.zillit.desktop.feature.shell.placeholderTools
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.datetime.atStartOfDayIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    installCrashLogging()
    val wantsDriveWidget = DriveWidgetLaunch.requestedBy(args)
    // Before anything opens the database or the preference file — the point of
    // the guard is that the second copy touches neither. See SingleInstance.
    if (!SingleInstance.claim()) {
        // A "Zillit Drive" shortcut while Zillit is up: hand the request to
        // the running copy and go quietly — a dialog here would be noise.
        if (wantsDriveWidget) DriveWidgetLaunch.signalRunningApp() else reportAlreadyRunning()
        return
    }
    installDockIcon()
    DriveWidgetLaunch.installUriHandler()
    runZillit(openDriveWidget = wantsDriveWidget)
}

/**
 * Writes the stack trace of anything that kills a thread into the app log.
 *
 * Compose Desktop catches an uncaught exception, shows a one-line dialog and
 * sends the trace to stderr. A packaged `.app` has no stderr anybody can read,
 * so the dialog is all that survives: "Index 0 out of bounds for length 0",
 * with no file, no line, and no way to tell which of a hundred lists it was.
 * That happened, and it cost a debugging round with nothing to go on.
 *
 * This is deliberately the very first thing `main` does — earlier than the
 * single-instance claim, the dock icon, or the log file itself — because a
 * crash during startup is exactly the one nobody can otherwise reproduce.
 * Before [ZillitLogging.initialise] runs there is no file sink yet, so this
 * also writes the trace directly to the log path as a fallback.
 *
 * It does not swallow anything: the handler runs and the thread still dies,
 * so behaviour is unchanged and only the evidence is better.
 */
private fun installCrashLogging() {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        runCatching {
            val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
            ZillitLog.e(CRASH_TAG) { "uncaught on '${thread.name}': $trace" }
            // Belt and braces: if the failure happened before the file sink was
            // installed, the line above went nowhere. Appending directly costs
            // nothing on a path that is already fatal.
            runCatching {
                ZillitLogging.logFile.also { it.parentFile?.mkdirs() }
                    .appendText("\nUNCAUGHT on '${thread.name}':\n$trace\n")
            }
        }
        // Whatever the platform installed still gets its turn — Compose's own
        // handler is what puts the dialog on screen, and losing it would turn
        // a visible crash into a silent one.
        previous?.uncaughtException(thread, error)
    }
}

/**
 * Says why nothing happened.
 *
 * A second launch that exits silently is indistinguishable from one that failed
 * — the user double-clicks again, and again. One dialog costs nothing and
 * answers the question.
 *
 * Best-effort: on a headless run there is no display to put it on, and the log
 * line from [SingleInstance] is then the whole story.
 */
private fun reportAlreadyRunning() {
    runCatching {
        javax.swing.JOptionPane.showMessageDialog(
            null,
            "Zillit-Desktop is already running.\n\n" +
                "Only one copy can be open at a time, because they share the same " +
                "local data. Switch to the window that is already open.",
            "Zillit-Desktop",
            javax.swing.JOptionPane.INFORMATION_MESSAGE,
        )
    }
}

/**
 * The dock/taskbar picture for runs outside a bundle.
 *
 * A packaged install takes its icon from the bundle (`iconFile` in the
 * distribution config); a `gradle run` is a bare `java` process wearing the
 * JDK's duke until told otherwise. Set before the first window so the icon
 * never visibly swaps.
 */
private fun installDockIcon() {
    runCatching {
        val taskbar = java.awt.Taskbar.getTaskbar()
        if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_IMAGE)) {
            val icon = object {}.javaClass.getResourceAsStream("/icons/zillit-icon.png")
                ?.use(javax.imageio.ImageIO::read) ?: return
            taskbar.iconImage = icon
        }
    }
}

@Suppress("LongMethod") // The application's wiring, in the order it must happen; splitting it hides that.
private fun runZillit(openDriveWidget: Boolean) = application {
    val graph = remember { AppGraph.build() }
    val preferences = remember {
        (graph as? AppGraph.Ready)?.preferences ?: PreferenceStoreFactory.create()
    }
    // The sign-in state, at application level: the main window shows it, and
    // the Drive widget lives or dies by it (see DriveWidgetWindow).
    val authViewModel = remember(graph) { (graph as? AppGraph.Ready)?.let { buildAuth(it) } }
    // Armed here because this is the first point at which there is somewhere to
    // save to, and every way out of the app after it goes through the hook.
    remember(preferences) { Shutdown.install(preferences) }
    val windowState = rememberRestoredWindowState(preferences)

    // One registry for the whole app: the main frame and every torn-off OS
    // window resolve through it, so a second one built for the frame alone
    // would make a detached Home render as a placeholder.
    val scope = rememberCoroutineScope()
    val viewModels = rememberAppViewModels(graph, preferences, scope)

    // The Drive widget: open if asked for on the command line, or if it was
    // open when the app last quit. Toggled from the tray, the Drive tool, and
    // a second launch with `--drive-widget`.
    var driveWidgetOpen by remember {
        mutableStateOf(openDriveWidget || runBlocking { preferences.get(ZillitPreferences.DriveWidgetOpen) })
    }
    LaunchedEffect(driveWidgetOpen) { preferences.set(ZillitPreferences.DriveWidgetOpen, driveWidgetOpen) }
    LaunchedEffect(Unit) { DriveWidgetLaunch.watch { driveWidgetOpen = true } }

    val registry = remember(viewModels) {
        buildRegistry(graph, viewModels, scope, openDriveWidget = { driveWidgetOpen = true })
    }

    val workspaceViewModel = remember(registry) {
        WorkspaceViewModel(
            registry = registry,
            sessionStore = FileWorkspaceSessionStore(),
            idGenerator = { UUID.randomUUID().toString() },
        )
    }

    // The frame, once it exists, so the tray's Show has something to raise.
    // Declared out here because the tray lives alongside the application, not
    // inside the window it acts on. Before the windows, too: reminders must
    // arrive whether or not the calendar is on screen.
    var mainFrame by remember { mutableStateOf<ComposeWindow?>(null) }

    val trayState = rememberTrayState()

    val driveWidgetHost = remember(graph, viewModels) {
        (graph as? AppGraph.Ready)?.let { ready ->
            DriveWidgetHost(
                ready = ready,
                scope = scope,
                openPermissions = { viewModels.home?.state?.value?.permissions ?: ProjectPermissions.Empty },
                openProjectId = { authViewModel?.currentState?.activeProject?.id },
            )
        }
    }

    AppTray(
        trayState = trayState,
        graph = graph,
        preferences = preferences,
        windowState = windowState,
        frame = mainFrame,
        driveWidgetOpen = driveWidgetOpen,
        onToggleDriveWidget = { driveWidgetOpen = !driveWidgetOpen },
        // The same shutdown the close button runs, geometry and all — a second
        // way out of the app must not be a way to lose your window layout.
        onQuit = { quitZillit(windowState) },
    )

    // Desktop banners for what arrives while the user is elsewhere. Mounted
    // beside the tray because that presence is what the OS delivers through.
    IncomingAlerts(
        graph = graph,
        preferences = preferences,
        trayState = trayState,
        frame = mainFrame,
        chat = viewModels.chat,
        email = viewModels.email,
        crewName = { id ->
            (graph as? AppGraph.Ready)?.projectContext?.context?.value?.user(id)?.fullName
        },
    )

    ZillitWindows(
        graph = graph,
        viewModels = viewModels,
        preferences = preferences,
        windowState = windowState,
        registry = registry,
        viewModel = workspaceViewModel,
        authViewModel = authViewModel,
        driveWidget = DriveWidgetMount(
            host = driveWidgetHost,
            open = driveWidgetOpen,
            onClose = { driveWidgetOpen = false },
            showMain = { showMainWindow(mainFrame, windowState) },
        ),
        showMain = { showMainWindow(mainFrame, windowState) },
        onFrame = { mainFrame = it },
    )
}

/** What the widget window needs from the application, gathered so ZillitWindows stays readable. */
private class DriveWidgetMount(
    val host: DriveWidgetHost?,
    val open: Boolean,
    val onClose: () -> Unit,
    val showMain: () -> Unit,
)

/**
 * Ends the session.
 *
 * Shared by the window's close button and the tray's Quit. The work itself
 * lives in [Shutdown], because ⌘Q reaches none of this and has to arrive at
 * the same place.
 */
private fun ApplicationScope.quitZillit(windowState: WindowState) {
    // Both this and the shutdown hook end up in the same place; Shutdown.run
    // is idempotent, and on an ordinary quit both really do reach it.
    Shutdown.remember(windowState.geometry())
    // Here rather than in the hook: this caller is still alive, so the write
    // can block until it lands. It also closes the gap left by the debounce —
    // resize, then quit within the settle, and the last move still counts.
    Shutdown.saveGeometryNow()
    Shutdown.run()
    exitApplication()
}

/** The window's size and position, as the preference store keeps them. */
/**
 * How long the window must sit still before its position is written.
 *
 * A drag emits a position every frame; without this the store would take a few
 * hundred writes to move a window across a desk.
 */
private const val GEOMETRY_SETTLE_MILLIS = 400L

/** How long the server gets to apply a read before counts are refetched. */
private const val READ_BADGE_SETTLE_MILLIS = 1_500L

private fun WindowState.geometry() = WindowGeometry(
    width = size.width.value.toInt().coerceAtLeast(1),
    height = size.height.value.toInt().coerceAtLeast(1),
    x = position.takeIf { it.isSpecified }?.x?.value?.toInt(),
    y = position.takeIf { it.isSpecified }?.y?.value?.toInt(),
)

/**
 * The app's windows: the main frame, plus one OS window per torn-off tool.
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // One parameter and one block per OS window; see the doc.
private fun ApplicationScope.ZillitWindows(
    graph: AppGraph,
    viewModels: AppViewModels,
    preferences: PreferenceStore,
    windowState: WindowState,
    registry: ToolRegistry,
    viewModel: WorkspaceViewModel,
    authViewModel: AuthViewModel?,
    driveWidget: DriveWidgetMount,
    /** Raises and focuses the main frame. See [showMainWindow]. */
    showMain: () -> Unit,
    onFrame: (ComposeWindow) -> Unit,
) {
    val workspace by viewModel.state.collectAsState()
    val themeMode by preferences
        .observeAs(ZillitPreferences.ThemeMode, ThemeMode::fromId)
        .collectAsState(initial = ThemeMode.System)

    val systemDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System -> systemDark
    }

    val os = remember { currentPlatform().os }
    val scope = rememberCoroutineScope()

    // Snapshotted *and written* as it changes, because a shutdown hook is the
    // wrong thread and the wrong moment for both — see `Shutdown.run`. The
    // effect restarts on every move, so the delay debounces a drag into one
    // write at the end of it rather than one per frame.
    LaunchedEffect(windowState.size, windowState.position) {
        val geometry = windowState.geometry()
        Shutdown.remember(geometry)
        delay(GEOMETRY_SETTLE_MILLIS)
        runCatching { preferences.saveWindowGeometry(geometry) }
    }

    Window(
        onCloseRequest = { quitZillit(windowState) },
        state = windowState,
        title = "Zillit-Desktop",
        icon = androidx.compose.ui.res.painterResource("icons/zillit-icon.png"),
        // Preview so shortcuts beat focused controls, but unhandled keys fall
        // through — a handler that swallows everything breaks typing.
        onPreviewKeyEvent = { event ->
            WorkspaceShortcuts.resolve(event, workspace, os)?.also(viewModel::onEvent) != null
        },
    ) {
        LaunchedEffect(window) { onFrame(window) }

        ZillitTheme(darkTheme = isDark) {
            // Inside the theme: the picker styles its page from the app's own
            // tokens. A no-op until the graph is Ready and until something
            // actually asks to pick — Chromium starts on first use.
            LocationPickerMount(graph) {
                ZillitContent(
                    graph = graph,
                    registry = registry,
                    viewModels = viewModels,
                    workspaceViewModel = viewModel,
                    authViewModel = authViewModel,
                    themeMode = themeMode,
                    onThemeModeChange = { mode ->
                        scope.launch { preferences.set(ZillitPreferences.ThemeMode, mode.name) }
                    },
                )
            }
        }
    }

    // Torn-off tools, each in its own OS window — declared outside the main
    // Window so the OS owns them independently (plan §3.2).
    DetachedToolWindows(
        state = workspace,
        registry = registry,
        onEvent = viewModel::onEvent,
        darkTheme = isDark,
        graph = graph,
    )

    // The popped-out video call: its own always-on-top OS window, for the
    // same reason — it must outlive being behind the main frame.
    (graph as? AppGraph.Ready)?.let { ready ->
        CallWindow(ready = ready, calls = viewModels.calls, darkTheme = isDark)
    }

    /*
     * Bringing the call home has to actually show it.
     *
     * "Move the call into the Zillit window" closes the call's own window and
     * re-homes the call into the main one — but nothing raised the main
     * window, so on a machine where it was behind something the call simply
     * disappeared. The one moment anybody reaches for that control is during a
     * screen share, when the main window is guaranteed to be behind the thing
     * being shared, which is why this read as "unable to get back to the call".
     *
     * Only on the transition, and only while a call is actually running:
     * raising on recomposition would steal focus continuously, and raising
     * when a call merely ends would yank the user out of whatever they moved
     * on to.
     */
    viewModels.calls?.let { calls ->
        val callState by calls.state.collectAsState()
        var wasPoppedOut by remember { mutableStateOf(false) }
        LaunchedEffect(callState.pipOpen, callState.phase) {
            if (wasPoppedOut &&
                !callState.pipOpen &&
                callState.phase == com.zillit.desktop.feature.calls.domain.CallPhase.InCall
            ) {
                showMain()
            }
            wasPoppedOut = callState.pipOpen
        }
    }

    // The Drive widget: the desktop's own small window onto one production's
    // drive, tied to the main window's session. See DriveWidgetWindow.
    DriveWidgetWindow(
        host = driveWidget.host,
        auth = authViewModel,
        preferences = preferences,
        visible = driveWidget.open,
        darkTheme = isDark,
        onClose = driveWidget.onClose,
        showMain = driveWidget.showMain,
    )
}

/**
 * Chooses between the configuration error, sign-in, and the shell.
 *
 * The shell is gated on a selected project rather than merely on a verified
 * device: every tool is project-scoped, so opening the workspace without one
 * would show a frame full of tools that cannot load anything.
 */
/**
 * One-way effects from the auth flow to the app module.
 *
 * Extracted so `ZillitContent` stays about *what is on screen*: these are
 * side-effect subscriptions, and reading them inline made the branch below hard
 * to find.
 */
/**
 * The rail, with live counts.
 *
 * The entries themselves are fixed — see [DefaultRailItems] for why they are not
 * derived from `project/tools` — except Admin, which only coordinators are
 * offered (`railItemsFor`).
 *
 * Two sources of number, because they count different things. Most badges are
 * unread counts from the server. Admin's is how many people are waiting to be
 * approved, which the unread endpoint has nothing to say about: it is asked with
 * `section=tools_label`, so the approval queues report their own length back
 * through the settings state.
 */
private fun railItemsWith(
    badges: BadgeCounts,
    isAdmin: Boolean,
    pendingApprovals: Int,
    /** The chat feature's live count — not the section's; see the call site. */
    cncBadge: Int,
): List<RailItem> = railItemsFor(isAdmin).map { item ->
    item.copy(
        badge = when (item.id) {
            ADMIN_RAIL_ID -> pendingApprovals
            "cnc" -> cncBadge
            else -> badges.section(item.badgeKey())
        },
    )
}

/**
 * How a rail section maps onto a badge group.
 *
 * The unread endpoint reports by area (`cnc_label`, `email_label`), not by the
 * rail's own ids.
 */
private fun RailItem.badgeKey(): String = when (id) {
    "cnc" -> "cnc_label"
    "email" -> "email_label"
    "home" -> "home_label"
    // The settings section's server rows are the admin approval queues and
    // nothing else (web `getSettingsBadges`, `badgeUtils.js:413-437`). This
    // rail counts those on its own Admin Settings entry, so counting the
    // section here too showed the same event twice — once per entry.
    "settings" -> ""
    // The SOS feed counts its own segment, as the web's side menu does
    // (`SideMenu.jsx`'s `sosBadges`). Help has nothing to count and must not
    // fall through to the tools total.
    "sos" -> SOS_BADGE_SEGMENT
    "help" -> ""
    else -> "tools_label"
}

private val ADMIN_RAIL_ID = AdminRailItem.id

/**
 * Which tool identifier a route belongs to.
 *
 * The workspace deals in routes; badges are keyed by the backend's tool
 * identifier. This is the one place that translates, so neither side has to know
 * about the other's naming.
 */
private fun HomeUiState.identifierFor(route: WorkspaceRoute): String =
    gridTools.firstOrNull { it.route.path == route.path }?.identifier ?: route.path

/**
 * The count a window tab wears.
 *
 * Grid tools badge by tool identifier; the four rail windows are not grid
 * tools — their paths (`/email`, `/cnc`, `/home`) matched nothing and every
 * such tab read zero forever. They badge by their section instead, the same
 * number the rail item beside them shows.
 */
private fun BadgeCounts.forWindow(route: WorkspaceRoute, homeState: HomeUiState): Int =
    when (route.path.trimEnd('/')) {
        "/home", "/home/tools" -> section("home_label")
        "/cnc" -> section("cnc_label")
        "/email" -> section("email_label")
        else -> get(homeState.identifierFor(route))
    }

/**
 * Feeds Home's socket events into the board.
 *
 * Subscribed once for the app rather than per screen: the flow is hot, and a
 * subscription tied to a window would stop delivering the moment that window
 * was closed or torn off.
 */
@Composable
private fun HomeRealtime(ready: AppGraph.Ready, feed: HomeFeedViewModel?) {
    if (feed == null) return

    LaunchedEffect(ready, feed) {
        ready.homeRealtime.stream.collect { event ->
            feed.onEvent(HomeFeedEvent.Realtime(event))
        }
    }
}

/**
 * Feeds one reused board's socket events into its feed — the boards ride the
 * Home engine but each has its own wire prefix (`info:message:added` and so
 * on, `listenerSocket.js`). Subscribed once for the app, like Home's, and
 * unconditional: an unknown board key is an empty flow, not an error.
 */
@Composable
private fun BoardRealtime(ready: AppGraph.Ready, board: String, feed: HomeFeedViewModel?) {
    if (feed == null) return

    LaunchedEffect(ready, feed) {
        boardRealtime(
            events = ready.socketEvents,
            board = board,
            decryptBody = { hex ->
                when (val result = ready.noticeDecryptor.decryptFromHex(hex)) {
                    is ZillitResult.Success -> result.data
                    is ZillitResult.Failure -> "[This message could not be decrypted]"
                }
            },
        ).collect { event -> feed.onEvent(HomeFeedEvent.Realtime(event)) }
    }
}

/**
 * Feeds the mailbox's socket events into it.
 *
 * Subscribed once for the app, like Home's: the flow is hot, and a subscription
 * tied to the mail window would stop delivering the moment it was closed — so
 * mail arriving while the user is in another tool would go unnoticed until they
 * came back and refreshed by hand.
 */
@Composable
private fun EmailRealtime(ready: AppGraph.Ready, mailbox: EmailViewModel?) {
    if (mailbox == null) return

    LaunchedEffect(ready, mailbox) {
        ready.emailRealtime.stream.collect { event ->
            mailbox.onEvent(EmailEvent.Realtime(event))
        }
    }
}

/**
 * Keeps unread counts current.
 *
 * The socket says *that* something changed; the counts are then refetched.
 * Android instead adjusts a running total per message — ~900 lines of
 * arithmetic whose drift is permanent until a restart. A refetch on an already
 * cheap endpoint buys correctness for one request.
 */
/**
 * The dock icon carries the production-wide total, like the phone's app icon.
 * Cleared (empty string) rather than set to zero — a dock "0" reads as one
 * unread called zero.
 */
@Composable
private fun DockBadge(ready: AppGraph.Ready) {
    val total = ready.badgeStore.counts.collectAsState().value.total
    LaunchedEffect(total) {
        runCatching {
            val taskbar = java.awt.Taskbar.getTaskbar()
            if (taskbar.isSupported(java.awt.Taskbar.Feature.ICON_BADGE_TEXT)) {
                taskbar.setIconBadge(total.takeIf { it > 0 }?.toString().orEmpty())
            } else {
                ZillitLog.d("Badges") { "dock badge unsupported on this platform" }
            }
        }.onFailure { ZillitLog.w("Badges") { "dock badge failed: ${it.message}" } }
    }
}

/**
 * Reads a film tool's badge when its window comes to the front.
 *
 * Every client clears a tool's count from inside the tool; the desktop's
 * tools live in workspace windows, and "the window on top" is that moment.
 * The read is tool-wide (`notification:level:read` scoped by tool, iOS's
 * `emitForBadgeReadLevels` with no levels) — the finer per-tab reads inside
 * a tool are the tool screen's own business as it grows them, exactly as on
 * mobile; without this the grid's tiles and the Film Tools rail count could
 * never fall from here at all.
 */
@Composable
private fun ToolReadOnFocus(ready: AppGraph.Ready, viewModels: AppViewModels, workspace: WorkspaceViewModel) {
    val homeState by (viewModels.home?.state ?: MutableStateFlow(HomeUiState())).collectAsState()
    val workspaceState by workspace.state.collectAsState()
    val active = workspaceState.activeWindow?.rootRoute
    val identifier = active?.let { route ->
        homeState.gridTools.firstOrNull { it.route.path == route.path }?.identifier
    }
    LaunchedEffect(identifier) {
        val tool = identifier ?: return@LaunchedEffect
        emitToolRead(ready, tool)
    }
}

/**
 * The Admin rail badge, counted before anyone opens Admin.
 *
 * The approval queues are what that number means, and they were only asked
 * once the Admin page mounted — so a coordinator saw 0 waiting until they
 * went to look, which is the trip the badge exists to save. Asked here, for
 * admins only, once the production is open; the pages keep it current after.
 */
@Composable
private fun ApprovalCounts(viewModels: AppViewModels) {
    val approvals = viewModels.approvals ?: return
    val settingsState by viewModels.settings.state.collectAsState()
    if (!settingsState.account.isAdmin) return

    LaunchedEffect(approvals) {
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.NewCrew))
        approvals.onEvent(ApprovalsEvent.Opened(ApprovalQueue.ProfileChanges))
    }
    val approvalState by approvals.state.collectAsState()
    val waitingCrew = approvalState.crew.items.size
    val waitingChanges = approvalState.profiles.items.size
    LaunchedEffect(waitingCrew, waitingChanges) {
        viewModels.settings.onEvent(SettingsEvent.ApprovalsCounted(waitingCrew, waitingChanges))
    }
}

@Composable
private fun BadgeRefresh(ready: AppGraph.Ready, signedIn: Boolean) {
    // Nothing to count once signed out — and the store was cleared at
    // sign-out; a collector left running would fill it back in from the next
    // socket event on the shared connection (found in QA: badges kept
    // arriving after logout). Keying on `signedIn` cancels both effects.
    if (!signedIn) return
    // The socket only ever says "changed" — someone must ask first; the
    // asking is ProjectScopedLoads' (counts need a production in the
    // headers — asked earlier the server answers 406).
    // A reconnect may have swallowed any number of change events; what the
    // counts are now is a question only the server can answer.
    val socketState by ready.socketEvents.connectionState.collectAsState()
    LaunchedEffect(socketState.isConnected) {
        if (socketState.isConnected) ready.badgeStore.refresh()
    }
    LaunchedEffect(ready) {
        // A burst of `notification:save` (one per record) must cost one
        // refetch, not one each — but a *sustained* stream must not starve
        // the refetch either, which is what a plain trailing debounce did.
        // So: coalesce arrivals inside a window, refetch once per window.
        val arrivals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        launch {
            // Missed calls ride their own event, not `notification:save` —
            // iOS increments its CnC count directly off it. See
            // `Calls.MissedCall`.
            val moved = ZillitSocketEvents.Badges.All + ZillitSocketEvents.Calls.MissedCall
            ready.socketEvents.onAny(moved).collect { arrivals.trySend(Unit) }
        }
        for (@Suppress("UNUSED_VARIABLE") signal in arrivals) {
            delay(BADGE_EVENT_SETTLE_MILLIS)
            ready.badgeStore.refresh()
        }
    }
}

/** Coalesces a burst of notification events into one counts refetch. */
private const val BADGE_EVENT_SETTLE_MILLIS = 600L

/**
 * Rereads the tool grid when the production's tool set moves under it — a
 * switch flipped in Admin Settings (here or on another device), a group made
 * or renamed, this user's rights on a tool changed. The grid's permissions
 * gate every feature, so this listens app-wide rather than only while the
 * Tools tab is open; Android does the same from its base socket listener.
 */
@Composable
private fun ToolsRefresh(ready: AppGraph.Ready, home: HomeViewModel?) {
    if (home == null) return
    LaunchedEffect(ready, home) {
        val moved = ZillitSocketEvents.ToolsGrid.All + ZillitSocketEvents.AccessGrid.All
        ready.socketEvents.onAny(moved).collect {
            home.onEvent(HomeEvent.Reload)
        }
    }
}

/**
 * Everything that must be (re)fetched when a production opens.
 *
 * Keyed on the production id, so switching reloads rather than carrying the
 * previous one's rights and board.
 */
@Composable
private fun ProjectScopedLoads(
    projectId: String?,
    viewModels: AppViewModels,
    workspace: WorkspaceViewModel,
    badges: BadgeStore? = null,
) {
    // Counts belong to a production: the previous one's numbers are wrong the
    // moment a different project opens, and showing them while the fetch is
    // out would badge the new production with the old one's unread. Cleared
    // here; fetched by the graph's open sequence once the headers carry the
    // new project — a fetch from here raced that and doubled the requests.
    LaunchedEffect(projectId) {
        if (projectId == null) return@LaunchedEffect
        badges?.clear()
    }
    // Every screen here holds a production's data. The view models outlive a
    // switch — they are built once per graph — so each one is told, rather
    // than only the two that used to be, which left the previous production's
    // mail and conversations on screen under the new production's name.
    LaunchedEffect(projectId) {
        if (projectId == null) return@LaunchedEffect
        // A production opens on its Home board, not on "Nothing open" — the
        // same landing every other client gives. Guarded so a restored
        // workspace (or anything already open) is never stomped.
        if (workspace.currentState.windows.isEmpty()) {
            workspace.onEvent(WorkspaceEvent.Open(WorkspaceRoute.Tool("/home")))
        }
        viewModels.home?.onEvent(HomeEvent.Reload)
        // Every notice board forgets the last production — its posts, its
        // tab, and the half-typed draft, which used to carry over into the
        // next production's composer. Home reloads at once (it is what opens);
        // the other boards load when their tool is next shown.
        listOf(
            viewModels.homeFeed,
            viewModels.info,
            viewModels.confidentialInfo,
            viewModels.reports,
            viewModels.catering,
            viewModels.accounts,
        ).forEach { it?.onEvent(HomeFeedEvent.ProjectChanged) }
        viewModels.homeFeed?.onEvent(HomeFeedEvent.Load)
        viewModels.chat?.onEvent(ChatEvent.ProjectChanged)
        viewModels.email?.onEvent(EmailEvent.ProjectChanged)
        viewModels.calendar?.onEvent(CalendarEvent2Event.ProjectChanged)
        // Rights are per-production, so both finance tools re-resolve who the
        // viewer is rather than carrying the previous production's answer.
        viewModels.cashExpenses?.onProjectChanged()
        viewModels.cardExpenses?.onProjectChanged()
        viewModels.purchaseOrders?.onProjectChanged()
        viewModels.timecards?.onProjectChanged()
        viewModels.payroll?.onProjectChanged()
        viewModels.dealMemos?.onProjectChanged()
        // The library tools are gated by the tool-access grid, which is
        // per-production too — carrying the previous production's grid would
        // show someone a Send button on a shoot they may only read.
        viewModels.docDist?.onProjectChanged()
        viewModels.drive?.onProjectChanged()
        // The rights spreadsheet is the previous production's until it reloads.
        viewModels.permissionGrid?.onProjectChanged()
        // The outside-contact directory is the previous production's until it reloads.
        viewModels.externalUsers?.onProjectChanged()
    }
}

/**
 * Everything that runs alongside the screen rather than because of it.
 *
 * Gathered into one call so the content function reads as a list of screens,
 * not a list of screens interleaved with subscriptions.
 */
@Composable
@Suppress("LongParameterList")
private fun BackgroundWork(
    ready: AppGraph.Ready,
    authViewModel: AuthViewModel,
    createViewModel: CreateProductionViewModel,
    joinViewModel: JoinProductionViewModel,
    viewModels: AppViewModels,
    workspace: WorkspaceViewModel,
) {
    val auth by authViewModel.state.collectAsState()
    SessionExpiry(ready, authViewModel)
    EndCallOnSignOut(ready, signedIn = auth.step == AuthStep.Complete)
    AuthEffects(authViewModel, createViewModel, joinViewModel)
    BadgeRefresh(ready, signedIn = auth.step == AuthStep.Complete)
    ToolsRefresh(ready, viewModels.home)
    DockBadge(ready)
    ApprovalCounts(viewModels)
    ToolReadOnFocus(ready, viewModels, workspace)
    HomeRealtime(ready, viewModels.homeFeed)
    EmailRealtime(ready, viewModels.email)
    BoardRealtime(ready, "info", viewModels.info)
    BoardRealtime(ready, "confidentialinfo", viewModels.confidentialInfo)
    BoardRealtime(ready, "reports", viewModels.reports)
    BoardRealtime(ready, "script-notes", viewModels.scriptNotes)
    BoardRealtime(ready, "catering", viewModels.catering)
    // The Accounts board's segment key is the singular "account".
    BoardRealtime(ready, "account", viewModels.accounts)
}

/**
 * Sends the user back to the QR screen when their session dies.
 *
 * Collected here rather than in any one feature: the request that discovers a
 * dead session is whichever fired next — a badge count, a mail sync, a reminder
 * refresh — and none of those screens should own signing out. The ViewModel
 * decides whether a given 401 actually means expiry.
 */
@Composable
private fun SessionExpiry(ready: AppGraph.Ready, authViewModel: AuthViewModel) {
    LaunchedEffect(ready) {
        ready.sessionExpired.collect { authViewModel.onEvent(AuthEvent.SessionExpired) }
    }
}

@Composable
private fun AuthEffects(
    authViewModel: AuthViewModel,
    createViewModel: CreateProductionViewModel,
    joinViewModel: JoinProductionViewModel,
) {
    // A requested production belongs in the list straight away, marked as
    // waiting — otherwise the user sends a request and sees nothing change.
    LaunchedEffect(joinViewModel) {
        joinViewModel.effects.collect { effect ->
            when (effect) {
                JoinEffect.Requested -> authViewModel.onEvent(AuthEvent.ReloadProjects)
                JoinEffect.Dismissed -> authViewModel.onEvent(AuthEvent.DismissJoin)
            }
        }
    }

    // A new production must appear in the list behind the dialog, and it is the
    // one the user almost certainly wants to open next.
    LaunchedEffect(createViewModel) {
        createViewModel.effects.collect { authViewModel.onEvent(AuthEvent.ReloadProjects) }
    }

    // Only the app module opens a browser. Feature modules raise an effect and
    // stay unable to launch anything themselves.
    LaunchedEffect(authViewModel) {
        authViewModel.effects.collect { effect ->
            if (effect is AuthEffect.OpenUrl) openInBrowser(effect.url)
        }
    }
}

@Composable
@Suppress("LongParameterList") // The screens' shared inputs; see the doc.
private fun ZillitContent(
    graph: AppGraph,
    registry: ToolRegistry,
    viewModels: AppViewModels,
    workspaceViewModel: WorkspaceViewModel,
    /** Built with the application (the widget shares it); null only when [graph] is not ready. */
    authViewModel: AuthViewModel?,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    if (graph is AppGraph.Unconfigured) {
        UnconfiguredScreen(graph.reason)
        return
    }

    val ready = graph as AppGraph.Ready

    // API keys gate everything: without them no request carries a valid header,
    // so every call would 401 with nothing to explain why.
    var hasApiKeys by remember { mutableStateOf(ready.hasApiKeys) }
    if (!hasApiKeys) {
        ApiKeySetupScreen(setup = ready.apiKeySetup, onConfigured = { hasApiKeys = true })
        return
    }

    val authViewModel = authViewModel ?: remember { buildAuth(ready) }
    val joinViewModel = remember { buildJoin(ready) }
    val createViewModel = remember {
        CreateProductionViewModel(
            projectRepository = ready.projectRepository,
            presetRepository = ready.presetRepository,
            authRepository = ready.authRepository,
        )
    }
    val authState by authViewModel.state.collectAsState()

    // Rights are per production. Reloading on switch rather than rebuilding the
    // ViewModel keeps one owner of the permission set for the session.
    ProjectScopedLoads(authState.activeProject?.id, viewModels, workspaceViewModel, ready.badgeStore)

    BackgroundWork(ready, authViewModel, createViewModel, joinViewModel, viewModels, workspaceViewModel)

    if (authState.step == AuthStep.Complete) {
        SignedInShell(ready, registry, viewModels, workspaceViewModel, authViewModel, themeMode, onThemeModeChange)
    } else {
        AuthScreen(
            viewModel = authViewModel,
            // The production list carries the theme toggle, as on the web —
            // it is the first screen a signed-in user sees, and the shell is
            // not reachable until they pick a production.
            themeMode = themeMode,
            onThemeModeChange = onThemeModeChange,
            createViewModel = createViewModel,
            joinViewModel = joinViewModel,
        )
    }
}

/**
 * The frame around a signed-in session: rail, tabs, status bar, calls, and
 * the sync queue's dialog. Split from [ZillitContent] so the auth branches
 * and the shell branch each read on their own.
 */
@Composable
private fun SignedInShell(
    ready: AppGraph.Ready,
    registry: ToolRegistry,
    viewModels: AppViewModels,
    workspaceViewModel: WorkspaceViewModel,
    authViewModel: AuthViewModel,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val authState by authViewModel.state.collectAsState()
    val homeState by (viewModels.home?.state ?: MutableStateFlow(HomeUiState())).collectAsState()
    val badges by ready.badgeStore.counts.collectAsState()
    val socketState by ready.socketEvents.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    val syncStatus by (ready.syncEngine?.status ?: MutableStateFlow(SyncStatus())).collectAsState()
    var pendingChangesOpen by remember { mutableStateOf(false) }

    // Once at sign-in, then every six hours. A desktop app stays open for
    // days, so a launch-only check leaves someone on a stale build for a
    // week; six hours is well inside Remote Config's own SDK default.
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Unknown) }
    LaunchedEffect(Unit) {
        while (true) {
            updateStatus = ready.appUpdateChecker.check()
            delay(UPDATE_CHECK_INTERVAL_MILLIS)
        }
    }

    // Whether the rail offers Admin at all, and what is waiting behind it.
    // Read from the settings state rather than the project: it is the same
    // flag the admin page itself gates on, so the rail and the page cannot
    // disagree about who is a coordinator.
    val settingsState by viewModels.settings.state.collectAsState()

    // The C&C rail and window tab wear the chat feature's own number — the
    // server's `cnc_label` section also counts rooms the user lost, which
    // every phone hides (see `ChatUiState.chatsBadge`, verified 2026-08-19:
    // 40 of 45 "unread" sat in rooms absent from `chat-room`).
    val chatBadgeState by (viewModels.chat?.state
        ?: MutableStateFlow(com.zillit.desktop.feature.chat.ui.ChatUiState())).collectAsState()
    val cncBadge = chatBadgeState.chatsBadge + chatBadgeState.callsBadge

    Box {
        AppShell(
        viewModel = workspaceViewModel,
        registry = registry,
        themeMode = themeMode,
        onThemeModeChange = onThemeModeChange,
        projectName = authState.activeProject?.name,
        statusText = statusText(socketState, syncStatus),
        statusAction = syncStatusAction(syncStatus) { pendingChangesOpen = true },
        updateNotice = updateStatus.toNotice(),
        // The guarded launcher — https only, as the auth links use.
        onDownloadUpdate = ::openInBrowser,
        railItems = railItemsWith(
            badges = badges,
            isAdmin = settingsState.account.isAdmin,
            pendingApprovals = settingsState.admin.pendingTotal,
            cncBadge = cncBadge,
        ),
        // Tabs read the same store as the rail — two sources would disagree
        // the moment one missed an update.
        badgeFor = { route ->
            if (route.path.trimEnd('/') == "/cnc") cncBadge else badges.forWindow(route, homeState)
        },
        // The rail's Logout: the same sign-out Settings runs (device/unlink,
        // then the local wipe); the auth view model watches the session and
        // takes the frame back to the QR screen.
        onSignOut = { scope.launch { ready.authRepository.signOut() } },
        // Behind the Zillit mark in the top bar, as on the phones.
        notificationsRoute = WorkspaceRoute.Tool(NOTIFICATIONS_PATH),
        notificationBadge = badges.section(GLOBAL_BADGE_SEGMENT),
        onSwitchProject = {
            // Windows are project-scoped (plan M3). Leaving them open would
            // carry one production's content into another's workspace.
            workspaceViewModel.onEvent(WorkspaceEvent.CloseAllForProjectSwitch)
            authViewModel.onEvent(AuthEvent.SwitchProject)
        },
        )

        CallSurface(ready, viewModels.calls)
        ready.syncEngine?.let { engine ->
            PendingChangesDialog(
                engine = engine,
                connectivity = ready.connectivity,
                visible = pendingChangesOpen,
                onDismiss = { pendingChangesOpen = false },
            )
        }
    }
}

/**
 * Shown when no `zillit.properties` could be found.
 *
 * A deployment problem the user can be told about and act on, rather than a
 * stack trace on a black window.
 */
@Composable
private fun UnconfiguredScreen(reason: String) {
    Box(
        modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.canvas),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(UNCONFIGURED_WIDTH),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText("Zillit is not configured", style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = "This installation has no server configuration, so it cannot sign in. " +
                    "Contact your administrator.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitText(
                text = reason,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

private val UNCONFIGURED_WIDTH = 480.dp

/**
 * Restores the window's saved geometry.
 *
 * Read synchronously, before the first frame. `runBlocking` is otherwise
 * forbidden (plan §11.2), but there is no UI thread to block yet, and applying
 * geometry after the window is on screen makes it visibly jump.
 */
@Suppress("ForbiddenMethodCall")
@Composable
private fun rememberRestoredWindowState(preferences: PreferenceStore): WindowState {
    val geometry = remember { runBlocking { preferences.loadWindowGeometry() } }
    return rememberWindowState(
        size = DpSize(geometry.width.dp, geometry.height.dp),
        position = if (geometry.hasPosition) {
            WindowPosition(geometry.x!!.dp, geometry.y!!.dp)
        } else {
            WindowPosition.PlatformDefault
        },
    )
}

/**
 * The mailbox, wired to whatever local storage this machine managed to open.
 *
 * Mail without a cache still works — it just re-downloads each folder — so an
 * unopenable database degrades the mailbox rather than removing it.
 */
private fun buildMailbox(ready: AppGraph.Ready): EmailViewModel {
    val cache = ready.emailCache?.let { store ->
        // Resolved per call: the mailbox outlives any one production, and
        // capturing the id would file one project's mail under another's.
        SqlMailboxCache(store) {
            ready.projectContext?.context?.value?.project?.projectId.orEmpty()
        }
    } ?: InMemoryMailboxCache()

    val mailbox = Mailbox(ready.emailRepository, cache)

    return EmailViewModel(
        mailbox = mailbox,
        repository = ready.emailRepository,
        draftRepository = ready.draftRepository,
        folderEditor = FolderEditor(ready.folderRepository),
        search = MailSearch { mailbox.cachedMessages() },
        nowMillis = System::currentTimeMillis,
        // The badge ledger's read, alongside the mailbox's own — one email,
        // one record, referenced by id.
        badges = com.zillit.desktop.feature.email.ui.MailBadges(
            onMessageRead = { messageId ->
                emitSegmentRead(ready, segment = "email_label", module = "email_label", referenceId = messageId)
            },
            // Per-folder unread from the badge ledger — the unit is the folder.
            folderBadges = { sectionSplit(ready, "email_label", "unit") },
        ),
        downloader = AttachmentDownloader(ready.emailRepository, DownloadsAttachmentStore()),
    )
}

/**
 * Every tool the app can open.
 *
 * Real providers replace their placeholders, so a feature that is not wired yet
 * still resolves to something rather than an empty window.
 */

/**
 * Picks one file with the OS dialog; the returned pending upload does the
 * slow half — poster extraction, then the routed store — after the thread
 * has its bubble up, reporting percent into the bubble's bar.
 */
private suspend fun pickChatAttachment(
    ready: AppGraph.Ready,
): com.zillit.desktop.feature.chat.domain.PendingChatUpload? {
    val picked = com.zillit.desktop.feature.email.data.FilePicker().pick().firstOrNull()
        ?: return null

    // The bytes ride along so the thread's preview dialog can show (and for a
    // picture, edit) the file before anything uploads; the upload then takes
    // the possibly edited bytes back.
    return com.zillit.desktop.feature.chat.domain.PendingChatUpload(
        name = picked.name,
        contentType = picked.contentType,
        bytes = picked.bytes,
    ) { bytes, onProgress ->
        uploadChatMedia(ready, picked.name, picked.contentType, bytes, onProgress)
    }
}

/**
 * Chat's route to storage for a named blob — the board's own capture: a video
 * or PDF gets a poster frame, and the frame travels as its own upload, the
 * same shape mail and notices use. Serves both the picker's files and the
 * composer's pasted images (ChatViewModel's `uploadMedia` seam).
 */
private suspend fun uploadChatMedia(
    ready: AppGraph.Ready,
    name: String,
    contentType: String,
    bytes: ByteArray,
    onProgress: (Int) -> Unit,
): ChatAttachment? {
    val media = homeMediaCapture(ready)
    val withPoster = media.videoThumbnail(
        com.zillit.desktop.feature.home.domain.PickedMedia(
            name = name,
            contentType = contentType,
            bytes = bytes,
        ),
    )
    val stored = (media.upload?.invoke(withPoster, onProgress)
        as? com.zillit.desktop.core.common.ZillitResult.Success)?.data

    return stored?.let {
        ChatAttachment(
            media = it.media,
            name = it.fileName,
            contentType = it.contentType,
            bucket = it.bucket.orEmpty(),
            region = it.region.orEmpty(),
            thumbnail = it.thumbnail.orEmpty(),
            widthPx = (it.widthPx ?: 0).toLong(),
            heightPx = (it.heightPx ?: 0).toLong(),
            durationMillis = it.durationMillis ?: 0,
        )
    }
}

/** Fetches a message's file to Downloads and hands it to the OS. */
private fun openChatAttachment(ready: AppGraph.Ready, file: ChatAttachment) {
    openNoticeAttachment(ready, appAttachmentScope)(
        com.zillit.desktop.feature.home.domain.NoticeAttachment(
            media = file.media,
            fileName = file.name,
            bucket = file.bucket,
            region = file.region,
        ),
    )
}

private val appAttachmentScope =
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default)

/**
 * The mailbox, with sender faces where the sender is crew here.
 *
 * Most mail is not from the production, so the lookup answers null far more
 * often than not — which is exactly what the initials avatar is for.
 */
// `ready` is not nullable here: the mailbox view model is itself built from a
// ready graph, so there is no state in which this is called without one — and
// the composer's repositories have no sensible null form.
private fun mailProvider(
    viewModel: EmailViewModel,
    ready: AppGraph.Ready,
    onOpenCalendar: () -> Unit,
) = EmailToolProvider(
    viewModel = viewModel,
    onOpenCalendar = onOpenCalendar,
    composing = Composing(
        repository = ready.emailRepository,
        drafts = ready.draftRepository,
        contacts = ready.contactRepository,
        signatures = ready.signatureRepository,
        // Crew come from the production context already loaded when the project
        // opened, so a composer costs one request rather than two.
        crew = { ready.projectContext?.context?.value?.crewContacts().orEmpty() },
        uploader = ready.attachmentUploader,
        chooseFiles = { FilePicker().pick() },
        newAttachmentId = { UUID.randomUUID().toString() },
        // Reply-all drops this address, so a reply never goes to the person
        // sending it.
        selfAddress = { ready.projectContext?.context?.value?.profile?.email.orEmpty() },
    ),
    messageById = { id -> viewModel.state.value.thread.firstOrNull { it.id == id } },
    // Reopening a draft reads the copy the Drafts folder already holds, rather
    // than re-fetching one the user is looking at.
    draftById = { id -> viewModel.state.value.draft(id) },
    loadAvatar = { address ->
        ready.projectContext?.context?.value?.users
            ?.firstOrNull { it.email?.equals(address, ignoreCase = true) == true }
            ?.let { user -> fetchAvatar(ready, user.userId)?.let(::decodeImageBitmap) }
    },
    // The same fetch the download path uses, decoded into a bitmap rather
    // than written to Downloads.
    loadThumbnail = { attachment, messageId ->
        val folder = viewModel.state.value.selectedFolder?.name.orEmpty()
        (
            ready.emailRepository.attachment(attachment.id, messageId, folder)
                as? com.zillit.desktop.core.common.ZillitResult.Success
            )?.data
            ?.let(::decodeBase64Default)
            ?.let(::decodeImageBitmap)
    },
)

/** Chat & Calls: the crew directory, from the users the project already syncs. */
/**
 * The Drive, wired to the four things it cannot do itself.
 *
 * The picker is asynchronous because the native dialog blocks its own thread;
 * launching it on [scope] and reporting back through the callback is what keeps
 * the compositor responsive while it is open.
 *
 * ## The editor does not go to the system browser
 *
 * [onOpenUrl] is the guarded launcher and is right for a presigned storage URL,
 * which is opaque and expires in an hour. The **editor** URL is not that: it
 * carries a WOPI session token good for eight hours against one document, and
 * `Desktop.browse` would put it in another application's history. It goes to
 * the app's own Chromium instead — see [DocumentEditorWindow], which also
 * explains why a runtime with no embedded browser is told so rather than
 * quietly falling back.
 */
private fun driveProvider(
    viewModel: DriveViewModel,
    scope: CoroutineScope,
    openWidget: () -> Unit,
) = DriveToolProvider(
    viewModel = viewModel,
    onOpenUrl = ::openInBrowser,
    onOpenWidget = openWidget,
    onPickFiles = { report ->
        scope.launch {
            val picked = DriveFilePicker().pick()
            if (picked.isNotEmpty()) report(picked)
        }
    },
    onCopy = ::copyToClipboard,
    onOpenEditor = { url, fileName ->
        DocumentEditorWindow.open(
            url = url,
            fileName = fileName,
            scope = scope,
            // Reported through the view model so it lands on the same toast as
            // every other failure, rather than in a dialog of its own.
            onUnavailable = { reason -> viewModel.onEditorUnavailable(reason) },
        )
    },
)

/** Puts [text] on the system clipboard. Failures are logged, never thrown. */
internal fun copyToClipboard(text: String) {
    runCatching {
        java.awt.Toolkit.getDefaultToolkit().systemClipboard
            .setContents(java.awt.datatransfer.StringSelection(text), null)
    }.onFailure {
        com.zillit.desktop.core.common.ZillitLog.w("Drive") { "could not reach the clipboard" }
    }
}

private fun buildAuth(ready: AppGraph.Ready) = AuthViewModel(
    authRepository = ready.authRepository,
    projectRepository = ready.projectRepository,
    qrLoginRepository = ready.qrLoginRepository,
    nowMillis = System::currentTimeMillis,
    projectUnread = { fetchProjectUnread(ready) },
    projectListStore = ready.projectListCache?.let(::CachedProjectList),
    // Offline, only a production this computer has seen before can open.
    isOnline = { ready.connectivity.online.value },
    hasOfflineData = { projectId -> ready.projectContext?.hasCached(projectId) == true },
)

/**
 * One area's unread, split one level down — `?section=<area>&group=<by>`.
 *
 * The same drill-down the store's standing queries use, asked per screen:
 * chat's tabs by tool, mail's folders by unit. Failure answers null and the
 * screen keeps its last split — a badge that is late beats one that is gone.
 */
private suspend fun sectionSplit(ready: AppGraph.Ready, section: String, groupBy: String): Map<String, Int>? =
    when (
        val got = ready.badgeDrilldown.unread(
            com.zillit.desktop.core.badges.BadgeDrilldownQuery(groupBy = groupBy, section = section),
        )
    ) {
        is ZillitResult.Success -> got.data
        is ZillitResult.Failure -> null
    }

/**
 * `GET device/unread` — unread per production, before any is open.
 *
 * The one badge question with device scope: the listing's cards wear the
 * answer. The row shape is tolerated loosely (`project_id` + `unread`)
 * because only the web still calls this endpoint and its store discards the
 * response shape immediately; unknown rows count nothing rather than fail.
 */
private suspend fun fetchProjectUnread(ready: AppGraph.Ready): Map<String, Int> {
    val rows = ready.apiClient.request(
        verb = com.zillit.desktop.core.network.HttpVerb.Get,
        url = "${ready.config.apiV2(com.zillit.desktop.core.config.ZillitService.Notification)}device/unread",
        serializer = kotlinx.serialization.builtins.ListSerializer(
            kotlinx.serialization.json.JsonElement.serializer(),
        ),
        module = com.zillit.desktop.core.network.RequestModule.Device,
    )
    val data = (rows as? ZillitResult.Success)?.data ?: return emptyMap()
    val counts = mutableMapOf<String, Int>()
    data.forEach { element ->
        val row = element as? kotlinx.serialization.json.JsonObject ?: return@forEach
        val id = (row["project_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: (row["data"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: return@forEach
        val unread = (row["unread"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content?.toIntOrNull() ?: 0
        if (unread > 0) counts[id] = (counts[id] ?: 0) + unread
    }
    if (counts.isEmpty() && data.isNotEmpty()) {
        ZillitLog.w("Badges") {
            "device/unread rows carried no project ids (keys=${
                (data.firstOrNull() as? kotlinx.serialization.json.JsonObject)?.keys
            })"
        }
    }
    return counts
}

/** The C&C area's tool identifier — Android `Constants.CNC_CHAT_TYPE` (Constants.kt:1898). */
private const val CNC_TOOL_IDENTIFIER = "cnc_section"

private fun chatProvider(
    ready: AppGraph.Ready,
    viewModel: ChatViewModel?,
    calls: CallViewModel?,
    audioPlayer: com.zillit.desktop.core.designsystem.component.AudioPlayer?,
    canDownload: () -> Boolean = { true },
) = ChatToolProvider(
    player = audioPlayer,
    loadAudio = { file -> fetchChatAudio(ready, file) },
    canDownload = canDownload,
    // The keep-name-private honour is applied here, before the screen ever
    // sees the list — the same rule Android's members tab keeps.
    crew = {
        ready.projectContext?.context?.value?.users.orEmpty()
            .filterNot { it.keepNamePrivate }
            .filter { it.fullName.isNotBlank() }
            // Invited-but-not-joined people are not someone to message yet.
            .filter { it.hasJoined() }
            .map { user ->
                CrewContact(
                    userId = user.userId,
                    fullName = user.fullName,
                    // Translated words, not the wire's label key — and the
                    // placeholder "member" designation dropped here, as the
                    // raw key it is on the wire, before translation hides it
                    // from the comparison.
                    designation = user.designationText(),
                    department = user.department
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Labels.translate(it) },
                    email = user.email,
                    isAdmin = user.isAdmin,
                    deviceId = user.deviceId,
                    lastActiveMillis = user.lastActiveMillis,
                    // "left"/"removed" stay listed (Android keeps them in the
                    // roster) but the thread shows Disconnected and refuses
                    // sends — ChatAndGroupPage.kt:362.
                    hasLeft = user.status == "left" || user.status == "removed",
                )
            }
    },
    // Hidden from Contacts: the signed-in user is not someone to message.
    selfId = { ready.projectContext?.context?.value?.profile?.userId },
    loadAvatar = { userId -> fetchAvatar(ready, userId)?.let(::decodeImageBitmap) },
    viewModel = viewModel,
    onCall = calls?.let { vm ->
        { peer, isGroup, video, mediasoup ->
            vm.onEvent(
                CallEvent.Place(
                    // A group is rung by its room; a person by their device.
                    chatRoomId = if (isGroup) peer.userId else "",
                    receiverDeviceId = if (isGroup) "" else peer.deviceId.orEmpty(),
                    mode = if (isGroup) CallMode.Group else CallMode.Private,
                    type = if (video) CallType.Video else CallType.Audio,
                    displayName = peer.fullName,
                    provider = if (mediasoup) CallProvider.Mediasoup else CallProvider.Agora,
                    // Line 1 rings a person rather than one of their devices
                    // — and a group has no person to name. For a group `peer`
                    // IS the room, so passing its id here would put a room id
                    // in a list of user ids and ring nobody, silently.
                    receiverUserId = if (isGroup) "" else peer.userId,
                ),
            )
        }
    },
    callLog = calls?.let { { CallLogTab(ready, it) } },
    // The Chats tab's "New group" and its message search — both straight off
    // the repository; the provider hides the affordances when absent.
    createRoom = { name, members -> ready.chatRepository.createRoom(name, members) },
    searchMessages = { query -> ready.chatRepository.searchMessages(query) },
    deleteRoom = { roomId -> ready.chatRepository.deleteRoom(roomId) },
    onOpenAttachment = { file -> openChatAttachment(ready, file) },
    loadThumbnail = { file -> fetchChatImage(ready, file, preview = true) },
    // The lightbox's fetch: the object itself, not its poster.
    loadFullImage = { file -> fetchChatImage(ready, file, preview = false) },
    // "Open in Maps" on a shared location — the same guarded launcher the
    // map, sides and distribution tools take (https only).
    onOpenUrl = ::openInBrowser,
)

/**
 * A chat picture's bytes, decoded — the bubble's preview or the lightbox's
 * full object, through the same storage source the boards use. The
 * `thumbnail` key must travel: without it the preview fetch asked S3 for a
 * blank object and showed nothing where the picture belonged.
 */
private suspend fun fetchChatImage(
    ready: AppGraph.Ready,
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
    preview: Boolean,
): androidx.compose.ui.graphics.ImageBitmap? =
    (
        ready.noticeMedia.fetch(
            com.zillit.desktop.feature.home.domain.NoticeAttachment(
                media = file.media,
                fileName = file.name,
                thumbnail = file.thumbnail,
                bucket = file.bucket,
                region = file.region,
            ),
            preview = preview,
        ) as? com.zillit.desktop.core.common.ZillitResult.Success
        )?.data?.let(::decodeImageBitmap)

/**
 * Who this person is, as the two finance tools need to know it.
 *
 * Read from the crew list rather than the profile: `user/profile` carries the
 * name and the admin flag but not the department or designation, and those two
 * are what decide whether someone processes other people's money or only
 * submits their own. Resolved per call so it is correct after a production
 * switch, and so the tools built at startup are not fixed to whoever was open
 * then — see the view models' `viewer` parameter.
 */
private fun AppGraph.Ready.cashViewer(): CashViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return CashViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
    )
}

private fun AppGraph.Ready.poViewer(): PoViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return PoViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
        isProjectAdmin = context?.isAdmin == true,
    )
}

private fun AppGraph.Ready.timecardViewer(): TimecardViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return TimecardViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
    )
}

private fun AppGraph.Ready.payrollViewer(): PayrollViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return PayrollViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
    )
}

/** `Unknown` and `UpToDate` both mean "render nothing". */
private fun UpdateStatus.toNotice(): UpdateNotice? = when (this) {
    is UpdateStatus.Available -> UpdateNotice(latestVersion, mandatory = false, downloadUrl = downloadUrl)
    is UpdateStatus.Required -> UpdateNotice(latestVersion, mandatory = true, downloadUrl = downloadUrl)
    UpdateStatus.Unknown, UpdateStatus.UpToDate -> null
}

private fun AppGraph.Ready.dealViewer(permissions: ProjectPermissions): DealViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return DealViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
        // The admin override rides along in `canPost` — "an administrator
        // can reach everything", as the admin grid puts it.
        hasPostingRights = permissions.canPost(DEAL_MEMO_TOOL_IDENTIFIER),
    )
}

/** The web's `TOOLS_NAME.deal_memo_tool` (`useDealMemoRights.js:36`). */
private const val DEAL_MEMO_TOOL_IDENTIFIER = "deal_memo_tool"

/**
 * Monday of the current week, in the machine's own zone.
 *
 * The timecard tool asks for it rather than computing it, because "this week"
 * is a wall-clock question and the module is common code with no clock in it.
 */
private fun currentWeekStarting(): Long {
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    val today = kotlinx.datetime.Instant
        .fromEpochMilliseconds(System.currentTimeMillis())
        .toLocalDateTime(zone)
        .date
    val monday = today.minus(today.dayOfWeek.ordinal, kotlinx.datetime.DateTimeUnit.DAY)
    return monday.atStartOfDayIn(zone).toEpochMilliseconds()
}

/**
 * Who this person is, as the two library tools need to know it.
 *
 * Different from the finance viewers above, and deliberately: Document
 * Distribution and the Drive are gated by the production's **tool access grid**
 * (`view_access` / `posting_access` / `download_access`), not by department and
 * designation. That grid arrives with `project/tools`, which Home fetches — so
 * [permissions] reads Home's resolved answer rather than issuing a second call
 * for the same list.
 *
 * Resolved per call so it is correct after a production switch, and so an
 * admin's mid-session rights change is picked up on the next open.
 */
private fun AppGraph.Ready.docDistViewer(permissions: ProjectPermissions): DocDistViewer {
    val context = projectContext?.context?.value
    return DocDistViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        // The reply-to on everything this person sends. Blank is fine — the
        // server falls back to the production's own address.
        userEmail = context?.profile?.email.orEmpty(),
    )
}

/**
 * Who is looking at the Account Hub.
 *
 * Two inputs, and they are not the same question. The **tool grid** decides
 * whether the console opens at all; the **accounts department** decides whether
 * its screens are editable. The web gated every hub route on department alone
 * and left people sitting inside the finance module after their tool access was
 * revoked (ZL-20533), so both are passed here and the module keeps them apart.
 */
private fun AppGraph.Ready.accountHubViewer(permissions: ProjectPermissions): AccountHubViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return AccountHubViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        // Substring, as the web matches it: the identifier is compound on some
        // productions ("production_accounts").
        isAccountant = AccountHubViewer.isAccountsDepartment(listOfNotNull(me?.department)),
    )
}

private fun AppGraph.Ready.driveViewer(permissions: ProjectPermissions): DriveViewer {
    val context = projectContext?.context?.value
    return DriveViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
    )
}

private fun AppGraph.Ready.callSheetViewer(permissions: ProjectPermissions): CallSheetViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return CallSheetViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
        // From the crew list, not the profile — the profile carries neither
        // department nor designation.
        designation = me?.designation.orEmpty(),
    )
}

private fun AppGraph.Ready.productionReportViewer(
    permissions: ProjectPermissions,
    kind: ReportKind = ReportKind.Production,
): ReportViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return ReportViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
        designation = me?.designation.orEmpty(),
        toolIdentifier = kind.toolIdentifier,
    )
}

/**
 * One report engine, three tools: the production report, and the AD / Wrap
 * reports that ride the same service under `shared.reportType`.
 */
private fun AppGraph.Ready.buildReport(
    kind: ReportKind,
    permissions: () -> ProjectPermissions,
    today: () -> kotlinx.datetime.LocalDate,
): ReportViewModel = ReportViewModel(
    repository = ReportRepositoryImpl(
        apiClient,
        config,
        bus = socketEvents,
        currentProjectId = { projectContext?.context?.value?.project?.projectId },
    ),
    kind = kind,
    delivery = productionReportDelivery(),
    callSheets = productionReportCallSheets(CallSheetRepositoryImpl(apiClient, config)),
    resolveViewer = { productionReportViewer(permissions(), kind) },
    projectId = { projectContext?.context?.value?.project?.projectId },
    membersProvider = { reportMembers() },
    todayYmd = {
        val day = today()
        "${day.year}-" +
            "${day.monthNumber.toString().padStart(2, '0')}-" +
            day.dayOfMonth.toString().padStart(2, '0')
    },
)

/** The crew as the call sheet's employee sections and pickers need them. */
private fun AppGraph.Ready.sheetMembers(): List<SheetMember> =
    projectContext?.context?.value?.users.orEmpty().map { user ->
        SheetMember(
            userId = user.userId,
            fullName = user.fullName,
            department = user.department.orEmpty(),
            designation = user.designation.orEmpty(),
        )
    }

/** The same crew, in the report module's own type. */
private fun AppGraph.Ready.reportMembers():
    List<com.zillit.desktop.feature.productionreport.domain.SheetMember> =
    projectContext?.context?.value?.users.orEmpty().map { user ->
        com.zillit.desktop.feature.productionreport.domain.SheetMember(
            userId = user.userId,
            fullName = user.fullName,
            department = user.department.orEmpty(),
            designation = user.designation.orEmpty(),
        )
    }

/**
 * Today, in the machine's own zone.
 *
 * The library groups by production date and heads the buckets "Today" and
 * "Yesterday"; that is a wall-clock question, and the module is common code
 * with no clock in it.
 */
private fun today(): kotlinx.datetime.LocalDate {
    val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
    return kotlinx.datetime.Instant
        .fromEpochMilliseconds(System.currentTimeMillis())
        .toLocalDateTime(zone)
        .date
}

private fun AppGraph.Ready.cardViewer(): CardViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return CardViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
    )
}

/** Every screen's view model, built once per graph and shared by every window. */
@Suppress("LongParameterList") // One field per screen; a holder object per pair would be worse.
internal class AppViewModels(
    val home: HomeViewModel?,
    val chat: ChatViewModel?,
    val homeFeed: HomeFeedViewModel?,
    val calendar: CalendarViewModel?,
    val email: EmailViewModel?,
    val settings: SettingsViewModel,
    /** Null without a session: there is no production to approve anyone onto. */
    val approvals: ApprovalsViewModel?,
    /** The administration pages. Null without a production to administer. */
    val admin: AdminViewModel?,
    /** The reader's own pages behind Settings. Null without a session. */
    val account: AccountViewModel?,
    val calls: CallViewModel?,
    /** The finance tools. Null before the graph is configured. */
    val cashExpenses: CashExpensesViewModel?,
    val cardExpenses: CardExpensesViewModel?,
    val purchaseOrders: PurchaseOrderViewModel?,
    val timecards: TimecardViewModel?,
    val payroll: PayrollViewModel?,
    val dealMemos: DealMemoViewModel?,
    /** The finance console that hosts the rest of the accounting tools. */
    val accountHub: AccountHubViewModel?,
    /** The embedded budget application's launch page. */
    val budgetBuilder: BudgetBuilderViewModel?,
    /** Standard forms, documents for signature, and the signature block. */
    val formSignature: FormSignatureViewModel?,
    /** Envelopes with typed fields, flattened server-side. */
    val esignature: EsignViewModel?,
    /** The day's call sheet: compose, review, publish. */
    val callSheet: CallSheetViewModel?,
    /** The daily production report, seeded from the last call sheet. */
    val productionReport: ReportViewModel?,
    /** AD and Wrap reports: the production-report engine on their own templates. */
    val adReport: ReportViewModel?,
    val wrapReport: ReportViewModel?,
    /** Script sides: scripts in, scene picks, server-generated PDFs out. */
    val sides: SidesViewModel?,

    /** The production's viewing & posting rights, as a spreadsheet. */
    val permissionGrid: PermissionGridViewModel?,
    /** The Info board — the Home feed engine on the `info` segment. */
    val info: HomeFeedViewModel?,
    /** The Confidential Info board — same engine, `confidentialinfo` segment. */
    val confidentialInfo: HomeFeedViewModel?,
    /** Camera & Sound Report — the same engine on the script-notes host, one tab per report unit. */
    val reports: HomeFeedViewModel?,
    /** Script Notes — the board engine on the script-notes host: takes, daily progress, continuity notes. */
    val scriptNotes: HomeFeedViewModel?,
    /** Catering and Message Accounts — the same engine on the unit host, tabs from each tool's units. */
    val catering: HomeFeedViewModel?,
    val accounts: HomeFeedViewModel?,
    /** The production diary: typed date blocks plus events and notes. */
    val boxSchedule: BoxScheduleViewModel?,
    /** Cities, typed pins and studio zones — the map tool without tiles. */
    val maps: MapViewModel?,
    /** Recce: scout-day plans — date, rendezvous, stops, personnel. */
    val recce: RecceViewModel?,
    /** External Users: the production's outside-contact directory. */
    val externalUsers: com.zillit.desktop.feature.externalusers.ui.ExternalUsersViewModel?,
    /** Distribution List: the per-user × per-unit email opt-in matrix. */
    val distribution: com.zillit.desktop.feature.distribution.ui.DistributionViewModel?,
    /** Crew List: the grouped roster and its generated PDF. */
    val crewList: com.zillit.desktop.feature.crewlist.ui.CrewListViewModel?,
    /** Asset Register: PO lines as assets — category, note, export. */
    val assetRegister: com.zillit.desktop.feature.assetreport.ui.AssetViewModel?,
    /** Transportation: vehicles, pickup requests, permanent allocations, drivers. */
    val transport: TransportViewModel?,
    /** Zillit Draft: the screenwriting editor, scripts kept on this machine per production. */
    val draft: DraftViewModel?,
    /** Location: the scouting library — photos, videos and links by place. */
    val location: LocationViewModel?,
    /** Continuity: photos, videos and documents by scene, per department and forwarded. */
    val continuity: ContinuityViewModel?,
    /** Cost Report: the crew-facing live worksheet and posted snapshots. */
    val costReport: CostReportViewModel?,
    /** Invoices: accounts payable — the department view, and the accountant pages. */
    val invoices: InvoicesViewModel?,
    /** The three PDF distribution tools — one engine, three [DistributionTool]s. */
    val scheduleDistribution: DistributionViewModel?,
    val scriptDistribution: DistributionViewModel?,
    val scheduleDod: DistributionViewModel?,
    /** The two library tools: what the production issues, and what it keeps. */
    val docDist: DocDistViewModel?,
    val drive: DriveViewModel?,
)

/**
 * The Film Tools grid's desktop-only section: tools this app provides without
 * a server entry. Zillit Draft keeps its scripts on this machine, so it is on
 * every production and needs no rights.
 */
private fun localToolSections(): List<ToolSection> = listOf(
    ToolSection(
        title = "Writing",
        tools = listOf(
            ToolPresentation(
                identifier = "zillit_draft",
                label = "Zillit Draft",
                icon = ZillitIcons.Edit,
                route = WorkspaceRoute.Tool(DRAFT_PATH),
            ),
        ),
        identifier = null,
    ),
)

/**
 * The Drive view model, for Zillit Draft's "Send PDF to Drive": built after
 * it in the same factory, so it is read through a holder rather than
 * captured. Set once the view models exist.
 */
private val driveHolder = java.util.concurrent.atomic.AtomicReference<DriveViewModel?>(null)

@Composable
// Linear construction of every screen; splitting it hides the set, and the
// "complexity" is one null-guard per screen rather than any branching logic.
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun rememberAppViewModels(
    graph: AppGraph,
    preferences: PreferenceStore,
    scope: CoroutineScope,
): AppViewModels {
    val ready = graph as? AppGraph.Ready
    val settings = remember(graph) { buildSettings(graph, preferences, scope) }
    return remember(graph, settings) {
        // Hoisted out of the constructor call because two other view models
        // read from it: the tool-access grid arrives with Home's `project/tools`
        // fetch, and the library tools are gated by it. Issuing a second call
        // for the same list would mean two answers that can disagree.
        val home = ready?.let {
            HomeViewModel(
                it.toolsRepository,
                offline = it.offlineSupport,
                // Gates the grid's customise entry, as the phones gate theirs.
                isAdmin = { it.projectContext?.context?.value?.isAdmin == true },
                localSections = localToolSections(),
            )
        }
        val permissions = { home?.state?.value?.permissions ?: ProjectPermissions.Empty }

        AppViewModels(
            home = home,
            chat = ready?.let {
                ChatViewModel(
                    repository = it.chatRepository,
                    presence = it.chatPresence,
                    presenceProjectId = { it.projectContext?.context?.value?.project?.projectId },
                    nowMillis = System::currentTimeMillis,
                    newUniqueId = { UUID.randomUUID().toString() },
                    offline = it.offlineSupport,
                    // The same picker and routed uploader mail and the board
                    // use; the stored key rides the message envelope.
                    pickAttachment = { pickChatAttachment(it) },
                    // Pasted images take the same route to storage.
                    uploadMedia = { name, type, bytes, onProgress ->
                        uploadChatMedia(it, name, type, bytes, onProgress)
                    },
                    // The picture of a shared place — the same Static Maps
                    // image the boards already post beside their locations.
                    staticMap = { lat, lng -> fetchStaticMapBytes(it, lat, lng) },
                    voice = chatVoice(it),
                    loadFavourites = {
                        it.preferences.get(ZillitPreferences.ChatFavourites)
                            .split('\n').filter(String::isNotBlank).toSet()
                    },
                    saveFavourites = { stars ->
                        it.preferences.set(
                            ZillitPreferences.ChatFavourites,
                            stars.joinToString("\n"),
                        )
                    },
                    // Chat reads ride the chat protocol itself (read-untill,
                    // emitted by the repository) — `notification:read` is not
                    // chat's clearing mechanism on any client. This hook only
                    // refetches the counts once the server has the read.
                    onThreadRead = { _ ->
                        delay(READ_BADGE_SETTLE_MILLIS)
                        it.badgeStore.refresh()
                    },
                    // The area's split by tool: chat_label / call_label —
                    // what the Chats and Calls tabs wear.
                    sectionBadges = { sectionSplit(it, "cnc_label", "tool") },
                    // Looking at the log reads the missed calls (iOS
                    // `readCNCMessage(.misscall)`: notification:read on call_label).
                    onCallsViewed = {
                        emitSegmentRead(it, segment = "call_label", module = "cnc_label")
                    },
                )
            },
            homeFeed = ready?.let { buildHomeFeed(it, permissions) },
            calendar = ready?.let(::buildCalendar),
            email = ready?.let(::buildMailbox),
            settings = settings,
            approvals = ready?.let { graph ->
                ApprovalsViewModel(
                    repository = graph.approvalsRepository,
                    // The crew list the session already holds. Without it the
                    // profile-change queue cannot say what is changing — the
                    // server sends only the requested values.
                    knownCrew = { userId ->
                        graph.projectContext?.context?.value?.user(userId)?.let { member ->
                            KnownCrewMember(
                                fullName = member.fullName,
                                department = member.department,
                                designation = member.designation,
                            )
                        }
                    },
                    // Departments come from the join flow's repository and units
                    // from their own service. Adapted here rather than reached
                    // for directly: a settings module importing another
                    // feature's data layer is the coupling the module layout
                    // exists to prevent.
                    presets = ApprovalPresets { graph.crewPresets() },
                    nowMillis = System::currentTimeMillis,
                )
            },
            admin = ready?.let { graph ->
                AdminViewModel(
                    repository = graph.adminRepository,
                    // Named on the deletion confirmation, and read at call time
                    // rather than captured: a dialog naming the wrong
                    // production is the worst place for a stale value.
                    productionName = {
                        graph.projectContext?.context?.value?.project?.name.orEmpty()
                    },
                    // The grid rereads at once; its own socket echo may not come.
                    onToolsChanged = { home?.onEvent(HomeEvent.Reload) },
                )
            },
            account = ready?.let(::buildAccount),
            calls = ready?.let { graph ->
                CallViewModel(
                    coordinator = graph.callCoordinator,
                    crew = { graph.callableCrew() },
                    // Null in a dev run: the helper only exists in a packaged
                    // bundle, and without it Share sends the whole screen.
                    screenSources = macCaptureHelper()?.let(::MacScreenSources),
                )
            },
            cashExpenses = ready?.let { graph ->
                CashExpensesViewModel(graph.cashRepository) { graph.cashViewer() }
            },
            cardExpenses = ready?.let { graph ->
                CardExpensesViewModel(graph.cardRepository) { graph.cardViewer() }
            },
            purchaseOrders = ready?.let { graph ->
                PurchaseOrderViewModel(
                    repository = graph.purchaseOrderRepository,
                    viewer = { graph.poViewer() },
                    offline = graph.offlineSupport,
                )
            },
            timecards = ready?.let { graph ->
                TimecardViewModel(
                    repository = graph.timecardRepository,
                    viewer = { graph.timecardViewer() },
                    currentWeekStarting = ::currentWeekStarting,
                    offline = graph.offlineSupport,
                )
            },
            payroll = ready?.let { graph ->
                PayrollViewModel(
                    repository = graph.payrollRepository,
                    viewer = { graph.payrollViewer() },
                    now = { System.currentTimeMillis() },
                )
            },
            dealMemos = ready?.let { graph ->
                DealMemoViewModel(graph.dealMemoRepository) { graph.dealViewer(permissions()) }
            },
            accountHub = ready?.let { graph ->
                AccountHubViewModel(
                    repository = graph.accountHubRepository,
                    viewer = { graph.accountHubViewer(permissions()) },
                )
            },
            budgetBuilder = ready?.let { graph ->
                BudgetBuilderViewModel(
                    resolveViewer = { BudgetBuilderViewer.from(permissions()) },
                    // Both halves, or the launch page says "unconfigured": the
                    // service API and the web deployment that serves the page.
                    configured = graph.config.services
                        .containsKey(com.zillit.desktop.core.config.ZillitService.BudgetBuilder) &&
                        graph.config.services
                            .containsKey(com.zillit.desktop.core.config.ZillitService.BudgetBuilderWeb),
                    online = graph.connectivity.online,
                )
            },
            formSignature = ready?.let { graph ->
                FormSignatureViewModel(
                    repository = FormSignatureRepositoryImpl(
                        graph.apiClient,
                        graph.config,
                        bus = graph.socketEvents,
                    ),
                    transfer = graph.formSignatureTransfer(),
                    pdfWork = PdfBoxWork(),
                    resolveViewer = { FormSignatureViewer.from(permissions()) },
                    currentUserId = {
                        graph.projectContext?.context?.value?.profile?.userId.orEmpty()
                    },
                    newId = { UUID.randomUUID().toString() },
                )
            },
            esignature = ready?.let { graph ->
                EsignViewModel(
                    repository = EsignRepositoryImpl(
                        apiClient = graph.apiClient,
                        config = graph.config,
                        today = { esignToday() },
                        bus = graph.socketEvents,
                    ),
                    transfer = graph.esignTransfer(),
                    pdf = esignPdf(),
                    resolveViewer = { EsignViewer.from(permissions()) },
                    currentUserId = {
                        graph.projectContext?.context?.value?.profile?.userId.orEmpty()
                    },
                    currentUserName = {
                        graph.projectContext?.context?.value?.profile?.fullName.orEmpty()
                    },
                    signerOptions = { graph.esignSignerOptions() },
                    newId = { UUID.randomUUID().toString() },
                )
            },
            callSheet = ready?.let { graph ->
                CallSheetViewModel(
                    repository = CallSheetRepositoryImpl(
                        graph.apiClient,
                        graph.config,
                        bus = graph.socketEvents,
                        currentProjectId = {
                            graph.projectContext?.context?.value?.project?.projectId
                        },
                    ),
                    delivery = graph.callSheetDelivery(),
                    resolveViewer = { graph.callSheetViewer(permissions()) },
                    projectId = {
                        graph.projectContext?.context?.value?.project?.projectId
                    },
                    membersProvider = { graph.sheetMembers() },
                    companySeed = {
                        val project = graph.projectContext?.context?.value?.project
                        CompanySeed(
                            projectName = project?.name.orEmpty(),
                            companyName = project?.companyName.orEmpty(),
                        )
                    },
                    // Local MIDNIGHT, not the current instant: the web sends
                    // start-of-day, and the server's renderer prints whatever
                    // calendar day the epoch lands on in ITS zone — an
                    // afternoon epoch drifts a day east of the dateline.
                    todayMs = {
                        val zone = kotlinx.datetime.TimeZone.currentSystemDefault()
                        today().atStartOfDayIn(zone).toEpochMilliseconds()
                    },
                )
            },
            productionReport = ready?.buildReport(ReportKind.Production, permissions, ::today),
            adReport = ready?.buildReport(ReportKind.Ad, permissions, ::today),
            wrapReport = ready?.buildReport(ReportKind.Wrap, permissions, ::today),
            sides = ready?.let { graph ->
                SidesViewModel(
                    repository = SidesRepositoryImpl(
                        apiClient = graph.apiClient,
                        config = graph.config,
                        rawScenes = graph.sidesRawGet(),
                        bus = graph.socketEvents,
                    ),
                    transfer = graph.sidesTransfer(),
                    resolveViewer = {
                        val context = graph.projectContext?.context?.value
                        SidesViewer.from(
                            permissions = permissions(),
                            userId = context?.profile?.userId.orEmpty(),
                            displayName = context?.profile?.fullName.orEmpty(),
                        )
                    },
                )
            },
            permissionGrid = ready?.let { graph ->
                PermissionGridViewModel(
                    repository = PermissionGridRepositoryImpl(
                        apiClient = graph.apiClient,
                        config = graph.config,
                        // Your own row is hidden: revoking your own view rights
                        // from this screen is a door that locks behind you.
                        currentUserId = {
                            graph.projectContext?.context?.value?.profile?.userId
                        },
                        bus = graph.socketEvents,
                        currentProjectId = {
                            graph.projectContext?.context?.value?.project?.projectId
                        },
                    ),
                )
            },
            info = ready?.boardFeed(
                board = "info",
                toolIdentifier = "info_tool",
                permissions = permissions,
            ),
            confidentialInfo = ready?.boardFeed(
                board = "confidentialinfo",
                toolIdentifier = "confidential_info_tool",
                permissions = permissions,
            ),
            reports = ready?.reportsFeed(permissions),
            scriptNotes = ready?.scriptNotesFeed(permissions),
            catering = ready?.cateringFeed(permissions),
            accounts = ready?.accountsFeed(permissions),
            boxSchedule = ready?.let { graph ->
                BoxScheduleViewModel(
                    repository = BoxScheduleRepositoryImpl(
                        graph.apiClient,
                        graph.config,
                        bus = graph.socketEvents,
                        currentProjectId = {
                            graph.projectContext?.context?.value?.project?.projectId
                        },
                    ),
                    calendar = graph.diaryCalendarLookup(),
                    resolveViewer = { graph.boxScheduleViewer(permissions()) },
                    nowMillis = System::currentTimeMillis,
                )
            },
            maps = ready?.let { graph ->
                MapViewModel(
                    repository = MapRepositoryImpl(
                        graph.apiClient,
                        graph.config,
                        bus = graph.socketEvents,
                        currentProjectId = {
                            graph.projectContext?.context?.value?.project?.projectId
                        },
                    ),
                    resolveViewer = { graph.mapViewer(permissions()) },
                    canvas = graph.mapCanvas,
                )
            },
            recce = ready?.buildRecce(permissions),
            externalUsers = ready?.buildExternalUsers(permissions),
            distribution = ready?.buildDistributionList(permissions),
            crewList = ready?.buildCrewList(permissions),
            assetRegister = ready?.buildAssetRegister(permissions),
            location = ready?.buildLocation(permissions),
            continuity = ready?.buildContinuity(permissions),
            costReport = ready?.buildCostReport(permissions),
            invoices = ready?.buildInvoices(permissions, scope),
            draft = ready?.let { graph ->
                graph.buildDraft(
                    drive = { driveHolder.get() },
                    projectId = { graph.projectContext?.context?.value?.project?.projectId },
                )
            },
            transport = ready?.let { graph ->
                TransportViewModel(
                    repository = TransportRepositoryImpl(
                        graph.apiClient,
                        graph.config,
                        bus = graph.socketEvents,
                        currentProjectId = {
                            graph.projectContext?.context?.value?.project?.projectId
                        },
                    ),
                    resolveViewer = {
                        TransportViewer.from(permissions(),
                            graph.projectContext?.context?.value?.profile?.userId.orEmpty())
                    },
                    nowMillis = System::currentTimeMillis,
                    // The web's `notification:read` for a request segment, module `transportation_label`.
                    onSegmentViewed = { segment -> emitSegmentRead(graph, segment = segment,
                        module = "transportation_label") },
                )
            },
            scheduleDistribution = ready?.buildDistribution(DistributionTool.ScheduleDistribution, permissions),
            scriptDistribution = ready?.buildDistribution(DistributionTool.ScriptDistribution, permissions),
            scheduleDod = ready?.buildDistribution(DistributionTool.ScheduleDod, permissions),
            docDist = ready?.let { graph ->
                DocDistViewModel(
                    repository = graph.docDistRepository,
                    viewer = { graph.docDistViewer(permissions()) },
                    today = ::today,
                )
            },
            drive = ready?.let { graph ->
                DriveViewModel(
                    repository = graph.driveRepository,
                    viewer = { graph.driveViewer(permissions()) },
                    // The plain client, deliberately: presigned S3 PUTs must
                    // not carry the API's encrypted headers. See
                    // MultipartDriveUploader.
                    uploader = MultipartDriveUploader(graph.driveRepository, graph.httpClient),
                    newUploadId = { UUID.randomUUID().toString() },
                ).also(driveHolder::set)
            },
        )
    }
}

@Suppress("LongMethod", "CyclomaticComplexMethod") // Linear assembly; splitting it hides the set.
private fun buildRegistry(
    graph: AppGraph,
    viewModels: AppViewModels,
    scope: CoroutineScope,
    /** Opens the desktop Drive widget — offered from the Drive tool's header. */
    openDriveWidget: () -> Unit,
): ToolRegistry {
    val homeViewModel = viewModels.home
    val chatViewModel = viewModels.chat
    // One speaker for the whole app: the board pausing when a chat voice
    // note starts is this single instance, not a coincidence.
    val audioPlayer = (graph as? AppGraph.Ready)?.let { ClipAudioPlayer(scope) }
    val homeFeedViewModel = viewModels.homeFeed
    val calendarViewModel = viewModels.calendar
    val emailViewModel = viewModels.email
    val settingsViewModel = viewModels.settings
    // One board context for every notice board — Home, Info, Confidential
    // Info — so media, avatars and author lines resolve identically.
    val boardContext = (graph as? AppGraph.Ready).let { ready ->
                HomeBoardContext(
                    media = ready?.noticeMedia,
                    onOpenAttachment = ready
                        ?.let { openNoticeAttachment(it, scope) }
                        ?: {},
                    // One player for the whole board: overlapping voice notes
                    // are noise, so a new one takes the speaker from the old.
                    player = audioPlayer,
                    // The same guarded launcher the auth links use — https only.
                    onOpenLocation = { point -> openInBrowser(point.mapsUrl) },
                    onOpenLink = { url -> openInBrowser(url) },
                    loadAvatar = { userId -> ready?.let { fetchAvatar(it, userId) } },
                    crewNames = {
                        ready?.projectContext?.context?.value?.users
                            ?.map { it.fullName }
                            ?.filter { it.isNotBlank() }
                            .orEmpty()
                    },
                    // "Full Name (Designation)", the web's sender line. The
                    // wire does not name senders; the crew list does.
                    resolveAuthor = { senderId ->
                        ready?.projectContext?.context?.value?.user(senderId)?.authorLine()
                    },
                )
    }
    val home = homeViewModel?.let { vm ->
        homeFeedViewModel?.let { feed ->
            HomeToolProvider(
                viewModel = vm,
                feedViewModel = feed,
                calendarViewModel = calendarViewModel,
                // The store's flow itself, so tabs and tiles recompose as
                // counts move — the rail reads the same store.
                badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
                board = boardContext,
                // The grid's customise button opens the same switches Admin
                // Settings holds — one page, two doors, as the phones do it.
                customiseToolsRoute = AdminDestination.ToolAvailability.path,
            )
        }
    }
    // Info and Confidential Info ARE the notice board, on their own segments.
    val info = viewModels.info?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.INFO_PATH,
            title = "Info",
            icon = ZillitToolIcons.Info,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    // The diary answers at both paths: the tile's own, and the web mount that
    // the legacy pre-production tile shares.
    val boxSchedule = viewModels.boxSchedule?.let { BoxScheduleToolProvider(it, BOX_SCHEDULE_PATH) }
    val preProduction = viewModels.boxSchedule?.let { BoxScheduleToolProvider(it, PRE_PRODUCTION_PATH) }
    val maps = viewModels.maps?.let {
        MapToolProvider(
            it,
            onOpenUrl = ::openInBrowser,
            canvas = (graph as? AppGraph.Ready)?.let(::mapCanvasSurface),
        )
    }
    val recce = viewModels.recce?.let { RecceToolProvider(it, onOpenUrl = ::openInBrowser) }
    val externalUsers = viewModels.externalUsers?.let {
        com.zillit.desktop.feature.externalusers.ui.ExternalUsersToolProvider(it)
    }
    val distributionList = viewModels.distribution?.let {
        com.zillit.desktop.feature.distribution.ui.DistributionToolProvider(it)
    }
    val crewList = viewModels.crewList?.let {
        com.zillit.desktop.feature.crewlist.ui.CrewListToolProvider(it)
    }
    val assetRegister = viewModels.assetRegister?.let {
        com.zillit.desktop.feature.assetreport.ui.AssetToolProvider(it)
    }
    val transport = viewModels.transport?.let { TransportToolProvider(it) }
    val draft = viewModels.draft?.let { DraftToolProvider(it) }
    val location = viewModels.location?.let { vm -> (graph as? AppGraph.Ready)?.locationProvider(vm, scope) }
    val continuity = viewModels.continuity?.let { vm ->
        (graph as? AppGraph.Ready)?.continuityProvider(vm, scope)
    }
    val costReport = viewModels.costReport?.let { vm -> (graph as? AppGraph.Ready)?.costReportProvider(vm) }
    val invoices = viewModels.invoices?.let { invoicesProvider(it) }
    // Schedule Full & One Line, Script & Pages, Schedule D.O.D — the same
    // PDF-distribution engine at the web's three paths.
    val ready = graph as? AppGraph.Ready
    val scheduleDistribution = viewModels.scheduleDistribution?.let { vm ->
        ready?.distributionProvider(
            vm, DistributionToolProvider.SCHEDULE_PATH, "Schedule Full & One Line", ZillitToolIcons.Chedule, scope,
        )
    }
    val scriptDistribution = viewModels.scriptDistribution?.let { vm ->
        ready?.distributionProvider(
            vm, DistributionToolProvider.SCRIPT_PATH, "Script & Pages Distribution", ZillitToolIcons.Script, scope,
        )
    }
    val scheduleDod = viewModels.scheduleDod?.let { vm ->
        ready?.distributionProvider(vm, DistributionToolProvider.DOD_PATH, "Schedule D.O.D", ZillitToolIcons.Dod, scope)
    }
    val confidentialInfo = viewModels.confidentialInfo?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.CONFIDENTIAL_INFO_PATH,
            title = "Confidential Info",
            icon = ZillitToolIcons.Info,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    val catering = viewModels.catering?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.CATERING_PATH,
            title = "Catering",
            icon = ZillitToolIcons.Catering,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    val accounts = viewModels.accounts?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.ACCOUNTS_PATH,
            title = "Message Accounts",
            icon = ZillitToolIcons.Account,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    // Camera & Sound Report: the same board, its tabs the report units the
    // script-notes service lists.
    val reports = viewModels.reports?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.REPORTS_PATH,
            title = "Camera & Sound Report",
            icon = ZillitToolIcons.ProductionReport,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    val scriptNotes = viewModels.scriptNotes?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.SCRIPT_NOTES_PATH,
            title = "Script Notes",
            icon = ZillitToolIcons.ScriptNote,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    val email = emailViewModel?.let { mailbox ->
        (graph as? AppGraph.Ready)?.let { ready ->
            // The mail drawer's Calendar row: the production's own calendar,
            // which is a unit on the Home board rather than a mail-only one.
            mailProvider(mailbox, ready) {
                // Selecting the unit is all the mail side does; the Home
                // window itself is opened by the provider's navigator.
                viewModels.homeFeed?.let { feed ->
                    feed.currentState.units.firstOrNull { it.kind == HomeUnitKind.Calendar }
                        ?.let { unit -> feed.onEvent(HomeFeedEvent.SelectUnit(unit.id)) }
                }
            }
        }
    }
    val chat = (graph as? AppGraph.Ready)?.let {
        chatProvider(
            it, chatViewModel, viewModels.calls, audioPlayer,
            // The C&C tool's download right (Android gates saves with
            // msg_download_right on the same flag). A production whose tools
            // list never mentions the tool leaves chat ungated, as the
            // phones' chat page is.
            canDownload = canDownload@{
                val permissions = viewModels.home?.state?.value?.permissions
                    ?: return@canDownload true
                if (permissions.tools.none { tool -> tool.identifier == CNC_TOOL_IDENTIFIER }) {
                    return@canDownload true
                }
                permissions.canDownload(CNC_TOOL_IDENTIFIER)
            },
        )
    }
    val signatures = (graph as? AppGraph.Ready)?.let {
        SignatureToolProvider(it.signatureRepository)
    }
    // openInBrowser is the guarded launcher — https only, as the auth links use.
    val settings = SettingsToolProvider(
        viewModel = settingsViewModel,
        onOpenExternal = ::openInBrowser,
        account = viewModels.account,
        onCopy = ::copyToClipboard,
    )
    // Its own window, off the rail. Shares the settings view model, which holds
    // the admin state — see AdminSettingsToolProvider.
    val admin = AdminSettingsToolProvider(settingsViewModel, viewModels.approvals, viewModels.admin)
    // The mail drawer's other two windows — Settings and Contacts.
    val mailSettings = (graph as? AppGraph.Ready)?.let { ready ->
        EmailSettingsToolProvider(
            apiClient = ready.apiClient,
            config = ready.config,
            crew = { ready.projectContext?.context?.value?.crewContacts().orEmpty() },
            isAdmin = { ready.projectContext?.context?.value?.isAdmin == true },
            onCopy = ::copyToClipboard,
        )
    }
    val mailContacts = (graph as? AppGraph.Ready)?.let { ready ->
        EmailContactsToolProvider(apiClient = ready.apiClient, config = ready.config)
    }
    // The rail's foot: SOS, and the two app pages beside it.
    val sos = (graph as? AppGraph.Ready)?.let { ready ->
        SosToolProvider(
            viewModel = SosViewModel(
                repository = SosRepositoryImpl(ready.apiClient, ready.config),
                nowMillis = System::currentTimeMillis,
                viewer = { ready.projectContext?.context?.value.sosViewer() },
                crew = { ready.projectContext?.context?.value.sosCrew() },
            ),
            onOpenLink = ::openInBrowser,
        )
    }
    // The rail's foot: the web side menu's Pin to Start and Zillit Help.
    // The bell page: the phones' notification list.
    val notifications = (graph as? AppGraph.Ready)?.let { ready ->
        NotificationsToolProvider(
            viewModel = NotificationsViewModel(
                repository = ready.notificationsRepository,
                nowMillis = System::currentTimeMillis,
                // Reading the list is what marks the global segment read on
                // the phones; the badge store hears about it on the next poll.
                onListRead = { ready.badgeStore.refresh() },
            ),
        )
    }
    val help = HelpToolProvider(onOpenExternal = ::openInBrowser, onContactSupport = ::contactSupport)
    val cash = viewModels.cashExpenses?.let { CashExpensesToolProvider(it) }
    val cards = viewModels.cardExpenses?.let { CardExpensesToolProvider(it) }
    val orders = viewModels.purchaseOrders?.let { PurchaseOrderToolProvider(it) }
    val timecards = viewModels.timecards?.let { TimecardToolProvider(it) }
    val payroll = viewModels.payroll?.let { PayrollToolProvider(it) }
    val deals = viewModels.dealMemos?.let { DealMemoToolProvider(it) }
    val distribution = viewModels.docDist?.let {
        // openInBrowser is the guarded launcher — https only, so a presigned
        // storage URL opens and anything else is refused.
        DocDistToolProvider(it, onOpenUrl = ::openInBrowser)
    }
    val drive = viewModels.drive?.let { driveProvider(it, scope, openDriveWidget) }
    // The console hands off to the finance tools above via its own window
    // navigator, so it needs nothing from here beyond its view model.
    val accountHub = viewModels.accountHub?.let { AccountHubToolProvider(it) }
    // The launch is the host's act — a loopback gateway plus a Chromium
    // window — so the provider is handed a launcher, not a repository.
    val budgetBuilder = viewModels.budgetBuilder?.let { viewModel ->
        BudgetBuilderToolProvider(viewModel) { onProblem ->
            (graph as? AppGraph.Ready)?.let { ready ->
                BudgetBuilderWindow.open(ready, scope, onProblem)
            }
        }
    }
    // The picker runs on IO and answers back on the caller's thread; a null
    // answer is a cancelled dialog and is passed through as such.
    val formSignature = viewModels.formSignature?.let { vm ->
        FormSignatureToolProvider(vm) { onPicked ->
            scope.launch { onPicked(pickPdf()) }
        }
    }
    val esignature = viewModels.esignature?.let { vm ->
        EsignToolProvider(vm) { onPicked ->
            scope.launch { onPicked(pickPdf()) }
        }
    }
    val callSheet = viewModels.callSheet?.let { CallSheetToolProvider(it) }
    val sides = viewModels.sides?.let { vm ->
        SidesToolProvider(
            viewModel = vm,
            onPickPdf = { onPicked -> scope.launch { onPicked(pickPdf()) } },
            onOpenUrl = ::openInBrowser,
        )
    }
    val permissionGrid = viewModels.permissionGrid?.let { vm ->
        PermissionGridToolProvider(
            viewModel = vm,
            // Resolved when the window is first shown, not here: the registry
            // is built before any production is open.
            viewer = {
                PermissionGridViewer.from(
                    homeViewModel?.state?.value?.permissions ?: ProjectPermissions.Empty,
                )
            },
        )
    }
    val productionReport = viewModels.productionReport?.let { ProductionReportToolProvider(it) }
    val adReport = viewModels.adReport?.let { ProductionReportToolProvider(it) }
    val wrapReport = viewModels.wrapReport?.let { ProductionReportToolProvider(it) }
    val real = listOfNotNull(
        home, chat, email, signatures, mailSettings, mailContacts, settings, admin, notifications,
        sos, help,
        cash, cards, orders, timecards, payroll, deals, distribution, drive,
        accountHub, budgetBuilder, formSignature, esignature,
        callSheet, productionReport, adReport, wrapReport, sides, permissionGrid,
        info, confidentialInfo, reports, scriptNotes,
        catering, accounts,
        boxSchedule, preProduction, maps, recce, externalUsers, distributionList, crewList,
        assetRegister, transport, location, continuity, costReport, invoices, draft,
        scheduleDistribution, scriptDistribution, scheduleDod,
    )
    val realPaths = real.map { it.path }.toSet()
    return ToolRegistry(real + placeholderTools().filterNot { it.path in realPaths })
}

/**
 * The production's crew, as addressable contacts.
 *
 * Anyone without a mail address is dropped — they cannot be written to, and an
 * unsendable suggestion is worse than no suggestion.
 *
 * Names are withheld for crew who asked to keep theirs private at registration.
 * The address still shows, since that is what addressing requires, but the app
 * does not put their name in front of the rest of the unit. Android carries this
 * flag through its email module and never reads it.
 */
/** Who is asking, for the SOS page's gating — see `SosViewer`. */
private fun ProjectContext?.sosViewer(): SosViewer = SosViewer(
    userId = this?.profile?.userId.orEmpty(),
    isAdmin = this?.isAdmin == true,
    // Android's `project_type_id == "personal"` — the personal production
    // hides crew designations on the receivers list.
    isPersonalProject = this?.project?.type.equals("personal", ignoreCase = true),
    phone = this?.profile?.phone.orEmpty(),
)

/** The production's crew, for the "add a receiver" picker. */
private fun ProjectContext?.sosCrew(): List<SosCrewMember> =
    this?.users.orEmpty().mapNotNull { user ->
        val id = user.userId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Translated words, not the wire's label key — the same rule the
        // Contacts tab applies (designationText drops the placeholder too).
        SosCrewMember(userId = id, fullName = user.fullName, designation = user.designationText().orEmpty())
    }

private fun ProjectContext.crewContacts(): List<EmailContact> =
    users.mapNotNull { user ->
        val address = user.email?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        EmailContact(
            address = address,
            name = if (user.keepNamePrivate) "" else user.fullName,
            source = ContactSource.ProjectUser,
            subtitle = user.department.orEmpty(),
        )
    }

/** Outbox rows plus drafts belonging to [userId] on this machine; 0 when nothing is signed in. */
private suspend fun AppGraph.Ready.unsentWorkFor(userId: String?): Int {
    if (userId.isNullOrBlank()) return 0
    return (syncEngine?.openCount(userId) ?: 0) + (draftStore?.countForUser(userId) ?: 0)
}

/**
 * Settings, wired to the things it actually changes.
 *
 * Only what has an effect reaches the screen — see [SettingsViewModel]. The
 * account summary is read from the session rather than fetched, so the screen
 * never shows a spinner where a name should be.
 */
/**
 * The notification switches, each reading and writing its own preference.
 *
 * One toggle per category rather than a list, because [NotificationSettings]
 * names them: the settings screen draws a fixed set of rows and nothing here
 * is meant to be iterated.
 */
private fun notificationSettings(
    preferences: PreferenceStore,
    scope: CoroutineScope,
) = NotificationSettings(
    muted = preferences.observe(ZillitPreferences.MuteNotifications),
    messages = preferences.observe(ZillitPreferences.NotifyMessages),
    mail = preferences.observe(ZillitPreferences.NotifyMail),
    updates = preferences.observe(ZillitPreferences.NotifyUpdates),
    calls = preferences.observe(ZillitPreferences.NotifyCalls),
    activity = preferences.observe(ZillitPreferences.NotifyActivity),
    setMuted = { muted -> scope.launch { preferences.set(ZillitPreferences.MuteNotifications, muted) } },
    setMessages = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyMessages, on) } },
    setMail = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyMail, on) } },
    setUpdates = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyUpdates, on) } },
    setCalls = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyCalls, on) } },
    setActivity = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyActivity, on) } },
)

private fun buildSettings(
    graph: AppGraph,
    preferences: PreferenceStore,
    scope: CoroutineScope,
): SettingsViewModel {
    val ready = graph as? AppGraph.Ready
    val context = ready?.projectContext?.context?.value

    return SettingsViewModel(
        setTheme = { mode -> scope.launch { preferences.set(ZillitPreferences.ThemeMode, mode.name) } },
        setScale = { percent ->
            scope.launch { preferences.set(ZillitPreferences.UiScalePercent, percent) }
        },
        notifications = notificationSettings(preferences, scope),
        // Clears the encrypted cache with the session — the dialog says so.
        signOut = { ready?.authRepository?.signOut() },
        // …and the durable store with it, which is why the dialog counts what
        // that would lose: unsent operations plus drafts, for this person.
        unsentChanges = { ready?.unsentWorkFor(ready.projectContext?.context?.value?.profile?.userId) ?: 0 },
        unitRepository = ready?.unitRepository,
        // The unit decides which notices and call sheets arrive, so a change
        // has to reach the rest of the app rather than sit in this screen.
        onUnitChanged = {
            context?.project?.projectId?.let { ready?.projectContext?.load(it) }
        },
        // Units belong to a production, so this follows the open one rather
        // than being read once at startup — when nothing is open there is
        // nobody to ask, and after a switch the previous list is wrong.
        unitContext = ready?.projectContext?.context?.map {
            UnitContext(projectId = it.project?.projectId, joinUnitId = it.profile?.joinUnitId)
        } ?: flowOf(UnitContext()),
        // Follows the loaded production, like the units above. The snapshot in
        // `initial` is taken before the profile has arrived, so read once this
        // was blank forever — and `isAdmin` never became true, which kept the
        // administration entry out of the rail for everyone.
        account = ready?.projectContext?.context?.map {
            AccountSummary(
                fullName = it.profile?.fullName.orEmpty(),
                email = it.profile?.email.orEmpty(),
                productionName = it.project?.name.orEmpty(),
                isAdmin = it.isAdmin,
            )
        } ?: flowOf(AccountSummary()),
        initial = SettingsUiState(
            unit = UnitSelection(selectedId = context?.profile?.joinUnitId),
            account = AccountSummary(
                fullName = context?.profile?.fullName.orEmpty(),
                email = context?.profile?.email.orEmpty(),
                productionName = context?.project?.name.orEmpty(),
                isAdmin = context?.isAdmin == true,
            ),
            // Which rows the administration page can offer — a corporate or
            // event production runs no shooting units, and both phone clients
            // drop those rows rather than offer a unit that cannot exist.
            admin = AdminSettingsUiState(
                production = productionFacts(context?.project?.name, context?.project?.type),
            ),
        ),
    )
}

/**
 * The reader's own pages behind Settings.
 *
 * ## Where the seed comes from
 *
 * All of it from the profile the session already holds — name, department,
 * role, admin rights — so the form opens filled in rather than fetching a
 * profile it is about to overwrite.
 *
 * Not the crew list. `project/users` carries a department *name* and no id, and
 * that name is not the same string the department catalogue uses, so seeding
 * from it left both pickers blank for a user who plainly had a department. See
 * ProfileSeed.
 *
 * ## Leaving
 *
 * Two steps, in this order: the server call takes the person off the crew, and
 * only then does the app drop the production and return to the picker. Doing it
 * the other way round would leave someone off a production the app still had
 * open, with every subsequent request refused.
 */
private fun buildAccount(ready: AppGraph.Ready): AccountViewModel =
    AccountViewModel(
        repository = ready.accountRepository,
        // The same departments-and-units load the approval queues use. One
        // adapter, so the two forms cannot disagree about what a department is.
        presets = ApprovalPresets { ready.crewPresets() },
        // The name shows on every message this person has sent, so the rest of
        // the app is told rather than left to notice on the next launch.
        onProfileChanged = {
            ready.projectContext?.context?.value?.project?.projectId?.let {
                ready.projectContext.load(it)
            }
        },
        // Server first, then the app: leaveProject() here is the local
        // deselect, not the API call — the repository above made that one.
        onLeftProduction = { ready.projectRepository.leaveProject() },
        seed = ready.projectContext?.context?.map { context ->
            val profile = context.profile
            ProfileSeed(
                firstName = profile?.firstName.orEmpty(),
                lastName = profile?.lastName.orEmpty(),
                email = profile?.email.orEmpty(),
                departmentId = profile?.departmentId,
                designationId = profile?.designationId,
                // The untranslated key, which is what the privacy toggle gates on.
                designationName = profile?.designationName,
                keepNamePrivate = profile?.keepNamePrivate == true,
                // A personal production has one member, who runs it — the web
                // makes the same substitution rather than reading the flag.
                isAdmin = context.isAdmin ||
                    context.project?.type.equals(PERSONAL_PRODUCTION, ignoreCase = true),
                productionName = context.project?.name.orEmpty(),
                productionCode = context.project?.code.orEmpty(),
            )
        } ?: flowOf(ProfileSeed()),
    )

/** `project_type` for a personal production, whose only member administers it. */
private const val PERSONAL_PRODUCTION = "personal"

/** Asking to join a production: code lookup, details, request. */
private fun buildJoin(ready: AppGraph.Ready) = JoinProductionViewModel(
    projectRepository = ready.projectRepository,
    unitRepository = ready.unitRepository,
)

/**
 * The calendar, wired to the clock and the signed-in user.
 *
 * The user id decides who may delete an event; it is null until a production is
 * open, which correctly withholds the action rather than guessing.
 */
private fun buildCalendar(ready: AppGraph.Ready) = CalendarViewModel(
    repository = ready.calendarRepository,
    today = {
        // kotlinx-datetime 0.8 moved Clock to kotlin.time.
        kotlin.time.Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
    },
    currentUserId = { ready.projectContext?.context?.value?.profile?.userId },
    // The crew, for the invitee list. From the session the app already holds,
    // so opening the form costs no request.
    invitees = {
        val context = ready.projectContext?.context?.value
        context?.users.orEmpty()
            // The organiser is not a guest — the form's "I will not be part
            // of this event" checkbox is how they step out, so offering them
            // in their own invite list only invites a double-booking.
            .filter { it.userId != context?.profile?.userId }
            .filter { it.hasJoined() }
            .map { user ->
                EventInvitee(
                    userId = user.userId,
                    name = user.fullName,
                    designation = user.designationText(),
                )
            }
    },
)

/**
 * Zillit Help › Contact Us: a mail to support in the person's own mail
 * client, as the web falls back to (`Help.jsx` `mailto:support@zillit.com`
 * with subject "Zillit Issue"); the in-app compose is the richer route but
 * needs a mailbox on this production, which the frame cannot assume.
 */
private fun contactSupport() {
    runCatching {
        val desktop = java.awt.Desktop.getDesktop().takeIf { java.awt.Desktop.isDesktopSupported() }
        if (desktop?.isSupported(java.awt.Desktop.Action.MAIL) == true) {
            desktop.mail(java.net.URI("mailto:support@zillit.com?subject=Zillit%20Issue"))
        }
    }
}

/** The segment the phones count the bell against (`GLOBAL_LABEL` on Android). */
private const val GLOBAL_BADGE_SEGMENT = "global_label"

/** The SOS feed's own segment — `SosEndpoints.SEGMENT`, kept a literal here as every other rail key is. */
private const val SOS_BADGE_SEGMENT = "sos_label"

private const val CRASH_TAG = "Crash"
