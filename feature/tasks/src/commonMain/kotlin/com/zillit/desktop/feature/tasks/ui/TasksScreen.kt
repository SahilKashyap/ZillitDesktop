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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.Task
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * The Tasks tool, laid out as the web's: a dark sidebar with the three views,
 * the open view in a grey work area, and the task panel sliding over it.
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
) {
    TasksThemeProvider(dark = null) {
        val k = TasksTheme.c
        val today = remember { Clock.System.todayIn(TimeZone.currentSystemDefault()) }
        val counts = state.counts

        Box(modifier.fillMaxSize().background(k.bg)) {
            Row(Modifier.fillMaxSize()) {
                Sidebar(state, counts.mine, counts.self, onEvent)
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        !state.viewer.resolved -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ZillitSpinner() }
                        !state.viewer.enabled -> Gate(str(S.desktop_tasks_gate_disabled_title), str(S.desktop_tasks_gate_disabled_hint), null, onEvent)
                        !state.viewer.canView -> Gate(str(S.desktop_tasks_gate_noview_title), str(S.desktop_tasks_gate_noview_hint), str(S.desktop_request_access), onEvent)
                        else -> when (state.view) {
                            TasksView.Board -> BoardView(state, today, onEvent)
                            TasksView.Mine -> MineView(state, today, onEvent)
                            TasksView.Self -> SelfView(state, today, onEvent)
                        }
                    }
                }
            }
            if (state.viewer.canCall) TaskPanelOverlay(state, today, onEvent, mentionable)
            // A refusal from the service, or a rule the client checked first ("2 subtasks are still open").
            ZillitErrorToast(
                message = state.error ?: state.notice,
                onDismiss = { onEvent(TasksEvent.DismissMessage) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            )
        }
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

/** The dark sidebar (`.zt-side`): the three views. The tool follows the app's light/dark, so it has no switch of its own. */
@Composable
private fun Sidebar(state: TasksUiState, mine: Int, self: Int, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    Column(Modifier.width(240.dp).fillMaxHeight().background(k.side).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                Triple(TasksView.Board, str(S.desktop_tasks_nav_board) to str(S.desktop_tasks_sub_board), null),
                Triple(TasksView.Mine, str(S.desktop_tasks_nav_mine) to str(S.desktop_tasks_nav_mine_sub), mine),
                Triple(TasksView.Self, str(S.desktop_tasks_nav_self) to str(S.desktop_tasks_nav_self_sub), self),
            ).forEach { (view, texts, n) ->
                val on = state.view == view
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (on) Color(0xFF2D5BE3) else Color.Transparent)
                        .clickable { onEvent(TasksEvent.ViewChanged(view)) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        TText(texts.first, 14, FontWeightSemi, if (on) Color.White else k.sideFg, maxLines = 1)
                        TText(texts.second, 12, color = if (on) Color(0xFFDBE4FB) else k.sideMuted, maxLines = 1)
                    }
                    if (n != null && state.loaded) {
                        TText(n.toString(), 12, FontWeightSemi, Color.White, Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) Color.White.copy(alpha = 0.2f) else k.side2).padding(horizontal = 7.dp, vertical = 1.dp))
                    }
                }
            }
        }
    }
}
