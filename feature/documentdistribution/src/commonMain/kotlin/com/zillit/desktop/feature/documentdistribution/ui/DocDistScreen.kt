package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.permissions.RightsKind
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitErrorState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.documentdistribution.ui.pages.AddressBookPage
import com.zillit.desktop.feature.documentdistribution.ui.pages.ComposerDialog
import com.zillit.desktop.feature.documentdistribution.ui.pages.DocDistPromptDialog
import com.zillit.desktop.feature.documentdistribution.ui.pages.HistoryPage
import com.zillit.desktop.feature.documentdistribution.ui.pages.LibraryPage
import com.zillit.desktop.feature.documentdistribution.ui.pages.ListsPage
import com.zillit.desktop.feature.documentdistribution.ui.pages.TemplateEditorDialog
import com.zillit.desktop.feature.documentdistribution.ui.pages.TemplatesPage
import com.zillit.desktop.feature.documentdistribution.ui.pages.WatermarkSettingsDialog

/**
 * The Document Distribution tool.
 *
 * ## Chrome, then one page
 *
 * The frame is constant — a header and the tab strip — and the body is
 * whichever [DocDistDestination] is open. Keeping the chrome outside the page
 * is what lets someone move between the library and History without the window
 * appearing to reload.
 *
 * ## Every tab here is one this viewer may use
 *
 * The tab list comes from [DocDistUiState.destinations], which filters by
 * rights, so there is no path to a "you do not have access" page *inside* the
 * tool. The one exception is the whole-tool denial below, which has to exist
 * because rights can be withdrawn while the window is open.
 */
@Composable
fun DocDistScreen(
    state: DocDistUiState,
    onEvent: (DocDistEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (state.viewer.isBlocked) {
            ZillitEmptyState(
                title = str(S.desktop_docdist_no_access_title),
                message = str(S.desktop_docdist_no_access_message),
                icon = ZillitIcons.Shield,
            )
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            DocDistHeader(state, onEvent)
            ZillitDivider()
            Box(modifier = Modifier.fillMaxSize()) {
                DocDistBody(state, onEvent)
            }
        }

        // Dialogs any page can raise: the composer is opened from the
        // address book and the lists too, and its "save as template" from
        // wherever it sits.
        ComposerDialog(state, onEvent)
        TemplateEditorDialog(state, onEvent)
        WatermarkSettingsDialog(state, onEvent)
        DocDistPromptDialog(state.prompt, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(DocDistEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

/**
 * The project-wide defaults every send starts from — the web's toolbar
 * button, beside the other tool-wide actions. Saving them is a posting
 * action, so readers never see the entry point (web 303a9fe28); there is
 * nothing in there for them.
 */
@Composable
private fun WatermarkSettingsButton(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    if (!state.viewer.canPost) return
    ZillitButton(
        text = str(S.dd_action_watermark_settings),
        onClick = { onEvent(DocDistEvent.OpenWatermarkSettings) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Shield,
    )
}

// The badge reader takes its units as a vararg, and a destination names at
// most two of them, so the copy the spread makes is a two-element array.
@Suppress("SpreadOperator")
@Composable
private fun DocDistHeader(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitPageHeader(
            eyebrow = str(S.cs_projects),
            title = str(S.dd_title),
            description = str(S.desktop_docdist_description),
            actions = {
                WatermarkSettingsButton(state, onEvent)
                ZillitButton(
                    text = str(S.dd_action_refresh),
                    onClick = { onEvent(DocDistEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Reload,
                    loading = state.loading,
                )
            },
        )

        ZillitTabStrip(
            tabs = state.destinations.map {
                ZillitTab(it.slug, it.label, count = state.unread.unit(*it.badgeUnits.toTypedArray()))
            },
            activeId = state.destination.slug,
            onSelect = { slug ->
                DocDistDestination.fromSlug(slug)?.let { onEvent(DocDistEvent.Open(it)) }
            },
            size = TabStripSize.Primary,
        )

        // Shown rather than silently disabling the buttons: a coordinator whose
        // Send button simply is not there has no way to learn that it is a
        // rights question and not a bug, and the web shows the same banner with
        // the same "ask for access" affordance.
        if (state.showRestrictionBanner) {
            ZillitNotice(
                text = restrictionText(state),
                tone = StatusTone.Pending,
                icon = ZillitIcons.Info,
                // The banner named what was missing and left the reader with
                // nowhere to go. The phones put the ask on exactly this
                // notice — one press, for the right they are short of.
                action = {
                    ZillitButton(
                        text = str(S.desktop_request_access),
                        variant = ButtonVariant.Tertiary,
                        size = ButtonSize.Small,
                        onClick = {
                            onEvent(
                                DocDistEvent.RequestRights(
                                    if (!state.viewer.canPost) RightsKind.Post else RightsKind.Download,
                                ),
                            )
                        },
                    )
                },
            )
        }
    }
}

private fun restrictionText(state: DocDistUiState): String = when {
    !state.viewer.canPost && !state.viewer.canDownload -> str(S.desktop_docdist_view_only_notice)

    !state.viewer.canPost -> str(S.desktop_docdist_download_only_notice)

    else -> str(S.desktop_docdist_send_only_notice)
}

@Composable
private fun DocDistBody(state: DocDistUiState, onEvent: (DocDistEvent) -> Unit) {
    val failure = state.error
    if (failure != null && !state.loading) {
        ZillitErrorState(message = failure, onRetry = { onEvent(DocDistEvent.Refresh) })
        return
    }

    when (state.destination) {
        DocDistDestination.Library -> LibraryPage(state, onEvent)
        DocDistDestination.History -> HistoryPage(state, onEvent)
        DocDistDestination.Lists -> ListsPage(state, onEvent)
        DocDistDestination.AddressBook -> AddressBookPage(state, onEvent)
        DocDistDestination.Templates -> TemplatesPage(state, onEvent)
    }
}
