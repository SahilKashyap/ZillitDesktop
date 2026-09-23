package com.zillit.desktop.feature.email.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitPaneSplitter
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** The nav strip's three views — Email, Calendar, Contacts (`EmailNavStrip.jsx`). */
enum class MailView { Email, Calendar, Contacts }

/**
 * Where the mailbox sends the user for the things that are not mail: the
 * calendar and the address book on the nav strip, the settings entries.
 */
data class MailNavigation(
    val onOpenCalendar: () -> Unit = {},
    val onOpenContacts: () -> Unit = {},
    val onSetting: (MailSettingsEntry) -> Unit = {},
    /** The settings entries this user gets; the sidebar draws them in this order. */
    val settingsEntries: List<MailSettingsEntry> = MailSettingsEntry.entries,
    /** A pending user gets Mail only — no Calendar, no Contacts (ZL-21084). */
    val showsOtherViews: Boolean = true,
)

/**
 * The mailbox: the web's `NewEmailComponent` — the nav strip, the sidebar,
 * the list, and the reading pane that the composer takes over while a
 * message is written. The sidebar and the list are resizable within the
 * web's bounds; the pane owns the rest.
 */
@Composable
fun EmailScreen(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    modifier: Modifier = Modifier,
    hooks: ReadingPaneHooks = ReadingPaneHooks(),
    folderEdit: FolderEdit? = null,
    navigation: MailNavigation = MailNavigation(),
    /** The composer standing in the reading pane, when one is open. */
    inlineCompose: (@Composable () -> Unit)? = null,
) {
    var sidebarWidth by rememberSaveable { mutableStateOf(SIDEBAR_MIN.value) }
    var listWidth by rememberSaveable { mutableStateOf(LIST_MIN.value) }
    val density = LocalDensity.current
    val drag = rememberDragToFolder { rowId, folder -> onEvent(EmailEvent.DropOnFolder(rowId, folder)) }
    // The open folder, Sent and Drafts refuse drops — the web's `isDropAllowed`.
    androidx.compose.runtime.SideEffect {
        drag.refused = setOfNotNull(state.selectedFolderName, "Sent", "Drafts")
    }

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Column(Modifier.fillMaxSize()) {
            SyncStrip(state)
            Row(Modifier.fillMaxSize()) {
                if (navigation.showsOtherViews) NavStrip(navigation)

                MailSidebar(
                    state = state,
                    onEvent = onEvent,
                    onSetting = navigation.onSetting,
                    settingsEntries = navigation.settingsEntries,
                    onFolderBounds = drag::onFolderBounds,
                    dragTarget = drag.target,
                    modifier = Modifier.width(sidebarWidth.dp),
                )
                ZillitPaneSplitter(
                    onDrag = { delta ->
                        sidebarWidth = (sidebarWidth + with(density) { delta.toDp() }.value)
                            .coerceIn(SIDEBAR_MIN.value, SIDEBAR_MAX.value)
                    },
                )

                ListAndPane(
                    state = state,
                    onEvent = onEvent,
                    hooks = hooks,
                    drag = drag,
                    listWidth = listWidth.dp,
                    onListWidth = { listWidth = it.value.coerceIn(LIST_MIN.value, LIST_MAX.value) },
                    inlineCompose = inlineCompose,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        Overlays(state, onEvent, folderEdit)
        DragChip(drag)
    }
}

/**
 * The list beside the pane, divided by a splitter — the width the user
 * dragged to, clamped to what this window can give (see [listPaneWidth]).
 */
@Composable
private fun ListAndPane(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    hooks: ReadingPaneHooks,
    drag: DragToFolder,
    listWidth: Dp,
    onListWidth: (Dp) -> Unit,
    inlineCompose: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val list = listPaneWidth(maxWidth, listWidth)
        Row(Modifier.fillMaxSize()) {
            MailListPane(
                state = state,
                onEvent = onEvent,
                loadAvatar = hooks.loadAvatar,
                drag = drag,
                modifier = Modifier.width(list).fillMaxHeight().testTag(LIST_PANE_TAG),
            )
            ZillitPaneSplitter(
                onDrag = { delta -> onListWidth(list + with(density) { delta.toDp() }) },
                modifier = Modifier.testTag(SPLITTER_TAG),
            )
            Box(Modifier.weight(1f).fillMaxHeight().testTag(PANE_TAG)) {
                if (inlineCompose != null) {
                    inlineCompose()
                } else {
                    ReadingPane(state, onEvent, hooks)
                }
            }
        }
    }
}

/** The dialogs and toasts that stand over the whole mailbox. */
@Composable
private fun BoxScope.Overlays(state: EmailUiState, onEvent: (EmailEvent) -> Unit, folderEdit: FolderEdit?) {
    folderEdit?.let { edit -> FolderDialog(edit = edit, onEvent = onEvent) }

    state.pendingConfirm?.let { pending ->
        ConfirmDialog(
            pending = pending,
            onConfirm = { onEvent(EmailEvent.ConfirmPending) },
            onDismiss = { onEvent(EmailEvent.DismissConfirm) },
        )
    }
    state.info?.let { info -> InfoDialog(info) { onEvent(EmailEvent.DismissInfo) } }
    if (state.conversationDialog) {
        ConversationViewDialog(
            enabled = state.conversationView,
            saving = state.isSavingConversationView,
            onChange = { onEvent(EmailEvent.ConversationViewChanged(it)) },
            onDismiss = { onEvent(EmailEvent.DismissConversationDialog) },
        )
    }
    if (state.tourOpen) MailboxTourDialog { onEvent(EmailEvent.DismissTour) }

    ZillitToast(
        message = state.notice,
        onDismiss = { onEvent(EmailEvent.DismissNotice) },
        tone = ZillitToastTone.Success,
        modifier = Modifier.align(Alignment.BottomCenter).padding(ZillitTheme.spacing.lg),
    )
    ZillitErrorToast(
        message = state.error?.takeIf { state.rows.isNotEmpty() || state.openRowId != null },
        onDismiss = { onEvent(EmailEvent.DismissError) },
        modifier = Modifier.align(Alignment.BottomCenter).padding(ZillitTheme.spacing.lg),
    )
}

/** "Syncing Emails… 45%" across the top while the full pass runs. */
@Composable
private fun SyncStrip(state: EmailUiState) {
    val progress = state.sync ?: return
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.accentSoft)
            .testTag(SYNC_STRIP_TAG),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.desktop_email_syncing_emails),
                style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.accentText,
            )
            if (progress.percent > 0) {
                ZillitText(
                    text = "${progress.percent}%",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.accentText,
                )
            }
        }
        ZillitProgressBar(
            fraction = progress.percent / PERCENT,
            modifier = Modifier.fillMaxWidth(),
            trackColor = Color.Transparent,
        )
    }
}

