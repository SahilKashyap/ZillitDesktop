@file:Suppress("MaxLineLength") // The bar and the chooser are laid out in one pass, to the web's TabBar / CreateChooser.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.TaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskStatus

/**
 * The bar across the top of the tool and the popup that asks what kind of task
 * a new one is — the web's `components/TabBar.jsx` and `CreateChooser.jsx`.
 *
 * The three views are tabs here, not a sidebar: the screens reproduce the
 * standalone Zillit Tasks app, whose tab bar sits across the top with Create
 * Task at its right end. The bar stays in place while a view scrolls, and each
 * view then starts with its tab's one-line description ([ViewLine]).
 */

/** One tab: its name, the open count on the last two, and an underline on the one on screen. */
@Composable
internal fun TasksTabBar(state: TasksUiState, counts: TaskCounts, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    BoxWithConstraints(Modifier.fillMaxWidth().background(k.surface)) {
        // "Narrow" follows the tool's own width, not the window's: a narrow
        // tool window on a wide screen gets the same layout.
        val narrow = maxWidth < NARROW_BAR
        Column {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 6.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Tab(str(S.desktop_tasks_nav_board), null, state.view == TasksView.Board) { onEvent(TasksEvent.ViewChanged(TasksView.Board)) }
                Tab(str(S.desktop_tasks_nav_mine), counts.mine.takeIf { state.loaded }, state.view == TasksView.Mine) { onEvent(TasksEvent.ViewChanged(TasksView.Mine)) }
                Tab(str(S.desktop_tasks_nav_self), counts.self.takeIf { state.loaded }, state.view == TasksView.Self) { onEvent(TasksEvent.ViewChanged(TasksView.Self)) }
                Box(Modifier.weight(1f))
                Box(Modifier.padding(bottom = 6.dp)) {
                    if (state.canPost) {
                        CreateButton(narrow) { onEvent(TasksEvent.OpenChooser()) }
                    } else {
                        TBtn(str(S.desktop_request_access), { onEvent(TasksEvent.AskForRights) }, kind = BtnKind.Ghost, small = true)
                    }
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(k.line))
        }
    }
}

@Composable
private fun Tab(label: String, count: Int?, on: Boolean, onClick: () -> Unit) {
    val k = TasksTheme.c
    val source = remember { MutableInteractionSource() }
    Column(
        // IntrinsicSize.Max, or the underline's fillMaxWidth below takes the
        // whole bar's width and this one tab pushes the others off it.
        Modifier.width(IntrinsicSize.Max).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            TText(label, 14, if (on) FontWeight.SemiBold else FontWeight.Normal, if (on) k.fg else k.muted, maxLines = 1)
            if (count != null) {
                TText(
                    count.toString(), 11, FontWeight.SemiBold, if (on) k.accentFg else k.chipFg,
                    Modifier.clip(RoundedCornerShape(999.dp)).background(if (on) k.accent else k.chip).padding(horizontal = 6.dp, vertical = 1.dp),
                    maxLines = 1,
                )
            }
        }
        // The tab on screen is underlined, as the standalone app draws it.
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (on) k.accent else Color.Transparent))
    }
}

