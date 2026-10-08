package com.zillit.desktop.feature.chat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import com.zillit.desktop.core.designsystem.component.ZillitActionMenu
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitLazyColumn
import com.zillit.desktop.core.designsystem.component.ZillitMenuEntry
import com.zillit.desktop.core.designsystem.component.ZillitMenuTone
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
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
import com.zillit.desktop.feature.chat.domain.GroupMember
import com.zillit.desktop.feature.chat.domain.designationLabel

/** What the Group info panel can ask for; hoisted to the thread so a dialog covers all of it. */
internal sealed interface GroupDialog {
    data object Edit : GroupDialog
    data object Leave : GroupDialog
    data object Delete : GroupDialog
    data object AddMembers : GroupDialog

    /** Takes [userId] out of the room, once confirmed. */
    data class RemoveMember(val userId: String, val name: String) : GroupDialog

    /** Makes [userId] an admin, or takes it back, once confirmed. */
    data class ToggleAdmin(val userId: String, val name: String, val makeAdmin: Boolean) : GroupDialog
}

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

/**
 * "Members · n": a search box, everyone in the room (administrators first),
 * and — for an administrator — "Add members", a menu on each other person,
 * and, while searching, the production's other people with an Add button
 * under "Not in Group" (`InfoSiderGroup.jsx`, the Members tab).
 */
@Composable
internal fun GroupMembersSection(info: GroupInfoState?, hooks: InfoHooks) {
    val detail = info?.detail
    val canManage = detail?.canManage(hooks.selfId) == true
    val members = detail?.membersInOrder { hooks.resolveContact(it)?.fullName.orEmpty() }
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
            text = if (detail == null) str(S.members) else "${str(S.members)} · ${members.size}",
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        if (canManage) {
            ZillitButton(
                text = str(S.desktop_add_members),
                onClick = { hooks.onGroupDialog(GroupDialog.AddMembers) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
                enabled = info?.busy != true,
            )
        }
    }
    if (info == null) return
    if (detail == null) {
        if (info.loading) {
            ZillitText(
                text = str(S.ah_loading),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
                modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            )
        }
        return
    }
    MembersList(info, detail, members, canManage, hooks)
}

/** The search box, the room's people it narrows, and (for an admin) the outsiders it finds. */
@Composable
private fun MembersList(
    info: GroupInfoState,
    detail: GroupDetail,
    members: List<Pair<GroupMember, CrewContact>>,
    canManage: Boolean,
    hooks: InfoHooks,
) {
    var query by remember(info.roomId) { mutableStateOf("") }
    val shown = members.filter { (_, contact) -> contact.matches(query) }
    val outsiders = if (canManage && query.isNotBlank()) {
        val inside = detail.members.mapTo(mutableSetOf()) { it.userId }
        hooks.people.filter { it.userId !in inside && it.matches(query) }.sortedBy { it.fullName.lowercase() }
    } else {
        emptyList()
    }
    ZillitSearchField(
        value = query,
        onValueChange = { query = it },
        placeholder = str(S.desktop_search_members),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
    )
    shown.forEach { (member, contact) ->
        val isMe = contact.userId == hooks.selfId
        MemberRow(contact, isAdmin = member.isAdmin && !detail.isSystemDefined, isMe = isMe, hooks = hooks) {
            if (canManage && !isMe) MemberMenu(member.isAdmin, contact, info.busy, hooks)
        }
    }
    if (outsiders.isNotEmpty()) NotInGroup(outsiders, info.busy, hooks)
    if (query.isNotBlank() && shown.isEmpty() && outsiders.isEmpty()) {
        ZillitText(
            text = str(S.no_user_found),
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textMuted,
            modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        )
    }
}

