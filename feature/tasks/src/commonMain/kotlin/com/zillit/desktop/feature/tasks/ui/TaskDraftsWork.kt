package com.zillit.desktop.feature.tasks.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.tasks.data.httpStatus
import com.zillit.desktop.feature.tasks.domain.DraftAttachmentWrite
import com.zillit.desktop.feature.tasks.domain.DraftWrite
import com.zillit.desktop.feature.tasks.domain.SavedTaskDraft
import com.zillit.desktop.feature.tasks.domain.TasksRepository
import com.zillit.desktop.feature.tasks.domain.compareDrafts
import com.zillit.desktop.feature.tasks.domain.draftData
import com.zillit.desktop.feature.tasks.domain.draftIsToMe
import com.zillit.desktop.feature.tasks.domain.formHasContent
import com.zillit.desktop.feature.tasks.domain.formOfDraft
import com.zillit.desktop.feature.tasks.domain.formSig
import com.zillit.desktop.feature.tasks.domain.visibilityChosen

/**
 * Drafts — a new task someone started and saved to finish later.
 *
 * Only their owner sees one, so this is a list of its own beside the tasks:
 * no socket event, no history, nobody else's rows in it. Every call needs
 * posting rights AND a service that keeps drafts
 * ([TasksUiState.draftsOn]); where that is off there is no Drafts tab, no
 * Save draft and no question on the way out of a started task.
 *
 * Its own class rather than more of [TasksViewModel], as
 * [TaskAttachmentsWork] is: reading, saving and deleting a draft each move
 * the draft's files around, and that class is already the list, the panel
 * and every task write.
 */
