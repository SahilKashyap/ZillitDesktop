@file:Suppress("TooManyFunctions") // One handler per user act.

package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.feature.boxschedule.domain.BlockDraft
import com.zillit.desktop.feature.boxschedule.domain.BlockWrite
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleRepository
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryClock
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.DiaryMath
import com.zillit.desktop.feature.boxschedule.domain.MainCalendarLookup
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The production diary.
 *
 * Every mutation is fire-then-refetch, like the web (nothing is optimistic
 * except nothing — the web's one splice path is for recurring PUTs, and this
 * client refetches instead). Types are refetched with the blocks because
 * their names and colours are denormalised onto every block.
 */
class BoxScheduleViewModel(
    private val repository: BoxScheduleRepository,
    private val calendar: MainCalendarLookup,
    private val resolveViewer: () -> BoxScheduleViewer,
    private val nowMillis: () -> Long,
) : ZillitViewModel<BoxScheduleUiState, BoxScheduleEvent, BoxScheduleEffect>(BoxScheduleUiState()) {

    fun start() {
        setState { copy(viewer = resolveViewer()) }
        refresh()
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod") // Event fan-out: one line per act.
    override fun onEvent(event: BoxScheduleEvent) {
        when (event) {
            BoxScheduleEvent.Refresh -> refresh()
            BoxScheduleEvent.NewBlock -> setState {
                copy(blockEditor = BlockEditor(typeId = types.firstOrNull()?.id.orEmpty()))
            }
            is BoxScheduleEvent.EditBlock -> openBlock(event.blockId)
            is BoxScheduleEvent.BlockChanged -> setState {
                copy(
                    blockEditor = blockEditor?.copy(
                        typeId = event.typeId ?: blockEditor.typeId,
                        title = event.title ?: blockEditor.title,
                        startText = event.startText ?: blockEditor.startText,
                        endText = event.endText ?: blockEditor.endText,
                        conflicts = emptyList(),
                    ),
                )
            }
            BoxScheduleEvent.SaveBlock -> saveBlock(resolve = null)
            is BoxScheduleEvent.ResolveConflict -> saveBlock(
                resolve = ConflictAction.entries.firstOrNull { it.wire == event.action },
            )
            BoxScheduleEvent.CloseBlock -> setState { copy(blockEditor = null) }
            is BoxScheduleEvent.DeleteBlockDate -> deleteBlockDate(event.blockId, event.date)
            is BoxScheduleEvent.DeleteBlock -> run(
                { repository.deleteBlock(event.blockId) },
                "Schedule removed",
            )
            is BoxScheduleEvent.NewDiary -> setState {
                copy(
                    diaryEditor = DiaryEditor(
                        kind = event.kind,
                        dateText = DiaryClock.ymd(event.date ?: nowMillis()),
                        scheduleDayId = event.scheduleDayId,
                        noteType = noteTypes.firstOrNull()?.value ?: "general",
                    ),
                )
            }
            is BoxScheduleEvent.EditDiary -> openDiary(event.listKey)
            is BoxScheduleEvent.DiaryChanged -> setState {
                copy(
                    diaryEditor = diaryEditor?.copy(
                        title = event.title ?: diaryEditor.title,
                        body = event.body ?: diaryEditor.body,
                        dateText = event.dateText ?: diaryEditor.dateText,
                        startText = event.startText ?: diaryEditor.startText,
                        endText = event.endText ?: diaryEditor.endText,
                        location = event.location ?: diaryEditor.location,
                        color = event.color ?: diaryEditor.color,
                        noteType = event.noteType ?: diaryEditor.noteType,
                        repeatStatus = event.repeatStatus ?: diaryEditor.repeatStatus,
                    ),
                )
            }
            BoxScheduleEvent.SaveDiary -> saveDiary()
            BoxScheduleEvent.CloseDiary -> setState { copy(diaryEditor = null) }
            is BoxScheduleEvent.DeleteDiary -> deleteDiary(event.listKey)
            BoxScheduleEvent.OpenTypes -> setState { copy(manageTypes = true) }
            BoxScheduleEvent.CloseTypes -> setState { copy(manageTypes = false, typeEditor = null) }
            BoxScheduleEvent.NewType -> setState { copy(typeEditor = TypeEditor()) }
            is BoxScheduleEvent.EditType -> setState {
                val type = types.firstOrNull { it.id == event.typeId }
                copy(
                    typeEditor = type?.let {
                        TypeEditor(typeId = it.id, title = it.title, color = it.color)
                    },
                )
            }
            is BoxScheduleEvent.TypeChanged -> setState {
                copy(
                    typeEditor = typeEditor?.copy(
                        title = event.title ?: typeEditor.title,
                        color = event.color ?: typeEditor.color,
                    ),
                )
            }
            BoxScheduleEvent.SaveType -> saveType()
            BoxScheduleEvent.CloseTypeEditor -> setState { copy(typeEditor = null) }
            is BoxScheduleEvent.DeleteType -> run({ repository.deleteType(event.typeId) }, "Type removed")
            BoxScheduleEvent.DismissError -> setState { copy(error = null) }
        }
    }

    private fun refresh() {
        setState { copy(loading = true) }
        launch {
            coroutineScope {
                val types = async { repository.types() }
                val blocks = async { repository.blocks() }
                val events = async { repository.events() }
                val noteTypes = async { repository.noteTypes() }
                // The web's window: a year either side of now.
                val calendarEvents = async {
                    calendar.events(nowMillis() - YEAR_MS, nowMillis() + YEAR_MS)
                }

                val typeList = types.await().orError()
                val blockList = blocks.await().orError()
                val eventList = events.await().orError()
                val noteTypeList = noteTypes.await().let { (it as? ZillitResult.Success)?.data.orEmpty() }
                val merged = DiaryMath.mergeWithCalendar(eventList.orEmpty(), calendarEvents.await())

                setState {
                    copy(
                        loading = false,
                        types = typeList ?: this.types,
                        blocks = blockList ?: this.blocks,
                        rows = DiaryMath.explode(blockList ?: this.blocks),
                        events = merged,
                        noteTypes = noteTypeList,
                    )
                }
            }
        }
    }

    private fun <T> ZillitResult<T>.orError(): T? = when (this) {
        is ZillitResult.Success -> data
        is ZillitResult.Failure -> {
            val message = this.error.userMessage
            setState { copy(error = message) }
            null
        }
    }

    private fun run(block: suspend () -> ZillitResult<Unit>, notice: String) {
        setState { copy(busy = true) }
        launch {
            when (val result = block()) {
                is ZillitResult.Success -> {
                    setState { copy(busy = false) }
                    sendEffect(BoxScheduleEffect.Notice(notice))
                    refresh()
                }
                is ZillitResult.Failure -> setState { copy(busy = false, error = result.error.userMessage) }
            }
        }
    }

    // Blocks ---------------------------------------------------------------

    private fun openBlock(blockId: String) {
        val block = state.value.blocks.firstOrNull { it.id == blockId } ?: return
        setState {
            copy(
                blockEditor = BlockEditor(
                    blockId = block.id,
                    typeId = block.typeId,
                    title = block.title,
                    startText = DiaryClock.ymd(block.calendarDays.minOrNull() ?: block.startDate),
                    endText = DiaryClock.ymd(block.calendarDays.maxOrNull() ?: block.endDate),
                ),
            )
        }
    }

    private fun saveBlock(resolve: ConflictAction?) {
        val editor = state.value.blockEditor ?: return
        val start = DiaryClock.midnightOf(editor.startText)
        val end = DiaryClock.midnightOf(editor.endText.ifBlank { editor.startText })
        val validRange = start != null && end != null && end >= start
        if (editor.typeId.isBlank() || !validRange) {
            setState { copy(error = "Pick a type and a valid date range (YYYY-MM-DD)") }
            return
        }
        checkNotNull(start)
        checkNotNull(end)
        val draft = BlockDraft(
            typeId = editor.typeId,
            title = editor.title.trim(),
            calendarDays = DiaryMath.rangeDays(start, end),
            byDates = false,
        )
        setState { copy(blockEditor = blockEditor?.copy(saving = true, conflicts = emptyList())) }
        launch {
            val result = if (editor.blockId == null) {
                repository.createBlock(draft, resolve)
            } else {
                repository.updateBlock(editor.blockId, draft, resolve)
            }
            when (result) {
                is ZillitResult.Success -> when (val write = result.data) {
                    BlockWrite.Saved -> {
                        setState { copy(blockEditor = null) }
                        sendEffect(BoxScheduleEffect.Notice("Schedule saved"))
                        refresh()
                    }
                    is BlockWrite.Conflicts -> setState {
                        copy(
                            blockEditor = blockEditor?.copy(
                                saving = false,
                                conflicts = write.conflicts.ifEmpty {
                                    // A bare 409: we know there was a clash, not where.
                                    listOf(
                                        com.zillit.desktop.feature.boxschedule.domain.DateConflict(
                                            date = start, existingType = "another schedule",
                                            existingColor = "", existingTitle = "",
                                        ),
                                    )
                                },
                            ),
                        )
                    }
                }
                is ZillitResult.Failure -> setState {
                    copy(blockEditor = blockEditor?.copy(saving = false), error = result.error.userMessage)
                }
            }
        }
    }

    private fun deleteBlockDate(blockId: String, date: Long) {
        val block = state.value.blocks.firstOrNull { it.id == blockId } ?: return
        run(
            {
                if (block.calendarDays.size <= 1) {
                    repository.deleteBlock(blockId)
                } else {
                    repository.removeDates(mapOf(blockId to listOf(date)))
                }
            },
            "Date removed",
        )
    }

    // Events / notes -------------------------------------------------------

    private fun openDiary(listKey: String) {
        val event = state.value.events.firstOrNull { it.listKey == listKey } ?: return
        if (event.calendarSourced) {
            sendEffect(BoxScheduleEffect.Notice("Calendar events are edited from the Home calendar"))
            return
        }
        setState {
            copy(
                diaryEditor = DiaryEditor(
                    eventId = event.masterId,
                    kind = event.kind,
                    title = event.title,
                    body = event.body,
                    dateText = DiaryClock.ymd(event.date),
                    startText = if (event.fullDay) "" else DiaryClock.hm(event.startDateTime),
                    endText = if (event.fullDay) "" else DiaryClock.hm(event.endDateTime),
                    location = event.location,
                    color = event.color.ifBlank { DiaryDraft.DEFAULT_EVENT_COLOR },
                    scheduleDayId = event.scheduleDayId,
                    noteType = event.noteType.ifBlank { "general" },
                    repeatStatus = event.repeatStatus.ifBlank { "none" },
                    occurrenceDate = event.occurrenceDate.takeIf { event.isRecurring },
                    isRecurring = event.isRecurring,
                ),
            )
        }
    }

    private fun saveDiary() {
        val editor = state.value.diaryEditor ?: return
        val draft = editor.toDraft() ?: run {
            setState {
                copy(error = "A title, a valid date (YYYY-MM-DD) and HH:mm times ending after start are needed")
            }
            return
        }
        setState { copy(diaryEditor = diaryEditor?.copy(saving = true)) }
        launch {
            val result = if (editor.eventId == null) {
                repository.createEvent(draft)
            } else {
                // Recurring rows edit the whole series here; per-occurrence
                // scopes need a picker this build does not have yet.
                repository.updateEvent(editor.eventId, draft, RecurrenceScope.All, null)
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(diaryEditor = null) }
                    val saved = if (editor.kind == DiaryKind.Note) "Note saved" else "Event saved"
                    sendEffect(BoxScheduleEffect.Notice(saved))
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(diaryEditor = diaryEditor?.copy(saving = false), error = result.error.userMessage)
                }
            }
        }
    }

    private fun deleteDiary(listKey: String) {
        val event = state.value.events.firstOrNull { it.listKey == listKey } ?: return
        if (event.calendarSourced) {
            sendEffect(BoxScheduleEffect.Notice("Calendar events are removed from the Home calendar"))
            return
        }
        run({ repository.deleteEvent(event.masterId, RecurrenceScope.All, null) }, "Removed")
    }

    // Types ----------------------------------------------------------------

    private fun saveType() {
        val editor = state.value.typeEditor ?: return
        if (editor.title.isBlank()) {
            setState { copy(error = "A type needs a name") }
            return
        }
        setState { copy(typeEditor = typeEditor?.copy(saving = true)) }
        launch {
            val result = if (editor.typeId == null) {
                repository.createType(editor.title.trim(), editor.color)
            } else {
                val current = state.value.types.firstOrNull { it.id == editor.typeId }
                // System types may only be recoloured; send only what changed.
                repository.updateType(
                    editor.typeId,
                    title = editor.title.trim().takeIf { it != current?.title && current?.systemDefined != true },
                    color = editor.color.takeIf { it != current?.color },
                )
            }
            when (result) {
                is ZillitResult.Success -> {
                    setState { copy(typeEditor = null) }
                    refresh()
                }
                is ZillitResult.Failure -> setState {
                    copy(typeEditor = typeEditor?.copy(saving = false), error = result.error.userMessage)
                }
            }
        }
    }

    /** The editor's text into a wire draft, or null when it does not parse. */
    private fun DiaryEditor.toDraft(): DiaryDraft? {
        val date = DiaryClock.midnightOf(dateText)
        val fullDay = kind == DiaryKind.Note || (startText.isBlank() && endText.isBlank())
        val start = when {
            date == null -> null
            fullDay -> date
            else -> DiaryClock.instantOf(dateText, startText)
        }
        val end = when {
            date == null -> null
            fullDay -> date + DiaryMath.DAY_MS - 1
            else -> DiaryClock.instantOf(dateText, endText)
        }
        val valid = title.isNotBlank() && date != null && start != null && end != null && end >= start
        if (!valid) return null
        return DiaryDraft(
            kind = kind,
            title = title,
            body = body,
            date = checkNotNull(date),
            startDateTime = checkNotNull(start),
            endDateTime = checkNotNull(end),
            fullDay = fullDay,
            location = location,
            color = color,
            scheduleDayId = scheduleDayId,
            noteType = noteType,
            repeatStatus = repeatStatus,
            repeatEndDate = 0,
        )
    }

    private companion object {
        const val YEAR_MS = 365L * DiaryMath.DAY_MS
    }
}
