package com.zillit.desktop.feature.email.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.email.domain.ComposeMode
import com.zillit.desktop.feature.email.domain.EmailFolder
import com.zillit.desktop.feature.email.domain.MailboxKind
import com.zillit.desktop.feature.email.domain.isDeletable

/**
 * The web's Settings popover, item for item (`NewEmailSidebar.jsx`
 * `settings`). Each opens a page of the Email Settings window, a dialog
 * over the mailbox, or the tour; the host decides what an entry opens.
 */
enum class MailSettingsEntry(private val labelKey: String) {
    Signatures(S.signatures),
    ConversationView(S.email_trailing),
    ImportContacts(S.desktop_email_import_contacts),
    BccPresets(S.bbc_presets),
    EmailGroup(S.create_email_group),
    Credentials(S.email_credentials),
    Forwarding(S.desktop_email_forwarding),
    Rules(S.email_rules_title),
    MailboxTour(S.desktop_email_mailbox_tour),
    ;

    val label: String get() = str(labelKey)
}

/**
 * The sidebar: the mailbox switcher for Accounts-department users, the one
 * filled button, the five system folders, the user's own folders under their
 * heading, and Settings directly beneath — the web's `NewEmailSidebar`,
 * including the two rules it documents: Settings follows the folders rather
 * than sitting at the foot of a tall panel, and every custom folder renders
 * (the list scrolls, nothing is capped).
 */
@Composable
internal fun MailSidebar(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    onSetting: (MailSettingsEntry) -> Unit,
    /** Which settings entries this user gets (admin-only groups, no credentials for the shared mailbox). */
    settingsEntries: List<MailSettingsEntry>,
    /** Where each folder row sits, for a drag to find the folder under the pointer. */
    onFolderBounds: (folderName: String, bounds: FolderBounds?) -> Unit = { _, _ -> },
    /** The folder a dragged row is hovering, drawn as the drop target. */
    dragTarget: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(ZillitTheme.colors.surfaceSunken)
            .testTag(SIDEBAR_TAG),
    ) {
        if (state.mailboxes.hasAccounts) {
            MailboxSwitcher(state.mailboxes, enabled = state.sync == null || state.messages.isNotEmpty()) {
                onEvent(EmailEvent.SwitchMailbox(it))
            }
        }

        ZillitButton(
            text = str(S.desktop_email_new_email),
            leadingIcon = ZillitIcons.Edit,
            onClick = { onEvent(EmailEvent.Compose(ComposeMode.New)) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.md)
                .padding(top = ZillitTheme.spacing.lg, bottom = ZillitTheme.spacing.sm)
                .testTag(NEW_EMAIL_TAG),
        )

        // The folders take what they need and no more (the web's `min-h-0`,
        // `0 1 auto` block): Settings sits directly under the last one, and
        // the empty space falls below it. The custom list alone scrolls, and
        // it stands directly in this column — nested in one of its own it
        // would stretch to the foot and take Settings with it.
        FolderLists(state, onEvent, onFolderBounds, dragTarget)

        SettingsRow(settingsEntries, onSetting)
    }
}

