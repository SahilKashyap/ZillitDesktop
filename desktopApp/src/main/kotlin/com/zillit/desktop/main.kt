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
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.common.ZillitLog
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.common.map
import com.zillit.desktop.core.common.currentPlatform
import com.zillit.desktop.core.badges.BadgeCounts
import com.zillit.desktop.core.badges.BadgeSections
import com.zillit.desktop.core.badges.BadgeStore
import com.zillit.desktop.core.badges.LedgerRead
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.Strings
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.socket.ZillitSocketEvents
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.PreferenceStoreFactory
import com.zillit.desktop.core.datastore.WindowGeometry
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.notifications.DesktopNotification
import com.zillit.desktop.core.datastore.loadWindowGeometry
import com.zillit.desktop.core.datastore.saveWindowGeometry
import kotlinx.coroutines.delay
import com.zillit.desktop.core.datastore.observeAs
import com.zillit.desktop.core.designsystem.ThemeMode
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.workspace.FileWorkspaceSessionStore
import com.zillit.desktop.feature.crewlist.ui.CrewListToolProvider
import com.zillit.desktop.core.workspace.ToolProvider
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
import com.zillit.desktop.feature.home.calendar.calendarRealtime
import com.zillit.desktop.feature.home.calendar.CalendarEvent2Event
import com.zillit.desktop.feature.home.calendar.CalendarEvent
import com.zillit.desktop.feature.home.calendar.CalendarViewModel
import com.zillit.desktop.feature.home.calendar.EventInvitee
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.zillit.desktop.core.session.ProjectContext
import com.zillit.desktop.feature.email.rules.DriveFolderOption
import com.zillit.desktop.feature.email.rules.DriveFolderSource
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
import com.zillit.desktop.feature.email.data.ConversationViewRepositoryImpl
import com.zillit.desktop.feature.email.data.CrewMailboxSource
import com.zillit.desktop.feature.email.data.EmailGroupRepositoryImpl
import com.zillit.desktop.feature.email.data.MailboxDirectoryImpl
import com.zillit.desktop.feature.email.ui.Composing
import com.zillit.desktop.feature.email.ui.EmailComposePopoutProvider
import com.zillit.desktop.feature.email.ui.EmailEvent
import com.zillit.desktop.feature.email.ui.EmailHost
import com.zillit.desktop.feature.email.ui.EmailThreadPopoutProvider
import com.zillit.desktop.feature.email.ui.FolderEditor
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
import com.zillit.desktop.feature.settings.ui.AboutInfo
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
import com.zillit.desktop.feature.castboard.ui.CASTING_BACKGROUND_PATH
import com.zillit.desktop.feature.castboard.ui.CASTING_MAIN_PATH
import com.zillit.desktop.feature.castboard.ui.CASTING_PATH
import com.zillit.desktop.feature.castboard.ui.CastingViewModel
import com.zillit.desktop.feature.castboard.domain.BoardTool
import com.zillit.desktop.feature.castboard.ui.WARDROBE_BACKGROUND_PATH
import com.zillit.desktop.feature.castboard.ui.WARDROBE_MAIN_PATH
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.home.ui.decodeImageBitmap
import com.zillit.desktop.feature.chat.ui.ChatEvent
import com.zillit.desktop.feature.chat.ui.CallLine
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
import com.zillit.desktop.feature.cashexpenses.domain.AssigneeOption
import com.zillit.desktop.feature.cashexpenses.domain.CashViewer
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesToolProvider
import com.zillit.desktop.feature.cashexpenses.ui.CashExpensesViewModel
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.permissions.RightsKind
import com.zillit.desktop.core.permissions.ProjectPermissions
import com.zillit.desktop.feature.dealmemo.ui.DealMemoToolProvider
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.documentdistribution.domain.DocDistViewer
import com.zillit.desktop.feature.documentdistribution.ui.DocDistToolProvider
import com.zillit.desktop.feature.accounthub.domain.AccountHubViewer
import com.zillit.desktop.feature.accounthub.ui.AccountHubToolProvider
import com.zillit.desktop.core.forms.FormModule
import com.zillit.desktop.feature.bankrec.ui.BankRecViewModel
import com.zillit.desktop.feature.taxfiling.ui.TaxFilingViewModel
import com.zillit.desktop.feature.accounthub.ui.AccountHubViewModel
import com.zillit.desktop.feature.weather.ui.WeatherViewModel
import com.zillit.desktop.feature.budget.ui.DEPARTMENT_BUDGET_PATH
import com.zillit.desktop.feature.budget.ui.MAIN_BUDGET_PATH
import com.zillit.desktop.feature.budgetbuilder.domain.BudgetBuilderViewer
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderToolProvider
import com.zillit.desktop.feature.budgetbuilder.ui.BudgetBuilderViewModel
import com.zillit.desktop.feature.callsheet.ui.CallSheetViewModel
import com.zillit.desktop.feature.esignature.data.EsignRepositoryImpl
import com.zillit.desktop.feature.esignature.domain.EsignViewer
import com.zillit.desktop.feature.esignature.ui.EsignToolProvider
import com.zillit.desktop.feature.esignature.ui.EsignViewModel
import com.zillit.desktop.feature.productionreport.domain.ReportKind
import com.zillit.desktop.feature.productionreport.ui.ReportViewModel
import com.zillit.desktop.feature.boxschedule.ui.BOX_SCHEDULE_PATH
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleToolProvider
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleViewModel
import com.zillit.desktop.feature.boxschedule.ui.PRE_PRODUCTION_PATH
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
import com.zillit.desktop.feature.addashboard.ui.AdViewModel
import com.zillit.desktop.feature.saportal.ui.SaPortalViewModel
import com.zillit.desktop.feature.sides.ui.SidesViewModel
import com.zillit.desktop.feature.formsignature.data.PdfBoxWork
import com.zillit.desktop.feature.formsignature.ui.FormSignatureViewModel
import com.zillit.desktop.feature.documentdistribution.ui.DocDistViewModel
import com.zillit.desktop.feature.drive.domain.DriveListQuery
import com.zillit.desktop.feature.drive.domain.DriveScope
import com.zillit.desktop.feature.drive.domain.DriveViewer
import com.zillit.desktop.feature.drive.ui.DriveHostSeams
import com.zillit.desktop.feature.drive.ui.DriveToolProvider
import com.zillit.desktop.feature.drive.ui.DriveViewModel
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
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    installCrashLogging()
    val wantsWidget = WidgetLaunch.requestedBy(args)
    val startHidden = BackgroundLaunch.requestedBy(args)
    // Before anything opens the database or the preference file — the point of
    // the guard is that the second copy touches neither. See SingleInstance.
    if (!SingleInstance.claim()) {
        // A "Zillit Drive" shortcut while Zillit is up: hand the request to
        // the running copy and go quietly — a dialog here would be noise.
        when {
            wantsWidget != null -> WidgetLaunch.signalRunningApp(wantsWidget)
            // The login item found Zillit already up: a dialog at every sign-in is worse than none.
            startHidden -> Unit
            else -> reportAlreadyRunning()
        }
        return
    }
    installDockIcon()
    WidgetLaunch.installUriHandler()
    runZillit(openWidget = wantsWidget, startHidden = startHidden)
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
            str(S.desktop_already_running_body),
            str(S.desktop_zillit_desktop_title),
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
private fun runZillit(openWidget: ZillitWidget?, startHidden: Boolean) = application {
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

    // The widgets: open if asked for on the command line, or if they were open
    // when the app last quit. Toggled from the tray, from Settings, from the
    // Drive tool, and by a second launch carrying a widget's flag.
    val widgets = rememberWidgetSwitches(preferences, openWidget, scope)

    val tools = remember(viewModels) {
        buildRegistry(
            graph,
            viewModels,
            scope,
            openDriveWidget = { widgets.open(ZillitWidget.Drive) },
            openWidget = { widget -> widgets.open(widget) },
        )
    }
    val registry = tools.registry

    val workspaceViewModel = remember(registry) {
        WorkspaceViewModel(
            registry = registry,
            sessionStore = FileWorkspaceSessionStore(),
            idGenerator = { UUID.randomUUID().toString() },
        )
    }
    // Window titles are stored at open time; a language change re-asks each
    // provider for its title so the tab strip follows the rest of the frame.
    LaunchedEffect(workspaceViewModel, Strings.language) {
        workspaceViewModel.onEvent(WorkspaceEvent.RefreshTitles)
    }

    // The frame, once it exists, so the tray's Show has something to raise.
    // Declared out here because the tray lives alongside the application, not
    // inside the window it acts on. Before the windows, too: reminders must
    // arrive whether or not the calendar is on screen.
    var mainFrame by remember { mutableStateOf<ComposeWindow?>(null) }
    // Hidden means "in the tray": the socket, the call card and the message card
    // carry on, and the tray, the Dock and a widget bring the window back.
    var mainVisible by remember { mutableStateOf(!startHidden) }
    val showMain: () -> Unit = {
        mainVisible = true
        showMainWindow(mainFrame, windowState)
    }
    val closeToTray by preferences.observe(ZillitPreferences.CloseToTray).collectAsState(initial = true)
    val crewName: (String) -> String? = { id ->
        (graph as? AppGraph.Ready)?.projectContext?.context?.value?.user(id)?.fullName
    }
    LaunchedEffect(Unit) { DockReopen.watch(showMain) }
    LaunchedEffect(preferences) { LoginItem.reconcile(preferences) }

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

    // Each tool widget's own production, switched from its picker without
    // moving the main window. The open production hands back the rail's own
    // provider; any other gets a scoped one. See ToolWidgetHost.
    val chatWidgetHost = remember(graph, viewModels, tools) {
        (graph as? AppGraph.Ready)?.let { ready ->
            ToolWidgetHost(
                ready = ready,
                scope = scope,
                openProjectId = { authViewModel?.currentState?.activeProject?.id },
                tag = "ChatWidget",
                openProvider = { tools.chatWidget },
                scopedProvider = { project, options, permissions ->
                    ready.scopedChatProvider(project, options, permissions, viewModels.calls)
                },
            )
        }
    }
    val crewWidgetHost = remember(graph, viewModels, tools) {
        (graph as? AppGraph.Ready)?.let { ready ->
            ToolWidgetHost(
                ready = ready,
                scope = scope,
                openProjectId = { authViewModel?.currentState?.activeProject?.id },
                tag = "CrewWidget",
                openProvider = { tools.crewWidget },
                scopedProvider = { project, options, permissions ->
                    ready.scopedCrewProvider(project, options, permissions)
                },
            )
        }
    }

    AppTray(
        trayState = trayState,
        graph = graph,
        preferences = preferences,
        onShow = showMain,
        widgets = widgets,
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
        crewName = crewName,
    )

    ZillitWindows(
        graph = graph,
        viewModels = viewModels,
        preferences = preferences,
        windowState = windowState,
        registry = registry,
        viewModel = workspaceViewModel,
        authViewModel = authViewModel,
        widgetMount = WidgetMount(
            driveHost = driveWidgetHost,
            chatHost = chatWidgetHost,
            crewHost = crewWidgetHost,
            switches = widgets,
            showMain = showMain,
        ),
        showMain = showMain,
        onFrame = { mainFrame = it },
        frame = mainFrame,
        crewName = crewName,
        mainVisible = mainVisible,
        onCloseMain = {
            if (closeToTray && isTraySupported) {
                mainVisible = false
                TrayNotifier(trayState).post(
                    DesktopNotification(
                        title = str(S.desktop_still_running_title),
                        body = str(S.desktop_still_running_body),
                    ),
                )
            } else {
                quitZillit(windowState)
            }
        },
        openChat = { workspaceViewModel.onEvent(WorkspaceEvent.Open(WorkspaceRoute.Tool("/cnc"))) },
    )
}

