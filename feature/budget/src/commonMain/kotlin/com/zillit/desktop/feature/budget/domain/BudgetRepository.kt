package com.zillit.desktop.feature.budget.domain

import com.zillit.desktop.core.common.ZillitResult

/** The budget service, as the screen needs it. */
interface BudgetRepository {

    /** Every budget document this person may see, both types in one answer (`GET budget/list`). */
    suspend fun documents(): ZillitResult<List<BudgetDocument>>

    /**
     * Every version of one budget: the production's when [type] is
     * [BudgetType.Main] — the web still names the reader's own department on
     * that path — or one department's (`GET budget/{type}/{departmentId}`).
     */
    suspend fun documentsOf(type: BudgetType, departmentId: String): ZillitResult<List<BudgetDocument>>

    /** Uploads a new version (`POST budget`). Answers the document the server saved. */
    suspend fun post(upload: BudgetUpload): ZillitResult<BudgetDocument>

    /** Removes documents by id — the wire takes a list even for one. */
    suspend fun delete(documentIds: List<String>): ZillitResult<Unit>

    /** Who can see each budget, split the way the wire splits it (`GET budget-users/{departmentId}`). */
    suspend fun members(departmentId: String): ZillitResult<BudgetMembers>

    /** Notes that this person has opened [documentId] (`PUT budget/update-last-visited/{id}`). */
    suspend fun markVisited(documentId: String): ZillitResult<Unit>

    /**
     * Records a view or a download and answers the document with its file
     * (`GET budget/view-download/{action}/{id}` — the web reads the
     * attachment off this answer before opening it, `CommonBudget.jsx:697`).
     */
    suspend fun record(documentId: String, activity: BudgetActivity): ZillitResult<BudgetDocument?>

    /** Who has viewed or downloaded a document, and how often (`GET budget/view-download/count/{id}`). */
    suspend fun activity(documentId: String, activity: BudgetActivity): ZillitResult<List<BudgetActivityRow>>

    /**
     * The people and rooms discussing one budget — the socket's
     * `budget:recent:list` (`cncEmit.js:482`), which the web asks with the
     * tool, the document and (for a department budget) the department.
     */
    suspend fun chats(mode: BudgetMode, departmentId: String, documentId: String): ZillitResult<List<BudgetChatEntry>>

    /**
     * Opens a room for a budget (`POST chat-room` with `room_tool`,
     * `department_id`, `budget_document_id` — `CommonBudget.jsx:createGroup`).
     * Answers the room's id.
     */
    suspend fun createRoom(
        mode: BudgetMode,
        departmentId: String,
        documentId: String,
        name: String,
        memberIds: List<String>,
    ): ZillitResult<BudgetChatEntry.Group>
}
