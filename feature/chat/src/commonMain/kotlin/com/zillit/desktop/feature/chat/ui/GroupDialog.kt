package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.TagTone
import com.zillit.desktop.core.designsystem.component.ZillitAvatar
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTag
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.chat.domain.ChatAttachment
import com.zillit.desktop.feature.chat.domain.CrewContact
import com.zillit.desktop.feature.chat.domain.GroupDetail
import com.zillit.desktop.feature.chat.domain.designationLabel

/** The three confirmations the Group info panel can ask for; hoisted so they cover the whole thread. */
internal enum class GroupDialog { Edit, Leave, Delete }

/** The group's own picture, fetched through the thread's poster seam; null while loading or with none set. */
@Composable
internal fun rememberGroupPicture(picture: ChatAttachment?, media: BubbleMedia): ImageBitmap? =
    produceState<ImageBitmap?>(initialValue = null, picture?.media, picture?.thumbnail) {
        value = picture?.let { media.loadThumbnail(it) }
    }.value

/**
 * "Change photo" under the group's picture — any member of a user-made room
 * (`InfoSiderGroup.jsx`, `canEditAvatar`). Nothing renders for a room the
 * viewer may not re-picture, or before the room has loaded.
 */
@Composable
internal fun GroupPhotoButton(info: GroupInfoState?, selfId: String?, onEvent: (ChatEvent) -> Unit) {
    if (info?.detail?.canChangePicture(selfId) != true) return
    ZillitButton(
        text = str(S.docusign_field_change_photo),
        onClick = { onEvent(ChatEvent.ChangeGroupPhoto) },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Photo,
        loading = info.busy,
        modifier = Modifier.padding(top = ZillitTheme.spacing.xs),
    )
}

/** "Members (n)": everyone in the room, administrators first, each with their role line. */
@Composable
internal fun GroupMembersSection(info: GroupInfoState?, hooks: InfoHooks) {
    val detail = info?.detail
    val rows = detail?.membersInOrder { hooks.resolveContact(it)?.fullName.orEmpty() }
        ?.mapNotNull { member -> hooks.resolveContact(member.userId)?.let { member to it } }
        .orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = ZillitIcons.Users, contentDescription = null, tint = ZillitTheme.colors.textSecondary)
        ZillitText(
            text = if (detail == null) str(S.members) else "${str(S.members)} · ${rows.size}",
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
    if (detail == null) {
        if (info?.loading == true) {
            ZillitText(
                text = str(S.ah_loading),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            )
        }
        return
    }
    rows.forEach { (member, contact) ->
        MemberRow(
            contact,
            isAdmin = member.isAdmin && !detail.isSystemDefined,
            isMe = contact.userId == hooks.selfId,
            hooks = hooks,
        )
    }
}

@Composable
private fun MemberRow(contact: CrewContact, isAdmin: Boolean, isMe: Boolean, hooks: InfoHooks) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitAvatar(
            name = contact.fullName,
            image = rememberChatFace(contact.userId, hooks.loadAvatar),
            size = MEMBER_AVATAR,
        )
        Column(Modifier.weight(1f)) {
            ZillitText(
                text = if (isMe) "${contact.fullName} (${str(S.you)})" else contact.fullName,
                style = ZillitTheme.typography.bodyMedium,
                maxLines = 1,
            )
            contact.designationLabel()?.takeIf { it.isNotBlank() }?.let { role ->
                ZillitText(
                    text = role.localised(),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
        if (isAdmin) ZillitTag(str(S.admin), tone = TagTone.Accent)
    }
}

/**
 * Edit group (an administrator's), Leave group (a member's, unless they are
 * the last administrator) and Delete group (an administrator's) — the web's
 * gating, `InfoSiderGroup.jsx`. Each opens its confirmation over the thread.
 */
@Composable
internal fun GroupActionsSection(info: GroupInfoState?, hooks: InfoHooks) {
    val detail = info?.detail ?: return
    val me = hooks.selfId
    val edit = detail.canManage(me)
    val leave = detail.canLeave(me)
    val delete = detail.canManage(me)
    if (listOf(edit, leave, delete).none { it } && info.error == null) return
    Column(Modifier.fillMaxWidth()) {
        if (edit) {
            GroupActionRow(ZillitIcons.Edit, str(S.edit_group), ZillitTheme.colors.textPrimary, !info.busy) {
                hooks.onGroupDialog(GroupDialog.Edit)
            }
        }
        if (leave) {
            GroupActionRow(ZillitIcons.Logout, str(S.leave_group), ZillitTheme.colors.danger, !info.busy) {
                hooks.onGroupDialog(GroupDialog.Leave)
            }
        }
        if (delete) {
            GroupActionRow(ZillitIcons.Trash, str(S.delete_group), ZillitTheme.colors.danger, !info.busy) {
                hooks.onGroupDialog(GroupDialog.Delete)
            }
        }
        info.error?.let { complaint ->
            ZillitText(
                text = complaint,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.danger,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            )
        }
    }
}

@Composable
private fun GroupActionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = icon, contentDescription = null, tint = tint)
        ZillitText(text = label, style = ZillitTheme.typography.bodyMedium, color = tint)
    }
}

