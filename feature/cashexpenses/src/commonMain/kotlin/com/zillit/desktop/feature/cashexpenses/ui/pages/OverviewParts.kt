package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitMeter
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import kotlin.time.Clock

/*
 * The pieces the three overview pages share: the web's `Notice`, `Section`
 * (title and sub above a card), `Row`, and the tone colours its tiles use
 * (`PCOverviewPage.jsx:53-232`, `PCCrewOverviewPage.jsx:43-168`).
 */

/** The web's notice banner: a bold title, an em dash, then the body. */
@Composable
internal fun OverviewNotice(
    title: String?,
    body: String?,
    tone: StatusTone = StatusTone.Pending,
    icon: ImageVector? = null,
) {
    val text = listOfNotNull(title?.takeIf { it.isNotBlank() }, body?.takeIf { it.isNotBlank() })
        .joinToString(" — ")
    ZillitNotice(text = text, tone = tone, icon = icon)
}

/**
 * A titled card: the title and its sub sit above the card, the [right]
 * content (a pill, a button, "View more") at the end of that line.
 */
@Composable
internal fun OverviewSection(
    title: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    right: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                ZillitText(text = title, style = ZillitTheme.typography.titleSmall, maxLines = 1)
                if (sub != null) {
                    ZillitText(
                        text = sub,
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                }
            }
            right?.invoke(this)
        }
        OverviewCard(content = content)
    }
}

/** The bordered white card every overview section sits in. */
@Composable
internal fun OverviewCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(CARD_RADIUS)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, shape),
        content = content,
    )
}

/** One row of a card, divided from the next unless it is the [last]. */
@Composable
internal fun OverviewRow(
    last: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let { if (onClick != null) it.clickable(onClick = onClick) else it }
                .padding(horizontal = ROW_PAD_H, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            content = content,
        )
        if (!last) RowRule()
    }
}

/** A hairline between rows — in a column, never a row, where full width collapses it. */
@Composable
internal fun RowRule() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(ZillitTheme.colors.divider))
}

/** The empty line inside a card. */
@Composable
internal fun OverviewEmpty(text: String) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodySmall,
        color = ZillitTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = EMPTY_PAD, horizontal = ROW_PAD_H),
    )
}

/** The small square chip an icon sits in, tinted by [tone]. */
@Composable
internal fun IconChip(icon: ImageVector, tone: StatusTone, size: androidx.compose.ui.unit.Dp = CHIP_SIZE) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(CHIP_RADIUS))
            .background(toneSoft(tone)),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(icon = icon, tint = toneColor(tone), size = CHIP_ICON)
    }
}

/** "View more →" at the end of a section's title line. */
@Composable
internal fun ViewMoreLink(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clickable(onClick = onClick).padding(ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitText(text = text, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        ZillitIcon(icon = ZillitIcons.ArrowRight, tint = ZillitTheme.colors.textSecondary, size = LINK_ICON)
    }
}

/** A label over a mono figure — the float card's stats and the recent floats' mini stats. */
@Composable
internal fun FigureStat(
    label: String,
    value: String,
    tone: StatusTone?,
    modifier: Modifier = Modifier,
    large: Boolean = true,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
            maxLines = 1,
        )
        ZillitText(
            text = value,
            style = if (large) {
                ZillitTheme.typography.displayLarge.copy(fontFamily = ZillitTheme.typography.numeric.fontFamily)
            } else {
                ZillitTheme.typography.numeric.copy(fontWeight = FontWeight.SemiBold)
            },
            color = tone?.let { toneColor(it) } ?: ZillitTheme.colors.textPrimary,
            maxLines = 1,
        )
    }
}

/** A labelled progress bar — the float's spend and the department's category bars. */
@Composable
internal fun LabelledMeter(label: String, trailing: String, fraction: Float, tone: StatusTone) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = label,
                style = ZillitTheme.typography.bodySmall,
                color = ZillitTheme.colors.textSecondary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitText(text = trailing, style = ZillitTheme.typography.numeric, maxLines = 1)
        }
        ZillitMeter(fraction = fraction.coerceIn(0f, 1f), tone = tone)
    }
}

@Composable
internal fun toneColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> ZillitTheme.colors.textSecondary
    StatusTone.Pending -> ZillitTheme.colors.warning
    StatusTone.Progress -> ZillitTheme.colors.info
    StatusTone.Ready -> ZillitTheme.colors.success
    StatusTone.Done -> ZillitTheme.colors.teal
    StatusTone.Rejected -> ZillitTheme.colors.danger
    StatusTone.Escalated -> ZillitTheme.colors.violet
    StatusTone.InTransit -> ZillitTheme.colors.gold
}

@Composable
internal fun toneSoft(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> ZillitTheme.colors.surfaceHover
    StatusTone.Pending -> ZillitTheme.colors.warningSoft
    StatusTone.Progress -> ZillitTheme.colors.infoSoft
    StatusTone.Ready -> ZillitTheme.colors.successSoft
    StatusTone.Done -> ZillitTheme.colors.tealSoft
    StatusTone.Rejected -> ZillitTheme.colors.dangerSoft
    StatusTone.Escalated -> ZillitTheme.colors.violetSoft
    StatusTone.InTransit -> ZillitTheme.colors.goldSoft
}

/** The web's remaining-balance colour: red overspent, violet exactly spent, green otherwise. */
internal fun remainingTone(balance: Double): StatusTone = when {
    balance < 0 -> StatusTone.Rejected
    balance == 0.0 -> StatusTone.Escalated
    else -> StatusTone.Ready
}

/**
 * A float's balance as the accountant overview and the department view work
 * it out: issued (else requested) less receipts (`PCOverviewPage.jsx:558-560`,
 * `PCDeptViewPage.jsx:105-107`) — the rows there may carry no `balance`.
 */
internal val CashFloat.registerIssued: Double
    get() = issuedAmount.takeIf { it > 0 } ?: requestedAmount

internal val CashFloat.registerBalance: Double
    get() = registerIssued - receiptsAmount

/** Whole days since [millis], never negative — the register's Age column. */
internal fun daysSince(millis: Long?, now: Long = Clock.System.now().toEpochMilliseconds()): Int {
    if (millis == null || millis <= 0) return 0
    return ((now - millis) / MILLIS_PER_DAY).toInt().coerceAtLeast(0)
}

/** "3 batches …" or "1 batch …" from a pair of keys. */
internal fun countText(count: Int, one: String, other: String): String =
    if (count == 1) str(one, count) else str(other, count)

/** "View more" everywhere. */
internal val viewMoreLabel: String get() = str(S.desktop_pc_view_more)

private val CARD_RADIUS = 14.dp
private val CHIP_RADIUS = 10.dp
private val CHIP_SIZE = 36.dp
private val CHIP_ICON = 17.dp
private val LINK_ICON = 13.dp
private val ROW_PAD_H = 18.dp
private val EMPTY_PAD = 32.dp
private const val MILLIS_PER_DAY = 86_400_000L
