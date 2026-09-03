package com.zillit.desktop.feature.email.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.feature.email.ui.settings.SettingsHint
import com.zillit.desktop.feature.email.ui.settings.SettingsMessage
import com.zillit.desktop.feature.email.ui.settings.SettingsPage
import com.zillit.desktop.feature.email.ui.settings.SettingsRow

/** Inbox rules: the run-ordered list, an editor, and a rule's run history. */
@Composable
internal fun EmailRulesPage(state: EmailRulesUiState, onEvent: (EmailRulesEvent) -> Unit, onBack: () -> Unit) {
    val history = state.history
    val editor = state.editor
    when {
        history != null -> HistoryPage(history, onEvent)
        editor != null -> EditorPage(state, editor, onEvent)
        else -> ListPage(state, onEvent, onBack)
    }
}

@Composable
private fun ListPage(state: EmailRulesUiState, onEvent: (EmailRulesEvent) -> Unit, onBack: () -> Unit) {
    SettingsPage(
        title = "Email rules",
        subtitle = "Run in this order on every incoming email, top first.",
        onBack = onBack,
        actions = {
            ZillitButton(text = "New rule", onClick = { onEvent(EmailRulesEvent.New) }, enabled = !state.atLimit)
        },
    ) {
        Messages(state, onEvent)
        when {
            state.isLoading && state.rules.isEmpty() -> SettingsHint("Loading…")
            state.rules.isEmpty() ->
                SettingsHint("No rules yet. A rule sorts, saves, forwards or marks incoming mail for you.")
            else -> state.rules.forEachIndexed { index, rule -> RuleRow(rule, index, state.rules.lastIndex, onEvent) }
        }
    }
}

@Composable
private fun Messages(state: EmailRulesUiState, onEvent: (EmailRulesEvent) -> Unit) {
    val dismiss = { onEvent(EmailRulesEvent.DismissMessage) }
    state.error?.let { SettingsMessage(text = it, tone = StatusTone.Rejected, onDismiss = dismiss) }
    state.info?.let { SettingsMessage(text = it, tone = StatusTone.Done, onDismiss = dismiss) }
}

@Composable
private fun RuleRow(rule: EmailRule, index: Int, last: Int, onEvent: (EmailRulesEvent) -> Unit) {
    SettingsRow {
        ZillitCheckbox(
            checked = rule.enabled,
            onCheckedChange = { onEvent(EmailRulesEvent.SetEnabled(rule.id, it)) },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(text = rule.name, style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = rule.summary() + if (rule.stopOnMatch) " · then stop" else "",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                maxLines = 2,
            )
        }
        RowButton("↑", enabled = index > 0) { onEvent(EmailRulesEvent.Move(rule.id, up = true)) }
        RowButton("↓", enabled = index < last) { onEvent(EmailRulesEvent.Move(rule.id, up = false)) }
        RowButton("History") { onEvent(EmailRulesEvent.OpenHistory(rule.id)) }
        RowButton("Edit", variant = ButtonVariant.Secondary) { onEvent(EmailRulesEvent.Edit(rule.id)) }
        RowButton("Delete", variant = ButtonVariant.Danger) { onEvent(EmailRulesEvent.Delete(rule.id)) }
    }
}

@Composable
private fun EditorPage(state: EmailRulesUiState, editor: RuleEditorState, onEvent: (EmailRulesEvent) -> Unit) {
    val draft = editor.draft
    fun edit(next: EmailRule) = onEvent(EmailRulesEvent.Draft(next))
    SettingsPage(
        title = if (draft.isNew) "New rule" else "Edit rule",
        subtitle = null,
        onBack = { onEvent(EmailRulesEvent.CloseEditor) },
        actions = {
            RowButton("Cancel") { onEvent(EmailRulesEvent.CloseEditor) }
            ZillitButton(
                text = "Save",
                onClick = { onEvent(EmailRulesEvent.Save) },
                enabled = !editor.isSaving,
                loading = editor.isSaving,
            )
        },
    ) {
        Messages(state, onEvent)
        if (editor.pickingForAction != null) {
            DrivePicker(editor, onEvent)
            return@SettingsPage
        }
        ZillitTextField(
            value = draft.name,
            onValueChange = { edit(draft.copy(name = it)) },
            label = "Name",
            placeholder = "Invoices to Drive",
        )
        FieldRow {
            ZillitText(text = "When", style = ZillitTheme.typography.bodyMedium)
            ZillitSelect(
                value = draft.matchType,
                options = RuleMatchType.entries,
                onSelect = { edit(draft.copy(matchType = it)) },
                label = { it.label },
                modifier = Modifier.width(SELECT_WIDTH),
            )
            ZillitText(text = "match:", style = ZillitTheme.typography.bodyMedium)
        }
        ConditionsSection(draft, ::edit)
        ActionsSection(draft, editor, onEvent, ::edit)
        ZillitCheckbox(
            checked = draft.stopOnMatch,
            onCheckedChange = { edit(draft.copy(stopOnMatch = it)) },
            label = "Stop running later rules when this one matches",
        )
        ZillitCheckbox(checked = draft.enabled, onCheckedChange = { edit(draft.copy(enabled = it)) }, label = "Enabled")
    }
}

