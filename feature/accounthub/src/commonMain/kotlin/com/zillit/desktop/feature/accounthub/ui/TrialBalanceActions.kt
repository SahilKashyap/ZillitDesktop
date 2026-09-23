package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.HubExportReport
import com.zillit.desktop.feature.accounthub.domain.PeriodMode
import com.zillit.desktop.feature.accounthub.domain.TrialBalanceFilters
import com.zillit.desktop.feature.accounthub.domain.TrialBalancePeriod
import kotlinx.coroutines.async
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.time.Clock
import kotlin.time.Instant
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str

/**
 * The trial balance — the web's `TrialBalanceModule`.
 *
 * Its own collaborator, as the bible beside it is: its rules are its own. It
 * runs on open, once, with the period and the currency already resolved; after
 * that it only runs when asked, and only the newest answer is ever shown.
 */
internal class TrialBalanceActions(
    private val vm: AccountHubViewModel,
    /** The reader's zone — what a "day" in the period means. */
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    /** Bumped for every request, so an answer overtaken by a later press is dropped — the web's `alive` flag. */
    private var latestRun = 0

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.SetTrialBalancePeriodMode -> edit { copy(periodMode = event.mode) }
            is AccountHubEvent.EditTrialBalanceDates -> edit { copy(fromText = event.from, toText = event.to) }
            is AccountHubEvent.EditTrialBalanceAccounts -> edit {
                copy(accountFromText = event.from, accountToText = event.to)
            }
            is AccountHubEvent.PickTrialBalanceCompany -> edit { copy(companyId = event.companyId) }
            is AccountHubEvent.PickTrialBalanceCurrency -> edit { copy(currency = event.code, currencyTouched = true) }
            is AccountHubEvent.SetTrialBalanceZeroAccounts -> edit { copy(includeZeroAccounts = event.include) }
            AccountHubEvent.RefreshTrialBalance -> refresh()
            is AccountHubEvent.ToggleTrialBalanceExport -> edit { copy(exportOpen = event.open) }
            is AccountHubEvent.ExportTrialBalance -> export(event.format)
            else -> return false
        }
        return true
    }

    /**
     * Opens the page and runs the report.
     *
     * The first run waits for the close boundary and the production's
     * currencies, so its one request already carries Current Period's real
     * first day and the default currency — the web pins exactly that ("one
     * request on open, already carrying the currency"). Coming back runs the
     * filters already applied again, with Current Period re-read: the web
     * remounts with fresh figures, and a period closed in the meantime moves
     * where "current" starts.
     */
    fun open() {
        val now = nowMillis()
        val zone = zone()
        edit {
            val first = applied == null && fromText.isBlank() && toText.isBlank()
            copy(
                today = TrialBalancePeriod.today(now, zone),
                // The web's Date Range opens on the first of January to today.
                fromText = if (first) TrialBalancePeriod.yearStart(now, zone) else fromText,
                toText = if (first) TrialBalancePeriod.today(now, zone) else toText,
                loading = true,
                failed = false,
                errorMessage = null,
            )
        }
        val known = vm.setupState.setup
        if (known.companies.saved.isEmpty()) loadCompanies()
        val needsCurrencies = known.currencies.saved.currencies.isEmpty()
        val runsBefore = latestRun
        vm.launchWork {
            val lock = async { vm.repo.periodLock() }
            val currencies = if (needsCurrencies) async { vm.repo.currencies() } else null
            // A boundary that cannot be read runs "Till today", as the web's
            // does; one already known from another page is kept.
            (lock.await() as? ZillitResult.Success)?.data?.let { boundary ->
                vm.update { copy(periodClose = periodClose.copy(lock = boundary)) }
            }
            (currencies?.await() as? ZillitResult.Success)?.data?.let { settings ->
                vm.update { copy(setup = setup.copy(currencies = setup.currencies.loaded(settings))) }
                if (settings.currencies.isEmpty() && vm.setupState.setup.currencyCatalogue.isEmpty()) loadCatalogue()
            }
            seedCurrency()
            // A Refresh pressed while this waited is newer than what the page
            // opened on: re-running the older filters now would paint over it.
            if (latestRun != runsBefore) return@launchWork
            val state = vm.setupState
            run(state.trialBalance.applied?.rebased(state) ?: state.trialBalanceDraft)
        }
    }

    /** Refresh: whatever the filter bar says now, with "today" read at the press. */
    private fun refresh() {
        edit { copy(today = TrialBalancePeriod.today(nowMillis(), zone())) }
        val draft = vm.setupState.trialBalanceDraft
        if (draft.hasValidPeriod) run(draft)
    }

    /**
     * The production's default currency, until the reader picks one — the
     * web's `currencyTouched`. A report is in that currency from its first
     * request rather than converting on a second.
     */
    private fun seedCurrency() {
        val default = vm.setupState.setup.currencies.saved.defaultCode.orEmpty()
        edit { if (currencyTouched || default.isBlank()) this else copy(currency = default) }
    }

    /**
     * The applied filters as they read today.
     *
     * Current Period is re-drawn from the boundary and the date, and an
     * untouched currency follows the production's default; everything the
     * reader chose stays as they chose it.
     */
    private fun TrialBalanceFilters.rebased(state: AccountHubUiState): TrialBalanceFilters {
        val trial = state.trialBalance
        val current = mode == PeriodMode.Current
        return copy(
            from = if (current) TrialBalanceState.currentFrom(state.periodClose.lock.lockedThrough) else from,
            to = if (current) trial.today else to,
            currency = if (trial.currencyTouched) currency else trial.currency,
        )
    }

    private fun run(filters: TrialBalanceFilters) {
        val defaultCurrency = vm.setupState.setup.currencies.saved.defaultCode.orEmpty()
        val query = filters.toQuery(zone(), defaultCurrency)
        val run = ++latestRun
        if (query == null) {
            // The web sends nothing while a day cannot be read.
            edit { copy(applied = filters, loading = false) }
            return
        }
        // Applied before the answer, as the web applies it on the press: the
        // header's Refresh goes away at once, and the skeleton says why.
        edit { copy(applied = filters, loading = true, failed = false, errorMessage = null, exportOpen = false) }
        vm.runResult({ vm.repo.trialBalance(query) }, { report ->
            if (run == latestRun) edit { copy(report = report, loading = false) }
        }, { error ->
            if (run == latestRun) edit { copy(loading = false, failed = true, errorMessage = error.localised()) }
        })
    }

    private fun loadCompanies() {
        vm.runResult(vm.repo::companies, { rows ->
            vm.update { copy(setup = setup.copy(companies = setup.companies.loaded(rows))) }
        })
    }

    /** A production that has picked no currencies is offered the whole catalogue, as the web offers it. */
    private fun loadCatalogue() {
        vm.runResult(vm.repo::currencyCatalogue, { rows ->
            vm.update { copy(setup = setup.copy(currencyCatalogue = rows)) }
        })
    }

    /**
     * The server-rendered file — `POST /trial-balance/export/{format}`.
     *
     * The filters the rows on screen answer, not the bar's newer ones, so the
     * file matches the table it was exported from.
     */
    private fun export(format: ExportFormat) {
        val exporter = vm.exporter ?: return
        val files = vm.files ?: return
        val state = vm.setupState
        if (state.trialBalance.exporting != null) return
        val body = exportBody(state) ?: return
        edit { copy(exporting = format, exportOpen = false) }
        vm.launchWork {
            when (val bytes = exporter.export(HubExportReport.TrialBalance, format, body)) {
                is ZillitResult.Failure -> vm.report(bytes.error)
                is ZillitResult.Success -> {
                    val name = "${HubExportReport.TrialBalance.fileStem}_${exportStamp()}.${format.extension}"
                    when (val saved = files.saveAndOpen(name, bytes.data)) {
                        is ZillitResult.Failure -> vm.report(saved.error)
                        is ZillitResult.Success -> vm.update { copy(notice = str(S.desktop_exported_file, name)) }
                    }
                }
            }
            edit { copy(exporting = null) }
        }
    }

    /**
     * The export's body: the read filters plus the header's words.
     *
     * Blank filters are left out rather than sent as null — the web's body is
     * serialised from `undefined`, which drops the key, and a validator that
     * types a field as a string refuses a null in it.
     */
    private fun exportBody(state: AccountHubUiState): JsonObject? {
        val applied = state.trialBalance.applied ?: return null
        val zone = zone()
        val start = TrialBalancePeriod.startOfDay(applied.from, zone) ?: return null
        val end = TrialBalancePeriod.endOfDay(applied.to, zone) ?: return null
        val company = state.setup.companies.saved.firstOrNull { it.id == applied.companyId }
        return buildJsonObject {
            put("period_start", JsonPrimitive(start))
            put("period_end", JsonPrimitive(end))
            putText("account_start", applied.accountStart)
            putText("account_end", applied.accountEnd)
            putText("company_id", applied.companyId)
            putText("currency", applied.currency.ifBlank { state.setup.currencies.saved.defaultCode.orEmpty() })
            put("zero_accounts", JsonPrimitive(applied.includeZeroAccounts))
            putText("project_name", state.projectName)
            putText("company_name", company?.let { it.name.ifBlank { it.legalName } }.orEmpty())
            put("period_label", JsonPrimitive(applied.periodLabel))
        }
    }

    private fun JsonObjectBuilder.putText(key: String, value: String) {
        if (value.isNotBlank()) put(key, JsonPrimitive(value))
    }

    /** `YYYY-MM-DD_HHMM` on the machine's clock — the web's `exportTs`. */
    private fun exportStamp(): String {
        val local = Instant.fromEpochMilliseconds(nowMillis()).toLocalDateTime(zone())
        return "${local.date}_${local.hour.toString().padStart(2, '0')}${local.minute.toString().padStart(2, '0')}"
    }

    private fun edit(change: TrialBalanceState.() -> TrialBalanceState) =
        vm.update { copy(trialBalance = trialBalance.change()) }

    /** The host's clock; the system's when a host passes none, as tests of other screens do. */
    private fun nowMillis(): Long = vm.nowMillis().takeIf { it > 0 } ?: Clock.System.now().toEpochMilliseconds()
}
