package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.BudgetStatus
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.ClosingReport
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.ReportPeriod

/**
 * The console's read surfaces: the budget, the two reports and the period
 * close with its two dashboards.
 *
 * Together for the same reason [SetupSections] and [VendorActions] are their
 * own classes — and because they are the same shape: a report the hub presents
 * rather than owns, fetched on request and exported as the server renders it.
 */
@Suppress("TooManyFunctions") // One handler per action.
internal class ReportActions(
    private val vm: AccountHubViewModel,
    private val defaultPeriod: () -> ReportPeriod,
) {

    @Suppress("CyclomaticComplexMethod", "LongMethod") // One branch per action.
    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.SelectBudgetVersion -> {
                vm.update { copy(budget = budget.copy(selectedId = event.id, lines = emptyList())) }
                event.id?.let(::loadBudgetLines)
            }
            AccountHubEvent.OpenBudgetFile -> openBudgetFile()
            is AccountHubEvent.SwitchPeriodCloseTab -> switchCloseTab(event.tab)
            is AccountHubEvent.EditCloseDate -> vm.update {
                copy(periodClose = periodClose.copy(closeDateText = event.text, result = null))
            }
            is AccountHubEvent.ProposePeriodClose -> vm.update {
                copy(periodClose = periodClose.copy(pendingCloseMillis = event.asOfMillis))
            }
            AccountHubEvent.ConfirmPeriodClose -> confirmPeriodClose()
            AccountHubEvent.CancelPeriodClose -> vm.update {
                copy(periodClose = periodClose.copy(pendingCloseMillis = null))
            }
            is AccountHubEvent.ToggleChecklistItem -> vm.update {
                val cash = periodClose.cashClose
                val next = if (event.label in cash.checked) cash.checked - event.label else cash.checked + event.label
                copy(periodClose = periodClose.copy(cashClose = cash.copy(checked = next)))
            }
            AccountHubEvent.ResetChecklist -> vm.update {
                copy(periodClose = periodClose.copy(cashClose = periodClose.cashClose.copy(checked = emptySet())))
            }
            is AccountHubEvent.EditPackages -> editPublish { copy(packages = event.packages) }
            AccountHubEvent.AddPackage -> editPublish {
                copy(packages = packages + ClosingPackage(id = nextId), nextId = nextId + 1)
            }
            is AccountHubEvent.RemovePackage -> editPublish {
                copy(packages = packages.filterNot { it.id == event.id })
            }
            is AccountHubEvent.TogglePackageRecipient -> editPackage(event.packageId) { pkg ->
                pkg.copy(
                    userIds =
                        if (event.userId in pkg.userIds) pkg.userIds - event.userId else pkg.userIds + event.userId,
                )
            }
            is AccountHubEvent.AddPackageEmail -> editPackage(event.packageId) { pkg ->
                val email = pkg.emailDraft.trim().lowercase()
                if (email.isEmpty() || !EMAIL.matches(email) || email in pkg.emails) pkg else pkg.copy(
                    emails = pkg.emails + email,
                    emailDraft = "",
                )
            }
            is AccountHubEvent.TogglePackageReport -> editPackage(event.packageId) { pkg ->
                pkg.copy(
                    reports =
                        if (event.report in pkg.reports) pkg.reports - event.report else pkg.reports + event.report,
                )
            }
            is AccountHubEvent.TogglePackageAllReports -> editPackage(event.packageId) { pkg ->
                pkg.copy(
                    reports =
                        if (pkg.reports.size == ClosingReport.entries.size) emptyList()
                        else ClosingReport.entries.toList(),
                )
            }
            is AccountHubEvent.OpenPackageMenu -> editPublish { copy(openMenu = event.packageId, menuQuery = "") }
            is AccountHubEvent.SearchPackageMenu -> editPublish { copy(menuQuery = event.term) }
            AccountHubEvent.PublishPackages -> publish()
            else -> return false
        }
        return true
    }

    // The trial balance is TrialBalanceActions', and the bible report BibleActions'.

    // -- budget ---------------------------------------------------------------

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
        vm.chart.ensureLoaded()
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

    private fun openBudgetFile() {
        val opener = vm.documentOpener ?: return
        val document = vm.setupState.budget.selected?.attachment ?: return
        vm.update { copy(budget = budget.copy(openingFile = true)) }
        vm.launchWork {
            when (val opened = opener.open(document)) {
                is ZillitResult.Failure -> vm.report(opened.error)
                is ZillitResult.Success -> Unit
            }
            vm.update { copy(budget = budget.copy(openingFile = false)) }
        }
    }

    // -- period close -------------------------------------------------------

    fun openPeriodClose() {
        loadPeriodLock()
        if (vm.setupState.periodClose.tab == PeriodCloseTab.CashClose) loadCashClose()
    }

    fun loadPeriodLock() {
        vm.update { copy(periodClose = periodClose.copy(loading = true)) }
        vm.runResult(vm.repo::periodLock, { lock ->
            vm.update {
                copy(
                    periodClose = periodClose.copy(
                        lock = lock,
                        loading = false,
                        closeDateText = periodClose.closeDateText.ifBlank { defaultCloseDate(lock) },
                    ),
                )
            }
        }, { error ->
            vm.update { copy(periodClose = periodClose.copy(loading = false)) }
            vm.report(error)
        })
    }

    /** The web's `lockedDefaultDateInput`: today, or the day after the lock, whichever is later. */
    /**
     * "Locked through 6 Sep 2026." — the same wording the web confirms with.
     *
     * Formatted, not the raw `YYYY-MM-DD`: the card above it prints the date
     * in words, and a confirmation that disagrees with the panel it confirms
     * reads as two different dates.
     */
    private fun lockedThroughText(lock: PeriodLock): String {
        val shown = IsoDate.toEpochMillis(lock.lockedThrough)?.let { EpochDate.date(it) }
            ?: lock.lockedThrough
        return if (shown.isBlank()) "The lock has moved." else "Locked through $shown."
    }

    private fun defaultCloseDate(lock: PeriodLock): String {
        val today = vm.nowMillis().takeIf { it > 0 } ?: defaultPeriod().endMillis
        val dayAfterLock = IsoDate.toEpochMillis(lock.lockedThrough)?.let { it + DAY_MILLIS }
        return EpochDate.isoDate(maxOf(today, dayAfterLock ?: today))
    }

    /** The earliest date the picker allows — the day after the lock. */
    internal fun minCloseDate(lock: PeriodLock): String? =
        IsoDate.toEpochMillis(lock.lockedThrough)?.let { EpochDate.isoDate(it + DAY_MILLIS) }

    private fun switchCloseTab(tab: PeriodCloseTab) {
        vm.update { copy(periodClose = periodClose.copy(tab = tab)) }
        if (tab == PeriodCloseTab.CashClose && !vm.setupState.periodClose.cashClose.loaded) loadCashClose()
    }

    private fun loadCashClose() {
        vm.update { copy(periodClose = periodClose.copy(cashClose = periodClose.cashClose.copy(loading = true))) }
        vm.runResult(vm.repo::cashClose, { dashboard ->
            vm.update {
                copy(
                    periodClose = periodClose.copy(
                        cashClose = CashCloseState(
                            loaded = true,
                            dashboard = dashboard,
                            // Seeded from the API's own `done`, then ticked locally.
                            checked = dashboard.checklist.filter { it.done }.map { it.label }.toSet(),
                        ),
                    ),
                )
            }
        }, { error ->
            vm.update {
                copy(periodClose = periodClose.copy(cashClose = periodClose.cashClose.copy(
                    loading = false,
                    loaded = true,
                )))
            }
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
                        result = CloseResult(true, "Period closed. ${lockedThroughText(lock)}"),
                        closeDateText = defaultCloseDate(lock),
                    ),
                    notice = "Period closed. ${lockedThroughText(lock)}",
                )
            }
        }, { error ->
            // The proposal is kept: the usual failure is unposted work, and
            // the accountant will want to try the same date again once it is
            // cleared rather than re-pick it.
            vm.update {
                copy(periodClose = periodClose.copy(
                    closing = false,
                    pendingCloseMillis = null,
                    result = CloseResult(false, error.localised()),
                ))
            }
        })
    }

    // -- publish closing package ----------------------------------------------

    private fun editPublish(change: PublishState.() -> PublishState) = vm.update {
        copy(periodClose = periodClose.copy(publish = periodClose.publish.change()))
    }

    private fun editPackage(id: Int, change: (ClosingPackage) -> ClosingPackage) = editPublish {
        copy(packages = packages.map { if (it.id == id) change(it) else it })
    }

    private fun publish() {
        val publish = vm.setupState.periodClose.publish
        val valid = publish.validPackages
        if (valid.isEmpty()) return
        editPublish { copy(publishing = true, result = null) }
        vm.runResult({ vm.repo.publishClosingPackage(valid) }, {
            editPublish {
                copy(publishing = false, result = CloseResult(true, "Published ${plural(valid.size, "package")}."))
            }
        }, { error ->
            editPublish { copy(publishing = false, result = CloseResult(false, error.localised())) }
        })
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
