package com.zillit.desktop.feature.budget

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.budget.domain.BudgetActivity
import com.zillit.desktop.feature.budget.domain.BudgetActivityRow
import com.zillit.desktop.feature.budget.domain.BudgetChatEntry
import com.zillit.desktop.feature.budget.domain.BudgetDepartment
import com.zillit.desktop.feature.budget.domain.BudgetDocument
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMembers
import com.zillit.desktop.feature.budget.domain.BudgetMode
import com.zillit.desktop.feature.budget.domain.BudgetRepository
import com.zillit.desktop.feature.budget.domain.BudgetType
import com.zillit.desktop.feature.budget.domain.BudgetUpload
import com.zillit.desktop.feature.budget.ui.BudgetBadges
import com.zillit.desktop.feature.budget.ui.BudgetContext
import com.zillit.desktop.feature.budget.ui.BudgetHost
import com.zillit.desktop.feature.budget.ui.PickedBudgetFile

/** A repository that remembers every call it was asked, reads and writes alike. */
internal class RecordingBudgetRepository(
    private val all: List<BudgetDocument> = emptyList(),
    private val byDepartment: Map<String, List<BudgetDocument>> = emptyMap(),
    private val chats: List<BudgetChatEntry> = emptyList(),
) : BudgetRepository {
    /** Every call, as `name:arguments`, in order. */
    val calls = mutableListOf<String>()

    override suspend fun documents(): ZillitResult<List<BudgetDocument>> {
        calls += "list"
        return ZillitResult.Success(all)
    }

    override suspend fun documentsOf(type: BudgetType, departmentId: String): ZillitResult<List<BudgetDocument>> {
        calls += "documentsOf:${type.wire}/$departmentId"
        return ZillitResult.Success(byDepartment[departmentId].orEmpty())
    }

    override suspend fun post(upload: BudgetUpload): ZillitResult<BudgetDocument> {
        calls += "post"
        return ZillitResult.Success(BudgetDocument(id = "posted", type = upload.type))
    }

    override suspend fun delete(documentIds: List<String>): ZillitResult<Unit> {
        calls += "delete"
        return ZillitResult.Success(Unit)
    }

    override suspend fun members(departmentId: String): ZillitResult<BudgetMembers> {
        calls += "members:$departmentId"
        return ZillitResult.Success(BudgetMembers())
    }

    override suspend fun markVisited(documentId: String): ZillitResult<Unit> {
        calls += "markVisited:$documentId"
        return ZillitResult.Success(Unit)
    }

    override suspend fun record(documentId: String, activity: BudgetActivity): ZillitResult<BudgetDocument?> {
        calls += "record:${activity.wire}/$documentId"
        return ZillitResult.Success(null)
    }

    override suspend fun activity(documentId: String, activity: BudgetActivity): ZillitResult<List<BudgetActivityRow>> {
        calls += "activity"
        return ZillitResult.Success(emptyList())
    }

    override suspend fun chats(
        mode: BudgetMode,
        departmentId: String,
        documentId: String,
    ): ZillitResult<List<BudgetChatEntry>> {
        calls += "chats:${mode.tool}/$departmentId/$documentId"
        return ZillitResult.Success(chats)
    }

    override suspend fun createRoom(
        mode: BudgetMode,
        departmentId: String,
        documentId: String,
        name: String,
        memberIds: List<String>,
    ): ZillitResult<BudgetChatEntry.Group> {
        calls += "createRoom"
        return ZillitResult.Success(BudgetChatEntry.Group(roomId = "r", name = name))
    }
}

/** A host whose picker is counted, so a test can prove it was never opened. */
internal class CountingHost : BudgetHost {
    var picks = 0

    override suspend fun pickPdf(): PickedBudgetFile? {
        picks++
        return PickedBudgetFile("x.pdf", ByteArray(1))
    }

    override fun context() = BudgetContext(
        userId = "me",
        departmentId = "dept-me",
        departmentName = "Camera",
        departments = listOf(BudgetDepartment("cam", "Camera"), BudgetDepartment("art", "Art")),
    )
}

/** Badges that note every read, so a test can prove there were none. */
internal class CountingBadges : BudgetBadges {
    val reads = mutableListOf<String>()

    override fun markDocumentRead(mode: BudgetMode, documentId: String, departmentId: String) {
        reads += "document:$documentId"
    }

    override fun markChatRead(mode: BudgetMode, documentId: String, key: String) {
        reads += "chat:$key"
    }
}

internal fun cabinetVersion(
    id: String,
    type: BudgetType = BudgetType.Main,
    department: String = "",
    updated: Long = 0,
) = BudgetDocument(
    id = id,
    type = type,
    departmentId = department,
    title = "Budget $id",
    file = BudgetFile(media = "k/$id.pdf", name = "$id.pdf"),
    uploadedById = "u1",
    createdMillis = updated,
    updatedMillis = updated,
)
