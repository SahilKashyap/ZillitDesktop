package com.zillit.desktop.feature.documentdistribution.ui

import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.documentdistribution.domain.Csv
import com.zillit.desktop.feature.documentdistribution.domain.Distribution
import com.zillit.desktop.feature.documentdistribution.domain.DistributionSender
import com.zillit.desktop.feature.documentdistribution.domain.HISTORY_PAGE_LIMIT
import com.zillit.desktop.feature.documentdistribution.domain.OpenState
import com.zillit.desktop.feature.documentdistribution.domain.RecipientStatus
import com.zillit.desktop.feature.documentdistribution.domain.fileNameStem

/**
 * History: the paged list of sends, the "Sent by" filter, and one send's
 * detail with its per-recipient delivery.
 */
@Suppress("TooManyFunctions") // One handler per user action.
internal class HistorySection(private val vm: VmScope, private val library: LibrarySection) {

    private val senders = HistorySenderSource(vm.repository) { block -> vm.run(block) }

    /** A production switch starts the sender question over. */
    fun reset() = senders.reset()

    /**
     * Newest first, sorted here rather than trusted from the server.
     *
     * `/distributions` does not reliably return in date order — a send made
     * minutes ago came back below rows from a fortnight earlier (verified live
     * 2026-08-11). Rows with no timestamp sort last.
     */
    fun load() {
        val state = vm.state
        val ids = state.historySenderIds
        vm.update { copy(loading = true, error = null) }
        vm.run {
            when (val page = vm.repository.history(page = 0, search = state.historySearch, senderIds = ids)) {
                is ZillitResult.Success -> vm.update {
                    val kept = if (ids.isEmpty()) page.data.rows else page.data.rows.filter { it.senderId in ids }
                    copy(
                        loading = false,
                        history = kept.sortedByDescending { it.sentAt ?: Long.MIN_VALUE },
                        historyTotal = page.data.total,
                        historySenders = mergedSenders(historySenders, page.data.rows),
                    )
                }
                is ZillitResult.Failure -> vm.update { copy(loading = false, error = page.error.userMessage) }
            }
        }
        senders.askOnce { fetched -> vm.update { copy(historySenders = mergedSenders(fetched, history)) } }
    }

    fun loadMore() {
        val state = vm.state
        if (state.historyLoadingMore || state.loading || !state.historyHasMore) return
        vm.update { copy(historyLoadingMore = true) }
        vm.run {
            val next = state.history.size / HISTORY_PAGE
            val page = vm.repository.history(
                page = next,
                search = state.historySearch,
                senderIds = state.historySenderIds,
            )
            vm.update { copy(historyLoadingMore = false) }
            when (page) {
                is ZillitResult.Success -> vm.update {
                    // Dedupe by id: a send between two fetches shifts the
                    // offsets, so a page can repeat a row already held.
                    val seen = history.map { it.id }.toSet()
                    val fresh = page.data.rows.filter { it.id !in seen }
                    copy(
                        history = history + fresh,
                        // An empty page ends the listing even if `total` disagrees.
                        historyTotal = if (fresh.isEmpty()) history.size else page.data.total,
                    )
                }
                is ZillitResult.Failure -> vm.report(page.error)
            }
        }
    }

    fun toggleSender(id: String) {
        vm.update { copy(historySenderIds = historySenderIds.toggled(id)) }
        load()
    }

    fun clearSenders() {
        vm.update { copy(historySenderIds = emptySet(), historySenderQuery = "") }
        load()
    }

    // -- detail --------------------------------------------------------------

    /** Opens one send and refreshes its open status from the mail service. */
    fun expand(distributionId: String?) {
        if (distributionId == null) {
            vm.update { copy(expandedDistributionId = null, historyDetail = null) }
            return
        }
        val known = vm.state.history.firstOrNull { it.id == distributionId }
        vm.update {
            copy(
                expandedDistributionId = distributionId,
                historyDetail = HistoryDetailState(id = distributionId, distribution = known, loading = true),
            )
        }
        refreshDetail()
    }

    fun refreshDetail() {
        val id = vm.state.historyDetail?.id ?: return
        vm.run {
            val full = when (val fetched = vm.repository.distribution(id)) {
                is ZillitResult.Success -> fetched.data
                is ZillitResult.Failure -> {
                    val fallback = vm.state.historyDetail?.distribution
                    if (fallback == null) vm.report(fetched.error)
                    fallback
                }
            }
            val enriched = full?.let { enrichOpenStatus(it) }
            vm.update {
                if (historyDetail?.id != id) this
                else copy(
                    historyDetail = historyDetail.copy(distribution = enriched, loading = false),
                    history = if (enriched == null) history else history.map { if (it.id == id) enriched else it },
                )
            }
        }
    }

