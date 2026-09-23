package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.DayTypes
import com.zillit.desktop.feature.accounthub.ui.components.SectionLoad

/**
 * Reads Production Setup's slices, one route each, and records which landed.
 *
 * Its own collaborator because the record is the point: a section that never
 * loaded holds the empty default, and saving it writes that default over the
 * server's — see [SliceLoads]. Each read marks its section loaded or failed;
 * the page shows a failed one as an error card with Retry, and the section
 * refuses to save until a read has landed.
 *
 * A failed first read is not also toasted: fourteen toasts for one dropped
 * connection say less than fourteen cards that each offer a Retry. A failed
 * *re*-read (a socket refresh) is toasted, because it has no card — the last
 * good read stays on screen.
 */
internal class SetupLoader(private val vm: AccountHubViewModel) {

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.RetrySetupSection -> load(event.section)
            AccountHubEvent.RetryBanks -> vm.loadBanks()
            AccountHubEvent.RetryCatalogues -> vm.loadCatalogues()
            else -> return false
        }
        return true
    }

    /** Every tracked slice, each on its own call. */
    fun loadAll() = SliceLoads.TRACKED.forEach(::load)

    @Suppress("CyclomaticComplexMethod") // One branch per slice; the list is the contract.
    fun load(section: SetupSection) {
        val repo = vm.repo
        when (section) {
            SetupSection.Companies -> read(section, repo::companies) { copy(companies = companies.loaded(it)) }
            SetupSection.Currencies -> read(section, repo::currencies) { copy(currencies = currencies.loaded(it)) }
            SetupSection.TaxTypes -> read(section, repo::taxTypes) { copy(taxTypes = taxTypes.loaded(it)) }
            SetupSection.AssetTags -> read(section, repo::assetTags) { copy(assetTags = assetTags.loaded(it)) }
            SetupSection.Schedule -> read(section, repo::productionSchedule) {
                copy(schedule = schedule.loaded(ScheduleForm.from(it)))
            }
            SetupSection.PayrollDefaults -> read(section, repo::payrollDefaults) {
                copy(payrollDefaults = payrollDefaults.loaded(it))
            }
            SetupSection.DealConditions -> read(section, repo::dealConditions) {
                copy(dealConditions = dealConditions.loaded(it))
            }
            SetupSection.PayrollBureaus -> read(section, repo::payrollBureaus) {
                copy(payrollBureaus = payrollBureaus.loaded(it))
            }
            SetupSection.Allowances -> read(section, repo::allowancesRentals) {
                copy(allowances = allowances.loaded(it))
            }
            SetupSection.PayrollSettings -> read(section, repo::payrollSettings) {
                copy(payrollSettings = payrollSettings.loaded(it))
            }
            SetupSection.PoSetup -> read(section, repo::purchaseOrderSetup) { copy(poSetup = poSetup.loaded(it)) }
            SetupSection.InvoicesSetup -> read(section, repo::invoicesSetup) {
                copy(invoicesSetup = invoicesSetup.loaded(it))
            }
            SetupSection.NonUnionPay -> read(section, repo::nonUnionPay) { copy(nonUnionPay = nonUnionPay.loaded(it)) }
            // Seeded on read as well as on save, so a fresh project shows the
            // three defaults rather than an empty catalogue — and so the
            // section does not read as unsaved the moment it loads.
            SetupSection.DayTypes -> read(section, repo::dayTypes) {
                copy(dayTypes = dayTypes.loaded(DayTypes.seeded(it)))
            }
            // Not on this page (the web dropped the section); nothing to read.
            SetupSection.Budget -> Unit
        }
    }

    /**
     * One read: on success the slice is applied and marked loaded; on failure
     * it is marked failed — unless it had loaded before, when the last good
     * read stays and the failure is only reported.
     */
    fun <T> read(
        section: SetupSection,
        call: suspend () -> ZillitResult<T>,
        applying: SetupState.(T) -> SetupState,
    ) {
        vm.update { copy(setup = setup.copy(slices = setup.slices.requested(section))) }
        vm.runResult(call, { value ->
            vm.update { copy(setup = setup.applying(value).copy(slices = setup.slices.succeeded(section))) }
        }, { error ->
            val loadedBefore = vm.setupState.setup.slices.isLoaded(section)
            vm.update { copy(setup = setup.copy(slices = setup.slices.failedWith(section, error.localised()))) }
            if (loadedBefore) vm.report(error)
        })
    }
}

/** A section's read state for its shell, Retry wired to [onEvent]. See [SliceLoads]. */
internal fun AccountHubUiState.sectionLoad(section: SetupSection, onEvent: (AccountHubEvent) -> Unit): SectionLoad =
    SectionLoad(
        loading = setup.slices.isLoading(section),
        error = setup.slices.failure(section),
        onRetry = { onEvent(AccountHubEvent.RetrySetupSection(section)) },
    )

/** The bank list's read state, as the Bank Accounts section draws it. */
internal fun AccountHubUiState.bankLoad(onEvent: (AccountHubEvent) -> Unit): SectionLoad = SectionLoad(
    loading = setup.banksLoading && !setup.banksLoaded,
    error = setup.banksError,
    onRetry = { onEvent(AccountHubEvent.RetryBanks) },
)
