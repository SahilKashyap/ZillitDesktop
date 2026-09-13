package com.zillit.desktop.feature.maps.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitColors
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.copyTextToClipboard
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.maps.domain.CanvasTheme
import com.zillit.desktop.feature.maps.ui.screen.LocalMapImages
import com.zillit.desktop.feature.maps.ui.screen.MapImages
import com.zillit.desktop.feature.maps.ui.screen.MapToast

/** The map tool as a workspace tool, at the web's path (`/film-tools/map`). */
class MapToolProvider(
    private val viewModel: MapViewModel,
    /** Opens a maps URL in the system browser (https only). */
    private val onOpenUrl: (String) -> Unit,
    /**
     * The host's map surface, told whether it may show. Null keeps the tool
     * without a map (no embedded browser).
     */
    private val canvas: (@Composable (visible: Boolean) -> Unit)? = null,
    /** Stored photos and zone previews; none shows placeholders. */
    private val images: MapImages = MapImages.None,
) : ToolProvider {

    override val path: String = MAP_PATH
    override val title: String = "Map"
    override val icon = ZillitToolIcons.Location
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1280.dp, 860.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        val toasts = remember { mutableStateListOf<MapToast>() }
        val colors = ZillitTheme.colors

        LaunchedEffect(viewModel) { viewModel.start() }
        // The page draws its own cards; they wear the app's colours.
        LaunchedEffect(colors.isDark, colors.surface) { viewModel.useTheme(colors.canvasTheme()) }
        LaunchedEffect(viewModel) {
            var next = 0L
            viewModel.effects.collect { effect ->
                when (effect) {
                    is MapEffect.Notice -> if (effect.message.isNotBlank()) {
                        toasts += MapToast(next++, effect.message, effect.tone)
                        while (toasts.size > MAX_TOASTS) toasts.removeAt(0)
                    }
                    is MapEffect.OpenUrl -> onOpenUrl(effect.url)
                    is MapEffect.Copy -> copyTextToClipboard(effect.text)
                    MapEffect.Leave -> if (navigator.canGoBack) navigator.back() else navigator.close()
                    is MapEffect.OpenTool -> navigator.openInNewWindow(WorkspaceRoute.Tool(effect.path))
                }
            }
        }

        CompositionLocalProvider(LocalMapImages provides images) {
            MapScreen(
                state = state,
                onEvent = viewModel::onEvent,
                canvas = canvas,
                toasts = toasts,
                onDismissToast = { id -> toasts.removeAll { it.id == id } },
            )
        }
    }
}

private fun ZillitColors.canvasTheme() = CanvasTheme(
    surface = surfaceRaised.hex(),
    text = textPrimary.hex(),
    textSecondary = textSecondary.hex(),
    textMuted = textMuted.hex(),
    border = border.hex(),
    accent = accent.hex(),
    isDark = isDark,
)

private fun Color.hex(): String = "#" + (toArgb() and RGB_MASK).toString(HEX_RADIX).padStart(RGB_DIGITS, '0')

private const val MAX_TOASTS = 3
private const val RGB_MASK = 0xFFFFFF
private const val HEX_RADIX = 16
private const val RGB_DIGITS = 6

const val MAP_PATH = "/film-tools/map"
