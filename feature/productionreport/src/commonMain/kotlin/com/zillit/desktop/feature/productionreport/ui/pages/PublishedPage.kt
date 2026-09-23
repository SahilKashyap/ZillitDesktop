package com.zillit.desktop.feature.productionreport.ui.pages

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.productionreport.domain.ReportSummary
import com.zillit.desktop.feature.productionreport.domain.formatDateTime
import com.zillit.desktop.feature.productionreport.domain.relativeLong
import com.zillit.desktop.feature.productionreport.domain.shootDayLabel
import com.zillit.desktop.feature.productionreport.ui.ListEvent
import com.zillit.desktop.feature.productionreport.ui.ReportEvent
import com.zillit.desktop.feature.productionreport.ui.ReportUiState
import com.zillit.desktop.feature.productionreport.ui.WorkflowEvent
import com.zillit.desktop.feature.productionreport.ui.components.ButtonKind
import com.zillit.desktop.feature.productionreport.ui.components.Face
import com.zillit.desktop.feature.productionreport.ui.components.MetaCell
import com.zillit.desktop.feature.productionreport.ui.components.ReportButton
import com.zillit.desktop.feature.productionreport.ui.components.ReportEmptyState
import com.zillit.desktop.feature.productionreport.ui.components.ReportErrorLine
import com.zillit.desktop.feature.productionreport.ui.components.ReportTable
import com.zillit.desktop.feature.productionreport.ui.components.TableColumn
import com.zillit.desktop.feature.productionreport.ui.components.reportText
import com.zillit.desktop.feature.productionreport.ui.theme.ReportTheme

/**
 * Published — the live report as a hero card, and the older versions behind
 * "History (n)" (`PublishedTab.jsx`).
 */
@Composable
internal fun PublishedPage(state: ReportUiState, onEvent: (ReportEvent) -> Unit, nowMillis: Long) {
    val list = state.lists.published
    val sorted = list.rows.sortedByDescending { it.publishedOn ?: it.updatedOn ?: 0L }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        list.error?.let { ReportErrorLine(it) { onEvent(ListEvent.Retry) } }
        val latest = sorted.firstOrNull()
        if (latest == null) {
            ReportEmptyState(
                if (list.loaded || !list.loading) {
                    str(S.desktop_pr_no_published_yet)
                } else {
                    str(S.desktop_pr_loading_published)
                },
            )
        } else {
            HeroCard(state, latest, nowMillis, onEvent)
        }
        val older = sorted.drop(1)
        if (older.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ReportButton(
                    text = if (state.showOlderPublished) {
                        str(S.desktop_hide_history)
                    } else {
                        str(S.desktop_history_n, older.size)
                    },
                    onClick = { onEvent(ListEvent.ToggleOlderPublished) },
                    kind = ButtonKind.Outline,
                    radius = 12.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 16.dp,
                    height = 38.dp,
                )
                if (state.showOlderPublished) OlderTable(state, older, onEvent)
            }
        }
    }
}

@Composable
private fun HeroCard(state: ReportUiState, report: ReportSummary, nowMillis: Long, onEvent: (ReportEvent) -> Unit) {
    val colors = ReportTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        HeroHeader(report, nowMillis)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        HeroMeta(state, report, nowMillis)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            Modifier.fillMaxWidth().background(colors.elevated).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                str(S.desktop_pr_current_live_version),
                style = reportText(11.sp),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            ReportButton(
                str(S.ah_attach_document),
                { onEvent(ListEvent.AttachDocument) },
                kind = ButtonKind.Secondary,
                icon = ZillitIcons.Paperclip,
                fontSize = 12.sp,
            )
            if (state.canDistribute) {
                ReportButton(
                    str(S.dd_publish_to_distribution),
                    { onEvent(WorkflowEvent.SendToDocDist(report, fromDraft = false)) },
                    kind = ButtonKind.Secondary,
                    icon = ZillitIcons.Send,
                    fontSize = 12.sp,
                )
            }
            ReportButton(
                str(S.ah_view_pdf),
                { onEvent(ListEvent.View(report)) },
                kind = ButtonKind.Accent,
                icon = ZillitIcons.Eye,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun HeroHeader(report: ReportSummary, nowMillis: Long) {
    val colors = ReportTheme.colors
    val background = if (colors.isDark) Brush.horizontalGradient(listOf(
        colors.surface,
        colors.surface,
    )) else Brush.horizontalGradient(listOf(colors.elevated, Color.White))
    Row(
        Modifier.fillMaxWidth().background(background).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(colors.greenBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(ZillitIcons.File, contentDescription = null, tint = colors.green, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    str(S.desktop_currently_published),
                    style = reportText(14.sp, FontWeight.SemiBold),
                    color = colors.textStrong,
                )
                LivePill()
            }
            val subject = report.name.ifBlank { str(S.desktop_pr_day_production_report, shootDayLabel(report.shared)) }
            val published = report.publishedOn?.let { " • Published ${relativeLong(it, nowMillis)}" }.orEmpty()
            Text(
                "$subject$published",
                style = reportText(12.sp),
                color = colors.textMeta,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            if (report.serialNo.isNotBlank()) "#${report.serialNo}" else "${report.id.take(ID_PREFIX)}…",
            style = reportText(11.sp).copy(fontFamily = FontFamily.Monospace),
            color = colors.textMeta,
        )
    }
}

private const val ID_PREFIX = 10

@Composable
private fun LivePill() {
    val colors = ReportTheme.colors
    val pulse = rememberInfiniteTransition()
    val alpha by pulse.animateFloat(1f, 0.5f, infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse))
    Row(
        Modifier.clip(CircleShape).background(colors.greenBg).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(Color(0xFF12B76A)))
        Text(
            str(S.desktop_status_live),
            style = reportText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
            color = colors.green,
        )
    }
}

