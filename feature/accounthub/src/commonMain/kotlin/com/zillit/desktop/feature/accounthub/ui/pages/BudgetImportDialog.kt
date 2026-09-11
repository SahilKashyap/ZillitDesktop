package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BudgetImports
import com.zillit.desktop.feature.accounthub.domain.CoaImportMode
import com.zillit.desktop.feature.accounthub.domain.ParsedBudget
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.BudgetImportState
import com.zillit.desktop.feature.accounthub.ui.ImportStep

/**
 * Importing a budget file.
 *
 * Pick, review, commit. The middle step is the reason the other two exist: the
 * parse is a guess at somebody else's spreadsheet, and committing it writes
 * codes into the chart of accounts that every other tool codes against. So the
 * preview shows what was found, what could not be coded, and every warning the
 * server raised, before anything is written.
 */
@Composable
internal fun BudgetImportDialog(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val import = state.budget.import

    ZillitDialogShell(
        title = when (import.step) {
            ImportStep.Upload -> "Import a budget"
            ImportStep.Preview -> "Review the import"
            ImportStep.Done -> "Budget imported"
        },
        visible = import.open,
        onDismiss = { onEvent(AccountHubEvent.CloseBudgetImport) },
        icon = ZillitIcons.BarChart,
        actions = { Actions(import, onEvent) },
    ) {
        when (import.step) {
            ImportStep.Upload -> UploadStep(import)
            ImportStep.Preview -> PreviewStep(state, onEvent)
            ImportStep.Done -> DoneStep(import)
        }
    }
}

@Composable
private fun Actions(import: BudgetImportState, onEvent: (AccountHubEvent) -> Unit) {
    when (import.step) {
        ImportStep.Upload -> {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.CloseBudgetImport) },
                variant = ButtonVariant.Tertiary,
                enabled = !import.uploading,
            )
            ZillitButton(
                text = "Choose a file",
                onClick = { onEvent(AccountHubEvent.PickBudgetFile) },
                loading = import.uploading,
                enabled = !import.uploading,
            )
        }

        ImportStep.Preview -> {
            ZillitButton(
                text = "Cancel",
                onClick = { onEvent(AccountHubEvent.CloseBudgetImport) },
                variant = ButtonVariant.Tertiary,
                enabled = !import.committing,
            )
            ZillitButton(
                text = "Import",
                onClick = { onEvent(AccountHubEvent.CommitBudgetImport) },
                loading = import.committing,
                enabled = import.canCommit,
            )
        }

        ImportStep.Done -> ZillitButton(
            text = "Done",
            onClick = { onEvent(AccountHubEvent.CloseBudgetImport) },
        )
    }
}

@Composable
private fun UploadStep(import: BudgetImportState) {
    ZillitText(
        text = "A PDF, an Excel file or a CSV. It is read on the server and shown back to " +
            "you before anything is written.",
        style = ZillitTheme.typography.bodyMedium,
    )
    ZillitText(
        text = "Up to 20 MB.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    if (import.uploading) {
        ZillitNotice(
            text = "Reading the file. A long budget can take a moment.",
            tone = StatusTone.Progress,
            icon = ZillitIcons.Info,
        )
    }
}

@Composable
private fun PreviewStep(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val import = state.budget.import
    val parsed = import.parsed ?: return

    ParsedSummary(parsed)

    // Warnings first, and in the server's own words: duplicate codes and
    // orphaned parents are exactly what this step exists to catch.
    parsed.warnings.forEach { warning ->
        ZillitNotice(text = warning, tone = StatusTone.Pending, icon = ZillitIcons.Info)
    }
    if (parsed.isEmpty) {
        ZillitNotice(
            text = "Nothing was found to import. That usually means the file is laid out in " +
                "a way the parser does not recognise.",
            tone = StatusTone.Rejected,
            icon = ZillitIcons.Info,
        )
    }

    MetaFields(state, onEvent)
    ModeChoice(import.mode, onEvent)
    UncodedList(parsed)
}

@Composable
private fun ParsedSummary(parsed: ParsedBudget) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(label = "Sections", value = parsed.sections.size.toString(), modifier = Modifier.weight(1f))
        ZillitStatTile(label = "Headers", value = parsed.headers.size.toString(), modifier = Modifier.weight(1f))
        ZillitStatTile(label = "Codes", value = parsed.nominals.size.toString(), modifier = Modifier.weight(1f))
        ZillitStatTile(
            label = "Uncoded",
            value = parsed.uncoded.size.toString(),
            tone = if (parsed.uncoded.isEmpty()) StatusTone.Neutral else StatusTone.Pending,
            modifier = Modifier.weight(1f),
        )
    }
    ZillitText(
        text = "Total ${parsed.currency} ${parsed.total}".trim(),
        style = ZillitTheme.typography.titleMedium,
    )
}

@Composable
private fun MetaFields(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val import = state.budget.import
    val meta = import.meta
    val latest = BudgetImports.latestVersion(state.budget.versions)

    ZillitSectionLabel("The new version")
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitTextField(
            value = meta.version,
            onValueChange = { onEvent(AccountHubEvent.EditBudgetImportMeta(meta.copy(version = it))) },
            label = "Version",
            // Versions are unique per production, not per budget name, so the
            // suggestion counts every version this production has.
            placeholder = latest?.let { "Latest is $it" } ?: "v1",
            enabled = !import.committing,
        )
        ZillitTextField(
            value = meta.label,
            onValueChange = { onEvent(AccountHubEvent.EditBudgetImportMeta(meta.copy(label = it))) },
            label = "Name",
            enabled = !import.committing,
            modifier = Modifier.weight(1f),
        )
    }
    ZillitTextField(
        value = meta.description,
        onValueChange = { onEvent(AccountHubEvent.EditBudgetImportMeta(meta.copy(description = it))) },
        label = "Description",
        enabled = !import.committing,
    )
}

@Composable
private fun ModeChoice(mode: CoaImportMode, onEvent: (AccountHubEvent) -> Unit) {
    ZillitSectionLabel("The chart of accounts")
    CoaImportMode.entries.forEach { option ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = option.label,
                onClick = { onEvent(AccountHubEvent.SetCoaImportMode(option)) },
                variant = if (option == mode) ButtonVariant.Secondary else ButtonVariant.Tertiary,
            )
            ZillitText(
                text = option.detail,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** What the parse could not put a code to — the accountant's follow-up work. */
@Composable
private fun UncodedList(parsed: ParsedBudget) {
    if (parsed.uncoded.isEmpty()) return
    ZillitSectionLabel("Not coded")
    ZillitText(
        text = "These import with the budget and can be coded afterwards.",
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
    )
    parsed.uncoded.forEach { line ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = line.name.ifBlank { "—" },
                style = ZillitTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            ZillitText(text = line.amount.toString(), style = ZillitTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun DoneStep(import: BudgetImportState) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        val created = import.created
        ZillitStatusPill(label = "Imported", tone = StatusTone.Done)
        ZillitText(
            text = created?.let { "${it.version} · ${it.name}".trim(' ', '·') }
                ?: "The budget was imported.",
            style = ZillitTheme.typography.titleMedium,
        )
        ZillitText(
            text = "It is a draft. Promote it when it is the one the cost report should read.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }
}
