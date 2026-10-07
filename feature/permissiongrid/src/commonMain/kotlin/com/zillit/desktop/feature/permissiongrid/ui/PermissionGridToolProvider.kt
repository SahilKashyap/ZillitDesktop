package com.zillit.desktop.feature.permissiongrid.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.permissiongrid.domain.PermissionGridViewer

const val PERMISSION_GRID_PATH = "/film-tools/permission-grid"

/** The read-only Default Grid, a page of this window — the web's `/defaultpermisssiongrid`. */
const val DEFAULT_GRID_PATH = "$PERMISSION_GRID_PATH/default"

/**
 * The rights grid as a workspace window — the one Film Tools opens and Admin
 * Settings opens, as both web entry points navigate to the same route.
 *
 * [viewer] is a lambda, not a value: the registry is built once per graph,
 * before any production is open, so the rights have to be resolved when the
 * window is first shown rather than captured at construction.
 *
 * @param onSaveFile puts the Default Grid's workbook on disk.
 * @param onOpenListingOrder the crew-list banner's "Click Here" — the
 *   department listing order, which belongs to another window. Null hides the
 *   link.
 */
class PermissionGridToolProvider(
    private val viewModel: PermissionGridViewModel,
    private val viewer: () -> PermissionGridViewer,
    private val onSaveFile: (fileName: String, bytes: ByteArray) -> Unit = { _, _ -> },
    private val onOpenListingOrder: ((WindowNavigator) -> Unit)? = null,
) : ToolProvider {

    override val path: String = PERMISSION_GRID_PATH
    override val title: String get() = str(S.desktop_pg_title)
    override val icon = ZillitToolIcons.PostingRights
    override val defaultSize: DpSize = DpSize(1280.dp, 800.dp)

    /** The grid and its Default Grid share the window and its history. */
    override val hostsOwnRoutes: Boolean = true

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val onDefault = route.path.startsWith(DEFAULT_GRID_PATH)

        LaunchedEffect(Unit) {
            viewModel.onEvent(PermissionGridEvent.Start(viewer()))
        }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is PermissionGridEffect.SaveFile -> onSaveFile(effect.fileName, effect.bytes)
                }
            }
        }
        LaunchedEffect(onDefault) {
            if (onDefault) viewModel.onEvent(PermissionGridEvent.Defaults.Open)
        }

        if (onDefault) {
            DefaultGridScreen(
                state = state,
                onEvent = viewModel::onEvent,
                onBack = {
                    if (navigator.canGoBack) navigator.back()
                    else navigator.navigate(WorkspaceRoute.Tool(PERMISSION_GRID_PATH))
                },
            )
        } else {
            PermissionGridScreen(
                state = state,
                onEvent = viewModel::onEvent,
                onViewDefaultGrid = { navigator.navigate(WorkspaceRoute.Tool(DEFAULT_GRID_PATH)) },
                onOpenListingOrder = onOpenListingOrder?.let { open -> { open(navigator) } },
            )
        }
    }
}
