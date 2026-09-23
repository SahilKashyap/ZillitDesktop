package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayPeriod
import com.zillit.desktop.feature.payroll.domain.PayrollTimecard
import com.zillit.desktop.feature.payroll.domain.PostOutcome
import kotlinx.coroutines.Job

/**
 * Payroll History's behaviour — the web's `AccountantPayrollModule`.
 *
 * ## The queue is the week's paid timecards
 *
 * `/weekly/{ws}/paid` returns the paid rows, and the posted ones so the
 * accountant can see what already went. Only a paid row can be ticked or
 * posted; the week opens on the last completed period, and the navigator
 * walks back as far as it likes but never past the current week.
 */
internal class HistoryActions(private val vm: PayrollViewModel) {

    private val state: HistoryState get() = vm.ui.history
    private var loadJob: Job? = null
    private var detailJob: Job? = null

    @Suppress("CyclomaticComplexMethod") // One branch per event.
    fun onEvent(event: HistoryEvent) {
        when (event) {
            is HistoryEvent.ShiftWeek -> shiftWeek(event.steps)
            HistoryEvent.CurrentWeek -> openWeek(vm.ui.currentWeek)
            HistoryEvent.Refresh -> reload(silent = false)
            is HistoryEvent.Search -> edit { copy(search = event.query) }
            is HistoryEvent.Select -> select(event.timecardId)
            is HistoryEvent.ToggleCheck -> toggleCheck(event.timecardId)
            HistoryEvent.ToggleAllVisible -> toggleAllVisible()
            HistoryEvent.ClearChecks -> edit { copy(checked = emptySet()) }
            is HistoryEvent.Tab -> edit { copy(tab = event.tab) }
            HistoryEvent.DownloadPayslip -> downloadPayslip()
            HistoryEvent.OpenPost -> openPost()
            is HistoryEvent.EditPost -> editPost(event)
            HistoryEvent.ConfirmPost -> confirmPost()
            HistoryEvent.DismissPost -> edit { copy(post = null) }
        }
    }

    /** Opens on the last completed period — the web's default — once the period boundary is known. */
    fun ensureLoaded() {
        if (state.weekStarting != null) return
        val ui = vm.ui
        openWeek(PayPeriod.shift(ui.currentWeek, -1, ui.metadata.payPeriodStartDay))
    }

    fun reload(silent: Boolean) {
        state.weekStarting?.let { load(it, silent) }
    }

    /** No future weeks: payroll cannot process timecards that do not exist yet. */
    private fun shiftWeek(steps: Int) {
        val week = state.weekStarting ?: return
        if (steps > 0 && week >= vm.ui.currentWeek) return
        openWeek(PayPeriod.shift(week, steps, vm.ui.metadata.payPeriodStartDay))
    }

    private fun openWeek(week: Long) {
        edit { copy(weekStarting = week, rows = emptyList(), checked = emptySet(), post = null) }
        load(week, silent = false)
    }

    /**
     * Reads the week. The queue is sorted by department then name, so the row
     * selected by default is the first one the list draws; the open row stays
     * open across a refresh while it is still in the queue.
     */
    private fun load(week: Long, silent: Boolean) {
        loadJob?.cancel()
        if (!silent) edit { copy(loading = true, error = null) }
        loadJob = vm.launchWork {
            when (val result = vm.repository.paidCrew(week)) {
                is ZillitResult.Success -> {
                    val ui = vm.ui
                    val rows = result.data.sortedWith(
                        compareBy<PayrollTimecard>({ ui.departmentOf(it.userId) }, { ui.nameOf(it.userId) }),
                    )
                    val keep = state.selectedId?.takeIf { id -> rows.any { it.id == id } }
                    val alive = rows.map { it.id }.toSet()
                    edit {
                        copy(
                            loading = false,
                            error = null,
                            rows = rows,
                            selectedId = keep ?: rows.firstOrNull()?.id,
                            checked = checked.filter { it in alive }.toSet(),
                        )
                    }
                    val open = keep ?: rows.firstOrNull()?.id
                    if (open != null && (open != keep || !silent)) loadDetail(open) else if (open == null) clearDetail()
                }

                is ZillitResult.Failure -> edit {
                    if (silent) copy(loading = false) else copy(
                        loading = false,
                        error = result.error,
                        rows = emptyList(),
                    )
                }
            }
        }
    }

