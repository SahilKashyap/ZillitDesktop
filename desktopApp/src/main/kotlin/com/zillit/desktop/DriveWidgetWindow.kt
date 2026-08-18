package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.ZillitPreferences
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.AuthStep
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import com.zillit.desktop.feature.drive.ui.DriveEffect
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveScreen
import com.zillit.desktop.feature.drive.ui.DriveViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Drive widget: a small always-on-top window that lives on the desktop
 * beside whatever else is open, showing one production's drive.
 *
 * ## Why a window of the app, not an OS widget
 *
 * macOS Notification Center widgets are Swift extensions built with Xcode,
 * signed, and by design a snapshot surface — no folder browsing, no upload;
 * Windows 11 widgets need a Store-published web card. Neither can hold this
 * app's session. A window of the running app has the session for free, runs
 * on both platforms unchanged, and can do everything the Drive tool does.
 *
 * ## The session is the main app's
 *
 * There is no login here. Signed in means the main window is signed in;
 * the moment it is not — sign-out, revoked device, expired session — this
 * shows [SignedOut] and brings the main window forward, which is where the
 * QR code is. Nothing in the widget can be used to get past that.
 *
 * ## Productions
 *
 * The picker in the title strip lists the same productions the main window
 * offers; choosing one scopes the widget to it (see [DriveWidgetHost]) —
 * the main window stays where it was.
 */
@Composable
@Suppress("LongParameterList") // Every seam is a distinct host concern; a holder object would just rename them.
internal fun ApplicationScope.DriveWidgetWindow(
    host: DriveWidgetHost?,
    auth: AuthViewModel?,
    preferences: PreferenceStore,
    visible: Boolean,
    darkTheme: Boolean,
    onClose: () -> Unit,
    /** Raises the main window — the sign-in page when signed out. */
    showMain: () -> Unit,
) {
    if (!visible) return
    val scope = rememberCoroutineScope()
    val windowState = rememberDriveWidgetWindowState(preferences)
    var pinned by remember { mutableStateOf(true) }

    LaunchedEffect(windowState.size, windowState.position) {
        delay(WIDGET_GEOMETRY_SETTLE_MILLIS)
        runCatching { preferences.saveDriveWidgetGeometry(windowState) }
    }

    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = "Zillit Drive",
        icon = androidx.compose.ui.res.painterResource("icons/zillit-icon.png"),
        alwaysOnTop = pinned,
        resizable = true,
    ) {
        ZillitTheme(darkTheme = darkTheme) {
            WidgetContent(
                host = host,
                auth = auth,
                preferences = preferences,
                pinned = pinned,
                onTogglePin = { pinned = !pinned },
                showMain = showMain,
            )
        }
    }
}

/** Signed in → the bar and one production's drive; otherwise [SignedOut]. */
@Composable
@Suppress("LongParameterList")
private fun WidgetContent(
    host: DriveWidgetHost?,
    auth: AuthViewModel?,
    preferences: PreferenceStore,
    pinned: Boolean,
    onTogglePin: () -> Unit,
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
            SignedOut(onOpenZillit = showMain)
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

        WidgetBar(
            projects = projects,
            current = session?.project,
            pinned = pinned,
            onSelect = { project ->
                scope.launch { preferences.set(ZillitPreferences.DriveWidgetProject, project.id) }
                host.select(project)
            },
            onTogglePin = onTogglePin,
            onOpenZillit = showMain,
        )
        ZillitDivider()
        WidgetBody(projects = projects, session = session, onRetry = { host.retry() })
    }
}

/** Empty, loading, failed, or the drive. */
@Composable
private fun WidgetBody(projects: List<Project>, session: DriveWidgetHost.Session?, onRetry: () -> Unit) {
    when {
        projects.isEmpty() -> ZillitEmptyState(
            title = "No productions",
            message = "This device is not on any production yet.",
            icon = ZillitIcons.Drive,
        )
        session == null || session.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { ZillitSpinner() }
        session.error != null -> ZillitErrorState(
            title = "Could not open this drive",
            message = session.error,
            onRetry = onRetry,
        )
        session.viewModel != null -> WidgetDrive(session.viewModel)
    }
}

