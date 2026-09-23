package com.zillit.desktop.feature.payroll.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.component.ZillitErrorToast
import com.zillit.desktop.core.designsystem.icon.ZillitToolIcons
import com.zillit.desktop.core.mvvm.ZillitViewModel
import com.zillit.desktop.core.workspace.OpenMode
import com.zillit.desktop.core.workspace.ToolProvider
import com.zillit.desktop.core.workspace.WindowNavigator
import com.zillit.desktop.core.workspace.WorkspaceRoute
import com.zillit.desktop.feature.payroll.domain.BankAccount
import com.zillit.desktop.feature.payroll.domain.DepartmentTotal
import com.zillit.desktop.feature.payroll.domain.NominalAllocation
import com.zillit.desktop.feature.payroll.domain.PayrollLine
import com.zillit.desktop.feature.payroll.domain.PayrollRepository
import com.zillit.desktop.feature.payroll.domain.PayrollViewer
import com.zillit.desktop.feature.payroll.domain.PayrollWeek
import com.zillit.desktop.feature.payroll.domain.Payslip
import com.zillit.desktop.feature.payroll.domain.TimecardStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/** Everything the payroll tool is showing. */
data class PayrollUiState(
    val viewer: PayrollViewer,
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: ZillitError? = null,
    val notice: String? = null,
    /** The week on screen. Null until the server's current week has landed. */
    val weekStarting: Long? = null,
    /** Recent weeks, newest first — the picker down the left. */
    val weekOptions: List<Long> = emptyList(),
    val lines: List<PayrollLine> = emptyList(),
    val search: String = "",
    /** Timecard ids ticked for a batch action. */
    val selection: Set<String> = emptySet(),
    val bankAccounts: List<BankAccount> = emptyList(),
    val prompt: PayrollPrompt? = null,
    /** The crew member whose detail is open, with what has loaded for them. */
    val openCrewId: String? = null,
    val nominalSplit: List<NominalAllocation> = emptyList(),
    val payslip: Payslip? = null,
) {
    val week: PayrollWeek
        get() = PayrollWeek(
            weekStarting = weekStarting ?: 0L,
            currency = lines.firstNotNullOfOrNull { it.currency },
            lines = lines,
        )

    val departmentTotals: List<DepartmentTotal> get() = DepartmentTotal.from(lines)

    val openLine: PayrollLine? get() = lines.firstOrNull { it.crewId == openCrewId }

    /**
     * Whether the open line's coding can still be changed.
     *
     * A posted timecard's is fixed — it is already in the ledger, and moving it
     * there means a journal correction rather than an edit here.
     */
    val codingEditable: Boolean
        get() = viewer.canOperate && openLine?.status != TimecardStatus.Posted

    /** What the nominal split comes to, which has to reach the line's gross. */
    val splitTotal: Double get() = nominalSplit.sumOf { it.amount }

    val splitBalances: Boolean
        get() = openLine?.let { kotlin.math.abs(splitTotal - it.gross) < PENNY } ?: true

    val visibleLines: List<PayrollLine>
        get() = lines.filter { line ->
            search.isBlank() || line.crewName.lowercase().contains(search.trim().lowercase())
        }

    /** Ticked rows that "mark paid" would actually move. */
    val selectedPayable: List<PayrollLine>
        get() = lines.filter { it.id in selection && it.status.isPayable }

    /** Ticked rows that a ledger post would actually move. */
    val selectedPostable: List<PayrollLine>
        get() = lines.filter { it.id in selection && it.status.isPostable }

    val selectedTotal: Double get() = lines.filter { it.id in selection }.sumOf { it.net }

    private companion object {
        const val PENNY = 0.005
    }
}

sealed interface PayrollPrompt {
    data class Confirm(
        val action: PayrollConfirmAction,
        val ids: List<String>,
        val title: String,
        val message: String,
    ) : PayrollPrompt

    /**
     * Posting to the ledger, which needs a settling account and a date.
     *
     * Both are the server's requirements rather than this screen's taste: a
     * post without either is refused, and the date is checked against the
     * cost-report lock.
     */
    data class Post(
        val ids: List<String>,
        val bankId: String? = null,
        val effectiveDate: Long,
    ) : PayrollPrompt
}

enum class PayrollConfirmAction { MarkPaid, MarkUnpaid }

sealed interface PayrollEvent {
    data object Refresh : PayrollEvent
    data class SelectWeek(val weekStarting: Long) : PayrollEvent
    data class Search(val query: String) : PayrollEvent
    data object ClearNotice : PayrollEvent
    data class Ask(val prompt: PayrollPrompt) : PayrollEvent
    data class UpdatePrompt(val prompt: PayrollPrompt) : PayrollEvent
    data object DismissPrompt : PayrollEvent
    data object ConfirmPrompt : PayrollEvent