@Composable
private fun ConditionsSection(draft: EmailRule, edit: (EmailRule) -> Unit) {
    draft.conditions.forEachIndexed { index, condition ->
        ConditionRow(
            condition,
            onChange = { edit(draft.copy(conditions = draft.conditions.replacing(index, it))) },
            onRemove = { edit(draft.copy(conditions = draft.conditions.filterIndexed { i, _ -> i != index })) },
        )
    }
    ZillitButton(
        text = "Add condition",
        onClick = { edit(draft.copy(conditions = draft.conditions + RuleCondition())) },
        variant = ButtonVariant.Secondary,
    )
}

@Composable
private fun ActionsSection(
    draft: EmailRule,
    editor: RuleEditorState,
    onEvent: (EmailRulesEvent) -> Unit,
    edit: (EmailRule) -> Unit,
) {
    ZillitText(text = "Then", style = ZillitTheme.typography.bodyMedium)
    draft.actions.forEachIndexed { index, action ->
        ActionRow(
            index = index,
            action = action,
            editor = editor,
            onEvent = onEvent,
            onChange = { edit(draft.copy(actions = draft.actions.replacing(index, it))) },
            onRemove = { edit(draft.copy(actions = draft.actions.filterIndexed { i, _ -> i != index })) },
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        RuleActionType.entries.forEach { type ->
            ZillitButton(
                text = "+ ${type.label}",
                onClick = { edit(draft.copy(actions = draft.actions + RuleAction.blank(type))) },
                variant = ButtonVariant.Tertiary,
                enabled = draft.actions.none { it.type == type },
            )
        }
    }
}

@Composable
private fun ConditionRow(condition: RuleCondition, onChange: (RuleCondition) -> Unit, onRemove: () -> Unit) {
    FieldRow {
        ZillitSelect(
            value = condition.field,
            options = ConditionField.entries,
            onSelect = { onChange(condition.withField(it)) },
            label = { it.label },
            modifier = Modifier.width(SELECT_WIDTH),
        )
        if (condition.field.operators.size > 1) {
            ZillitSelect(
                value = condition.operator,
                options = condition.field.operators,
                onSelect = { onChange(condition.copy(operator = it)) },
                label = { it.label },
                modifier = Modifier.width(SELECT_WIDTH),
            )
        }
        if (condition.field.needsValue) {
            ZillitTextField(
                value = condition.value,
                onValueChange = { onChange(condition.copy(value = it)) },
                placeholder = "value",
                modifier = Modifier.weight(1f),
            )
        }
        RowButton("Remove", onClick = onRemove)
    }
}

@Composable
private fun ActionRow(
    index: Int,
    action: RuleAction,
    editor: RuleEditorState,
    onEvent: (EmailRulesEvent) -> Unit,
    onChange: (RuleAction) -> Unit,
    onRemove: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        FieldRow {
            ZillitText(
                text = action.type.label,
                style = ZillitTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            RowButton("Remove", onClick = onRemove)
        }
        when (action) {
            is RuleAction.SaveAttachmentsToDrive -> SaveToDriveFields(index, action, editor, onEvent, onChange)
            is RuleAction.MoveToFolder -> ZillitSelect(
                value = action.folderName.ifBlank { null },
                options = listOf<String?>(null) + editor.folderOptions,
                onSelect = { onChange(action.copy(folderName = it.orEmpty())) },
                label = { it ?: "Select a folder…" },
                modifier = Modifier.width(SELECT_WIDTH * 2),
            )
            is RuleAction.ForwardTo -> ZillitTextField(
                value = action.email,
                onValueChange = { onChange(action.copy(email = it)) },
                placeholder = "ops@partner.com",
            )
            RuleAction.MarkRead -> Unit
        }
    }
}

@Composable
private fun SaveToDriveFields(
    index: Int,
    action: RuleAction.SaveAttachmentsToDrive,
    editor: RuleEditorState,
    onEvent: (EmailRulesEvent) -> Unit,
    onChange: (RuleAction) -> Unit,
) {
    FieldRow {
        ZillitText(
            text = action.driveFolderName.ifBlank { folderPlaceholder(action.driveFolderId) },
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        ZillitButton(
            text = "Choose Drive folder",
            onClick = { onEvent(EmailRulesEvent.BrowseDrive(index, null)) },
            variant = ButtonVariant.Secondary,
            enabled = !editor.driveLoading,
        )
    }
    ZillitTextField(
        value = action.extensions.joinToString(", "),
        onValueChange = { text ->
            onChange(action.copy(extensions = RuleAction.SaveAttachmentsToDrive.parseExtensions(text)))
        },
        label = "Only these file types (optional)",
        placeholder = "pdf, xlsx",
    )
    ZillitTextField(
        value = if (action.maxSizeBytes > 0) (action.maxSizeBytes / MB).toString() else "",
        onValueChange = { text -> onChange(action.copy(maxSizeBytes = megabytesToBytes(text))) },
        label = "Skip attachments larger than (MB, optional)",
    )
}

@Composable
private fun DrivePicker(editor: RuleEditorState, onEvent: (EmailRulesEvent) -> Unit) {
    val trail = editor.driveTrail
    FieldRow {
        ZillitText(
            text = "Drive" + trail.joinToString("") { " / ${it.name}" },
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        RowButton("Up", enabled = trail.isNotEmpty()) { onEvent(EmailRulesEvent.DriveUp) }
        trail.lastOrNull()?.let { here ->
            ZillitButton(text = "Use \"${here.name}\"", onClick = { onEvent(EmailRulesEvent.PickDriveFolder(here)) })
        }
        RowButton("Cancel") { onEvent(EmailRulesEvent.CancelDrivePick) }
    }
    when {
        editor.driveLoading -> SettingsHint("Loading folders…")
        editor.driveChildren.isEmpty() -> SettingsHint("No folders here.")
        else -> editor.driveChildren.forEach { folder ->
            SettingsRow {
                ZillitText(
                    text = folder.name,
                    style = ZillitTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                RowButton("Open") { onEvent(EmailRulesEvent.BrowseDrive(editor.pickingForAction ?: 0, folder)) }
                RowButton("Use", variant = ButtonVariant.Secondary) { onEvent(EmailRulesEvent.PickDriveFolder(folder)) }
            }
        }
    }
}

@Composable
private fun HistoryPage(history: RuleHistoryState, onEvent: (EmailRulesEvent) -> Unit) {
    SettingsPage(
        title = "Rule history",
        subtitle = history.rule.name,
        onBack = { onEvent(EmailRulesEvent.CloseHistory) },
    ) {
        when {
            history.isLoading && history.executions.isEmpty() -> SettingsHint("Loading…")
            history.executions.isEmpty() -> SettingsHint("This rule has not run yet.")
            else -> history.executions.forEach { run -> ExecutionRow(run) }
        }
        if (history.hasMore) {
            ZillitButton(
                text = "Load more",
                onClick = { onEvent(EmailRulesEvent.MoreHistory) },
                variant = ButtonVariant.Secondary,
                loading = history.isLoading,
            )
        }
    }
}

@Composable
private fun ExecutionRow(run: RuleExecution) {
    SettingsRow {
        ZillitStatusPill(label = run.status.label, tone = run.status.tone())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            ZillitText(
                text = run.messageId.ifBlank { "Message" } + " · ${run.mailboxEmail}",
                style = ZillitTheme.typography.bodySmall,
            )
            run.results.forEach { result ->
                ZillitText(
                    text = "${result.type?.label ?: "Action"}: ${result.status.label}" +
                        result.detail?.let { " — $it" }.orEmpty(),
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            run.error?.let {
                ZillitText(text = it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.danger)
            }
        }
    }
}

private fun ExecutionStatus.tone(): StatusTone = when (this) {
    ExecutionStatus.Pending -> StatusTone.Pending
    ExecutionStatus.Success -> StatusTone.Done
    ExecutionStatus.Partial -> StatusTone.Escalated
    ExecutionStatus.Failed -> StatusTone.Rejected
}

private fun <T> List<T>.replacing(index: Int, item: T): List<T> =
    mapIndexed { i, existing -> if (i == index) item else existing }

/** A horizontal line of controls with the page's standard gap. */
@Composable
private fun FieldRow(content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        content = content,
    )
}

/** A small row action — tertiary unless told otherwise. */
@Composable
private fun RowButton(
    text: String,
    variant: ButtonVariant = ButtonVariant.Tertiary,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    ZillitButton(text = text, onClick = onClick, variant = variant, enabled = enabled)
}

private fun folderPlaceholder(driveFolderId: String): String =
    if (driveFolderId.isBlank()) "No folder chosen" else "Folder $driveFolderId"

private fun megabytesToBytes(text: String): Long = (text.trim().toLongOrNull() ?: 0L).coerceAtLeast(0L) * MB

private val SELECT_WIDTH = 180.dp
private const val MB = 1024L * 1024
