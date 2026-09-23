package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ApplicationScope
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import com.zillit.desktop.feature.drive.ui.handle
import com.zillit.desktop.feature.drive.ui.DriveScreen
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.launch

/**
 * The Drive widget: a small window that lives on the desktop beside whatever
 * else is open, showing one production's drive.
 *
 * The window itself — floating or desktop-layer, its geometry, its grip and
 * its signed-out page — is [WidgetWindow]'s; this file is what goes inside.
 *
 * ## The session is the main app's
 *
 * There is no login here. Signed in means the main window is signed in;
 * the moment it is not — sign-out, revoked device, expired session — this
 * shows the signed-out page and brings the main window forward, which is where
 * the QR code is. Nothing in the widget can be used to get past that.
 *
 * ## Productions
 *
 * The picker in the title strip lists the same productions the main window
 * offers; choosing one scopes the widget to it (see [DriveWidgetHost]) —
 * the main window stays where it was. This is the one thing the Drive widget
 * has that the others do not: chat and the crew list belong to the production
 * the app is open on.
 */
@Composable
@Suppress("LongParameterList") // Every seam is a distinct host concern; a holder object would just rename them.
internal fun ApplicationScope.DriveWidgetWindow(
    host: DriveWidgetHost?,
    auth: AuthViewModel?,
    preferences: PreferenceStore,
    visible: Boolean,
    darkTheme: Boolean,
    graph: AppGraph,
    onClose: () -> Unit,
    /** Raises the main window — the sign-in page when signed out. */
    showMain: () -> Unit,
) {
    WidgetWindow(
        title = str(S.desktop_zillit_drive),
        keys = ZillitPreferences.DriveWidget,
        preferences = preferences,
        visible = visible,
        darkTheme = darkTheme,
        graph = graph,
        onClose = onClose,
    ) { chrome ->
        WidgetContent(
            host = host,
            auth = auth,
            preferences = preferences,
            chrome = chrome,
            showMain = showMain,
        )
    }
}

/** Signed in → the bar and one production's drive; otherwise the signed-out page. */
@Composable
private fun WidgetContent(
    host: DriveWidgetHost?,
    auth: AuthViewModel?,
    preferences: PreferenceStore,
    chrome: WidgetChrome,
    showMain: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val authState = auth?.state?.collectAsState()?.value
    val signedIn = authState?.step?.isSignedIn == true
    val session by (host?.session ?: NO_SESSION).collectAsState()

    // Signed out while the widget is up: nothing here works any more. The
    // main window comes forward on its own — that is where the QR code is —
    // and the drive is dropped, so a later sign-in as someone else starts
    // clean.
    LaunchedEffect(signedIn) {
        if (!signedIn) {
            host?.close()
            showMain()
        }
    }

    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (host == null || authState == null || !signedIn) {
            WidgetSignedOut(what = str(S.desktop_the_drive_widget), onOpenZillit = showMain)
            return@Column
        }

        val projects = authState.projects.filter { it.isOpenable }
        // The production the widget shows: last time's, else the open one,
        // else the first. Persisted so it survives a restart.
        LaunchedEffect(projects, authState.activeProject?.id) {
            if (session != null || projects.isEmpty()) return@LaunchedEffect
            val remembered = preferences.get(ZillitPreferences.DriveWidgetProject)
            val chosen = projects.firstOrNull { it.id == remembered }
                ?: projects.firstOrNull { it.id == authState.activeProject?.id }
                ?: projects.first()
            host.select(chosen)
        }

        WidgetBar(chrome = chrome, onOpenZillit = showMain) {
            if (projects.isNotEmpty()) {
                ZillitSelect(
                    value = session?.project ?: projects.first(),
                    options = projects,
                    onSelect = { project ->
                        scope.launch { preferences.set(ZillitPreferences.DriveWidgetProject, project.id) }
                        host.select(project)
                    },
                    label = { it.name },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Box(Modifier.weight(1f))
            }
        }
        ZillitDivider()
        WidgetBody(projects = projects, session = session, onRetry = { host.retry() })
    }
}

/** Empty, loading, failed, or the drive. */
@Composable
private fun WidgetBody(projects: List<Project>, session: DriveWidgetHost.Session?, onRetry: () -> Unit) {
    when {
        projects.isEmpty() -> ZillitEmptyState(
            title = str(S.desktop_no_projects),
            message = str(S.desktop_device_on_no_project),
            icon = ZillitIcons.Drive,
        )
        session == null || session.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { ZillitSpinner() }
        session.error != null -> ZillitErrorState(
            title = str(S.desktop_could_not_open_drive),
            message = session.error,
            onRetry = onRetry,
        )
        session.viewModel != null -> WidgetDrive(session.viewModel)
    }
}

/** The compact Drive, plus the host seams the tool has: browser, editor, clipboard, pickers. */
@Composable
private fun WidgetDrive(viewModel: DriveViewModel) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }
    val host = remember(viewModel) { driveHostSeams(viewModel, scope) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect -> failure = handle(effect, viewModel, host) ?: failure }
    }

    Box(Modifier.fillMaxSize()) {
        DriveScreen(state = state, onEvent = viewModel::onEvent, compact = true, now = host.now)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

private val NO_SESSION = kotlinx.coroutines.flow.MutableStateFlow<DriveWidgetHost.Session?>(null)