    /**
     * Asks the email service whether the copies have been opened. Only for
     * the open row: one call per send on every page load, for a figure nobody
     * is looking at, is not worth it.
     */
    private suspend fun enrichOpenStatus(record: Distribution): Distribution {
        val ids = record.recipients.mapNotNull { it.uniqueId }.distinct()
        if (ids.isEmpty()) return record
        val statuses = (vm.repository.openStatus(ids) as? ZillitResult.Success)?.data ?: return record
        return record.copy(
            recipients = record.recipients.map { delivery ->
                val fresh = delivery.uniqueId?.let(statuses::get) ?: return@map delivery
                // The open-status API is authoritative: a found-but-unopened
                // record clears a stale "opened" back to delivered.
                val status = when {
                    fresh.state == OpenState.Opened -> RecipientStatus.Opened
                    fresh.state == OpenState.NotOpened && delivery.status in REVERTIBLE -> RecipientStatus.Accepted
                    else -> delivery.status
                }
                delivery.copy(
                    state = fresh.state,
                    status = status,
                    openedAt = if (fresh.state == OpenState.Opened) fresh.openedAt ?: delivery.openedAt else null,
                    openCount = if (fresh.state == OpenState.Opened) fresh.openCount else 0,
                )
            },
        )
    }

    fun openSaveAsList(open: Boolean) = vm.update {
        val detail = historyDetail ?: return@update this
        val subject = detail.distribution?.subject
        copy(
            historyDetail = detail.copy(
                saveListName = if (!open) null else subject?.let { "$it — recipients" } ?: "New distribution list",
            ),
        )
    }

    fun editSaveListName(text: String) = vm.update {
        copy(historyDetail = historyDetail?.copy(saveListName = text))
    }

    fun confirmSaveAsList() {
        val detail = vm.state.historyDetail ?: return
        val name = detail.saveListName?.trim().orEmpty()
        val recipients = detail.distribution?.uniqueRecipients.orEmpty()
        if (name.isEmpty()) return vm.fail("List name is required")
        if (recipients.isEmpty()) return vm.fail("No recipients to save")
        if (vm.refusesWrite()) return
        vm.update { copy(historyDetail = historyDetail?.copy(savingList = true)) }
        vm.run {
            when (val result = vm.repository.createList(name, recipients)) {
                is ZillitResult.Success -> {
                    vm.update {
                        copy(
                            historyDetail = historyDetail?.copy(saveListName = null, savingList = false),
                            lists = lists + result.data,
                        )
                    }
                    vm.notice("Distribution list created")
                }
                is ZillitResult.Failure -> {
                    vm.update { copy(historyDetail = historyDetail?.copy(savingList = false)) }
                    vm.report(result.error)
                }
            }
        }
    }

    /** The To / Cc / Bcc rows with delivery, to Downloads as CSV. */
    fun exportRecipientsCsv() {
        val distribution = vm.state.historyDetail?.distribution ?: return
        if (vm.refusesDownload()) return
        val csv = Csv.deliveries(distribution) { EpochDate.dateTime(it) }
        val stem = fileNameStem(distribution.subject, "recipients").take(SUBJECT_STEM_LENGTH)
        vm.run { library.saveAndOpen("${stem}_recipients.csv", csv.encodeToByteArray()) }
    }

    private companion object {
        /** Matches the server's own default and cap behaviour. */
        const val HISTORY_PAGE = HISTORY_PAGE_LIMIT
        const val SUBJECT_STEM_LENGTH = 60
        val REVERTIBLE = setOf(RecipientStatus.Opened, RecipientStatus.Accepted, RecipientStatus.Pending)
    }
}

/** The menu's senders: the endpoint's list, plus anyone a loaded row names that it did not, by id. */
internal fun mergedSenders(known: List<DistributionSender>, rows: List<Distribution>): List<DistributionSender> {
    val byId = linkedMapOf<String, DistributionSender>()
    known.forEach { if (it.id.isNotBlank()) byId[it.id] = it }
    rows.forEach { row ->
        if (row.senderId.isNotBlank() && row.senderId !in byId) {
            byId[row.senderId] = DistributionSender(row.senderId, row.sentByName)
        }
    }
    return byId.values.sortedBy { it.name.lowercase() }
}