    private fun select(id: String) {
        if (state.rows.none { it.id == id }) return
        edit { copy(selectedId = id) }
        loadDetail(id)
    }

    /**
     * The full timecard, then the crew member's deal: the slim queue carries
     * no days, history or claims, and the deal names the lines the week has
     * not coded itself. They fail apart — a crew member with no active deal
     * still has a breakdown.
     */
    private fun loadDetail(id: String) {
        detailJob?.cancel()
        edit { copy(detailLoading = true, detail = null, deal = null) }
        detailJob = vm.launchWork {
            val timecard = vm.repository.timecard(id).getOrNull()
            val deal = timecard?.userId?.let { vm.repository.settings.activeDealCoding(it).getOrNull() }
            if (state.selectedId == id) edit { copy(detailLoading = false, detail = timecard, deal = deal) }
        }
    }

    private fun clearDetail() = edit { copy(detail = null, deal = null, detailLoading = false) }

    /** Posted rows stay visible for the record and cannot be ticked. */
    private fun toggleCheck(id: String) {
        val row = state.rows.firstOrNull { it.id == id } ?: return
        if (!row.status.isPostable) return
        edit { copy(checked = if (id in checked) checked - id else checked + id) }
    }

    /** Ticks every paid row the search leaves visible, or clears them all. */
    private fun toggleAllVisible() {
        val ui = vm.ui
        val visible = ui.historyGroups().flatMap { it.second }.filter { it.status.isPostable }.map { it.id }
        edit {
            copy(checked = if (visible.isNotEmpty() && checked.containsAll(visible)) emptySet() else visible.toSet())
        }
    }

    private fun downloadPayslip() {
        val detail = state.detail ?: return
        val week = detail.weekStarting
        val documents = vm.documents
        val files = vm.files
        if (week == null || documents == null || files == null) return
        if (!vm.ui.viewer.seesAccountantViews || state.payslipBusy) return
        edit { copy(payslipBusy = true) }
        vm.launchWork {
            val saved = when (val pdf = documents.payslip(week, detail.userId)) {
                is ZillitResult.Success -> files.saveAndOpen("payslip_${fileStamp(vm.ui.now)}.pdf", pdf.data)
                is ZillitResult.Failure -> pdf
            }
            edit { copy(payslipBusy = false) }
            if (saved is ZillitResult.Failure) vm.fail(saved.error.localised())
        }
    }

    /**
     * Posting to the ledger is irreversible, so the dialog is offered only to
     * an accountant who is also the production's approver — the rule the
     * earlier desktop port drew, kept because the web gives this dialog no
     * gate of its own (see the report: the web no longer opens it at all).
     */
    private fun openPost() {
        val ui = vm.ui
        if (!ui.canPostHistory()) {
            vm.fail(str(S.desktop_payroll_no_rights))
            return
        }
        val ids = state.postIds
        if (ids.isEmpty()) return
        val earliest = ui.earliestEffectiveDate
        val today = ui.todayIso
        edit {
            copy(
                post = HistoryPost(
                    ids = ids,
                    fromSelection = checked.isNotEmpty(),
                    effectiveDate = if (earliest != null && today < earliest) earliest else today,
                ),
            )
        }
    }

    private fun editPost(event: HistoryEvent.EditPost) = edit {
        val current = post ?: return@edit this
        copy(
            post = current.copy(
                bankId = event.bankId ?: current.bankId,
                effectiveDate = event.effectiveDate ?: current.effectiveDate,
                error = null,
            ),
        )
    }

