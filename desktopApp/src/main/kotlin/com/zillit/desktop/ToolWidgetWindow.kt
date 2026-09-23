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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ApplicationScope
import com.zillit.desktop.core.datastore.PreferenceKey
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.WidgetKeys
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.auth.domain.Project
import com.zillit.desktop.feature.auth.ui.AuthViewModel
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.launch

/**
 * A widget onto one of the app's own tools — Chat & Calls, the Crew List —
 * with a production picker of its own, like the Drive widget's.
 *
 * ## Choosing the open production costs nothing
 *
 * On the production the app is already on, the widget renders over the rail's
 * own ViewModel: one socket, one badge ledger, one call in flight. A message
 * read in the widget is read in the tool, because there is only one of each —
 * only the layout differs.
 *
 * ## Another production is a scoped copy
 *
 * Pick a different production and the widget builds its own stack for it —
 * every call naming that production and the user's id there
 * ([ToolWidgetHost]). Live messages still arrive: the chat socket is
 * registered per **user**, not per production, and each repository keeps only
 * the frames naming its own project.
 *
 * Attachments, voice notes and calls work on a scoped copy too, each told
 * which production it is acting on — files land in that production's own
 * storage, and a call carries its id and the caller's id there.
 *
 * The one thing that stays the open production's is the unread badge count,
 * so a widget on another production reads its messages without a count to
 * clear ([ScopedNote]).
 */
@Composable
@Suppress("LongParameterList") // Every seam is a distinct host concern; a holder object would just rename them.
internal fun ApplicationScope.ToolWidgetWindow(
    title: String,
    /** Names the widget in the signed-out sentence — "The Chat widget". */
    what: String,
    keys: WidgetKeys,
    /** Remembers the production this widget last showed, across restarts. */
    projectKey: PreferenceKey.StringKey,
    host: ToolWidgetHost?,
    route: WorkspaceRoute,
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
        title = title,
        keys = keys,
        preferences = preferences,
        visible = visible,
        darkTheme = darkTheme,
        graph = graph,
        onClose = onClose,
    ) { chrome ->
        WidgetToolContent(
            what = what,
            projectKey = projectKey,
            host = host,
            route = route,
            auth = auth,
            preferences = preferences,
            chrome = chrome,
            showMain = showMain,
        )
    }
}

@Composable
@Suppress("LongParameterList")
private fun WidgetToolContent(
    what: String,
    projectKey: PreferenceKey.StringKey,
    host: ToolWidgetHost?,
    route: WorkspaceRoute,
    auth: AuthViewModel?,
    preferences: PreferenceStore,
    chrome: WidgetChrome,
    showMain: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val authState = auth?.state?.collectAsState()?.value
    val signedIn = authState?.step?.isSignedIn == true
    val session by (host?.session ?: NO_TOOL_SESSION).collectAsState()

    // Signed out while the widget is up: nothing here works any more. The main
    // window comes forward on its own — that is where the QR code is — and the
    // production is dropped, so a later sign-in as someone else starts clean.
    LaunchedEffect(signedIn) {
        if (!signedIn) {
            host?.close()
            showMain()
        }
    }

    Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (host == null || authState == null || !signedIn) {
            WidgetSignedOut(what = what, onOpenZillit = showMain)
            return@Column
        }

        val projects = authState.projects.filter { it.isOpenable }
        // The production the widget shows: last time's, else the open one,
        // else the first. Persisted so it survives a restart.
        LaunchedEffect(projects, authState.activeProject?.id) {
            if (session != null || projects.isEmpty()) return@LaunchedEffect
            val remembered = preferences.get(projectKey)
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
                        scope.launch { preferences.set(projectKey, project.id) }
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
        WidgetToolBody(
            projects = projects,
            session = session,
            route = route,
            onRetry = { host.retry() },
        )
    }
}

/** Empty, loading, failed, or the tool. */
@Composable
private fun WidgetToolBody(
    projects: List<Project>,
    session: ToolWidgetHost.Session?,
    route: WorkspaceRoute,
    onRetry: () -> Unit,
) {
    when {
        projects.isEmpty() -> ZillitEmptyState(
            title = str(S.desktop_no_projects),
            message = str(S.desktop_device_on_no_project),
            icon = ZillitIcons.Users,
        )

        session == null || session.loading ->
            Box(Modifier.fillMaxSize(), Alignment.Center) { ZillitSpinner() }

        session.error != null -> ZillitErrorState(
            title = str(S.desktop_could_not_open_project),
            message = session.error,
            onRetry = onRetry,
        )

        session.provider != null -> Column(Modifier.fillMaxSize()) {
            if (!session.isOpenProject) ScopedNote(session.project.name)
            Box(Modifier.weight(1f).fillMaxSize()) {
                session.provider.Content(route = route, navigator = WidgetNavigator)
            }
        }
    }
}

/**
 * Names the production a widget is showing when it is not the open one.
 *
 * Messages, files and calls all work here; what does not follow is the unread
 * count, which belongs to the production the main window shows. Saying which
 * production this is matters more than the caveat: two widgets side by side
 * are otherwise identical.
 */
@Composable
private fun ScopedNote(projectName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceRaised)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(
            text = "$projectName — unread counts stay with the project Zillit is open on.",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            maxLines = 2,
        )
    }
}

private val NO_TOOL_SESSION = kotlinx.coroutines.flow.MutableStateFlow<ToolWidgetHost.Session?>(null)

/**
 * The navigator a widget hands its tool.
 *
 * A widget is one window showing one tool: there are no tabs to rename, no
 * history to walk, and nothing to tear off. Every call is a no-op rather than
 * an error because a tool may make them incidentally — the Crew List sets a
 * title on load — and none of them mean anything here.
 */
private object WidgetNavigator : com.zillit.desktop.core.workspace.WindowNavigator {
    override val windowId = com.zillit.desktop.core.workspace.WindowId("widget")
    override val canGoBack: Boolean = false

    override fun navigate(route: WorkspaceRoute) = Unit
    override fun back() = Unit
    override fun openInNewWindow(route: WorkspaceRoute) = Unit
    override fun setTitle(title: String) = Unit
    override fun setDirty(dirty: Boolean) = Unit
    override fun close() = Unit
}
