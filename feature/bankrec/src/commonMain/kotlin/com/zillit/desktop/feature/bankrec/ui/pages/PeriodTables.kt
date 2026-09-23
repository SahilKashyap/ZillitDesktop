package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.toDisplayLabel
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.BankRecFormat
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.BankTab
import com.zillit.desktop.feature.bankrec.ui.LocalBankRecPeople
import com.zillit.desktop.feature.bankrec.ui.PeriodScope
import com.zillit.desktop.feature.bankrec.ui.components.BrAlign
import com.zillit.desktop.feature.bankrec.ui.components.BrBadge
import com.zillit.desktop.feature.bankrec.ui.components.BrBankIdentity
import com.zillit.desktop.feature.bankrec.ui.components.BrCard
import com.zillit.desktop.feature.bankrec.ui.components.BrColumn
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.components.BrLinkButton
import com.zillit.desktop.feature.bankrec.ui.components.BrMiniProgress
import com.zillit.desktop.feature.bankrec.ui.components.BrTable
import com.zillit.desktop.feature.bankrec.ui.components.mono
import com.zillit.desktop.feature.bankrec.ui.signer

/**
 * The Overview's "Reconciliation History" card.
 *
 * A row's click opens its period while nothing is selected — the workspace
 * for one in progress, the History tab for one signed off — and toggles its
 * selection once something is, so building a multi-select never trips into
 * the workspace.
 */
@Composable
internal fun ColumnScope.PeriodHistoryCard(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit, scope: PeriodScope) {
    val selected = state.overviewSelection
    BrCard(
        modifier = Modifier.fillMaxWidth(),
        title = str(S.desktop_br_reconciliation_history),
        icon = ZillitIcons.Clock,
        titleRight = { SelectionActions(state, selected, scope, onEvent, exportLabel = str(S.recce_export_pdf)) },
    ) {
        PeriodTable(state, state.periods, selected, scope, onEvent)
    }
}

