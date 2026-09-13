// Compose screens read top to bottom in layout order; splitting them by length hides that.
@file:Suppress("LongMethod")

package com.zillit.desktop.feature.callsheet.ui.pages

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.callsheet.domain.CallSheetSummary
import com.zillit.desktop.feature.callsheet.domain.formatDateTime
import com.zillit.desktop.feature.callsheet.domain.relativeLong
import com.zillit.desktop.feature.callsheet.domain.shootDayLabel
import com.zillit.desktop.feature.callsheet.ui.ListEvent
import com.zillit.desktop.feature.callsheet.ui.SheetEvent
import com.zillit.desktop.feature.callsheet.ui.SheetUiState
import com.zillit.desktop.feature.callsheet.ui.components.ButtonKind
import com.zillit.desktop.feature.callsheet.ui.components.Face
import com.zillit.desktop.feature.callsheet.ui.components.MetaCell
import com.zillit.desktop.feature.callsheet.ui.components.SheetButton
import com.zillit.desktop.feature.callsheet.ui.components.SheetEmptyState
import com.zillit.desktop.feature.callsheet.ui.components.SheetErrorLine
import com.zillit.desktop.feature.callsheet.ui.components.SheetTable
import com.zillit.desktop.feature.callsheet.ui.components.TableColumn
import com.zillit.desktop.feature.callsheet.ui.components.sheetText
import com.zillit.desktop.feature.callsheet.ui.theme.SheetIcons
import com.zillit.desktop.feature.callsheet.ui.theme.SheetTheme

/**
 * Published Call sheet — the live sheet as a hero card, and the older
 * versions behind "History (n)" (`PublishedTab.jsx`).
 */
@Composable
internal fun PublishedPage(state: SheetUiState, onEvent: (SheetEvent) -> Unit, nowMillis: Long) {
    val list = state.lists.published
    val sorted = list.rows.sortedByDescending { it.publishedOn ?: it.updatedOn ?: 0L }
    val fallbackTotal = sorted.firstNotNullOfOrNull { it.shared?.totalDays?.trim()?.ifEmpty { null } }.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        list.error?.let { SheetErrorLine(it) { onEvent(ListEvent.Retry) } }
        val latest = sorted.firstOrNull()
        when {
            latest != null -> HeroCard(state, latest, nowMillis, onEvent)
            !list.loaded -> LoadingBlock("Loading published call sheets…")
            else -> SheetEmptyState(
                "No published call sheets yet",
                minHeight = 260.dp,
                sub = "Published call sheets will appear here once approved and published.",
            )
        }
        val older = sorted.drop(1)
        if (older.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetButton(
                    text = if (state.showOlderPublished) "Hide History" else "History (${older.size})",
                    onClick = { onEvent(ListEvent.ToggleOlderPublished) },
                    kind = ButtonKind.Outline,
                    radius = 12.dp,
                    fontSize = 14.sp,
                    horizontalPadding = 16.dp,
                    height = 38.dp,
                )
                if (state.showOlderPublished) OlderTable(state, older, fallbackTotal, onEvent)
            }
        }
    }
}

@Composable
private fun HeroCard(state: SheetUiState, sheet: CallSheetSummary, nowMillis: Long, onEvent: (SheetEvent) -> Unit) {
    val colors = SheetTheme.colors
    Column(
        Modifier.fillMaxWidth()
            .shadow(1.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(12.dp)),
    ) {
        HeroHeader(sheet, nowMillis)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        HeroMeta(state, sheet, nowMillis)
        Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
        Row(
            Modifier.fillMaxWidth()
                .background(if (colors.isDark) colors.elevated else Color(0xFFFCFCFD))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "This is the current live version of the call sheet.",
                style = sheetText(11.sp),
                color = colors.textMuted,
                modifier = Modifier.weight(1f),
            )
            if (state.isPoster) {
                SheetButton(
                    "Attach Document",
                    { onEvent(ListEvent.AttachDocument(sheet)) },
                    kind = ButtonKind.Secondary,
                    icon = ZillitIcons.Paperclip,
                    fontSize = 12.sp,
                    height = 32.dp,
                )
            }
            SheetButton(
                "View PDF",
                { onEvent(ListEvent.View(sheet)) },
                kind = ButtonKind.Accent,
                icon = ZillitIcons.Eye,
                fontSize = 12.sp,
                height = 32.dp,
            )
        }
    }
}

