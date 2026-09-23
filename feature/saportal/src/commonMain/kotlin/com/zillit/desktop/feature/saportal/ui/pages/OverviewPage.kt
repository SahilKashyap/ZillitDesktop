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
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

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
        ZillitSectionCard(title = str(S.desktop_sa_waiting_signature), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitText(
                    text = str(S.desktop_sa_not_sent_until_signed),
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
        ZillitSectionCard(title = str(S.desktop_sa_next_booking), modifier = Modifier.fillMaxWidth()) {
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
                title = str(S.desktop_nothing_here_yet),
                message = str(S.desktop_sa_nothing_here_message),
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
    ZillitSectionLabel(str(S.desktop_sa_your_earnings))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitStatTile(
            label = str(S.desktop_this_year),
            value = money(summary.ytdGross, state.profile?.currency),
        )
        ZillitStatTile(
            label = str(S.desktop_all_time),
            value = money(summary.totalGross, state.profile?.currency),
        )
        ZillitStatTile(
            label = str(S.desktop_sa_holiday_accrued),
            value = money(summary.holidayAccrued, state.profile?.currency),
            // Not spendable yet, and saying so stops it being read as owed now.
            sub = str(S.desktop_sa_paid_with_final_week),
        )
        ZillitStatTile(
            label = str(S.desktop_sa_days_worked),
            value = summary.vouchers.total.toString(),
            sub = str(S.desktop_sa_paid_count, summary.vouchers.paid),
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
            text = str(S.desktop_sa_review_and_sign),
            onClick = { onEvent(SaEvent.OpenVoucher(voucher.id)) },
            size = ButtonSize.Small,
            variant = ButtonVariant.Primary,
        )
    }
}
