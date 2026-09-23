package com.zillit.desktop.feature.boxschedule.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryView
import com.zillit.desktop.feature.boxschedule.ui.pages.AudiencePickerDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.BulkDeleteDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.CalendarInfoDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.CalendarReminderPrompt
import com.zillit.desktop.feature.boxschedule.ui.pages.CommandPaletteDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.ConflictDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.DayDrawerSheet
import com.zillit.desktop.feature.boxschedule.ui.pages.DeleteAllOnDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.DeleteBlockDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.DeleteDayDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.DiaryCalendarView
import com.zillit.desktop.feature.boxschedule.ui.pages.DiaryHeader
import com.zillit.desktop.feature.boxschedule.ui.pages.DiaryListView
import com.zillit.desktop.feature.boxschedule.ui.pages.EntryDetailsSheet
import com.zillit.desktop.feature.boxschedule.ui.pages.EntryFormSheet
import com.zillit.desktop.feature.boxschedule.ui.pages.EntryScopeDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.FilterDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.GuestsDialogView
import com.zillit.desktop.feature.boxschedule.ui.pages.HistoryDetailDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.HistorySheet
import com.zillit.desktop.feature.boxschedule.ui.pages.PdfOptionsDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.PresetsDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.PrintSelectedDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.QuickActionDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.ScheduleFormSheet
import com.zillit.desktop.feature.boxschedule.ui.pages.ScheduleScopeDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.SelectionBar
import com.zillit.desktop.feature.boxschedule.ui.pages.ShareDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.TypesManagerDialog
import com.zillit.desktop.feature.boxschedule.ui.pages.ViewBar

/**
 * The production diary page — the web's `BoxSchedulePage`: the toolbar and
 * masthead, the view bar or select mode's bar, the Calendar or List view,
 * and whatever is open over them.
 *
 * Single-key shortcuts — N, E, T, ← and → — work while nothing is open over
 * the page and the search box is not being typed in; Cmd/Ctrl+K opens the
 * command palette from anywhere.
 */