/** "Create Task" — only its + on a narrow tool, where the words would crowd the tabs. */
@Composable
private fun CreateButton(narrow: Boolean, onClick: () -> Unit) {
    if (narrow) {
        val k = TasksTheme.c
        Box(
            Modifier.width(32.dp).height(32.dp).clip(CircleShape).background(k.accent).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { ZillitIcon(ZillitIcons.Add, tint = k.accentFg, size = 16.dp) }
        return
    }
    TBtn(str(S.desktop_tasks_create_task_button), onClick, icon = ZillitIcons.Add, small = true)
}

/**
 * The line under the bar: the open view's one-line description. On the web its
 * right end carries "Back to Tools" and a theme switch; here the window shell
 * owns the first and the tool follows the app's light/dark, so neither is drawn.
 */
@Composable
internal fun ViewLine(text: String, modifier: Modifier = Modifier) {
    TText(text, 13, color = TasksTheme.c.muted, modifier = modifier)
}

/**
 * "Create Task" asks first what kind of task this is, then opens the new-task
 * form of that kind:
 *
 *   Self task           a self task (private), with the cursor in the name
 *   Assign to someone   a project task (public), with the crew picker open
 *
 * [TaskChooser.status] is the Board column whose + opened it, so the new task
 * starts in that column. Escape, the ✕ and a press outside close it and create
 * nothing. It covers the tool's own area, never the window's title bar.
 */
@Composable
internal fun CreateChooser(state: TasksUiState, onEvent: (TasksEvent) -> Unit) {
    val chooser = state.chooser ?: return
    val k = TasksTheme.c
    val close = { onEvent(TasksEvent.CloseChooser) }
    val pick = { self: Boolean ->
        val seed = TaskDraft(isSelf = self, status = chooser.status ?: TaskStatus.Todo)
        onEvent(TasksEvent.NewTask(seed, pickAssignee = !self))
    }
    val shape = RoundedCornerShape(14.dp)
    // The first choice takes the focus, so Escape and the arrow keys reach the box.
    val focus = remember { FocusRequester() }
    var at by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(k.scrim), contentAlignment = Alignment.Center) {
        // A press on the backdrop closes; one inside the box must not.
        Box(
            Modifier.fillMaxSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = close),
        )
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth(0.9f).clip(shape).background(k.surface)
                .border(BorderStroke(1.dp, k.line), shape).padding(18.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .onPreviewKeyEvent { event ->
                    chooserKey(event, onClose = close, onMove = { step -> at = (at + step + CHOICES) % CHOICES }, onPick = { pick(at == 0) })
                }
                .focusRequester(focus).focusable(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    TText(str(S.desktop_tasks_create_task_button), 16, FontWeight.SemiBold)
                    TText(str(S.desktop_tasks_chooser_question), 13, color = k.muted)
                }
                TIconBtn(ZillitIcons.Close, str(S.close), close)
            }
            Choice(
                icon = ZillitIcons.Lock,
                title = str(S.desktop_tasks_self_task),
                hint = str(S.desktop_tasks_chooser_self_hint),
                on = at == 0,
            ) { pick(true) }
            Choice(
                icon = ZillitIcons.UserPlus,
                title = str(S.desktop_tasks_chooser_assign),
                on = at == 1,
                // "…or make it private." only where the service keeps visibility:
                // the form that opens has the Public | Private switch only then.
                hint = str(if (state.features.visibility) S.desktop_tasks_chooser_assign_hint_visibility else S.desktop_tasks_chooser_assign_hint),
            ) { pick(false) }
        }
    }
}

/**
 * The chooser's keys: Escape closes and creates nothing, the arrow keys move
 * between the two choices either way, and Enter or Space picks the one that is on.
 */
private fun chooserKey(event: KeyEvent, onClose: () -> Unit, onMove: (Int) -> Unit, onPick: () -> Unit): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val step = when (event.key) {
        Key.DirectionDown, Key.DirectionRight -> 1
        Key.DirectionUp, Key.DirectionLeft -> -1
        else -> 0
    }
    if (step != 0) {
        onMove(step)
        return true
    }
    return when (event.key) {
        Key.Escape -> {
            onClose()
            true
        }
        Key.Enter, Key.NumPadEnter, Key.Spacebar -> {
            onPick()
            true
        }
        else -> false
    }
}

@Composable
private fun Choice(icon: ImageVector, title: String, hint: String, on: Boolean, onClick: () -> Unit) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(if (on) k.accentSoft else k.surface2)
            .border(BorderStroke(1.dp, if (on) k.accent else k.line), shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.width(32.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(k.accentSoft),
            contentAlignment = Alignment.Center,
        ) { ZillitIcon(icon, tint = k.accent, size = 16.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            TText(title, 14, FontWeight.SemiBold)
            TText(hint, 13, color = k.muted)
        }
    }
}

private val NARROW_BAR = 760.dp
private const val CHOICES = 2
