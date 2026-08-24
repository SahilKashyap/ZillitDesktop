package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute

/**
 * "Zillit Help": the web's help desk (`pages/helpDesk/Help.jsx`) — Terms of
 * Use, Privacy Policy, FAQ, Contact Us, Reviews — as cards that open the
 * browser, and Contact Us that opens a mail to support (the web opens its
 * compose window when the person has a mailbox; here the frame decides).
 *
 * It lives under Settings' path so the rail — which cannot import this
 * module — can name it as a fixed route. *Pin to Start* used to live beside
 * it and is gone: the web page behind it exists to install the web app, and a
 * desktop build is the installed app.
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
    ZillitScrollColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
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

const val HELP_PATH = "/settings/help"
