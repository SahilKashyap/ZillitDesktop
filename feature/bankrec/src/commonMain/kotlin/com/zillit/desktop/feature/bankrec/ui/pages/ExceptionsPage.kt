package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.bankrec.domain.BankException
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.ui.BankRecEvent
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.shortDayLabel

/**
 * The bank lines the reconciliation could not place.
 *
 * Two ways out of one: say what is happening to it, or post it to the ledger.
 * Posting writes a journal, so it is the one that carries a form.
 */
@Composable
fun ColumnScope.ExceptionsPage(state: BankRecUiState, onEvent: (BankRecEvent) -> Unit) {
    val exceptions = state.exceptions

    PeriodFilter(state, exceptions.periodId) { onEvent(BankRecEvent.FilterExceptions(it)) }

    if (exceptions.loading && exceptions.rows.isEmpty()) {
        ZillitSpinner()
        return
    }

    if (exceptions.rows.isEmpty()) {
        ZillitEmptyState(
            title = "Nothing outstanding",
            message = "Every line on this period's statement found its counterpart in the ledger.",
            icon = ZillitIcons.Tick,
        )
        return
    }

    Section(
        title = "Outstanding",
        rows = exceptions.outstanding,
        state = state,
        onEvent = onEvent,
        emptyMessage = "Nothing left to investigate.",
    )
    Section(
        title = "Settled",
        rows = exceptions.settled,
        state = state,
        onEvent = onEvent,
        emptyMessage = "Nothing has been resolved or ignored yet.",
    )
}

@Composable
private fun ColumnScope.Section(
    title: String,
    rows: List<BankException>,
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
    emptyMessage: String,
) {
    ZillitSectionCard(
        title = title,
        meta = "${rows.size}",
        icon = ZillitIcons.Warning,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (rows.isEmpty()) {
            ZillitText(
                text = emptyMessage,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
            return@ZillitSectionCard
        }
        rows.forEach { row -> ExceptionRow(row, state, onEvent) }
    }
}

@Composable
private fun ColumnScope.ExceptionRow(
    row: BankException,
    state: BankRecUiState,
    onEvent: (BankRecEvent) -> Unit,
) {
    val currency = row.currency ?: state.currencyOf(state.periods.firstOrNull { it.id == row.periodId })
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    text = listOfNotNull(
                        row.title.takeIf { it.isNotBlank() } ?: row.type.label,
                        shortDayLabel(row.transaction?.transactionDateMillis).takeIf { it != "—" },
                    ).joinToString(" — "),
                    style = ZillitTheme.typography.bodyMedium,
                )
                ZillitText(
                    text = row.type.guidance,
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            ZillitText(
                text = signedMoney(row.amount, currency),
                style = ZillitTheme.typography.bodyMedium,
            )
            ZillitStatusPill(label = row.status.label, tone = row.status.tone(), dot = true)
        }

        if (row.notes.isNotBlank()) {
            ZillitText(
                text = row.notes,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textMuted,
            )
        }

        RowActions(row, onEvent)
        ZillitDivider()
    }
}

@Composable
private fun RowActions(row: BankException, onEvent: (BankRecEvent) -> Unit) {
    if (!row.status.isOutstanding) return
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            text = "Post to ledger",
            onClick = { onEvent(BankRecEvent.ComposeQuickAdd(row)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Ledger,
        )
        if (row.status == ExceptionStatus.Open) {
            ZillitButton(
                text = "Investigating",
                onClick = {
                    onEvent(BankRecEvent.ComposeExceptionNote(row, ExceptionStatus.UnderInvestigation))
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        } else {
            ZillitButton(
                text = "Investigated",
                onClick = { onEvent(BankRecEvent.ComposeExceptionNote(row, ExceptionStatus.Investigated)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
        // Ignoring closes it without a posting, which is a decision worth
        // recording — so it goes through the same note dialog.
        ZillitButton(
            text = "Ignore",
            onClick = { onEvent(BankRecEvent.ComposeExceptionNote(row, ExceptionStatus.Ignored)) },
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
        )
    }
}
