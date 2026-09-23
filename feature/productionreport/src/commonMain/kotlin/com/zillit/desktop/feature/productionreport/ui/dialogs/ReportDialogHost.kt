// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("CyclomaticComplexMethod")

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.Labels
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.domain.formatDateTime
import com.zillit.desktop.feature.productionreport.domain.reminderSender
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ReportDialog
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.ConfirmModal
import com.zillit.desktop.feature.productionreport.ui.components.Face
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportEmptyState
import com.zillit.desktop.feature.productionreport.ui.components.ReportInput
import com.zillit.desktop.feature.productionreport.ui.components.ReportModal
import com.zillit.desktop.feature.productionreport.ui.components.plainClick
import com.zillit.desktop.feature.productionreport.ui.components.rememberHover
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/** Shows whichever dialog the state holds. */
@Composable
internal fun ReportDialogHost(state: ReportUiState, onEvent: (ReportEvent) -> Unit) {
    val dismiss = { onEvent(DialogEvent.Dismiss) }
    when (val dialog = state.dialog) {
        null -> Unit
        is ReportDialog.Confirm -> ConfirmModal(
            title = dialog.title,
            message = dialog.message,
            confirmLabel = dialog.confirmLabel,
            danger = dialog.danger,
            onConfirm = { onEvent(DialogEvent.Confirm) },
            onCancel = dismiss,
            secondaryLabel = dialog.secondaryLabel,
            onSecondary = { onEvent(DialogEvent.ConfirmSecondary) },
            busy = dialog.busy,
        )
        is ReportDialog.TemplatePicker -> TemplatePickerDialog(dialog, onEvent)
        is ReportDialog.DraftName -> DraftNameDialog(dialog, onEvent)
        is ReportDialog.SendPicker -> SendPickerDialog(state, dialog, onEvent)
        is ReportDialog.Publish -> PublishDialog(state, dialog, onEvent)
        is ReportDialog.Comments -> CommentsDialog(state, dialog, onEvent)
        is ReportDialog.History -> HistoryDialog(dialog, dismiss)
        is ReportDialog.Approve -> ApproveDialog(state, dialog, onEvent)
        is ReportDialog.Reject -> RejectDialog(state, dialog, onEvent)
        is ReportDialog.ReminderCompose -> ReminderComposeDialog(state, dialog, onEvent)
        is ReportDialog.Reminders -> RemindersDialog(state, dialog, dismiss)
        is ReportDialog.SendForChat -> SendForChatDialog(state, dialog, onEvent)
        is ReportDialog.DocDistConfirm -> ConfirmModal(
            title = str(S.dd_publish_confirm_title),
            message = str(S.desktop_pr_publish_to_dd_message, dialog.fileName),
            confirmLabel = str(S.publish),
            danger = false,
            onConfirm = { onEvent(WorkflowEvent.ConfirmDocDist) },
            onCancel = dismiss,
        )
        is ReportDialog.DocDistDone -> DocDistDoneDialog(dialog, dismiss)
    }
}

