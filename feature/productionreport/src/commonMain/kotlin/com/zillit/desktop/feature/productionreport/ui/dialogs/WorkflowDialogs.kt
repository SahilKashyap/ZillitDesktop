// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.productionreport.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.PublishDestination
import com.zillit.desktop.feature.productionreport.ui.PublishType
import com.zillit.desktop.feature.productionreport.ui.ReportDialog
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.DISABLED_ALPHA
import com.zillit.desktop.feature.productionreport.ui.components.Face
import com.zillit.desktop.feature.productionreport.ui.components.ModalScrim
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportInput
import com.zillit.desktop.feature.productionreport.ui.components.ReportModal
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.components.swallowClicks
import com.zillit.desktop.feature.productionreport.ui.theme.ReportIcons
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/** "Save As" — a draft never saves without a name (ZL-20654). */
@Composable
internal fun DraftNameDialog(dialog: ReportDialog.DraftName, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    ModalScrim(onDismiss = null, onEscape = { onEvent(DialogEvent.Dismiss) }) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth(0.95f)
                .shadow(20.dp, RoundedCornerShape(10.dp)).clip(RoundedCornerShape(10.dp))
                .background(colors.surface).swallowClicks().padding(20.dp),
        ) {
            Text(
                str(S.pr_save_as),
                style = reportText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                str(S.desktop_draft_name),
                style = reportText(12.sp),
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            ReportInput(
                value = dialog.name,
                onChange = { onEvent(DialogEvent.EditDraftName(it)) },
                placeholder = str(S.desktop_enter_draft_name),
                autoFocus = true,
                radius = 4.dp,
                onEnter = { onEvent(DialogEvent.ConfirmDraftName) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ReportButton(str(S.cancel), { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost)
                ReportButton(
                    str(S.confirm),
                    { onEvent(DialogEvent.ConfirmDraftName) },
                    enabled = dialog.name.isNotBlank(),
                )
            }
        }
    }
}

/**
 * Send for comments: the recipient picker, or the removal prompt. The menu
 * entry (or header button) has already said which kind of send this is, so
 * there is no type chooser in front of it.
 */
@Composable
internal fun SendPickerDialog(state: ReportUiState, dialog: ReportDialog.SendPicker, onEvent: (ReportEvent) -> Unit) {
    val removal = dialog.pendingRemoval
    if (removal != null) RemovalPrompt(removal, onEvent) else RecipientPickerDialog(state, dialog, onEvent)
}

@Composable
private fun RecipientPickerDialog(
    state: ReportUiState,
    dialog: ReportDialog.SendPicker,
    onEvent: (ReportEvent) -> Unit,
) {
    val colors = ReportTheme.colors
    val selectable = state.members.filter { it.isAccepted && it.userId != state.me && it.userId.isNotBlank() }
    val query = dialog.search.trim().lowercase()
    val filtered = selectable.filter { member ->
        query.isEmpty() || listOf(
            member.fullName,
            member.designation,
            member.department,
        ).any { it.lowercase().contains(query) }
    }
    val previous = filtered.filter { it.userId in dialog.initial }
    val others = filtered.filterNot { it.userId in dialog.initial }
    val count = dialog.selected.count { id -> selectable.any { it.userId == id } }
    ReportModal(
        str(S.desktop_pr_select_recipients_for_comments),
        { onEvent(DialogEvent.Dismiss) },
        scrollable = false,
    ) {
        Column(Modifier.fillMaxWidth().height(540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReportInput(
                value = dialog.search,
                onChange = { onEvent(WorkflowEvent.SearchRecipients(it)) },
                placeholder = str(S.desktop_search_by_name_role_department),
                leadingIcon = ZillitIcons.Search,
                autoFocus = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count", style = reportText(12.sp, FontWeight.SemiBold), color = colors.textPrimary)
                Text(
                    " of ${selectable.size} selected",
                    style = reportText(12.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (count == selectable.size && selectable.isNotEmpty()) {
                        str(S.dd_deselect_all)
                    } else {
                        str(S.select_all)
                    },
                    style = reportText(12.sp, FontWeight.Medium),
                    color = colors.accent,
                    modifier = Modifier.plainClick { onEvent(WorkflowEvent.ToggleAllRecipients) },
                )
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .zillitVerticalScroll(rememberScrollState()),
            ) {
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                        Text(str(S.desktop_no_members_found), style = reportText(14.sp), color = colors.textSecondary)
                    }
                }
                if (previous.isNotEmpty()) SectionHeader(str(S.cs_previously_selected))
                previous.forEach { member ->
                    MemberRow(member, member.userId in dialog.selected) {
                        onEvent(WorkflowEvent.ToggleRecipient(member.userId))
                    }
                }
                if (others.isNotEmpty()) SectionHeader(str(S.desktop_all_members))
                others.forEach { member ->
                    MemberRow(member, member.userId in dialog.selected) {
                        onEvent(WorkflowEvent.ToggleRecipient(member.userId))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ReportButton(
                    str(S.cancel),
                    { onEvent(DialogEvent.Dismiss) },
                    kind = ButtonKind.Ghost,
                    height = 40.dp,
                )
                Box(Modifier.weight(1f))
                ReportButton(
                    "Send ($count)",
                    { onEvent(WorkflowEvent.SendRecipients) },
                    enabled = count > 0,
                    height = 40.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = reportText(10.sp, FontWeight.Bold).copy(letterSpacing = 0.5.sp),
        color = ReportTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(ReportTheme.colors.elevated)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MemberRow(member: SheetMember, checked: Boolean, onToggle: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth()
            .background(
                when {
                    checked -> colors.accent.copy(alpha = 0.08f)
                    hovered -> colors.elevated
                    else -> Color.Transparent
                },
            )
            .hoverable(source)
            .plainClick(source = source, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Face(member.userId, member.fullName, 36.dp)
        Column(Modifier.weight(1f)) {
            Text(
                member.fullName,
                style = reportText(14.sp, FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
            )
            val sub = listOf(member.designation, member.department).filter { it.isNotBlank() }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, style = reportText(12.sp), color = colors.textSecondary, maxLines = 1)
        }
        ZillitCheckbox(checked = checked, onCheckedChange = { onToggle() })
    }
}

/** "Remove from comments?" — asked before a re-send drops anyone who received comments last time. */
@Composable
private fun RemovalPrompt(removed: List<SheetMember>, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    ReportModal(str(S.cmt_removal_title), { onEvent(WorkflowEvent.CancelRemoval) }, width = 720.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                removed.joinToString(", ") { it.fullName },
                style = reportText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Text(
                str(S.desktop_pr_comment_removal_message),
                style = reportText(14.sp),
                color = colors.textSecondary,
            )
            Text(
                str(S.desktop_pr_comment_removal_access_question),
                style = reportText(14.sp),
                color = colors.textSecondary,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ReportButton(
                    str(S.cancel),
                    { onEvent(WorkflowEvent.CancelRemoval) },
                    kind = ButtonKind.Ghost,
                    height = 40.dp,
                )
                ReportButton(
                    str(S.cmt_removal_keep_access),
                    { onEvent(WorkflowEvent.FinishSend(false)) },
                    kind = ButtonKind.Outline,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
                ReportButton(
                    str(S.cmt_removal_revoke_access),
                    { onEvent(WorkflowEvent.FinishSend(true)) },
                    kind = ButtonKind.Danger,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/**
 * "Publish Production Report": where it goes first; for the app, then
 * Continuation (keeps earlier chat posts), New (replaces them all) or —
 * when the unit chat holds a live document — Replace (swaps that one). The
 * dialog closes the moment the publish call succeeds; the PDF render, upload
 * and chat post run behind it.
 */
@Composable
internal fun PublishDialog(state: ReportUiState, dialog: ReportDialog.Publish, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    ReportModal(str(S.desktop_pr_publish_production_report), { onEvent(DialogEvent.Dismiss) }) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(
                    ReportIcons.FileUpload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            if (dialog.choosingDestination) {
                DestinationStep(state, dialog, onEvent)
            } else {
                TypeStep(state, dialog, onEvent)
            }
            ReportButton(str(S.cancel), { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun DestinationStep(state: ReportUiState, dialog: ReportDialog.Publish, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Text(
        str(S.desktop_pr_where_to_publish),
        style = reportText(15.sp, FontWeight.Medium),
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            str(S.desktop_where_should_it_go),
            style = reportText(12.sp, FontWeight.SemiBold),
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        PublishDestination.entries.forEach { option ->
            val blocked = option.needsDocDist && !state.canDistribute
            val selected = dialog.destination == option && !blocked
            OptionRow(option.label, option.hint, selected, blocked) { onEvent(WorkflowEvent.PickDestination(option)) }
        }
    }
    ReportButton(
        text = if (dialog.destination == PublishDestination.DocDist) {
            str(S.dd_publish_confirm_title)
        } else {
            str(S.continue_text)
        },
        onClick = { onEvent(WorkflowEvent.ContinuePublish) },
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        radius = 12.dp,
        height = 48.dp,
        fontSize = 14.sp,
    )
}

@Composable
private fun OptionRow(label: String, hint: String, selected: Boolean, blocked: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    com.zillit.desktop.core.designsystem.component.ZillitTooltip(
        if (blocked) str(S.pub_dest_needs_dd_rights) else hint,
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (selected) colors.accentLight else Color.Transparent)
                .border(2.dp, if (selected) colors.accent else colors.border, RoundedCornerShape(8.dp))
                .alpha(if (blocked) DISABLED_ALPHA else 1f)
                .plainClick(enabled = !blocked, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioDot(selected, 16.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = reportText(14.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                    color = colors.textPrimary,
                )
                Text(hint, style = reportText(12.sp), color = colors.textTertiary)
            }
            if (blocked) Text(str(S.desktop_no_rights_lower), style = reportText(10.sp), color = colors.textMuted)
        }
    }
}

@Composable
private fun RadioDot(selected: Boolean, size: androidx.compose.ui.unit.Dp) {
    val colors = ReportTheme.colors
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .border(2.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(size / 2).clip(CircleShape).background(colors.accent))
    }
}

@Composable
private fun TypeStep(state: ReportUiState, dialog: ReportDialog.Publish, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Text(
        str(S.desktop_pr_how_to_publish),
        style = reportText(15.sp, FontWeight.Medium),
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Replace is hidden, not disabled, when there is nothing to swap.
        val offered = PublishType.entries.filter { it != PublishType.Replace || dialog.replaceOptions.isNotEmpty() }
        offered.forEach { type ->
            TypeCard(type.label, type.hint, dialog.type == type) { onEvent(WorkflowEvent.PickPublishType(type)) }
        }
        if (dialog.type == PublishType.Replace) ReplacePicker(dialog, onEvent)
    }
    val label = when (dialog.type) {
        PublishType.Continuation -> str(S.desktop_publish_as_continuation)
        PublishType.New -> str(S.desktop_publish_as_new)
        PublishType.Replace -> str(S.desktop_publish_and_replace)
        null -> str(S.desktop_select_an_option_to_publish)
    }
    ReportButton(
        text = if (state.busy) str(S.desktop_publishing) else label,
        onClick = { onEvent(WorkflowEvent.ConfirmPublish) },
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        enabled = dialog.type != null && !state.busy,
        radius = 12.dp,
        height = 48.dp,
        fontSize = 14.sp,
    )
}

/** The live documents a Replace can retire, newest first; the newest is pre-picked. */
@Composable
private fun ReplacePicker(dialog: ReportDialog.Publish, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, colors.border, RoundedCornerShape(8.dp)),
    ) {
        Text(
            str(S.um_document_to_replace),
            style = reportText(12.sp, FontWeight.SemiBold),
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Column(
            Modifier.fillMaxWidth()
                .heightIn(max = REPLACE_LIST_MAX_HEIGHT.dp)
                .zillitVerticalScroll(rememberScrollState()),
        ) {
            dialog.replaceOptions.forEach { option ->
                val selected = option.chatId == dialog.replaceChatId
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) colors.accentLight else Color.Transparent)
                        .plainClick { onEvent(WorkflowEvent.PickReplaceTarget(option.chatId)) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    RadioDot(selected, 16.dp)
                    Text(
                        option.label,
                        style = reportText(13.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private const val REPLACE_LIST_MAX_HEIGHT = 180

@Composable
private fun TypeCard(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) colors.accentLight else if (hovered) colors.hover else colors.surface)
            .border(
                2.dp,
                if (selected) colors.accent else if (hovered) Color(0xFFFDB022) else colors.border,
                RoundedCornerShape(12.dp),
            )
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioDot(selected, 20.dp)
        Column {
            Text(title, style = reportText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
            Text(
                description,
                style = reportText(12.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** "Reject Production Report" — the reason is optional. */
@Composable
internal fun RejectDialog(state: ReportUiState, dialog: ReportDialog.Reject, onEvent: (ReportEvent) -> Unit) {
    ReportModal(str(S.desktop_pr_reject_production_report), { onEvent(DialogEvent.Dismiss) }) {
        Text(
            str(S.hint_rejection_reason),
            style = reportText(14.sp),
            color = ReportTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ReportInput(
            value = dialog.reason,
            onChange = { onEvent(WorkflowEvent.EditRejectReason(it)) },
            placeholder = str(S.reason),
            singleLine = false,
            minLines = 3,
            radius = 4.dp,
            autoFocus = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportButton(
                str(S.reject),
                { onEvent(WorkflowEvent.ConfirmReject) },
                kind = ButtonKind.Danger,
                enabled = !state.busy,
                radius = 12.dp,
                height = 40.dp,
                fontSize = 14.sp,
            )
            ReportButton(str(S.cancel), { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, height = 40.dp)
        }
    }
}

/** "Send Reminder" — a message rides along; blank sends the default. */
@Composable
internal fun ReminderComposeDialog(
    state: ReportUiState,
    dialog: ReportDialog.ReminderCompose,
    onEvent: (ReportEvent) -> Unit,
) {
    ReportModal(str(S.pr_send_reminder), { onEvent(DialogEvent.Dismiss) }) {
        Text(
            str(S.desktop_reminder_message_prompt),
            style = reportText(14.sp),
            color = ReportTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ReportInput(
            value = dialog.message,
            onChange = { onEvent(WorkflowEvent.EditReminder(it)) },
            placeholder = str(S.desktop_pr_default_reminder),
            singleLine = false,
            minLines = 3,
            radius = 4.dp,
            autoFocus = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportButton(
                str(S.pr_send_reminder),
                { onEvent(WorkflowEvent.ConfirmReminder) },
                enabled = !state.busy,
                radius = 12.dp,
                height = 40.dp,
                fontSize = 14.sp,
            )
            ReportButton(str(S.cancel), { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, height = 40.dp)
        }
    }
}
