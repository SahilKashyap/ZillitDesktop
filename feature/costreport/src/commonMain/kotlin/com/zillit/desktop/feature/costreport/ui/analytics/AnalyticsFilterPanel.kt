package com.zillit.desktop.feature.costreport.ui.analytics

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitDateField
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitOptionPopup
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.popupWidth
import com.zillit.desktop.core.designsystem.component.rememberZillitSelectAnchor
import com.zillit.desktop.core.designsystem.component.zillitSelectAnchor
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.costreport.domain.analytics.AnalyticsOption

/**
 * The Filters panel: a card dropped under the header over a dimmed page.
 * Picks change only the draft; Done applies them, and a click on the
 * backdrop closes the panel keeping the picks unapplied — as the web's does.
 */
@Composable
internal fun BoxScope.FilterOverlay(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    AnimatedVisibility(
        visible = state.filtersOpen,
        enter = fadeIn(tween(FADE_MS)),
        exit = fadeOut(tween(FADE_MS)),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(BACKDROP)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    onEvent(AnalyticsEvent.CloseFilters)
                },
        )
    }
    AnimatedVisibility(
        visible = state.filtersOpen,
        enter = fadeIn(tween(FADE_MS)) + slideInVertically(tween(FADE_MS)) { -it / SLIDE_FRACTION },
        exit = fadeOut(tween(FADE_MS)) + slideOutVertically(tween(FADE_MS)) { -it / SLIDE_FRACTION },
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        Box(
            Modifier.widthIn(max = 1640.dp)
                .fillMaxWidth()
                .padding(start = 32.dp, end = 32.dp, top = 10.dp, bottom = 24.dp),
        ) {
            FilterPanel(state, onEvent)
        }
    }
}

