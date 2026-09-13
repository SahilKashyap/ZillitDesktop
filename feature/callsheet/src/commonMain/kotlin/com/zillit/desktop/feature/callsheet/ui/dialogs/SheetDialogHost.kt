// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("CyclomaticComplexMethod", "LongMethod")

package com.zillit.desktop.feature.callsheet.ui.dialogs

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.ApprovalStatusEntry
import com.zillit.desktop.feature.callsheet.domain.HistoryEntry
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.WorkflowEvent
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.ConfirmModal
import com.zillit.desktop.feature.callsheet.ui.components.Face
import com.zillit.desktop.feature.callsheet.ui.components.ModalScrim
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetEmptyState
import com.zillit.desktop.feature.callsheet.ui.components.SheetModal
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.components.swallowClicks
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/** Shows whichever dialog the state holds. */
@Composable
internal fun SheetDialogHost(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: () -> Long) {
    val dismiss = { onEvent(DialogEvent.Dismiss) }
    when (val dialog = state.dialog) {
        null -> Unit
        is SheetDialog.Confirm -> ConfirmModal(
            title = dialog.title,
            message = dialog.message,
            confirmLabel = dialog.confirmLabel,
            danger = dialog.danger,
            onConfirm = { onEvent(DialogEvent.Confirm) },
            onCancel = dismiss,
            secondaryLabel = dialog.secondaryLabel,
            onSecondary = { onEvent(DialogEvent.ConfirmSecondary) },
        )
        is SheetDialog.TemplatePicker -> TemplatePickerDialog(dialog, onEvent)
        is SheetDialog.DraftName -> DraftNameDialog(dialog, onEvent)
        is SheetDialog.MissingTitles -> MissingTitlesDialog(dialog, onEvent)
        is SheetDialog.SendPicker -> SendPickerDialog(state, dialog, onEvent)
        is SheetDialog.ChatSend -> SendForChatDialog(state, dialog, onEvent)
        is SheetDialog.Publish -> PublishDialog(state, dialog, onEvent)
        is SheetDialog.AttachDocument -> AttachDocumentDialog(dialog, onEvent)
        is SheetDialog.Comments -> CommentsDialog(state, dialog, onEvent)
        is SheetDialog.History -> HistoryDialog(dialog, dismiss)
        is SheetDialog.ApprovalStatus -> ApprovalStatusDialog(dialog, dismiss)
        is SheetDialog.Approve -> ApproveDialog(state, dialog, onEvent, nowMillis)
        is SheetDialog.Reject -> RejectDialog(state, dialog, onEvent)
        is SheetDialog.ReminderCompose -> ReminderComposeDialog(state, dialog, onEvent)
        is SheetDialog.ReminderView -> ReminderViewDialog(state, dialog, dismiss)
        is SheetDialog.ChatPicker -> ChatPickerDialog(state, dialog, onEvent)
        is SheetDialog.DocDistConfirm -> ConfirmModal(
            title = "Publish to Document Distribution",
            message = "Publish \"${dialog.fileName}\" to the Document Distribution library?",
            confirmLabel = "Publish",
            danger = false,
            onConfirm = { onEvent(WorkflowEvent.ConfirmDocDist) },
            onCancel = dismiss,
        )
        is SheetDialog.DocDistDone -> DocDistDoneDialog(dialog, dismiss)
    }
}

// History ---------------------------------------------------------------------------------------------------

/**
 * `HistoryModal`: a timeline, newest first — a tinted disc per action on a
 * hairline, the person, the action pill, the revision, and any message or
 * reason. Reminders keep their bell (the web's "sent" match stole it).
 */
@Composable
private fun HistoryDialog(dialog: SheetDialog.History, onClose: () -> Unit) {
    SheetModal(dialog.title, onClose, width = 760.dp) {
        if (dialog.entries.isEmpty()) {
            SheetEmptyState(dialog.emptyText, bordered = false, minHeight = 160.dp)
            return@SheetModal
        }
        Column {
            dialog.entries.forEachIndexed { index, entry ->
                TimelineEntry(entry, last = index == dialog.entries.lastIndex)
            }
        }
    }
}

