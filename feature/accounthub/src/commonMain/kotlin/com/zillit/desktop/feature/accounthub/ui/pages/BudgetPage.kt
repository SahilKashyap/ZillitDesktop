package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitVerticalDivider
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BudgetRow
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage

private const val VERSIONS_WIDTH = 300
private const val INDENT_STEP = 16
private const val CODE_WIDTH = 220
private const val AMOUNT_WIDTH = 160

/**
 * The production's budget, by version.
 *
 * Versions on the left, the chosen one's lines on the right — the shape the
 * web settled on, and the one the data wants: a version means nothing without
 * its status beside its total, and a line means nothing outside its version.
 *
 * Read-only. A version is created by importing a budget file, which is its own
 * surface, and Live and Archived versions cannot be edited at all — the way to
 * change one is to clone it, which the import flow does.
 */
@Composable
fun BudgetPage(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    /** Whether the host wired storage; false hides the import. */
    canImport: Boolean = false,
) {
    val budget = state.budget

    HubPage {
        ZillitPageHeader(
            eyebrow = "Setup",
            title = "Budget",
            description = "The versioned project budget that hangs off the chart of accounts " +
                "and feeds the cost report.",
        )

        if (canImport && state.viewer.canEdit) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                com.zillit.desktop.core.designsystem.component.ZillitButton(
                    text = "Import a budget",
                    onClick = { onEvent(AccountHubEvent.OpenBudgetImport) },
                    variant = com.zillit.desktop.core.designsystem.component.ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Upload,
                )
                ZillitText(
                    text = "A PDF, Excel file or CSV becomes a new draft version.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
        }

        if (budget.versions.isEmpty() && !budget.loading) {
            ZillitEmptyState(
                title = "No budget versions yet",
                message = "A version is created by importing a budget file. Until then the " +
                    "cost report has nothing to compare against.",
                icon = ZillitIcons.BarChart,
            )
            BudgetImportDialog(state, onEvent)
            return@HubPage
        }

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            VersionList(budget.versions, budget.selectedId, onEvent)
            ZillitVerticalDivider()
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .padding(start = ZillitTheme.spacing.lg),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            ) {
                VersionDetail(state)
            }
        }
    }

    BudgetImportDialog(state, onEvent)
}

@Composable
private fun VersionList(
    versions: List<BudgetVersion>,
    selectedId: String?,
    onEvent: (AccountHubEvent) -> Unit,
) {
    ZillitScrollColumn(
        modifier = Modifier.width(VERSIONS_WIDTH.dp).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        versions.forEach { version ->
            ZillitSectionCard(
                modifier = Modifier.fillMaxWidth(),
                title = version.version.ifBlank { version.name },
                meta = version.name.takeIf { version.version.isNotBlank() },
                action = { StatusPill(version.status) },
            ) {
                ZillitText(
                    text = version.total.asMoney(version.currencyCode),
                    style = ZillitTheme.typography.titleMedium,
                )
                version.sourceFileName.takeIf { it.isNotBlank() }?.let { file ->
                    ZillitText(
                        text = "Imported from $file",
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                // Selection is a click on the card, which is the whole target —
                // a separate control would be a second thing to find.
                SelectRow(
                    selected = version.id == selectedId,
                    onSelect = { onEvent(AccountHubEvent.SelectBudgetVersion(version.id)) },
                )
            }
        }
    }
}

@Composable
private fun SelectRow(selected: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.zillit.desktop.core.designsystem.component.ZillitButton(
            text = if (selected) "Showing" else "Show lines",
            onClick = onSelect,
            variant = if (selected) {
                com.zillit.desktop.core.designsystem.component.ButtonVariant.Secondary
            } else {
                com.zillit.desktop.core.designsystem.component.ButtonVariant.Tertiary
            },
            size = com.zillit.desktop.core.designsystem.component.ButtonSize.Small,
            enabled = !selected,
        )
    }
}

@Composable
private fun StatusPill(status: BudgetStatus) {
    ZillitStatusPill(
        label = status.label,
        tone = when (status) {
            BudgetStatus.Live -> StatusTone.Done
            BudgetStatus.Approved -> StatusTone.Progress
            BudgetStatus.Draft -> StatusTone.Pending
            BudgetStatus.Archived -> StatusTone.Neutral
        },
    )
}

@Composable
private fun ColumnScope.VersionDetail(state: AccountHubUiState) {
    val budget = state.budget
    val version = budget.selected ?: return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitStatTile(
            label = "Version total",
            value = version.total.asMoney(version.currencyCode),
            modifier = Modifier.weight(1f),
        )
        ZillitStatTile(
            label = "Lines",
            value = budget.lines.size.toString(),
            modifier = Modifier.weight(1f),
        )
    }

    if (version.status.isLocked) {
        ZillitText(
            text = "${version.status.label} versions are fixed. To change this budget, " +
                "import a new version from it.",
            style = ZillitTheme.typography.bodySmall,
            color = ZillitTheme.colors.textSecondary,
        )
    }

    ZillitSectionCard(
        title = "Lines",
        icon = ZillitIcons.Ledger,
        padded = false,
        modifier = Modifier.fillMaxWidth().weight(1f),
    ) {
        ZillitDataTable(
            rows = budget.rows,
            key = { it.line.id },
            loading = budget.linesLoading,
            columns = budgetColumns(version.currencyCode),
            emptyTitle = "No lines on this version",
            emptyMessage = "An imported budget carries its own lines; this one has none.",
        )
    }
}

private fun budgetColumns(currencyCode: String): List<TableColumn<BudgetRow>> = listOf(
    TableColumn(
        header = "Code",
        width = ColumnWidth.Fixed(CODE_WIDTH.dp),
        // Indented by depth, so the chart's shape is legible in a flat table.
        cell = { row -> IndentedText(row.line.title, row.depth) },
    ),
    TableColumn(
        header = "Level",
        cell = { row ->
            ZillitText(
                text = if (row.orphaned) "Detached" else row.line.lineType.tagLabel,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        },
    ),
    TableColumn(
        header = "Amount",
        width = ColumnWidth.Fixed(AMOUNT_WIDTH.dp),
        numeric = true,
        cell = { row ->
            ZillitText(
                text = row.line.shownTotal.asMoney(currencyCode),
                style = ZillitTheme.typography.bodyMedium,
            )
        },
    ),
)

@Composable
private fun IndentedText(text: String, depth: Int) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = (depth * INDENT_STEP).dp),
    )
}

/**
 * A figure with its budget's own currency.
 *
 * The code rather than a symbol: this screen has no currency table, and a
 * wrong symbol reads as a wrong amount. Blank means the production's default,
 * which is shown as the bare number.
 */
private fun Double.asMoney(currencyCode: String): String {
    val whole = toLong()
    val text = if (this == whole.toDouble()) whole.toString() else toString()
    return if (currencyCode.isBlank()) text else "$currencyCode $text"
}
