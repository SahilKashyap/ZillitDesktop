package com.zillit.desktop.feature.accounthub.ui.pages

import com.zillit.desktop.core.common.Money
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitProgressBar
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.accounthub.domain.BudgetRow
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.BudgetVersion
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.HubPage
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.MonoChip
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill
import com.zillit.desktop.feature.accounthub.ui.components.SubCard

/**
 * The production's budget, by version — the web's `BudgetModule` / `BudgetsTab`.
 *
 * Version cards on the left (a lock on Live and Archived, the total, the
 * date, the source file), the chosen version's lines on the right — Account,
 * Name, Amount, and an allocation bar as a share of the whole — with subtotal
 * rows per group, the orphaned lines set apart, and the grand total pinned.
 *
 * Read-only. A version is created by importing a budget file, which is its
 * own surface, and Live and Archived versions cannot be edited at all.
 */
@Suppress("LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
fun BudgetPage(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    /** Whether the host wired storage; false hides the import. */
    canImport: Boolean = false,
    canOpenDocuments: Boolean = false,
) {
    val budget = state.budget

    HubPage {
        ZillitPageHeader(
            eyebrow = "Setup",
            title = "Budget",
            description = "Versioned project budgets that hang off the Chart of Accounts and drive Cost Report. " +
                "Import a " +
                "budget file (PDF / Excel) to extract every code + amount as a draft version.",
            actions = {
                ZillitIconButton(
                    icon = ZillitIcons.Reload,
                    contentDescription = "Refresh",
                    onClick = { onEvent(AccountHubEvent.Refresh) },
                )
                if (canImport && state.viewer.canEdit) {
                    ZillitButton(
                        text = "Import Budget",
                        onClick = { onEvent(AccountHubEvent.OpenBudgetImport) },
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Upload,
                    )
                }
            },
        )

        if (budget.loading && budget.versions.isEmpty()) {
            ZillitSpinner()
        } else if (budget.versions.isEmpty()) {
            ZillitEmptyState(
                title = "No budget versions yet",
                message = "Import a budget file to create the first version. Until then the cost report has " +
                    "nothing to compare against.",
                icon = ZillitIcons.BarChart,
                action = {
                    if (canImport && state.viewer.canEdit) {
                        ZillitButton(
                            text = "Import Budget",
                            onClick = { onEvent(AccountHubEvent.OpenBudgetImport) },
                            leadingIcon = ZillitIcons.Upload,
                        )
                    }
                },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                MonoLabel("Budget Versions")
                FieldHint("${budget.versions.size} " +
                    "version${if (budget.versions.size == 1) "" else "s"}" +
                        if (budget.live != null) " · 1 LIVE" else "",
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().weight(1f),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            ) {
                VersionList(budget.versions, budget.selectedId, onEvent)
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                ) {
                    VersionDetail(state, onEvent, canOpenDocuments)
                }
            }
        }
    }

    BudgetImportDialog(state, onEvent)
}

@Composable
private fun VersionList(versions: List<BudgetVersion>, selectedId: String?, onEvent: (AccountHubEvent) -> Unit) {
    ZillitScrollColumn(
        modifier = Modifier.width(VERSIONS_WIDTH).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        versions.forEach {
            version -> VersionCard(version, version.id == selectedId) {
                onEvent(AccountHubEvent.SelectBudgetVersion(version.id))
            }
        }
    }
}

/** One version — the web's `VersionCard`: version, name, status, a lock on the fixed ones, total, date, file. */
@Composable
private fun VersionCard(version: BudgetVersion, selected: Boolean, onSelect: () -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) colors.accent else colors.border,
                ZillitTheme.shapes.large,
            )
            .clickable(onClick = onSelect)
            .padding(ZillitTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitText(
                text = version.version.ifBlank { version.name },
                style = ZillitTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (version.status.isLocked) ZillitIcon(icon = ZillitIcons.Shield, tint = colors.textMuted, size = 14.dp)
            StatusPill(version.status)
        }
        if (version.version.isNotBlank() && version.name.isNotBlank()) FieldHint(version.name)
        ZillitText(
            text = version.total.asMoney(version.currencyCode),
            style = ZillitTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        )
        FieldHint(
            listOfNotNull(
                EpochDate.date(version.createdAtMillis).takeIf { it.isNotBlank() },
                version.sourceFileName.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
        )
    }
}

@Composable
private fun StatusPill(status: BudgetStatus) {
    Pill(
        status.label.uppercase(),
        tone = when (status) {
            BudgetStatus.Live -> StatusTone.Done
            BudgetStatus.Approved -> StatusTone.Progress
            BudgetStatus.Draft -> StatusTone.Pending
            BudgetStatus.Archived -> StatusTone.Neutral
        },
    )
}