    // -- selection ---------------------------------------------------------

    data class ToggleSelection(val timecardId: String) : PayrollEvent

    /** Ticks every row the pending action could move, or clears the lot. */
    data object SelectAllPayable : PayrollEvent
    data object SelectAllPostable : PayrollEvent

    // -- one crew member ---------------------------------------------------

    /** Opens a crew member's nominal split and payslip. */
    data class OpenLine(val crewId: String?) : PayrollEvent

    data class EditAllocation(val index: Int, val allocation: NominalAllocation) : PayrollEvent

    data object AddAllocation : PayrollEvent

    data class RemoveAllocation(val index: Int) : PayrollEvent

    data object SaveAllocations : PayrollEvent
}

sealed interface PayrollEffect {
    data class Failed(val message: String) : PayrollEffect
}

/**
 * The payroll tool's view model.
 *
 * ## Opening on the server's week, not ours
 *
 * The first load asks for the current processing week without naming it, and
 * takes the week back from whatever comes. A production whose pay period is
 * not a Monday week would otherwise open on an empty grid — the week we
 * computed — with nothing to say why.
 */
@Suppress("TooManyFunctions") // One per user action; the alternative is one big handler.
class PayrollViewModel(
    private val repository: PayrollRepository,
    private val viewer: () -> PayrollViewer,
    private val now: () -> Long,
) : ZillitViewModel<PayrollUiState, PayrollEvent, PayrollEffect>(PayrollUiState(viewer = viewer())) {

    private var loadJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        setState { copy(viewer = viewer()) }
        listenOnce()
        loadCurrentWeek()
        loadPermissions()
    }

    /**
     * Folds the socket's announcements into the grid: a final approval, lock,
     * payment or post landing on another client reloads the week on screen —
     * the web's `ah:payroll:list` refetch pattern. Guarded so a project
     * switch restarting the tool does not stack collectors, and debounced
     * because a batch action emits one frame per timecard (the web coalesces
     * at `accountHubListeners.js` `DEBOUNCE_MS = 500`).
     */
    private fun listenOnce() {
        if (listening) return
        listening = true
        launch {
            repository.refreshes.collect {
                syncJob?.cancel()
                syncJob = launch {
                    delay(SYNC_DEBOUNCE_MILLIS)
                    currentState.weekStarting?.let(::loadWeek) ?: loadCurrentWeek()
                }
            }
        }
    }

    private var listening = false
    private var syncJob: Job? = null

    fun onProjectChanged() {
        started = false
        setState {
            copy(
                weekStarting = null,
                weekOptions = emptyList(),
                lines = emptyList(),
                selection = emptySet(),
                bankAccounts = emptyList(),
                openCrewId = null,
            )
        }
        start()
    }

    /**
     * Swaps in the real rights once the tool grid has answered.
     *
     * `projectId` flips the moment a production is chosen, but the rights that
     * gate this screen arrive with the Home load a beat later — so the viewer
     * resolved at open is the "not yet known" one, and nothing used to replace
     * it. Seen live 2026-08-27: Document Distribution offered no publish
     * destination at all on a production with 42 tools switched on. Only the
     * viewer changes here; the open page and its data are already right.
     */
    fun onRightsChanged() {
        // Read outside the state lambda: inside it, `viewer` is the
        // state's own viewer property rather than the supplier.
        val resolved = viewer()
        setState { copy(viewer = resolved) }
    }

    @Suppress("CyclomaticComplexMethod") // One branch per user action.
    override fun onEvent(event: PayrollEvent) {
        when (event) {
            PayrollEvent.Refresh -> currentState.weekStarting?.let(::loadWeek) ?: loadCurrentWeek()

            is PayrollEvent.SelectWeek -> {
                setState {
                    copy(
                        weekStarting = event.weekStarting,
                        lines = emptyList(),
                        search = "",
                        selection = emptySet(),
                        openCrewId = null,
                        nominalSplit = emptyList(),
                        payslip = null,
                    )
                }
                loadWeek(event.weekStarting)
            }

            is PayrollEvent.Search -> setState { copy(search = event.query) }
            PayrollEvent.ClearNotice -> setState { copy(notice = null) }
            is PayrollEvent.Ask -> setState { copy(prompt = event.prompt) }
            is PayrollEvent.UpdatePrompt -> setState { copy(prompt = event.prompt) }
            PayrollEvent.DismissPrompt -> setState { copy(prompt = null) }
            PayrollEvent.ConfirmPrompt -> resolvePrompt()

            is PayrollEvent.ToggleSelection -> setState {
                copy(
                    selection = if (event.timecardId in selection) {
                        selection - event.timecardId
                    } else {
                        selection + event.timecardId
                    },
                )
            }

            PayrollEvent.SelectAllPayable -> setState { toggleAll(week.payableLines) }
            PayrollEvent.SelectAllPostable -> setState { toggleAll(week.postableLines) }

            is PayrollEvent.OpenLine -> openLine(event.crewId)

            is PayrollEvent.EditAllocation -> setState {
                copy(
                    nominalSplit = nominalSplit.mapIndexed { index, allocation ->
                        if (index == event.index) event.allocation else allocation
                    },
                )
            }

            PayrollEvent.AddAllocation -> setState {
                val short = openLine?.let { it.gross - splitTotal }?.coerceAtLeast(0.0) ?: 0.0
                copy(
                    nominalSplit = nominalSplit + NominalAllocation(
                        id = null,
                        nominalCode = "",
                        description = "",
                        // Starts at what is unallocated, which is what someone
                        // adding a line almost always means to charge.
                        amount = short,
                    ),
                )
            }

            is PayrollEvent.RemoveAllocation -> setState {
                copy(nominalSplit = nominalSplit.filterIndexed { index, _ -> index != event.index })
            }

            PayrollEvent.SaveAllocations -> saveAllocations()
        }
    }

    private fun PayrollUiState.toggleAll(candidates: List<PayrollLine>): PayrollUiState {
        val ids = candidates.map { it.id }.toSet()
        return copy(selection = if (selection.containsAll(ids) && ids.isNotEmpty()) emptySet() else ids)
    }

    /**
     * Opens one crew member.
     *
     * The split and the payslip load together but fail independently: a week
     * with no slip yet is ordinary before it is paid, and letting that hide the
     * split would make the coding unreachable exactly when it is being
     * questioned.
     */
    private fun openLine(crewId: String?) {
        setState { copy(openCrewId = crewId, nominalSplit = emptyList(), payslip = null) }
        val weekStarting = currentState.weekStarting ?: return
        if (crewId == null) return
        launch {
            val split = repository.nominalSplit(weekStarting, crewId).getOrNull().orEmpty()
            val slip = repository.payslip(weekStarting, crewId).getOrNull()
            if (currentState.openCrewId == crewId) {
                setState { copy(nominalSplit = split, payslip = slip) }
            }
        }
    }

    /**
     * Saves the nominal split, refusing one that does not reach the line.
     *
     * An under-allocated line posts the difference nowhere, which surfaces as
     * a ledger that does not balance a month later.
     */
    private fun saveAllocations() {
        val weekStarting = currentState.weekStarting ?: return
        val crewId = currentState.openCrewId ?: return
        if (!currentState.splitBalances) {
            sendEffect(
                PayrollEffect.Failed(
                    str(S.desktop_payroll_split_mismatch),
                ),
            )
            return
        }
        if (currentState.nominalSplit.any { it.nominalCode.isBlank() }) {
            sendEffect(PayrollEffect.Failed(str(S.desktop_payroll_allocation_needs_code)))
            return
        }
        act(str(S.desktop_payroll_split_saved)) {
            repository.saveNominalSplit(weekStarting, crewId, currentState.nominalSplit)
        }
    }

    /**
     * Eight weeks back from the one the server landed on, plus that one.
     *
     * Stepped back from the server's week rather than from a locally computed
     * Monday: a production whose pay period runs Friday to Thursday would
     * otherwise get a picker whose every row is two days out from the weeks
     * that actually exist.
     */
    private fun recentWeeks(landed: Long): List<Long> =
        (0 until WEEKS_SHOWN).map { landed - it * WEEK_MILLIS }

    /**
     * Opens on whatever week the server calls current.
     *
     * The week is read off the response rather than computed, so the picker,
     * the header and the grid all name the same week even when the pay period
     * does not start on a Monday.
     */
    private fun loadCurrentWeek() {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (val result = repository.currentWeek()) {
                is ZillitResult.Success -> setState {
                    val landed = result.data.weekStarting
                    copy(
                        loading = false,
                        lines = result.data.lines,
                        weekStarting = landed,
                        weekOptions = recentWeeks(landed),
                    )
                }

                // No week to show and none to guess: the picker stays empty
                // rather than offering weeks that may not line up with the
                // production's pay period at all.
                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error) }
            }
        }
    }

    private fun loadWeek(weekStarting: Long) {
        loadJob?.cancel()
        setState { copy(loading = true, error = null) }
        loadJob = launch {
            when (val result = repository.week(weekStarting)) {
                is ZillitResult.Success -> setState {
                    copy(loading = false, lines = result.data.lines)
                }

                is ZillitResult.Failure -> setState { copy(loading = false, error = result.error) }
            }
        }
    }

    /**
     * Loads what this user may do, and what they would post from.
     *
     * Both are advisory reads: a failure leaves the operator with the
     * conservative default — no posting — rather than an error over a grid they
     * can still legitimately look at.
     */
    private fun loadPermissions() = launch {
        val finalApprover = repository.isFinalApprover().getOrNull() == true
        val accounts = repository.bankAccounts().getOrNull().orEmpty()
        setState {
            copy(viewer = viewer.copy(isFinalApprover = finalApprover), bankAccounts = accounts)
        }
    }

    private fun resolvePrompt() {
        val prompt = currentState.prompt ?: return
        setState { copy(prompt = null) }

        // Marking a week paid, unpaid or posting it to the ledger is a
        // payroll operator's act — the screen offers each only where
        // `canOperate` holds (`PayrollScreen`), and this handler took
        // whatever prompt reached it.
        if (!currentState.viewer.canOperate) {
            sendEffect(PayrollEffect.Failed(str(S.desktop_payroll_no_rights)))
            return
        }
        when (prompt) {
            is PayrollPrompt.Confirm -> when (prompt.action) {
                PayrollConfirmAction.MarkPaid ->
                    act(str(S.desktop_payroll_marked_paid, prompt.ids.size)) {
                        repository.markPaid(prompt.ids)
                    }

                PayrollConfirmAction.MarkUnpaid ->
                    act(str(S.desktop_payroll_returned_to_approved)) {
                        repository.markUnpaid(prompt.ids.first())
                    }
            }

            is PayrollPrompt.Post -> post(prompt)
        }
    }

    /**
     * Posts the batch, reporting what the server actually moved.
     *
     * It skips rows in the wrong state rather than failing, so a post can
     * succeed having moved nothing — which is worth saying out loud, because
     * silence there reads as success.
     */
    private fun post(prompt: PayrollPrompt.Post) {
        val bankId = prompt.bankId
        if (bankId.isNullOrBlank()) {
            sendEffect(PayrollEffect.Failed(str(S.desktop_payroll_choose_account)))
            setState { copy(prompt = prompt) }
            return
        }
        launch {
            setState { copy(busy = true) }
            when (val result = repository.markPosted(prompt.ids, bankId, prompt.effectiveDate)) {
                is ZillitResult.Success -> {
                    val outcome = result.data
                    setState {
                        copy(
                            busy = false,
                            selection = emptySet(),
                            notice = when {
                                outcome.marked == 0 ->
                                    str(S.desktop_payroll_nothing_posted, outcome.skipped)

                                outcome.skipped > 0 ->
                                    str(S.desktop_payroll_posted_skipped, outcome.marked, outcome.skipped)

                                else -> str(S.desktop_payroll_posted_count, outcome.marked)
                            },
                        )
                    }
                    currentState.weekStarting?.let(::loadWeek)
                }

                is ZillitResult.Failure -> {
                    setState { copy(busy = false) }
                    sendEffect(PayrollEffect.Failed(result.error.localised()))
                }
            }
        }
    }

    /** A post defaults to today, which is what an accountant means nine times in ten. */
    fun today(): Long = now()

    private fun act(success: String, block: suspend () -> ZillitResult<Unit>) = launch {
        setState { copy(busy = true) }
        when (val result = block()) {
            is ZillitResult.Success -> {
                setState { copy(busy = false, notice = success, selection = emptySet()) }
                currentState.weekStarting?.let(::loadWeek)
            }

            is ZillitResult.Failure -> {
                setState { copy(busy = false) }
                sendEffect(PayrollEffect.Failed(result.error.localised()))
            }
        }
    }

    companion object {
        private const val WEEKS_SHOWN = 9
        private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000

        /** The web's refetch coalescing window — accountHubListeners.js `DEBOUNCE_MS`. */
        const val SYNC_DEBOUNCE_MILLIS = 500L
    }
}

/** Payroll as a workspace window. */
class PayrollToolProvider(
    private val viewModel: PayrollViewModel,
) : ToolProvider {

    override val path: String = PAYROLL_PATH
    override val title: String get() = str(S.dm_section_payroll)
    override val icon = ZillitToolIcons.Payroll
    override val openMode: OpenMode = OpenMode.Maximized
    override val hostsOwnRoutes: Boolean = true
    override val defaultSize: DpSize = DpSize(1440.dp, 880.dp)

    @Composable
    override fun Content(route: WorkspaceRoute, navigator: WindowNavigator) {
        val state by viewModel.state.collectAsState()
        var failure by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(viewModel) { viewModel.start() }
        LaunchedEffect(viewModel) {
            viewModel.effects.collect { effect ->
                when (effect) {
                    is PayrollEffect.Failed -> failure = effect.message
                }
            }
        }

        PayrollScreen(state = state, onEvent = viewModel::onEvent, today = viewModel::today)
        ZillitErrorToast(message = failure, onDismiss = { failure = null })
    }
}

const val PAYROLL_PATH = "/film-tools/payroll"
