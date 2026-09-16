@file:Suppress("LongMethod", "TooManyFunctions") // A table is a linear layout; one composable per column group.

package com.zillit.desktop.feature.recce.ui.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.units.ProductionUnit
import com.zillit.desktop.feature.recce.domain.Recce
import com.zillit.desktop.feature.recce.domain.RecceClock
import com.zillit.desktop.feature.recce.domain.RecceQuery
import com.zillit.desktop.feature.recce.ui.RecceEvent
import com.zillit.desktop.feature.recce.ui.RecceFilter
import com.zillit.desktop.feature.recce.ui.RecceUiState
import com.zillit.desktop.feature.recce.ui.components.AvatarStack
import com.zillit.desktop.feature.recce.ui.components.MutedText
import com.zillit.desktop.feature.recce.ui.components.RecceBody
import com.zillit.desktop.feature.recce.ui.components.RecceCard
import com.zillit.desktop.feature.recce.ui.components.RecceColors
import com.zillit.desktop.feature.recce.ui.components.RecceIcons
import com.zillit.desktop.feature.recce.ui.components.RecceSegmented
import com.zillit.desktop.feature.recce.ui.components.RecceTag
import com.zillit.desktop.feature.recce.ui.components.RecceToolHeader
import com.zillit.desktop.feature.recce.ui.components.SegmentOption
import com.zillit.desktop.feature.recce.ui.components.TagKind
import com.zillit.desktop.feature.recce.ui.components.UnitTag

/**
 * The list — the web's `RecceIndex`: segmented All / Published / Drafts with
 * counts, a search box and a unit filter over a table of one server page,
 * paged by the footer.
 */
@Composable
internal fun RecceIndexPage(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    Column {
        RecceToolHeader(
            title = "Recce",
            onBack = null,
            actions = {
                ZillitButton(
                    text = "Create Recce",
                    onClick = { onEvent(RecceEvent.New) },
                    leadingIcon = ZillitIcons.Add,
                )
            },
        )
        RecceBody {
            MutedText("Location scout schedules — personnel, timings and locations for every stop.")
            Spacer(Modifier.height(16.dp))
            if (state.viewer.isBlocked) {
                ZillitNotice(text = "You do not have access to the Recce tool.", tone = StatusTone.Rejected)
                Spacer(Modifier.height(12.dp))
            }
            ErrorNotice(state, onEvent)
            Toolbar(state, onEvent)
            Spacer(Modifier.height(16.dp))
            if (state.loading && state.recces.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
                    ZillitSpinner()
                }
            } else {
                RecceCard {
                    TableHeader()
                    val rows = state.visible
                    if (rows.isEmpty()) {
                        EmptyRows(if (state.counts.all == 0) "No recces yet" else "No recces match your filters")
                    }
                    rows.forEachIndexed { index, recce ->
                        RecceRow(state, recce, last = index == rows.lastIndex, onEvent)
                    }
                    Pager(state, onEvent)
                }
            }
        }
    }
}

@Composable
private fun Toolbar(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RecceSegmented(
            options = RecceFilter.entries.map { SegmentOption(it.name, it.label, state.counts.of(it)) },
            activeId = state.filter.name,
            onSelect = { id -> onEvent(RecceEvent.Filter(RecceFilter.valueOf(id))) },
        )
        Spacer(Modifier.weight(1f))
        ZillitSearchField(
            value = state.query,
            onValueChange = { onEvent(RecceEvent.Search(it)) },
            placeholder = "Search location or rendezvous…",
            modifier = Modifier.width(SEARCH_WIDTH),
        )
        ZillitSelect(
            value = state.unitFilter?.let { id -> state.unitsInList.firstOrNull { it.id == id } },
            options = listOf<ProductionUnit?>(null) + state.unitsInList,
            onSelect = { onEvent(RecceEvent.FilterUnit(it?.id)) },
            label = { it?.name?.localised() ?: "All units" },
            modifier = Modifier.width(UNIT_WIDTH),
        )
    }
}

@Composable
private fun TableHeader() {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surfaceSunken)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Recce", Modifier.weight(TITLE_WEIGHT))
        HeaderCell("Unit", Modifier.weight(1f))
        HeaderCell("Date", Modifier.weight(1f))
        HeaderCell("Rendezvous", Modifier.weight(1f))
        HeaderCell("Stops", Modifier.width(STOPS_WIDTH))
        HeaderCell("Personnel", Modifier.weight(1f))
        Spacer(Modifier.width(ACTIONS_WIDTH))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    ZillitText(
        text = text,
        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        color = ZillitTheme.colors.textSecondary,
        modifier = modifier,
        maxLines = 1,
    )
}

@Composable
private fun EmptyRows(text: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZillitIcon(icon = RecceIcons.MapPin, tint = ZillitTheme.colors.textMuted, size = 28.dp)
            MutedText(text)
        }
    }
}