/** "History" / "Approval History" — newest first, one bordered line per event. */
@Composable
private fun HistoryDialog(dialog: ReportDialog.History, onClose: () -> Unit) {
    val colors = ReportTheme.colors
    ReportModal(dialog.title, onClose, width = 760.dp) {
        if (dialog.entries.isEmpty()) {
            ReportEmptyState(dialog.emptyText, bordered = false, minHeight = 180.dp)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dialog.entries.forEach { entry ->
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(6.dp)).padding(10.dp),
                    ) {
                        Text(entry.by, style = reportText(14.sp, FontWeight.Medium), color = colors.textPrimary)
                        if (entry.role.isNotBlank()) Text(
                            entry.role,
                            style = reportText(12.sp),
                            color = colors.textMeta,
                        )
                        Text(
                            entry.metaLine { formatDateTime(it) },
                            style = reportText(12.sp),
                            color = colors.textMeta,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        if (entry.recipients.size > 1) {
                            Text(
                                "To: ${entry.recipients.joinToString(", ")}",
                                style = reportText(12.sp),
                                color = colors.textMeta,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                        if (entry.message.isNotBlank()) {
                            Text(
                                "Message: ${entry.message}",
                                style = reportText(12.sp),
                                color = androidx.compose.ui.graphics.Color(0xFF8A4A00),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        if (entry.reason.isNotBlank()) {
                            Text(
                                "Reason: ${entry.reason}",
                                style = reportText(12.sp),
                                color = colors.red,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Reminder" / "Reminders (n)" — who reminded, when, and what they said. The
 * sender is resolved from the crew by id (`getReminderSender`) so a renamed
 * or re-titled person reads right on an old reminder; the designation is a
 * label key, translated for this reader.
 */
@Composable
private fun RemindersDialog(state: ReportUiState, dialog: ReportDialog.Reminders, onClose: () -> Unit) {
    val colors = ReportTheme.colors
    val title = if (dialog.reminders.size > 1) {
        str(S.desktop_reminders_n, dialog.reminders.size)
    } else {
        str(S.reminder)
    }
    ReportModal(title, onClose) {
        if (dialog.reminders.isEmpty()) {
            Text(str(S.desktop_no_reminders_yet), style = reportText(14.sp), color = colors.textMeta)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            dialog.reminders.forEach { reminder ->
                val sender = reminderSender(state.members, reminder)
                Column {
                    Text(
                        sender.name.ifBlank { "-" },
                        style = reportText(14.sp, FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                    if (sender.role.isNotBlank()) Text(
                        Labels.translate(sender.role),
                        style = reportText(12.sp),
                        color = colors.textMeta,
                    )
                    Text(
                        formatDateTime(reminder.createdOn),
                        style = reportText(12.sp),
                        color = colors.textMeta,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    Text(
                        reminder.message,
                        style = reportText(14.sp),
                        color = colors.textPrimary,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(colors.elevated)
                            .border(1.dp, colors.border, RoundedCornerShape(4.dp)).padding(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * "Send for Chat" (ZL-21415) — `SendForChatModal`: search + single-select
 * rows, everyone but the sender; the chosen member receives the PDF in a
 * 1:1 chat. The footer disables while the PDF uploads.
 */
@Composable
@Suppress("LongMethod") // The picker reads top to bottom in layout order: helper line, search, list, footer.
private fun SendForChatDialog(state: ReportUiState, dialog: ReportDialog.SendForChat, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    val candidates = state.members.filter { it.userId.isNotBlank() && it.userId != state.me }
    val query = dialog.search.trim().lowercase()
    val filtered = candidates.filter { member ->
        query.isEmpty() || listOf(member.fullName, member.designation, member.department)
            .any { it.lowercase().contains(query) }
    }
    val close = { if (!dialog.sending) onEvent(DialogEvent.Dismiss) }
    ReportModal(str(S.cs_action_send_for_chat), close, scrollable = false) {
        Column(
            Modifier.fillMaxWidth().height(CHAT_PICKER_HEIGHT.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                Text(
                    str(S.desktop_pr_send_for_chat_hint),
                    style = reportText(14.sp),
                    color = colors.textSecondary,
                )
                if (dialog.report.name.isNotBlank()) Text(
                    " ${dialog.report.name}",
                    style = reportText(14.sp, FontWeight.Medium),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
            }
            ReportInput(
                value = dialog.search,
                onChange = { onEvent(ListEvent.SearchChatRecipient(it)) },
                placeholder = str(S.desktop_search_by_name_role_department),
                leadingIcon = ZillitIcons.Search,
                autoFocus = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                    .zillitVerticalScroll(rememberScrollState()),
            ) {
                if (filtered.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                        Text(str(S.desktop_no_members_found), style = reportText(14.sp), color = colors.textSecondary)
                    }
                }
                filtered.forEach { member ->
                    ChatRecipientRow(member, selected = member.userId == dialog.selected) {
                        onEvent(ListEvent.PickChatRecipient(member.userId))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ReportButton(str(S.cancel), close, kind = ButtonKind.Ghost, height = 40.dp, enabled = !dialog.sending)
                ReportButton(
                    if (dialog.sending) str(S.dd_busy_sending) else str(S.send),
                    { onEvent(ListEvent.ConfirmSendForChat) },
                    enabled = dialog.selected != null && !dialog.sending,
                    height = 40.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
            }
        }
    }
}

private const val CHAT_PICKER_HEIGHT = 520

@Composable
private fun ChatRecipientRow(member: SheetMember, selected: Boolean, onPick: () -> Unit) {
    val colors = ReportTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth()
            .background(
                when {
                    selected -> colors.accent.copy(alpha = 0.08f)
                    hovered -> colors.elevated
                    else -> Color.Transparent
                },
            )
            .hoverable(source)
            .plainClick(source = source, onClick = onPick)
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
        Box(
            Modifier.size(16.dp).clip(CircleShape)
                .border(2.dp, if (selected) colors.accent else colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.accent))
        }
    }
}

/** "Published." — the file named, as the web's success popup does (ZL-19833). */
@Composable
private fun DocDistDoneDialog(dialog: ReportDialog.DocDistDone, onClose: () -> Unit) {
    val colors = ReportTheme.colors
    ReportModal(str(S.pr_published), onClose, width = 440.dp) {
        Column(
            Modifier.fillMaxWidth().heightIn(min = 120.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(colors.greenBg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(ZillitIcons.Check, contentDescription = null, tint = colors.green, modifier = Modifier.size(22.dp))
            }
            Text(
                "\"${dialog.fileName}\" was added to Document Distribution.",
                style = reportText(14.sp),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            ReportButton(str(S.ok), onClose, kind = ButtonKind.Accent)
        }
    }
}