@Composable
fun BoxScheduleScreen(
    state: BoxScheduleUiState,
    onEvent: (BoxScheduleEvent) -> Unit,
    mayCall: Boolean = false,
) {
    val focus = remember { FocusRequester() }
    var searching by remember { mutableStateOf(false) }
    val nothingOpen = state.overlays == DiaryOverlays()
    LaunchedEffect(nothingOpen) { if (nothingOpen) runCatching { focus.requestFocus() } }

    Box(
        Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .onPreviewKeyEvent { key -> paletteShortcut(key, onEvent) }
            .onKeyEvent { key -> nothingOpen && !searching && pageShortcut(key, state, onEvent) }
            .focusRequester(focus)
            .focusable(),
    ) {
        Column(Modifier.fillMaxSize()) {
            DiaryHeader(state, onEvent)
            if (state.page.selecting) SelectionBar(
                state,
                onEvent,
            ) else ViewBar(state, onEvent, onSearchFocus = { searching = it })
            state.error?.let { message ->
                ZillitNotice(
                    text = message,
                    tone = StatusTone.Rejected,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    action = {
                        ZillitButton(
                            str(S.sync_action_dismiss),
                            onClick = { onEvent(PageEvent.DismissError) },
                            variant = ButtonVariant.Tertiary,
                            size = ButtonSize.Small,
                        )
                    },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                PageBody(state, onEvent)
                if (state.loading && state.loadedOnce) {
                    RefreshingPill(Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
                }
            }
        }
        DiaryOverlayHost(state, onEvent, mayCall)
    }
}

@Composable
private fun PageBody(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit) {
    when {
        state.viewer.isBlocked -> ZillitEmptyState(
            title = str(S.dd_publish_no_access_badge),
            message = str(S.desktop_bs_no_access_message),
            icon = ZillitIcons.Lock,
            modifier = Modifier.fillMaxSize(),
        )
        state.loading && !state.loadedOnce && state.blocks.isEmpty() ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
        state.page.view == DiaryView.Calendar -> DiaryCalendarView(state, onEvent)
        else -> DiaryListView(state, onEvent)
    }
}

/** "Refreshing…" — a reload over data already on screen, which stays put. */
@Composable
private fun RefreshingPill(modifier: Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier
            .shadow(4.dp, RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(colors.surface)
            .border(1.dp, colors.border, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ZillitSpinner(size = 12.dp)
        ZillitText(
            str(S.bs_refreshing),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = colors.textMuted,
        )
    }
}

/** Everything open over the page, drawn in the order it stacks. */
@Suppress("CyclomaticComplexMethod") // One line per surface the page can open.
@Composable
private fun DiaryOverlayHost(state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit, mayCall: Boolean) {
    val o = state.overlays
    o.day?.let { DayDrawerSheet(state, it, onEvent, mayCall) }
    o.viewing?.let { key -> state.entry(key)?.let { EntryDetailsSheet(state, it, onEvent, mayCall) } }
    o.quickAction?.let { QuickActionDialog(state, it, onEvent) }
    o.scheduleForm?.let { ScheduleFormSheet(state, it, onEvent) }
    o.entryForm?.let { form ->
        EntryFormSheet(state, form, onEvent)
        form.audiencePicker?.let { AudiencePickerDialog(state, it, onEvent) }
        form.guestsDialog?.let { GuestsDialogView(it, onEvent) }
        if (form.askCalendar) CalendarReminderPrompt(onEvent)
    }
    o.conflict?.let { ConflictDialog(state, it, onEvent) }
    o.scheduleScope?.let { prompt ->
        state.block(prompt.blockId)?.let { ScheduleScopeDialog(state, prompt, it, onEvent) }
    }
    o.deleteDay?.let { DeleteDayDialog(it, onEvent) }
    o.deleteBlock?.let { id -> state.block(id)?.let { DeleteBlockDialog(state, it, onEvent) } }
    o.deleteAllOn?.let { DeleteAllOnDialog(state, it, onEvent) }
    if (o.bulkDelete) BulkDeleteDialog(state.page.selected.size, onEvent)
    o.updateScope?.let { prompt ->
        state.entry(prompt.listKey)?.let { EntryScopeDialog(state, prompt, it, delete = false, onEvent = onEvent) }
    }
    o.deleteEntry?.let { prompt ->
        state.entry(prompt.listKey)?.let { EntryScopeDialog(state, prompt, it, delete = true, onEvent = onEvent) }
    }
    o.calendarInfo?.let { CalendarInfoDialog(it, onEvent) }
    o.types?.let { TypesManagerDialog(state, it, onEvent) }
    o.history?.let { panel ->
        HistorySheet(state, panel, onEvent)
        panel.detailId?.let { id ->
            panel.entries.firstOrNull { it.id == id }?.let { HistoryDetailDialog(state, panel, it, onEvent) }
        }
    }
    o.presets?.let { PresetsDialog(state, it, onEvent) }
    o.filters?.let { FilterDialog(state, it, onEvent) }
    o.pdf?.let { PdfOptionsDialog(it, onEvent) }
    o.printSelected?.let { PrintSelectedDialog(it, state.page.selected.size, onEvent) }
    o.share?.let { ShareDialog(it, onEvent) }
    o.palette?.let { CommandPaletteDialog(state, it, onEvent) }
}

/** Cmd/Ctrl+K toggles the palette, wherever focus is. */
private fun paletteShortcut(key: KeyEvent, onEvent: (BoxScheduleEvent) -> Unit): Boolean {
    val command = key.isMetaPressed || key.isCtrlPressed
    if (key.type != KeyEventType.KeyDown || key.key != Key.K || !command) return false
    onEvent(PageEvent.TogglePalette)
    return true
}

/** N new schedule, E new event, T today, ← and → — the writes only for someone who may post. */
private fun pageShortcut(key: KeyEvent, state: BoxScheduleUiState, onEvent: (BoxScheduleEvent) -> Unit): Boolean {
    val modified = key.isMetaPressed || key.isCtrlPressed || key.isAltPressed
    if (key.type != KeyEventType.KeyDown || modified) return false
    val event = when (key.key) {
        Key.N -> ScheduleEvent.NewSchedule().takeIf { state.mayEdit }
        Key.E -> EntryEvent.NewEntry(DiaryKind.Event).takeIf { state.mayEdit }
        Key.T -> PageEvent.Today
        Key.DirectionLeft -> PageEvent.Step(forward = false)
        Key.DirectionRight -> PageEvent.Step(forward = true)
        else -> null
    } ?: return false
    onEvent(event)
    return true
}
