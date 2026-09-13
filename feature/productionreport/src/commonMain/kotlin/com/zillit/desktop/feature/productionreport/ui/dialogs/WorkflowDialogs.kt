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
import com.zillit.desktop.feature.productionreport.domain.SheetMember
import com.zillit.desktop.feature.productionreport.ui.DialogEvent
import com.zillit.desktop.feature.productionreport.ui.PublishDestination
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
                "Save As",
                style = reportText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Text(
                "Draft Name",
                style = reportText(12.sp),
                color = colors.textSecondary,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            ReportInput(
                value = dialog.name,
                onChange = { onEvent(DialogEvent.EditDraftName(it)) },
                placeholder = "Enter draft name",
                autoFocus = true,
                radius = 4.dp,
                onEnter = { onEvent(DialogEvent.ConfirmDraftName) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                ReportButton("Cancel", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost)
                ReportButton("Confirm", { onEvent(DialogEvent.ConfirmDraftName) }, enabled = dialog.name.isNotBlank())
            }
        }
    }
}

/** Send for comments: the chooser, the recipient picker, or the removal prompt. */
@Composable
internal fun SendPickerDialog(state: ReportUiState, dialog: ReportDialog.SendPicker, onEvent: (ReportEvent) -> Unit) {
    val removal = dialog.pendingRemoval
    when {
        removal != null -> RemovalPrompt(removal, onEvent)
        dialog.choosing -> ReportModal("Send for Approval", { onEvent(DialogEvent.Dismiss) }) {
            Text(
                "Choose how to send this production report for approval:",
                style = reportText(14.sp),
                color = ReportTheme.colors.textSecondary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ReportButton(
                    "For Comments",
                    { onEvent(WorkflowEvent.ChooseComments) },
                    Modifier.weight(1f),
                    radius = 12.dp,
                    fontSize = 14.sp,
                    height = 40.dp,
                )
                ReportButton(
                    "For Signature",
                    { onEvent(WorkflowEvent.ChooseSignature) },
                    Modifier.weight(1f),
                    radius = 12.dp,
                    fontSize = 14.sp,
                    height = 40.dp,
                )
            }
            ReportButton(
                "Cancel",
                { onEvent(DialogEvent.Dismiss) },
                Modifier.fillMaxWidth().padding(top = 8.dp),
                kind = ButtonKind.Ghost,
            )
        }
        else -> RecipientPickerDialog(state, dialog, onEvent)
    }
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
    ReportModal("Select Recipients for Comments", { onEvent(DialogEvent.Dismiss) }, scrollable = false) {
        Column(Modifier.fillMaxWidth().height(540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReportInput(
                value = dialog.search,
                onChange = { onEvent(WorkflowEvent.SearchRecipients(it)) },
                placeholder = "Search by name, role, department...",
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
                    if (count == selectable.size && selectable.isNotEmpty()) "Deselect All" else "Select All",
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
                        Text("No members found", style = reportText(14.sp), color = colors.textSecondary)
                    }
                }
                if (previous.isNotEmpty()) SectionHeader("Previously Selected")
                previous.forEach { member ->
                    MemberRow(member, member.userId in dialog.selected) {
                        onEvent(WorkflowEvent.ToggleRecipient(member.userId))
                    }
                }
                if (others.isNotEmpty()) SectionHeader("All Members")
                others.forEach { member ->
                    MemberRow(member, member.userId in dialog.selected) {
                        onEvent(WorkflowEvent.ToggleRecipient(member.userId))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ReportButton(
                    "Back",
                    { onEvent(if (dialog.fromEditor) WorkflowEvent.BackToChooser else DialogEvent.Dismiss) },
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
    ReportModal("Remove from comments?", { onEvent(WorkflowEvent.CancelRemoval) }, width = 720.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                removed.joinToString(", ") { it.fullName },
                style = reportText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Text(
                "This user will stop receiving comment notifications on all production reports in this project.",
                style = reportText(14.sp),
                color = colors.textSecondary,
            )
            Text(
                "Do you also want to remove their viewing access to Drafts Production Report / " +
                    "Production Report Creation? Users who are still approvers keep their access either way.",
                style = reportText(14.sp),
                color = colors.textSecondary,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                ReportButton(
                    "Cancel",
                    { onEvent(WorkflowEvent.CancelRemoval) },
                    kind = ButtonKind.Ghost,
                    height = 40.dp,
                )
                ReportButton(
                    "Remove from comments only",
                    { onEvent(WorkflowEvent.FinishSend(false)) },
                    kind = ButtonKind.Outline,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
                ReportButton(
                    "Remove comments and viewing access",
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
 * Continuation (keeps earlier chat posts) or New (replaces them). The web's
 * card wording said the opposite of what it did — this reads as it acts.
 */
@Composable
internal fun PublishDialog(state: ReportUiState, dialog: ReportDialog.Publish, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    ReportModal("Publish Production Report", { onEvent(DialogEvent.Dismiss) }) {
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
            ReportButton("Cancel", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun DestinationStep(state: ReportUiState, dialog: ReportDialog.Publish, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Text(
        "Where would you like to publish this Production Report?",
        style = reportText(15.sp, FontWeight.Medium),
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Where should it go?",
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
        text = if (dialog.destination == PublishDestination.DocDist) "Publish to Document Distribution" else "Continue",
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
        if (blocked) "Needs Document Distribution posting rights" else hint,
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
            if (blocked) Text("no rights", style = reportText(10.sp), color = colors.textMuted)
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
        "How would you like to publish this Production Report?",
        style = reportText(15.sp, FontWeight.Medium),
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TypeCard(
            "Continuation",
            "Keep the existing report in the chat and add this version alongside it.",
            dialog.continuation == true,
        ) {
            onEvent(WorkflowEvent.PickContinuation(true))
        }
        TypeCard("New", "Replace the existing report in the chat with this version.", dialog.continuation == false) {
            onEvent(WorkflowEvent.PickContinuation(false))
        }
    }
    val label = when (dialog.continuation) {
        true -> "Publish as Continuation"
        false -> "Publish as New"
        null -> "Select an option to publish"
    }
    ReportButton(
        text = label,
        onClick = { onEvent(WorkflowEvent.ConfirmPublish) },
        modifier = Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 8.dp),
        enabled = dialog.continuation != null && !state.busy,
        radius = 12.dp,
        height = 48.dp,
        fontSize = 14.sp,
    )
}

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
    ReportModal("Reject Production Report", { onEvent(DialogEvent.Dismiss) }) {
        Text(
            "Reason for rejection (optional):",
            style = reportText(14.sp),
            color = ReportTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ReportInput(
            value = dialog.reason,
            onChange = { onEvent(WorkflowEvent.EditRejectReason(it)) },
            placeholder = "Reason...",
            singleLine = false,
            minLines = 3,
            radius = 4.dp,
            autoFocus = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportButton(
                "Reject",
                { onEvent(WorkflowEvent.ConfirmReject) },
                kind = ButtonKind.Danger,
                enabled = !state.busy,
                radius = 12.dp,
                height = 40.dp,
                fontSize = 14.sp,
            )
            ReportButton("Cancel", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, height = 40.dp)
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
    ReportModal("Send Reminder", { onEvent(DialogEvent.Dismiss) }) {
        Text(
            "Write a message to send along with the reminder:",
            style = reportText(14.sp),
            color = ReportTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ReportInput(
            value = dialog.message,
            onChange = { onEvent(WorkflowEvent.EditReminder(it)) },
            placeholder = "Please review and approve this production report.",
            singleLine = false,
            minLines = 3,
            radius = 4.dp,
            autoFocus = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportButton(
                "Send Reminder",
                { onEvent(WorkflowEvent.ConfirmReminder) },
                enabled = !state.busy,
                radius = 12.dp,
                height = 40.dp,
                fontSize = 14.sp,
            )
            ReportButton("Cancel", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, height = 40.dp)
        }
    }
}
