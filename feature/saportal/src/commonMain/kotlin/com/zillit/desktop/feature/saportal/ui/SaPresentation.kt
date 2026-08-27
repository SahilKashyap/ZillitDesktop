package com.zillit.desktop.feature.saportal.ui

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.Money
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.feature.saportal.domain.Voucher
import com.zillit.desktop.feature.saportal.domain.VoucherStatus

/** How a voucher's state reads, and in what colour. */
internal fun VoucherStatus.tone(): StatusTone = when (this) {
    VoucherStatus.Pending -> StatusTone.Pending
    VoucherStatus.Signed -> StatusTone.Ready
    VoucherStatus.Paid -> StatusTone.Done
    VoucherStatus.Unknown -> StatusTone.Neutral
}

@Composable
internal fun VoucherPill(status: VoucherStatus) {
    ZillitStatusPill(label = status.label, tone = status.tone())
}

internal fun money(amount: Double?, currency: String?): String = Money.format(amount, currency)

internal fun day(millis: Long?): String = EpochDate.date(millis).ifEmpty { "—" }

/**
 * The shift as a person describes it: "07:00 – 19:30 · 12h 30m".
 *
 * Both ends can be missing on a day that has not wrapped, and the hours are
 * the server's `minutes_worked` rather than a subtraction here — meal breaks
 * come out of it and this client does not know the rules.
 */
internal fun shift(voucher: Voucher): String {
    val ends = listOf(voucher.callTime, voucher.wrapTime).filter { it.isNotBlank() }
    val span = when (ends.size) {
        2 -> "${ends[0]} – ${ends[1]}"
        1 -> ends[0]
        else -> ""
    }
    val worked = voucher.minutesWorked.takeIf { it > 0 }?.let { minutes ->
        val hours = minutes / MINUTES_PER_HOUR
        val rest = minutes % MINUTES_PER_HOUR
        if (rest == 0) "${hours}h" else "${hours}h ${rest}m"
    }
    return listOfNotNull(span.takeIf { it.isNotBlank() }, worked).joinToString(" · ").ifEmpty { "—" }
}

/** What a day is called: its role, or its category, or its code. */
internal fun title(voucher: Voucher): String = listOf(voucher.role, voucher.category)
    .firstOrNull { it.isNotBlank() }
    ?: voucher.code.ifBlank { "Shoot day" }

private const val MINUTES_PER_HOUR = 60
