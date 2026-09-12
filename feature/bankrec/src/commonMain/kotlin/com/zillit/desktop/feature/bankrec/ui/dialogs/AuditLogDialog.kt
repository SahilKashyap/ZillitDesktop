package com.zillit.desktop.feature.bankrec.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.AuditAction
import com.zillit.desktop.feature.bankrec.domain.AuditExportFormat
import com.zillit.desktop.feature.bankrec.domain.BankRecDirectory
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.domain.FraudAuditEntry
import com.zillit.desktop.feature.bankrec.ui.AuditLogState
import com.zillit.desktop.feature.bankrec.ui.AuditSort
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.LocalBankRecPeople
import com.zillit.desktop.feature.bankrec.ui.components.BrAlign
import com.zillit.desktop.feature.bankrec.ui.components.BrBankIdentity
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrSkeletonRows
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.pages.designationLabel
import com.zillit.desktop.feature.bankrec.ui.visibleEntries

/**
 * Who did what, when, to which line — the fraud engine's trail and the
 * module's own imports, re-runs and deletions in one table.
 *
 * The filters scope the export as well as the screen, so the file matches what
 * was looked at; the dialog says so rather than leaving it to be discovered.
 */
@Composable
internal fun AuditLogDialog(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val audit = rememberLast(state.fraudPage.audit) ?: return
    val people = LocalBankRecPeople.current
    ZillitDialogShell(
        title = "Audit Log",
        subtitle = "Fraud reviews, imports, re-runs and deleted periods",
        onDismiss = { onEvent(BankRecEvent.CloseAuditLog) },
        visible = state.fraudPage.audit != null,
        icon = ZillitIcons.Shield,
        width = WIDE_DIALOG,
        maxHeight = TALL_DIALOG,
    ) {
        when {
            audit.loading -> BrSkeletonRows(6)
            audit.entries.isEmpty() -> ZillitText(
                "No audit log entries yet.",
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )

            else -> {
                val rows = audit.visibleEntries()
                Filters(audit, rows.size, state, people, onEvent)
                Tip()
                Box(
                    Modifier.fillMaxWidth().clip(ZillitTheme.shapes.large)
                        .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.large),
                ) {
                    AuditTable(rows, audit, state, people, onEvent)
                }
            }
        }
    }
}

@Composable
private fun Filters(
    audit: AuditLogState,
    shown: Int,
    state: BankRecUiState,
    people: BankRecDirectory,
    onEvent: (BankRecEvent) -> Unit,
) {
    val filters = audit.filters
    val periods = audit.entries.filter { it.periodId.isNotBlank() && it.periodMillis != null }
        .distinctBy { it.periodId }
    val users = audit.entries.map { it.performedBy }.filter { it.isNotBlank() }.distinct()
    val actions = audit.entries.map { it.action }.filter { it.isNotBlank() }.distinct()
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        FilterSelect(
            value = filters.bankAccountId,
            options = listOf("") + state.bankAccounts.map { it.id },
            label = { id ->
                if (id.isBlank()) "All Banks" else state.account(id)?.displayName?.ifBlank { null } ?: "Bank"
            },
        ) { onEvent(BankRecEvent.FilterAuditLog(filters.copy(bankAccountId = it))) }
        FilterSelect(
            value = filters.periodId,
            options = listOf("") + periods.map { it.periodId },
            label = { id ->
                if (id.isBlank()) {
                    "All Periods"
                } else {
                    BankRecFormat.monthLabel(periods.firstOrNull { it.periodId == id }?.periodMillis)
                }
            },
        ) { onEvent(BankRecEvent.FilterAuditLog(filters.copy(periodId = it))) }
        FilterSelect(
            value = filters.performedBy,
            options = listOf("") + users,
            label = { id -> if (id.isBlank()) "All Users" else people.person(id)?.name ?: "Unknown user" },
        ) { onEvent(BankRecEvent.FilterAuditLog(filters.copy(performedBy = it))) }
        FilterSelect(
            value = filters.action,
            options = listOf("") + actions,
            label = { action -> if (action.isBlank()) "All Types" else AuditAction.labelFor(action) },
        ) { onEvent(BankRecEvent.FilterAuditLog(filters.copy(action = it))) }
        ZillitText(
            "$shown of ${audit.entries.size} entries",
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ExportMenu(audit, onEvent)
    }
}

