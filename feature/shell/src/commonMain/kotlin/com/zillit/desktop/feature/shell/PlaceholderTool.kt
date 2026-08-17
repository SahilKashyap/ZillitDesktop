package com.zillit.desktop.feature.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * Stand-in providers for the rail destinations whose feature modules have not
 * been built yet (M4 onwards).
 *
 * They are not decorative: each keeps per-window state, so opening several,
 * typing in them and switching between tabs exercises the state-retention
 * contract in `WorkspaceHost`. That is the behaviour most likely to regress as
 * real features land, and it is easiest to notice while it is trivial to check.
 *
 * Delete each one as its real module arrives.
 */
class PlaceholderTool(
    override val path: String,
    override val title: String,
    override val icon: ImageVector,
    override val openMode: OpenMode = OpenMode.Maximized,
) : ToolProvider {

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        // `rememberSaveable`, not `remember`: WorkspaceHost disposes an
        // inactive window's composition, and only saveable state survives that
        // (see the contract on WorkspaceHost). This is the behaviour the
        // placeholder exists to keep honest.
        var interactions by rememberSaveable { mutableStateOf(0) }

        Column(
            modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xxl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md, Alignment.CenterVertically),
        ) {
            ZillitText(text = title, style = ZillitTheme.typography.titleLarge)
            ZillitText(
                text = "This module arrives later in the roadmap.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textSecondary,
            )
            ZillitText(
                text = "Window state kept across tab switches: $interactions",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitButton(
                text = "Interact",
                onClick = { interactions++ },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = if (navigator.canGoBack) "Back" else "Open a sub-page",
                onClick = {
                    if (navigator.canGoBack) {
                        navigator.back()
                    } else {
                        navigator.navigate(WorkspaceRoute.Tool("$path/detail"))
                    }
                },
                variant = ButtonVariant.Tertiary,
            )
            ZillitText(
                text = route.path,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    }
}

/** Providers matching [DefaultRailItems], so every rail entry opens something. */
fun placeholderTools(): List<ToolProvider> = listOf(
    PlaceholderTool("/home", "Home", ZillitIcons.Home),
    PlaceholderTool("/cnc", "Chat & Calls", ZillitIcons.Chat),
    PlaceholderTool("/email", "Email", ZillitIcons.Mail),
    PlaceholderTool("/calendar", "Calendar", ZillitIcons.Calendar),
    PlaceholderTool("/film-tools", "Film Tools", ZillitIcons.Tools),
    PlaceholderTool("/drive", "Drive", ZillitIcons.Drive),
    PlaceholderTool("/transportation", "Transport", ZillitIcons.Transport),
    // Settings is real now; this stays only for a build where the feature
    // module is absent, and the real provider replaces it when present.
    PlaceholderTool("/settings", "Settings", ZillitIcons.Settings),
)
