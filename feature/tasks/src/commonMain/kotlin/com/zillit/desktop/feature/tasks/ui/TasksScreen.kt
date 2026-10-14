@file:Suppress("MaxLineLength") // The shell lays out the sidebar, the view and the panel.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import kotlin.time.Clock
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * The Tasks tool, laid out as the web's: the three views as tabs across the
 * top with Create Task at the right end, the open view under them, and the
 * task panel sliding in over it.
 *
 * Rights gate everything. Until the tool's row has answered nothing is shown or
 * requested; a production without the tool, or a viewer without view rights,
 * gets a plain explanation instead of a request the service would answer with
 * a 403.
 */
@Composable
fun TasksScreen(
    state: TasksUiState,
    onEvent: (TasksEvent) -> Unit,
    mentionable: (Task) -> List<TaskPerson>,
    modifier: Modifier = Modifier,
    /**
     * The host's guarded external-URL launcher, behind a link in a description
     * or a comment. Null leaves links as plain words rather than dead ones.
     */
    onOpenUrl: ((String) -> Unit)? = null,
) {
    TasksThemeProvider(dark = null) {
        val k = TasksTheme.c
        val today by rememberToday()
        val counts = state.counts

        Box(modifier.fillMaxSize().background(k.bg)) {
            Column(Modifier.fillMaxSize()) {
                // The bar stays in place while a view scrolls.
                if (state.viewer.canCall) TasksTabBar(state, counts, onEvent)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !state.viewer.resolved -> GatePage { GateLoading() }
                        !state.viewer.enabled -> GatePage { Gate(str(S.desktop_tasks_gate_disabled_title), str(S.desktop_tasks_gate_disabled_hint), null, onEvent) }
                        !state.viewer.canView -> GatePage { Gate(str(S.desktop_tasks_gate_noview_title), str(S.desktop_tasks_gate_noview_hint), str(S.desktop_request_access), onEvent) }
                        else -> when (state.view) {
                            TasksView.Board -> BoardView(state, today, onEvent)
                            TasksView.Mine -> MineView(state, today, onEvent)
                            TasksView.Self -> SelfView(state, today, onEvent)
                        }
                    }
                }
            }
            // The chooser covers the tool's area, as the panel does, and never
            // opens over the panel's unsaved edits (the view model refuses it).
            if (state.viewer.canCall) CreateChooser(state, onEvent)
            if (state.viewer.canCall) TaskPanelOverlay(state, today, onEvent, mentionable, onOpenUrl = onOpenUrl)
            // A refusal from the service, or a rule the client checked first ("2 subtasks are still open");
            // otherwise what the server announced for a write it saved.
            val problem = state.error ?: state.notice
            ZillitToast(
                message = problem ?: state.success,
                onDismiss = { onEvent(TasksEvent.DismissMessage) },
                tone = if (problem != null) ZillitToastTone.Danger else ZillitToastTone.Success,
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
    }
}

/**
 * Today, for the due dates: read again every minute, so a window left open
 * past midnight moves its Overdue / Today groups, tones and labels on, as the
 * web reads the clock on every render.
 */
@Composable
private fun rememberToday() = produceState(Clock.System.todayIn(TimeZone.currentSystemDefault())) {
    while (true) {
        delay(TODAY_TICK_MS)
        value = Clock.System.todayIn(TimeZone.currentSystemDefault())
    }
}

private const val TODAY_TICK_MS = 60_000L

/** A gate in place of a view: the tool's name as the heading, the state under it (the web's `.zt-top` + `StatePanel`). */
@Composable
private fun GatePage(content: @Composable () -> Unit) {
    val k = TasksTheme.c
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().background(k.surface)) {
            TText(str(S.desktop_tasks_tool_label), 24, androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp), maxLines = 1)
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        }
        content()
    }
}

/** "Loading tasks…" while the rights list has not answered: nothing may call the service before it does. */
@Composable
private fun GateLoading() {
    Row(Modifier.padding(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ZillitSpinner(size = 16.dp)
        TText(str(S.desktop_tasks_loading), 14, color = TasksTheme.c.muted)
    }
}

@Composable
private fun Gate(title: String, hint: String, action: String?, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.padding(28.dp).width(560.dp).clip(shape).background(k.surface).border(BorderStroke(1.dp, k.line), shape).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TText(title, 16, FontWeightSemi)
        TText(hint, 14, color = k.muted)
        if (action != null) TBtn(action, { onEvent(TasksEvent.AskForRights) }, kind = BtnKind.Ghost)
    }
}

private val FontWeightSemi = androidx.compose.ui.text.font.FontWeight.SemiBold
