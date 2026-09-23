package com.zillit.desktop.feature.dealmemo.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealOverview
import com.zillit.desktop.feature.dealmemo.domain.rates.Js
import com.zillit.desktop.feature.dealmemo.domain.rates.RateFormat
import com.zillit.desktop.feature.dealmemo.ui.DealMemoEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoUiState
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.OverviewEvent
import com.zillit.desktop.feature.dealmemo.ui.components.DmButton
import com.zillit.desktop.feature.dealmemo.ui.components.DmButtonStyle
import com.zillit.desktop.feature.dealmemo.ui.components.DmCard
import com.zillit.desktop.feature.dealmemo.ui.components.DmCell
import com.zillit.desktop.feature.dealmemo.ui.components.DmColumn
import com.zillit.desktop.feature.dealmemo.ui.components.DmHoverRow
import com.zillit.desktop.feature.dealmemo.ui.components.DmIconTile
import com.zillit.desktop.feature.dealmemo.ui.components.DmIcons
import com.zillit.desktop.feature.dealmemo.ui.components.DmPersonAvatar
import com.zillit.desktop.feature.dealmemo.ui.components.DmStatusBadge
import com.zillit.desktop.feature.dealmemo.ui.components.DmTableHeader
import com.zillit.desktop.feature.dealmemo.ui.components.DmType
import com.zillit.desktop.feature.dealmemo.ui.components.dm
import com.zillit.desktop.feature.dealmemo.ui.components.rememberHover
import kotlin.math.round

/** Overview — the project-wide dashboard (`DMOverviewPage.jsx`). */
@Composable
fun OverviewPage(state: DealMemoUiState, onEvent: (DealMemoEvent) -> Unit) {
    val overview = state.overview
    val data = overview.data
    when {
        data == null && !overview.failed -> Box(
            Modifier.fillMaxSize().padding(top = 96.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitSpinner(size = 16.dp, color = Color(0xFF9CA3AF))
                ZillitText(text = str(S.dm_loading), style = DmType.sans(12.sp), color = Color(0xFF9CA3AF))
            }
        }
        data == null -> OverviewFailed(onRetry = { onEvent(OverviewEvent.Retry) })
        else -> OverviewBody(state, data, onEvent)
    }
}

@Composable
private fun OverviewFailed(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitText(text = str(S.desktop_dm_couldnt_load_the_overview), style = DmType.sans(14.sp), color = dm.ink2)
        Spacer(Modifier.height(8.dp))
        ZillitText(
            text = str(S.desktop_dm_refresh_the_page_once_the_deal_memo),
            style = DmType.sans(12.sp),
            color = dm.ink3,
        )
        Spacer(Modifier.height(12.dp))
        DmButton(str(S.retry), onClick = onRetry, style = DmButtonStyle.SmallSecondary, icon = ZillitIcons.Reload)
    }
}

@Composable
private fun OverviewBody(state: DealMemoUiState, data: DealOverview, onEvent: (DealMemoEvent) -> Unit) {
    val labels = remember(state.people, state.catalogue) { state.labels }
    ZillitScrollColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        OverviewCallout()
        Spacer(Modifier.height(22.dp))
        StatCards(data, state.projectCurrency)
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxWidth()) {
            RecentDeals(
                data.recent,
                labels,
                onViewAll = { onEvent(DealMemoEvent.Navigate(DealMemoRoute.Tab(DealTab.Deals))) },
                modifier = Modifier.weight(2f),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ByDepartment(data, labels)
                StatusSummary(data)
            }
        }
    }
}

@Composable
private fun OverviewCallout() {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(dm.amberSoft)
            .border(1.dp, dm.amberRing, shape)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DmIconTile(
            DmIcons.FileProtect,
            background = dm.card,
            ring = dm.amberRing,
            tint = dm.accent,
            size = 30.dp,
            iconSize = 15.dp,
        )
        ZillitText(
            text = buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(str(S.desktop_dm_deal_memo_overview)) }
                append(" · " + str(S.desktop_dm_overview_tagline))
            },
            style = DmType.sans(13.5.sp).copy(lineHeight = 19.sp),
            color = dm.amberInk,
        )
    }
}