@Composable
private fun TimelineEntry(entry: HistoryEntry, last: Boolean) {
    val colors = SheetTheme.colors
    val meta = actionMeta(entry.action)
    val rail = colors.border
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).drawBehind {
            // The timeline's hairline runs under every disc but the last.
            if (!last) {
                val x = 19.dp.toPx()
                drawLine(rail, Offset(x, 38.dp.toPx()), Offset(x, size.height), 1.dp.toPx())
            }
        },
    ) {
        Box(Modifier.width(38.dp)) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(meta.background(colors.isDark)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    meta.icon,
                    contentDescription = null,
                    tint = meta.tint(colors.isDark),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp, bottom = if (last) 0.dp else 20.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Face(entry.userId, entry.by, 36.dp, Modifier.padding(top = 2.dp))
                Column(Modifier.weight(1f)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(entry.by, style = sheetText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
                        Text(
                            (if (entry.stage.isNotBlank()) "${entry.stage} · " else "") + meta.label(entry.action),
                            style = sheetText(10.sp, FontWeight.Medium),
                            color = meta.tint(colors.isDark),
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(meta.background(colors.isDark))
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                        if (entry.revisionText.isNotBlank()) {
                            Text(
                                entry.revisionText,
                                style = sheetText(10.sp, FontWeight.Medium),
                                color = colors.textTertiary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.sunken)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (entry.role.isNotBlank()) {
                        Text(
                            entry.role.localised(),
                            style = sheetText(12.sp),
                            color = colors.textTertiary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    Text(
                        formatDateTime(entry.atMillis),
                        style = sheetText(11.sp),
                        color = colors.textMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (entry.message.isNotBlank()) {
                        Text(
                            entry.message,
                            style = sheetText(12.sp).copy(fontStyle = FontStyle.Italic),
                            color = colors.textSecondary,
                            modifier = Modifier
                                .padding(top = 8.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.elevated)
                                .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    if (entry.reason.isNotBlank()) {
                        ReasonBox(label = "Reason:", reason = entry.reason)
                    }
                }
            }
            if (!last) {
                Box(Modifier.padding(top = 20.dp).fillMaxWidth().height(1.dp).background(colors.borderFaint))
            }
        }
    }
}

@Composable
private fun ReasonBox(label: String, reason: String) {
    val colors = SheetTheme.colors
    Row(
        Modifier
            .padding(top = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.redBg)
            .border(1.dp, colors.redBorder.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = sheetText(12.sp, FontWeight.Medium), color = colors.red)
        Text(reason, style = sheetText(12.sp), color = colors.red)
    }
}

/** `getActionMeta`, with reminders checked before "sent". */
private class ActionMeta(
    val icon: ImageVector,
    private val light: Pair<Color, Color>,
    private val dark: Pair<Color, Color>,
    private val fixedLabel: String?,
) {
    fun tint(isDark: Boolean) = if (isDark) dark.first else light.first
    fun background(isDark: Boolean) = if (isDark) dark.second else light.second
    fun label(action: String) = fixedLabel ?: action
}

@Suppress("MagicNumber") // The web's action colours, as literals.
private fun actionMeta(action: String): ActionMeta {
    val lower = action.lowercase()
    return when {
        "approved" in lower -> ActionMeta(
            ZillitIcons.Check,
            Color(0xFF12B76A) to Color(0xFFECFDF3),
            Color(0xFF34D399) to Color(0x2E067647),
            "Approved",
        )
        "rejected" in lower -> ActionMeta(
            ZillitIcons.Close,
            Color(0xFFF04438) to Color(0xFFFEF3F2),
            Color(0xFFFDA29B) to Color(0x26F04438),
            "Rejected",
        )
        "reminder" in lower -> ActionMeta(
            ZillitIcons.Bell,
            Color(0xFFB54708) to Color(0xFFFFF7ED),
            Color(0xFFFDB022) to Color(0x2EF79009),
            "Reminder Sent",
        )
        "sent" in lower || "signature" in lower || "comment" in lower -> ActionMeta(
            ZillitIcons.Send,
            Color(0xFF175CD3) to Color(0xFFEFF8FF),
            Color(0xFF60A5FA) to Color(0x2E175CD3),
            null,
        )
        "created" in lower -> ActionMeta(
            SheetIcons.PlusCircle,
            Color(0xFF667085) to Color(0xFFF2F4F7),
            Color(0x99FFFFFF) to Color(0x14FFFFFF),
            "Created",
        )
        "updated" in lower -> ActionMeta(
            ZillitIcons.Edit,
            Color(0xFF6941C6) to Color(0xFFF4F3FF),
            Color(0xFFB692F6) to Color(0x2E6941C6),
            "Updated",
        )
        else -> ActionMeta(
            ZillitIcons.Edit,
            Color(0xFF667085) to Color(0xFFF2F4F7),
            Color(0x99FFFFFF) to Color(0x14FFFFFF),
            null,
        )
    }
}

// Approval status --------------------------------------------------------------------------------------------

/** `ApprovalStatusModal`: the round's progress, each approver's state, and any reasons. */
@Composable
private fun ApprovalStatusDialog(dialog: SheetDialog.ApprovalStatus, onClose: () -> Unit) {
    val colors = SheetTheme.colors
    SheetModal(dialog.title, onClose, width = 560.dp) {
        val entries = dialog.entries
        if (entries.isEmpty()) {
            Text(
                "No approval requests found.",
                style = sheetText(14.sp),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
            )
            return@SheetModal
        }
        val approved = entries.count { it.status == "APPROVED" }
        val progress by animateFloatAsState(approved.toFloat() / entries.size, tween(PROGRESS_MS))
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("$approved/${entries.size} approved", style = sheetText(12.sp), color = colors.textTertiary)
            Box(
                Modifier.weight(1f).padding(horizontal = 12.dp).height(6.dp).clip(CircleShape)
                    .background(colors.sunken),
            ) {
                Box(Modifier.fillMaxWidth(progress).height(6.dp).background(Color(0xFF12B76A)))
            }
            Text(
                entries.first().stage.ifBlank { "Final" },
                style = sheetText(12.sp, FontWeight.Medium),
                color = colors.textPrimary,
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(
                1.dp,
                colors.border,
                RoundedCornerShape(12.dp),
            ),
        ) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderFaint))
                StatusRow(entry)
            }
        }
        val reasons = entries.filter { it.reason.isNotBlank() }
        if (reasons.isNotEmpty()) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                reasons.forEach { entry ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.redBg)
                            .border(1.dp, colors.redBorder.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            ZillitIcons.Close,
                            contentDescription = null,
                            tint = Color(0xFFF04438),
                            modifier = Modifier.size(14.dp),
                        )
                        Column {
                            Text(entry.name, style = sheetText(12.sp, FontWeight.SemiBold), color = colors.red)
                            Text(
                                entry.reason,
                                style = sheetText(12.sp),
                                color = if (colors.isDark) colors.red else Color(0xFF912018),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val PROGRESS_MS = 150

@Suppress("MagicNumber") // The web's status chip colours, as literals.
@Composable
private fun StatusRow(entry: ApprovalStatusEntry) {
    val colors = SheetTheme.colors
    val dark = colors.isDark
    data class Chip(val icon: ImageVector, val ink: Color, val fill: Color, val rim: Color, val label: String)
    val chip = when (entry.status) {
        "APPROVED" -> Chip(
            ZillitIcons.Check,
            if (dark) Color(0xFF34D399) else Color(0xFF12B76A),
            if (dark) Color(0x2E067647) else Color(0xFFECFDF3),
            if (dark) Color(0x6634D399) else Color(0xFFA6F4C5),
            "Approved",
        )
        "REJECTED" -> Chip(
            ZillitIcons.Close,
            if (dark) Color(0xFFFDA29B) else Color(0xFFF04438),
            if (dark) Color(0x26F04438) else Color(0xFFFEF3F2),
            if (dark) Color(0x66FDA29B) else Color(0xFFFDA29B),
            "Rejected",
        )
        else -> Chip(
            ZillitIcons.Clock,
            if (dark) Color(0xFFFDB022) else Color(0xFFF79009),
            if (dark) Color(0x2EF79009) else Color(0xFFFFFAEB),
            if (dark) Color(0x66FDB022) else Color(0xFFFEDF89),
            "Pending",
        )
    }
    Row(
        Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Face(entry.userId, entry.name, 40.dp)
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = sheetText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                entry.role.localised(),
                style = sheetText(12.sp),
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(chip.fill)
                    .border(1.dp, chip.rim, CircleShape)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(chip.icon, contentDescription = null, tint = chip.ink, modifier = Modifier.size(11.dp))
                Text(chip.label, style = sheetText(11.sp, FontWeight.Medium), color = chip.ink)
            }
            Text(formatDateTime(entry.atMillis), style = sheetText(10.sp), color = colors.textMuted)
        }
    }
}

// Reminder, chat, Document Distribution --------------------------------------------------------------------------

/** "Reminder" — who reminded (by id, then name — the web printed the raw id), when, and the message. */
@Composable
private fun ReminderViewDialog(state: SheetUiState, dialog: SheetDialog.ReminderView, onClose: () -> Unit) {
    val colors = SheetTheme.colors
    val reminder = dialog.reminder
    val member = state.member(reminder.sentById)
    SheetModal("Reminder", onClose) {
        Row(
            Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Face(reminder.sentById, member?.fullName ?: reminder.sentBy, 36.dp)
            Column {
                Text(
                    member?.fullName ?: reminder.sentBy.ifBlank { "-" },
                    style = sheetText(14.sp, FontWeight.Medium),
                    color = colors.textPrimary,
                )
                val role = member?.designation?.ifBlank { null } ?: reminder.sentByRole
                if (role.isNotBlank()) Text(role.localised(), style = sheetText(12.sp), color = colors.textMeta)
            }
        }
        Text(
            formatDateTime(reminder.createdOn),
            style = sheetText(12.sp),
            color = colors.textMeta,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            reminder.message.ifBlank { "You have a pending call sheet approval. Please review it." },
            style = sheetText(14.sp),
            color = colors.textPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(colors.elevated)
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .padding(12.dp),
        )
    }
}

/** "Chat with Approver" / "Chat with Creator": a Chat button per person. */
@Composable
private fun ChatPickerDialog(state: SheetUiState, dialog: SheetDialog.ChatPicker, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    SheetModal(dialog.title, { onEvent(DialogEvent.Dismiss) }) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            dialog.userIds.forEach { id ->
                val member = state.member(id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Face(id, member?.fullName ?: "-", 32.dp)
                    Column(Modifier.weight(1f)) {
                        Text(
                            member?.fullName ?: "-",
                            style = sheetText(14.sp, FontWeight.Medium),
                            color = colors.textPrimary,
                            maxLines = 1,
                        )
                        member?.designation?.takeIf { it.isNotBlank() }?.let {
                            Text(it.localised(), style = sheetText(12.sp), color = colors.textMeta, maxLines = 1)
                        }
                    }
                    SheetButton(
                        "Chat",
                        { onEvent(ListEvent.ChatWith(id)) },
                        kind = ButtonKind.Warning,
                        icon = ZillitIcons.Chat,
                        fontSize = 12.sp,
                        height = 30.dp,
                    )
                }
            }
        }
    }
}

/** "Published." — the file named, as the web's success popup does. */
@Composable
private fun DocDistDoneDialog(dialog: SheetDialog.DocDistDone, onClose: () -> Unit) {
    val colors = SheetTheme.colors
    SheetModal("Published.", onClose, width = 440.dp) {
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
                style = sheetText(14.sp),
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            SheetButton("OK", onClose, kind = ButtonKind.Accent)
        }
    }
}

// Editor dialogs -------------------------------------------------------------------------------------------------

/**
 * "Section name required" — every default section saved nameless, with its
 * first value as a hint. A row selects that section (the web promised a Fix
 * and had none).
 */
@Composable
private fun MissingTitlesDialog(dialog: SheetDialog.MissingTitles, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val count = dialog.items.size
    ModalScrim(onDismiss = null, onEscape = { onEvent(DialogEvent.Dismiss) }) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth(0.95f)
                .shadow(28.dp, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface)
                .swallowClicks()
                .padding(24.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(colors.redBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        ZillitIcons.Warning,
                        contentDescription = null,
                        tint = Color(0xFFF04438),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column {
                    Text(
                        "Section name required",
                        style = sheetText(16.sp, FontWeight.SemiBold),
                        color = colors.textPrimary,
                    )
                    Text(
                        if (count == 1) {
                            "1 default section is missing a name. Add a name before saving."
                        } else {
                            "$count default sections are missing a name. Add names before saving."
                        },
                        style = sheetText(12.sp, lineHeight = 18.sp),
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Column(Modifier.heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                dialog.items.forEach { item ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (colors.isDark) colors.redBg else Color(0xFFFFFBFA))
                            .border(1.dp, colors.redBorder, RoundedCornerShape(8.dp))
                            .plainClick { onEvent(DialogEvent.FixMissingTitle(item)) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text("Untitled section", style = sheetText(12.sp, FontWeight.SemiBold), color = colors.red)
                        if (item.hint.isNotBlank()) {
                            Row(Modifier.padding(top = 2.dp)) {
                                Text("First field: ", style = sheetText(12.sp), color = colors.textTertiary)
                                Text(
                                    item.hint,
                                    style = sheetText(12.sp),
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.End) {
                SheetButton("Close", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, fontSize = 14.sp)
            }
        }
    }
}
