package com.zillit.desktop.feature.notifications.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.notifications.domain.NotificationTarget

/**
 * The notification list as a workspace tool.
 *
 * The web mounts the same drawer at `/DrawerNotification` (`toolRegistry.js:210`);
 * [NOTIFICATIONS_PATH] is the desktop's plainer spelling, and [path] is a
 * parameter so the host can register it under the web's key too.
 */
class NotificationsToolProvider(
    private val viewModel: NotificationsViewModel,
    override val path: String = NOTIFICATIONS_PATH,
    /**
     * Where a clicked row goes. Null keeps Android's behaviour — a tap does
     * nothing. A host that maps `section`/`tool`/`action` to a route the way
     * the web's `notificationRouteMatcher` does passes it here.
     */
    private val onOpen: ((NotificationTarget) -> Unit)? = null,
    /**
     * The unread count for the tab badge, read at composition. The list's own
     * payload carries no total, so this comes from wherever the host keeps
     * the `global_label` count — the badge store — and null shows no badge.
     */
    private val unreadCount: @Composable () -> Int? = { null },
) : ToolProvider {

    override val title: String get() = str(S.notifications)
    override val icon = ZillitIcons.Bell
    override val openMode: OpenMode = OpenMode.Window
    override val defaultSize: DpSize = DpSize(720.dp, 760.dp)

    @Composable
    override fun badge(): Int? = unreadCount()

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is NotificationsEffect.Notice -> notice = effect.message
                    is NotificationsEffect.Open -> onOpen?.invoke(effect.target)
                }
            }
        }

        NotificationsScreen(state = state, onEvent = viewModel::onEvent)
        ZillitToast(message = notice, onDismiss = { notice = null }, tone = ZillitToastTone.Success)
    }
}

const val NOTIFICATIONS_PATH = "/notifications"
