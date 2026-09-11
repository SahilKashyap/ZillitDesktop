package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState

internal val PERIOD_FILTER_WIDTH = 260.dp

/** Every period, plus an "all periods" entry. Blank is the all entry. */
internal const val ALL_PERIODS = ""

/**
 * Money, in the currency it is actually denominated in.
 *
 * No default currency: a caller that forgets to resolve one renders a bare
 * number rather than a plausible symbol against the wrong amount.
 */
internal fun money(amount: Double?, currency: String): String = Money.format(amount, currency)

/** `+£1,234.50` / `-£335.22` — for a column where direction is the point. */
internal fun signedMoney(amount: Double?, currency: String): String {
    if (amount == null) return "—"
    val body = Money.format(kotlin.math.abs(amount), currency)
    return when {
        amount < 0 -> "-$body"
        amount > 0 -> "+$body"
        else -> body
    }
}

internal fun PeriodStatus.tone(): StatusTone =
    if (this == PeriodStatus.Complete) StatusTone.Done else StatusTone.Progress

internal fun TxnStatus.tone(): StatusTone = when (this) {
    TxnStatus.Matched -> StatusTone.Done
    TxnStatus.Suggested -> StatusTone.Pending
    TxnStatus.Unmatched -> StatusTone.Rejected
    TxnStatus.FraudFlag -> StatusTone.Escalated
    TxnStatus.Fx -> StatusTone.InTransit
}

internal fun ExceptionStatus.tone(): StatusTone = when (this) {
    ExceptionStatus.Open -> StatusTone.Rejected
    ExceptionStatus.UnderInvestigation -> StatusTone.Pending
    ExceptionStatus.Investigated, ExceptionStatus.Resolved -> StatusTone.Done
    ExceptionStatus.Ignored -> StatusTone.Neutral
}

internal fun FraudStatus.tone(): StatusTone = when (this) {
    FraudStatus.Active -> StatusTone.Escalated
    FraudStatus.Escalated -> StatusTone.Rejected
    FraudStatus.Accepted, FraudStatus.Dismissed -> StatusTone.Done
}

/**
 * The period filter every list tab carries.
 *
 * Offers "All periods" as well as each one, because an exception or an alert
 * outlives the month it was raised in and an accountant chasing one does not
 * always know which month that was.
 */
@Composable
internal fun ColumnScope.PeriodFilter(
    state: BankRecUiState,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZillitText(text = "Period", style = ZillitTheme.typography.label)
        ZillitSelect(
            value = selected,
            options = listOf(ALL_PERIODS) + state.periods.map { it.id },
            onSelect = onSelect,
            label = { id -> if (id == ALL_PERIODS) "All periods" else state.periodLabelFor(id) },
            modifier = Modifier.width(PERIOD_FILTER_WIDTH),
        )
    }
}