/** What the widget windows need from the application, gathered so ZillitWindows stays readable. */
private class WidgetMount(
    val driveHost: DriveWidgetHost?,
    val chatHost: ToolWidgetHost?,
    val crewHost: ToolWidgetHost?,
    val switches: WidgetSwitches,
    val showMain: () -> Unit,
)

/**
 * Whether each widget is on screen.
 *
 * The preference file is the one source of truth, not a copy of it: the tray,
 * Settings, the command line and the windows' own close buttons all write the
 * same key and all read it back. A widget that could be "open" in two places
 * at once would flicker between them.
 */
internal class WidgetSwitches(
    private val open: Map<ZillitWidget, Boolean>,
    private val onSet: (ZillitWidget, Boolean) -> Unit,
) {
    fun isOpen(widget: ZillitWidget): Boolean = open[widget] == true

    fun open(widget: ZillitWidget) = set(widget, true)

    fun close(widget: ZillitWidget) = set(widget, false)

    fun toggle(widget: ZillitWidget) = set(widget, !isOpen(widget))

    fun set(widget: ZillitWidget, open: Boolean) = onSet(widget, open)
}

/** The switches, kept in step with the preference file and with a second launch's flag. */
@Composable
private fun rememberWidgetSwitches(
    preferences: PreferenceStore,
    opened: ZillitWidget?,
    scope: CoroutineScope,
): WidgetSwitches {
    val open = ZillitWidget.entries.associateWith { widget ->
        preferences.observe(widget.keys.open)
            .collectAsState(initial = remember { runBlocking { preferences.get(widget.keys.open) } })
            .value
    }
    // A widget named on the command line opens once, at startup; from then on
    // it is the stored switch like any other.
    LaunchedEffect(opened) { if (opened != null) preferences.set(opened.keys.open, true) }
    LaunchedEffect(Unit) { WidgetLaunch.watch { widget -> scope.launch { preferences.set(widget.keys.open, true) } } }
    return WidgetSwitches(open) { widget, on -> scope.launch { preferences.set(widget.keys.open, on) } }
}

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
    widgetMount: WidgetMount,
    /** Raises and focuses the main frame. See [showMainWindow]. */
    showMain: () -> Unit,
    onFrame: (ComposeWindow) -> Unit,
    frame: ComposeWindow?,
    crewName: (String) -> String?,
    mainVisible: Boolean,
    onCloseMain: () -> Unit,
    openChat: () -> Unit,
) {
    val workspace by viewModel.state.collectAsState()
    val themeMode by preferences
        .observeAs(ZillitPreferences.ThemeMode, ThemeMode::fromId)
        .collectAsState(initial = ThemeMode.System)
    // The stored choice, blank for "follow the system". The catalogue that
    // is actually on screen follows this through `StringStore` in the graph;
    // the bar only needs to know which row to tick.
    val language by preferences.observe(ZillitPreferences.Language).collectAsState(initial = "")

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
        onCloseRequest = onCloseMain,
        state = windowState,
        visible = mainVisible,
        title = str(S.desktop_zillit_desktop_title),
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
            AvatarFaces(graph) {
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
                        language = language,
                        onLanguageChange = { code ->
                            scope.launch { preferences.set(ZillitPreferences.Language, code) }
                        },
                    )
                }
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
        CallWindow(ready = ready, calls = viewModels.calls, darkTheme = isDark, showMain = showMain)
        IncomingCallWidget(
            ready = ready,
            calls = viewModels.calls,
            preferences = preferences,
            frame = frame,
            darkTheme = isDark,
            showMain = showMain,
        )
        MessageWidget(
            ready = ready,
            chat = viewModels.chat,
            preferences = preferences,
            frame = frame,
            darkTheme = isDark,
            crewName = crewName,
            showMain = showMain,
            openChat = openChat,
        )
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

    // The widgets: the desktop's own small windows, tied to the main window's
    // session. Drive picks its own production; chat and the crew list follow
    // the one the app is open on. See WidgetShell.
    DriveWidgetWindow(
        host = widgetMount.driveHost,
        auth = authViewModel,
        preferences = preferences,
        visible = widgetMount.switches.isOpen(ZillitWidget.Drive),
        darkTheme = isDark,
        graph = graph,
        onClose = { widgetMount.switches.close(ZillitWidget.Drive) },
        showMain = widgetMount.showMain,
    )
    ToolWidgetWindow(
        title = str(S.desktop_zillit_chat),
        what = str(S.desktop_the_chat_widget),
        keys = ZillitPreferences.ChatWidget,
        projectKey = ZillitPreferences.ChatWidgetProject,
        host = widgetMount.chatHost,
        route = WorkspaceRoute.Tool("/cnc"),
        auth = authViewModel,
        preferences = preferences,
        visible = widgetMount.switches.isOpen(ZillitWidget.Chat),
        darkTheme = isDark,
        graph = graph,
        onClose = { widgetMount.switches.close(ZillitWidget.Chat) },
        showMain = widgetMount.showMain,
    )
    ToolWidgetWindow(
        title = str(S.desktop_zillit_crew),
        what = str(S.desktop_the_crew_list_widget),
        keys = ZillitPreferences.CrewWidget,
        projectKey = ZillitPreferences.CrewWidgetProject,
        host = widgetMount.crewHost,
        route = WorkspaceRoute.Tool(CrewListToolProvider.CREW_LIST_PATH),
        auth = authViewModel,
        preferences = preferences,
        visible = widgetMount.switches.isOpen(ZillitWidget.Crew),
        darkTheme = isDark,
        graph = graph,
        onClose = { widgetMount.switches.close(ZillitWidget.Crew) },
        showMain = widgetMount.showMain,
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
private fun CalendarRealtime(ready: AppGraph.Ready, calendar: CalendarViewModel?) {
    if (calendar == null) return

    LaunchedEffect(ready, calendar) {
        calendarRealtime(ready.socketEvents).collect { kind ->
            calendar.onEvent(CalendarEvent2Event.Realtime(kind))
        }
    }
}

/**
 * Feeds the board's socket events into the notice feed.
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
private fun DockBadge(ready: AppGraph.Ready, viewModels: AppViewModels) {
    val counts by ready.badgeStore.counts.collectAsState()
    // The rail's C&C number, not the ledger's raw section: the rail also drops
    // rooms `chat-room` no longer lists, and the dock must not disagree with it.
    val chatState by (viewModels.chat?.state
        ?: MutableStateFlow(com.zillit.desktop.feature.chat.ui.ChatUiState())).collectAsState()
    val total = counts.totalWith(BadgeSections.CNC, chatState.chatsBadge + chatState.callsBadge)
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
 * Reads a film tool's badge whole when its window comes to the front — only
 * for a tool that has no finer read of its own.
 *
 * The phones and the web never read a tool whole on open: each tab, folder
 * and row is read as it is seen (iOS `emitForBadgeReadLevels` refuses a read
 * with no unit), which is what lets the badges *inside* a tool show at all.
 * This read used to fire for every tool, and it flipped every row of the
 * tool before the screen could draw one inner badge — a call sheet's tab
 * counts, a location's folder counts, the diary's history count were all
 * gone by the time they were asked for. So a tool in [SELF_READING_TOOLS]
 * is left to its own reads; the whole-tool read stays as the fallback for
 * a tool whose desktop screen badges nothing inside yet, so its tile can
 * still fall.
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
        if (tool in SELF_READING_TOOLS) return@LaunchedEffect
        emitToolRead(ready, tool)
    }
}

/**
 * The tools whose screens read their own units, levels and rows — a board's
 * unit as it is opened, a tab as it is shown, a folder or record as it is
 * viewed — and so must not be read whole on front. Grid identifiers.
 */
private val SELF_READING_TOOLS: Set<String> = setOf(
    // Boards: the visible unit is read when it loads (HomeFeedViewModel.selectUnit).
    "info_tool", "confidential_info_tool", "catering_tool", "accounting_tool", "reports_tool", "script_notes_tool",
    // Tabbed tools reading per unit/level.
    "callsheet_tool", "production_report_tool", "continuity_tool", "deal_memo_tool", "account_hub_tool",
    "purchase_order_tool", "card_expenses_tool", "cash_expenses_tool", "timecard_tool",
    "supporting_artistes_extras_tool", "sa_portal_tool", "ad_dashboard_tool", "invoices_tool", "e_signature_tool",
    "document_distribution_tool",
    "main_budget_tool", "department_budget_tool", "transportation_tool", "map_tool",
    "dod_tool", "schedule_distribution_tool", "script_distribution_tool",
    // The diary reads its rows when History is opened (Pre-Production shares
    // the screen but files under its own tool with no History read, so it
    // keeps the whole-tool read — Android's `PreProduction`).
    "box_schedule_tool",
    // Folders and records read as they are viewed.
    "location_tool", "casting_main_tool", "casting_background_tool", "wardrobe_main_tool", "wardrobe_background_tool",
    "forms_and_signature_tool",
)

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
    // The socket's frames are applied to the ledger as they arrive (see
    // HomeWiring); a seed asks the server only for rows updated since the
    // ledger's newest, so it is cheap enough to run on every doubt. A
    // reconnect may have swallowed any number of frames, so it seeds.
    val socketState by ready.socketEvents.connectionState.collectAsState()
    LaunchedEffect(socketState.isConnected) {
        if (socketState.isConnected) ready.seedBadges()
    }
    LaunchedEffect(ready) { badgeSocketEffects(ready) }
    LaunchedEffect(ready) {
        // Coalesce the doubts inside a window, seed once per window.
        val arrivals = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        launch {
            // Cross-device pings carry no records, and a missed call rides
            // its own event with no `notification:save` behind it — the
            // ledger learns of both from the next page.
            val moved = listOf(
                ZillitSocketEvents.Badges.ReadSync,
                ZillitSocketEvents.Badges.DeleteSync,
                ZillitSocketEvents.Badges.DeleteGlobalSync,
                ZillitSocketEvents.Calls.MissedCall,
            )
            ready.socketEvents.onAny(moved).collect { arrivals.trySend(Unit) }
        }
        // Reads on another device reach the ledger as `notification:silent`
        // read ids, as they reach the phones. The server has never sent this
        // socket the `notification:read:sync` the phones also refetch on, so
        // two stand-ins remain: a seed when any Zillit window becomes active
        // again — the moment a person looks back from their phone — and a
        // slow tick while a badge is showing; a row read elsewhere comes back
        // read on the next page.
        launch { windowActivations().collect { arrivals.trySend(Unit) } }
        // A thread read on the phone: the room-level read-until frame, which
        // Android applies to its ledger by conversation
        // (`ChatSocketHelper.kt:1288-1400`) — the same act here.
        launch {
            ready.chatRepository.selfReads.collect { conversationId ->
                ready.badgeStore.markRead(LedgerRead.Conversation(conversationId))
            }
        }
        launch {
            while (true) {
                delay(BADGE_POLL_MILLIS)
                if (socketState.isConnected && !ready.badgeStore.counts.value.isEmpty) arrivals.trySend(Unit)
            }
        }
        for (@Suppress("UNUSED_VARIABLE") signal in arrivals) {
            delay(BADGE_EVENT_SETTLE_MILLIS)
            ready.seedBadges()
        }
    }
}

/** The page of rows the ledger has not seen, for the open production. */
private suspend fun AppGraph.Ready.seedBadges() {
    val projectId = badgeStore.openProjectId ?: return
    badgeSeeder.seed(projectId)
}

/** Emits each time one of this app's windows becomes the active window. */
private fun windowActivations(): kotlinx.coroutines.flow.Flow<Unit> = kotlinx.coroutines.flow.callbackFlow {
    val manager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
    val listener = java.beans.PropertyChangeListener { event -> if (event.newValue != null) trySend(Unit) }
    manager.addPropertyChangeListener("activeWindow", listener)
    awaitClose { manager.removePropertyChangeListener("activeWindow", listener) }
}

