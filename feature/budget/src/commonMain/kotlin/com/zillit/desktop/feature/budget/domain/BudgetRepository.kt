package com.zillit.desktop.feature.budget.domain

import com.zillit.desktop.core.common.ZillitResult

/** The budget service, as the screen needs it. */
interface BudgetRepository {

    /** Every budget document this person may see, both types in one answer. */
    suspend fun documents(): ZillitResult<List<BudgetDocument>>

    /** One department's budget, or the main budget when [type] is [BudgetType.Main]. */
    suspend fun documentsOf(type: BudgetType, departmentId: String): ZillitResult<List<BudgetDocument>>

    /** Uploads a file as the budget for [type] (and [departmentId] when it is a department's). */
    suspend fun post(type: BudgetType, departmentId: String, file: BudgetFile): ZillitResult<BudgetDocument>

    /** Removes documents by id — the wire takes a list even for one. */
    suspend fun delete(documentIds: List<String>): ZillitResult<Unit>

    /** Who can see each budget, split the way the wire splits it. */
    suspend fun members(departmentId: String): ZillitResult<BudgetMembers>

    /** Notes that this person has opened [documentId] — drives the unread dot. */
    suspend fun markVisited(documentId: String): ZillitResult<Unit>

    /** How many people have viewed or downloaded a document. */
    suspend fun activityCount(documentId: String, action: BudgetActivity): ZillitResult<Int>
}

/** The two audiences, as `/budget-users/{id}` returns them. */
data class BudgetMembers(
    val main: List<BudgetMember> = emptyList(),
    val department: List<BudgetMember> = emptyList(),
)

/** What the counts endpoint counts. */
enum class BudgetActivity(val wire: String) {
    View("view"),
    Download("download"),
}
