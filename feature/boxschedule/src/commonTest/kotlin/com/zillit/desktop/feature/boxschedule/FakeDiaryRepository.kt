package com.zillit.desktop.feature.boxschedule

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.boxschedule.domain.BlockDraft
import com.zillit.desktop.feature.boxschedule.domain.BlockWrite
import com.zillit.desktop.feature.boxschedule.domain.BoxScheduleRepository
import com.zillit.desktop.feature.boxschedule.domain.ConflictAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryDraft
import com.zillit.desktop.feature.boxschedule.domain.DiaryEvent
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdf
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfAction
import com.zillit.desktop.feature.boxschedule.domain.DiaryPdfOptions
import com.zillit.desktop.feature.boxschedule.domain.DiaryRevision
import com.zillit.desktop.feature.boxschedule.domain.HistoryEntry
import com.zillit.desktop.feature.boxschedule.domain.NoteType
import com.zillit.desktop.feature.boxschedule.domain.RecurrenceScope
import com.zillit.desktop.feature.boxschedule.domain.ScheduleBlock
import com.zillit.desktop.feature.boxschedule.domain.ScheduleType
import com.zillit.desktop.feature.boxschedule.domain.UserPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** An in-memory diary that answers every read with what it was given and every write with success. */
internal open class FakeDiaryRepository(
    override val refreshes: Flow<Unit> = emptyFlow(),
    var types: List<ScheduleType> = emptyList(),
    var blocks: List<ScheduleBlock> = emptyList(),
    var events: List<DiaryEvent> = emptyList(),
) : BoxScheduleRepository {
    var blockLoads = 0
    val writes = mutableListOf<String>()

    override suspend fun types() = ZillitResult.Success(types)
    override suspend fun createType(title: String, color: String): ZillitResult<String?> {
        writes += "createType:$title"
        return ZillitResult.Success("new-type")
    }
    override suspend fun updateType(id: String, title: String?, color: String?): ZillitResult<Unit> {
        writes += "updateType:$id"
        return ZillitResult.Success(Unit)
    }
    override suspend fun deleteType(id: String): ZillitResult<Unit> {
        writes += "deleteType:$id"
        return ZillitResult.Success(Unit)
    }
    override suspend fun blocks(): ZillitResult<List<ScheduleBlock>> {
        blockLoads++
        return ZillitResult.Success(blocks)
    }
    override suspend fun createBlock(draft: BlockDraft, resolve: ConflictAction?): ZillitResult<BlockWrite> {
        writes += "createBlock"
        return ZillitResult.Success(BlockWrite.Saved)
    }
    override suspend fun updateBlock(
        id: String,
        draft: BlockDraft,
        resolve: ConflictAction?,
    ): ZillitResult<BlockWrite> {
        writes += "updateBlock:$id"
        return ZillitResult.Success(BlockWrite.Saved)
    }
    override suspend fun renameBlock(id: String, title: String): ZillitResult<Unit> {
        writes += "renameBlock:$id"
        return ZillitResult.Success(Unit)
    }
    override suspend fun changeSingleDay(
        id: String,
        date: Long,
        typeId: String,
        action: ConflictAction,
    ): ZillitResult<Unit> {
        writes += "changeSingleDay:$id"
        return ZillitResult.Success(Unit)
    }
    override suspend fun deleteBlock(id: String): ZillitResult<String?> {
        writes += "deleteBlock:$id"
        return ZillitResult.Success(null)
    }
    override suspend fun removeDates(entries: Map<String, List<Long>>): ZillitResult<String?> {
        writes += "removeDates:${entries.keys.sorted()}"
        return ZillitResult.Success(null)
    }
    override suspend fun duplicateBlock(
        sourceId: String,
        newStartDate: Long,
    ): ZillitResult<Unit> = ZillitResult.Success(Unit)
    override suspend fun events(scheduleDayId: String?) = ZillitResult.Success(events)
    override suspend fun noteTypes() = ZillitResult.Success(NoteType.DEFAULTS)
    override suspend fun pdf(options: DiaryPdfOptions, action: DiaryPdfAction, watermark: String) =
        ZillitResult.Success(DiaryPdf(media = "box/diary.pdf", name = "Box Schedule.pdf"))
    override suspend fun createEvent(draft: DiaryDraft): ZillitResult<Unit> {
        writes += "createEvent"
        return ZillitResult.Success(Unit)
    }
    override suspend fun updateEvent(
        id: String,
        draft: DiaryDraft,
        scope: RecurrenceScope,
        occurrenceDate: Long?,
    ): ZillitResult<Unit> {
        writes += "updateEvent:$id"
        return ZillitResult.Success(Unit)
    }
    override suspend fun deleteEvent(id: String, scope: RecurrenceScope?, occurrenceDate: Long?): ZillitResult<Unit> {
        writes += "deleteEvent:$id"
        return ZillitResult.Success(Unit)
    }
    override suspend fun history() = ZillitResult.Success(emptyList<HistoryEntry>())
    override suspend fun revisions() = ZillitResult.Success(emptyList<DiaryRevision>())
    override suspend fun presets() = ZillitResult.Success(emptyList<UserPreset>())
    override suspend fun savePreset(presetId: String?, name: String, userIds: List<String>): ZillitResult<Unit> {
        writes += "savePreset:$name"
        return ZillitResult.Success(Unit)
    }
    override suspend fun deletePreset(presetId: String): ZillitResult<Unit> {
        writes += "deletePreset:$presetId"
        return ZillitResult.Success(Unit)
    }
    override suspend fun shareLink() = ZillitResult.Success("https://web.test/share/abc")
}