@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun ColumnScope.VersionDetail(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canOpenDocuments: Boolean,
) {
    val budget = state.budget
    val version = budget.selected ?: return
    val rows = budget.rows
    val grand = version.total.takeIf { it != 0.0 } ?: rows.filter { it.depth == 0 }.sumOf { it.line.shownTotal }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                ZillitText(
                    text = "${version.version} ${version.name}".trim(),
                    style = ZillitTheme.typography.titleLarge,
                )
                StatusPill(version.status)
            }
            if (version.sourceFileName.isNotBlank()) FieldHint("Imported from ${version.sourceFileName}")
            if (version.status.isLocked) FieldHint("${version.status.label} versions are fixed. To change this " +
                "budget, import a new version from it.")
        }
        if (canOpenDocuments && version.attachment != null) {
            ZillitButton(
                text = "View file",
                onClick = { onEvent(AccountHubEvent.OpenBudgetFile) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Eye,
                loading = budget.openingFile,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            MonoLabel("Total")
            ZillitText(
                text = grand.asMoney(version.currencyCode),
                style = ZillitTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
            )
        }
    }

    SubCard(padded = false, modifier = Modifier.fillMaxWidth().weight(1f)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ZillitTheme.colors.surfaceSunken)
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            MonoLabel("Account", Modifier.width(CODE_WIDTH))
            MonoLabel("Name", Modifier.weight(1f))
            MonoLabel("Amount", Modifier.width(AMOUNT_WIDTH))
            MonoLabel("Allocation", Modifier.width(BAR_WIDTH))
        }
        if (budget.linesLoading) Box(
            Modifier.fillMaxWidth().padding(ZillitTheme.spacing.lg),
            contentAlignment = Alignment.Center,
        ) {
            ZillitSpinner()
        }
        if (rows.isEmpty() && !budget.linesLoading) FieldHint(
            "No lines on this version — an imported budget carries its own lines; this one has none.",
            Modifier.padding(ZillitTheme.spacing.lg),
        )
        ZillitScrollColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val placed = rows.filterNot { it.orphaned }
            val orphans = rows.filter { it.orphaned }
            placed.forEachIndexed { index, row ->
                LineRow(row, grand, version.currencyCode)
                // A group's subtotal, after its last child — "Total · name".
                val next = placed.getOrNull(index + 1)
                if (row.depth > 0 && (next == null || next.depth < row.depth)) {
                    val parentDepth = row.depth - 1
                    val parent = placed.take(index).lastOrNull { it.depth == parentDepth }
                    if (parent != null && parent.line.lineType != CoaLineType.SubCategory) SubtotalRow(
                        parent,
                        version.currencyCode,
                    )
                }
            }
            if (orphans.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(
                    horizontal = ZillitTheme.spacing.lg,
                    vertical = ZillitTheme.spacing.sm,
                )) {
                    MonoLabel("Orphaned (${orphans.size})")
                }
                orphans.forEach { LineRow(it, grand, version.currencyCode) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.border))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(text = "Total", style = ZillitTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            ZillitText(
                text = grand.asMoney(version.currencyCode),
                style = ZillitTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            )
            Box(Modifier.width(BAR_WIDTH))
        }
    }
}

@Composable
private fun LineRow(row: BudgetRow, grand: Double, currency: String) {
    val line = row.line
    val share = if (grand > 0) (line.shownTotal / grand).toFloat().coerceIn(0f, 1f) else 0f
    val isGroup = line.lineType != CoaLineType.SubCategory && line.lineType != CoaLineType.Category
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.width(CODE_WIDTH).padding(start = (row.depth * INDENT_STEP).dp),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitText(
                text = line.account.ifBlank { "—" },
                style = ZillitTheme.typography.numeric.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (isGroup) FontWeight.Bold else FontWeight.Normal,
                ),
                maxLines = 1,
            )
            if (row.orphaned) MonoChip("Detached")
        }
        ZillitText(
            text = line.title.takeIf { line.account.isNotBlank() }?.let { line.uncodedName.ifBlank { it } }
                ?: line.uncodedName.ifBlank { "—" },
            style = ZillitTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitText(
            text = line.shownTotal.asMoney(currency),
            style = ZillitTheme.typography.numeric.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        Row(
            modifier = Modifier.width(BAR_WIDTH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ZillitProgressBar(fraction = share, modifier = Modifier.weight(1f))
            FieldHint("${(share * PERCENT).toInt()}%")
        }
    }
}

@Composable
private fun SubtotalRow(parent: BudgetRow, currency: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(Modifier.width(CODE_WIDTH))
        ZillitText(
            text = "Total · ${parent.line.uncodedName.ifBlank { parent.line.account }}",
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        ZillitText(
            text = parent.line.shownTotal.asMoney(currency),
            style = ZillitTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.width(AMOUNT_WIDTH),
        )
        Box(Modifier.width(BAR_WIDTH))
    }
}

/**
 * A figure with its budget's own currency.
 *
 * The symbol the web prints — `£1,200` — falling back to the code for a
 * currency the table has never heard of, which is what [Money.symbol] does.
 * Blank means the production's default and shows the bare number.
 */
internal fun Double.asMoney(currencyCode: String): String {
    val grouped = com.zillit.desktop.feature.accounthub.ui.components.groupAmount(
        if (this == toLong().toDouble()) toLong().toString() else String.format2(this),
    )
    return if (currencyCode.isBlank()) grouped else Money.symbol(currencyCode) + grouped
}

/** Two decimals without a platform formatter. */
private fun String.Companion.format2(value: Double): String {
    val rounded = kotlin.math.round(kotlin.math.abs(value) * PENCE) / PENCE
    val whole = rounded.toLong()
    val pence = kotlin.math.round((rounded - whole) * PENCE).toInt()
    val text = "$whole.${pence.toString().padStart(2, '0')}"
    return if (value < 0) "-$text" else text
}

private val VERSIONS_WIDTH = 300.dp
private val CODE_WIDTH = 200.dp
private val AMOUNT_WIDTH = 150.dp
private val BAR_WIDTH = 160.dp
private const val INDENT_STEP = 14
private const val PERCENT = 100
private const val PENCE = 100.0