/**
 * The web's vertical strip on the far left: Email, Calendar, Contacts.
 * Email is always the one lit — the other two open elsewhere.
 */
@Composable
private fun NavStrip(navigation: MailNavigation) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .width(NAV_WIDTH)
            .fillMaxHeight()
            .background(colors.surfaceSunken)
            .padding(vertical = ZillitTheme.spacing.md)
            .testTag(NAV_STRIP_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        NavButton(ZillitIcons.Mail, str(S.email), active = true) {}
        NavButton(ZillitIcons.Calendar, str(S.calendar), active = false, onClick = navigation.onOpenCalendar)
        NavButton(ZillitIcons.Users, str(S.contacts), active = false, onClick = navigation.onOpenContacts)
    }
    Box(Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
}

@Composable
private fun NavButton(icon: ImageVector, label: String, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    ZillitTooltip(label) {
        Box(
            modifier = Modifier
                .size(NAV_BUTTON)
                .clip(ZillitTheme.shapes.medium)
                .background(
                    when {
                        active -> colors.accentSoft
                        hovered -> colors.surfaceHover
                        else -> Color.Transparent
                    },
                )
                .border(
                    1.dp,
                    if (active) colors.accent.copy(alpha = NAV_BORDER_ALPHA) else Color.Transparent,
                    ZillitTheme.shapes.medium,
                )
                .hoverable(interaction)
                .clickable(onClick = onClick)
                .testTag("email-nav-${label.lowercase()}"),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon,
                contentDescription = label,
                tint = if (active) colors.accentText else colors.textSecondary,
                size = NAV_ICON,
            )
        }
    }
}

/**
 * Where the list and the pane divide, for a mailbox [total] wide.
 *
 * The list keeps what it was dragged to within the web's bounds
 * (280–520), and never squeezes the pane below its minimum: on a narrow
 * window the list yields first, down to half the width, because a message
 * body wrapping every line twice is worse than a shorter list.
 */
internal fun listPaneWidth(total: Dp, wanted: Dp): Dp {
    val smallest = LIST_MIN.coerceAtMost(total / 2)
    val largest = (total - PANE_MIN).coerceAtLeast(smallest).coerceAtMost(LIST_MAX)
    return wanted.coerceIn(smallest, largest.coerceAtLeast(smallest))
}

internal const val LIST_PANE_TAG = "email-list-pane"
internal const val SPLITTER_TAG = "email-splitter"
internal const val PANE_TAG = "email-pane"
internal const val SYNC_STRIP_TAG = "email-sync-strip"
internal const val NAV_STRIP_TAG = "email-nav-strip"

internal val SIDEBAR_MIN = 200.dp
internal val SIDEBAR_MAX = 320.dp
internal val LIST_MIN = 280.dp
internal val LIST_MAX = 520.dp
internal val PANE_MIN = 400.dp
private val NAV_WIDTH = 48.dp
private val NAV_BUTTON = 36.dp
private val NAV_ICON = 20.dp
private const val NAV_BORDER_ALPHA = 0.3f
private const val PERCENT = 100f
