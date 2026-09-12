package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.accounthub.data.toExportBody
import com.zillit.desktop.feature.accounthub.domain.BibleFormat
import com.zillit.desktop.feature.accounthub.domain.BiblePeriod
import com.zillit.desktop.feature.accounthub.domain.ExportFormat
import com.zillit.desktop.feature.accounthub.domain.HubExportReport
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The closeout bible — the web's `BibleReportModule`.
 *
 * Its own collaborator, like [ReportActions] beside it, because its rules are
 * its own: it never runs on open, its period is resolved at the press rather
 * than when the page appeared, and its export sends what the table was run
 * with rather than what the filter bar says now.
 */
internal class BibleActions(
    private val vm: AccountHubViewModel,
    /** The reader's zone — what a "day" in the period pickers means. */
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            is AccountHubEvent.EditBibleFilters -> vm.update { copy(bible = bible.copy(filters = event.filters)) }
            AccountHubEvent.RunBibleReport -> run()
            is AccountHubEvent.ToggleBibleAccount -> vm.update {
                val folded = bible.collapsed
                val next = if (event.code in folded) folded - event.code else folded + event.code
                copy(bible = bible.copy(collapsed = next))
            }
            is AccountHubEvent.SetAllBibleAccounts -> vm.update {
                val codes = bible.report?.accounts.orEmpty().map { it.code }.toSet()
                copy(bible = bible.copy(collapsed = if (event.collapsed) codes else emptySet()))
            }
            is AccountHubEvent.ToggleBibleExport -> vm.update { copy(bible = bible.copy(exportOpen = event.open)) }
            is AccountHubEvent.ExportBible -> export(event.format)
            else -> return false
        }
        return true
    }

    /**
     * Opens the bible — and does not run it.
     *
     * The first open seeds the Date Range with the web's own default, the first
     * of January to today, and preselects the production's currency. Every open
     * re-reads the close boundary and the filters' sources: both change from
     * other screens, and a Current Period drawn from a boundary read an hour ago
     * would label a report with a period that has since closed.
     */
    fun open() {
        val today = today()
        vm.update {
            val filters = if (bible.prepared) {
                bible.filters
            } else {
                val (from, to) = BiblePeriod.defaultRange(today)
                bible.filters.copy(
                    fromDate = from,
                    toDate = to,
                    currency = bible.filters.currency.ifBlank { setup.currencies.saved.defaultCode.orEmpty() },
                )
            }
            copy(bible = bible.copy(filters = filters, prepared = true, today = today.toString()))
        }
        loadLock()
        loadFilterSources()
    }

    /**
     * The boundary Current Period starts on.
     *
     * Kept in the period close's slot rather than the bible's own, so the two
     * pages beside each other can never disagree about where the books close.
     * A failure is quiet, as the web's is: the report then runs "Till today",
     * and the label on screen says exactly that.
     */
    private fun loadLock() {
        vm.update { copy(bible = bible.copy(lockLoading = true)) }
        vm.runResult(vm.repo::periodLock, { lock ->
            vm.update { copy(periodClose = periodClose.copy(lock = lock), bible = bible.copy(lockLoading = false)) }
        }, {
            vm.update { copy(bible = bible.copy(lockLoading = false)) }
        })
    }

    /**
     * Companies, currencies, taxes and tags from the production's settings,
     * and every vendor for the one single-choice picker.
     *
     * Failures are swallowed, as the web's filter loads are: an empty picker
     * narrows nothing, and the report still runs across everything.
     */
    private fun loadFilterSources() {
        vm.runResult(vm.repo::companies, { rows ->
            vm.update { copy(setup = setup.copy(companies = setup.companies.loaded(rows))) }
        })
        vm.runResult(vm.repo::currencies, { settings ->
            vm.update {
                copy(
                    setup = setup.copy(currencies = setup.currencies.loaded(settings)),
                    // The web preselects the production's default once it
                    // resolves, and never overrides a currency already picked.
                    bible = bible.copy(
                        filters = bible.filters.copy(
                            currency = bible.filters.currency.ifBlank { settings.defaultCode.orEmpty() },
                        ),
                    ),
                )
            }
            if (settings.currencies.isEmpty() && vm.setupState.setup.currencyCatalogue.isEmpty()) loadCatalogue()
        })
        vm.runResult(vm.repo::taxTypes, { rows ->
            vm.update { copy(setup = setup.copy(taxTypes = setup.taxTypes.loaded(rows))) }
        })
        vm.runResult(vm.repo::assetTags, { tags ->
            vm.update { copy(setup = setup.copy(assetTags = setup.assetTags.loaded(tags))) }
        })
        vm.runResult({ vm.repo.vendors() }, { rows ->
            vm.update { copy(bible = bible.copy(vendors = rows.sortedBy { it.display.lowercase() })) }
        })
    }

    /** A production that has picked no currencies is offered the whole catalogue, as the web offers it. */
    private fun loadCatalogue() {
        vm.runResult(vm.repo::currencyCatalogue, { rows ->
            vm.update { copy(setup = setup.copy(currencyCatalogue = rows)) }
        })
    }

    /**
     * Runs the report for whatever the filter bar says now.
     *
     * "Today" is read here, at the press, so a console left open overnight asks
     * for today's period rather than the day it was opened. A failure keeps the
     * previous run's table on screen under the message, as the web does.
     */
    private fun run() {
        val snapshot = vm.setupState
        if (snapshot.bible.loading) return
        val today = today()
        val lockedThrough = snapshot.periodClose.lock.lockedThrough
        val filters = snapshot.bible.filters
        val period = BiblePeriod.resolve(filters, lockedThrough, today, zone()) ?: return
        val query = filters.toQuery(period)
        val companyName = snapshot.setup.companies.saved
            .filter { it.id in query.companyIds }
            .joinToString(", ") { it.name.ifBlank { it.legalName } }
        val pending = BibleRun(query, BiblePeriod.label(filters, lockedThrough, today), companyName)

        vm.update { copy(bible = bible.copy(loading = true, error = null, today = today.toString())) }
        vm.runResult({ vm.repo.bibleReport(query) }, { report ->
            vm.update { copy(bible = bible.copy(loading = false, report = report, run = pending)) }
        }, { error ->
            vm.update {
                copy(bible = bible.copy(loading = false, error = error.localised().ifBlank { RUN_FAILED }))
            }
        })
    }

    /**
     * The server-rendered file — `POST /bible/export/{format}`.
     *
     * The filters of the run on screen, not the bar's current ones, so the file
     * an accountant sends is the table they were looking at.
     */
    private fun export(format: ExportFormat) {
        val exporter = vm.exporter ?: return
        val files = vm.files ?: return
        val snapshot = vm.setupState
        val shown = snapshot.bible.run ?: return
        if (snapshot.bible.exporting != null) return
        val body = shown.query.toExportBody(snapshot.projectName, shown.companyName, shown.periodLabel)
        vm.update { copy(bible = bible.copy(exporting = format, exportOpen = false)) }
        vm.launchWork {
            when (val bytes = exporter.export(HubExportReport.Bible, format, body)) {
                is ZillitResult.Failure -> vm.report(bytes.error)
                is ZillitResult.Success -> {
                    val stamp = BibleFormat.exportStamp(nowMillis(), zone())
                    val name = "${HubExportReport.Bible.fileStem}_$stamp.${format.extension}"
                    when (val saved = files.saveAndOpen(name, bytes.data)) {
                        is ZillitResult.Failure -> vm.report(saved.error)
                        is ZillitResult.Success -> vm.update { copy(notice = "Exported $name.") }
                    }
                }
            }
            vm.update { copy(bible = bible.copy(exporting = null)) }
        }
    }

    private fun today(): LocalDate = BiblePeriod.today(nowMillis(), zone())

    /** The host's clock; the system's when a host passes none, as tests of other screens do. */
    private fun nowMillis(): Long =
        vm.nowMillis().takeIf { it > 0 } ?: kotlin.time.Clock.System.now().toEpochMilliseconds()

    private companion object {
        /** The web's words when a run fails without a message of its own. */
        const val RUN_FAILED = "Failed to run Bible Report"
    }
}