@Composable
private fun FilterPanel(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    val colors = analyticsColors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(24.dp, shape, ambientColor = PANEL_SHADOW, spotColor = PANEL_SHADOW)
            .background(colors.surface, shape)
            .border(1.dp, colors.line, shape)
            .clip(shape)
            // Swallow clicks so one inside the card never reaches the backdrop.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        PanelHeader(state, onEvent)
        Column(Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
            FilterSection(str(S.desktop_cr_period_date_range)) { PeriodChoices(state, onEvent) }
            FilterSection(str(S.asset_currency)) {
                if (state.options.currencies.isEmpty()) {
                    ZillitText(str(S.desktop_cr_no_currencies), style = AnalyticsType.text(12f), color = colors.ink3)
                }
                val effective = state.draft.effectiveCurrency(state.options)
                state.options.currencies.forEach { currency ->
                    FilterChip(currency.chipLabel, on = effective == currency.code) {
                        onEvent(AnalyticsEvent.SetCurrency(currency.code))
                    }
                }
            }
            FilterSection(str(S.desktop_cr_entity_company)) {
                FilterChip(str(S.cr_all_companies), on = state.draft.entity.isBlank()) {
                    onEvent(AnalyticsEvent.SetEntity(""))
                }
                state.options.entities.forEach { entity ->
                    FilterChip(
                        entity.label,
                        on = state.draft.entity == entity.value,
                    ) { onEvent(AnalyticsEvent.SetEntity(entity.value)) }
                }
            }
            DepartmentSection(state, onEvent)
            FilterSection(str(S.dm_step2_unit), last = true) {
                FilterChip(str(S.recce_unit_all), on = state.draft.unitIds.isEmpty()) {
                    onEvent(AnalyticsEvent.AllUnits)
                }
                state.options.units.forEach { unit ->
                    FilterChip(
                        unit.label,
                        on = unit.value in state.draft.unitIds,
                    ) { onEvent(AnalyticsEvent.ToggleUnit(unit.value)) }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    val colors = analyticsColors
    val count = state.draft.activeCount(state.options)
    Row(
        modifier = Modifier.fillMaxWidth().bottomRule(true, colors.line).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ZillitText(
            str(S.desktop_cr_filter_analytics),
            style = AnalyticsType.text(14f, FontWeight.Bold),
            color = colors.ink,
            modifier = Modifier.weight(1f),
        )
        ZillitText(
            str(if (count == 1) S.desktop_cr_filter_one_active else S.desktop_cr_filter_many_active, count),
            style = AnalyticsType.mono(11f),
            color = colors.ink3,
        )
        ZillitText(
            str(S.desktop_reset_all),
            style = AnalyticsType.text(12f, FontWeight.Bold),
            color = if (count > 0) colors.amber else colors.ink4,
            modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onEvent(AnalyticsEvent.ResetFilters) },
        )
        ZillitText(
            str(S.done_text),
            style = AnalyticsType.text(12.5f, FontWeight.Bold),
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.amber)
                .clickable { onEvent(AnalyticsEvent.ApplyFilters) }
                .padding(horizontal = 18.dp, vertical = 7.dp),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterSection(title: String, last: Boolean = false, content: @Composable () -> Unit) {
    val colors = analyticsColors
    Column(Modifier.fillMaxWidth().bottomRule(!last, colors.line).padding(horizontal = 18.dp, vertical = 14.dp)) {
        ZillitText(
            title.uppercase(),
            style = AnalyticsType.mono(10f, FontWeight.Bold, 0.09f),
            color = colors.ink3,
            modifier = Modifier.padding(bottom = 9.dp),
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            content()
        }
    }
}

@Composable
private fun PeriodChoices(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    val colors = analyticsColors
    AnalyticsPeriod.entries.forEach { period ->
        FilterChip(period.label, on = state.draft.period == period) { onEvent(AnalyticsEvent.SetPeriod(period)) }
    }
    if (state.draft.period == AnalyticsPeriod.Range) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            ZillitDateField(
                value = state.draft.dateFrom,
                onValueChange = { onEvent(AnalyticsEvent.SetDateFrom(it)) },
                modifier = Modifier.width(DATE_WIDTH),
            )
            ZillitText("→", style = AnalyticsType.text(12f), color = colors.ink3)
            ZillitDateField(
                value = state.draft.dateTo,
                onValueChange = { onEvent(AnalyticsEvent.SetDateTo(it)) },
                modifier = Modifier.width(DATE_WIDTH),
            )
        }
    }
}

/** The department picker — "N selected" in the field, the picks as removable chips beside it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DepartmentSection(state: AnalyticsUiState, onEvent: (AnalyticsEvent) -> Unit) {
    val colors = analyticsColors
    val selected = state.draft.departmentIds
    val byId = state.options.departments.associateBy { it.value }
    Column(Modifier.fillMaxWidth().bottomRule(true, colors.line).padding(horizontal = 18.dp, vertical = 14.dp)) {
        ZillitText(
            str(S.department).uppercase(),
            style = AnalyticsType.mono(10f, FontWeight.Bold, 0.09f),
            color = colors.ink3,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DepartmentSelect(state.options.departments, selected) { onEvent(AnalyticsEvent.SetDepartments(it)) }
            selected.forEach { id ->
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(CHIP_GRADIENT)
                        .clickable { onEvent(AnalyticsEvent.SetDepartments(selected - id)) }
                        .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitText(
                        byId[id]?.label ?: id,
                        style = AnalyticsType.text(11.5f, FontWeight.SemiBold),
                        color = Color.White,
                    )
                    Box(
                        Modifier.size(14.dp).background(Color.White.copy(alpha = 0.28f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZillitIcon(ZillitIcons.Close, tint = Color.White, size = 8.dp)
                    }
                }
            }
        }
    }
}

/**
 * The department field: the panel's own "N selected" summary (the picks are
 * the chips beside it), opening the app's one list ([ZillitOptionPopup]) —
 * a tick per picked row, staying open while rows are toggled. A picked id
 * the options no longer carry stays picked.
 */
@Composable
private fun DepartmentSelect(options: List<AnalyticsOption>, selected: List<String>, onChange: (List<String>) -> Unit) {
    val colors = analyticsColors
    var open by remember { mutableStateOf(false) }
    val anchor = rememberZillitSelectAnchor()
    val shape = RoundedCornerShape(10.dp)
    val text = when (selected.size) {
        0 -> null
        1 -> options.firstOrNull { it.value == selected.first() }?.label ?: str(S.desktop_n_selected, "1")
        else -> str(S.desktop_n_selected, selected.size.toString())
    }
    Box(Modifier.width(340.dp).zillitSelectAnchor(anchor)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surface)
                .border(1.dp, if (open) colors.amber else colors.line2, shape)
                .clickable { open = !open }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text ?: str(S.desktop_select_departments_2),
                style = AnalyticsType.text(13f, if (text == null) FontWeight.Normal else FontWeight.SemiBold),
                color = if (text == null) colors.ink3 else colors.ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            ZillitIcon(ZillitIcons.ChevronDown, tint = colors.ink3, size = 14.dp)
        }
        if (open) {
            ZillitOptionPopup(
                onDismiss = { open = false },
                width = anchor.popupWidth(),
                options = options,
                isSelected = { it.value in selected },
                onPick = { option ->
                    onChange(if (option.value in selected) selected - option.value else selected + option.value)
                },
                label = { it.label },
                searchable = true,
                footer = { shown ->
                    val plural = str(if (shown == 1) S.desktop_dm_one_option_suffix else S.desktop_dm_options_suffix)
                    val picked = if (selected.isEmpty()) "" else str(S.desktop_selected_suffix, selected.size)
                    str(S.desktop_cr_options_count, shown, plural, picked)
                },
                emptyText = str(S.desktop_cr_no_departments),
            )
        }
    }
}

/** A filter choice: the orange gradient when on, a hairline pill when off. */
@Composable
private fun FilterChip(label: String, on: Boolean, onClick: () -> Unit) {
    val colors = analyticsColors
    ZillitText(
        label,
        style = AnalyticsType.text(12f, FontWeight.SemiBold),
        color = if (on) Color.White else colors.ink2,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .then(
                if (on) Modifier.background(
                    CHIP_GRADIENT,
                ) else Modifier.background(colors.surface).border(1.dp, colors.line2, CircleShape),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
    )
}

/** `linear-gradient(135deg, #fdb043 0%, #fc9404 60%, #ec8600 100%)`. */
private val CHIP_GRADIENT = Brush.linearGradient(
    0f to Color(0xFFFDB043),
    0.6f to Color(0xFFFC9404),
    1f to Color(0xFFEC8600),
    start = Offset.Zero,
    end = Offset.Infinite,
)

private val BACKDROP = Color(0x240F1115)
private val PANEL_SHADOW = Color(0x4D0F1115)
private val DATE_WIDTH = 160.dp
private const val FADE_MS = 180
private const val SLIDE_FRACTION = 12
