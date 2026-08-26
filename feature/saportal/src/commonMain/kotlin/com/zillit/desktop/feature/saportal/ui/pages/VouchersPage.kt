package com.zillit.desktop.feature.saportal.ui.pages

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
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.domain.VoucherStatus
import com.zillit.desktop.feature.saportal.ui.SaEvent
import com.zillit.desktop.feature.saportal.ui.SaUiState
import com.zillit.desktop.feature.saportal.ui.VoucherPill
import com.zillit.desktop.feature.saportal.ui.day
import com.zillit.desktop.feature.saportal.ui.money
import com.zillit.desktop.feature.saportal.ui.shift
import com.zillit.desktop.feature.saportal.ui.title

/** Every day the artiste has worked here, newest first, filterable by state. */
@Composable
internal fun ColumnScope.VouchersPage(state: SaUiState, onEvent: (SaEvent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        FilterChip("All", state.voucherFilter == null) { onEvent(SaEvent.FilterVouchers(null)) }
        // Unknown is this client's word for "unrecognised", not a bucket the
        // server has, so it is never offered as a filter.
        VoucherStatus.entries.filter { it != VoucherStatus.Unknown }.forEach { status ->
            FilterChip(status.label, state.voucherFilter == status) {
                onEvent(SaEvent.FilterVouchers(status))
            }
        }
    }

    if (state.vouchers.isEmpty()) {
        if (!state.loading) {
            ZillitEmptyState(
                title = if (state.voucherFilter == null) "No days yet" else "Nothing in that state",
                message = if (state.voucherFilter == null) {
                    "Days appear here once the AD has submitted them."
                } else {
                    "Try another filter to see your other days."
                },
                icon = ZillitIcons.Info,
            )
        }
        return
    }

    state.vouchers.forEach { voucher -> VoucherRow(voucher, onEvent) }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    ZillitButton(
        text = label,
        onClick = onClick,
        variant = if (selected) ButtonVariant.Secondary else ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
}

@Composable
private fun VoucherRow(voucher: Voucher, onEvent: (SaEvent) -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = day(voucher.shootDate), style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    text = "${title(voucher)} · ${shift(voucher)}",
                    style = ZillitTheme.typography.bodySmall,
                    color = ZillitTheme.colors.textSecondary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                ZillitText(
                    text = money(voucher.gross, voucher.currency),
                    style = ZillitTheme.typography.numeric,
                )
                VoucherPill(voucher.status)
            }
            ZillitButton(
                text = "Open",
                onClick = { onEvent(SaEvent.OpenVoucher(voucher.id)) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
            // Signing is offered from the row as well as the detail: an
            // artiste with six days to sign should not have to open each.
            if (voucher.signable) {
                ZillitButton(
                    text = "Sign",
                    onClick = { onEvent(SaEvent.StartSigning(voucher)) },
                    size = ButtonSize.Small,
                )
            }
        }
    }
}
