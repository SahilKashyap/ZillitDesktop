package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The Zillit Tour — the guided web tour of the app, opened from Zillit Help.
 *
 * All three clients open the same hosted page; what they disagree about is
 * where. The web has nowhere else to put it and opens a browser tab, but both
 * phones host it **inside the app** — Android in a `WebViewActivity`
 * (`HelpActivity.kt:272`, with `openBrowser(CORPORATE_TOUR_URL)` left
 * commented out beside it) and iOS in its own web view
 * (`ZillitTour.open(from:)`, "in the in-app web view, like the guide's other
 * pages"). So the tour is a window of this app, carrying embedded Chromium,
 * rather than a hand-off to Safari.
 *
 * Full-bleed and [hostsOwnRoutes]: the tour is a self-contained application
 * with its own navigation, and a page header above it would be a second
 * heading for one page. The way out is this window's own close.
 *
 * The browser is [tour]'s to own — a heavyweight AWT surface this module has
 * no way to build — and is injected, as Budget Builder's application is.
 */
class ZillitTourToolProvider(
    private val tour: @Composable () -> Unit,
) : ToolProvider {

    override val path: String = ZILLIT_TOUR_PATH
    override val title: String get() = str(S.desktop_zillit_tour)
    override val icon = ZillitIcons.Globe
    override val openMode: OpenMode = OpenMode.Window
    override val defaultSize: DpSize = DpSize(WINDOW_WIDTH.dp, WINDOW_HEIGHT.dp)
    override val hostsOwnRoutes: Boolean = true

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        Box(modifier = Modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) { tour() }
    }

    private companion object {
        /** Wide enough for the tour's own layout, which is built for a browser. */
        const val WINDOW_WIDTH = 1180
        const val WINDOW_HEIGHT = 820
    }
}

/**
 * What the tour window shows when there is no embedded browser to show it in.
 *
 * Here rather than beside the Chromium host so it renders — and is
 * screenshot-tested — without one. The button is the phones' commented-out
 * line brought back as the fallback it always was: the tour is a public page,
 * and a browser is the one thing every machine has.
 */
@Composable
fun ZillitTourUnavailable(reason: String, onOpenInBrowser: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitText(
            text = reason,
            style = ZillitTheme.typography.bodyMedium,
            color = ZillitTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        ZillitButton(
            text = str(S.desktop_drive_open_in_browser),
            variant = ButtonVariant.Primary,
            onClick = onOpenInBrowser,
        )
    }
}

const val ZILLIT_TOUR_PATH = "/settings/help/tour"

/**
 * The hosted tour, at the one address all three clients carry: Android's
 * `Constants.CORPORATE_TOUR_URL`, iOS's `ZillitTour.url`, and the web's
 * sixth help card.
 */
const val ZILLIT_TOUR_URL = "https://zillit-tour.zillit.com/"
