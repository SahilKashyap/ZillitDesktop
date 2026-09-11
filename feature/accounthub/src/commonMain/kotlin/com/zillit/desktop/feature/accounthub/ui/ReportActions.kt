package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.BibleQuery
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.ReportPeriod
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceQuery

/**
 * The console's two read-only surfaces: the budget and the trial balance.
 *
 * Together for the same reason [SetupSections] and [VendorActions] are their
 * own classes — and because they are the same shape: a report the hub presents
 * rather than owns, fetched once and never written.
 */
internal class ReportActions(
    private val vm: AccountHubViewModel,
    private val defaultPeriod: () -> ReportPeriod,
) {

    /**
     * Opens the report on the year so far, and runs it once.
     *
     * The default period is the calendar year to today, which is what the web
     * opens on. It is only re-run when the user asks: a report that re-ran on
     * every filter keystroke would spend the server's time on periods nobody
     * meant.
     */
    fun openTrialBalance() {
        if (vm.setupState.trialBalance.applied != null) return
        val period = defaultPeriod()
        vm.update {
            copy(
                trialBalance = trialBalance.copy(
                    draft = TrialBalanceQuery(
                        periodStartMillis = period.startMillis,
                        periodEndMillis = period.endMillis,
                    ),
                ),
            )
        }
        runTrialBalance()
    }

    fun runTrialBalance() {
        val query = vm.setupState.trialBalance.draft
        vm.update { copy(trialBalance = trialBalance.copy(loading = true)) }
        vm.runResult({ vm.repo.trialBalance(query) }, { report ->
            vm.update {
                copy(trialBalance = trialBalance.copy(report = report, loading = false, applied = query))
            }
        }, { error ->
            vm.update { copy(trialBalance = trialBalance.copy(loading = false)) }
            vm.report(error)
        })
    }

    /**
     * The versions, then the lines of whichever is selected.
     *
     * Defaults to the Live one when there is no selection: it is the version
     * every other screen is reading, so it is the one an accountant opening
     * this page meant.
     */
    fun loadBudget() {
        vm.update { copy(budget = budget.copy(loading = true)) }
        vm.runResult(vm.repo::budgetVersions, { rows ->
            val chosen = vm.setupState.budget.selectedId
                ?: rows.firstOrNull { it.status == BudgetStatus.Live }?.id
                ?: rows.firstOrNull()?.id
            vm.update { copy(budget = budget.copy(versions = rows, loading = false, selectedId = chosen)) }
            chosen?.let(::loadBudgetLines)
        }, { error ->
            vm.update { copy(budget = budget.copy(loading = false)) }
            vm.report(error)
        })
    }

    fun loadBudgetLines(versionId: String) {
        vm.update { copy(budget = budget.copy(linesLoading = true, lines = emptyList())) }
        vm.runResult({ vm.repo.budgetLines(versionId) }, { rows ->
            vm.update {
                // Only if that version is still the one on screen: a slow
                // answer must not paint another version's lines.
                if (budget.selectedId == versionId) {
                    copy(budget = budget.copy(lines = rows, linesLoading = false))
                } else {
                    this
                }
            }
        }, { error ->
            vm.update { copy(budget = budget.copy(linesLoading = false)) }
            vm.report(error)
        })
    }

    // -- period close -------------------------------------------------------

    fun loadPeriodLock() {
        vm.update { copy(periodClose = periodClose.copy(loading = true)) }
        vm.runResult(vm.repo::periodLock, { lock ->
            vm.update { copy(periodClose = periodClose.copy(lock = lock, loading = false)) }
        }, { error ->
            vm.update { copy(periodClose = periodClose.copy(loading = false)) }
            vm.report(error)
        })
    }

    /**
     * Closes the period, once the accountant has confirmed it.
     *
     * The server owns every rule: which week the date falls in, whether
     * anything inside it is still unposted, and the refusal to move the
     * boundary backwards. Its message is shown as it comes — "post them, or
     * move their dates past the close" is the accountant's next step, and only
     * the server knows which transactions it means.
     */
    fun confirmPeriodClose() {
        val asOf = vm.setupState.periodClose.pendingCloseMillis ?: return
        if (!vm.mayActAsAccountant()) return
        vm.update { copy(periodClose = periodClose.copy(closing = true)) }
        vm.runResult({ vm.repo.closePeriod(asOf) }, { lock ->
            vm.update {
                copy(
                    periodClose = periodClose.copy(
                        lock = lock,
                        closing = false,
                        pendingCloseMillis = null,
                    ),
                    notice = "Period closed through ${lock.lockedThrough}.",
                )
            }
        }, { error ->
            // The proposal is kept: the usual failure is unposted work, and
            // the accountant will want to try the same date again once it is
            // cleared rather than re-pick it.
            vm.update { copy(periodClose = periodClose.copy(closing = false)) }
            vm.report(error)
        })
    }

    // -- bible report -------------------------------------------------------

    /** Opens the bible on the year so far, and runs it once. */
    fun openBibleReport() {
        if (vm.setupState.bible.applied != null) return
        val period = defaultPeriod()
        vm.update {
            copy(
                bible = bible.copy(
                    draft = BibleQuery(
                        periodStartMillis = period.startMillis,
                        periodEndMillis = period.endMillis,
                    ),
                ),
            )
        }
        runBibleReport()
    }

    fun runBibleReport() {
        val query = vm.setupState.bible.draft
        vm.update { copy(bible = bible.copy(loading = true)) }
        vm.runResult({ vm.repo.bibleReport(query) }, { report ->
            vm.update { copy(bible = bible.copy(report = report, loading = false, applied = query)) }
        }, { error ->
            vm.update { copy(bible = bible.copy(loading = false)) }
            vm.report(error)
        })
    }
}