private const val PULSE_MS = 1000

@Composable
private fun HeroMeta(state: ReportUiState, report: ReportSummary, nowMillis: Long) {
    val member = state.member(report.createdById)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val columns = if (maxWidth >= 720.dp) 4 else 2
        val items: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> MetaItem(m, ZillitIcons.Calendar, null, str(S.pr_shoot_day), shootDayLabel(report.shared), null) },
            { m ->
                MetaItem(m, null, report, str(S.pr_created_by), report.createdBy.ifBlank { "-" }, member?.designation)
            },
            { m ->
                MetaItem(m, ZillitIcons.Clock, null, str(S.ah_lbl_created_at), formatDateTime(report.createdOn), null)
            },
            { m ->
                MetaItem(
                    m,
                    ZillitIcons.Clock,
                    null,
                    str(S.desktop_published_at),
                    formatDateTime(report.publishedOn),
                    relativeLong(report.publishedOn, nowMillis),
                )
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items.chunked(columns).forEach { chunk ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    chunk.forEach { it(Modifier.weight(1f)) }
                    repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun MetaItem(
    modifier: Modifier,
    icon: ImageVector?,
    person: ReportSummary?,
    label: String,
    value: String,
    hint: String?,
) {
    val colors = ReportTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (person != null) {
            Face(person.createdById, person.createdBy, 32.dp)
        } else if (icon != null) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(colors.sunken),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
            }
        }
        Column {
            Text(
                label.uppercase(),
                style = reportText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = colors.textMuted,
            )
            Text(value, style = reportText(14.sp, FontWeight.Medium), color = colors.textPrimary, maxLines = 1)
            if (!hint.isNullOrBlank()) Text(hint, style = reportText(11.sp), color = colors.textMeta, maxLines = 1)
        }
    }
}

@Composable
private fun OlderTable(state: ReportUiState, rows: List<ReportSummary>, onEvent: (ReportEvent) -> Unit) {
    ReportTable(
        columns = listOf(
            TableColumn(str(S.desktop_s_no), width = 70.dp),
            TableColumn(str(S.bs_day), width = 100.dp),
            TableColumn(str(S.pr_created_by), weight = 1.2f),
            TableColumn(str(S.ah_lbl_created_at), width = 190.dp),
            TableColumn(str(S.desktop_published_at), width = 190.dp),
            TableColumn(str(S.dd_actions), width = 210.dp),
        ),
        rows = rows,
        minWidth = 900.dp,
    ) { row, column, _ ->
        when (column) {
            0 -> MetaCell(row.serialNo.ifBlank { "-" })
            1 -> MetaCell(shootDayLabel(row.shared))
            2 -> CreatorCell(state, row)
            3 -> MetaCell(formatDateTime(row.createdOn))
            4 -> MetaCell(formatDateTime(row.publishedOn))
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val loading = state.historyLoadingId == row.id
                ReportButton(
                    if (loading) str(S.pr_loading) else str(S.cs_action_view_history),
                    { onEvent(ListEvent.OpenHistory(row, str(S.history))) },
                    kind = ButtonKind.Outline,
                    enabled = state.historyLoadingId == null,
                    height = 26.dp,
                    fontSize = 12.sp,
                    horizontalPadding = 8.dp,
                )
                ReportButton(
                    str(S.view),
                    { onEvent(ListEvent.View(row)) },
                    kind = ButtonKind.Warning,
                    height = 26.dp,
                    fontSize = 12.sp,
                    horizontalPadding = 8.dp,
                )
            }
        }
    }
}