/** The system folders, then the FOLDERS heading and the custom ones under it. */
@Composable
private fun ColumnScope.FolderLists(
    state: EmailUiState,
    onEvent: (EmailEvent) -> Unit,
    onFolderBounds: (folderName: String, bounds: FolderBounds?) -> Unit,
    dragTarget: String?,
) {
    var customExpanded by rememberSaveable { mutableStateOf(true) }
    val (system, custom) = state.sidebar.partition { it.isSystem }

    if (state.isLoadingFolders && state.folders.isEmpty()) {
        ZillitText(
            text = str(S.ah_loading),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(ZillitTheme.spacing.md),
        )
    }
    Column(
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        system.forEach { folder ->
            FolderRow(state, folder, onEvent, nested = false, onFolderBounds, dragTarget)
        }
    }

    FoldersHeading(
        hasFolders = custom.isNotEmpty(),
        expanded = customExpanded,
        onToggle = { customExpanded = !customExpanded },
        onCreate = { onEvent(EmailEvent.EditFolder()) },
    )
    if (custom.isNotEmpty() && customExpanded) {
        ZillitScrollColumn(
            modifier = Modifier.weight(1f, fill = false).padding(horizontal = ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            custom.forEach { folder ->
                FolderRow(state, folder, onEvent, nested = true, onFolderBounds, dragTarget)
            }
        }
    }
}

/** "FOLDERS ▾  +" — or the create-a-folder invitation when there are none. */
@Composable
private fun FoldersHeading(hasFolders: Boolean, expanded: Boolean, onToggle: () -> Unit, onCreate: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg)
            .padding(top = ZillitTheme.spacing.lg, bottom = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasFolders) {
            Row(
                modifier = Modifier.weight(1f).clip(ZillitTheme.shapes.small).clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitIcon(
                    icon = if (expanded) ZillitIcons.ChevronDown else ZillitIcons.ChevronRight,
                    contentDescription = null,
                    tint = colors.textMuted,
                    size = HEADING_CHEVRON,
                )
                ZillitText(
                    text = str(S.folders).uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = HEADING_TRACKING,
                    ),
                    color = colors.textMuted,
                )
            }
        } else {
            Row(
                modifier = Modifier.weight(1f).clip(ZillitTheme.shapes.small).clickable(onClick = onCreate),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                ZillitIcon(ZillitIcons.Folder, contentDescription = null, tint = colors.textMuted, size = FOLDER_ICON)
                ZillitText(text = str(S.folders), style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.Add,
            contentDescription = str(S.create_folder),
            onClick = onCreate,
            tint = colors.textMuted,
            size = HEADING_BUTTON,
            modifier = Modifier.testTag(CREATE_FOLDER_TAG),
        )
    }
}

/**
 * The count on a folder's row: the badge ledger's when it has one, else the
 * server's `unread_count`. The ledger keys folders by lower-cased name
 * (iOS's `unreadEmailCountsByFolder`); match the same way.
 */
private fun EmailUiState.unreadBadge(folder: EmailFolder): Int =
    folderBadges.takeIf { it.isNotEmpty() }
        ?.let { split -> split[folder.name] ?: split[folder.name.lowercase()] ?: 0 }
        ?: folder.unreadCount

@Composable
@Suppress("LongParameterList")
private fun FolderRow(
    state: EmailUiState,
    folder: EmailFolder,
    onEvent: (EmailEvent) -> Unit,
    nested: Boolean,
    onFolderBounds: (String, FolderBounds?) -> Unit,
    dragTarget: String?,
) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val isActive = folder.name == state.selectedFolder?.name
    val isDropTarget = dragTarget != null && dragTarget == folder.name
    val badge = state.unreadBadge(folder)
    val background by animateColorAsState(
        when {
            isActive || isDropTarget -> colors.accentSoft
            hovered -> colors.surfaceHover
            else -> Color.Transparent
        },
        label = "folderRow",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                val origin = coords.positionInRoot()
                onFolderBounds(
                    folder.name,
                    FolderBounds(origin.x, origin.y, origin.x + coords.size.width, origin.y + coords.size.height),
                )
            }
            .clip(ZillitTheme.shapes.large)
            .background(background)
            .border(
                width = 1.dp,
                color = if (isActive) colors.accent.copy(alpha = ACTIVE_BORDER_ALPHA) else Color.Transparent,
                shape = ZillitTheme.shapes.large,
            )
            .hoverable(interaction)
            .clickable { onEvent(EmailEvent.SelectFolder(folder.name)) }
            .padding(start = if (nested) NESTED_INSET else ZillitTheme.spacing.md, end = ZillitTheme.spacing.sm)
            .height(if (nested) NESTED_ROW_HEIGHT else ROW_HEIGHT)
            .testTag("folder-${folder.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitIcon(
            icon = folder.icon(),
            contentDescription = null,
            tint = if (isActive) colors.accentText else colors.textMuted,
            size = FOLDER_ICON,
        )
        ZillitText(
            text = folder.displayName,
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
            ),
            color = if (isActive) colors.accentText else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        ZillitBadge(count = badge, background = colors.accent, contentColor = colors.textOnAccent)
        if (folder.isDeletable) FolderOptions(folder, revealed = hovered, onEvent)
    }
}

