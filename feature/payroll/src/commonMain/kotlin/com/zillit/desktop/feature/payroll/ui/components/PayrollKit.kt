package com.zillit.desktop.feature.payroll.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.TimecardStatus

/**
 * A payroll screen's top bar — the web's V3 shell: a back chip to the
 * landing, then "PAYROLL / <screen>", then the screen's own actions.
 */
@Composable
internal fun PayrollTopBar(
    crumb: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(ZillitTheme.colors.canvas)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = ZillitTheme.spacing.xl,
                vertical = ZillitTheme.spacing.md,
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.desktop_payroll_back_to_payroll),
                onClick = onBack,
            )
            ZillitText(
                text = str(S.dm_section_payroll).uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accentText,
            )
            ZillitText(text = "/", style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textMuted)
            ZillitText(
                text = crumb,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = ZillitTheme.colors.textSecondary,
            )
            Spacer(Modifier.weight(1f))
            actions()
        }
        ZillitDivider()
    }
}

/**
 * The week stepper — "W/E 28 Apr – 04 May 2026" between two chevrons, with a
 * way back to the current week when the viewer has walked off it. Next stops
 * at the current week: there are no timecards in the future.
 */
@Composable
internal fun WeekNavigator(
    label: String,
    canGoNext: Boolean,
    isCurrent: Boolean,
    onShift: (Int) -> Unit,
    onCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(ZillitTheme.shapes.medium)
                .background(ZillitTheme.colors.surface)
                .border(1.dp, ZillitTheme.colors.border, ZillitTheme.shapes.medium)
                .padding(horizontal = ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitIconButton(
                icon = ZillitIcons.ChevronLeft,
                contentDescription = str(S.desktop_cr_previous_week),
                onClick = { onShift(-1) },
            )
            ZillitText(
                text = str(S.desktop_payroll_week_ending, label),
                style = ZillitTheme.typography.numeric,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitIconButton(
                icon = ZillitIcons.ChevronRight,
                contentDescription = str(S.desktop_cr_next_week),
                onClick = { onShift(1) },
                enabled = canGoNext,
            )
        }
        if (!isCurrent) {
            ZillitButton(
                text = str(S.desktop_cr_go_to_this_week),
                onClick = onCurrent,
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

/** A labelled figure — the web's KPI card: a mono value over a small caps label. */
@Composable
internal fun FigureCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = ZillitTheme.colors.textPrimary,
    highlight: Boolean = false,
    sub: String? = null,
) {
    val colors = ZillitTheme.colors
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (highlight) colors.successSoft else colors.surface)
            .border(
                1.dp,
                if (highlight) colors.success.copy(alpha = HIGHLIGHT_BORDER) else colors.border,
                ZillitTheme.shapes.large,
            )
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.columnHeader,
            color = colors.textMuted,
            maxLines = 1,
        )
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = if (highlight) colors.success else valueColor,
            maxLines = 1,
        )
        sub?.let {
            ZillitText(
                text = it,
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

/** A section heading inside a tab — the web's mono `SectionHead`. */
@Composable
internal fun SectionHead(text: String, modifier: Modifier = Modifier) {
    ZillitText(
        text = text.uppercase(),
        style = ZillitTheme.typography.columnHeader,
        color = ZillitTheme.colors.textMuted,
        modifier = modifier.padding(vertical = ZillitTheme.spacing.sm),
        maxLines = 1,
    )
}

/** A label and a figure on one line — the payslip's rows and the summary cards'. */
@Composable
internal fun FigureRow(
    label: String,
    value: String,
    valueColor: Color = ZillitTheme.colors.textPrimary,
    bold: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = label,
            style = if (bold) ZillitTheme.typography.titleSmall else ZillitTheme.typography.bodyMedium,
            color = if (bold) ZillitTheme.colors.textPrimary else ZillitTheme.colors.textSecondary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(ZillitTheme.spacing.md))
        ZillitText(
            text = value,
            style = ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.Bold),
            color = valueColor,
            maxLines = 1,
        )
    }
}

/** A thin rule with breathing room, for inside a card. */
@Composable
internal fun Rule() {
    Spacer(Modifier.height(ZillitTheme.spacing.xs))
    ZillitDivider()
    Spacer(Modifier.height(ZillitTheme.spacing.xs))
}

/**
 * A timecard status's hue on the Run and Processing grids — the web's
 * `TIMECARD_STATUS_V3_TONE`: green once approved, amber while waiting,
 * red when queried, blue once paid, purple once posted.
 */
internal val TimecardStatus.tone: StatusTone
    get() = when (this) {
        TimecardStatus.Draft, TimecardStatus.Submitted, TimecardStatus.Received, TimecardStatus.Unknown ->
            StatusTone.Neutral
        TimecardStatus.AwaitingApproval, TimecardStatus.Pending, TimecardStatus.Unpaid -> StatusTone.Pending
        TimecardStatus.Queried, TimecardStatus.Rejected -> StatusTone.Rejected
        TimecardStatus.Approved, TimecardStatus.FinalApproved, TimecardStatus.Locked -> StatusTone.Ready
        TimecardStatus.Paid -> StatusTone.Progress
        TimecardStatus.Posted, TimecardStatus.Processed -> StatusTone.Escalated
    }

/** The history queue's hue — the web's `STATUS_PILL`, where paid is teal. */
internal val TimecardStatus.historyTone: StatusTone
    get() = if (this == TimecardStatus.Paid) StatusTone.Done else tone

private const val HIGHLIGHT_BORDER = 0.35f
