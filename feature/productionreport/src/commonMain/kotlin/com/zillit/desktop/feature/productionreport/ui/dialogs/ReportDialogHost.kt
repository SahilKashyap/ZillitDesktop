// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("CyclomaticComplexMethod")

package com.zillit.desktop.feature.productionreport.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.productionreport.domain.formatDateTime
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
import com.zillit.desktop.feature.productionreport.ui.components.ReportModal
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
        is ReportDialog.Reminders -> RemindersDialog(dialog, dismiss)
        is ReportDialog.ChatPicker -> ChatPickerDialog(state, dialog, onEvent)
        is ReportDialog.DocDistConfirm -> ConfirmModal(
            title = "Publish to Document Distribution",
            message = "Publish \"${dialog.fileName}\" to the Document Distribution library?",
            confirmLabel = "Publish",
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

/** "Reminder" / "Reminders (n)" — who reminded, when, and what they said. */
@Composable
private fun RemindersDialog(dialog: ReportDialog.Reminders, onClose: () -> Unit) {
    val colors = ReportTheme.colors
    val title = if (dialog.reminders.size > 1) "Reminders (${dialog.reminders.size})" else "Reminder"
    ReportModal(title, onClose) {
        if (dialog.reminders.isEmpty()) {
            Text("No reminders yet.", style = reportText(14.sp), color = colors.textMeta)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            dialog.reminders.forEach { reminder ->
                Column {
                    Text(
                        reminder.sentBy.ifBlank { "-" },
                        style = reportText(14.sp, FontWeight.Medium),
                        color = colors.textPrimary,
                    )
                    if (reminder.sentByRole.isNotBlank()) Text(
                        reminder.sentByRole,
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

/** "Chat with Approver" / "Chat with Creator": a Chat button per person. */
@Composable
private fun ChatPickerDialog(state: ReportUiState, dialog: ReportDialog.ChatPicker, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    ReportModal(dialog.title, { onEvent(DialogEvent.Dismiss) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            dialog.userIds.forEach { id ->
                val member = state.member(id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Face(id, member?.fullName ?: "-", 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            member?.fullName ?: "-",
                            style = reportText(14.sp, FontWeight.Medium),
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                        member?.designation?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = reportText(12.sp), color = colors.textMeta, maxLines = 1)
                        }
                    }
                    ReportButton(
                        "Chat",
                        { onEvent(ListEvent.ChatWith(id)) },
                        kind = ButtonKind.Warning,
                        icon = ZillitIcons.Chat,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

/** "Published." — the file named, as the web's success popup does (ZL-19833). */
@Composable
private fun DocDistDoneDialog(dialog: ReportDialog.DocDistDone, onClose: () -> Unit) {
    val colors = ReportTheme.colors
    ReportModal("Published.", onClose, width = 440.dp) {
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
            ReportButton("OK", onClose, kind = ButtonKind.Accent)
        }
    }
}