/** The three dots on a custom folder — Edit and Delete — faint until the row is hovered. */
@Composable
private fun FolderOptions(folder: EmailFolder, revealed: Boolean, onEvent: (EmailEvent) -> Unit) {
    val colors = ZillitTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ZillitIconButton(
            icon = ZillitIcons.MoreVertical,
            contentDescription = str(S.desktop_email_folder_options),
            onClick = { menuOpen = true },
            tint = if (revealed || menuOpen) {
                colors.textSecondary
            } else {
                colors.textMuted.copy(alpha = MENU_IDLE_ALPHA)
            },
            size = HEADING_BUTTON,
        )
        ZillitActionMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            entries = listOf(
                ZillitMenuEntry.Action(str(S.edit), ZillitIcons.Edit) { onEvent(EmailEvent.EditFolder(folder)) },
                ZillitMenuEntry.Action(str(S.delete), ZillitIcons.Trash, ZillitMenuTone.Danger) {
                    onEvent(EmailEvent.DeleteFolder(folder))
                },
            ),
        )
    }
}

/** The Settings row under the folders, and its popover. */
@Composable
private fun SettingsRow(entries: List<MailSettingsEntry>, onSetting: (MailSettingsEntry) -> Unit) {
    val colors = ZillitTheme.colors
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Column(Modifier.padding(horizontal = ZillitTheme.spacing.sm).padding(top = ZillitTheme.spacing.sm)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
        Box(Modifier.padding(top = ZillitTheme.spacing.sm, bottom = ZillitTheme.spacing.md)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.large)
                    .background(if (hovered || open) colors.accentSoft else Color.Transparent)
                    .hoverable(interaction)
                    .clickable { open = true }
                    .padding(horizontal = ZillitTheme.spacing.md)
                    .height(ROW_HEIGHT)
                    .testTag(SETTINGS_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitIcon(
                    icon = ZillitIcons.Settings,
                    contentDescription = null,
                    tint = if (hovered || open) colors.accentText else colors.textSecondary,
                    size = FOLDER_ICON,
                )
                ZillitText(
                    text = str(S.settings),
                    style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = if (hovered || open) colors.accentText else colors.textPrimary,
                )
            }
            ZillitActionMenu(
                expanded = open,
                onDismissRequest = { open = false },
                entries = entries.map { entry ->
                    ZillitMenuEntry.Action(entry.label, entry.icon()) { onSetting(entry) }
                },
            )
        }
    }
}

private fun MailSettingsEntry.icon(): ImageVector = when (this) {
    MailSettingsEntry.Signatures -> ZillitIcons.Signature
    MailSettingsEntry.ConversationView -> ZillitIcons.Chat
    MailSettingsEntry.ImportContacts -> ZillitIcons.UserPlus
    MailSettingsEntry.BccPresets -> ZillitIcons.Mail
    MailSettingsEntry.EmailGroup -> ZillitIcons.Users
    MailSettingsEntry.Credentials -> ZillitIcons.Info
    MailSettingsEntry.Forwarding -> ZillitIcons.Forward
    MailSettingsEntry.Rules -> ZillitIcons.Filter
    MailSettingsEntry.MailboxTour -> ZillitIcons.Help
}

/**
 * The web's mailbox account switcher: an avatar disc, the mailbox's title
 * and address, a caret. Brand-tinted while the shared Accounts mailbox is
 * active — the same "you are here" treatment as the selected folder — and
 * neutral for personal. The menu lists both with their unread pills; a dot
 * on the disc says the mailbox you are NOT looking at has unread mail.
 */
