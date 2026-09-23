package com.zillit.desktop.feature.boxschedule.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitBadge
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleEvent
import com.zillit.desktop.feature.boxschedule.ui.BoxScheduleUiState
import com.zillit.desktop.feature.boxschedule.ui.EntryEvent
import com.zillit.desktop.feature.boxschedule.ui.PageEvent
import com.zillit.desktop.feature.boxschedule.ui.PanelEvent
import com.zillit.desktop.feature.boxschedule.ui.PdfDestination
import com.zillit.desktop.feature.boxschedule.ui.ScheduleEvent

/**
 * The page's head — the web's toolbar row and the centred masthead,
 * "PRODUCTION DIARY/BOX SCHEDULE" over the day it was prepared.
 */
@Composable
internal fun DiaryHeader(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DiaryToolbar(state, onEvent)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ZillitText(
                text = str(S.bs_title),
                style = serif(20.sp, FontWeight.ExtraBold, spacing = 3.sp),
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            ZillitText(
                text = "Prepared: ${DiaryFormat.fullDate(state.today)}",
                style = ZillitTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = colors.textMuted,
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** The toolbar row: Select, History, types, presets and the PDF on the left; the creates on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiaryToolbar(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val mayEdit = state.mayEdit
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val canSelect = state.page.view == DiaryView.List && !state.page.selecting && state.filteredRows.isNotEmpty()
        if (mayEdit && canSelect) {
            ToolbarButton(str(S.select), ZillitIcons.Check) { onEvent(PageEvent.EnterSelect) }
        }
        Box {
            ToolbarButton(str(S.history), ZillitIcons.Clock) { onEvent(PanelEvent.OpenHistory) }
            ZillitBadge(
                count = state.historyBadge,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
            )
        }
        if (mayEdit) ToolbarButton(str(S.bs_edit_types), ZillitIcons.Settings) { onEvent(PanelEvent.OpenTypes) }
        ToolbarButton(str(S.bs_presets), ZillitIcons.StarOutline) { onEvent(PanelEvent.OpenPresets) }
        if (mayEdit) PdfButtons(state, onEvent)
        Spacer(Modifier.weight(1f))
        if (mayEdit) {
            ToolbarButton(str(S.create_event), ZillitIcons.Calendar) { onEvent(EntryEvent.NewEntry(DiaryKind.Event)) }
            ToolbarButton(str(S.bs_create_note), ZillitIcons.Edit) { onEvent(EntryEvent.NewEntry(DiaryKind.Note)) }
            ZillitButton(
                text = str(S.create_schedule),
                onClick = { onEvent(ScheduleEvent.NewSchedule()) },
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

/** Print, and Publish where the viewer may post into Document Distribution; each spins while its PDF is made. */
@Composable
private fun PdfButtons(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val busy = state.overlays.pdf?.takeIf { it.busy }?.destination
    ZillitButton(
        text = if (busy == PdfDestination.Print) str(S.preparing) else str(S.print),
        onClick = { onEvent(PanelEvent.OpenPdf(PdfDestination.Print)) },
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = ZillitIcons.Download,
        loading = busy == PdfDestination.Print,
    )
    if (state.canPublish) {
        ZillitButton(
            text = str(S.dd_publish_to_distribution),
            onClick = { onEvent(PanelEvent.OpenPdf(PdfDestination.Publish)) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Upload,
            loading = busy == PdfDestination.Publish,
        )
    }
}

@Composable
private fun ToolbarButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    ZillitButton(
        text = text,
        onClick = onClick,
        variant = ButtonVariant.Secondary,
        size = ButtonSize.Small,
        leadingIcon = icon,
    )
}

/** Select mode's dark bar: the count, Select All, and what can be done to the selection. */
@Composable
internal fun SelectionBar(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val total = state.filteredRows.size
    val chosen = state.page.selected.size
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.textPrimary)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ZillitText(
            text = "$chosen of $total selected",
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold),
            color = colors.surface,
        )
        val allChosen = total > 0 && chosen == total
        ZillitText(
            text = if (allChosen) str(S.deselect_emails) else str(S.select_all),
            style = ZillitTheme.typography.label,
            color = colors.surface.copy(alpha = 0.7f),
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { onEvent(if (allChosen) PageEvent.DeselectAll else PageEvent.SelectAll) }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        if (state.canPrint) {
            ZillitButton(
                text = if (state.overlays.printSelected?.printing == true) {
                    str(S.preparing)
                } else {
                    str(S.desktop_bs_print_selected)
                },
                onClick = { onEvent(PanelEvent.OpenPrintSelected) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Download,
                enabled = chosen > 0,
            )
        }
        ZillitButton(
            text = str(S.bs_delete_selected),
            onClick = { onEvent(ScheduleEvent.AskBulkDelete) },
            variant = ButtonVariant.Danger,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Trash,
            enabled = chosen > 0,
        )
        ZillitButton(
            text = str(S.cancel),
            onClick = { onEvent(PageEvent.ExitSelect) },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Close,
        )
    }
}

/**
 * The view switch, the default view, search, the Filter button and the
 * type legend. [onSearchFocus] tells the page when typing belongs to the
 * search box rather than to the single-key shortcuts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ViewBar(
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
    onSearchFocus: (Boolean) -> Unit,
) {
    val colors = ZillitTheme.colors
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Segments(
            options = DiaryView.entries,
            selected = state.page.view,
            label = { it.label },
            onSelect = { onEvent(PageEvent.SetView(it)) },
        )
        DefaultViewMenu(
            buttonText = str(S.desktop_bs_set_default_view),
            description = str(S.desktop_bs_default_view_hint),
            options = DiaryView.entries,
            current = state.page.defaultView,
            label = { it.label },
            hint = { it.hint },
            onChoose = { onEvent(PageEvent.SaveDefaultView(it)) },
        )
        Box(Modifier.width(240.dp).onFocusChanged { onSearchFocus(it.hasFocus) }) {
            ZillitSearchField(
                value = state.page.filter.search,
                onValueChange = { onEvent(PageEvent.Search(it)) },
                placeholder = str(S.bs_search_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Box {
            ZillitButton(
                text = str(S.filter),
                onClick = { onEvent(PageEvent.OpenFilters) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Filter,
            )
            ZillitBadge(
                count = state.page.filter.activeCount,
                background = colors.accent,
                modifier = Modifier.align(Alignment.TopEnd).offset(x = 6.dp, y = (-6).dp),
            )
        }
        Spacer(Modifier.weight(1f))
        TypeLegend(state = state, onEvent = onEvent)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** The first five types and their colours; the rest behind "+N more". */
@Composable
private fun TypeLegend(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    if (state.types.isEmpty()) return
    val shown = state.types.take(LEGEND_MAX)
    val hidden = state.types.size - shown.size
    Row(
        modifier = Modifier.height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        shown.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Dot(swatchColor(type.color), size = 10.dp, square = true)
                ZillitText(
                    text = type.title,
                    style = ZillitTheme.typography.label,
                    color = ZillitTheme.colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
        if (hidden > 0) MoreChip("+$hidden more", onClick = { if (state.mayEdit) onEvent(PanelEvent.OpenTypes) })
    }
}

/**
 * "Set Default View" / "Set as Default" — the web's popover of radio cards,
 * the saved one marked Current.
 */
@Composable
internal fun <T> DefaultViewMenu(
    buttonText: String,
    description: String,
    options: List<T>,
    current: T,
    label: (T) -> String,
    hint: (T) -> String,
    onChoose: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val colors = ZillitTheme.colors
    Box {
        ZillitButton(
            text = buttonText,
            onClick = { open = true },
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Grid,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(260.dp).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZillitText(str(S.dv_choose_title), style = ZillitTheme.typography.titleSmall)
                ZillitText(description, style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                options.forEach { option ->
                    DefaultOption(
                        title = label(option),
                        hint = hint(option),
                        selected = option == current,
                        onClick = {
                            open = false
                            onChoose(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DefaultOption(title: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.accentSoft else colors.surface)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) colors.accent else colors.border,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RadioMark(selected)
        Column(Modifier.weight(1f)) {
            ZillitText(title, style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.SemiBold))
            ZillitText(hint, style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        }
        if (selected) ZillitText(str(S.dv_current), style = ZillitTheme.typography.labelSmall, color = colors.success)
    }
}

private const val LEGEND_MAX = 5
