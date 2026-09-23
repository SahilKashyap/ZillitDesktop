package com.zillit.desktop.feature.drive.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TabStripSize
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitTabStrip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.drive.domain.DriveShareLink
import com.zillit.desktop.feature.drive.domain.LinkPermission
import com.zillit.desktop.feature.drive.ui.DriveEvent
import com.zillit.desktop.feature.drive.ui.DriveUiState
import com.zillit.desktop.feature.drive.ui.LocalDriveNow
import com.zillit.desktop.feature.drive.ui.ShareState
import com.zillit.desktop.feature.drive.ui.ShareTab
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The share drawer — `ShareDrawer.jsx`. A file has two tabs: the per-user
 * access editor and "Share via link"; a folder has the people editor only
 * (folder links are out of scope on the web too).
 */
@Composable
internal fun ShareSheet(state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val share = state.share
    val item = share?.item
    DriveSideSheet(
        title = if (item?.isFolder == true) str(S.drive_btn_manage_access) else str(S.desktop_drive_share_file),
        subtitle = item?.name,
        visible = share != null,
        onDismiss = { onEvent(DriveEvent.CloseShare) },
        icon = ZillitIcons.Users,
        width = SHARE_WIDTH,
        actions = if (share?.tab == ShareTab.People) {
            {
                ZillitButton(
                    text = str(S.cancel),
                    onClick = { onEvent(DriveEvent.CloseShare) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = str(S.desktop_drive_save_access),
                    onClick = { onEvent(DriveEvent.SubmitShare) },
                    loading = share.submitting,
                    enabled = !share.loading,
                    leadingIcon = ZillitIcons.Check,
                )
            }
        } else {
            null
        },
    ) {
        if (share == null || item == null) return@DriveSideSheet
        if (!item.isFolder) {
            ZillitTabStrip(
                tabs = ShareTab.entries.map { ZillitTab(it.name, it.label) },
                activeId = share.tab.name,
                onSelect = { id ->
                    ShareTab.entries.firstOrNull { it.name == id }?.let { onEvent(DriveEvent.ShareTabTo(it)) }
                },
                size = TabStripSize.Secondary,
            )
        }
        when (share.tab) {
            ShareTab.People -> PeopleTab(share, state, onEvent)
            ShareTab.Link -> LinkTab(share, onEvent)
        }
    }
}

@Composable
private fun PeopleTab(share: ShareState, state: DriveUiState, onEvent: (DriveEvent) -> Unit) {
    val item = share.item
    if (!item.isFolder) {
        ZillitButton(
            text = str(S.desktop_drive_copy_link_24h),
            onClick = { onEvent(DriveEvent.CopyLink(item)) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Copy,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    ZillitText(text = str(S.select_users), style = ZillitTheme.typography.titleSmall)
    if (share.loading) {
        Box(Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg), contentAlignment = Alignment.Center) {
            ZillitSpinner()
        }
        return
    }
    // The creator and the viewer themself are listed nowhere: sharing an
    // item with its owner or with yourself is meaningless and was the source
    // of confusing states on the web before it locked those rows.
    AccessPicker(
        draft = share.access,
        onChange = { onEvent(DriveEvent.ShareAccess(it)) },
        people = state.sharePeople,
        forFolder = item.isFolder,
        locked = setOf(item.createdById, state.viewer.userId).filter { it.isNotBlank() }.toSet(),
        showInherit = item.isFolder,
        enabled = !share.submitting,
    )
}

/** "Share via link" — `ShareViaLink.jsx`: the form, then the live links. */
@Composable
@Suppress("LongMethod") // One form; splitting it separates each field from its label.
private fun LinkTab(share: ShareState, onEvent: (DriveEvent) -> Unit) {
    val form = share.link
    val colors = ZillitTheme.colors
    SheetSection {
        ZillitTextField(
            value = form.recipients,
            onValueChange = { onEvent(DriveEvent.ShareLinkRecipients(it)) },
            label = str(S.drive_link_recipients_label),
            placeholder = "alice@example.com, bob@studio.co — or one per line",
            helperText = str(S.drive_link_recipients_helper),
            leadingIcon = ZillitIcons.Mail,
            singleLine = false,
            enabled = !form.submitting,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = str(S.permission),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
                ZillitSelect(
                    value = form.permission,
                    options = LinkPermission.entries,
                    onSelect = { onEvent(DriveEvent.ShareLinkPermission(it)) },
                    label = { it.label },
                    enabled = !form.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = str(S.drive_link_expires_label),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textSecondary,
                )
                ZillitSelect(
                    value = EXPIRY_OPTIONS.firstOrNull { it.first == form.expiresInMillis } ?: EXPIRY_OPTIONS[1],
                    options = EXPIRY_OPTIONS,
                    onSelect = { onEvent(DriveEvent.ShareLinkExpiry(it.first)) },
                    label = { str(it.second) },
                    enabled = !form.submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Column(Modifier.fillMaxWidth()) {
            ZillitText(
                text = str(S.drive_link_max_views_label),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
            ZillitSelect(
                value = VIEW_OPTIONS.firstOrNull { it == form.maxViews } ?: VIEW_OPTIONS[0],
                options = VIEW_OPTIONS,
                onSelect = { onEvent(DriveEvent.ShareLinkMaxViews(it)) },
                label = { viewCountLabel(it) },
                enabled = !form.submitting,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ZillitTextField(
            value = form.message,
            onValueChange = { onEvent(DriveEvent.ShareLinkMessage(it)) },
            label = str(S.drive_link_message_label),
            placeholder = str(S.drive_link_message_hint),
            singleLine = false,
            maxLength = MESSAGE_MAX,
            enabled = !form.submitting,
        )
        ZillitText(
            text = str(S.desktop_drive_link_tracking_note),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        ZillitButton(
            text = if (form.recipients.isBlank()) str(S.drive_link_generate) else str(S.drive_link_send_email),
            onClick = { onEvent(DriveEvent.GenerateShareLink) },
            loading = form.submitting,
            leadingIcon = ZillitIcons.Link,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    ZillitDivider()
    ZillitText(text = str(S.drive_link_active_links), style = ZillitTheme.typography.titleSmall)
    when {
        form.loadingLinks -> Box(
            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.md),
            contentAlignment = Alignment.Center,
        ) {
            ZillitSpinner()
        }

        form.links.isEmpty() -> ZillitText(
            text = str(S.drive_link_no_active),
            style = ZillitTheme.typography.bodySmall,
            color = colors.textMuted,
        )

        else -> form.links.forEach { link -> ActiveLinkRow(link, onEvent) }
    }
}

@Composable
@Suppress("LongMethod") // One card: its pills, its actions and its recipients.
private fun ActiveLinkRow(link: DriveShareLink, onEvent: (DriveEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val expired = link.isExpired(LocalDriveNow.current())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(if (expired) colors.warningSoft else colors.surfaceSunken)
            .border(1.dp, colors.border, ZillitTheme.shapes.medium)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitStatusPill(
                label = link.permission.label,
                tone = if (link.permission == LinkPermission.View) StatusTone.Progress else StatusTone.Ready,
            )
            if (expired) ZillitStatusPill(label = str(S.expired), tone = StatusTone.Pending)
            if (link.maxViews > 0) ZillitStatusPill(
                label = str(S.desktop_drive_views_of_max, link.viewCount, link.maxViews),
                tone = StatusTone.Neutral,
            )
            Box(Modifier.weight(1f))
            ZillitTooltip(text = str(S.drive_cd_copy_link)) {
                ZillitIconButton(
                    icon = ZillitIcons.Copy,
                    contentDescription = str(S.drive_cd_copy_link),
                    onClick = { onEvent(DriveEvent.CopyShareLink(link)) },
                    enabled = !expired && link.url.isNotBlank(),
                )
            }
            ZillitTooltip(text = str(S.drive_cd_revoke_link)) {
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = str(S.drive_cd_revoke_link),
                    onClick = { onEvent(DriveEvent.RevokeShareLink(link)) },
                    tint = colors.danger,
                )
            }
        }
        ZillitText(
            text = str(
                S.drive_expires_format,
                if (link.expiresOn > 0) EpochDate.dateTime(link.expiresOn) else str(S.never),
            ),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        ZillitText(
            text = str(S.desktop_drive_recipients_count, link.recipients.size) +
                if (link.recipients.isNotEmpty()) " · " + str(S.desktop_drive_total_views, link.viewCount) else "",
            style = ZillitTheme.typography.labelSmall,
            color = colors.textSecondary,
        )
        if (link.recipients.isNotEmpty()) {
            ZillitDivider()
            link.recipients.forEach { recipient ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ZillitIcon(icon = ZillitIcons.Mail, tint = colors.textMuted, size = ZillitTheme.spacing.md)
                    ZillitText(
                        text = recipient.email,
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textSecondary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(start = ZillitTheme.spacing.xs),
                    )
                    ZillitText(
                        text = if (recipient.viewCount > 0) {
                            viewCountLabel(recipient.viewCount)
                        } else {
                            str(S.drive_link_not_viewed)
                        },
                        style = ZillitTheme.typography.labelSmall,
                        color = if (recipient.viewCount > 0) colors.success else colors.textMuted,
                    )
                }
            }
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000
private val EXPIRY_OPTIONS = listOf(
    DAY_MS to S.drive_link_expires_24h,
    7 * DAY_MS to S.drive_link_expires_7d,
    30 * DAY_MS to S.drive_link_expires_30d,
    0L to S.drive_link_never_expires_summary,
)
private val VIEW_OPTIONS = listOf(0, 1, 3, 10)

/** "Unlimited", "1 view", "3 views" — the max-views options and a recipient's count. */
private fun viewCountLabel(count: Int): String = when (count) {
    0 -> str(S.drive_link_views_unlimited)
    1 -> str(S.desktop_drive_view_count_one)
    else -> str(S.desktop_drive_view_count_many, count)
}
private val SHARE_WIDTH = 440.dp
private const val MESSAGE_MAX = 2000