@Composable
internal fun MailboxSwitcher(switch: MailboxSwitch, enabled: Boolean, onSwitch: (MailboxKind) -> Unit) {
    val colors = ZillitTheme.colors
    val active = switch.activeIdentity ?: return
    val isAccounts = switch.active == MailboxKind.Accounts
    var open by remember { mutableStateOf(false) }

    Box(Modifier.padding(horizontal = ZillitTheme.spacing.md).padding(top = ZillitTheme.spacing.md)) {
        ZillitTooltip(text = active.address) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.large)
                    .background(if (isAccounts) colors.accentSoft else colors.surface)
                    .border(
                        1.dp,
                        if (isAccounts) colors.accent.copy(alpha = ACTIVE_BORDER_ALPHA) else colors.border,
                        ZillitTheme.shapes.large,
                    )
                    .clickable(enabled = enabled) { open = true }
                    .padding(start = ZillitTheme.spacing.xs, end = ZillitTheme.spacing.sm)
                    .padding(vertical = ZillitTheme.spacing.xs)
                    .testTag(SWITCHER_TAG),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                MailboxDisc(switch.active, dot = switch.inactiveHasUnread)
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        text = active.title,
                        style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = if (isAccounts) colors.accentText else colors.textPrimary,
                        maxLines = 1,
                    )
                    ZillitText(
                        text = active.address,
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                        maxLines = 1,
                    )
                }
                ZillitIcon(
                    icon = ZillitIcons.ChevronDown,
                    contentDescription = null,
                    tint = if (isAccounts) colors.accentText else colors.textMuted,
                    size = HEADING_CHEVRON,
                )
            }
        }
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = switch.menuEntries(onSwitch),
        )
    }
}

/** One entry per mailbox this user has, the active one ticked, each with its unread count. */
private fun MailboxSwitch.menuEntries(onSwitch: (MailboxKind) -> Unit): List<ZillitMenuEntry> =
    listOf(MailboxKind.Personal, MailboxKind.Accounts).mapNotNull { kind ->
        val identity = identity(kind) ?: return@mapNotNull null
        ZillitMenuEntry.Action(
            label = identity.title + if (kind == active) "  ✓" else "",
            icon = if (kind == MailboxKind.Accounts) ZillitIcons.Users else ZillitIcons.Mail,
            tone = if (kind == MailboxKind.Accounts) ZillitMenuTone.Primary else ZillitMenuTone.Neutral,
            badge = unread(kind),
        ) { onSwitch(kind) }
    }

/** The mailbox's avatar: an envelope on grey for personal, people on the accent for Accounts. */
@Composable
private fun MailboxDisc(kind: MailboxKind, dot: Boolean) {
    val colors = ZillitTheme.colors
    val accounts = kind == MailboxKind.Accounts
    Box {
        Box(
            modifier = Modifier
                .size(DISC)
                .clip(CircleShape)
                .background(if (accounts) colors.accent else colors.surfaceSunken),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(
                icon = if (accounts) ZillitIcons.Users else ZillitIcons.Mail,
                contentDescription = null,
                tint = if (accounts) colors.textOnAccent else colors.textMuted,
                size = FOLDER_ICON,
            )
        }
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 1.dp, y = (-1).dp)
                    .size(DOT)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(colors.accent),
            )
        }
    }
}

/** The web's folder glyphs (`NewEmailSidebar.jsx` `getDefaultFoldersIcon`), from our set. */
internal fun EmailFolder.icon(): ImageVector = when (name.lowercase()) {
    "inbox" -> ZillitIcons.Inbox
    "sent" -> ZillitIcons.Send
    "drafts" -> ZillitIcons.Draft
    "trash" -> ZillitIcons.Trash
    "junk", "spam" -> ZillitIcons.Warning
    else -> ZillitIcons.Tag
}

/** A folder row's place in the window, in root pixels — the drop target a drag looks for. */
data class FolderBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

internal const val SIDEBAR_TAG = "email-sidebar"
internal const val NEW_EMAIL_TAG = "email-new"
internal const val SETTINGS_TAG = "email-settings"
internal const val SWITCHER_TAG = "email-mailbox-switcher"
internal const val CREATE_FOLDER_TAG = "email-create-folder"

private val ROW_HEIGHT = 40.dp
private val NESTED_ROW_HEIGHT = 36.dp
private val NESTED_INSET = 24.dp
private val FOLDER_ICON = 18.dp
private val HEADING_CHEVRON = 12.dp
private val HEADING_BUTTON = 24.dp
private val DISC = 32.dp
private val DOT = 10.dp
private val HEADING_TRACKING = androidx.compose.ui.unit.TextUnit(0.08f, androidx.compose.ui.unit.TextUnitType.Em)
private const val ACTIVE_BORDER_ALPHA = 0.35f
private const val MENU_IDLE_ALPHA = 0.55f
