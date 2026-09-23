// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.domain.relativeShort
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/** The approvals counter a card can show, with its hover list. */
internal class CardApprovals(val approved: Int, val total: Int, val popover: (@Composable () -> Unit)?)

/** A secondary text link in a card's action strip. */
internal class CardLink(
    val label: String,
    val icon: ImageVector? = null,
    val badge: Int = 0,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

/** A primary pill. */
internal class CardPill(
    val label: String,
    val icon: ImageVector,
    val kind: PillKind,
    val tooltip: String,
    val wide: Boolean = true,
    val onClick: () -> Unit,
)

internal enum class PillKind { Navy, Accent, Approve, Danger, Outline }

/**
 * `SheetGridCard` — the Cards view: an accent strip, the title and status,
 * the creator, the day progress and approvals, the timestamps, then the
 * action links and pills.
 */
@Composable
internal fun SheetGridCard(
    row: CallSheetSummary,
    creatorName: String,
    creatorDesignation: String,
    nowMillis: Long,
    approvals: CardApprovals?,
    links: List<CardLink>,
    pills: List<CardPill>,
    modifier: Modifier = Modifier,
    /** The unread REPORT number beside the title; nothing at zero. */
    reportBadge: Int = 0,
) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val lift by animateDpAsState(if (hovered) (-2).dp else 0.dp, tween(CARD_MS))
    Column(
        modifier = modifier
            .offset(y = lift)
            .shadow(if (hovered) 10.dp else 0.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, if (hovered) colors.accent.copy(alpha = 0.4f) else colors.border, RoundedCornerShape(12.dp))
            .hoverable(source),
    ) {
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .background(Brush.horizontalGradient(listOf(colors.accent, Color(0xFFFDB022), colors.accent))),
        )
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Eyebrow(str(S.cs_app_name), modifier = Modifier.weight(1f), strong = true)
                StatusBadge(row.status, row.statusLabel)
            }
            Row(
                Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The name truncates on its own, so a long title never clips the number.
                Text(
                    row.name.ifBlank { str(S.untitled) },
                    style = sheetText(18.sp, FontWeight.Bold, 22.sp),
                    color = colors.textStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                InlineCount(reportBadge)
            }
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Face(row.createdById, creatorName.ifBlank { row.createdBy }, 36.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(
                    creatorName.ifBlank { row.createdBy.ifBlank { "—" } },
                    style = sheetText(14.sp, FontWeight.SemiBold),
                    color = colors.textPrimary,
                    maxLines = 1,
                )
                if (creatorDesignation.isNotBlank()) Text(
                    creatorDesignation,
                    style = sheetText(12.sp),
                    color = colors.textTertiary,
                    maxLines = 1,
                )
            }
        }
        DayAndApprovals(row, approvals)
        Timestamps(row, nowMillis)
        if (links.isNotEmpty()) CardLinks(links)
        if (pills.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { pills.forEach { CardPillButton(it) } }
        }
    }
}

private const val CARD_MS = 200

@Composable
private fun DayAndApprovals(row: CallSheetSummary, approvals: CardApprovals?) {
    val colors = SheetTheme.colors
    val day = row.shared?.shootDayNumber?.trim()?.toIntOrNull() ?: 0
    val total = row.shared?.totalDays?.trim()?.toIntOrNull() ?: 0
    if (total <= 0 && approvals == null) return
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderFaint))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (total > 0) {
            Text(
                str(S.desktop_day_n_slash, day) + " ",
                style = sheetText(13.sp, FontWeight.SemiBold),
                color = colors.textPrimary,
            )
            Text(
                "$total",
                style = sheetText(13.sp, FontWeight.Medium),
                color = colors.textMuted,
                modifier = Modifier.offset(x = (-12).dp),
            )
            BoxWithConstraints(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(colors.gridLine)) {
                val fraction = (day.toFloat() / total).coerceIn(0f, 1f)
                Box(
                    Modifier.fillMaxHeight().width(maxWidth * fraction)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF53B1FD), Color(0xFF2E90FA)))),
                )
            }
        }
        if (approvals != null) ApprovalsCounter(approvals)
    }
}

@Composable
private fun ApprovalsCounter(approvals: CardApprovals) {
    val colors = SheetTheme.colors
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                str(S.desktop_approvals_colon),
                style = sheetText(13.sp, FontWeight.Medium),
                color = colors.textTertiary,
            )
            ApprovalsGlyph(approvals.approved, approvals.total)
            Text("${approvals.approved}", style = sheetText(13.sp, FontWeight.SemiBold), color = colors.textPrimary)
            Text("/ ${approvals.total}", style = sheetText(13.sp), color = colors.textMuted)
        }
    }
    val popover = approvals.popover
    if (popover == null) content() else HoverCard(str(S.onboarding_status), trigger = content, content = popover)
}

