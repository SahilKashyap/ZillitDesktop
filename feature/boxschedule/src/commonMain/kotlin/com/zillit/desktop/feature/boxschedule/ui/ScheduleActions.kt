package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.boxschedule.domain.BlockDraft
import com.zillit.desktop.feature.boxschedule.domain.BlockWrite
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DateConflict
import com.zillit.desktop.feature.boxschedule.domain.DiaryCalendar
import com.zillit.desktop.feature.boxschedule.domain.DiaryFormat
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import kotlinx.datetime.LocalDate

/**
 * Schedules — the web's `CreateScheduleModal`, `ConflictDialog`,
 * `ScheduleScopePrompt` and the page's delete handlers.
 *
 * Deletes never widen what was chosen: a date removal that fails is reported,
 * not retried as a delete of the whole block (the web's own
 * `confirmDelete` fix, applied to every delete path here).
 */
@Suppress("TooManyFunctions") // One handler per act in the schedule drawer and its prompts.
internal class ScheduleActions(private val vm: BoxScheduleViewModel) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per control.
    fun onEvent(event: ScheduleEvent) {
        when (event) {
            is ScheduleEvent.NewSchedule -> newSchedule(event.date)
            is ScheduleEvent.EditSchedule -> editSchedule(event.blockId, event.date)
            is ScheduleEvent.ChooseScope -> vm.updateOverlays {
                copy(scheduleScope = scheduleScope?.copy(scope = event.scope))
            }
            ScheduleEvent.ConfirmScope -> confirmScope()
            ScheduleEvent.CancelScope -> vm.updateOverlays { copy(scheduleScope = null) }
            is ScheduleEvent.SetType -> form { copy(typeId = event.typeId) }
            is ScheduleEvent.SetTitle -> form { copy(title = event.title) }
            is ScheduleEvent.SetTab -> form { copy(tab = event.tab) }
            is ScheduleEvent.SetRangeMode -> form { copy(rangeMode = event.mode) }
            is ScheduleEvent.SetStart -> form {
                if (lockedStart != null || isPast(event.date)) this else copy(start = event.date)
            }
            is ScheduleEvent.SetCount -> form { copy(countText = event.text.filter(Char::isDigit).take(COUNT_DIGITS)) }
            is ScheduleEvent.SetEnd -> form { if (isPast(event.date)) this else copy(end = event.date) }
            is ScheduleEvent.TogglePick -> form { togglePick(event.date) }
            is ScheduleEvent.SetDayWiseStart -> form {
                if (lockedStart != null || isPast(event.date)) this else copy(dayWiseStart = event.date)
            }
            is ScheduleEvent.SetDayWiseEnd -> form { if (isPast(event.date)) this else copy(dayWiseEnd = event.date) }
            is ScheduleEvent.ToggleWeekday -> form {
                copy(weekdays = if (event.day in weekdays) weekdays - event.day else weekdays + event.day)
            }
            is ScheduleEvent.SetSingleAction -> form { copy(singleAction = event.action) }
            ScheduleEvent.OpenNewType -> form { copy(newType = if (newType == null) NewTypeDraft() else null) }
            is ScheduleEvent.SetNewTypeName -> form { copy(newType = newType?.copy(name = event.name)) }
            is ScheduleEvent.SetNewTypeColor -> form { copy(newType = newType?.copy(color = event.color)) }
            ScheduleEvent.CancelNewType -> form { copy(newType = null) }
            ScheduleEvent.CreateNewType -> createInlineType()
            ScheduleEvent.Save -> save()
            ScheduleEvent.CloseForm -> vm.updateOverlays { copy(scheduleForm = null) }
            is ScheduleEvent.PickConflict -> vm.updateOverlays {
                copy(conflict = conflict?.copy(choice = event.action))
            }
            ScheduleEvent.ResolveConflict -> resolveConflict()
            ScheduleEvent.ConflictBack -> vm.updateOverlays {
                conflict?.let { copy(conflict = null, scheduleForm = it.form.copy(saving = false)) } ?: this
            }
            ScheduleEvent.CancelConflict -> vm.updateOverlays { copy(conflict = null) }
            is ScheduleEvent.AskDeleteDay -> askDeleteDay(event.blockId, event.date)
            ScheduleEvent.ConfirmDeleteDay -> confirmDeleteDay()
            ScheduleEvent.CancelDeleteDay -> vm.updateOverlays { copy(deleteDay = null) }
            is ScheduleEvent.AskDeleteBlock -> if (vm.mayEdit()) vm.updateOverlays { copy(deleteBlock = event.blockId) }
            ScheduleEvent.ConfirmDeleteBlock -> confirmDeleteBlock()
            ScheduleEvent.CancelDeleteBlock -> vm.updateOverlays { copy(deleteBlock = null) }
            is ScheduleEvent.AskDeleteAllOn -> if (vm.mayEdit()) vm.updateOverlays { copy(deleteAllOn = event.dayKey) }
            ScheduleEvent.ConfirmDeleteAllOn -> confirmDeleteAllOn()
            ScheduleEvent.CancelDeleteAllOn -> vm.updateOverlays { copy(deleteAllOn = null) }
            ScheduleEvent.AskBulkDelete -> if (vm.mayEdit() && vm.currentState.page.selected.isNotEmpty()) {
                vm.updateOverlays { copy(bulkDelete = true) }
            }
            ScheduleEvent.ConfirmBulkDelete -> confirmBulkDelete()
            ScheduleEvent.CancelBulkDelete -> vm.updateOverlays { copy(bulkDelete = false) }
        }
    }

    private fun form(change: ScheduleForm.() -> ScheduleForm) =
        vm.updateOverlays { copy(scheduleForm = scheduleForm?.change()) }

    /** Past dates are greyed out in every picker; the handler refuses them too. */
    private fun isPast(date: LocalDate?): Boolean = date != null && date < vm.currentState.today

    private fun ScheduleForm.togglePick(date: LocalDate): ScheduleForm = when {
        date == lockedStart || isPast(date) -> this
        date in picked -> copy(picked = picked - date)
        else -> copy(picked = picked + date)
    }

    // Opening ----------------------------------------------------------------

    private fun newSchedule(date: Long?) {
        if (!vm.mayEdit()) return
        val start = date?.let { DiaryCalendar.dateOf(it, vm.currentState.zone) }
        vm.updateOverlays {
            copy(scheduleForm = ScheduleForm.create(start), day = null, quickAction = null, viewing = null)
        }
    }

    private fun editSchedule(blockId: String, date: Long?) {
        if (!vm.mayEdit()) return
        val block = vm.currentState.block(blockId) ?: return
        if (date != null && block.calendarDays.size > 1) {
            vm.updateOverlays { copy(scheduleScope = ScheduleScopePrompt(blockId, date, ScopeMode.Edit)) }
        } else {
            vm.updateOverlays { copy(scheduleForm = ScheduleForm.edit(block, vm.currentState.zone), day = null) }
        }
    }

    private fun confirmScope() {
        val prompt = vm.currentState.overlays.scheduleScope ?: return
        if (!vm.mayEdit()) return
        val block = vm.currentState.block(prompt.blockId)
        if (block == null) {
            vm.updateOverlays { copy(scheduleScope = null) }
            return
        }
        when (prompt.mode) {
            ScopeMode.Edit -> vm.updateOverlays {
                val next = if (prompt.scope == ScheduleScope.Single) {
                    ScheduleForm.singleDay(block, stored(block, prompt.date))
                } else {
                    ScheduleForm.edit(block, vm.currentState.zone)
                }
                copy(scheduleScope = null, scheduleForm = next, day = null)
            }
            ScopeMode.Delete -> {
                vm.updateOverlays { copy(scheduleScope = prompt.copy(working = true)) }
                vm.work {
                    val result = if (prompt.scope == ScheduleScope.Single) {
                        vm.repo.removeDates(mapOf(block.id to listOf(stored(block, prompt.date))))
                    } else {
                        vm.repo.deleteBlock(block.id)
                    }
                    vm.updateOverlays { copy(scheduleScope = null) }
                    afterDelete(result)
                }
            }
        }
    }

    // Saving -------------------------------------------------------------------

    private fun save() {
        val form = vm.currentState.overlays.scheduleForm ?: return
        if (!vm.mayEdit() || !form.canSave) return
        form { copy(saving = true) }
        if (form.isSingleDay) saveSingleDay(form) else saveBlock(form)
    }

    /**
     * One date to another type — `PUT /days/:id/single-date`, the server's
     * atomic split. A kept type is only a title change.
     */
    private fun saveSingleDay(form: ScheduleForm) {
        val blockId = form.blockId ?: return
        val date = form.singleDate ?: return
        vm.work {
            if (!form.typeChanged) {
                finishSingleDay(vm.repo.renameBlock(blockId, form.title.trim()), "Schedule updated successfully")
                return@work
            }
            val newType = vm.currentState.types.firstOrNull { it.id == form.typeId }?.title ?: "new type"
            val day = DiaryFormat.monthDay(date, vm.currentState.zone)
            val old = form.originalTypeName
            val message = when (form.singleAction) {
                ConflictAction.Replace -> "$day changed from $old to $newType"
                ConflictAction.Extend -> "$day changed to $newType. $old extended by 1 day to keep the same total."
                ConflictAction.Overlap -> "$newType added on $day (overlapping with $old)"
            }
            finishSingleDay(vm.repo.changeSingleDay(blockId, date, form.typeId, form.singleAction), message)
        }
    }

    private fun finishSingleDay(result: ZillitResult<Unit>, message: String) {
        when (result) {
            is ZillitResult.Success -> {
                vm.updateOverlays { copy(scheduleForm = null) }
                vm.notice(message, success = true)
                vm.refresh()
            }
            is ZillitResult.Failure -> {
                form { copy(saving = false) }
                vm.notice(result.error.localised())
            }
        }
    }

    private fun saveBlock(form: ScheduleForm) {
        val draft = form.draft(vm.currentState.zone)
        vm.work {
            val result = form.blockId?.let { vm.repo.updateBlock(it, draft) } ?: vm.repo.createBlock(draft)
            when (result) {
                is ZillitResult.Success -> when (val write = result.data) {
                    BlockWrite.Saved -> {
                        vm.updateOverlays { copy(scheduleForm = null) }
                        vm.notice(if (form.isEdit) "Schedule updated" else "Schedule created", success = true)
                        vm.refresh()
                    }
                    is BlockWrite.Conflicts -> vm.updateOverlays {
                        copy(
                            scheduleForm = null,
                            conflict = ConflictPrompt(
                                conflicts = write.conflicts.ifEmpty { collisions(draft, form.blockId) },
                                draft = draft,
                                blockId = form.blockId,
                                form = form.copy(saving = false),
                            ),
                        )
                    }
                }
                is ZillitResult.Failure -> {
                    form { copy(saving = false) }
                    vm.notice(result.error.localised())
                }
            }
        }
    }

    /** Replace, Extend or Overlap — the same write again, with `conflictAction`. */
    private fun resolveConflict() {
        val prompt = vm.currentState.overlays.conflict ?: return
        val choice = prompt.choice ?: return
        if (!vm.mayEdit() || prompt.saving) return
        vm.updateOverlays { copy(conflict = prompt.copy(saving = true)) }
        vm.work {
            val result = prompt.blockId?.let { vm.repo.updateBlock(it, prompt.draft, choice) }
                ?: vm.repo.createBlock(prompt.draft, choice)
            when (result) {
                is ZillitResult.Success -> when (val write = result.data) {
                    BlockWrite.Saved -> {
                        vm.updateOverlays { copy(conflict = null) }
                        vm.notice(
                            if (prompt.blockId != null) "Schedule updated" else "Schedule created",
                            success = true,
                        )
                        vm.refresh()
                    }
                    is BlockWrite.Conflicts -> vm.updateOverlays {
                        val named = write.conflicts.ifEmpty { conflict?.conflicts.orEmpty() }
                        copy(conflict = conflict?.copy(conflicts = named, saving = false))
                    }
                }
                is ZillitResult.Failure -> {
                    vm.updateOverlays { copy(conflict = conflict?.copy(saving = false)) }
                    vm.notice(result.error.localised())
                }
            }
        }
    }

    /**
     * The colliding dates, named from the schedule already on screen — a 409
     * says there was a clash without saying where.
     */
    private fun collisions(draft: BlockDraft, editing: String?): List<DateConflict> {
        val zone = vm.currentState.zone
        return draft.calendarDays.flatMap { day ->
            val key = DiaryCalendar.dayKey(day, zone)
            vm.currentState.blocks
                .filter { it.id != editing && it.calendarDays.any { d -> DiaryCalendar.dayKey(d, zone) == key } }
                .map {
                    DateConflict(
                        date = day,
                        existingType = it.typeName,
                        existingColor = it.color,
                        existingTitle = it.title,
                    )
                }
        }
    }

    /**
     * The drawer's "+" type — checked for a clashing name or colour first, so
     * the refusal names the type it clashes with, then selected once made.
     */
    private fun createInlineType() {
        val draft = vm.currentState.overlays.scheduleForm?.newType ?: return
        val name = draft.name.trim()
        if (!vm.mayEdit() || draft.saving || name.isEmpty()) return
        val clash = typeClash(name, draft.color)
        if (clash != null) {
            vm.notice(clash)
            return
        }
        form { copy(newType = newType?.copy(saving = true)) }
        vm.work {
            when (val result = vm.repo.createType(name, draft.color)) {
                is ZillitResult.Success -> {
                    form { copy(newType = null, typeId = result.data ?: typeId) }
                    vm.refresh()
                }
                is ZillitResult.Failure -> {
                    form { copy(newType = newType?.copy(saving = false)) }
                    vm.notice(result.error.localised().ifBlank { "Failed to create type" })
                }
            }
        }
    }

    /** Why a new type cannot be made — its name or its colour is taken, naming the type that has it — or null. */
    private fun typeClash(name: String, color: String): String? {
        val types = vm.currentState.types
        if (types.any { it.title.trim().equals(
            name,
            ignoreCase = true,
        ) }) return "A type with this name already exists."
        val owner = types.firstOrNull { it.color.equals(color, ignoreCase = true) } ?: return null
        val taken = owner.title.ifBlank { "another type" }
        return "That color is already used by $taken. Please pick a different color."
    }

    // Deleting -----------------------------------------------------------------

    private fun askDeleteDay(blockId: String, date: Long?) {
        if (!vm.mayEdit()) return
        val block = vm.currentState.block(blockId) ?: return
        if (date != null && block.calendarDays.size > 1) {
            vm.updateOverlays { copy(scheduleScope = ScheduleScopePrompt(blockId, date, ScopeMode.Delete)) }
        } else {
            vm.updateOverlays { copy(deleteDay = DeleteDayPrompt(blockId, date)) }
        }
    }

    private fun confirmDeleteDay() {
        val prompt = vm.currentState.overlays.deleteDay ?: return
        if (!vm.mayEdit()) return
        vm.updateOverlays { copy(deleteDay = prompt.copy(working = true)) }
        vm.work {
            val result = vm.repo.deleteBlock(prompt.blockId)
            vm.updateOverlays { copy(deleteDay = null) }
            afterDelete(result)
        }
    }

    private fun confirmDeleteBlock() {
        val blockId = vm.currentState.overlays.deleteBlock ?: return
        if (!vm.mayEdit()) return
        vm.updateOverlays { copy(deleteBlock = null) }
        vm.work { afterDelete(vm.repo.deleteBlock(blockId)) }
    }

    /** The server's own sentence on success — ZL-21307 — or the web's fallback. */
    private fun afterDelete(result: ZillitResult<String?>) {
        when (result) {
            is ZillitResult.Success -> {
                vm.notice(sentence(result.data, "Schedule day dates removed successfully"), success = true)
                vm.updatePage { copy(expandedRow = null) }
                vm.refresh()
            }
            is ZillitResult.Failure -> vm.notice(result.error.localised().ifBlank { "Failed to delete" })
        }
    }

    /** Every schedule on one date loses that date; a one-day schedule goes entirely, server-side. */
    private fun confirmDeleteAllOn() {
        val dayKey = vm.currentState.overlays.deleteAllOn ?: return
        if (!vm.mayEdit()) return
        val zone = vm.currentState.zone
        val onDay = vm.currentState.page.filter.calendarBlocks(vm.currentState.blocks)
            .filter { block -> block.calendarDays.any { DiaryCalendar.dayKey(it, zone) == dayKey } }
        vm.updateOverlays { copy(deleteAllOn = null) }
        if (onDay.isEmpty()) return
        vm.work {
            when (val result = vm.repo.removeDates(onDay.associate { it.id to listOf(stored(it, dayKey)) })) {
                is ZillitResult.Success -> {
                    vm.notice("${onDay.size} schedule${if (onDay.size > 1) "s" else ""} removed", success = true)
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.notice(result.error.localised().ifBlank { "Failed to delete" })
            }
        }
    }

    private fun confirmBulkDelete() {
        if (!vm.mayEdit()) return
        val rows = vm.currentState.selectedRows
        vm.updateOverlays { copy(bulkDelete = false) }
        if (rows.isEmpty()) return
        val entries = rows.groupBy { it.block.id }.mapValues { (_, dayRows) ->
            dayRows.map { stored(it.block, it.date) }
        }
        vm.work {
            when (val result = vm.repo.removeDates(entries)) {
                is ZillitResult.Success -> {
                    vm.notice("${rows.size} day(s) deleted", success = true)
                    vm.updatePage { copy(selecting = false, selected = emptySet()) }
                    vm.refresh()
                }
                is ZillitResult.Failure -> vm.notice(result.error.localised().ifBlank { "Failed to delete" })
            }
        }
    }

    /**
     * The date exactly as the block stores it. The page keys days by local
     * midnight; the server matches the value it saved, which a block made in
     * another zone does not share.
     */
    private fun stored(block: ScheduleBlock, dayKey: Long): Long {
        val zone = vm.currentState.zone
        val key = DiaryCalendar.dayKey(dayKey, zone)
        return block.calendarDays.firstOrNull { DiaryCalendar.dayKey(it, zone) == key } ?: dayKey
    }

    private companion object {
        const val COUNT_DIGITS = 3
    }
}

/**
 * A server message fit to show, or [fallback]: translated when it is a key,
 * and never a raw snake_case identifier.
 */
internal fun sentence(message: String?, fallback: String): String {
    val raw = message?.trim().orEmpty()
    if (raw.isEmpty()) return fallback
    val text = raw.localisedMessage()
    return if (text.contains(' ')) text else fallback
}