private enum class StatTone { Ink, Green, Amber }

@Composable
private fun StatCards(data: DealOverview, currency: String) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 900.dp -> 4
            maxWidth >= 560.dp -> 2
            else -> 1
        }
        val cards: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> StatCard(str(S.desktop_dm_total_deals), count(data.total), StatTone.Ink, DmIcons.List, m) },
            { m -> StatCard(str(S.dm_overview_approved), count(data.approved), StatTone.Green, ZillitIcons.Shield, m) },
            { m ->
                StatCard(
                    str(S.dm_filter_status_pending),
                    count(data.awaitingApproval),
                    StatTone.Amber,
                    ZillitIcons.Clock,
                    m,
                )
            },
            { m ->
                StatCard(
                    str(S.desktop_dm_total_daily_value),
                    RateFormat.currencySymbol(currency) + RateFormat.groupAmountAuto(data.totalValue),
                    StatTone.Amber,
                    DmIcons.Dollar,
                    m,
                )
            },
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cards.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { card -> card(Modifier.weight(1f)) }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun count(value: Double): String = Js.number(value)

@Composable
private fun StatCard(label: String, value: String, tone: StatTone, icon: ImageVector, modifier: Modifier) {
    val dark = ZillitTheme.colors.isDark
    val (ink, soft, ring) = when (tone) {
        StatTone.Ink -> Triple(dm.ink, if (dark) Color.White.copy(alpha = 0.06f) else dm.soft, dm.controlBorder)
        StatTone.Green -> Triple(dm.greenInk, dm.greenSoft, dm.greenRing)
        StatTone.Amber -> Triple(dm.accent, dm.amberSoft, dm.amberRing)
    }
    DmCard(modifier = modifier, padding = PaddingValues(18.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                text = label.uppercase(),
                style = DmType.mono(10.5.sp, FontWeight.Bold, 0.11.em),
                color = dm.ink3,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            DmIconTile(icon, background = soft, ring = ring, tint = ink)
        }
        Spacer(Modifier.height(14.dp))
        ZillitText(
            text = value,
            style = DmType.mono(30.sp, FontWeight.Bold, (-0.028).em).copy(lineHeight = 30.sp),
            color = ink,
            maxLines = 1,
        )
    }
}

private val RECENT_COLUMNS get() = listOf(
    DmColumn(str(S.desktop_reference), width = 140.dp),
    DmColumn(str(S.crew_member), weight = 1.4f),
    DmColumn(str(S.desktop_dm_position), weight = 1.2f),
    DmColumn(str(S.desktop_dm_rate_day), width = 120.dp),
    DmColumn(str(S.dm_label_status), width = 180.dp),
)

@Composable
private fun RecentDeals(recent: List<DealDoc>, labels: DealCrewLabels, onViewAll: () -> Unit, modifier: Modifier) {
    DmCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = str(S.desktop_dm_recent_deal_memos),
                style = DmType.sans(15.5.sp, FontWeight.Bold, (-0.01).em),
                color = dm.ink,
                modifier = Modifier.weight(1f),
            )
            ViewAllLink(onViewAll)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
        DmTableHeader(RECENT_COLUMNS)
        if (recent.isEmpty()) {
            TableMessage(loading = false, text = str(S.dm_notices_empty))
        } else {
            recent.forEachIndexed { index, deal ->
                if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(dm.cardBorder))
                RecentRow(deal, labels)
            }
        }
    }
}

@Composable
private fun ViewAllLink(onClick: () -> Unit) {
    val (source, hovered) = rememberHover()
    Row(
        modifier = Modifier
            .hoverable(source)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            text = str(S.desktop_dm_view_all),
            style = DmType.sans(12.5.sp, FontWeight.Bold).copy(
                textDecoration = if (hovered) TextDecoration.Underline else null,
            ),
            color = dm.accent,
        )
        Spacer(Modifier.width(4.dp))
        ZillitIcon(ZillitIcons.ArrowRight, size = 10.dp, tint = dm.accent)
    }
}

