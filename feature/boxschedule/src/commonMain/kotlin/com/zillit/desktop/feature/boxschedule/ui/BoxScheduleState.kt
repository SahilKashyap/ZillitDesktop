package com.zillit.desktop.feature.boxschedule.ui

import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleViewer
import com.zillit.desktop.feature.boxschedule.domain.DateConflict
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryKind
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleDayRow
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType

/** A block being created or edited. */
data class BlockEditor(
    val blockId: String? = null,
    val typeId: String = "",
    val title: String = "",
    /** `YYYY-MM-DD` typed dates; the VM turns them into local midnights. */
    val startText: String = "",
    val endText: String = "",
    val saving: Boolean = false,
    val conflicts: List<DateConflict> = emptyList(),
)

/** An event or note being created or edited. */
data class DiaryEditor(
    val eventId: String? = null,
    val kind: DiaryKind = DiaryKind.Event,
    val title: String = "",
    val body: String = "",
    /** `YYYY-MM-DD`. */
    val dateText: String = "",
    /** `HH:mm`; blank pair means full day. */
    val startText: String = "",
    val endText: String = "",
    val location: String = "",
    val color: String = "#3498DB",
    val scheduleDayId: String = "",
    val noteType: String = "general",
    val repeatStatus: String = "none",
    val saving: Boolean = false,
    /** For a recurring occurrence's edit/delete: which instant it is. */
    val occurrenceDate: Long? = null,
    val isRecurring: Boolean = false,
)

/** A new type being added inline. */
data class TypeEditor(
    val typeId: String? = null,
    val title: String = "",
    val color: String = "#3498DB",
    val saving: Boolean = false,
)

data class BoxScheduleUiState(
    val viewer: BoxScheduleViewer = BoxScheduleViewer(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val types: List<ScheduleType> = emptyList(),
    val blocks: List<ScheduleBlock> = emptyList(),
    val rows: List<ScheduleDayRow> = emptyList(),
    val events: List<DiaryEvent> = emptyList(),
    val noteTypes: List<NoteType> = emptyList(),
    val blockEditor: BlockEditor? = null,
    val diaryEditor: DiaryEditor? = null,
    val typeEditor: TypeEditor? = null,
    val manageTypes: Boolean = false,
) {
    /** Events and notes for one date, standalone or linked. */
    fun eventsOn(date: Long): List<DiaryEvent> = events.filter { it.date == date }

    /** Events not pinned to any date row shown (their date has no block). */
    val orphanEvents: List<DiaryEvent>
        get() {
            val blockDates = rows.map { it.date }.toSet()
            return events
                .filter { it.date !in blockDates }
                .sortedBy { it.startDateTime.takeIf { s -> s > 0 } ?: it.date }
        }
}

sealed interface BoxScheduleEvent {
    data object Refresh : BoxScheduleEvent

    // Blocks
    data object NewBlock : BoxScheduleEvent
    data class EditBlock(val blockId: String) : BoxScheduleEvent
    data class BlockChanged(
        val typeId: String? = null,
        val title: String? = null,
        val startText: String? = null,
        val endText: String? = null,
    ) : BoxScheduleEvent
    data object SaveBlock : BoxScheduleEvent
    data class ResolveConflict(val action: String) : BoxScheduleEvent
    data object CloseBlock : BoxScheduleEvent
    data class DeleteBlockDate(val blockId: String, val date: Long) : BoxScheduleEvent
    data class DeleteBlock(val blockId: String) : BoxScheduleEvent

    // Events / notes
    data class NewDiary(val kind: DiaryKind, val date: Long?, val scheduleDayId: String = "") : BoxScheduleEvent
    data class EditDiary(val listKey: String) : BoxScheduleEvent
    data class DiaryChanged(
        val title: String? = null,
        val body: String? = null,
        val dateText: String? = null,
        val startText: String? = null,
        val endText: String? = null,
        val location: String? = null,
        val color: String? = null,
        val noteType: String? = null,
        val repeatStatus: String? = null,
    ) : BoxScheduleEvent
    data object SaveDiary : BoxScheduleEvent
    data object CloseDiary : BoxScheduleEvent
    data class DeleteDiary(val listKey: String) : BoxScheduleEvent

    // Types
    data object OpenTypes : BoxScheduleEvent
    data object CloseTypes : BoxScheduleEvent
    data object NewType : BoxScheduleEvent
    data class EditType(val typeId: String) : BoxScheduleEvent
    data class TypeChanged(val title: String? = null, val color: String? = null) : BoxScheduleEvent
    data object SaveType : BoxScheduleEvent
    data object CloseTypeEditor : BoxScheduleEvent
    data class DeleteType(val typeId: String) : BoxScheduleEvent

    data object DismissError : BoxScheduleEvent
}

sealed interface BoxScheduleEffect {
    data class Notice(val message: String) : BoxScheduleEffect
}