/** A 12 px pie of approved over total. */
@Composable
internal fun ApprovalsGlyph(approved: Int, total: Int) {
    val colors = SheetTheme.colors
    val sweep = if (total > 0) 360f * approved / total else 0f
    Canvas(Modifier.size(12.dp)) {
        drawCircle(colors.gridLine)
        drawArc(Color(0xFF067647), startAngle = -90f, sweepAngle = sweep, useCenter = true)
        val ring = 1.5.dp.toPx()
        drawCircle(colors.surface, radius = size.minDimension / 2 - ring / 2, style = Stroke(width = ring))
    }
}

@Composable
private fun Timestamps(row: CallSheetSummary, nowMillis: Long) {
    val colors = SheetTheme.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TimestampRow(str(S.drive_created), row.createdOn)
        if (row.updatedOn != null) {
            TimestampRow(str(S.desktop_updated), row.updatedOn)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    ZillitIcons.Clock,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(11.dp),
                )
                Text(
                    str(S.desktop_last_updated, relativeShort(row.updatedOn, nowMillis)),
                    style = sheetText(11.sp),
                    color = colors.textMuted,
                )
            }
        } else {
            TimestampRow(str(S.desktop_updated), null)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderFaint))
}

@Composable
private fun TimestampRow(label: String, millis: Long?) {
    val colors = SheetTheme.colors
    val formatted = formatDateTime(millis)
    val date = formatted.substringBefore(" | ")
    val time = formatted.substringAfter(" | ", "")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ZillitIcons.Clock, contentDescription = null, tint = colors.textMuted, modifier = Modifier.size(12.dp))
        Text(label, style = sheetText(12.sp, FontWeight.SemiBold), color = colors.textSecondary)
        Text("•", style = sheetText(12.sp), color = colors.textMuted)
        Text(
            if (millis == null && label == str(S.desktop_updated)) "—" else date,
            style = sheetText(12.sp, FontWeight.Medium),
            color = colors.textPrimary,
        )
        if (time.isNotEmpty()) {
            Text("•", style = sheetText(12.sp), color = colors.textMuted)
            Text(time, style = sheetText(12.sp), color = colors.textSecondary)
        }
    }
}

/**
 * The secondary links, three to a line — the web crams up to six into one
 * row that cannot wrap; here a long set takes a second line instead.
 */
@Composable
private fun CardLinks(links: List<CardLink>) {
    val colors = SheetTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        links.chunked(LINKS_PER_LINE).forEachIndexed { line, chunk ->
            if (line > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderFaint))
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                chunk.forEachIndexed { index, link ->
                    if (index > 0) Box(Modifier.width(1.dp).height(18.dp).background(colors.borderFaint))
                    CardLinkButton(link)
                }
                repeat(LINKS_PER_LINE - chunk.size) {
                    if (links.size > LINKS_PER_LINE) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.borderFaint))
}

private const val LINKS_PER_LINE = 3

@Composable
private fun RowScope.CardLinkButton(link: CardLink) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .weight(1f)
            .hoverable(source)
            .plainClick(enabled = link.enabled, source = source, onClick = link.onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = if (hovered && link.enabled) colors.accent else colors.textPrimary.copy(
            alpha = if (link.enabled) 1f else DISABLED_ALPHA,
        )
        link.icon?.let { Icon(it, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp)) }
        Text(
            link.label,
            style = sheetText(13.sp, FontWeight.Medium, 16.sp),
            color = tint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (link.badge > 0) CornerBadge(link.badge)
    }
}

@Composable
private fun RowScope.CardPillButton(pill: CardPill) {
    val colors = SheetTheme.colors
    val (source, hovered) = rememberHover()
    val (bg, fg, border) = when (pill.kind) {
        PillKind.Navy -> Triple(if (hovered) colors.navyHover else colors.navy, Color.White, Color.Transparent)
        PillKind.Accent -> Triple(if (hovered) colors.accentHover else colors.accent, Color.White, Color.Transparent)
        PillKind.Approve -> Triple(
            if (hovered) Color(0xFF055F3A) else Color(0xFF067647),
            Color.White,
            Color.Transparent,
        )
        PillKind.Danger -> Triple(if (hovered) colors.redBg else Color.Transparent, colors.red, colors.redBorder)
        PillKind.Outline -> Triple(
            if (hovered) colors.hover else Color.Transparent,
            colors.textPrimary,
            if (hovered) colors.textMuted else colors.borderStrong,
        )
    }
    com.zillit.desktop.core.designsystem.component.ZillitTooltip(pill.tooltip) {
        Row(
            modifier = Modifier
                .then(if (pill.wide) Modifier.weight(1f) else Modifier)
                .height(40.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, border, CircleShape)
                .hoverable(source)
                .plainClick(source = source, onClick = pill.onClick)
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(pill.icon, contentDescription = null, tint = fg, modifier = Modifier.size(14.dp))
            Text(pill.label, style = sheetText(14.sp, FontWeight.SemiBold), color = fg, maxLines = 1)
        }
    }
}

/** The card grid: one, two or three columns by the width it has. */
@Composable
internal fun <T> CardGrid(items: List<T>, modifier: Modifier = Modifier, card: @Composable (T, Modifier) -> Unit) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 1248.dp -> 3
            maxWidth >= 608.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    chunk.forEach { item -> card(item, Modifier.weight(1f)) }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