@Composable
private fun HeroHeader(sheet: CallSheetSummary, nowMillis: Long) {
    val colors = SheetTheme.colors
    val background = if (colors.isDark) {
        Brush.horizontalGradient(listOf(colors.surface, colors.surface))
    } else {
        Brush.horizontalGradient(listOf(colors.elevated, Color.White))
    }
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
                Text("Currently Published", style = sheetText(14.sp, FontWeight.SemiBold), color = colors.textStrong)
                LivePill()
            }
            val subject = sheet.name.ifBlank { "${shootDayLabel(sheet.shared)} call sheet" }
            val published = sheet.publishedOn?.let { " • Published ${relativeLong(it, nowMillis)}" }.orEmpty()
            Text(
                "$subject$published",
                style = sheetText(12.sp),
                color = colors.textMeta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        val serial = sheet.serialNo.ifBlank { sheet.id }
        ZillitTooltip(serial) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    SheetIcons.IdCard,
                    contentDescription = null,
                    tint = colors.textMeta,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    if (serial.length > SERIAL_SHOWN) "${serial.take(SERIAL_SHOWN)}…" else serial,
                    style = sheetText(11.sp).copy(fontFamily = FontFamily.Monospace),
                    color = colors.textMeta,
                )
            }
        }
    }
}

private const val SERIAL_SHOWN = 10

@Composable
private fun LivePill() {
    val colors = SheetTheme.colors
    val pulse = rememberInfiniteTransition()
    val alpha by pulse.animateFloat(1f, 0.5f, infiniteRepeatable(tween(PULSE_MS), RepeatMode.Reverse))
    Row(
        Modifier.clip(CircleShape).background(colors.greenBg).padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val dot = if (colors.isDark) colors.green else Color(0xFF12B76A)
        Box(Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(dot))
        Text("LIVE", style = sheetText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp), color = colors.green)
    }
}

private const val PULSE_MS = 1000

@Composable
private fun HeroMeta(state: SheetUiState, sheet: CallSheetSummary, nowMillis: Long) {
    val member = state.member(sheet.createdById)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp)) {
        val columns = if (maxWidth >= 720.dp) 4 else 2
        val items: List<@Composable (Modifier) -> Unit> = listOf(
            { m -> MetaItem(m, ZillitIcons.Calendar, null, "Shoot Day", shootDayLabel(sheet.shared), null) },
            { m ->
                MetaItem(
                    m,
                    null,
                    sheet,
                    "Created By",
                    member?.fullName ?: sheet.createdBy.ifBlank { "-" },
                    member?.designation,
                )
            },
            { m -> MetaItem(m, ZillitIcons.Clock, null, "Created At", formatDateTime(sheet.createdOn), null) },
            { m ->
                MetaItem(
                    m,
                    ZillitIcons.Clock,
                    null,
                    "Published At",
                    formatDateTime(sheet.publishedOn),
                    relativeLong(sheet.publishedOn, nowMillis),
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
    person: CallSheetSummary?,
    label: String,
    value: String,
    hint: String?,
) {
    val colors = SheetTheme.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (person != null) {
            Face(person.createdById, value, 32.dp)
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
                style = sheetText(10.sp, FontWeight.SemiBold).copy(letterSpacing = 0.5.sp),
                color = colors.textMuted,
            )
            Text(
                value,
                style = sheetText(14.sp, FontWeight.Medium),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    hint,
                    style = sheetText(11.sp),
                    color = colors.textMeta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun OlderTable(
    state: SheetUiState,
    rows: List<CallSheetSummary>,
    fallbackTotal: String,
    onEvent: (SheetEvent) -> Unit,
) {
    SheetTable(
        columns = listOf(
            TableColumn("S.No", width = 72.dp),
            TableColumn("Day", width = 110.dp),
            TableColumn("Created By", weight = 1f),
            TableColumn("Created At", width = 190.dp),
            TableColumn("Published At", width = 190.dp),
            TableColumn("Actions", width = 200.dp),
        ),
        rows = rows,
        minWidth = 900.dp,
    ) { row, column, _ ->
        when (column) {
            0 -> MetaCell(row.serialNo.ifBlank { "-" })
            1 -> MetaCell(shootDayLabel(row.shared, fallbackTotal))
            2 -> CreatorCell(state, row)
            3 -> MetaCell(formatDateTime(row.createdOn))
            4 -> MetaCell(formatDateTime(row.publishedOn))
            else -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val loading = state.historyLoadingId == row.id
                SheetButton(
                    if (loading) "Loading…" else "View History",
                    { onEvent(ListEvent.OpenHistory(row, "History")) },
                    kind = ButtonKind.Outline,
                    enabled = state.historyLoadingId == null,
                    height = 26.dp,
                    fontSize = 12.sp,
                    horizontalPadding = 8.dp,
                )
                SheetButton(
                    "View",
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