internal class TaskDraftsWork(
    private val vm: TasksViewModel,
    private val repository: TasksRepository,
) {

    /** Like the task list's: an older read never overwrites a newer save or delete. */
    private var seq = 0

    /** My drafts, newest first. Does nothing where drafts are not offered. */
    fun load() {
        if (!vm.currentState.draftsOn) return
        seq += 1
        val mine = seq
        vm.work {
            val answer = repository.drafts()
            if (answer !is ZillitResult.Success) return@work
            vm.setDrafts(loaded = true) { if (mine == seq) answer.data.sortedDrafts() else it }
        }
    }

    /** A draft a write (or a read of one) returned, in its place: newest first. */
    fun take(saved: SavedTaskDraft) {
        seq += 1
        vm.setDrafts(loaded = true) { list -> (listOf(saved) + list.filterNot { it.id == saved.id }).sortedDrafts() }
    }

    fun drop(draftId: String) {
        seq += 1
        vm.setDrafts(loaded = null) { list -> list.filterNot { it.id == draftId } }
    }

    /** Every shared-task draft under a main task that has just been deleted: the service deletes them too. */
    fun dropUnder(mainTaskId: String) {
        seq += 1
        vm.setDrafts(loaded = null) { list -> list.filterNot { it.parentId == mainTaskId } }
    }

    /**
     * The draft the panel has just been pointed at, read before its form shows
     * (its images and files come with links then).
     *
     * A shared-task draft whose main task is no longer in the list opens as a
     * task of its own. One that is gone — deleted somewhere else — says so and
     * the panel closes.
     */
    fun fetch(panelKey: Long, draftId: String) {
        vm.work {
            val answer = repository.draft(draftId)
            if (vm.currentState.panel?.key != panelKey) return@work
            when (answer) {
                is ZillitResult.Failure -> {
                    if (answer.error.draftGone) drop(draftId)
                    vm.closePanelAt(panelKey)
                    vm.failWith(answer.error)
                }
                is ZillitResult.Success -> openForm(panelKey, answer.data)
            }
        }
    }

    private fun openForm(panelKey: Long, saved: SavedTaskDraft) {
        val state = vm.currentState
        val mainId = saved.parentId?.takeIf { it in state.byId }
        if (saved.parentId != null && mainId == null) vm.warn(str(S.desktop_tasks_draft_main_gone))
        val form = formOfDraft(saved, mainId)
        val pending = mainId == null && state.features.visibility && !saved.visibilityChosen
        val keys = saved.attachments.map { DRAFT_FILE_PREFIX + it.id }
        vm.panelEdit {
            if (key != panelKey) {
                this
            } else {
                copy(
                    orig = form,
                    draft = form,
                    parentId = mainId,
                    parentTitle = if (mainId == null) "" else parentTitle.ifEmpty { saved.parentTitle },
                    visPending = pending,
                    visNag = 0,
                    draftFiles = saved.attachments,
                    droppedFiles = emptyList(),
                    savedSig = formSig(form, pending, keys),
                    draftReady = true,
                )
            }
        }
        take(saved)
    }

    /**
     * Save draft: the form as it is, with its images and files, kept to finish
     * later. A new draft is made, or the open one updated; what waits in the
     * browser then goes up to it, and what was taken out of the form leaves it.
     *
     * The Drafts tab will open on the tab ("To Me" / "To Other") the draft
     * landed in. Answers true when it was saved.
     */
    @Suppress("ReturnCount")
    suspend fun save(panelKey: Long): Boolean {
        val state = vm.currentState
        val open = state.panel?.takeIf { it.key == panelKey } ?: return false
        val draft = open.draft ?: return false
        if (!canSave(open, state)) return false
        if (!formHasContent(draft, open.fileKeys.size, open.draftId)) {
            vm.warn(str(S.desktop_tasks_draft_nothing))
            return false
        }
        vm.panelEdit { if (key == panelKey) copy(saving = true) else this }
        val data = draftData(draft, chosen = !open.visUnchosen(state.features))
        val answer = if (open.draftId != null) {
            repository.updateDraft(open.draftId, data)
        } else {
            repository.createDraft(isSelf = draft.isSelf, parentId = draft.parentId, data = data)
        }
        if (answer is DraftWrite.Refused) {
            refused(answer, open.draftId, panelKey)
            return false
        }
        var saved = (answer as DraftWrite.Saved).draft
        vm.announceMessage(answer.message)
        saved = moveFiles(saved, open.droppedFiles, panelKey)
        take(saved)
        vm.setDraftsScope(if (draftIsToMe(saved, vm.currentState.me)) TaskScope.Me else TaskScope.Other)
        vm.panelEdit { if (key == panelKey) copy(saving = false, released = true) else this }
        return true
    }

    /** Only a new task's form, not already saving, with posting rights and a service that keeps drafts. */
    private fun canSave(open: TaskPanel, state: TasksUiState): Boolean =
        open.creating && !open.saving && state.canPost && state.draftsOn

    /**
     * A save the service refused. One it says is gone — deleted somewhere else
     * — leaves the list, and what the form holds can still become a new draft.
     */
    private fun refused(answer: DraftWrite.Refused, draftId: String?, panelKey: Long) {
        vm.panelEdit { if (key == panelKey) copy(saving = false) else this }
        vm.failWith(answer.error)
        if (draftId == null || !answer.isGone) return
        drop(draftId)
        vm.panelEdit {
            if (key != panelKey) this else copy(draftId = null, draftFiles = emptyList(), droppedFiles = emptyList())
        }
    }

    /**
     * The draft's files after a save: the ones taken out of the form are
     * removed from it, then what waited in the browser goes up to it, one
     * after another. Each answer is the draft as the service now has it.
     */
    private suspend fun moveFiles(saved: SavedTaskDraft, dropped: List<String>, panelKey: Long): SavedTaskDraft {
        var now = saved
        dropped.forEach { attachmentId ->
            when (val gone = repository.removeDraftAttachment(now.id, attachmentId)) {
                is DraftAttachmentWrite.Saved -> now = gone.draft
                // Already gone is not worth a word; anything else the service said is.
                is DraftAttachmentWrite.Refused -> if (gone.httpStatus != NOT_FOUND) vm.failWith(gone.error)
            }
        }
        val waiting = vm.currentState.panel?.takeIf { it.key == panelKey }?.pending.orEmpty()
        waiting.forEach { item ->
            when (val up = repository.addDraftAttachment(now.id, item)) {
                is DraftAttachmentWrite.Saved -> {
                    now = up.draft
                    vm.panelEdit {
                        if (key == panelKey) copy(pending = pending.filterNot { it.key == item.key }) else this
                    }
                }
                is DraftAttachmentWrite.Refused -> vm.failWith(up.error)
            }
        }
        return now
    }

    /**
     * Images and files taken out of a draft's form go out of the DRAFT before
     * Create, or the service would move them onto the new task with the rest.
     * Answers false when one could not be removed: nothing is created then.
     */
    suspend fun dropFilesBeforeCreate(draftId: String, dropped: List<String>): Boolean {
        dropped.forEach { attachmentId ->
            val gone = repository.removeDraftAttachment(draftId, attachmentId)
            if (gone is DraftAttachmentWrite.Refused && gone.httpStatus != NOT_FOUND) {
                vm.failWith(gone.error)
                return false
            }
        }
        return true
    }

    /**
     * Deletes a draft; its images and files go with it. One already gone
     * (deleted elsewhere) leaves the list too. Answers true when it is gone.
     */
    suspend fun delete(draftId: String): Boolean {
        if (!vm.currentState.draftsOn) return false
        val answer = repository.deleteDraft(draftId)
        val gone = answer is ZillitResult.Success || (answer as? ZillitResult.Failure)?.error?.draftGone == true
        when (answer) {
            is ZillitResult.Success -> vm.announceMessage(answer.data)
            is ZillitResult.Failure -> vm.failWith(answer.error)
        }
        if (gone) drop(draftId)
        return gone
    }

    private fun List<SavedTaskDraft>.sortedDrafts(): List<SavedTaskDraft> = sortedWith(compareDrafts)

    private companion object {
        const val NOT_FOUND = 404
    }
}

/** A 404 from a draft call: it is not there any more (or never was mine). */
private val ZillitError.draftGone: Boolean get() = httpStatus == NOT_FOUND_STATUS

private const val NOT_FOUND_STATUS = 404