/** Clear and bulk delete while a selection is on; Export PDF otherwise. */
@Composable
internal fun SelectionActions(
    state: BankRecUiState,
    selected: Set<String>,
    scope: PeriodScope,
    onEvent: (BankRecEvent) -> Unit,
    exportLabel: String,
) {
    if (selected.isNotEmpty()) {
        BrLinkButton(
            str(S.txt_clear),
            ZillitTheme.colors.textSecondary,
            { onEvent(BankRecEvent.ClearPeriodSelection(scope)) },
        )
        ZillitButton(
            text = if (state.deleting?.deleting == true) {
                str(S.ah_deleting)
            } else if (selected.size == 1) {
                str(S.desktop_br_delete_selected_one, selected.size)
            } else {
                str(S.desktop_br_delete_n_periods, selected.size)
            },
            onClick = { onEvent(BankRecEvent.AskDeletePeriods(selected.toList())) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
            enabled = state.deleting?.deleting != true,
        )
    } else {
        ZillitButton(
            text = exportLabel,
            onClick = { onEvent(BankRecEvent.OpenExportPdf) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Download,
        )
    }
}

@Composable
private fun PeriodTable(
    state: BankRecUiState,
    rows: List<BankPeriod>,
    selected: Set<String>,
    scope: PeriodScope,
    onEvent: (BankRecEvent) -> Unit,
) {
    val selecting = selected.isNotEmpty()
    BrTable(
        rows = rows,
        key = { it.id },
        isSelected = { it.id in selected },
        onRowClick = { period ->
            when {
                selecting -> if (period.isDeletable) onEvent(BankRecEvent.TogglePeriodSelection(scope, period.id))
                period.isOpen -> onEvent(BankRecEvent.OpenPeriod(period.id))
                else -> onEvent(BankRecEvent.OpenTab(BankTab.History))
            }
        },
        empty = { BrEmpty(title = str(S.desktop_br_no_periods_found), icon = ZillitIcons.Bank) },
        columns = listOf(
            selectColumn(rows, selected, scope, onEvent),
            BrColumn(str(S.cr_meta_period), width = 92.dp) { period ->
                ZillitText(BankRecFormat.periodLabel(period), style = mono(13.sp, FontWeight.SemiBold), maxLines = 1)
            },
            BrColumn(str(S.desktop_bank), weight = 1.5f) { period ->
                val account = state.account(period.bankAccountId)
                BrBankIdentity(
                    name = account?.displayName.orEmpty(),
                    sortCode = account?.sortCode.orEmpty(),
                    accountNumber = account?.accountNumber.orEmpty(),
                    compact = true,
                )
            },
            BrColumn(str(S.desktop_txns), width = 48.dp, align = BrAlign.End) { period ->
                ZillitText(period.totalTxns.toString(), style = mono(13.sp), color = ZillitTheme.colors.textSecondary)
            },
            BrColumn(str(S.desktop_matched), width = 68.dp, align = BrAlign.End) { period ->
                ZillitText(
                    "${period.matchedCount}/${period.totalTxns}",
                    style = mono(13.sp),
                    color = ZillitTheme.colors.textSecondary,
                )
            },
            BrColumn(str(S.desktop_progress), width = 104.dp) { period -> BrMiniProgress(period.matchedPercent) },
            BrColumn(str(S.desktop_fraud), width = 76.dp) { period ->
                val (label, tone) = period.fraudBadge
                BrBadge(label, tone)
            },
            BrColumn(str(S.status), width = 96.dp) { period -> BrBadge(period.status.label, period.status.tone) },
            BrColumn(str(S.desktop_signed_off_caps), weight = 1.2f) { period -> SignedOffCell(period) },
            BrColumn(str(S.dd_actions), width = 142.dp, align = BrAlign.End) { period ->
                if (!selecting) RowActions(period, onEvent, viewOpensDetail = true)
            },
        ),
    )
}

/**
 * The checkbox column — offered only on periods that can be deleted, with a
 * select-all over those in the header.
 */
internal fun selectColumn(
    rows: List<BankPeriod>,
    selected: Set<String>,
    scope: PeriodScope,
    onEvent: (BankRecEvent) -> Unit,
): BrColumn<BankPeriod> {
    val deletable = rows.filter { it.isDeletable }.map { it.id }
    return BrColumn(
        header = "",
        width = 22.dp,
        headerContent = {
            if (deletable.isNotEmpty()) {
                ZillitCheckbox(
                    checked = selected.containsAll(deletable),
                    onCheckedChange = { onEvent(BankRecEvent.ToggleAllPeriods(scope)) },
                )
            }
        },
    ) { period ->
        if (period.isDeletable) {
            ZillitCheckbox(
                checked = period.id in selected,
                onCheckedChange = { onEvent(BankRecEvent.TogglePeriodSelection(scope, period.id)) },
            )
        }
    }
}

/** Who closed the period and when — never the id the service stores for them. */
@Composable
internal fun SignedOffCell(period: BankPeriod, showDate: Boolean = true) {
    val colors = ZillitTheme.colors
    val person = LocalBankRecPeople.current.signer(period)
    val date = period.signedAtMillis?.let(BankRecFormat::day)
    if (person == null && date == null) {
        ZillitText(BankRecFormat.DASH, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        ZillitText(
            person?.name ?: str(S.desktop_signed_off),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            maxLines = 1,
        )
        val designation = person?.designation?.takeIf { it.isNotBlank() }?.let(::readableLabel)
        val detail = listOfNotNull(designation, date.takeIf { showDate }).joinToString(" · ")
        if (detail.isNotBlank()) {
            ZillitText(detail, style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
        }
    }
}

/** A designation as words — a backend key like `production_accountant` reads as such. */
internal fun readableLabel(value: String): String = if ('_' in value && ' ' !in value) value.toDisplayLabel() else value

/** Open (for a period in progress) or View, and Delete for one that may be deleted. */
@Composable
internal fun RowActions(period: BankPeriod, onEvent: (BankRecEvent) -> Unit, viewOpensDetail: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (period.isDeletable) {
            ZillitButton(
                text = str(S.delete),
                onClick = { onEvent(BankRecEvent.AskDeletePeriods(listOf(period.id))) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        if (period.isOpen) {
            ZillitButton(
                text = str(S.recce_open),
                onClick = { onEvent(BankRecEvent.OpenPeriod(period.id)) },
                size = ButtonSize.Small,
            )
        } else {
            ZillitButton(
                text = str(S.view),
                onClick = {
                    onEvent(
                        if (viewOpensDetail) {
                            BankRecEvent.OpenPeriodDetail(period.id)
                        } else {
                            BankRecEvent.OpenTab(BankTab.History)
                        },
                    )
                },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
    }
}