/** The compact Drive, plus the host seams the tool has: browser, editor, clipboard, picker. */
@Composable
private fun WidgetDrive(viewModel: DriveViewModel) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    var failure by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DriveEffect.Failed -> failure = effect.message
                is DriveEffect.OpenUrl -> openInBrowser(effect.url)
                is DriveEffect.OpenEditor -> DocumentEditorWindow.open(
                    url = effect.url,
                    fileName = effect.fileName,
                    scope = scope,
                    onUnavailable = { reason -> viewModel.onEditorUnavailable(reason) },
                )
                is DriveEffect.CopyToClipboard -> {
                    copyToClipboard(effect.text)
                    failure = effect.label
                }
                DriveEffect.PickFiles -> scope.launch {
                    val picked = DriveFilePicker().pick()
                    if (picked.isNotEmpty()) viewModel.onEvent(DriveEvent.Upload(picked))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        DriveScreen(state = state, onEvent = viewModel::onEvent, compact = true)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

/** The strip along the top: which production, pinned or not, and the way to the main window. */
@Composable
private fun WidgetBar(
    projects: List<Project>,
    current: Project?,
    pinned: Boolean,
    onSelect: (Project) -> Unit,
    onTogglePin: () -> Unit,
    onOpenZillit: () -> Unit,
) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        if (projects.isNotEmpty()) {
            ZillitSelect(
                value = current ?: projects.first(),
                options = projects,
                onSelect = onSelect,
                label = { it.name },
                modifier = Modifier.weight(1f),
            )
        } else {
            Box(Modifier.weight(1f))
        }
        ZillitIconButton(
            icon = ZillitIcons.Pin,
            contentDescription = if (pinned) "Unpin from the top" else "Keep on top",
            onClick = onTogglePin,
            tint = if (pinned) colors.accent else colors.textMuted,
        )
        ZillitIconButton(
            icon = ZillitIcons.Monitor,
            contentDescription = "Open Zillit",
            onClick = onOpenZillit,
        )
    }
}

/**
 * What the widget shows without a session. Says why, and offers the one
 * thing that helps — the main window, which is on the sign-in page.
 */
@Composable
private fun SignedOut(onOpenZillit: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
        ZillitEmptyState(
            title = "Zillit is signed out",
            message = "The Drive widget uses the main Zillit app's session. Sign in there — scan the QR " +
                "code — and the widget will work again.",
            icon = ZillitIcons.Shield,
            action = {
                ZillitButton(text = "Open Zillit to sign in", onClick = onOpenZillit, size = ButtonSize.Small)
            },
        )
    }
}

/** Signed in, for the widget's purposes: the device is linked and the session is live. */
private val AuthStep.isSignedIn: Boolean
    get() = this == AuthStep.Complete || this == AuthStep.ProjectSelection

@Composable
private fun rememberDriveWidgetWindowState(preferences: PreferenceStore): WindowState {
    val (size, position) = remember {
        runBlocking {
            val width = preferences.get(ZillitPreferences.DriveWidgetWidth)
            val height = preferences.get(ZillitPreferences.DriveWidgetHeight)
            val x = preferences.get(ZillitPreferences.DriveWidgetX)
            val y = preferences.get(ZillitPreferences.DriveWidgetY)
            val hasPosition = x != ZillitPreferences.UNSET_POSITION && y != ZillitPreferences.UNSET_POSITION
            DpSize(width.dp, height.dp) to
                if (hasPosition) WindowPosition(x.dp, y.dp) else WindowPosition.Aligned(Alignment.TopEnd)
        }
    }
    return rememberWindowState(size = size, position = position)
}

private suspend fun PreferenceStore.saveDriveWidgetGeometry(state: WindowState) {
    set(ZillitPreferences.DriveWidgetWidth, state.size.width.value.toInt().coerceAtLeast(1))
    set(ZillitPreferences.DriveWidgetHeight, state.size.height.value.toInt().coerceAtLeast(1))
    val position = state.position
    if (position.isSpecified) {
        set(ZillitPreferences.DriveWidgetX, position.x.value.toInt())
        set(ZillitPreferences.DriveWidgetY, position.y.value.toInt())
    }
}

private val NO_SESSION = kotlinx.coroutines.flow.MutableStateFlow<DriveWidgetHost.Session?>(null)
private const val WIDGET_GEOMETRY_SETTLE_MILLIS = 400L
