package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.EpochDate
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.asTree
import com.zillit.desktop.feature.accounthub.domain.ClosingPackage
import com.zillit.desktop.feature.accounthub.domain.ClosingReport
import com.zillit.desktop.feature.accounthub.domain.IsoDate
import com.zillit.desktop.feature.accounthub.domain.PeriodLock
import com.zillit.desktop.feature.accounthub.domain.ReportPeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

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
                vm.update {
                    copy(budget = budget.copy(selectedId = event.id, lines = emptyList(), openGroups = emptySet()))
                }
                event.id?.let(::loadBudgetLines)
            }
            AccountHubEvent.OpenBudgetFile -> openBudgetFile()
            is AccountHubEvent.ToggleBudgetGroup -> vm.update {
                val open = budget.openGroups
                copy(budget = budget.copy(openGroups = if (event.id in open) open - event.id else open + event.id))
            }
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
     * With nothing selected, the first card is — the web's
     * `selectedId ?? list[0].id`. A selection that no longer exists (a version
     * removed elsewhere) falls back the same way rather than leaving the
     * detail pane empty. The production's currencies are read once too: a
     * version with no currency of its own shows the production's default.
     */
    fun loadBudget() {
        vm.update { copy(budget = budget.copy(loading = true)) }
        vm.runResult(vm.repo::budgetVersions, { rows ->
            val current = vm.setupState.budget.selectedId
            val chosen = current?.takeIf { id -> rows.any { it.id == id } } ?: rows.firstOrNull()?.id
            vm.update { copy(budget = budget.copy(versions = rows, loading = false, selectedId = chosen)) }
            // A refresh of the version already on screen keeps what was opened.
            val refresh = chosen == current && vm.setupState.budget.lines.isNotEmpty()
            chosen?.let { loadBudgetLines(it, keepOpen = refresh) }
        }, { error ->
            vm.update { copy(budget = budget.copy(loading = false)) }
            vm.report(error)
        })
        vm.chart.ensureLoaded()
        if (vm.setupState.setup.currencies.saved.currencies.isEmpty()) {
            vm.runResult(vm.repo::currencies, { settings ->
                vm.update { copy(setup = setup.copy(currencies = setup.currencies.loaded(settings))) }
            }, { })
        }
    }

    /**
     * A version's lines. The top-level groups open, as the web's rows do at
     * depth 0 — unless [keepOpen], a refresh of the version already on screen,
     * where whatever the accountant opened stays open.
     */
    fun loadBudgetLines(versionId: String, keepOpen: Boolean = false) {
        vm.update {
            copy(budget = budget.copy(linesLoading = true, lines = if (keepOpen) budget.lines else emptyList()))
        }
        vm.runResult({ vm.repo.budgetLines(versionId) }, { rows ->
            vm.update {
                // Only if that version is still the one on screen: a slow
                // answer must not paint another version's lines.
                if (budget.selectedId == versionId) {
                    val open = if (keepOpen) budget.openGroups else rows.asTree().initiallyOpen
                    copy(budget = budget.copy(lines = rows, linesLoading = false, openGroups = open))
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
        // Noon, not midnight, of the UTC day: midnight formats as the day before
        // anywhere west of UTC.
        val shown = IsoDate.toEpochMillis(lock.lockedThrough)?.let { EpochDate.date(it + DAY_MILLIS / 2) }
            ?: lock.lockedThrough
        return if (shown.isBlank()) str(S.desktop_hub_the_lock_has_moved) else str(
            S.desktop_hub_locked_through_x,
            shown,
        )
    }

    private fun defaultCloseDate(lock: PeriodLock): String {
        val today = vm.nowMillis().takeIf { it > 0 } ?: defaultPeriod().endMillis
        // Noon UTC of the day after the lock, so a zone west of UTC does not
        // format it as the lock day itself.
        val dayAfterLock = IsoDate.toEpochMillis(lock.lockedThrough)?.let { it + DAY_MILLIS + DAY_MILLIS / 2 }
        return EpochDate.isoDate(maxOf(today, dayAfterLock ?: today))
    }

    /** The earliest date the picker allows — the day after the lock. */
    internal fun minCloseDate(lock: PeriodLock): String? =
        IsoDate.toEpochMillis(lock.lockedThrough)?.let { EpochDate.isoDate(it + DAY_MILLIS + DAY_MILLIS / 2) }

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
        vm.runResult({ vm.repo.closePeriod(asOf) }, { answered ->
            // The web falls back to the chosen date when the reply names none
            // (`… || target`): a blank lock here would read "No period locked
            // yet" straight after a close, and reset the picker's minimum.
            val lock = if (answered.isClosed) answered else answered.copy(lockedThrough = utcIsoDate(asOf))
            vm.update {
                copy(
                    periodClose = periodClose.copy(
                        lock = lock,
                        closing = false,
                        pendingCloseMillis = null,
                        result = CloseResult(true, str(S.desktop_hub_period_closed_x, lockedThroughText(lock))),
                        closeDateText = defaultCloseDate(lock),
                    ),
                    notice = str(S.desktop_hub_period_closed_x, lockedThroughText(lock)),
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

    /** A user's edit: it also clears the last result, as the web hides its banner on any change. */
    private fun editPublish(change: PublishState.() -> PublishState) = setPublish { change().copy(result = null) }

    private fun setPublish(change: PublishState.() -> PublishState) = vm.update {
        copy(periodClose = periodClose.copy(publish = periodClose.publish.change()))
    }

    private fun editPackage(id: Int, change: (ClosingPackage) -> ClosingPackage) = editPublish {
        copy(packages = packages.map { if (it.id == id) change(it) else it })
    }

    private fun publish() {
        val publish = vm.setupState.periodClose.publish
        val valid = publish.validPackages
        if (valid.isEmpty() || publish.publishing) return
        if (!vm.mayActAsAccountant()) return
        setPublish { copy(publishing = true, result = null) }
        vm.runResult({ vm.repo.publishClosingPackage(valid) }, {
            // Back to one empty package, as the web does: leaving the sent ones
            // in place with Publish still lit e-mailed the same people the same
            // reports again on a second click.
            setPublish {
                copy(
                    packages = listOf(ClosingPackage(id = 1)),
                    nextId = 2,
                    openMenu = null,
                    publishing = false,
                    result = CloseResult(true, publishedText(valid.size)),
                )
            }
        }, { error ->
            setPublish { copy(publishing = false, result = CloseResult(false, error.localised())) }
        })
    }

    /** The UTC day `as_of` falls in — the day the accountant picked, whatever this machine's zone. */
    private fun utcIsoDate(millis: Long): String =
        Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.UTC).date.toString()

    private fun publishedText(n: Int) =
        if (n == 1) str(S.desktop_hub_published_one_package) else str(S.desktop_hub_published_n_packages, n)

    private companion object {
        const val DAY_MILLIS = 86_400_000L
        val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
    }
}