/** People the search found who are not in the room, each with an Add. */
@Composable
private fun NotInGroup(people: List<CrewContact>, busy: Boolean, hooks: InfoHooks) {
    ZillitText(
        text = str(S.desktop_not_in_group).uppercase(),
        style = ZillitTheme.typography.labelSmall,
        color = ZillitTheme.colors.textMuted,
        modifier = Modifier.padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
    )
    people.forEach { contact ->
        MemberRow(contact, isAdmin = false, isMe = false, hooks = hooks) {
            ZillitButton(
                text = str(S.add),
                onClick = { hooks.onEvent(ChatEvent.AddGroupMembers(listOf(contact.userId))) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.UserPlus,
                enabled = !busy,
            )
        }
    }
}

/** Every word of [query] is in the name or the role line — the web's member search. */
private fun CrewContact.matches(query: String): Boolean {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return true
    val name = fullName.lowercase()
    val role = designationLabel()?.localised()?.lowercase().orEmpty()
    return words.all { name.contains(it) || role.contains(it) }
}

/** The ⋮ on someone else's row, for an administrator: make or unmake admin, or remove. */
@Composable
private fun MemberMenu(isAdmin: Boolean, contact: CrewContact, busy: Boolean, hooks: InfoHooks) {
    var open by remember { mutableStateOf(false) }
    Box {
        ZillitIconButton(
            icon = ZillitIcons.MoreVertical,
            contentDescription = contact.fullName,
            onClick = { open = true },
            enabled = !busy,
            tint = ZillitTheme.colors.textSecondary,
        )
        ZillitActionMenu(
            expanded = open,
            onDismissRequest = { open = false },
            entries = listOf(
                ZillitMenuEntry.Action(
                    label = if (isAdmin) str(S.desktop_remove_as_admin) else str(S.desktop_make_admin),
                    icon = ZillitIcons.Shield,
                ) {
                    open = false
                    hooks.onGroupDialog(GroupDialog.ToggleAdmin(contact.userId, contact.fullName, !isAdmin))
                },
                ZillitMenuEntry.Action(
                    label = str(S.mtg_remove_from_group),
                    icon = ZillitIcons.Trash,
                    tone = ZillitMenuTone.Danger,
                ) {
                    open = false
                    hooks.onGroupDialog(GroupDialog.RemoveMember(contact.userId, contact.fullName))
                },
            ),
        )
    }
}

@Composable
private fun MemberRow(
    contact: CrewContact,
    isAdmin: Boolean,
    isMe: Boolean,
    hooks: InfoHooks,
    trailing: @Composable () -> Unit = {},
) {
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
        trailing()
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
    hooks: InfoHooks,
    dialog: GroupDialog?,
    onDismiss: () -> Unit,
    onEvent: (ChatEvent) -> Unit,
) {
    val detail = info?.detail
    EditGroupDialog(detail, info, visible = dialog == GroupDialog.Edit, onDismiss, onEvent)
    val title = detail?.name?.localised().orEmpty()
    ConfirmGroupDialog(
        title, str(S.are_you_sure_you_want_to_leave_this_group), ZillitIcons.Logout, str(S.leave),
        visible = dialog == GroupDialog.Leave, onDismiss = onDismiss,
    ) {
        onDismiss()
        onEvent(ChatEvent.LeaveGroup)
    }
    ConfirmGroupDialog(
        title, str(S.are_you_sure_you_want_to_delete_this_group), ZillitIcons.Trash, str(S.delete),
        visible = dialog == GroupDialog.Delete, onDismiss = onDismiss,
    ) {
        onDismiss()
        onEvent(ChatEvent.DeleteGroup)
    }
    // The member-specific confirmations keep the last person asked about, so
    // the text does not blank while the dialog fades out.
    val removing = dialog as? GroupDialog.RemoveMember
    ConfirmGroupDialog(
        removing?.name.orEmpty(), str(S.desktop_remove_member_confirm), ZillitIcons.Trash, str(S.dd_action_remove),
        visible = removing != null, onDismiss = onDismiss,
    ) {
        onDismiss()
        removing?.let { onEvent(ChatEvent.RemoveGroupMember(it.userId)) }
    }
    val toggling = dialog as? GroupDialog.ToggleAdmin
    ConfirmGroupDialog(
        toggling?.name.orEmpty(),
        if (toggling?.makeAdmin == false) str(S.desktop_remove_admin_confirm) else str(S.desktop_make_admin_confirm),
        ZillitIcons.Shield, str(S.ah_yes),
        visible = toggling != null, onDismiss = onDismiss, danger = false,
    ) {
        onDismiss()
        toggling?.let { onEvent(ChatEvent.ToggleGroupAdmin(it.userId)) }
    }
    AddMembersDialog(detail, hooks, visible = dialog == GroupDialog.AddMembers, onDismiss, onEvent)
}

@Composable
private fun ConfirmGroupDialog(
    title: String,
    question: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    action: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    danger: Boolean = true,
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
            ZillitButton(
                text = action,
                variant = if (danger) ButtonVariant.Danger else ButtonVariant.Primary,
                onClick = onConfirm,
            )
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

/**
 * "Add members": everyone in the production who is not in the room yet, a
 * search box and a tick each — one write for the lot. A fixed-height list,
 * because a lazy list in a dialog needs bounds ([ZillitLazyColumn]).
 */
@Composable
private fun AddMembersDialog(
    detail: GroupDetail?,
    hooks: InfoHooks,
    visible: Boolean,
    onDismiss: () -> Unit,
    onEvent: (ChatEvent) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            picked = emptySet()
        }
    }
    val inside = detail?.members.orEmpty().mapTo(mutableSetOf()) { it.userId }
    val candidates = hooks.people.filter { it.userId !in inside && it.matches(query) }
        .sortedBy { it.fullName.lowercase() }
    ZillitDialogShell(
        title = str(S.desktop_add_members),
        icon = ZillitIcons.UserPlus,
        visible = visible,
        onDismiss = onDismiss,
        scrollable = false,
        actions = {
            ZillitButton(text = str(S.cancel), variant = ButtonVariant.Tertiary, onClick = onDismiss)
            ZillitButton(
                text = str(S.add),
                enabled = picked.isNotEmpty(),
                onClick = {
                    onDismiss()
                    onEvent(ChatEvent.AddGroupMembers(picked.toList()))
                },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitSearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = str(S.desktop_search_members),
            )
            ZillitLazyColumn(
                modifier = Modifier.fillMaxWidth().height(ADD_LIST_HEIGHT),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
            ) {
                items(candidates, key = CrewContact::userId) { contact ->
                    ZillitCheckbox(
                        checked = contact.userId in picked,
                        onCheckedChange = {
                            picked = if (contact.userId in picked) picked - contact.userId else picked + contact.userId
                        },
                        label = contact.fullName,
                    )
                }
            }
        }
    }
}

private val ADD_LIST_HEIGHT = 240.dp
private val MEMBER_AVATAR = 40.dp