    /**
     * Posts the batch. The account and the date are the server's requirements,
     * and a date on or before the cost-report lock is refused there, so all
     * three are checked first and named in the dialog rather than bounced.
     */
    private fun confirmPost() {
        val post = state.post ?: return
        if (post.saving) return
        if (!vm.ui.canPostHistory()) {
            vm.fail(str(S.desktop_payroll_no_rights))
            return
        }
        // Only rows still paid: a row that moved under us is not sent.
        val ids = post.ids.filter { it in state.readyIds }
        val request = postRequest(post, ids)
        if (request == null) {
            edit { copy(post = post.copy(error = postRefusal(post))) }
            return
        }
        edit { copy(post = post.copy(saving = true, error = null)) }
        vm.launchWork {
            when (val result = vm.repository.markPosted(request.ids, request.bankId, request.effectiveDate)) {
                is ZillitResult.Success -> {
                    edit { copy(post = null, checked = emptySet()) }
                    vm.notify(result.data.describe())
                    reload(silent = true)
                }

                is ZillitResult.Failure -> edit {
                    copy(post = this.post?.copy(saving = false, error = result.error.localised()))
                }
            }
        }
    }

    /** What the server needs, or null when something it requires is missing. */
    private fun postRequest(post: HistoryPost, ids: List<String>): PostRequest? {
        val bankId = post.bankId?.takeIf { it.isNotBlank() } ?: return null
        val date = PayPeriod.parseIsoDate(post.effectiveDate) ?: return null
        val earliest = vm.ui.earliestEffectiveDate
        if (ids.isEmpty() || (earliest != null && post.effectiveDate < earliest)) return null
        return PostRequest(ids, bankId, date)
    }

    /** Why [postRequest] said no — named in the dialog rather than bounced by the server. */
    private fun postRefusal(post: HistoryPost): String {
        val earliest = vm.ui.earliestEffectiveDate
        return when {
            post.bankId.isNullOrBlank() -> str(S.desktop_payroll_choose_account)
            PayPeriod.parseIsoDate(post.effectiveDate) == null -> str(S.desktop_payroll_choose_effective_date)
            earliest != null && post.effectiveDate < earliest -> str(S.desktop_payroll_date_in_locked_period, earliest)
            else -> str(S.desktop_payroll_nothing_ready_to_post)
        }
    }

    private data class PostRequest(val ids: List<String>, val bankId: String, val effectiveDate: Long)

    /** What the batch moved, said out loud: the server skips rather than fails. */
    private fun PostOutcome.describe(): String = when {
        marked == 0 -> str(S.desktop_payroll_nothing_posted, skipped)
        skipped > 0 -> str(S.desktop_payroll_posted_skipped, marked, skipped)
        else -> str(S.desktop_payroll_posted_count, marked)
    }

    private fun edit(reducer: HistoryState.() -> HistoryState) = vm.update { copy(history = history.reducer()) }
}

/** Posting from the history queue: an accountant approver — see [HistoryActions.openPost]. */
internal fun PayrollUiState.canPostHistory(): Boolean = viewer.isAccountant && viewer.isFinalApprover

/**
 * The queue grouped by department, alphabetically, after the search — the
 * web's `deptGroups`: the search matches name, designation or department.
 */
internal fun PayrollUiState.historyGroups(): List<Pair<String, List<PayrollTimecard>>> {
    val query = history.search.trim().lowercase()
    return history.rows
        .filter { row ->
            query.isEmpty() ||
                "${nameOf(row.userId)} ${roleOf(row.userId)} ${departmentOf(row.userId)}".lowercase().contains(query)
        }
        .groupBy { departmentOf(it.userId) }
        .toList()
        .sortedBy { it.first }
}

/** `2026-05-04_1430` — the web's `exportTs`, read in UTC. */
internal fun fileStamp(now: Long): String {
    val minutes = (now.mod(PayPeriod.DAY_MILLIS)) / MILLIS_PER_MINUTE
    val hh = (minutes / MINUTES_PER_HOUR).toString().padStart(2, '0')
    val mm = (minutes % MINUTES_PER_HOUR).toString().padStart(2, '0')
    return "${PayPeriod.isoDate(now)}_$hh$mm"
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
