package com.zillit.desktop.feature.externalusers.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * External Users as a workspace tool, at the catalogue's route. The web's own
 * path is `/film-tools/otheruser`; the desktop keeps the catalogue spelling —
 * routes are this app's vocabulary, not the wire's.
 */
class ExternalUsersToolProvider(
    private val viewModel: ExternalUsersViewModel,
) : ToolProvider {

    override val path: String = EXTERNAL_USERS_PATH
    override val title: String = "External Users"
    override val icon = ZillitToolIcons.IcInviteUser
    override val openMode: OpenMode = OpenMode.Window
    override val defaultSize: DpSize = DpSize(1150.dp, 740.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var notice by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is ExternalUsersEffect.Notice -> notice = effect.text
                }
            }
        }

        ExternalUsersScreen(state = state, onEvent = viewModel::onEvent)
        ZillitErrorToast(message = notice ?: state.error, onDismiss = {
            notice = null
            viewModel.onEvent(ExternalUsersEvent.DismissError)
        })
    }

    companion object {
        const val EXTERNAL_USERS_PATH = "/film-tools/external-users"
    }
}
