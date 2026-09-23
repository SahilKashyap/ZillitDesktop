// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package com.zillit.desktop.feature.callsheet.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.component.zillitVerticalScroll
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.callsheet.domain.SheetMember
import com.zillit.desktop.feature.callsheet.ui.DialogEvent
import com.zillit.desktop.feature.callsheet.ui.PublishChoice
import com.zillit.desktop.feature.callsheet.ui.PublishDestination
import com.zillit.desktop.feature.callsheet.ui.PublishStep
import com.zillit.desktop.feature.callsheet.ui.SheetDialog
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.WorkflowEvent
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.DISABLED_ALPHA
import com.zillit.desktop.feature.callsheet.ui.components.Face
import com.zillit.desktop.feature.callsheet.ui.components.FieldLabel
import com.zillit.desktop.feature.callsheet.ui.components.ModalScrim
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetCheckbox
import com.zillit.desktop.feature.callsheet.ui.components.SheetInput
import com.zillit.desktop.feature.callsheet.ui.components.SheetModal
import com.zillit.desktop.feature.callsheet.ui.components.SheetRadio
import com.zillit.desktop.feature.callsheet.ui.components.plainClick
import com.zillit.desktop.feature.callsheet.ui.components.rememberHover
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.components.swallowClicks
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/** "Save As" — a draft never saves without a name (ZL-20654). */
@Composable
internal fun DraftNameDialog(dialog: SheetDialog.DraftName, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    ModalScrim(onDismiss = null, onEscape = { onEvent(DialogEvent.Dismiss) }) {
        Column(
            Modifier.widthIn(max = 380.dp).fillMaxWidth(0.95f)
                .shadow(24.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                .background(colors.surface).swallowClicks().padding(24.dp),
        ) {
            Text(
                "Save As",
                style = sheetText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                modifier = Modifier.padding(bottom = 16.dp),
            )
            FieldLabel("Draft Name")
            SheetInput(
                value = dialog.name,
                onChange = { onEvent(DialogEvent.EditDraftName(it)) },
                placeholder = "Enter draft name",
                autoFocus = true,
                onEnter = { onEvent(DialogEvent.ConfirmDraftName) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                SheetButton("Cancel", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, fontSize = 14.sp)
                SheetButton(
                    "Confirm",
                    { onEvent(DialogEvent.ConfirmDraftName) },
                    kind = ButtonKind.Navy,
                    enabled = dialog.name.isNotBlank(),
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
            }
        }
    }
}

// Send for Comments ---------------------------------------------------------------------------------------------

/** "Send for Comments": the recipient picker, or the removal prompt that replaces it. */
@Composable
internal fun SendPickerDialog(state: SheetUiState, dialog: SheetDialog.SendPicker, onEvent: (SheetEvent) -> Unit) {
    val removal = dialog.pendingRemoval
    if (removal != null) {
        RemovalPrompt(state, removal, onEvent)
    } else {
        RecipientPickerDialog(state, dialog, onEvent)
    }
}

@Composable
private fun RecipientPickerDialog(state: SheetUiState, dialog: SheetDialog.SendPicker, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val selectable = selectableMembers(state)
    val filtered = filterMembers(selectable, dialog.search)
    val previous = filtered.filter { it.userId in dialog.initial }
    val others = filtered.filterNot { it.userId in dialog.initial }
    val count = dialog.selected.count { id -> selectable.any { it.userId == id } }
    val removed = selectable.count { it.userId in dialog.initial && it.userId !in dialog.selected }
    SheetModal("Send for Comments", { onEvent(DialogEvent.Dismiss) }, scrollable = false) {
        Column(Modifier.fillMaxWidth().height(PICKER_HEIGHT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchField(dialog.search) { onEvent(WorkflowEvent.SearchRecipients(it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$count", style = sheetText(12.sp, FontWeight.SemiBold), color = colors.textPrimary)
                Text(
                    " of ${selectable.size} selected",
                    style = sheetText(12.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                LinkText(if (count == selectable.size && selectable.isNotEmpty()) "Deselect All" else "Select All") {
                    onEvent(WorkflowEvent.ToggleAllRecipients)
                }
            }
            MemberList(Modifier.weight(1f), empty = filtered.isEmpty()) {
                if (previous.isNotEmpty()) SectionHeader("Previously Selected")
                previous.forEach { member ->
                    MemberRow(member, member.userId in dialog.selected, radio = false) {
                        onEvent(WorkflowEvent.ToggleRecipient(member.userId))
                    }
                }
                if (others.isNotEmpty()) SectionHeader("All Members")
                others.forEach { member ->
                    MemberRow(member, member.userId in dialog.selected, radio = false) {
                        onEvent(WorkflowEvent.ToggleRecipient(member.userId))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SheetButton(
                    "Cancel",
                    { onEvent(DialogEvent.Dismiss) },
                    kind = ButtonKind.Ghost,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
                Box(Modifier.weight(1f))
                SheetButton(
                    "Send ($count)",
                    { onEvent(WorkflowEvent.SendRecipients) },
                    enabled = (count > 0 || removed > 0) && !state.busy,
                    height = 40.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
            }
        }
    }
}

/** "Remove from comments?" — asked once before a send drops anyone who received comments last time. */
@Composable
private fun RemovalPrompt(state: SheetUiState, removed: List<SheetMember>, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    SheetModal("Remove from comments?", { onEvent(WorkflowEvent.CancelRemoval) }, width = 720.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                removed.mapNotNull { it.fullName.ifBlank { null } }.joinToString(", "),
                style = sheetText(14.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Text(
                "This user will stop receiving comment notifications on all call sheets in this project.",
                style = sheetText(14.sp),
                color = colors.textSecondary,
            )
            Text(
                "Do you also want to remove their viewing access to Drafts Call Sheet / Call Sheet Creation? " +
                    "Users who are still approvers keep their access either way.",
                style = sheetText(14.sp),
                color = colors.textSecondary,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SheetButton(
                    "Cancel",
                    { onEvent(WorkflowEvent.CancelRemoval) },
                    kind = ButtonKind.Ghost,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
                SheetButton(
                    "Remove from comments only",
                    { onEvent(WorkflowEvent.FinishSend(false)) },
                    kind = ButtonKind.Outline,
                    enabled = !state.busy,
                    height = 40.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
                SheetButton(
                    "Remove comments and viewing access",
                    { onEvent(WorkflowEvent.FinishSend(true)) },
                    kind = ButtonKind.Danger,
                    enabled = !state.busy,
                    height = 40.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
            }
        }
    }
}

// Send for Chat ------------------------------------------------------------------------------------------------------

/** "Send for Chat": one person; the call sheet's PDF lands in your one-to-one chat with them. */
@Composable
internal fun SendForChatDialog(state: SheetUiState, dialog: SheetDialog.ChatSend, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val candidates = selectableMembers(state)
    val filtered = filterMembers(candidates, dialog.search)
    SheetModal(
        "Send for Chat",
        { onEvent(DialogEvent.Dismiss) },
        scrollable = false,
        closeOnScrim = !dialog.sending,
    ) {
        Column(Modifier.fillMaxWidth().height(PICKER_HEIGHT), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Text(
                    "Shares this call sheet as a PDF in a 1:1 chat.",
                    style = sheetText(14.sp),
                    color = colors.textSecondary,
                )
                if (dialog.sheet.name.isNotBlank()) {
                    Text(
                        " ${dialog.sheet.name}",
                        style = sheetText(14.sp, FontWeight.Medium),
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SearchField(dialog.search) { onEvent(WorkflowEvent.SearchChatRecipients(it)) }
            MemberList(Modifier.weight(1f), empty = filtered.isEmpty()) {
                filtered.forEach { member ->
                    MemberRow(member, member.userId == dialog.selected, radio = true) {
                        onEvent(WorkflowEvent.PickChatRecipient(member.userId))
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SheetButton(
                    "Cancel",
                    { onEvent(DialogEvent.Dismiss) },
                    kind = ButtonKind.Ghost,
                    enabled = !dialog.sending,
                    height = 40.dp,
                    fontSize = 14.sp,
                )
                Box(Modifier.weight(1f))
                SheetButton(
                    if (dialog.sending) "Sending…" else "Send PDF",
                    { onEvent(WorkflowEvent.ConfirmSendForChat) },
                    enabled = dialog.selected != null && !dialog.sending,
                    height = 40.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 20.dp,
                )
            }
        }
    }
}

// Shared picker pieces ------------------------------------------------------------------------------------------------

private val PICKER_HEIGHT = 540.dp

private fun selectableMembers(state: SheetUiState): List<SheetMember> =
    state.members.filter { it.isAccepted && it.userId != state.me && it.userId.isNotBlank() }

/** The web's match: name, designation or department, case-insensitive. */
private fun filterMembers(members: List<SheetMember>, search: String): List<SheetMember> {
    val query = search.trim().lowercase()
    if (query.isEmpty()) return members
    return members.filter { member ->
        listOf(member.fullName, member.designation.localised(), member.department.localised())
            .any { it.lowercase().contains(query) }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    SheetInput(
        value = value,
        onChange = onChange,
        placeholder = "Search by name, role, department...",
        leadingIcon = ZillitIcons.Search,
        autoFocus = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Text(
        text,
        style = sheetText(12.sp, FontWeight.Medium).let {
            if (hovered) it.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) else it
        },
        color = SheetTheme.colors.accent,
        modifier = Modifier.hoverable(source).plainClick(source = source, onClick = onClick),
    )
}

@Composable
private fun MemberList(modifier: Modifier, empty: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val colors = SheetTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, colors.border, RoundedCornerShape(8.dp))
            .zillitVerticalScroll(rememberScrollState()),
    ) {
        if (empty) {
            Box(Modifier.fillMaxWidth().padding(vertical = 80.dp), contentAlignment = Alignment.Center) {
                Text("No members found", style = sheetText(14.sp), color = colors.textSecondary)
            }
        } else {
            content()
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = sheetText(10.sp, FontWeight.Bold).copy(letterSpacing = 0.5.sp),
        color = SheetTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .background(SheetTheme.colors.elevated)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** A whole-row toggle — the web's `<label>` rows: avatar, name, designation · department, and the box. */
@Composable
private fun MemberRow(member: SheetMember, checked: Boolean, radio: Boolean, onToggle: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
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
                style = sheetText(14.sp, FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOf(member.designation.localised(), member.department.localised())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(
                    sub,
                    style = sheetText(12.sp),
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (radio) SheetRadio(checked) else SheetCheckbox(checked)
    }
}

// Publish -------------------------------------------------------------------------------------------------------------

/**
 * "Publish Call Sheet": where it goes first; for the app, then Continuation
 * or New; "Attach a document instead" on both steps.
 */
@Composable
internal fun PublishDialog(state: SheetUiState, dialog: SheetDialog.Publish, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    SheetModal("Publish Call Sheet", { onEvent(DialogEvent.Dismiss) }) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 20.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(64.dp).clip(CircleShape).background(colors.accent), contentAlignment = Alignment.Center) {
                Icon(
                    SheetIcons.FileUpload,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
            when (dialog.step) {
                PublishStep.Destination -> DestinationStep(state, dialog, onEvent)
                PublishStep.Type -> TypeStep(state, dialog, onEvent)
            }
            LinkLine("Attach a document instead", colors.blue) { onEvent(WorkflowEvent.AttachInstead) }
            SheetButton("Cancel", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, fontSize = 14.sp)
        }
    }
}

@Composable
private fun LinkLine(text: String, color: Color, onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Text(
        text,
        style = sheetText(12.sp).let {
            if (hovered) it.copy(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline) else it
        },
        color = color,
        modifier = Modifier.padding(bottom = 8.dp).hoverable(source).plainClick(source = source, onClick = onClick),
    )
}

@Composable
private fun DestinationStep(state: SheetUiState, dialog: SheetDialog.Publish, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Text(
        "Where would you like to publish this Call Sheet?",
        style = sheetText(15.sp, FontWeight.Medium, 24.sp),
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
    )
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            "Where should it go?",
            style = sheetText(12.sp, FontWeight.SemiBold),
            color = colors.textSecondary,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        PublishDestination.entries.forEach { option ->
            val blocked = option.needsDocDist && !state.canDistribute
            val selected = dialog.destination == option && !blocked
            OptionRow(option.label, option.hint, selected, blocked) { onEvent(WorkflowEvent.PickDestination(option)) }
        }
    }
    SheetButton(
        text = if (dialog.destination == PublishDestination.DocDist) "Publish to Document Distribution" else "Continue",
        onClick = { onEvent(WorkflowEvent.ContinuePublish) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        radius = 12.dp,
        height = 46.dp,
        fontSize = 14.sp,
    )
}

@Composable
private fun OptionRow(label: String, hint: String, selected: Boolean, blocked: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    ZillitTooltip(if (blocked) "Needs Document Distribution posting rights" else hint) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (selected) colors.accentLight else Color.Transparent)
                .border(
                    2.dp,
                    when {
                        selected -> colors.accent
                        hovered && !blocked -> colors.borderStrong
                        else -> colors.border
                    },
                    RoundedCornerShape(8.dp),
                )
                .hoverable(source)
                .plainClick(enabled = !blocked, source = source, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SheetRadio(selected, size = 16.dp, enabled = !blocked)
            Text(
                label,
                style = sheetText(14.sp, if (selected) FontWeight.SemiBold else FontWeight.Normal),
                color = if (blocked) colors.textMuted else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).alpha(if (blocked) DISABLED_ALPHA + 0.2f else 1f),
            )
            if (blocked) Text("no rights", style = sheetText(10.sp), color = colors.textMuted)
        }
    }
}

@Composable
private fun TypeStep(state: SheetUiState, dialog: SheetDialog.Publish, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Text(
        "How would you like to publish this Call Sheet?",
        style = sheetText(15.sp, FontWeight.Medium, 24.sp),
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 20.dp, bottom = 24.dp),
    )
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TypeCard(
            "Continuation",
            "Post as a continuation — keeps the existing call sheet in the chat and adds this version alongside.",
            dialog.choice == PublishChoice.Continuation,
        ) { onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.Continuation)) }
        TypeCard(
            "New",
            "Replace the existing call sheet document in Home Callsheet with this new version.",
            dialog.choice == PublishChoice.New,
        ) { onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.New)) }
        // Hidden, not disabled, when there is nothing to swap: a first publish has no target.
        if (dialog.replaceTargets.isNotEmpty()) {
            TypeCard(
                "Replace",
                "Swap one existing document. It moves to History with its comments.",
                dialog.choice == PublishChoice.Replace,
            ) { onEvent(WorkflowEvent.PickPublishChoice(PublishChoice.Replace)) }
        }
    }
    if (dialog.choice == PublishChoice.Replace) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FieldLabel("Document to replace")
            dialog.replaceTargets.forEach { target ->
                OptionRow(
                    label = target.label,
                    hint = target.label,
                    selected = dialog.replaceChatId == target.chatId,
                    blocked = false,
                ) { onEvent(WorkflowEvent.PickReplaceTarget(target.chatId)) }
            }
        }
    }
    if (dialog.choice == PublishChoice.Continuation) {
        Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            FieldLabel("Publish Notes")
            SheetInput(
                value = dialog.notes,
                onChange = { onEvent(WorkflowEvent.EditPublishNotes(it)) },
                placeholder = "Briefly describe what changed in this version…",
                singleLine = false,
                minLines = 3,
                autoFocus = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    val label = dialog.choice?.let { "Publish as ${it.label}" } ?: "Select an option to publish"
    SheetButton(
        text = if (state.busy) "Publishing…" else label,
        onClick = { onEvent(WorkflowEvent.ConfirmPublish) },
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        enabled = dialog.canConfirm && !state.busy,
        radius = 12.dp,
        height = 46.dp,
        fontSize = 14.sp,
    )
    Text(
        "Back",
        style = sheetText(12.sp),
        color = colors.textTertiary,
        modifier = Modifier.padding(bottom = 8.dp).plainClick { onEvent(WorkflowEvent.BackToDestination) },
    )
}

@Composable
private fun TypeCard(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        Modifier.fillMaxWidth()
            .then(if (selected) Modifier.shadow(1.dp, RoundedCornerShape(12.dp)) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .background(
                when {
                    selected -> colors.accentLight
                    hovered -> if (colors.isDark) colors.accentTint else Color(0xFFFFFCF5)
                    else -> colors.surface
                },
            )
            .border(
                2.dp,
                when {
                    selected -> colors.accent
                    hovered -> Color(0xFFFDB022)
                    else -> colors.border
                },
                RoundedCornerShape(12.dp),
            )
            .hoverable(source)
            .plainClick(source = source, onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetRadio(selected, size = 20.dp)
        Column {
            Text(title, style = sheetText(14.sp, FontWeight.SemiBold), color = colors.textPrimary)
            Text(
                description,
                style = sheetText(12.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

// Attach document -----------------------------------------------------------------------------------------------------

/**
 * The picked PDF before it posts — the web's DocumentModal: the file named
 * and sized, a caption, Upload / Close.
 */
@Composable
internal fun AttachDocumentDialog(dialog: SheetDialog.AttachDocument, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    val document = dialog.document
    SheetModal(
        if (dialog.withPublish) "Publish with a Document" else "Attach Document",
        { onEvent(DialogEvent.Dismiss) },
        width = 560.dp,
        closeOnScrim = !dialog.uploading,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .size(width = 120.dp, height = 150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.redBg)
                    .border(1.dp, colors.redBorder, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        ZillitIcons.File,
                        contentDescription = null,
                        tint = colors.red,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        "PDF",
                        style = sheetText(14.sp, FontWeight.Bold),
                        color = colors.red,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Text(
                document.name,
                style = sheetText(15.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 14.dp),
            )
            Text(fileSize(document.bytes.size.toLong()), style = sheetText(12.sp), color = colors.textTertiary)
            if (dialog.withPublish) {
                Text(
                    "The call sheet publishes as a continuation, and this document is posted beside it in Home.",
                    style = sheetText(12.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
        SheetInput(
            value = dialog.caption,
            onChange = { onEvent(WorkflowEvent.EditAttachCaption(it)) },
            placeholder = "Type a message",
            enabled = !dialog.uploading,
            onEnter = { onEvent(WorkflowEvent.ConfirmAttach) },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            SheetButton("Close", { onEvent(DialogEvent.Dismiss) }, kind = ButtonKind.Ghost, enabled = !dialog.uploading)
            SheetButton(
                if (dialog.uploading) "Uploading…" else "Upload",
                { onEvent(WorkflowEvent.ConfirmAttach) },
                enabled = !dialog.uploading,
                icon = ZillitIcons.Upload,
                horizontalPadding = 18.dp,
            )
        }
    }
}

private fun fileSize(bytes: Long): String = when {
    bytes >= MB -> "${(bytes * TENTHS / MB) / TENTHS.toDouble()} MB"
    bytes >= KB -> "${bytes / KB} KB"
    else -> "$bytes B"
}

private const val KB = 1024L
private const val TENTHS = 10L
private const val MB = 1024L * 1024L

// Reject / remind -----------------------------------------------------------------------------------------------------

/** "Reject Call Sheet" — the reason is optional. */
@Composable
internal fun RejectDialog(state: SheetUiState, dialog: SheetDialog.Reject, onEvent: (SheetEvent) -> Unit) {
    SheetModal("Reject Call Sheet", { onEvent(DialogEvent.Dismiss) }) {
        Text(
            "Reason for rejection (optional):",
            style = sheetText(14.sp),
            color = SheetTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SheetInput(
            value = dialog.reason,
            onChange = { onEvent(WorkflowEvent.EditRejectReason(it)) },
            placeholder = "Reason...",
            singleLine = false,
            minLines = 3,
            autoFocus = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton(
                "Reject",
                { onEvent(WorkflowEvent.ConfirmReject) },
                kind = ButtonKind.Reject,
                enabled = !state.busy,
                radius = 12.dp,
                height = 40.dp,
                fontSize = 14.sp,
                horizontalPadding = 20.dp,
            )
            SheetButton(
                "Cancel",
                { onEvent(DialogEvent.Dismiss) },
                kind = ButtonKind.Ghost,
                height = 40.dp,
                fontSize = 14.sp,
            )
        }
    }
}

/** "Send Reminder" — a message rides along; blank sends the default. */
@Composable
internal fun ReminderComposeDialog(
    state: SheetUiState,
    dialog: SheetDialog.ReminderCompose,
    onEvent: (SheetEvent) -> Unit,
) {
    SheetModal("Send Reminder", { onEvent(DialogEvent.Dismiss) }) {
        Text(
            "Write a message to send along with the reminder:",
            style = sheetText(14.sp),
            color = SheetTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        SheetInput(
            value = dialog.message,
            onChange = { onEvent(WorkflowEvent.EditReminder(it)) },
            placeholder = "Please review and approve this call sheet.",
            singleLine = false,
            minLines = 3,
            autoFocus = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SheetButton(
                if (state.busy) "Sending…" else "Send Reminder",
                { onEvent(WorkflowEvent.ConfirmReminder) },
                enabled = !state.busy,
                radius = 12.dp,
                height = 40.dp,
                fontSize = 14.sp,
                horizontalPadding = 20.dp,
            )
            SheetButton(
                "Cancel",
                { onEvent(DialogEvent.Dismiss) },
                kind = ButtonKind.Ghost,
                height = 40.dp,
                fontSize = 14.sp,
            )
        }
    }
}
