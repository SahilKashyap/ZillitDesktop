package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * The two pages at the foot of the web's side menu (`SideMenu.jsx`) that are
 * about the app rather than the production: *Pin to Start* and *Zillit Help*.
 * They live under Settings' path so the rail — which cannot import this
 * module — can name them as fixed routes.
 */

/** Which desktop this is, for the pinning steps; the frame knows, the module does not. */
enum class HostPlatform { MacOs, Windows, Linux }

/**
 * "Pin to Start": on the web an install-the-app page (`PinToStart.jsx` —
 * "Installation Steps … Add to Dock"). A native app is already installed, so
 * this is the equivalent for the desktop it is on: keep it in the Dock /
 * pin it to Start, and open it at sign-in.
 */
class PinToStartToolProvider(private val platform: HostPlatform) : ToolProvider {

    override val path: String = PIN_TO_START_PATH
    override val title: String = "Pin to Start"
    override val icon = ZillitIcons.Pin
    override val defaultSize: DpSize = DpSize(560.dp, 520.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        PinToStartScreen(platform)
    }
}

@Composable
internal fun PinToStartScreen(platform: HostPlatform) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        ZillitPageHeader(
            eyebrow = "Zillit",
            title = "Pin to Start",
            description = "Keep Zillit one click away and have it open when you sign in.",
        )
        val (keep, launch) = pinningSteps(platform)
        StepsCard(title = keep.first, steps = keep.second)
        StepsCard(title = launch.first, steps = launch.second)
    }
}

/** The steps per desktop — the same two outcomes on each, in that desktop's own words. */
internal fun pinningSteps(platform: HostPlatform): Pair<Pair<String, List<String>>, Pair<String, List<String>>> =
    when (platform) {
        HostPlatform.MacOs ->
            ("Keep Zillit in the Dock" to listOf(
                "With Zillit open, find its icon in the Dock.",
                "Right-click (or Control-click) the icon.",
                "Choose Options › Keep in Dock.",
            )) to ("Open Zillit at login" to listOf(
                "Right-click the Zillit icon in the Dock.",
                "Choose Options › Open at Login.",
                "Or: System Settings › General › Login Items › add Zillit.",
            ))

        HostPlatform.Windows ->
            ("Pin Zillit to Start and the taskbar" to listOf(
                "Open the Start menu and find Zillit.",
                "Right-click it and choose Pin to Start.",
                "Right-click again › More › Pin to taskbar for the taskbar too.",
            )) to ("Open Zillit at sign-in" to listOf(
                "Press Win+R, type shell:startup and press Enter.",
                "Right-click Zillit in the Start menu › More › Open file location.",
                "Copy the Zillit shortcut into the Startup folder that opened.",
            ))

        HostPlatform.Linux ->
            ("Add Zillit to your favourites" to listOf(
                "Open your applications menu and find Zillit.",
                "Right-click it and choose Add to Favourites (or Pin to Dash / Panel).",
            )) to ("Open Zillit at login" to listOf(
                "Open your desktop's Startup Applications (or Session and Startup) settings.",
                "Add Zillit to the list.",
            ))
    }

@Composable
private fun StepsCard(title: String, steps: List<String>) {
    ZillitSectionCard(title = title, icon = ZillitIcons.Pin) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    ZillitText(
                        text = "${index + 1}.",
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.accent,
                    )
                    ZillitText(
                        text = step,
                        style = ZillitTheme.typography.bodyMedium,
                        color = ZillitTheme.colors.textPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * "Zillit Help": the web's help desk (`pages/helpDesk/Help.jsx`) — Terms of
 * Use, Privacy Policy, FAQ, Contact Us, Reviews — as cards that open the
 * browser, and Contact Us that opens a mail to support (the web opens its
 * compose window when the person has a mailbox; here the frame decides).
 */
class HelpToolProvider(
    private val onOpenExternal: (String) -> Unit,
    private val onContactSupport: () -> Unit,
) : ToolProvider {

    override val path: String = HELP_PATH
    override val title: String = "Zillit Help"
    override val icon = ZillitIcons.Help
    override val defaultSize: DpSize = DpSize(560.dp, 560.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        HelpScreen(onOpenExternal, onContactSupport)
    }
}

/** One card of the help desk. */
internal data class HelpEntry(val title: String, val blurb: String, val url: String?)

/** The web's five cards and links (`Help.jsx:220-274`), in its order. */
internal val HELP_ENTRIES: List<HelpEntry> = listOf(
    HelpEntry(
        "Terms of Use",
        "The terms you agreed to when you joined Zillit.",
        "https://corporate.zillit.com/terms-of-use",
    ),
    HelpEntry("Privacy Policy", "What Zillit stores, and why.", "https://corporate.zillit.com/privacy-policy"),
    HelpEntry("FAQ", "Answers to the questions people ask most.", "https://zillit.com/frequently-asked-questions"),
    HelpEntry("Contact Us", "Write to support@zillit.com — we read everything.", null),
    HelpEntry("Reviews", "Tell us how Zillit is working for your production.", "https://corporate.zillit.com/d"),
)

@Composable
internal fun HelpScreen(onOpenExternal: (String) -> Unit, onContactSupport: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .verticalScroll(rememberScrollState())
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            eyebrow = "Zillit",
            title = "Zillit Help",
            description = "Get assistance and information about Zillit.",
        )
        HELP_ENTRIES.forEach { entry ->
            ZillitSectionCard(
                title = entry.title,
                icon = ZillitIcons.Help,
                action = {
                    ZillitButton(
                        text = if (entry.url == null) "Write to us" else "Open",
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        onClick = { entry.url?.let(onOpenExternal) ?: onContactSupport() },
                    )
                },
            ) {
                ZillitText(
                    text = entry.blurb,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }
    }
}

const val PIN_TO_START_PATH = "/settings/pin-to-start"
const val HELP_PATH = "/settings/help"