@Composable
private fun RecceRow(state: RecceUiState, recce: Recce, last: Boolean, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background by animateColorAsState(
        if (hovered) colors.surfaceSunken else colors.surface,
        label = "rowBackground",
    )
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(background)
                .hoverable(interaction)
                .clickable { onEvent(RecceEvent.Open(recce.id)) }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TitleCell(recce, Modifier.weight(TITLE_WEIGHT))
            Box(Modifier.weight(1f)) {
                val unit = state.unitName(recce.unit).localised()
                if (unit.isBlank()) Dash() else UnitTag(unit)
            }
            TwoLine(
                top = RecceClock.shortDate(recce.dateMs),
                bottom = RecceClock.weekday(recce.dateMs),
                modifier = Modifier.weight(1f),
            )
            TwoLine(top = RecceClock.hm(recce.rdv.timeMs), bottom = recce.station, modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.width(STOPS_WIDTH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ZillitIcon(icon = RecceIcons.MapPin, tint = colors.textMuted, size = 15.dp)
                ZillitText(
                    text = recce.locationCount.toString(),
                    style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = colors.textPrimary,
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val crew = recce.realPersonnel
                AvatarStack(crew)
                ZillitText(
                    text = crew.size.toString(),
                    style = ZillitTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                )
            }
            Row(
                modifier = Modifier.width(ACTIONS_WIDTH),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                ZillitIconButton(
                    icon = ZillitIcons.Trash,
                    contentDescription = "Delete recce",
                    onClick = { onEvent(RecceEvent.Delete(recce.id)) },
                    tint = if (hovered) colors.danger else colors.textMuted,
                )
                ZillitIcon(icon = ZillitIcons.ChevronRight, tint = colors.textMuted, size = 16.dp)
            }
        }
        if (!last) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    }
}

/** The brand-tinted pin tile, the title, and a Draft chip when unpublished. */
@Composable
private fun TitleCell(recce: Recce, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = RecceIcons.MapPin, tint = RecceColors.Brand, size = 18.dp)
        }
        ZillitText(
            text = recce.title.ifBlank { "Untitled recce" },
            style = ZillitTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (!recce.isPublished) RecceTag(text = "Draft", kind = TagKind.Pending, dot = true)
    }
}

@Composable
private fun TwoLine(top: String, bottom: String, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Column(modifier) {
        if (top.isBlank()) {
            Dash()
        } else {
            ZillitText(text = top, style = ZillitTheme.typography.bodyLarge, color = colors.textSecondary, maxLines = 1)
        }
        if (bottom.isNotBlank()) {
            ZillitText(text = bottom, style = ZillitTheme.typography.bodySmall, color = colors.textMuted, maxLines = 1)
        }
    }
}

@Composable
private fun Dash() {
    ZillitText(text = "—", style = ZillitTheme.typography.bodyLarge, color = ZillitTheme.colors.textMuted)
}

/**
 * The pager — "1–50 of 120", the page numbers, and the size changer. Not
 * hidden on a single page: the size changer is the only way back to a
 * smaller limit, and hiding it at "100 per page, 60 records" would strand
 * the reader there.
 */
@Composable
private fun Pager(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MutedText(state.rangeLabel)
        Spacer(Modifier.weight(1f))
        ZillitIconButton(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = "Previous page",
            onClick = { onEvent(RecceEvent.GoToPage(state.page - 1)) },
            enabled = state.page > 1,
        )
        pageNumbers(state.page, state.pageCount).forEach { number ->
            if (number == null) {
                ZillitText(text = "…", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            } else {
                PageNumber(number, active = number == state.page) { onEvent(RecceEvent.GoToPage(number)) }
            }
        }
        ZillitIconButton(
            icon = ZillitIcons.ChevronRight,
            contentDescription = "Next page",
            onClick = { onEvent(RecceEvent.GoToPage(state.page + 1)) },
            enabled = state.page < state.pageCount,
        )
        Spacer(Modifier.width(8.dp))
        ZillitSelect(
            value = state.pageSize,
            options = RecceQuery.PAGE_SIZES,
            onSelect = { onEvent(RecceEvent.PageSize(it)) },
            label = { "$it / page" },
            modifier = Modifier.width(PAGE_SIZE_WIDTH),
        )
    }
}

@Composable
private fun PageNumber(number: Int, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val border by animateColorAsState(
        when {
            active -> RecceColors.Brand
            hovered -> RecceColors.Brand
            else -> colors.border
        },
        label = "pageBorder",
    )
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surface)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .hoverable(interaction)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = number.toString(),
            style = ZillitTheme.typography.bodyMedium.copy(
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            ),
            color = if (active || hovered) RecceColors.Brand else colors.textSecondary,
        )
    }
}

/** antd's page window: every page up to seven, otherwise the ends and the current page's neighbours. */
internal fun pageNumbers(current: Int, count: Int): List<Int?> {
    if (count <= PAGE_WINDOW) return (1..count).toList()
    val around = (current - 1..current + 1).filter { it in 2 until count }
    val pages = (listOf(1) + around + listOf(count)).distinct()
    return buildList {
        pages.forEachIndexed { index, page ->
            if (index > 0 && page - pages[index - 1] > 1) add(null)
            add(page)
        }
    }
}

private const val PAGE_WINDOW = 7
private const val TITLE_WEIGHT = 2.4f
private val SEARCH_WIDTH = 280.dp
private val UNIT_WIDTH = 170.dp
private val STOPS_WIDTH = 72.dp
private val ACTIONS_WIDTH = 64.dp
private val PAGE_SIZE_WIDTH = 120.dp

/** The shared error strip under the header, dismissable — the web's `message.error`. */
@Composable
internal fun ErrorNotice(state: RecceUiState, onEvent: (RecceEvent) -> Unit) {
    state.error?.let { message ->
        ZillitNotice(
            text = message,
            tone = StatusTone.Rejected,
            action = {
                ZillitButton(
                    text = "Dismiss",
                    onClick = { onEvent(RecceEvent.DismissError) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            },
        )
        Spacer(Modifier.height(12.dp))
    }
}