/**
 * The panel's three confirmations, composed at the thread's root rather than
 * in the panel: a dialog shell is a plain scrimmed Box, and inside the
 * 380-wide panel it would cover only the panel.
 */
@Composable
internal fun GroupInfoDialogs(
    info: GroupInfoState?,
    dialog: GroupDialog?,
    onDismiss: () -> Unit,
    onEvent: (ChatEvent) -> Unit,
) {
    val detail = info?.detail
    EditGroupDialog(detail, info, visible = dialog == GroupDialog.Edit, onDismiss, onEvent)
    ConfirmGroupDialog(
        title = detail?.name?.localised().orEmpty(),
        question = str(S.are_you_sure_you_want_to_leave_this_group),
        icon = ZillitIcons.Logout,
        action = str(S.leave),
        visible = dialog == GroupDialog.Leave,
        onDismiss = onDismiss,
    ) {
        onDismiss()
        onEvent(ChatEvent.LeaveGroup)
    }
    ConfirmGroupDialog(
        title = detail?.name?.localised().orEmpty(),
        question = str(S.are_you_sure_you_want_to_delete_this_group),
        icon = ZillitIcons.Trash,
        action = str(S.delete),
        visible = dialog == GroupDialog.Delete,
        onDismiss = onDismiss,
    ) {
        onDismiss()
        onEvent(ChatEvent.DeleteGroup)
    }
}

@Composable
private fun ConfirmGroupDialog(
    title: String,
    question: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    ZillitDialogShell(
        title = title,
        subtitle = question,
        icon = icon,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Tertiary, onClick = onDismiss)
            ZillitButton(text = action, variant = ButtonVariant.Danger, onClick = onConfirm)
        },
    ) {}
}

/**
 * Rename, the part of Edit the web's drawer offers every administrator
 * (`handleSaveName`). Opens on the current name; closes itself once the
 * server has taken the new one, and says its refusal beside the field if not.
 */
@Composable
private fun EditGroupDialog(
    detail: GroupDetail?,
    info: GroupInfoState?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ChatEvent) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            name = detail?.name.orEmpty()
            submitted = false
        }
    }
    // The write is over: success shows as the stored name matching, anything
    // else stays open with the refusal below the field.
    val busy = info?.busy == true
    LaunchedEffect(busy, detail?.name) {
        val finished = submitted && !busy && info?.error == null
        if (visible && finished && detail?.name == name.trim()) onDismiss()
    }
    val save = {
        submitted = true
        if (name.trim() == detail?.name) onDismiss() else onEvent(ChatEvent.RenameGroup(name))
    }
    ZillitDialogShell(
        title = str(S.edit_group),
        icon = ZillitIcons.Edit,
        visible = visible,
        onDismiss = onDismiss,
        actions = {
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Tertiary, onClick = onDismiss)
            ZillitButton(text = str(S.save), loading = busy, enabled = name.isNotBlank(), onClick = save)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitTextField(
                value = name,
                onValueChange = { name = it },
                label = str(S.group_name),
                maxLength = GROUP_NAME_MAX,
                modifier = Modifier.fillMaxWidth(),
                onImeAction = { if (name.isNotBlank()) save() },
            )
            info?.error?.takeIf { submitted }?.let { complaint ->
                ZillitText(
                    text = complaint,
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.danger,
                )
            }
        }
    }
}

private val MEMBER_AVATAR = 40.dp
