@file:Suppress("MaxLineLength") // Laid out in one pass, to the web's Drafts.jsx / DraftsScreen.jsx.

package com.zillit.desktop.feature.tasks.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.TaskVisibility
import com.zillit.desktop.feature.tasks.domain.TaskPerson
import com.zillit.desktop.feature.tasks.domain.draftIsToMe
import kotlinx.coroutines.delay
import kotlin.time.Clock

/**
 * Drafts — tasks I started and saved to finish later; only I can see them.
 *
 * This file holds the tab, a draft's row (both on the tab and as the dashed
 * DRAFT row under a main task's Shared tasks) and the "Save this task as a
 * draft?" question — the web's `components/Drafts.jsx` and
 * `screens/DraftsScreen.jsx`.
 */

/**
 * The Drafts tab: my drafts, newest first, under "To Me | To Other".
 *
 * To Me is assigned to me, or a self task nobody is on; To Other is assigned
 * to someone else, or a project task nobody is on yet — its rows show who it
 * is for. Opening the tab on an empty side while the other has drafts shows
 * the other. No drafts at all: how drafts work, and Create Task.
 */
@Composable
internal fun DraftsView(state: TasksUiState, onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    val scope = state.draftsScope
    val lists = remember(state.drafts, state.me) {
        mapOf(
            TaskScope.Me to state.drafts.filter { draftIsToMe(it, state.me) },
            TaskScope.Other to state.drafts.filterNot { draftIsToMe(it, state.me) },
        )
    }
    val list = lists.getValue(scope)

    // Opening (or the drafts first arriving) on an empty side while the other has some: show the other.
    LaunchedEffect(state.drafts.size, state.draftsScopeDecided) {
        if (state.drafts.isNotEmpty() && !state.draftsScopeDecided) {
            onEvent(
                TasksEvent.DraftsScopeDecided(
                    if (list.isEmpty()) (if (scope == TaskScope.Me) TaskScope.Other else TaskScope.Me) else scope,
                ),
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            Page {
                ViewLine(str(S.desktop_tasks_nav_drafts_sub))
                if (!state.loaded || !state.draftsLoaded) {
                    DraftsLoading()
                    return@Page
                }
                Column(Modifier.widthIn(max = NARROW), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (state.drafts.isEmpty()) {
                        DraftsEmpty(onEvent)
                        return@Column
                    }
                    ScopeTabs(scope, lists.mapValues { (_, rows) -> rows.size }, { next -> onEvent(TasksEvent.ScopeChanged(TasksView.Drafts, next)) })
                    TText(
                        str(if (scope == TaskScope.Me) S.desktop_tasks_drafts_to_me_sub else S.desktop_tasks_drafts_to_other_sub),
                        13, color = k.muted,
                    )
                    if (list.isEmpty()) {
                        TText(str(if (scope == TaskScope.Me) S.desktop_tasks_drafts_none_me else S.desktop_tasks_drafts_none_other), 14, color = k.faint)
                        return@Column
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        list.forEach { saved ->
                            DraftRow(
                                saved = saved,
                                mainTitle = saved.parentId?.let { state.byId[it]?.title ?: saved.parentTitle }.orEmpty(),
                                showAssignee = scope == TaskScope.Other,
                                crewById = state.crewById,
                                onOpen = { onEvent(TasksEvent.OpenDraft(saved)) },
                                onDelete = { onEvent(TasksEvent.DeleteDraft(saved)) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DraftsLoading() {
    TText(str(S.desktop_tasks_loading), 14, color = TasksTheme.c.muted)
}

/** No drafts at all: what Save draft is for, and a way to start a task. */
@Composable
private fun DraftsEmpty(onEvent: (TasksEvent) -> Unit) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(k.surface2).border(BorderStroke(1.dp, k.line), shape).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ZillitIcon(ZillitIcons.Edit, tint = k.muted, size = 20.dp)
        TText(str(S.desktop_po_no_drafts), 16, SemiBold)
        TText(
            "${str(S.desktop_tasks_drafts_empty_before)} “${str(S.desktop_ad_save_draft)}” ${str(S.desktop_tasks_drafts_empty_after)}",
            13, color = k.muted, maxLines = Int.MAX_VALUE,
        )
        TBtn(str(S.desktop_tasks_create_task_button), { onEvent(TasksEvent.OpenChooser()) }, icon = ZillitIcons.Add, small = true)
    }
}

/**
 * One draft: its icon (a shared task's arrow), its name ("Untitled task"
 * without one), then a line with the DRAFT tag, what it will be, 🔒 or 🌐 when
 * Public / Private was chosen, 📎 and how many images and files, and when it
 * was saved. The row opens it; 🗑 deletes it, pressed twice (the first press
 * asks, and forgets after three seconds).
 *
 *  - [compact]: the dashed DRAFT row under a main task's Shared tasks — no
 *    "what it will be", since it is a shared task of that main task.
 *  - [showAssignee]: who it is for, at the right end (Drafts → To Other):
 *    avatar, name and designation, or "Not assigned yet".
 */
@Composable
internal fun DraftRow(
    saved: SavedTaskDraft,
    mainTitle: String,
    crewById: Map<String, TaskPerson>,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showAssignee: Boolean = false,
) {
    val k = TasksTheme.c
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth()
            .let { if (compact) it.dashedEdge(k.line2) else it.clip(shape).background(k.surface).border(BorderStroke(1.dp, k.line2), shape) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZillitIcon(if (saved.parentId != null) ZillitIcons.ArrowRight else ZillitIcons.Edit, tint = k.muted, size = 16.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                DraftName(saved)
                DraftMeta(saved, mainTitle, compact)
            }
            if (showAssignee) DraftAssignee(saved, crewById)
        }
        DraftDelete(onDelete)
    }
}

/** A draft's name, or "Untitled task" when it has none yet. */
@Composable
private fun DraftName(saved: SavedTaskDraft) {
    val k = TasksTheme.c
    val title = saved.data.title.trim()
    TText(
        title.ifEmpty { str(S.desktop_tasks_draft_untitled) }, 14, Medium,
        if (title.isEmpty()) k.faint else k.fg, maxLines = 1,
    )
}

/**
 * The line under the name: the DRAFT tag, what the draft will be (not on a
 * [compact] shared-task row, which is under that main task already), 🔒 or 🌐
 * once Public / Private is chosen, 📎 and how many images and files, and when
 * it was saved.
 */
@Composable
private fun DraftMeta(saved: SavedTaskDraft, mainTitle: String, compact: Boolean) {
    val k = TasksTheme.c
    // A shared task is public or private as its main task is: nothing to show.
    val vis = saved.data.visibility.takeIf { saved.parentId == null }
    val files = saved.attachments.size
    val saidAt = saved.updatedMillis.takeIf { it > 0 } ?: saved.createdMillis
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TText(
            str(S.draft).uppercase(), 11, SemiBold, k.chipFg,
            Modifier.clip(RoundedCornerShape(4.dp)).background(k.chip).padding(horizontal = 5.dp, vertical = 1.dp),
            maxLines = 1,
        )
        if (!compact) TText(draftKindText(saved, mainTitle), 12, color = k.muted, maxLines = 1)
        if (vis != null) {
            ZillitIcon(
                if (vis == TaskVisibility.Private) ZillitIcons.Lock else ZillitIcons.Globe,
                tint = if (vis == TaskVisibility.Private) k.warn else k.ok, size = 11.dp,
            )
        }
        if (files > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                ZillitIcon(ZillitIcons.Paperclip, tint = k.muted, size = 11.dp)
                TText(files.toString(), 12, color = k.muted, maxLines = 1)
            }
        }
        TText("· ${str(S.desktop_tasks_draft_saved_ago, timeAgo(saidAt, nowMillis()))}", 12, color = k.faint, maxLines = 1)
    }
}

/** Who the draft is for, at the right end (Drafts → To Other). */
@Composable
private fun DraftAssignee(saved: SavedTaskDraft, crewById: Map<String, TaskPerson>) {
    val who = saved.data.assigneeId?.let { crewById[it] }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (who != null || saved.data.assigneeId != null) {
            PersonAvatar(who, 26.dp)
            PersonName(who, null)
        } else {
            TText(str(S.desktop_tasks_draft_not_assigned), 12, color = TasksTheme.c.faint, maxLines = 1)
        }
    }
}

/** 🗑, pressed twice: the first press asks, and forgets after three seconds. */
@Composable
private fun DraftDelete(onDelete: () -> Unit) {
    val k = TasksTheme.c
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(ARM_MS)
            armed = false
        }
    }
    ZillitTooltip(str(if (armed) S.desktop_tasks_draft_delete_again else S.ah_cd_delete_draft)) {
        Row(
            Modifier.height(26.dp).clip(RoundedCornerShape(6.dp)).background(if (armed) k.lateBg else k.chip)
                .clickable {
                    if (armed) {
                        armed = false
                        onDelete()
                    } else {
                        armed = true
                    }
                }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            ZillitIcon(ZillitIcons.Trash, tint = if (armed) k.late else k.muted, size = 14.dp)
            if (armed) TText(str(S.dd_delete_confirm_title), 12, SemiBold, k.late, maxLines = 1)
        }
    }
}

/** What a draft will become, in a few words: "Project task", "Self task", "Shared task in “…”". */
internal fun draftKindText(saved: SavedTaskDraft, mainTitle: String): String = when {
    saved.parentId != null && mainTitle.isNotEmpty() -> str(S.desktop_tasks_draft_kind_shared_in, mainTitle)
    saved.parentId != null -> str(S.desktop_tasks_subtask)
    saved.isSelf -> str(S.desktop_tasks_self_task)
    else -> str(S.desktop_tasks_project_task)
}

/**
 * "Save this task as a draft?" — or, on a saved draft with changes, "Save
 * changes to your draft?" — before a started new task is let go.
 *
 * Keep editing changes nothing; Discard ("Don't save" on a draft) throws the
 * form away and leaves a saved draft as it was; Save as draft keeps it. It
 * covers the tool's own area, over the panel, and Escape keeps editing.
 */
@Composable
internal fun LeavePromptOverlay(state: TasksUiState, onEvent: (TasksEvent) -> Unit) {
    val ask = state.leaveAsk ?: return
    val k = TasksTheme.c
    val shape = RoundedCornerShape(14.dp)
    Box(Modifier.fillMaxSize().background(k.scrim), contentAlignment = Alignment.Center) {
        // A press on the backdrop keeps editing, as Escape does: nothing is thrown away by accident.
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onEvent(TasksEvent.LeaveStay) },
        )
        Column(
            Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f).clip(shape).background(k.surface)
                .border(BorderStroke(1.dp, k.line), shape).padding(18.dp)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.width(32.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(k.accentSoft),
                    contentAlignment = Alignment.Center,
                ) { ZillitIcon(ZillitIcons.Edit, tint = k.accent, size = 16.dp) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TText(str(if (ask.fromDraft) S.desktop_tasks_leave_draft_title else S.desktop_tasks_leave_title), 16, FontWeight.SemiBold)
                    TText(
                        "${str(if (ask.fromDraft) S.desktop_tasks_leave_draft_text else S.desktop_tasks_leave_text)} ${str(S.desktop_tasks_leave_only_you)}",
                        13, color = k.muted, maxLines = Int.MAX_VALUE,
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TBtn(str(S.ah_keep_editing), { onEvent(TasksEvent.LeaveStay) }, kind = BtnKind.Ghost, small = true)
                TBtn(
                    str(if (ask.fromDraft) S.desktop_tasks_leave_dont_save else S.ah_discard),
                    { onEvent(TasksEvent.LeaveDiscard) }, kind = BtnKind.Ghost, small = true,
                )
                TBtn(
                    str(if (ask.fromDraft) S.desktop_ad_save_draft else S.desktop_tasks_leave_save),
                    { onEvent(TasksEvent.LeaveSave) }, small = true,
                )
            }
        }
    }
}

/**
 * When "saved 3h ago" is measured from. A draft's own stamps are instants, so
 * this reads the clock rather than the view's day.
 */
private fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

private const val ARM_MS = 3000L
