package com.zillit.desktop.feature.bankrec.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.bankrec.domain.BankPeriod
import com.zillit.desktop.feature.bankrec.domain.ExceptionStatus
import com.zillit.desktop.feature.bankrec.domain.FraudStatus
import com.zillit.desktop.feature.bankrec.domain.PeriodStatus
import com.zillit.desktop.feature.bankrec.domain.PortalStatus
import com.zillit.desktop.feature.bankrec.domain.TxnStatus
import com.zillit.desktop.feature.bankrec.ui.ALL_PERIODS
import com.zillit.desktop.feature.bankrec.ui.BankRecUiState
import com.zillit.desktop.feature.bankrec.ui.components.BrEmpty
import com.zillit.desktop.feature.bankrec.ui.components.BrTone
import com.zillit.desktop.feature.bankrec.ui.components.eyebrow
import com.zillit.desktop.feature.bankrec.ui.resolvePeriodChoice

/**
 * The open-period selector over the Exceptions, Fraud Alerts and FX lists.
 *
 * Draws nothing when only one period is open: a dropdown with a single choice
 * is noise. Signed-off periods are not offered — these lists are for what can
 * still be acted on.
 */
@Composable
internal fun PeriodFilter(state: BankRecUiState, choice: String?, onChoose: (String) -> Unit) {
    val open = state.openPeriods
    if (open.size < 2) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZillitText("PERIOD", style = eyebrow(), color = ZillitTheme.colors.textSecondary)
        ZillitSelect(
            value = resolvePeriodChoice(choice, open),
            options = listOf(ALL_PERIODS) + open.map { it.id },
            onSelect = onChoose,
            label = { id ->
                if (id == ALL_PERIODS) "All Open Periods" else state.period(id)?.let(state::periodOptionLabel) ?: id
            },
            modifier = Modifier.widthIn(min = 180.dp, max = 260.dp),
        )
    }
}

/** A page's top row: things on the left, a gap, things on the right. */
@Composable
internal fun ActionRow(
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading()
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** The list tabs' shared empty state when nothing is open to act on. */
@Composable
internal fun NoActivePeriod(icon: ImageVector) {
    BrEmpty(
        title = "No Active Period",
        message = "Import a bank statement to start a reconciliation period.",
        icon = icon,
    )
}

/** The rows a list tab shows for a period choice. */
internal fun <T> List<T>.forPeriod(state: BankRecUiState, choice: String?, periodOf: (T) -> String): List<T> {
    val periodId = resolvePeriodChoice(choice, state.openPeriods)
    return if (periodId == ALL_PERIODS) this else filter { periodOf(it) == periodId }
}

// -- status words ---------------------------------------------------------------

internal val PeriodStatus.tone: BrTone get() = if (this == PeriodStatus.Complete) BrTone.Green else BrTone.Amber

internal val BankPeriod.fraudBadge: Pair<String, BrTone>
    get() = if (fraudCount > 0) "$fraudCount flags" to BrTone.Red else "Clear" to BrTone.Green

/** The web's workspace colours: matched green, suggested amber, unmatched red, fraud deep red, FX teal. */
internal val TxnStatus.tone: BrTone
    get() = when (this) {
        TxnStatus.Matched -> BrTone.Green
        TxnStatus.Suggested -> BrTone.Amber
        TxnStatus.Unmatched, TxnStatus.FraudFlag -> BrTone.Red
        TxnStatus.Fx -> BrTone.Teal
    }

/** The Exceptions tab's badge for a status — none for an open one, which the section already says. */
internal val ExceptionStatus.badge: Pair<String, BrTone>?
    get() = when (this) {
        ExceptionStatus.Open -> null
        ExceptionStatus.Ignored -> "Ignored" to BrTone.Gray
        ExceptionStatus.UnderInvestigation -> "Under Investigation" to BrTone.Amber
        ExceptionStatus.Investigated -> "Investigated" to BrTone.Green
        ExceptionStatus.Resolved -> "Resolved" to BrTone.Green
    }

/** The portal summary's words for the same statuses, which differ from the tab's. */
internal val ExceptionStatus.portalBadge: Pair<String, BrTone>
    get() = when (this) {
        ExceptionStatus.Open -> "Open" to BrTone.Amber
        ExceptionStatus.Resolved -> "Resolved" to BrTone.Green
        ExceptionStatus.Ignored -> "Ignored" to BrTone.Gray
        ExceptionStatus.UnderInvestigation -> "Investigating" to BrTone.Blue
        ExceptionStatus.Investigated -> "Investigated" to BrTone.Green
    }

internal val FraudStatus.badge: Pair<String, BrTone>
    get() = when (this) {
        FraudStatus.Active -> "Under Review" to BrTone.Red
        FraudStatus.Escalated -> "Escalated" to BrTone.Red
        FraudStatus.Dismissed -> "Dismissed" to BrTone.Gray
        FraudStatus.Accepted -> "Accepted" to BrTone.Green
    }

internal val PortalStatus.tone: BrTone
    get() = when (this) {
        PortalStatus.Active -> BrTone.Green
        PortalStatus.Revoked -> BrTone.Red
        PortalStatus.Expired -> BrTone.Gray
    }

/** Green for money in, red for out, dark for nothing — the web's amount colours. */
@Composable
internal fun amountColor(amount: Double?): Color {
    val c = ZillitTheme.colors
    return when {
        amount == null || amount == 0.0 -> c.textPrimary
        amount > 0 -> c.success
        else -> c.danger
    }
}
