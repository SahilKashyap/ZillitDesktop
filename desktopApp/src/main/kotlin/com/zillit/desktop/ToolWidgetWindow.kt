package com.zillit.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ApplicationScope
import com.zillit.desktop.core.datastore.PreferenceStore
import com.zillit.desktop.core.datastore.WidgetKeys
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowId
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.auth.ui.AuthViewModel

/**
 * A widget onto one of the app's own tools — Chat & Calls, the Crew List.
 *
 * Unlike the Drive widget, these show the production the app is **open on**,
 * and they show it through the very [ToolProvider] the rail uses, holding the
 * same ViewModel. That is the whole point: the widget's chat is not a second
 * chat. One socket, one set of unread badges, one call in flight — a message
 * read in the widget is read in the tool, because there is only one of each.
 *
 * A production picker like the Drive widget's would need a second socket and a
 * second badge ledger, which is a different feature; switching productions
 * stays the main window's job, and this follows it.
 */
@Composable
@Suppress("LongParameterList") // Every seam is a distinct host concern; a holder object would just rename them.
internal fun ApplicationScope.ToolWidgetWindow(
    title: String,
    /** Names the widget in the signed-out sentence — "The Chat widget". */
    what: String,
    keys: WidgetKeys,
    provider: ToolProvider?,
    route: WorkspaceRoute,
    auth: AuthViewModel?,
    preferences: PreferenceStore,
    visible: Boolean,
    darkTheme: Boolean,
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
        onClose = onClose,
    ) { chrome ->
        val authState = auth?.state?.collectAsState()?.value
        val signedIn = authState?.step?.isSignedIn == true

        // Signed out while the widget is up: nothing here works any more, and
        // the main window is where the QR code is.
        LaunchedEffect(signedIn) { if (!signedIn) showMain() }

        Column(Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
            if (provider == null || !signedIn) {
                WidgetSignedOut(what = what, onOpenZillit = showMain)
                return@Column
            }

            WidgetBar(chrome = chrome, onOpenZillit = showMain) {
                ZillitText(
                    text = provider.title,
                    style = ZillitTheme.typography.titleSmall,
                    color = ZillitTheme.colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            ZillitDivider()
            Box(Modifier.weight(1f).fillMaxSize()) {
                provider.Content(route = route, navigator = WidgetNavigator)
            }
        }
    }
}

/**
 * The navigator a widget hands its tool.
 *
 * A widget is one window showing one tool: there are no tabs to rename, no
 * history to walk, and nothing to tear off. Every call is a no-op rather than
 * an error because a tool may make them incidentally — the Crew List sets a
 * title on load — and none of them mean anything here.
 */
private object WidgetNavigator : WindowNavigator {
    override val windowId: WindowId = WindowId("widget")
    override val canGoBack: Boolean = false

    override fun navigate(route: WorkspaceRoute) = Unit
    override fun back() = Unit
    override fun openInNewWindow(route: WorkspaceRoute) = Unit
    override fun setTitle(title: String) = Unit
    override fun setDirty(dirty: Boolean) = Unit
    override fun close() = Unit
}