@Composable
private fun FilterSelect(
    value: String,
    options: List<String>,
    label: (String) -> String,
    onSelect: (String) -> Unit,
) {
    ZillitSelect(
        value = value,
        options = options,
        onSelect = onSelect,
        label = label,
        modifier = Modifier.widthIn(min = 140.dp, max = 180.dp),
    )
}

@Composable
private fun ExportMenu(audit: AuditLogState, onEvent: (BankRecEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box {
        ZillitButton(
            text = audit.exporting?.let { "Exporting ${it.label}…" } ?: "Export",
            onClick = { onEvent(BankRecEvent.ShowAuditExportMenu(!audit.exportMenu)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
            loading = audit.exporting != null,
            enabled = audit.exporting == null,
        )
        DropdownMenu(
            expanded = audit.exportMenu,
            onDismissRequest = { onEvent(BankRecEvent.ShowAuditExportMenu(false)) },
            modifier = Modifier.background(colors.surfaceRaised),
        ) {
            AuditExportFormat.entries.forEach { format ->
                DropdownMenuItem(
                    text = { ZillitText("Export ${format.label}", style = ZillitTheme.typography.bodyMedium) },
                    onClick = { onEvent(BankRecEvent.ExportAuditLog(format)) },
                )
            }
        }
    }
}

@Composable
private fun Tip() {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium).background(colors.accentSoft)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitIcon(ZillitIcons.Info, tint = colors.accentText, size = 13.dp)
        ZillitText(
            "Use the filters above to narrow down the log entries. Exports will only include the filtered results " +
                "as shown here.",
            style = ZillitTheme.typography.bodySmall,
            color = colors.textSecondary,
        )
    }
}

@Suppress("LongMethod") // One column per field the web's trail shows.
@Composable
private fun AuditTable(
    rows: List<FraudAuditEntry>,
    audit: AuditLogState,
    state: BankRecUiState,
    people: BankRecDirectory,
    onEvent: (BankRecEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val small = ZillitTheme.typography.bodySmall.copy(fontSize = 11.5.sp)
    val figure = mono(11.5.sp)
    fun sortable(
        title: String,
        sort: AuditSort,
        width: Int,
        align: BrAlign = BrAlign.Start,
        cell: @Composable (FraudAuditEntry) -> Unit,
    ) = BrColumn(
        header = title,
        width = width.dp,
        align = align,
        headerContent = { SortHeader(title, sort, audit) },
        onHeaderClick = { onEvent(BankRecEvent.SortAuditLog(sort)) },
        cell = cell,
    )
    BrTable(
        rows = rows,
        key = { it.id },
        rowPadding = 9.dp,
        empty = {
            ZillitText(
                "No entries match these filters.",
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
            )
        },
        columns = listOf(
            sortable("Date / Time", AuditSort.CreatedAt, 128) { entry ->
                ZillitText(BankRecFormat.dateTime(entry.createdAtMillis), style = figure, color = colors.textSecondary)
            },
            BrColumn("Bank", width = 150.dp) { entry ->
                BrBankIdentity(entry.bankName, sortCode = entry.bankSortCode, compact = true)
            },
            BrColumn("Account No.", width = 92.dp) { entry ->
                ZillitText(
                    entry.bankAccountNumber.ifBlank { BankRecFormat.DASH },
                    style = figure,
                    color = colors.textSecondary,
                )
            },
            sortable("Action", AuditSort.Action, 168) { entry ->
                ZillitText(
                    AuditAction.labelFor(entry.action),
                    style = small.copy(fontWeight = FontWeight.SemiBold),
                    color = actionColor(entry.action),
                    maxLines = 2,
                )
            },
            BrColumn("By", width = 160.dp) { entry -> ZillitText(byLine(entry, people), style = small, maxLines = 2) },
            BrColumn("Fraud Type", width = 128.dp) { entry ->
                ZillitText(
                    entry.fraudType.replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }.ifBlank { BankRecFormat.DASH },
                    style = small.copy(fontWeight = FontWeight.Bold),
                    maxLines = 2,
                )
            },
            sortable("Risk", AuditSort.Risk, 64) { entry ->
                ZillitText(entry.riskScore?.toString() ?: BankRecFormat.DASH, style = mono(11.5.sp, FontWeight.Bold))
            },
            BrColumn("Period", width = 72.dp) { entry ->
                ZillitText(BankRecFormat.monthLabel(entry.periodMillis), style = small, color = colors.textSecondary)
            },
            BrColumn("Description", width = 280.dp) { entry ->
                ZillitText(entry.detail.ifBlank { BankRecFormat.DASH }, style = small, maxLines = 3)
            },
            sortable("Amount", AuditSort.Amount, 104, BrAlign.End) { entry ->
                val txn = entry.transaction
                val code = txn?.currency ?: state.account(entry.bankAccountId)?.currencyCode ?: state.projectCurrency
                ZillitText(
                    entry.amount?.let { BankRecFormat.plainMoney(it, code) } ?: BankRecFormat.DASH,
                    style = mono(11.5.sp, FontWeight.Bold),
                )
            },
            BrColumn("Txn Date", width = 88.dp) { entry ->
                ZillitText(
                    BankRecFormat.day(entry.transaction?.transactionDateMillis),
                    style = figure,
                    color = colors.textSecondary,
                )
            },
            sortable("Vendor", AuditSort.Vendor, 140) { entry ->
                ZillitText(
                    entry.transaction?.vendorName?.ifBlank { null } ?: BankRecFormat.DASH,
                    style = small,
                    maxLines = 2,
                )
            },
            BrColumn("Method", width = 76.dp) { entry ->
                ZillitText(
                    entry.transaction?.paymentMethod?.uppercase()?.ifBlank { null } ?: BankRecFormat.DASH,
                    style = mono(10.5.sp),
                    color = colors.textSecondary,
                )
            },
            BrColumn("Reference", width = 120.dp) { entry ->
                ZillitText(
                    entry.transaction?.reference?.ifBlank { null } ?: BankRecFormat.DASH,
                    style = mono(10.5.sp),
                    color = colors.textMuted,
                    maxLines = 2,
                )
            },
        ),
    )
}

@Composable
private fun SortHeader(title: String, sort: AuditSort, audit: AuditLogState) {
    val colors = ZillitTheme.colors
    val active = audit.sort == sort
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        ZillitText(
            title.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.08.em,
            ),
            color = if (active) colors.textPrimary else colors.textMuted,
            maxLines = 1,
        )
        ZillitIcon(
            if (active && audit.ascending) ZillitIcons.ChevronUp else ZillitIcons.ChevronDown,
            tint = if (active) colors.accent else colors.textMuted.copy(alpha = IDLE_ARROW_ALPHA),
            size = 11.dp,
        )
    }
}

/** "Name · Designation" — never the id the service stores. */
private fun byLine(entry: FraudAuditEntry, people: BankRecDirectory): String {
    if (entry.performedBy.isBlank()) return BankRecFormat.DASH
    val person = people.person(entry.performedBy) ?: return "Unknown user"
    val designation = designationLabel(person.designation)
    return if (designation.isBlank()) person.name else "${person.name} · $designation"
}

/** The web's colour for each action — red for what was flagged, green for what was cleared. */
@Composable
private fun actionColor(action: String): Color {
    val colors = ZillitTheme.colors
    return when (AuditAction.from(action)) {
        AuditAction.Created, AuditAction.Escalated -> colors.danger
        AuditAction.Accepted, AuditAction.ImportResults -> colors.success
        AuditAction.Dismissed -> colors.textMuted
        AuditAction.RemovedByRerun, AuditAction.PeriodDeleted -> colors.warning
        AuditAction.AutoMatchRerun, AuditAction.StatementImport -> colors.info
        null -> colors.textSecondary
    }
}

private const val IDLE_ARROW_ALPHA = 0.5f
