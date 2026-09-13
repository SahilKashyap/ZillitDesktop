package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.CoaAccount
import com.zillit.desktop.feature.accounthub.domain.CoaBulk
import com.zillit.desktop.feature.accounthub.domain.CoaBulkRow
import com.zillit.desktop.feature.accounthub.domain.CoaBulkSnapshot
import com.zillit.desktop.feature.accounthub.domain.CoaBulkStatus
import com.zillit.desktop.feature.accounthub.domain.CoaCostType
import com.zillit.desktop.feature.accounthub.domain.CoaLineType
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll

/**
 * The "New COA Entry" grid's autosave — the web's `CoaBulkAddPage`.
 *
 * One independent saver per row. A row is created once it has a code (two
 * seconds after its last edit), then updated on every later edit. A changed code
 * on a saved row is a create under the new code and a retire of the old one,
 * because the code is a natural key with no in-place rename; a changed level is
 * a plain update that carries the level and parent. Removing a saved row
 * retires it — the API has no hard delete.
 */
internal class ChartBulkActions(
    private val vm: AccountHubViewModel,
    /** Done: the grid has closed and the chart should be read again. */
    private val onFinished: () -> Unit,
) {

    /** One row's bookkeeping — the web's `saveRef` entry. Never drawn. */
    private class Book {
        var timer: Job? = null
        var flight: Job? = null
        var redo = false
        var savedCode: String? = null
        var savedLineType: CoaLineType? = null
        var lastSaved: CoaBulkSnapshot? = null
    }

    private val books = mutableMapOf<String, Book>()

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.OpenBulkAdd -> open(event.parent)
            is AccountHubEvent.EditBulkRow -> edit(event.row)
            is AccountHubEvent.AddBulkRows -> add(event.count, event.focus)
            is AccountHubEvent.RemoveBulkRow -> remove(event.localId)
            AccountHubEvent.FinishBulkAdd -> finish()
            else -> return false
        }
        return true
    }

    private fun open(parent: CoaAccount?) {
        if (!vm.mayActAsAccountant()) return
        books.values.forEach { it.timer?.cancel() }
        books.clear()
        // Seeded from the tab: the class decides which tab a saved row shows on,
        // and seeding Expense on Balance Sheet Codes would save every row out of it.
        val costType = vm.setupState.chart.view.defaultCostType
        val first = blank(costType)
        vm.update {
            copy(chart = chart.copy(bulk = BulkAddState(parent = parent, rows = listOf(first), costType = costType)))
        }
    }

    /**
     * Every new row is a Nominal, however the page was opened — the web's fixed
     * default, chosen over "one level below the parent" because it is the level
     * accountants enter by hand. The level stays editable.
     */
    private fun blank(costType: CoaCostType) =
        CoaBulkRow(localId = vm.newLocalId("coa-row"), lineType = CoaLineType.Category, costType = costType)

    private fun add(count: Int, focus: Boolean) {
        val bulk = vm.setupState.chart.bulk ?: return
        val added = List(count.coerceAtLeast(1)) { blank(bulk.costType) }
        vm.update {
            val current = chart.bulk ?: return@update this
            copy(
                chart = chart.copy(
                    bulk = current.copy(
                        rows = current.rows + added,
                        focusRowId = if (focus) added.last().localId else current.focusRowId,
                    ),
                ),
            )
        }
    }

    /**
     * Takes an edit and (re)starts that row's timer.
     *
     * Only the edited row's timer: resetting every row on every keystroke would
     * keep pushing back the saves of rows nobody is touching.
     */
    private fun edit(row: CoaBulkRow) {
        val current = vm.setupState.chart.bulk?.rows?.firstOrNull { it.localId == row.localId } ?: return
        // The server id is the saver's to set; an edit made from a copy drawn
        // before a create answered must not clear it.
        val next = row.copy(serverId = current.serverId)
        if (next == current) return
        replaceRow(next)
        val book = books.getOrPut(row.localId) { Book() }
        book.timer?.cancel()
        book.timer = vm.launchWork {
            delay(CoaBulk.SAVE_DEBOUNCE_MS)
            // Cleared before the flush, so a later edit cancels only a waiting
            // timer and never a save already on the wire.
            book.timer = null
            flush(row.localId)
        }
    }

    /**
     * Sends one row, if anything about it needs sending.
     *
     * Read fresh at fire time: a row can become a duplicate, or be edited
     * again, while its timer waits. A row already on the wire is marked to go
     * again once that save answers, so the last edit is never the lost one.
     */
    private suspend fun flush(localId: String) {
        val book = books.getOrPut(localId) { Book() }
        val bulk = vm.setupState.chart.bulk
        val row = bulk?.rows?.firstOrNull { it.localId == localId }
        if (bulk == null || row == null || !sendable(bulk, row)) return

        val kind = kindOf(row, book)
        if (kind == SaveKind.Unchanged) return
        if (book.flight != null) {
            // A save is already out; this one goes once it answers.
            book.redo = true
            return
        }

        book.flight = currentCoroutineContext()[Job]
        setStatus(localId, CoaBulkStatus.Saving)
        val parentId = bulk.parent?.id
        try {
            when (kind) {
                SaveKind.Create -> create(row, book, parentId)
                SaveKind.Rename -> rename(row, book, parentId)
                SaveKind.Retype -> update(row, book, retype = true, parentId = parentId)
                SaveKind.Update, SaveKind.Unchanged -> update(row, book, retype = false, parentId = parentId)
            }
        } finally {
            book.flight = null
            if (book.redo) {
                book.redo = false
                flush(localId)
            }
        }
    }

    /** What a row's next save is, against what the server was last sent. */
    private enum class SaveKind { Create, Rename, Retype, Update, Unchanged }

    private fun kindOf(row: CoaBulkRow, book: Book): SaveKind = when {
        row.serverId == null -> SaveKind.Create
        // The code is a natural key with no in-place rename.
        book.savedCode != null && book.savedCode != row.normalisedCode -> SaveKind.Rename
        book.savedLineType != null && book.savedLineType != row.lineType -> SaveKind.Retype
        CoaBulk.snapshot(row) == book.lastSaved -> SaveKind.Unchanged
        else -> SaveKind.Update
    }

    private suspend fun create(row: CoaBulkRow, book: Book, parentId: String?) {
        when (val created = vm.repo.createAccount(CoaBulk.newAccount(row, parentId))) {
            is ZillitResult.Failure -> failed(row.localId, created.error)
            is ZillitResult.Success -> {
                val serverId = created.data.id.takeIf { it.isNotBlank() }
                // Removed from the grid while the create was out: retire what it
                // made rather than leave a live code nothing on screen can reach.
                if (!rowExists(row.localId)) {
                    serverId?.let { vm.repo.deactivateAccount(it) }
                    return
                }
                saved(row, book, serverId)
            }
        }
    }

    /**
     * A new code on a saved row: create it first, then retire the old one.
     *
     * In that order so a failure never leaves the entry with no live code — at
     * worst it briefly has two. A refusal to retire the old one is reported
     * without undoing the new.
     */
    private suspend fun rename(row: CoaBulkRow, book: Book, parentId: String?) {
        val oldId = row.serverId ?: return
        when (val created = vm.repo.createAccount(CoaBulk.newAccount(row, parentId))) {
            is ZillitResult.Failure -> failed(row.localId, created.error)
            is ZillitResult.Success -> {
                val newId = created.data.id.takeIf { it.isNotBlank() }
                if (!rowExists(row.localId)) {
                    newId?.let { vm.repo.deactivateAccount(it) }
                    return
                }
                (vm.repo.deactivateAccount(oldId) as? ZillitResult.Failure)?.let { vm.report(it.error) }
                saved(row, book, newId)
            }
        }
    }

    private suspend fun update(row: CoaBulkRow, book: Book, retype: Boolean, parentId: String?) {
        val serverId = row.serverId ?: return
        when (val result = vm.repo.updateAccount(serverId, CoaBulk.patch(row, retype, parentId))) {
            is ZillitResult.Failure -> failed(row.localId, result.error)
            is ZillitResult.Success -> {
                book.lastSaved = CoaBulk.snapshot(row)
                book.savedLineType = row.lineType
                setStatus(row.localId, CoaBulkStatus.Saved)
            }
        }
    }

    /** A create or rename answered: remember what the server now holds. */
    private fun saved(row: CoaBulkRow, book: Book, serverId: String?) {
        book.savedCode = row.normalisedCode
        book.savedLineType = row.lineType
        book.lastSaved = CoaBulk.snapshot(row)
        vm.update {
            val current = chart.bulk ?: return@update this
            copy(
                chart = chart.copy(
                    bulk = current.copy(
                        rows = current.rows.map { if (it.localId == row.localId) it.copy(serverId = serverId) else it },
                        createdCodes = current.createdCodes + row.normalisedCode,
                        status = current.status + (row.localId to CoaBulkStatus.Saved),
                        errors = current.errors - row.localId,
                    ),
                ),
            )
        }
    }

    private fun failed(localId: String, error: ZillitError) {
        vm.update {
            val current = chart.bulk ?: return@update this
            copy(
                chart = chart.copy(
                    bulk = current.copy(
                        status = current.status + (localId to CoaBulkStatus.Error),
                        errors = current.errors + (localId to error.localised()),
                    ),
                ),
            )
        }
        vm.report(error)
    }

    /** Removing a saved row retires it; an unsaved one just goes. */
    private fun remove(localId: String) {
        val row = vm.setupState.chart.bulk?.rows?.firstOrNull { it.localId == localId } ?: return
        books.remove(localId)?.timer?.cancel()
        vm.update {
            val current = chart.bulk ?: return@update this
            copy(
                chart = chart.copy(
                    bulk = current.copy(
                        rows = current.rows.filterNot { it.localId == localId },
                        status = current.status - localId,
                        errors = current.errors - localId,
                    ),
                ),
            )
        }
        val serverId = row.serverId ?: return
        vm.runResult({ vm.repo.deactivateAccount(serverId) }, { }, vm::report)
    }

    /**
     * Done — and the back arrow. Everything still waiting on its timer goes now,
     * every save already out is waited for, then the grid closes and the chart
     * is read again, so a fast click cannot drop an edit or miss a code.
     */
    private fun finish() {
        val bulk = vm.setupState.chart.bulk ?: return
        if (bulk.finishing) return
        vm.update { copy(chart = chart.copy(bulk = chart.bulk?.copy(finishing = true))) }
        books.values.forEach {
            it.timer?.cancel()
            it.timer = null
        }
        vm.launchWork {
            val current = vm.setupState.chart.bulk ?: return@launchWork
            val duplicates = current.duplicateIds(vm.setupState.chart.accounts)
            current.rows
                .filter { CoaBulk.isReady(it) && it.localId !in duplicates }
                .map { row -> async { flush(row.localId) } }
                .awaitAll()
            books.values.mapNotNull { it.flight }.joinAll()
            books.clear()
            vm.update { copy(chart = chart.copy(bulk = null)) }
            onFinished()
        }
    }

    /** A row goes once it has a code and that code collides with nothing. */
    private fun sendable(bulk: BulkAddState, row: CoaBulkRow): Boolean =
        CoaBulk.isReady(row) && row.localId !in bulk.duplicateIds(vm.setupState.chart.accounts)

    private fun rowExists(localId: String): Boolean =
        vm.setupState.chart.bulk?.rows?.any { it.localId == localId } == true

    private fun replaceRow(row: CoaBulkRow) = vm.update {
        val current = chart.bulk ?: return@update this
        val rows = current.rows.map { if (it.localId == row.localId) row else it }
        copy(chart = chart.copy(bulk = current.copy(rows = rows)))
    }

    private fun setStatus(localId: String, status: CoaBulkStatus) = vm.update {
        val current = chart.bulk ?: return@update this
        copy(chart = chart.copy(bulk = current.copy(status = current.status + (localId to status))))
    }
}