/** Coalesces a burst of notification events into one counts refetch. */
private const val BADGE_EVENT_SETTLE_MILLIS = 600L

/** How often a lit badge is re-asked about, in case a read elsewhere was never announced. */
private const val BADGE_POLL_MILLIS = 60_000L

/**
 * Rereads the tool grid when the production's tool set moves under it — a
 * switch flipped in Admin Settings (here or on another device), a group made
 * or renamed, this user's rights on a tool changed. The grid's permissions
 * gate every feature, so this listens app-wide rather than only while the
 * Tools tab is open; Android does the same from its base socket listener.
 *
 * Both spellings of a rights change are here. The grid's own
 * `access-grid:*-rights:update` was all this watched until 2026-09-09, so a
 * right moved through a tool's page — which the server announces as
 * `continuity:posting-rights:update` and friends — left the stale gate on
 * screen ([ZillitSocketEvents.ToolRights]).
 */
@Composable
private fun ToolsRefresh(ready: AppGraph.Ready, home: HomeViewModel?) {
    if (home == null) return
    LaunchedEffect(ready, home) {
        val moved = ZillitSocketEvents.ToolsGrid.All +
            ZillitSocketEvents.AccessGrid.All +
            ZillitSocketEvents.ToolRights.All
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
) {
    // Counts belong to a production, and the graph's open sequence swaps the
    // ledger to the new one's rows (`BadgeStore.open`). Not cleared here as
    // well: a clear that landed after that swap emptied the rail until the
    // next production open, and a swap cannot be caught the other way round.
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
        // A reconciliation belongs to one production's bank accounts; carrying
        // the previous one's periods would show another shoot's statement.
        viewModels.bankRec?.onProjectChanged()
    }

    // The calls above run while the tool grid is still out — `projectId` flips
    // when the production is chosen, but rights arrive with the Home load this
    // very effect kicks off. Every viewer resolved up there is therefore the
    // "rights not yet known" one, and nothing replaced it: Document
    // Distribution offered no publish destination at all on a production with
    // 42 tools switched on (seen live 2026-08-27). Keyed on the arrival, so it
    // fires once per production and swaps in the real rights without
    // re-fetching a thing.
    val rights = viewModels.home?.state?.collectAsState()?.value?.permissions
    LaunchedEffect(projectId, rights) {
        if (projectId == null || rights == null || rights === ProjectPermissions.Empty) {
            return@LaunchedEffect
        }
        viewModels.cashExpenses?.onRightsChanged()
        viewModels.cardExpenses?.onRightsChanged()
        viewModels.dealMemos?.onRightsChanged()
        viewModels.payroll?.onRightsChanged()
        viewModels.accountHub?.onRightsChanged()
        viewModels.docDist?.onRightsChanged()
        viewModels.drive?.onRightsChanged()
        viewModels.adDashboard?.onRightsChanged()
        viewModels.saPortal?.onRightsChanged()
        viewModels.purchaseOrders?.onRightsChanged()
        viewModels.timecards?.onRightsChanged()
        viewModels.permissionGrid?.onRightsChanged(PermissionGridViewer.from(rights))
        viewModels.externalUsers?.onRightsChanged()
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
    DockBadge(ready, viewModels)
    ApprovalCounts(viewModels)
    ToolReadOnFocus(ready, viewModels, workspace)
    HomeRealtime(ready, viewModels.homeFeed)
    CalendarRealtime(ready, viewModels.calendar)
    EmailRealtime(ready, viewModels.email)
    BoardRealtime(ready, "info", viewModels.info)
    BoardRealtime(ready, "confidentialinfo", viewModels.confidentialInfo)
    BoardRealtime(ready, "reports", viewModels.reports)
    BoardRealtime(ready, "script-notes", viewModels.scriptNotes)
    BoardRealtime(ready, "catering", viewModels.catering)
    // The Accounts board's segment key is the singular "account".
    BoardRealtime(ready, "account", viewModels.accounts)
    BoardRealtime(ready, PRODUCTION_REPORT_BOARD, viewModels.productionReportChat)
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
    language: String,
    onLanguageChange: (String) -> Unit,
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
    ProjectScopedLoads(authState.activeProject?.id, viewModels, workspaceViewModel)

    BackgroundWork(ready, authViewModel, createViewModel, joinViewModel, viewModels, workspaceViewModel)

    Box {
        if (authState.step == AuthStep.Complete) {
            SignedInShell(
                ready, registry, viewModels, workspaceViewModel, authViewModel,
                themeMode, onThemeModeChange, language, onLanguageChange,
            )
        } else {
            AuthScreen(
                viewModel = authViewModel,
                // The production list carries the theme toggle, as on the web —
                // it is the first screen a signed-in user sees, and the shell is
                // not reachable until they pick a production.
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                language = language,
                onLanguageChange = onLanguageChange,
                createViewModel = createViewModel,
                joinViewModel = joinViewModel,
                // On the sign-in page, where someone who cannot get in can
                // still read it off to support.
                appVersion = installedAppVersion(),
            )
        }
        // Above either screen: the startup notification-permission check, as
        // the phones make it, whatever the person is looking at.
        NotificationPermissionPrompt()
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
    language: String,
    onLanguageChange: (String) -> Unit,
) {
    val authState by authViewModel.state.collectAsState()
    val homeState by (viewModels.home?.state ?: MutableStateFlow(HomeUiState())).collectAsState()
    val badges by ready.badgeStore.counts.collectAsState()
    val socketState by ready.socketEvents.connectionState.collectAsState()
    val scope = rememberCoroutineScope()
    val syncStatus by (ready.syncEngine?.status ?: MutableStateFlow(SyncStatus())).collectAsState()
    var pendingChangesOpen by remember { mutableStateOf(false) }
    val updateStatus = rememberUpdateStatus(ready)

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
        language = language,
        onLanguageChange = onLanguageChange,
        projectName = authState.activeProject?.name,
        statusText = statusText(socketState, syncStatus),
        statusAction = syncStatusAction(syncStatus) { pendingChangesOpen = true },
        updateNotice = updateStatus.toNotice(installedAppVersion()),
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
        // Answers every module's "ask an admin for this right" — the phones'
        // flow, hosted once here because no tool window can float a dialog
        // over the frame or reach the chat socket.
        RightsRequestSurface(ready, ready.rightsRequests)
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
 * `macOS 26.5`, `Windows 11`, `Linux 6.8` — what the About row prints.
 *
 * The JVM still calls the Mac "Mac OS X", a name Apple dropped in 2016;
 * nobody types it into a bug report, so it is not shown.
 */
private fun hostPlatformLabel(): String {
    val name = System.getProperty("os.name").orEmpty().let { if (it.startsWith("Mac OS X")) "macOS" else it }
    return "$name ${System.getProperty("os.version").orEmpty()}".trim()
}

/**
 * The update check: once at sign-in, then every six hours. A desktop app
 * stays open for days, so a launch-only check leaves someone on a stale
 * build for a week; six hours is well inside Remote Config's own SDK default.
 */
@Composable
private fun rememberUpdateStatus(ready: AppGraph.Ready): UpdateStatus {
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Unknown) }
    LaunchedEffect(Unit) {
        while (true) {
            updateStatus = ready.appUpdateChecker.check()
            delay(UPDATE_CHECK_INTERVAL_MILLIS)
        }
    }
    return updateStatus
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
            ZillitText(str(S.desktop_not_configured_title), style = ZillitTheme.typography.titleMedium)
            ZillitText(
                text = str(S.desktop_not_configured_body),
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
    val projectId = { ready.projectContext?.context?.value?.project?.projectId.orEmpty() }
    val cache = ready.emailCache?.let { store ->
        // Resolved per call: the mailbox outlives any one production, and
        // capturing the id would file one project's mail under another's.
        // The address partitions the rows too — the personal and the shared
        // Accounts mailbox reuse the same uids for different mail.
        SqlMailboxCache(store, currentProjectId = projectId, currentMailbox = { ready.activeMailbox.address })
    } ?: InMemoryMailboxCache { ready.activeMailbox.address }

    val mailbox = Mailbox(ready.emailRepository, cache)

    // The ledger's email rows carry the mailbox address (`level_1`), so every
    // read names the mailbox it happened in.
    val ledger = MailLedger(store = ready.badgeStore, scopes = ready.mailboxScopes)
    val directory = MailboxDirectoryImpl(
        ready.apiClient,
        ready.config,
        projectId = { projectId().takeIf { it.isNotBlank() } },
        userName = { ready.projectContext?.context?.value?.profile?.fullName.orEmpty() },
    )

    return EmailViewModel(
        mailbox = mailbox,
        repository = ready.emailRepository,
        draftRepository = ready.draftRepository,
        folderEditor = FolderEditor(ready.folderRepository),
        nowMillis = System::currentTimeMillis,
        // The badge ledger's reads, alongside the mailbox's own. A row is
        // keyed by folder and uid (see `LedgerRead.Mail`); the server is still
        // told by message id, as the web and iOS tell it.
        badges = com.zillit.desktop.feature.email.ui.MailBadges(
            onMessageRead = { read ->
                ledger.read(read, read.mailboxAddress)
                emitSegmentRead(ready, segment = "email_label", module = "email_label", referenceId = read.messageId)
            },
            onFolderSynced = { sync -> ledger.synced(sync, sync.mailboxAddress) },
            // Per-folder unread from the badge ledger — the unit is the folder.
            folderBadges = { address -> ledger.folderBadges(address) },
            mailboxUnread = { ledger.mailboxUnread() },
        ),
        downloader = AttachmentDownloader(ready.emailRepository, DownloadsAttachmentStore()),
        activeMailbox = ready.activeMailbox,
        directory = directory,
        preferences = MailboxPreferenceStore(ready.preferences),
        conversationView = ConversationViewRepositoryImpl(
            ready.apiClient,
            ready.config,
            scope = ready.activeMailbox,
            directory = directory,
        ),
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
internal suspend fun pickChatAttachment(
    ready: AppGraph.Ready,
    kind: com.zillit.desktop.core.media.PreviewKind? = null,
    /** Where the bytes go — another production's storage for a widget on one. */
    capture: com.zillit.desktop.feature.home.ui.MediaCapture = homeMediaCapture(ready),
): com.zillit.desktop.feature.chat.domain.ChatPick {
    // Chat's own ceiling, not mail's 25 MB: both other clients carry files up
    // to 70 MB, and a desktop that stops at 25 refuses what a phone sends.
    var refusal: String? = null
    val picked = if (kind == null) {
        com.zillit.desktop.feature.email.data.FilePicker(
            maxBytes = com.zillit.desktop.feature.chat.domain.ChatComposerRules.MAX_ATTACHMENT_BYTES,
            onRefused = { _, _ ->
                refusal = com.zillit.desktop.feature.chat.domain.ChatComposerRules.ATTACHMENT_TOO_LARGE
            },
        ).pick().firstOrNull()?.let { PickedBytes(it.name, it.contentType, it.bytes) }
    } else {
        // The attach sheet's kind: the same 70 MB ceiling, plus a wrong-kind
        // refusal the phones word as "Please select a valid file type".
        attachmentPicker.pick(
            kind = kind,
            multiple = false,
            maxBytes = com.zillit.desktop.feature.chat.domain.ChatComposerRules.MAX_ATTACHMENT_BYTES,
            onRefused = { why ->
                refusal = when (why) {
                    is com.zillit.desktop.core.media.PickRefusal.TooLarge ->
                        com.zillit.desktop.feature.chat.domain.ChatComposerRules.ATTACHMENT_TOO_LARGE
                    is com.zillit.desktop.core.media.PickRefusal.WrongKind ->
                        com.zillit.desktop.feature.chat.domain.ChatComposerRules.ATTACHMENT_REFUSED_TYPE
                }
            },
        ).firstOrNull()?.let { PickedBytes(it.name, it.contentType, it.bytes) }
    }

    val reason = refusal
    if (picked == null) {
        return reason?.let { com.zillit.desktop.feature.chat.domain.ChatPick.Refused(it) }
            ?: com.zillit.desktop.feature.chat.domain.ChatPick.Cancelled
    }

    // The bytes ride along so the thread's preview dialog can show (and for a
    // picture, edit) the file before anything uploads; the upload then takes
    // the possibly edited bytes back.
    return com.zillit.desktop.feature.chat.domain.ChatPick.Ready(
        com.zillit.desktop.feature.chat.domain.PendingChatUpload(
            name = picked.name,
            contentType = picked.contentType,
            bytes = picked.bytes,
        ) { bytes, onProgress ->
            uploadChatMedia(ready, picked.name, picked.contentType, bytes, onProgress, capture)
        },
    )
}

/**
 * Chat's route to storage for a named blob — the board's own capture: a video
 * or PDF gets a poster frame, and the frame travels as its own upload, the
 * same shape mail and notices use. Serves both the picker's files and the
 * composer's pasted images (ChatViewModel's `uploadMedia` seam).
 */
internal suspend fun uploadChatMedia(
    ready: AppGraph.Ready,
    name: String,
    contentType: String,
    bytes: ByteArray,
    onProgress: (Int) -> Unit,
    /** Where the bytes go — another production's storage for a widget on one. */
    capture: com.zillit.desktop.feature.home.ui.MediaCapture = homeMediaCapture(ready),
): ChatAttachment? {
    val media = capture
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
internal fun openChatAttachment(ready: AppGraph.Ready, file: ChatAttachment) {
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
    composing = Composing(
        repository = ready.emailRepository,
        drafts = ready.draftRepository,
        contacts = ready.contactRepository,
        signatures = ready.signatureRepository,
        // Crew come from the production context already loaded when the project
        // opened, so a composer opens with suggestions before any request
        // answers; the mailbox addresses replace them once `project/users` does.
        crew = { ready.projectContext?.context?.value?.crewContacts().orEmpty() },
        crewMailboxes = {
            CrewMailboxSource(ready.apiClient, ready.config) {
                ready.projectContext?.context?.value?.isAdmin == true
            }.crew()
        },
        groups = { EmailGroupRepositoryImpl(ready.apiClient, ready.config).groups() },
        uploader = ready.attachmentUploader,
        chooseFiles = { FilePicker().pick() },
        chooseFilesOf = { kind ->
            attachmentPicker.pick(kind).map {
                com.zillit.desktop.feature.email.domain.PickedFile(it.name, it.contentType, it.bytes)
            }
        },
        newAttachmentId = { UUID.randomUUID().toString() },
        // Reply-all drops this address, so a reply never goes to the person
        // sending it.
        selfAddress = {
            ready.activeMailbox.address.ifBlank { ready.projectContext?.context?.value?.profile?.email.orEmpty() }
        },
        mailbox = { ready.activeMailbox.identity.value },
        nowMillis = System::currentTimeMillis,
    ),
    host = EmailHost(
        loadAvatar = { address ->
            ready.projectContext?.context?.value?.users
                ?.firstOrNull { it.email?.equals(address, ignoreCase = true) == true }
                ?.let { user -> fetchAvatar(ready, user.userId)?.let(::decodeAvatar) }
        },
        // The same fetch the download path uses, decoded into a bitmap rather
        // than written to Downloads.
        loadThumbnail = { attachment, messageId, folder ->
            (
                ready.emailRepository.attachment(attachment.id, messageId, folder)
                    as? com.zillit.desktop.core.common.ZillitResult.Success
                )?.data
                ?.let(::decodeBase64Default)
                ?.let(::decodeImageBitmap)
        },
        onOpenLink = ::openInBrowser,
        onPrint = ::printMailPage,
        onOpenCalendar = onOpenCalendar,
        readBy = { messageId -> readByForSentMail(ready, messageId) },
        isAdmin = { ready.projectContext?.context?.value?.isAdmin == true },
        canAttach = true,
        // A message another screen queued — "write to us" on the help page.
        claimPendingCompose = ::claimPendingSupportCompose,
    ),
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
    host = driveHostSeams(viewModel, scope),
    onOpenWidget = openWidget,
)

/**
 * The seams both the Drive tool and its widget hand the view model: the
 * native pickers (launched on [scope] because the dialog blocks its own
 * thread), the guarded browser, the clipboard, and the app's own Chromium
 * for the editor and for video/audio — a presigned stream URL in the system
 * browser would leave the app just as the editor token would.
 */
internal fun driveHostSeams(viewModel: DriveViewModel, scope: CoroutineScope) = DriveHostSeams(
    onOpenUrl = ::openInBrowser,
    onPickFiles = { report ->
        scope.launch {
            val picked = DriveFilePicker().pick()
            if (picked.isNotEmpty()) report(picked)
        }
    },
    onPickFolder = { report ->
        scope.launch {
            val picked = DriveFilePicker().pickFolder()
            if (picked.isNotEmpty()) report(picked)
        }
    },
    // Paths, not bytes — a Drive upload can be 10 GB.
    onPickFilesOf = { kind, report ->
        scope.launch {
            val picked = attachmentPicker.pickPaths(kind).map { it.toDrivePick() }
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
    onOpenMedia = { url, title ->
        DocumentEditorWindow.open(
            url = url,
            fileName = title,
            scope = scope,
            onUnavailable = { _ -> openInBrowser(url) },
        )
    },
    now = System::currentTimeMillis,
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

/** The picker's seed: the last production opened here, else the first the cache lists. */
private suspend fun seedFromLastProduction(ready: AppGraph.Ready) {
    val known = ready.projectListCache?.let(::CachedProjectList)?.load().orEmpty()
    val lastId = ready.preferences.get(ZillitPreferences.LastProjectId)
    val project = known.firstOrNull { it.id == lastId } ?: known.firstOrNull() ?: return
    val userId = project.userId?.takeIf { it.isNotBlank() } ?: return
    ready.badgeSeeder.seed(project.id, userId)
    // The picker's counts leave out mail for a mailbox this desktop cannot
    // show: the productions opened before scope by what was learnt then, the
    // last one by a fresh ask under its own ids.
    ready.mailboxScopes.restore()
    ready.mailboxScopes.learn(project.id, userId)
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
    // Android's foreground fetch (`ZillitApplication.onStart` → `callBadgesApi`):
    // one listing call under the last production's headers, before anything
    // is open — the listing is user-wide, so it fills every production's
    // rows. Then the ledger, as the phones' picker groups their own rows by
    // production and never asks the server. The server's answer stands in
    // only while the ledger is still empty.
    seedFromLastProduction(ready)
    val ledger = ready.badgeStore.projectCounts(ready.deviceId())
    if (ledger.isNotEmpty()) {
        ZillitLog.d("Badges") { "picker counts from ledger: $ledger" }
        return ledger
    }
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
    ZillitLog.d("Badges") { "picker counts from device/unread: $counts" }
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

@Suppress("LongParameterList") // One seam per host thing the chat needs; a bag would only rename them.
private fun chatProvider(
    ready: AppGraph.Ready,
    viewModel: ChatViewModel?,
    calls: CallViewModel?,
    audioPlayer: com.zillit.desktop.core.designsystem.component.AudioPlayer?,
    canDownload: () -> Boolean = { true },
    /** One pane at a time — the Chat widget's copy. */
    compact: Boolean = false,
    onOpenWidget: (() -> Unit)? = null,
    /** The mailbox the menu's Share hands a line to; null leaves Share off the menu. */
    mail: EmailViewModel? = null,
) = ChatToolProvider(
    compact = compact,
    onOpenWidget = onOpenWidget,
    shareAsEmail = mail?.let { mailbox ->
        { message, navigator -> ready.shareChatMessageAsEmail(mailbox, message, navigator) }
    },
    player = audioPlayer,
    loadAudio = { file -> fetchChatAudio(ready, file) },
    canDownload = canDownload,
    // Chat & Calls is a tool like any other, so a missing download right is
    // something an admin can grant — the refusal offers to ask for it.
    requestDownloadRights = { ready.rightsRequests.ask(str(S.desktop_chat_calls), RightsKind.Download) },
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
                    department = user.departmentText(),
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
    // Which lines the call buttons offer: the two every production has, and
    // Line 3 where remote config lists this one.
    lines = { ready.callLines(ready.projectContext?.context?.value?.project?.projectId) },
    onCall = calls?.let { vm ->
        { peer, isGroup, video, line ->
            vm.onEvent(
                CallEvent.Place(
                    // A group is rung by its room; a person by their device.
                    chatRoomId = if (isGroup) peer.userId else "",
                    receiverDeviceId = if (isGroup) "" else peer.deviceId.orEmpty(),
                    mode = if (isGroup) CallMode.Group else CallMode.Private,
                    type = if (video) CallType.Video else CallType.Audio,
                    displayName = peer.fullName,
                    provider = line.toProvider(),
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
): androidx.compose.ui.graphics.ImageBitmap? {
    val image = fetchChatBytes(ready, file, preview)?.let(::decodeImageBitmap)
    if (image != null || !preview || !file.isPdf) return image
    // No poster on the server — a Box production uploads none, a Drive
    // share carries none, and the phones' `zillit-pdf-icon.png` placeholder
    // is a key nothing answers — so page one is drawn here from the file
    // itself, the way the upload path draws it before sending. Capped so a
    // bubble never pulls a whole script down for a 240dp tile.
    if (file.sizeBytes > PDF_POSTER_MAX_BYTES) return null
    val pdf = fetchChatBytes(ready, file, preview = false) ?: return null
    return withContext(Dispatchers.Default) {
        pdfThumbnailJpeg(pdf)?.jpegBytes?.let(::decodeImageBitmap)
    }
}

private suspend fun fetchChatBytes(
    ready: AppGraph.Ready,
    file: com.zillit.desktop.feature.chat.domain.ChatAttachment,
    preview: Boolean,
): ByteArray? =
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
        )?.data

/** A PDF larger than this keeps its chip rather than being fetched whole for a poster. */
private const val PDF_POSTER_MAX_BYTES = 15L * 1024 * 1024

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
/**
 * Who a cash batch may be handed to: the whole crew.
 *
 * Not filtered by department or designation — the web's picker is the crew
 * list, and a senior taking an unassigned batch themselves is the ordinary
 * case, so the viewer is not removed either. The only exclusion is the
 * batch's current owner, which [BatchAssignment.eligible] applies.
 */
private fun AppGraph.Ready.cashAssignees(): List<AssigneeOption> {
    val context = projectContext?.context?.value
    return context?.users.orEmpty().map { user ->
        AssigneeOption(
            userId = user.userId,
            fullName = user.fullName,
            designation = user.designationText().orEmpty(),
            // The pickers list the accounts team only, as the web's
            // `ACCOUNTS_TEAM_USERS`; the module filters on this.
            department = user.department.orEmpty(),
        )
    }
}

private fun AppGraph.Ready.cashViewer(): CashViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return CashViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
    )
}

private fun AppGraph.Ready.poViewer(permissions: ProjectPermissions): PoViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return PoViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
        isProjectAdmin = context?.isAdmin == true,
        // The department view's All POs tab is gated on this: `is_admin` OR the
        // tool's own posting right, which is the web's `canSeeAllPOs_department`.
        canPostPurchaseOrders = permissions.canPost(PURCHASE_ORDER_TOOL),
        // The id, not the identifier — both are needed and they are different
        // strings; see PoViewer. It is on the *profile*: the crew list carries
        // a department name and no id at all.
        departmentId = context?.profile?.departmentId,
    )
}

/** The tool-rights identifier for purchase orders, as the grid issues it. */
private const val PURCHASE_ORDER_TOOL = "purchase_order_tool"

private fun AppGraph.Ready.timecardViewer(): TimecardViewer {
    val context = projectContext?.context?.value
    val me = context?.user(context.profile?.userId)
    return TimecardViewer(
        userId = context?.profile?.userId.orEmpty(),
        departmentIdentifier = me?.department,
        designationIdentifier = me?.designation,
    )
}

/** `Unknown` and `UpToDate` both mean "render nothing". */
private fun UpdateStatus.toNotice(installed: String): UpdateNotice? = when (this) {
    is UpdateStatus.Available ->
        UpdateNotice(latestVersion, mandatory = false, downloadUrl = downloadUrl, installedVersion = installed)
    is UpdateStatus.Required ->
        UpdateNotice(latestVersion, mandatory = true, downloadUrl = downloadUrl, installedVersion = installed)
    UpdateStatus.Unknown, UpdateStatus.UpToDate -> null
}

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
        // Television productions make the episode number mandatory on the
        // schedule and script publish destinations.
        isTelevision = context?.project?.subType?.contains("television", ignoreCase = true) == true,
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

/** The crew as the share pickers list them — id, name, designation. */
private fun AppGraph.Ready.driveCrew(): List<com.zillit.desktop.feature.drive.domain.DrivePerson> =
    projectContext?.context?.value?.users.orEmpty().map { it.toDrivePerson() }

private fun AppGraph.Ready.driveViewer(permissions: ProjectPermissions): DriveViewer {
    val context = projectContext?.context?.value
    return DriveViewer.from(
        permissions = permissions,
        userId = context?.profile?.userId.orEmpty(),
        displayName = context?.profile?.fullName.orEmpty(),
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
    /** HMRC Making Tax Digital — reached from the console's own sidebar. */
    val taxFiling: TaxFilingViewModel?,
    /** Bank Reconciliation — also reached from the console's sidebar. */
    val bankRec: BankRecViewModel?,
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
    /** The production report tool's unit chat — its Chat workspace. */
    val productionReportChat: HomeFeedViewModel?,
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
    /** The accountant's Cost Report worksheet — the Account Hub's REPORTS row. */
    val costReportWorksheet: com.zillit.desktop.feature.costreport.ui.worksheet.WorksheetViewModel?,
    /** The cost report's Analytics page, which both cost-report screens open. */
    val costReportAnalytics: com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsViewModel?,
    val saPortal: SaPortalViewModel?,
    val adDashboard: AdViewModel?,
    /** The two budget tiles, one view model each — see BudgetToolProvider. */
    val budget: BudgetViewModels?,
    /** The forecast where the unit is. */
    val weather: WeatherViewModel?,
    /** Characters and who is up for them — one board, both casting lists. */
    val casting: CastingViewModel?,
    /** The same board, for costumes. */
    val wardrobe: CastingViewModel?,
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
/**
 * Whether Zillit Draft appears on the tools grid.
 *
 * Off for now, and **hidden rather than removed**: the module, its route, its
 * view model and its provider all stay wired, so a workspace tab already open
 * on it keeps working and turning the tile back on is this one flag. The
 * section below is left whole for the same reason — there is nothing to
 * reconstruct when it returns.
 */
private const val SHOW_ZILLIT_DRAFT = false

private fun localToolSections(): List<ToolSection> = listOfNotNull(
    ToolSection(
        title = str(S.desktop_writing_section),
        tools = listOf(
            ToolPresentation(
                identifier = "zillit_draft",
                label = str(S.desktop_zillit_draft),
                icon = ZillitIcons.Edit,
                route = WorkspaceRoute.Tool(DRAFT_PATH),
            ),
        ),
        identifier = null,
    ).takeIf { SHOW_ZILLIT_DRAFT },
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
                    pickAttachmentOf = { kind -> pickChatAttachment(it, kind) },
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
                    // chat's clearing mechanism on any client. The ledger is
                    // told by conversation, as Android's `markReadyByChatRoomIdSenderId`.
                    onThreadRead = { conversationId ->
                        it.badgeStore.markRead(LedgerRead.Conversation(conversationId))
                    },
                    // The area's split by tool: chat_label / call_label —
                    // what the Chats and Calls tabs wear.
                    sectionBadges = { sectionSplit(it, "cnc_label", "tool") },
                    // Looking at the log reads the missed calls (iOS
                    // `readCNCMessage(.misscall)`: notification:read on call_label).
                    onCallsViewed = {
                        emitSegmentRead(it, segment = "call_label", module = "cnc_label")
                    },
                    // The bubble menu's clocks and Translate: an admin's
                    // Edit/Delete never time out; Translate shows when the
                    // production's language is not this computer's.
                    isAdmin = { it.projectContext?.context?.value?.isAdmin == true },
                    translator = AppChatTranslator(it, scope.chatUiLanguage(preferences)),
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
                    isAdmin = { graph.projectContext?.context?.value?.isAdmin == true },
                    // Named on the deletion confirmation, and read at call time
                    // rather than captured: a dialog naming the wrong
                    // production is the worst place for a stale value.
                    productionName = {
                        graph.projectContext?.context?.value?.project?.name.orEmpty()
                    },
                    // The grid rereads at once; its own socket echo may not come.
                    onToolsChanged = { home?.onEvent(HomeEvent.Reload) },
                    // Crew, departments and the join queues move under a second
                    // coordinator; the page being looked at should say so.
                    events = graph.socketEvents,
                )
            },
            account = ready?.let(::buildAccount),
            calls = ready?.let { graph ->
                CallViewModel(
                    coordinator = graph.callCoordinator,
                    crew = { graph.callableCrew() },
                    nameDirectory = graph.callNameDirectory(),
                    // Null in a dev run: the helper only exists in a packaged
                    // bundle, and without it Share sends the whole screen.
                    screenSources = macCaptureHelper()?.let(::MacScreenSources),
                    copyToClipboard = { com.zillit.desktop.core.designsystem.component.copyTextToClipboard(it) },
                )
            },
            cashExpenses = ready?.let { graph ->
                // The badge scope follows the view the tool is showing: an
                // accountant who opened it from the tools grid sees the crew
                // view (as on the web) and reads the cash tool's badges.
                var cashModel: CashExpensesViewModel? = null
                CashExpensesViewModel(
                    repository = graph.cashRepositoryWithExports(),
                    files = cashFiles(),
                    viewer = { graph.cashViewer() },
                    assignees = { graph.cashAssignees() },
                    events = graph.socketEvents,
                    // As with purchase orders: the float request form's
                    // configuration is the account hub's document.
                    formTemplate = graph.formTemplateFor(FormModule.CashExpenses),
                    // An accountant's rows file under the account hub, everyone
                    // else's under the cash tool (`constants.js:229-246`).
                    badges = graph.tabBadges("level_1") {
                        val accountant = cashModel?.state?.value?.viewer?.isAccountant
                            ?: graph.cashViewer().isAccountant
                        TabBadgeScope(
                            tool = if (accountant) "account_hub_label" else "cash_expenses_label",
                            unit = "cash_expenses_label",
                        )
                    },
                ).also { cashModel = it }
            },
            cardExpenses = ready?.let { graph ->
                // The badge scope follows the view the tool is showing — see
                // the cash tool's note above.
                var cardModel: CardExpensesViewModel? = null
                CardExpensesViewModel(
                    repository = graph.cardRepositoryWithExports(),
                    files = cardFiles(),
                    banks = { graph.cardBanks() },
                    events = graph.socketEvents,
                    // Both host seams: the crew belongs to the production and
                    // the picker to this machine, and the card service offers
                    // neither. See CardExpensesWiring.
                    people = { graph.cardPeople() },
                    uploader = graph.cardAttachmentUploader(),
                    // An accountant's rows file under the account hub, a
                    // cardholder's under the card tool (`constants.js:189-193`).
                    badges = graph.tabBadges("level_1") {
                        val accountant = cardModel?.state?.value?.viewer?.isAccountant
                            ?: graph.cardViewer().isAccountant
                        TabBadgeScope(
                            tool = if (accountant) "account_hub_label" else "card_expenses_label",
                            unit = "card_expenses_label",
                        )
                    },
                    viewer = { graph.cardViewer() },
                ).also { cardModel = it }
            },
            purchaseOrders = ready?.let { graph ->
                PurchaseOrderViewModel(
                    repository = graph.purchaseOrderRepository,
                    viewer = { graph.poViewer(permissions()) },
                    offline = graph.offlineSupport,
                    // The form's configuration belongs to the account hub's
                    // service, not the purchase-order one, so it is handed in
                    // rather than fetched by the module's own repository.
                    formTemplate = graph.formTemplateFor(FormModule.PurchaseOrders),
                    // The Settings tab's pickers read the crew list, and its
                    // terms document rides the hub's document store.
                    people = graph.poSettingsPeople(),
                    termsFiles = graph.poTermsFiles(),
                    // Companies, tax types, departments and currencies — the
                    // web fetches all four once on PO entry and shares them
                    // between both role views; they are the hub's documents.
                    projectSettings = graph.poProjectSettings(),
                    // An order's own paperwork: the same store, a wider accept
                    // rule than the terms document's.
                    attachmentFiles = graph.poAttachmentFiles(),
                    badges = graph.purchaseOrderBadges(),
                )
            },
            timecards = ready?.let { graph ->
                TimecardViewModel(
                    repository = graph.timecardRepository,
                    viewer = { graph.timecardViewer() },
                    currentWeekStarting = ::currentWeekStarting,
                    offline = graph.offlineSupport,
                    // Every timecard and dispute event files under the tool with
                    // the tile as its unit (`constants.js:107-116`).
                    badges = graph.tabBadges("unit") { TabBadgeScope(tool = "timecard_label") },
                )
            },
            payroll = ready?.buildPayroll(permissions),
            dealMemos = ready?.buildDealMemos(permissions),
            accountHub = ready?.let { graph ->
                AccountHubViewModel(
                    repository = graph.accountHubRepository,
                    events = graph.socketEvents,
                    viewer = { graph.accountHubViewer(permissions()) },
                    agreementFiles = graph.agreementFiles(),
                    defaultReportPeriod = ::defaultReportPeriod,
                    clock = System::currentTimeMillis,
                    // The pay breakdown's scope names departments; the hub's
                    // own service does not list them, so the host does.
                    departments = { graph.departmentNames() },
                    users = { graph.hubUsers() },
                    departmentList = { graph.hubDepartments() },
                    exporter = graph.hubExporter(),
                    files = hubFiles(),
                    documentOpener = graph.hubDocumentOpener(),
                    projectId = { graph.projectContext?.context?.value?.project?.projectId.orEmpty() },
                    projectName = { graph.projectContext?.context?.value?.project?.name.orEmpty() },
                    tourSeen = { key -> graph.tourSeen(key) },
                    markTourSeen = { key -> graph.markTourSeen(key) },
                    // The console renders the other film tools inside its
                    // shell, as the web does — see `AccountHubToolProvider.tools`.
                    embedsTools = true,
                    readToolRow = { itemId, isAccountant -> graph.readHubToolRow(itemId, isAccountant) },
                )
            },
            taxFiling = ready?.buildTaxFiling(),
            bankRec = ready?.buildBankRec(),
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
            formSignature = ready?.let { graph -> graph.buildFormSignature(permissions) },
            esignature = ready?.let { graph ->
                EsignViewModel(
                    repository = EsignRepositoryImpl(
                        apiClient = graph.apiClient,
                        config = graph.config,
                        today = { esignToday() },
                        bus = graph.socketEvents,
                        rawGet = graph.esignRawGet(),
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
                    currentUserEmail = {
                        graph.projectContext?.context?.value?.profile?.email.orEmpty()
                    },
                    rights = graph.rightsRequests,
                    badges = graph.esignBadges(),
                )
            },
            callSheet = ready?.buildCallSheet(permissions),
            productionReport = ready?.buildReport(ReportKind.Production, permissions, ::today),
            productionReportChat = ready?.productionReportChatFeed(permissions),
            adReport = ready?.buildReport(ReportKind.Ad, permissions, ::today),
            wrapReport = ready?.buildReport(ReportKind.Wrap, permissions, ::today),
            sides = ready?.let { graph ->
                SidesViewModel(
                    repository = SidesRepositoryImpl(
                        apiClient = graph.apiClient,
                        config = graph.config,
                        rawGet = graph.sidesRawGet(),
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
                    rights = graph.rightsRequests,
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
                    rights = graph.rightsRequests,
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
            boxSchedule = ready?.buildBoxSchedule(permissions),
            maps = ready?.buildMaps(permissions),
            recce = ready?.buildRecce(permissions),
            externalUsers = ready?.buildExternalUsers(permissions),
            distribution = ready?.buildDistributionList(permissions),
            crewList = ready?.buildCrewList(permissions),
            assetRegister = ready?.buildAssetRegister(permissions),
            location = ready?.buildLocation(permissions),
            continuity = ready?.buildContinuity(permissions),
            costReport = ready?.buildCostReport(permissions),
            costReportWorksheet = ready?.buildCostReportWorksheet(permissions),
            costReportAnalytics = ready?.buildCostReportAnalytics(),
            saPortal = ready?.buildSaPortal(permissions),
            adDashboard = ready?.buildAdDashboard(permissions),
            budget = ready?.buildBudget(permissions, scope),
            weather = ready?.buildWeather(permissions),
            casting = ready?.buildCastBoard(BoardTool.Casting, permissions),
            wardrobe = ready?.buildCastBoard(BoardTool.Wardrobe, permissions),
            invoices = ready?.buildInvoices(permissions, scope),
            draft = ready?.let { graph ->
                graph.buildDraft(
                    drive = { driveHolder.get() },
                    projectId = { graph.projectContext?.context?.value?.project?.projectId },
                )
            },
            transport = ready?.buildTransport(permissions),
            scheduleDistribution = ready?.buildDistribution(DistributionTool.ScheduleDistribution, permissions),
            scriptDistribution = ready?.buildDistribution(DistributionTool.ScriptDistribution, permissions),
            scheduleDod = ready?.buildDistribution(DistributionTool.ScheduleDod, permissions),
            docDist = ready?.let { graph ->
                DocDistViewModel(
                    repository = graph.docDistRepository,
                    viewer = { graph.docDistViewer(permissions()) },
                    today = ::today,
                    rights = graph.rightsRequests,
                    host = AppDocDistHost(graph.signatureRepository),
                    badges = graph.docDistBadges(),
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
                    previewHost = AppDrivePreviewHost(graph.httpClient),
                    crew = { graph.driveCrew() },
                    newUploadId = { UUID.randomUUID().toString() },
                    rights = graph.rightsRequests,
                    now = System::currentTimeMillis,
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
    /** Opens a tool widget from the tool itself — the Drive header's button, for the other two. */
    openWidget: (ZillitWidget) -> Unit,
): AppTools {
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
                // A calendar call is a room, not a ring: everyone invited
                // dials the event's own group and walks in.
                onJoinEventCall = viewModels.calls?.let { vm ->
                    { event -> vm.onEvent(joinEventCall(event)) }
                },
            )
        }
    }
    // Info and Confidential Info ARE the notice board, on their own segments.
    val info = viewModels.info?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.INFO_PATH,
            title = str(S.info),
            icon = ZillitToolIcons.Info,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    // The diary answers at both paths: the tile's own, and the web mount that
    // the legacy pre-production tile shares.
    // The same room a calendar Join opens — the two surfaces show the same
    // events, so the lambda is the same one in both places.
    val joinDiaryCall: ((String, String, Boolean) -> Unit)? = viewModels.calls?.let { vm ->
        { roomId, title, video ->
            vm.onEvent(
                CallEvent.Place(
                    chatRoomId = roomId,
                    receiverDeviceId = "",
                    mode = CallMode.Group,
                    type = if (video) CallType.Video else CallType.Audio,
                    displayName = title,
                    isCalendarCall = true,
                ),
            )
        }
    }
    val diaryFaces = (graph as? AppGraph.Ready)?.let(::crewFaceLoader)
    val boxSchedule = viewModels.boxSchedule?.let {
        BoxScheduleToolProvider(it, BOX_SCHEDULE_PATH, onJoinCall = joinDiaryCall, loadAvatar = diaryFaces)
    }
    val preProduction = viewModels.boxSchedule?.let {
        BoxScheduleToolProvider(it, PRE_PRODUCTION_PATH, onJoinCall = joinDiaryCall, loadAvatar = diaryFaces)
    }
    val maps = viewModels.maps?.let { vm ->
        (graph as? AppGraph.Ready)?.mapToolProvider(vm) ?: MapToolProvider(vm, onOpenUrl = ::openInBrowser)
    }
    val recce = viewModels.recce?.let { RecceToolProvider(it, onOpenUrl = ::openInBrowser) }
    val externalUsers = viewModels.externalUsers?.let { vm ->
        (graph as? AppGraph.Ready)?.externalUsersProvider(vm, viewModels)
            ?: com.zillit.desktop.feature.externalusers.ui.ExternalUsersToolProvider(vm)
    }
    val distributionList = viewModels.distribution?.let { vm ->
        (graph as? AppGraph.Ready)?.distributionListProvider(vm, viewModels)
            ?: com.zillit.desktop.feature.distribution.ui.DistributionToolProvider(vm, onOpenUrl = ::openInBrowser)
    }
    val crewList = viewModels.crewList?.let { vm ->
        (graph as? AppGraph.Ready)?.crewListProvider(vm, viewModels, onOpenWidget = { openWidget(ZillitWidget.Crew) })
            ?: CrewListToolProvider(vm, onOpenWidget = { openWidget(ZillitWidget.Crew) })
    }
    val assetRegister = viewModels.assetRegister?.let {
        com.zillit.desktop.feature.assetreport.ui.AssetToolProvider(it)
    }
    val transport = viewModels.transport?.let { (graph as? AppGraph.Ready)?.transportProvider(it, viewModels) }
    val draft = viewModels.draft?.let { DraftToolProvider(it) }
    val location = viewModels.location?.let { vm -> (graph as? AppGraph.Ready)?.locationProvider(vm, scope) }
    val continuity = viewModels.continuity?.let { vm ->
        (graph as? AppGraph.Ready)?.continuityProvider(vm, scope)
    }
    val costReport = viewModels.costReport?.let { vm -> (graph as? AppGraph.Ready)?.costReportProvider(vm) }
    val costReportWorksheet = viewModels.costReportWorksheet?.let { vm ->
        (graph as? AppGraph.Ready)?.costReportWorksheetProvider(vm)
    }
    val costReportAnalytics = viewModels.costReportAnalytics?.let { vm ->
        com.zillit.desktop.feature.costreport.ui.analytics.AnalyticsToolProvider(vm)
    }
    val saPortal = viewModels.saPortal?.let { vm -> saPortalProviders(vm) }.orEmpty()
    val adDashboard = viewModels.adDashboard?.let { vm -> adDashboardProvider(vm) }
    val weather = viewModels.weather?.let { vm -> (graph as? AppGraph.Ready)?.weatherProvider(vm) }
    // Three casting tiles, one board: whichever tile is clicked, the lists
    // this viewer's rights allow are what open.
    val castingTools = viewModels.casting?.let { vm ->
        (graph as? AppGraph.Ready)?.let { readyGraph ->
            listOf(CASTING_PATH, CASTING_MAIN_PATH, CASTING_BACKGROUND_PATH)
                .map { path -> readyGraph.castBoardProvider(vm, path) }
        }
    }.orEmpty()
    // The same board again, for costumes: two tiles, two lists, one engine.
    val wardrobeTools = viewModels.wardrobe?.let { vm ->
        (graph as? AppGraph.Ready)?.let { readyGraph ->
            listOf(WARDROBE_MAIN_PATH, WARDROBE_BACKGROUND_PATH)
                .map { path -> readyGraph.castBoardProvider(vm, path) }
        }
    }.orEmpty()
    // The two budget tiles: one screen shape, two view models — as the web
    // mounts FullBudget and DepartmentBudget on two routes over one body.
    val mainBudget = viewModels.budget?.let { vms ->
        (graph as? AppGraph.Ready)?.budgetProvider(vms.main, MAIN_BUDGET_PATH, scope, audioPlayer)
    }
    val departmentBudget = viewModels.budget?.let { vms ->
        (graph as? AppGraph.Ready)?.budgetProvider(vms.department, DEPARTMENT_BUDGET_PATH, scope, audioPlayer)
    }
    val invoices = viewModels.invoices?.let { invoicesProvider(it) }
    // Schedule Full & One Line, Script & Pages, Schedule D.O.D — the same
    // PDF-distribution engine at the web's three paths.
    val ready = graph as? AppGraph.Ready
    val scheduleDistribution = viewModels.scheduleDistribution?.let { vm ->
        ready?.distributionProvider(
            vm,
            DistributionToolProvider.SCHEDULE_PATH,
            str(S.dd_pub_dest_schedule_card),
            ZillitToolIcons.Chedule,
            scope,
        )
    }
    val scriptDistribution = viewModels.scriptDistribution?.let { vm ->
        ready?.distributionProvider(
            vm, DistributionToolProvider.SCRIPT_PATH, str(S.dd_pub_dest_script_card), ZillitToolIcons.Script, scope,
        )
    }
    val scheduleDod = viewModels.scheduleDod?.let { vm ->
        ready?.distributionProvider(
            vm, DistributionToolProvider.DOD_PATH, str(S.dd_pub_dest_dod_card), ZillitToolIcons.Dod, scope,
        )
    }
    val confidentialInfo = viewModels.confidentialInfo?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.CONFIDENTIAL_INFO_PATH,
            title = str(S.confidential_info),
            icon = ZillitToolIcons.Info,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    val catering = viewModels.catering?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.CATERING_PATH,
            title = str(S.catering),
            icon = ZillitToolIcons.Catering,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    val accounts = viewModels.accounts?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.ACCOUNTS_PATH,
            title = str(S.desktop_message_accounts),
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
            title = str(S.desktop_camera_sound_report),
            icon = ZillitToolIcons.ProductionReport,
            feedViewModel = feed,
            board = boardContext,
            badges = (graph as? AppGraph.Ready)?.badgeStore?.counts,
        )
    }
    val scriptNotes = viewModels.scriptNotes?.let { feed ->
        BoardToolProvider(
            path = BoardToolProvider.SCRIPT_NOTES_PATH,
            title = str(S.script_notes),
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
            it, chatViewModel, viewModels.calls, audioPlayer, cncDownloadRight(viewModels),
            onOpenWidget = { openWidget(ZillitWidget.Chat) },
            mail = emailViewModel,
        )
    }
    val signatures = (graph as? AppGraph.Ready)?.let {
        SignatureToolProvider(it.signatureRepository, events = it.socketEvents)
    }
    // The web's two pop-outs: a composer, and a conversation, each in a
    // window of its own, served from the mailbox's own state.
    val mailCompose = email?.let { EmailComposePopoutProvider(it) }
    val mailThread = email?.let { provider ->
        (graph as? AppGraph.Ready)?.let { ready ->
            EmailThreadPopoutProvider(
                provider,
                host = EmailHost(
                    loadAvatar = { address ->
                        ready.projectContext?.context?.value?.users
                            ?.firstOrNull { it.email?.equals(address, ignoreCase = true) == true }
                            ?.let { user -> fetchAvatar(ready, user.userId)?.let(::decodeAvatar) }
                    },
                    onOpenLink = ::openInBrowser,
                    onPrint = ::printMailPage,
                    readBy = { messageId -> readByForSentMail(ready, messageId) },
                ),
            )
        }
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
            // Email rules: a Move action picks from the mailbox's folders, a Save
            // action browses the Drive one folder level at a time.
            folders = { ready.emailRepository.folders() },
            driveFolders = DriveFolderSource { parent -> ready.driveFolderOptions(parent) },
            events = ready.socketEvents,
            // The settings follow the mailbox the Email tool has open.
            scope = ready.activeMailbox,
            directory = MailboxDirectoryImpl(
                ready.apiClient,
                ready.config,
                projectId = { ready.projectContext?.context?.value?.project?.projectId },
                userName = { ready.projectContext?.context?.value?.profile?.fullName.orEmpty() },
            ),
        )
    }
    val mailContacts = (graph as? AppGraph.Ready)?.let { ready ->
        EmailContactsToolProvider(
            apiClient = ready.apiClient,
            config = ready.config,
            events = ready.socketEvents,
            scope = ready.activeMailbox,
            // "Write to" from the address book: queued for the mailbox, which
            // raises the composer when its window opens or at once if it is up.
            onWriteTo = { address -> emailViewModel?.composeRequests?.post(address) },
        )
    }
    // The rail's foot: SOS, and the two app pages beside it.
    val sos = (graph as? AppGraph.Ready)?.let { ready ->
        SosToolProvider(
            viewModel = SosViewModel(
                repository = SosRepositoryImpl(ready.apiClient, ready.config).readingLedger(ready.badgeStore),
                nowMillis = System::currentTimeMillis,
                viewer = { ready.projectContext?.context?.value.sosViewer() },
                crew = { ready.projectContext?.context?.value.sosCrew() },
                // An alarm raised on set must not wait for a refresh.
                events = ready.socketEvents,
            ),
            onOpenLink = ::openInBrowser,
            // An ordinary private call. Nothing about the wire is special —
            // only the screen it was started from.
            onCall = viewModels.calls?.let { vm ->
                { userId, deviceId, name, video ->
                    vm.onEvent(
                        CallEvent.Place(
                            chatRoomId = "",
                            receiverDeviceId = deviceId,
                            mode = CallMode.Private,
                            type = if (video) CallType.Video else CallType.Audio,
                            displayName = name,
                            receiverUserId = userId,
                        ),
                    )
                }
            },
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
                // the phones; the repository's read already flips the ledger
                // (see `readingLedger`), so nothing more is owed here.
                onListRead = {},
                // The same frame the badge store folds into the bell's count.
                // Without this the count went up and the open list did not.
                arrivals = ready.socketEvents.on(ZillitSocketEvents.Badges.Save).map { },
            ),
        )
    }
    val help = HelpToolProvider(
        onOpenExternal = ::openInBrowser,
        onContactSupport = ::queueSupportMessage,
        // Where it lands once queued: Zillit's own mailbox, not the OS's idea
        // of a mail client.
        supportComposeRoute = "/email",
        // A support call is a self-dial: it goes to this account's PRIMARY
        // device and the backend routes it to whichever agent is free.
        // Deliberately NOT this machine's device id — on a QR-linked desktop
        // that is a child row, and dialling it would ring this very computer.
        onCallSupport = callSupport(graph as? AppGraph.Ready, viewModels.calls),
    )
    val cash = viewModels.cashExpenses?.let { vm ->
        CashExpensesToolProvider(
            viewModel = vm,
            // A claim's receipt goes through the same routed store the boards
            // read: fetched to Downloads, then handed to the OS. The key is
            // stored bare on some productions, so the bucket and region are
            // left to the store's own defaults.
            onOpenAttachment = { key ->
                (graph as? AppGraph.Ready)?.let { ready ->
                    openNoticeAttachment(ready, scope)(
                        com.zillit.desktop.feature.home.domain.NoticeAttachment(
                            media = key,
                            fileName = key.substringAfterLast('/'),
                        ),
                    )
                }
            },
            // Every name in the cash tool is shown with the crew photo behind
            // it; the same cached loader the boards and calls use.
            loadAvatar = { userId ->
                (graph as? AppGraph.Ready)?.let { ready -> crewFaceLoader(ready)(userId) }
            },
        )
    }
    val cards = viewModels.cardExpenses?.let { vm ->
        CardExpensesToolProvider(
            viewModel = vm,
            // Same routed store as the boards and the cash receipts: fetched
            // to Downloads, then handed to the OS.
            onOpenAttachment = { key ->
                (graph as? AppGraph.Ready)?.let { ready ->
                    openNoticeAttachment(ready, scope)(
                        com.zillit.desktop.feature.home.domain.NoticeAttachment(
                            media = key,
                            fileName = key.substringAfterLast('/'),
                        ),
                    )
                }
            },
        )
    }
    val orders = viewModels.purchaseOrders?.let { vm ->
        PurchaseOrderToolProvider(
            viewModel = vm,
            // An order's paperwork goes through the same routed store the
            // boards read: fetched to Downloads, then handed to the OS.
            onOpenAttachment = { file ->
                (graph as? AppGraph.Ready)?.let { ready ->
                    openNoticeAttachment(ready, scope)(
                        com.zillit.desktop.feature.home.domain.NoticeAttachment(
                            media = file.media,
                            fileName = file.displayName,
                            bucket = file.bucket,
                            region = file.region,
                        ),
                    )
                }
            },
        )
    }
            val timecards = viewModels.timecards?.let { TimecardToolProvider(it) }

    val payroll = viewModels.payroll?.let { PayrollToolProvider(it) }
    val deals = viewModels.dealMemos?.let { vm ->
        (graph as? AppGraph.Ready)?.dealMemoProvider(vm) ?: DealMemoToolProvider(vm)
    }
    val distribution = viewModels.docDist?.let {
        // openInBrowser is the guarded launcher — https only, so a presigned
        // storage URL opens and anything else is refused.
        DocDistToolProvider(it, onOpenUrl = ::openInBrowser)
    }
    val drive = viewModels.drive?.let { driveProvider(it, scope, openDriveWidget) }
    // The console hands off to the finance tools above via its own window
    // navigator; from here it takes only the ledger's counts and the theme.
    // Filled once every provider exists, below; the console resolves the tools
    // it embeds through it lazily, so the registry can list the console too.
    var registryRef: ToolRegistry? = null
    val accountHub = viewModels.accountHub?.let { viewModel ->
        val ready = graph as? AppGraph.Ready
        AccountHubToolProvider(
            viewModel,
            tools = { path -> registryRef?.resolve(WorkspaceRoute.Tool(path)) },
            badges = ready?.hubBadges(scope),
            // Approvers, pickers and chips show crew photos, as the web's UserAvatar does.
            loadAvatar = { userId -> ready?.let { crewFaceLoader(it)(userId) } },
        )
    }
    // Registered under the console's own path, which is the only place it is
    // reached from — see TaxFilingToolProvider.
    val taxFiling = viewModels.taxFiling?.let { taxFilingProvider(it) }
    val bankRec = viewModels.bankRec?.let { bankRecProvider(it) }
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
        (graph as? AppGraph.Ready)?.formSignatureProvider(
            viewModel = vm,
            permissions = { viewModels.home?.state?.value?.permissions ?: ProjectPermissions.Empty },
            scope = scope,
            board = boardContext,
        )
    }
    val esignature = viewModels.esignature?.let { vm ->
        EsignToolProvider(vm) { kind, onPicked ->
            scope.launch { onPicked(pickEsignFile(kind)) }
        }
    }
    val callSheet = viewModels.callSheet?.let { callSheetToolProvider(it, graph) }
    val sides = viewModels.sides?.let { vm ->
        SidesToolProvider(
            viewModel = vm,
            onPickFile = { pdfOnly, onPicked -> scope.launch { onPicked(pickSidesDocument(pdfOnly)) } },
            onOpenUrl = ::openInBrowser,
            onSaveFile = { fileName, bytes -> scope.launch { saveSidesFile(fileName, bytes) } },
            loadAvatar = (graph as? AppGraph.Ready)?.let { crewFaceLoader(it) } ?: { null },
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
    val productionReport = viewModels.productionReport?.let {
        reportToolProvider(it, graph, viewModels.productionReportChat, boardContext)
    }
    val adReport = viewModels.adReport?.let { reportToolProvider(it, graph) }
    val wrapReport = viewModels.wrapReport?.let { reportToolProvider(it, graph) }
    val real = listOfNotNull(
        home, chat, email, mailCompose, mailThread, signatures, mailSettings, mailContacts,
        settings, admin, notifications, sos, help,
        cash, cards, orders, timecards, payroll, deals, distribution, drive,
        accountHub, taxFiling, bankRec, budgetBuilder, formSignature, esignature,
        callSheet, productionReport, adReport, wrapReport, sides, permissionGrid,
        info, confidentialInfo, reports, scriptNotes,
        catering, accounts,
        boxSchedule, preProduction, maps, recce, externalUsers, distributionList, crewList,
        assetRegister, transport, location, continuity, costReport, costReportWorksheet, costReportAnalytics,
        invoices, draft,
        mainBudget, departmentBudget, weather, adDashboard,
        scheduleDistribution, scriptDistribution, scheduleDod,
    ) + castingTools + wardrobeTools + saPortal
    val realPaths = real.map { it.path }.toSet()
    val registry = ToolRegistry(real + placeholderTools().filterNot { it.path in realPaths })
    registryRef = registry
    return AppTools(
        registry = registry,
        // The widgets' own copies: the same ViewModels — and the same single
        // audio player — in their one-pane shape. Built here because that is
        // where those instances live; building them outside would mint a
        // second speaker and a second chat.
        chatWidget = chatViewModel?.let {
            chatProvider(
                graph as AppGraph.Ready, it, viewModels.calls, audioPlayer,
                canDownload = cncDownloadRight(viewModels),
                compact = true,
                mail = emailViewModel,
            )
        },
        crewWidget = viewModels.crewList?.let { CrewListToolProvider(it, compact = true) },
    )
}

/** The registry, plus the compact providers the Chat and Crew List widgets show. */
private class AppTools(
    val registry: ToolRegistry,
    val chatWidget: ToolProvider?,
    val crewWidget: ToolProvider?,
)

/**
 * The C&C tool's download right (Android gates saves with `msg_download_right`
 * on the same flag). A production whose tools list never mentions the tool
 * leaves chat ungated, as the phones' chat page is.
 *
 * Shared by the rail's chat and the widget's, so one grid change moves both.
 */
private fun cncDownloadRight(viewModels: AppViewModels): () -> Boolean = canDownload@{
    val permissions = viewModels.home?.state?.value?.permissions ?: return@canDownload true
    if (permissions.tools.none { tool -> tool.identifier == CNC_TOOL_IDENTIFIER }) {
        return@canDownload true
    }
    permissions.canDownload(CNC_TOOL_IDENTIFIER)
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
/**
 * Rings the 24x7 support team, or null when there is nothing to ring with.
 *
 * Null rather than a no-op button: no calling, or no open production, and the
 * control simply is not drawn — pressing something that answers with an
 * apology is worse on a support screen than anywhere else.
 *
 * The dial target is the account's own primary device. Nothing on the wire
 * says "support"; the backend recognises the self-dial and routes it. The
 * `is247Call` flag is local, and is what keeps Record and Add-people off the
 * call once it connects.
 */
/**
 * The Place event for a calendar or box-schedule room.
 *
 * One helper because three surfaces raise it — the calendar popover, the box
 * schedule and pre-production — and three copies of the same six arguments is
 * how they drift apart. The room id IS the call: nobody is rung, and the
 * `isCalendarCall` flag is what keeps hanging up from closing the room on
 * everyone still in it.
 */
private fun joinEventCall(event: CalendarEvent): CallEvent.Place = CallEvent.Place(
    chatRoomId = event.cncGroupId,
    receiverDeviceId = "",
    mode = CallMode.Group,
    type = if (event.callType?.prefersVideo == true) CallType.Video else CallType.Audio,
    displayName = event.title,
    isCalendarCall = true,
)

private fun callSupport(
    ready: AppGraph.Ready?,
    calls: CallViewModel?,
): (suspend () -> String?)? {
    if (ready == null || calls == null) return null
    return {
        ZillitLog.i(SUPPORT_TAG) { "call us pressed" }
        // Both of these are read WHEN THE BUTTON IS PRESSED, not when it is
        // built. The registry is assembled once, early, and the profile often
        // is not loaded by then — reading it there returned null, which took
        // the whole button away and made the failure look like a missing
        // feature rather than a not-yet.
        val userId = ready.projectContext?.context?.value?.profile?.userId.orEmpty()
        val primary = ready.accountRepository.linkedDevices()
            .getOrNull()
            ?.firstOrNull { it.isPrimary }
            ?.id
            .orEmpty()
        when {
            userId.isBlank() -> str(S.desktop_support_open_project_first)
            // A blank receiver is dropped from the request body, so this would
            // place a call nobody was ever invited to.
            primary.isBlank() -> {
                ZillitLog.w(SUPPORT_TAG) { "no primary device on this account; support call not placed" }
                str(S.desktop_support_no_primary_device)
            }
            else -> {
                ZillitLog.i(SUPPORT_TAG) { "placing a support call to the primary device" }
                calls.onEvent(
                    CallEvent.Place(
                        chatRoomId = "",
                        receiverDeviceId = primary,
                        mode = CallMode.Private,
                        type = CallType.Audio,
                        displayName = str(S.desktop_zillit_support),
                        receiverUserId = userId,
                        is247Call = true,
                    ),
                )
                null
            }
        }
    }
}

private fun ProjectContext?.sosCrew(): List<SosCrewMember> =
    this?.users.orEmpty().mapNotNull { user ->
        val id = user.userId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Translated words, not the wire's label key — the same rule the
        // Contacts tab applies (designationText drops the placeholder too).
        SosCrewMember(
            userId = id,
            fullName = user.fullName,
            designation = user.designationText().orEmpty(),
            // Carried so an alert can be answered with a call. The list keeps
            // people who have left — the picker still has to name them on old
            // alerts — so their status rides along and the call refuses them,
            // which a blank device id would not: this roster keeps that too.
            deviceId = user.deviceId.orEmpty(),
            hasLeft = user.status == "left" || user.status == "removed",
        )
    }

private fun ProjectContext.crewContacts(): List<EmailContact> =
    users.mapNotNull { user ->
        val address = user.email?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        EmailContact(
            address = address,
            name = if (user.keepNamePrivate) "" else user.fullName,
            source = ContactSource.ProjectUser,
            userId = user.userId,
            subtitle = user.departmentText().orEmpty(),
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
    ringtone = preferences.observe(ZillitPreferences.RingOnIncomingCall),
    activity = preferences.observe(ZillitPreferences.NotifyActivity),
    callWidget = preferences.observe(ZillitPreferences.CallWidget),
    messageWidget = preferences.observe(ZillitPreferences.MessageWidget),
    closeToTray = preferences.observe(ZillitPreferences.CloseToTray),
    startAtLogin = preferences.observe(ZillitPreferences.StartAtLogin),
    startAtLoginAvailable = LoginItem.available,
    setMuted = { muted -> scope.launch { preferences.set(ZillitPreferences.MuteNotifications, muted) } },
    setMessages = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyMessages, on) } },
    setMail = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyMail, on) } },
    setUpdates = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyUpdates, on) } },
    setCalls = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyCalls, on) } },
    setRingtone = { on -> scope.launch { preferences.set(ZillitPreferences.RingOnIncomingCall, on) } },
    setActivity = { on -> scope.launch { preferences.set(ZillitPreferences.NotifyActivity, on) } },
    setCallWidget = { on -> scope.launch { preferences.set(ZillitPreferences.CallWidget, on) } },
    setMessageWidget = { on -> scope.launch { preferences.set(ZillitPreferences.MessageWidget, on) } },
    setCloseToTray = { on -> scope.launch { preferences.set(ZillitPreferences.CloseToTray, on) } },
    setStartAtLogin = { on -> scope.launch { LoginItem.sync(preferences, on) } },
    // The widgets' switches, read and written where the tray and the windows
    // read and write them — the preference file, not a copy.
    widgets = widgetToggles(preferences),
    setWidget = { id, on ->
        ZillitWidget.entries.firstOrNull { it.name == id }
            ?.let { widget -> scope.launch { preferences.set(widget.keys.open, on) } }
    },
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
        // The same preference the bar's globe writes; the graph's string
        // store and the label refresh both follow it.
        setLanguage = { code -> scope.launch { preferences.set(ZillitPreferences.Language, code) } },
        language = preferences.observe(ZillitPreferences.Language),
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
        // The About row's button — the same checker the banner polls, so the
        // two can never disagree about what "latest" is. Null before the
        // graph is ready: there is no client to ask with.
        checkForUpdates = ready?.let { { it.appUpdateChecker.check() } },
        // Follows the loaded production, like the units above. The snapshot in
        // `initial` is taken before the profile has arrived, so read once this
        // was blank forever — and `isAdmin` never became true, which kept the
        // administration entry out of the rail for everyone.
        account = ready?.projectContext?.context?.map {
            AccountSummary(
                userId = it.profile?.userId.orEmpty(),
                fullName = it.profile?.fullName.orEmpty(),
                email = it.profile?.email.orEmpty(),
                productionName = it.project?.name.orEmpty(),
                isAdmin = it.isAdmin,
            )
        } ?: flowOf(AccountSummary()),
        initial = SettingsUiState(
            about = AboutInfo(
                version = installedAppVersion(),
                build = BuildInfo.GIT_SHA,
                platform = hostPlatformLabel(),
            ),
            unit = UnitSelection(selectedId = context?.profile?.joinUnitId),
            account = AccountSummary(
                userId = context?.profile?.userId.orEmpty(),
                fullName = context?.profile?.fullName.orEmpty(),
                email = context?.profile?.email.orEmpty(),
                productionName = context?.project?.name.orEmpty(),
                isAdmin = context?.isAdmin == true,
            ),
            // Which rows the administration page can offer — a corporate or
            // event production runs no shooting units, and both phone clients
            // drop those rows rather than offer a unit that cannot exist.
            admin = AdminSettingsUiState(
                production = productionFacts(
                    name = context?.project?.name,
                    type = context?.project?.type,
                    // Set only when this production is itself a remote unit,
                    // which cannot spawn units of its own.
                    parentName = context?.project?.parentName,
                    markedForDeletion = context?.project?.markedForDeletion == true,
                ),
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
                userId = profile?.userId.orEmpty(),
                firstName = profile?.firstName.orEmpty(),
                lastName = profile?.lastName.orEmpty(),
                email = profile?.email.orEmpty(),
                departmentId = profile?.departmentId,
                designationId = profile?.designationId,
                // The untranslated key, which is what the privacy toggle gates on.
                designationName = profile?.designationName,
                keepNamePrivate = profile?.keepNamePrivate == true,
                showMailboxInCrewList = profile?.showMailboxInCrewList ?: true,
                mailboxAddress = profile?.mailboxAddress,
                isPersonal = context.project?.type.equals(PERSONAL_PRODUCTION, ignoreCase = true),
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

/** What either chat picker hands on: the same three fields, whichever dialog opened. */
private class PickedBytes(val name: String, val contentType: String, val bytes: ByteArray)

/** Asking to join a production: code lookup, details, request. */
private fun buildJoin(ready: AppGraph.Ready) = JoinProductionViewModel(
    projectRepository = ready.projectRepository,
    unitRepository = ready.unitRepository,
    photoStore = ready.joinPhotoStore(),
    choosePhoto = ::chooseJoinPhoto,
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
/**
 * Queues a message to support in Zillit's own mail.
 *
 * Not `mailto:`. A `mailto:` is only as good as whatever the OS registered for
 * it, and here that is a *browser*, which accepts the URL, reports success and
 * then does nothing unless it has separately been told which webmail to hand
 * it to. Every layer reported success and no compose window ever appeared —
 * `open` exits 0, `Desktop.mail` returns normally, and nothing anywhere
 * signals that the message was dropped.
 *
 * The app has a mailbox of its own, which is what the web client uses for this
 * row too. Left here for the mail window to claim when it opens, because the
 * composer is raised by an effect and an effect emitted before that window
 * exists has nobody collecting it.
 */
private fun queueSupportMessage(): String? {
    ZillitLog.i(SUPPORT_TAG) { "write to us pressed" }
    pendingSupportCompose = SUPPORT_ADDRESS to SUPPORT_SUBJECT
    return null
}

/**
 * The message waiting for the mail window, if any.
 *
 * Read once and cleared, so re-opening mail later does not raise a composer
 * the user never asked for a second time.
 */
@Volatile
private var pendingSupportCompose: Pair<String, String>? = null

private fun claimPendingSupportCompose(): Pair<String, String>? {
    val pending = pendingSupportCompose
    pendingSupportCompose = null
    if (pending != null) ZillitLog.i(SUPPORT_TAG) { "mail opened; starting the message to support" }
    return pending
}

private const val SUPPORT_SUBJECT = "Zillit Issue"

private const val SUPPORT_TAG = "Support"

private const val SUPPORT_ADDRESS = "support@zillit.com"

/** The segment the phones count the bell against (`GLOBAL_LABEL` on Android). */
private const val GLOBAL_BADGE_SEGMENT = "global_label"

/** The SOS feed's own segment — `SosEndpoints.SEGMENT`, kept a literal here as every other rail key is. */
private const val SOS_BADGE_SEGMENT = "sos_label"

private const val CRASH_TAG = "Crash"

/** The Drive's folders under [parentId] (null = the root), as the email-rules picker lists them. */
private suspend fun AppGraph.Ready.driveFolderOptions(parentId: String?): ZillitResult<List<DriveFolderOption>> =
    driveRepository.listing(DriveListQuery(scope = DriveScope.Mine)).map { listing ->
        listing.folders.filter { it.parentFolderId == parentId }.map { DriveFolderOption(it.id, it.name) }
    }

/**
 * The desktop widgets as Settings lists them, live from the preference file.
 *
 * Combined rather than one flow each so the section repaints once when a
 * switch moves, wherever it was moved from.
 */
private fun widgetToggles(
    preferences: PreferenceStore,
): kotlinx.coroutines.flow.Flow<List<com.zillit.desktop.feature.settings.ui.WidgetToggle>> =
    kotlinx.coroutines.flow.combine(
        ZillitWidget.entries.map { widget -> preferences.observe(widget.keys.open) },
    ) { open ->
        ZillitWidget.entries.mapIndexed { index, widget ->
            com.zillit.desktop.feature.settings.ui.WidgetToggle(
                id = widget.name,
                label = str(S.desktop_widget_row_label, widget.label),
                detail = widget.widgetDetail,
                on = open[index],
            )
        }
    }

/** What each widget's Settings row says it does. */
private val ZillitWidget.widgetDetail: String
    get() = when (this) {
        ZillitWidget.Drive -> str(S.desktop_widget_detail_drive)
        ZillitWidget.Chat -> str(S.desktop_widget_detail_chat)
        ZillitWidget.Crew -> str(S.desktop_widget_detail_crew)
    }

/** The chat module names a line by its wire word; the calls module by its provider. */
internal fun CallLine.toProvider(): CallProvider = when (this) {
    CallLine.One -> CallProvider.Mediasoup
    CallLine.Two -> CallProvider.Agora
    CallLine.Three -> CallProvider.LiveKit
}

/** The lines a production offers. Line 3 only where the roll-out list names it — see LineThreeGate. */
internal fun AppGraph.Ready.callLines(projectId: String?): List<CallLine> =
    if (lineThreeEnabled(projectId)) CallLine.DEFAULT + CallLine.Three else CallLine.DEFAULT