@Composable
private fun RecentRow(deal: DealDoc, labels: DealCrewLabels) {
    val person = labels.labels(deal)
    DmHoverRow(onClick = null, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 14.dp)) {
        DmCell(RECENT_COLUMNS[0]) {
            ZillitText(
                text = deal.reference ?: "—",
                style = DmType.mono(12.5.sp, FontWeight.SemiBold),
                color = dm.ink2,
                maxLines = 1,
            )
        }
        DmCell(RECENT_COLUMNS[1]) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DmPersonAvatar(person.name, deal.userId, size = 28.dp)
                ZillitText(
                    text = person.name,
                    style = DmType.sans(13.5.sp, FontWeight.Bold),
                    color = dm.ink,
                    maxLines = 1,
                )
            }
        }
        DmCell(RECENT_COLUMNS[2]) {
            ZillitText(text = person.role, style = DmType.sans(13.sp), color = dm.ink3, maxLines = 1)
        }
        DmCell(RECENT_COLUMNS[3]) {
            val symbol = RateFormat.currencySymbol(deal.contractCurrency ?: "GBP")
            ZillitText(
                text = deal.dailyRate?.let { symbol + RateFormat.groupAmount(it) } ?: "—",
                style = DmType.mono(13.sp, FontWeight.Bold),
                color = dm.ink,
                maxLines = 1,
            )
        }
        DmCell(RECENT_COLUMNS[4]) { DmStatusBadge(deal.status) }
    }
}

private val BAR_COLOURS = listOf(
    Color(0xFF60A5FA),
    Color(0xFFFBBF24),
    Color(0xFFC084FC),
    Color(0xFF2DD4BF),
    Color(0xFFFB923C),
    Color(0xFFF472B6),
)

@Composable
private fun ByDepartment(data: DealOverview, labels: DealCrewLabels) {
    DmCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        ZillitText(
            text = str(S.desktop_dm_by_department),
            style = DmType.sans(14.5.sp, FontWeight.Bold, (-0.01).em),
            color = dm.ink,
        )
        Spacer(Modifier.height(14.dp))
        if (data.departmentBreakdown.isEmpty()) {
            ZillitText(
                text = str(S.desktop_no_departments_yet),
                style = DmType.sans(12.sp).copy(fontStyle = FontStyle.Italic),
                color = dm.ink3,
            )
            return@DmCard
        }
        val total = data.departmentBreakdown.sumOf { it.count }.takeIf { it > 0 } ?: 1.0
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            data.departmentBreakdown.forEachIndexed { index, row ->
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        ZillitText(
                            text = labels.departmentLabel(row.department),
                            style = DmType.sans(12.5.sp, FontWeight.SemiBold),
                            color = dm.ink2,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        ZillitText(
                            text = Js.number(row.count),
                            style = DmType.mono(12.sp, FontWeight.Bold),
                            color = dm.ink3,
                        )
                    }
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(dm.track)) {
                        val fraction = (round(row.count / total * 100) / 100).toFloat().coerceIn(0f, 1f)
                        Box(
                            Modifier.fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(BAR_COLOURS[index % BAR_COLOURS.size]),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusSummary(data: DealOverview) {
    DmCard(modifier = Modifier.fillMaxWidth(), padding = PaddingValues(18.dp)) {
        ZillitText(
            text = str(S.desktop_dm_status_summary),
            style = DmType.sans(14.5.sp, FontWeight.Bold, (-0.01).em),
            color = dm.ink,
        )
        Spacer(Modifier.height(14.dp))
        val rows = listOf(
            Triple(str(S.dm_overview_active), data.active, dm.green),
            Triple(str(S.dm_filter_status_pending), data.awaitingApproval, Color(0xFFE8861A)),
            Triple(str(S.desktop_issued), data.issued, dm.teal),
            Triple(
                str(S.dm_overview_draft),
                data.draft,
                if (ZillitTheme.colors.isDark) Color(0xFF3A4252) else Color(0xFFC9C8C2),
            ),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { (label, value, colour) ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(colour))
                    ZillitText(
                        text = label,
                        style = DmType.sans(13.sp, FontWeight.SemiBold),
                        color = dm.ink2,
                        modifier = Modifier.weight(1f),
                    )
                    ZillitText(text = Js.number(value), style = DmType.mono(13.5.sp, FontWeight.Bold), color = dm.ink)
                }
            }
        }
    }
}
