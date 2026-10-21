package com.zillit.desktop.feature.addashboard.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDayStatus
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.domain.formatMoney
import com.zillit.desktop.feature.addashboard.ui.AdUiState

/** The bits every AD page uses, so none of them invents its own. */

/**
 * The scrolling page body each tab sits in.
 *
 * A plain scrolling column, not a lazy one — which is why every table inside
 * one must pass `virtualised = false`: a virtualised table in an infinitely
 * tall parent throws rather than measuring.
 */
@Composable
internal fun AdPage(
    modifier: Modifier = Modifier,
    spacing: Dp = ZillitTheme.spacing.md,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    ZillitScrollColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(ZillitTheme.spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** A label and its value, side by side — the shape every detail panel repeats. */
@Composable
internal fun AdDetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        ZillitText(
            text = label,
            modifier = Modifier.width(DETAIL_LABEL_WIDTH),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(
            text = value.ifBlank { EM_DASH },
            modifier = Modifier.fillMaxWidth(),
            style = ZillitTheme.typography.bodySmall,
        )
    }
}

/** A heading and a hint above a group of fields. */
@Composable
internal fun AdFieldGroup(
    title: String,
    hint: String? = null,
    content: ColumnContent,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText(text = title, style = ZillitTheme.typography.titleSmall)
        hint?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
        content()
    }
}

internal typealias ColumnContent = @Composable () -> Unit

/** An amount in the row's **own** currency — money is per row on this service. */
internal fun AdUiState.money(amount: Double, currency: String): String =
    formatMoney(amount, currencies.symbolFor(currency))

/** The day's default symbol, for a total that spans no single row. */
internal fun AdUiState.defaultMoney(amount: Double): String =
    formatMoney(amount, currencies.defaultSymbol)

/** How a row's lifecycle reads, and in what colour. */
internal fun SupportingArtistDay.statusTone(): StatusTone = when {
    isBlocked -> StatusTone.Rejected
    signed -> StatusTone.Done
    isSubmitted -> StatusTone.Progress
    isPresent -> StatusTone.Ready
    isDraft -> StatusTone.Neutral
    else -> StatusTone.Pending
}

/** The row's own words for where it stands. */
internal fun SupportingArtistDay.statusLabel(): String = when {
    isBlocked -> str(S.desktop_ad_status_blocked)
    signed -> str(S.signed)
    isSubmitted -> str(S.txt_submitted)
    isPresent -> str(S.desktop_call_present)
    isDraft -> str(S.draft)
    attendance == com.zillit.desktop.feature.addashboard.domain.AttendanceStatus.Absent -> str(S.desktop_ad_absent)
    else -> str(S.desktop_ad_attendance_booked)
}

internal fun ArtisteStatus.tone(): StatusTone = when (this) {
    ArtisteStatus.Verified -> StatusTone.Done
    ArtisteStatus.Blocked -> StatusTone.Rejected
    ArtisteStatus.Draft -> StatusTone.Neutral
    ArtisteStatus.Pending -> StatusTone.Pending
}

internal fun AdDayStatus.tone(): StatusTone = when (this) {
    AdDayStatus.Submitted, AdDayStatus.Approved, AdDayStatus.Published -> StatusTone.Done
    AdDayStatus.InProgress, AdDayStatus.Wrapped -> StatusTone.Progress
    AdDayStatus.Draft -> StatusTone.Neutral
    AdDayStatus.Unknown -> StatusTone.Neutral
}

internal fun ReportStatus.tone(): StatusTone =
    if (published) StatusTone.Done else StatusTone.Pending

/** The dash a missing value shows, rather than an empty cell. */
internal const val EM_DASH = "—"

private val DETAIL_LABEL_WIDTH: Dp = 160.dp
