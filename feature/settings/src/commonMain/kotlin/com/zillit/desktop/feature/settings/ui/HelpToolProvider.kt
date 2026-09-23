package com.zillit.desktop.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import kotlinx.coroutines.launch
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
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
 * "Zillit Help": the phones' Zillit Guide — FAQs, Privacy Policy, Terms of Use
 * and Contact Us — as cards that open the browser, plus a Contact Us that both
 * rings the 24x7 support team and opens a mail to support.
 *
 * The list and its order are iOS's (`ZillitGuideViewController.swift:12-22`),
 * and so are the link targets, which differ from the web client's shorter
 * slugs this screen used to carry.
 *
 * Two of the phones' rows are deliberately absent. *Reviews* is commented out
 * in iOS's own list. *Tutorial for Admin* and *Tutorial for User* are not
 * links at all — they dismiss to the dashboard and start a coach-mark tour of
 * the iOS tab bar, which has no desktop equivalent; a browser row in their
 * place would invent a destination neither client has.
 *
 * It lives under Settings' path so the rail — which cannot import this
 * module — can name it as a fixed route. *Pin to Start* used to live beside
 * it and is gone: the web page behind it exists to install the web app, and a
 * desktop build is the installed app.
 */
class HelpToolProvider(
    private val onOpenExternal: (String) -> Unit,
    /**
     * Opens a mail to support. Returns why it could not, or null when it did.
     *
     * A return value rather than a bare callback because there is no other
     * way for this screen to know: a machine with no mail client set up takes
     * the request and does nothing with it, and a button that silently does
     * nothing is indistinguishable from a broken one.
     */
    private val onContactSupport: suspend () -> String?,
    /**
     * Rings the 24x7 support team, answering why it could not.
     *
     * Suspending because it has to find this account's primary device first —
     * that is a request, and doing it when the button is built rather than
     * when it is pressed would hide the button from anyone whose profile had
     * not loaded yet. Null only where calling is unavailable at all.
     */
    private val onCallSupport: (suspend () -> String?)? = null,
    /**
     * Where writing to support goes, once [onContactSupport] has queued it.
     *
     * A route rather than a call into mail: this module knows nothing about
     * the mailbox, and the frame owns which window that is.
     */
    private val supportComposeRoute: String? = null,
) : ToolProvider {

    override val path: String = HELP_PATH
    override val title: String get() = str(S.zillit_help)
    override val icon = ZillitIcons.Help
    override val defaultSize: DpSize = DpSize(560.dp, 560.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        var notice by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        HelpScreen(
            onOpenExternal = onOpenExternal,
            // Both run off the click: asking the OS to open a mail means
            // waiting on a process, and blocking the frame to do it would
            // freeze the window for as long as the mail app takes to wake up.
            onContactSupport = {
                scope.launch {
                    notice = onContactSupport()
                    // Only on success: a failure has a message to read, and
                    // moving the window out from under it would hide it.
                    if (notice == null) {
                        supportComposeRoute?.let { navigator.navigate(WorkspaceRoute.Tool(it)) }
                    }
                }.let { }
            },
            onCallSupport = onCallSupport?.let { call ->
                { scope.launch { notice = call() }.let { } }
            },
        )
        ZillitErrorToast(message = notice, onDismiss = { notice = null })
    }
}

/** One card of the help desk. */
internal data class HelpEntry(
    private val titleKey: String,
    private val blurbKey: String,
    val url: String?,
    /** This row can also ring the support team, not only write to them. */
    val callable: Boolean = false,
) {
    val title: String get() = str(titleKey)
    val blurb: String get() = str(blurbKey)
}

/** The phones' four cards and links, in their order. See the class comment. */
internal val HELP_ENTRIES: List<HelpEntry> = listOf(
    HelpEntry(
        S.desktop_faqs,
        S.desktop_help_faqs_blurb,
        "https://corporate.zillit.com/frequently-asked-questions-for-zillit-application-and-web-platform",
    ),
    HelpEntry(
        S.privacy,
        S.desktop_help_privacy_blurb,
        "https://corporate.zillit.com/privacy-policy-for-zillit-application-and-web-platform",
    ),
    HelpEntry(
        S.txt_help_condition,
        S.desktop_help_terms_blurb,
        "https://corporate.zillit.com/terms-conditions-for-zillit-application-and-web-platform",
    ),
    HelpEntry(
        S.txt_help_contact,
        S.desktop_help_contact_blurb,
        null,
        callable = true,
    ),
)

@Composable
internal fun HelpScreen(
    onOpenExternal: (String) -> Unit,
    onContactSupport: () -> Unit,
    onCallSupport: (() -> Unit)? = null,
) {
    ZillitScrollColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas),
        contentPadding = PaddingValues(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            eyebrow = str(S.app_name),
            title = str(S.zillit_help),
            description = str(S.desktop_help_description),
        )
        HELP_ENTRIES.forEach { entry ->
            ZillitSectionCard(
                title = entry.title,
                icon = ZillitIcons.Help,
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        if (entry.callable && onCallSupport != null) {
                            ZillitButton(
                                text = str(S.txt_call_us),
                                variant = ButtonVariant.Primary,
                                size = ButtonSize.Small,
                                onClick = onCallSupport,
                            )
                        }
                        ZillitButton(
                            text = if (entry.url == null) str(S.desktop_write_to_us) else str(S.recce_open),
                            variant = ButtonVariant.Secondary,
                            size = ButtonSize.Small,
                            onClick = { entry.url?.let(onOpenExternal) ?: onContactSupport() },
                        )
                    }
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
