package com.zillit.desktop.feature.budget.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.budget.domain.BudgetFile
import com.zillit.desktop.feature.budget.domain.BudgetMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** A file picked from disk, before it is stored. */
data class PickedBudgetFile(
    val name: String,
    val bytes: ByteArray,
)

/**
 * What the host lends the tool: the OS picker, the production's storage,
 * the crew list, and the admins' inbox for an oversize upload.
 *
 * Defaults do nothing, which is what a test and a host with no storage want.
 */
interface BudgetHost {
    /** Opens the OS picker for one PDF; null when the reader cancels. */
    suspend fun pickPdf(): PickedBudgetFile? = null

    /** Puts the bytes in the production's storage and answers the descriptor the service wants. */
    suspend fun store(name: String, bytes: ByteArray): ZillitResult<BudgetFile> =
        ZillitResult.Failure(com.zillit.desktop.core.common.ZillitError.Unknown("storage is not wired"))

    /** The reader and the production, as of now — resolved late because both come with the open production. */
    fun context(): BudgetContext = BudgetContext()

    /** Everyone on the production, for names and designations beside ids. */
    fun crew(): List<BudgetPerson> = emptyList()

    /**
     * The web's `distributeCncMessage` after an upload: a document past 25 MB
     * is announced to every admin in a private message. Failures are the
     * host's to log; the upload has already succeeded.
     */
    suspend fun announceOversize(fileName: String, sizeBytes: Long) = Unit

    companion object {
        val None: BudgetHost = object : BudgetHost {}
    }
}

/**
 * The unread the budget wears, and the reads that clear it.
 *
 * Separated from [BudgetHost] because it is the one seam with a lifetime:
 * a flow the screen collects for as long as it is open.
 */
interface BudgetBadges {
    fun unread(mode: BudgetMode): Flow<BudgetUnread> = flowOf(BudgetUnread.None)

    /**
     * Opening a version reads its `budget_has_created` row — the web's
     * `notification:read` with `segment=<label>` and `reference_id=<doc>`
     * (`CommonBudget.jsx:readMainBudgetBadges`).
     */
    fun markDocumentRead(mode: BudgetMode, documentId: String, departmentId: String) = Unit

    /** Opening a thread reads its lines; the chat itself sends the read on the wire. */
    fun markChatRead(mode: BudgetMode, documentId: String, key: String) = Unit

    companion object {
        val None: BudgetBadges = object : BudgetBadges {}
    }
}
