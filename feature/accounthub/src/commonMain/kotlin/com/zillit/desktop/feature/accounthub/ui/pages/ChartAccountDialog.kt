package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.ChartOfAccounts
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.ui.AccountForm
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.components.CoaIcons
import com.zillit.desktop.feature.accounthub.ui.components.FieldLabel
import com.zillit.desktop.feature.accounthub.ui.components.HubSelect

/**
 * Editing a code — the web's `AccountFormModal`.
 *
 * Line type and parent may change on a manual row (a structural edit the server
 * re-walks), never on a budget row; the code is the natural key and never
 * changes; the name is optional. Save stays off until the form would be
 * accepted, and the hints under each field say why when it would not.
 */
@Suppress("LongMethod") // A form, read top to bottom; the order is the reading order.
@Composable
internal fun ChartAccountDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val form = state.chart.form
    val rows = state.chart.accounts

    ZillitDialogShell(
        title = form?.title.orEmpty(),
        visible = form != null,
        onDismiss = { onEvent(AccountHubEvent.DismissAccountForm) },
        icon = CoaIcons.Tree,
        width = DIALOG_WIDTH,
        actions = {
            ZillitButton(
                text = str(S.cancel),
                onClick = { onEvent(AccountHubEvent.DismissAccountForm) },
                variant = ButtonVariant.Secondary,
            )
            ZillitButton(
                text = when {
                    form?.saving == true -> str(S.ah_saving)
                    form?.isEdit == true -> str(S.dm_setup_save)
                    else -> str(S.create)
                },
                onClick = { onEvent(AccountHubEvent.SaveAccount) },
                loading = form?.saving == true,
                enabled = form?.canSave(rows) == true,
            )
        },
    ) {
        if (form == null) return@ZillitDialogShell

        FormField(str(S.desktop_line_type), required = true) {
            ZillitSelect(
                value = form.lineType,
                options = CoaLineType.entries,
                onSelect = { onEvent(AccountHubEvent.SetAccountLineType(it)) },
                label = { it.tagLabel },
                enabled = !form.structureLocked,
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.structureLocked) {
                Hint(
                    str(S.desktop_hub_imported_from_a_budget_its_line_type_is_fixed_to),
                )
            }
            val children = form.editing?.let { ChartOfAccounts.childCount(rows, it.id) } ?: 0
            if (form.structureChanged && children > 0) {
                val plural = if (children == 1) "" else "ren"
                Hint("Re-typing this row also re-threads $children child$plural beneath it.")
            }
        }

        ParentField(form, state, onEvent)

        CodeAndClass(form, rows, onEvent)

        FormField(str(S.av_display_name)) {
            ZillitTextField(
                value = form.name,
                onValueChange = { onEvent(AccountHubEvent.SetAccountName(it)) },
                placeholder = if (form.lineType == CoaLineType.Header) {
                    str(S.desktop_above_the_line)
                } else {
                    str(S.desktop_script_writing_fees)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Side by side: two one-word toggles read as a pair.
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ZillitCheckbox(
                checked = form.isActive,
                onCheckedChange = { onEvent(AccountHubEvent.ToggleAccountActive) },
                label = "Active",
            )
            // Unticking keeps the code in the chart, so history that names it
            // still resolves, but takes it off every module's picker.
            ZillitCheckbox(
                checked = form.isPosting,
                onCheckedChange = { onEvent(AccountHubEvent.ToggleAccountPosting) },
                label = "Posting",
            )
        }
    }
}

/** The code (fixed once it exists) beside the class (fixed on a budget row), each with its hint. */
@Composable
private fun CodeAndClass(form: AccountForm, rows: List<CoaAccount>, onEvent: (AccountHubEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        FormField(str(S.desktop_nominal_account_code), required = true, modifier = Modifier.weight(1f)) {
            ZillitTextField(
                value = form.code,
                onValueChange = { onEvent(AccountHubEvent.SetAccountCode(it)) },
                placeholder = if (form.lineType == CoaLineType.Header) "ATL" else "1100",
                enabled = !form.isEdit,
                modifier = Modifier.fillMaxWidth(),
            )
            // "Required" waits for a keystroke; a taken code is said at once.
            form.codeError(rows)?.takeIf { form.code.isNotEmpty() }?.let { Hint(it, error = true) }
            if (form.isEdit) Hint(str(S.desktop_hub_code_is_immutable_clone_to_a_new_code_if_needed))
        }
        FormField(str(S.desktop_cost_type), required = true, modifier = Modifier.weight(1f)) {
            ZillitSelect(
                value = form.costType,
                options = CoaCostType.entries,
                onSelect = { onEvent(AccountHubEvent.SetAccountCostType(it)) },
                label = { it.label },
                // A budget describes costs: its rows are Expense for good, here and on the server.
                enabled = !form.structureLocked,
                modifier = Modifier.fillMaxWidth(),
            )
            Hint(form.costTypeHint)
        }
    }
}

/**
 * The parent, searchable by code or name, and optional: cleared, the row sits
 * at the top of the tree. Hidden for the top level, which has none.
 */
@Composable
private fun ParentField(form: AccountForm, state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val expected = form.lineType.parentType ?: return
    val rows = state.chart.accounts
    val options = ChartOfAccounts.parentOptions(rows, form.lineType, excludingId = form.editing?.id)
    val selected = rows.firstOrNull { it.id == form.parentId }
    FormField("Parent (${expected.tagLabel})") {
        HubSelect(
            value = selected,
            options = options,
            label = { it.label("·") },
            onSelect = { onEvent(AccountHubEvent.SetAccountParent(it?.id)) },
            placeholder = "Optional — search a ${expected.tagLabel.lowercase()}…",
            clearable = true,
            enabled = !form.structureLocked,
            modifier = Modifier.fillMaxWidth(),
        )
        val problem = form.parentProblem(rows)
        when {
            problem != null && form.parentId != null -> Hint(problem, error = true)
            form.structureLocked ->
                Hint(str(S.desktop_hub_parent_is_fixed_on_an_imported_row_to_re_parent))
            !form.isEdit && options.isEmpty() ->
                Hint("No ${expected.tagLabel.lowercase()} rows exist yet — this will be created without a parent.")
        }
    }
}

@Composable
private fun FormField(
    label: String,
    required: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FieldLabel(label, required = required)
        content()
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    val colors = ZillitTheme.colors
    ZillitText(
        text,
        style = ZillitTheme.typography.labelSmall,
        color = if (error) colors.danger else colors.textMuted,
    )
}

private val DIALOG_WIDTH = 520.dp
