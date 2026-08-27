package com.zillit.desktop.feature.saportal.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitStatTile
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.ui.SaEvent
import com.zillit.desktop.feature.saportal.ui.SaUiState
import com.zillit.desktop.feature.saportal.ui.VoucherPill
import com.zillit.desktop.feature.saportal.ui.day
import com.zillit.desktop.feature.saportal.ui.money
import com.zillit.desktop.feature.saportal.ui.shift
import com.zillit.desktop.feature.saportal.ui.title

/**
 * What an artiste opens the portal to find out: is anything waiting on me,
 * and when am I next in.
 *
 * Those two answers lead; the earnings tiles sit under them. The order is
 * deliberate — a day left unsigned is money not yet on its way, and burying
 * it under a year-to-date figure is how it stays unsigned.
 */
@Composable
internal fun ColumnScope.OverviewPage(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    val summary = state.summary

    if (state.hasOutstanding) {
        ZillitSectionCard(title = "Waiting for your signature", modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitText(
                    text = "A day is not sent for payment until you have signed it.",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
                state.awaitingSignature.forEach { voucher ->
                    OutstandingRow(voucher, onEvent)
                }
            }
        }
    }

    summary?.nextBooking?.let { next ->
        ZillitSectionCard(title = "Next booking", modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    ZillitText(text = day(next.shootDate), style = ZillitTheme.typography.titleMedium)
                    ZillitText(
                        text = listOf(title(next), shift(next)).filter { it != "—" }.joinToString(" · "),
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
                VoucherPill(next.status)
            }
        }
    }

    if (summary == null) {
        if (!state.loading) {
            ZillitEmptyState(
                title = "Nothing here yet",
                message = "Once you have worked a day on this production it will appear here.",
                icon = ZillitIcons.Info,
            )
        }
        return
    }

    EarningsTiles(state)
}

@Composable
private fun ColumnScope.EarningsTiles(state: SaUiState) {
    val summary = state.summary ?: return
    ZillitSectionLabel("Your earnings")
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(
            label = "This year",
            value = money(summary.ytdGross, state.profile?.currency),
        )
        ZillitStatTile(
            label = "All time",
            value = money(summary.totalGross, state.profile?.currency),
        )
        ZillitStatTile(
            label = "Holiday accrued",
            value = money(summary.holidayAccrued, state.profile?.currency),
            // Not spendable yet, and saying so stops it being read as owed now.
            sub = "Paid with your final week",
        )
        ZillitStatTile(
            label = "Days worked",
            value = summary.vouchers.total.toString(),
            sub = "${summary.vouchers.paid} paid",
            tone = if (summary.vouchers.pending > 0) StatusTone.Pending else StatusTone.Done,
        )
    }
}

@Composable
private fun OutstandingRow(voucher: Voucher, onEvent: (SaEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(text = day(voucher.shootDate), style = ZillitTheme.typography.bodyMedium)
            ZillitText(
                text = "${title(voucher)} · ${money(voucher.gross, voucher.currency)}",
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
            )
        }
        ZillitButton(
            text = "Review and sign",
            onClick = { onEvent(SaEvent.OpenVoucher(voucher.id)) },
            size = ButtonSize.Small,
            variant = ButtonVariant.Primary,
        )
    }
}
