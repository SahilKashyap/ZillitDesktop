package com.zillit.desktop.feature.cashexpenses.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.cashexpenses.domain.CashFloat
import com.zillit.desktop.feature.cashexpenses.domain.TierApproval
import com.zillit.desktop.feature.cashexpenses.ui.CashPerson

/**
 * The small pieces the web's Approval Queue, Sign-off and Active Floats pages
 * share: the soft notice with a bold title, the tab cards, a bordered card and
 * the approval-progress list.
 */

/** The notice's tints — the web's `NOTICE_TONES`. */
internal enum class NoticeTone { Accent, Bad, Info }

/** The web's `Notice`: an icon, a bold title and a softer line after it. */
@Composable
internal fun PcNotice(title: String, body: String, tone: NoticeTone, icon: ImageVector) {
    val colors = ZillitTheme.colors
    val (soft, ink) = when (tone) {
        NoticeTone.Accent -> colors.accentSoft to colors.accentText
        NoticeTone.Bad -> colors.dangerSoft to colors.danger
        NoticeTone.Info -> colors.infoSoft to colors.info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(soft)
            .border(HAIRLINE, ink.copy(alpha = NOTICE_BORDER), ZillitTheme.shapes.large)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitIcon(icon = icon, tint = ink, size = NOTICE_ICON)
        ZillitText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(title) }
                append("  ")
                append(body)
            },
            style = ZillitTheme.typography.bodyMedium,
            color = ink,
        )
    }
}

/** One of the Approval Queue's two tab cards (`PCApprovalPage.jsx:165-182`). */
@Composable
internal fun CategoryCard(
    title: String,
    sub: String,
    icon: ImageVector,
    active: Boolean,
    unread: Int,
    info: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ZillitTheme.colors
    val soft = if (info) colors.infoSoft else colors.accentSoft
    val ink = if (info) colors.info else colors.accentText
    val edge = if (info) colors.info else colors.accent
    Row(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(if (active) soft else colors.surface)
            .border(HAIRLINE, if (active) edge else colors.border, ZillitTheme.shapes.large)
            .clickable(onClick = onClick)
            .padding(ZillitTheme.spacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
    ) {
        Box(
            modifier = Modifier
                .size(CARD_ICON_BOX)
                .clip(ZillitTheme.shapes.large)
                .background(if (active) colors.surface else soft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = icon, tint = ink, size = NOTICE_ICON)
        }
        Column(modifier = Modifier.weight(1f)) {
            ZillitText(
                text = title,
                style = ZillitTheme.typography.titleSmall,
                color = if (active) ink else colors.textPrimary,
            )
            ZillitText(
                text = sub,
                style = ZillitTheme.typography.bodySmall,
                color = if (active) ink else colors.textMuted,
            )
        }
        ZillitBadge(count = unread)
    }
}

/** A white bordered card, the web's `rounded-[14px] border` list shell. */
@Composable
internal fun PcCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .clip(ZillitTheme.shapes.large)
            .background(ZillitTheme.colors.surface)
            .border(HAIRLINE, ZillitTheme.colors.border, ZillitTheme.shapes.large),
        content = content,
    )
}

/** A small uppercase label over a value. */
@Composable
internal fun PcField(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color? = null) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        ZillitText(text = value, style = ZillitTheme.typography.bodyMedium, color = valueColor)
    }
}

/**
 * Who has signed at which level — the web's green approval rows, or
 * "No approvals yet" (`PCApprovalPage.jsx:412-449, 729-766`).
 */
@Composable
internal fun ApprovalProgress(approvals: List<TierApproval>, total: Int) {
    val colors = ZillitTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = str(S.desktop_pc_approval_progress_count, approvals.size, total).uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = colors.textMuted,
        )
        if (approvals.isEmpty()) {
            ZillitText(
                text = str(S.desktop_pc_no_approvals_yet),
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        approvals.forEach { approval ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ZillitTheme.shapes.medium)
                    .background(colors.successSoft)
                    .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CashPerson(userId = approval.userId, modifier = Modifier.weight(1f))
                ZillitText(
                    text = str(S.desktop_level_n, approval.tierNumber),
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.success,
                )
            }
        }
    }
}

/** "No approval tiers configured for this department" with the way to fix it. */
@Composable
internal fun NoTiersBanner(onSet: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.large)
            .background(colors.warningSoft)
            .padding(horizontal = ZillitTheme.spacing.md, vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ZillitText(
            text = str(S.desktop_pc_no_tiers_for_department),
            style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.warning,
            modifier = Modifier.weight(1f),
        )
        com.zillit.desktop.core.designsystem.component.ZillitButton(
            text = str(S.desktop_pc_set_approval_level_arrow),
            onClick = onSet,
            size = com.zillit.desktop.core.designsystem.component.ButtonSize.Small,
        )
    }
}

/** "Run of Show", "3 days" or "—" — the float's duration as the web words it. */
internal fun durationLabel(float: CashFloat, runOfShow: String = str(S.desktop_pc_run_of_show)): String? {
    if (float.durationType == RUN_OF_SHOW) return runOfShow
    val days = float.duration?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
    return if (days == 1) str(S.desktop_pc_days_one, days) else str(S.ah_days_format, days)
}

/**
 * A total across records that may be in different currencies — one figure
 * per currency, joined, rather than a sum that adds pounds to euros. The web
 * converts to the project default (`describeTotal`); no rates reach this
 * module, so the honest answer is the split.
 */
internal fun com.zillit.desktop.feature.cashexpenses.ui.CashUiState.describeTotal(
    amounts: List<Pair<String?, Double>>,
): String {
    val byCode = amounts.groupBy({ currencyOf(it.first) }, { it.second })
    if (byCode.isEmpty()) return formatAggregate(0.0)
    return byCode.entries.joinToString(" + ") { (code, values) -> formatMoney(values.sum(), code) }
}

internal const val RUN_OF_SHOW = "run_of_show"

private val HAIRLINE = 1.dp
private val NOTICE_ICON = 18.dp
private val CARD_ICON_BOX = 44.dp
private const val NOTICE_BORDER = 0.2f
