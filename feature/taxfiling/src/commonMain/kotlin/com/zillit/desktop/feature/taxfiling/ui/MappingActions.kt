package com.zillit.desktop.feature.taxfiling.ui

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.taxfiling.domain.BoxMapping
import com.zillit.desktop.feature.taxfiling.domain.TaxRegistration
import com.zillit.desktop.feature.taxfiling.domain.VatBox
import com.zillit.desktop.feature.taxfiling.domain.VatDraft
import com.zillit.desktop.feature.taxfiling.domain.ledgerFileName
import com.zillit.desktop.feature.taxfiling.domain.ledgerWorkbook

/**
 * The box mapping and what it produces: editing it, saving it, calculating the
 * return from it and exporting the ledger behind it — and loading the three
 * account-hub lists it is picked from.
 */
internal class MappingActions(private val vm: TaxFilingViewModel) {

    fun onEvent(event: TaxFilingEvent): Boolean {
        when (event) {
            is TaxFilingEvent.EditMapping -> edit(event.mapping)
            is TaxFilingEvent.ToggleBox -> vm.update {
                val open = returnState.expanded
                val next = if (event.box in open) open - event.box else open + event.box
                copy(returnState = returnState.copy(expanded = next))
            }
            TaxFilingEvent.ToggleAllBoxes -> vm.update {
                val next = if (returnState.anyCollapsed) VatBox.mappable.toSet() else emptySet()
                copy(returnState = returnState.copy(expanded = next))
            }
            TaxFilingEvent.SaveMapping -> save()
            TaxFilingEvent.Calculate -> calculate()
            TaxFilingEvent.ExportLedger -> exportLedger()
            else -> return false
        }
        return true
    }

    /** The company's saved boxes; a failure leaves the boxes empty and says why. */
    fun loadMap(registration: TaxRegistration) {
        vm.request({ vm.repository.boxMap(registration.companyId) }, { rows ->
            if (!isOpen(registration.id)) return@request
            val byBox = rows.mapNotNull { row -> VatBox.bySlot(row.box)?.let { it to row } }.toMap()
            vm.update { copy(returnState = returnState.copy(mappings = byBox, mappingLoading = false)) }
        }, { error ->
            if (isOpen(registration.id)) vm.update { copy(returnState = returnState.copy(mappingLoading = false)) }
            vm.fail(error)
        })
    }

    /**
     * The chart, the layers and the tags, once per production.
     *
     * Only the chart's failure is kept, for the code picker to explain; the web
     * treats layers as optional and tags as a list that may simply be empty.
     */
    fun loadLookups() {
        val lookups = vm.current.lookups
        if (lookups.loaded || lookups.coaLoading) return
        vm.update { copy(lookups = this.lookups.copy(loaded = true, coaLoading = true, coaFailed = false)) }
        vm.request({ vm.repository.coaCodes() }, { rows ->
            vm.update { copy(lookups = this.lookups.copy(coa = rows, coaLoading = false)) }
        }, {
            // Kept retryable: the next return opened asks again.
            vm.update { copy(lookups = this.lookups.copy(coaLoading = false, coaFailed = true, loaded = false)) }
        })
        vm.request({ vm.repository.layerSets() }, { rows ->
            vm.update { copy(lookups = this.lookups.copy(layerSets = rows)) }
        }, { })
        vm.request({ vm.repository.assetTags() }, { rows ->
            vm.update { copy(lookups = this.lookups.copy(assetTags = rows)) }
        }, { })
    }

    private fun isOpen(registrationId: String) = vm.current.returnState.registration?.id == registrationId

    /**
     * A change to one box.
     *
     * A draft already built stays on screen, as it does on the web, but is
     * marked stale: its figures describe the mapping before this edit, and
     * they are not submitted until they are recalculated.
     */
    private fun edit(mapping: BoxMapping) {
        val box = VatBox.bySlot(mapping.box)?.takeUnless { it.computed } ?: return
        val next = mapping.copy(box = box.slot)
        vm.update {
            if (returnState.mappingFor(box) == next) return@update this
            copy(
                returnState = returnState.copy(
                    mappings = returnState.mappings + (box to next),
                    draftStale = returnState.draft != null,
                ),
            )
        }
    }

    /** A date typed but not a date would be saved as no date at all — so it is not saved. */
    private fun refuseInvalidDates(state: ReturnState): Boolean {
        val box = state.invalidDateBox ?: return false
        vm.info("Box ${box.number} has a date that isn't a valid date. Use YYYY-MM-DD, or clear it.")
        return true
    }

    private fun save() {
        val state = vm.current.returnState
        val registration = state.registration ?: return
        if (state.savingMapping || state.calculating || refuseInvalidDates(state)) return
        vm.update { copy(returnState = returnState.copy(savingMapping = true)) }
        vm.request({ vm.repository.saveBoxMap(registration.companyId, state.mappingRows) }, {
            if (isOpen(registration.id)) vm.update { copy(returnState = returnState.copy(savingMapping = false)) }
            vm.toast("Box mapping saved for ${registration.companyName}")
        }, { error ->
            if (isOpen(registration.id)) vm.update { copy(returnState = returnState.copy(savingMapping = false)) }
            vm.fail(error)
        })
    }

    /**
     * Saves the mapping, then builds the return from it.
     *
     * In that order and never the other way: a draft built from a mapping the
     * server has not been told about is figures nobody can reproduce.
     */
    private fun calculate() {
        val state = vm.current.returnState
        val registration = state.registration ?: return
        if (state.calculating || state.savingMapping) return
        if (state.periodKey.isBlank()) return vm.info("Select an obligation period first.")
        if (refuseInvalidDates(state)) return
        val rows = state.mappingRows
        val periodKey = state.periodKey

        vm.update { copy(returnState = returnState.copy(calculating = true)) }
        vm.request({ vm.repository.saveBoxMap(registration.companyId, rows) }, {
            vm.request({ vm.repository.buildDraft(registration.id, periodKey) }, { built ->
                applyDraft(registration.id, periodKey, rows, built)
            }, ::stopCalculating)
        }, ::stopCalculating)
    }

    private fun applyDraft(registrationId: String, periodKey: String, rows: List<BoxMapping>, built: VatDraft) {
        val current = vm.current.returnState
        if (!isOpen(registrationId) || current.periodKey != periodKey) {
            if (isOpen(registrationId)) vm.update { copy(returnState = returnState.copy(calculating = false)) }
            return
        }
        vm.update {
            copy(
                returnState = returnState.copy(
                    draft = built.vatReturn,
                    diagnostics = built.diagnostics,
                    calculating = false,
                    // Edited while the draft was being built: shown, but stale.
                    draftStale = returnState.mappingRows != rows,
                ),
            )
        }
        if (built.isAllZero) vm.info(built.allZeroExplanation) else vm.toast("Recalculated from ledger")
    }

    private fun stopCalculating(error: ZillitError) {
        vm.update { copy(returnState = returnState.copy(calculating = false)) }
        vm.fail(error)
    }

    /**
     * The ledger lines behind the boxes, as a workbook.
     *
     * The answer to "why is box 6 that figure", and the reason it is offered
     * beside the draft rather than after filing: a mapping is checked against
     * the rows it selected, not against a total.
     */
    private fun exportLedger() {
        val state = vm.current.returnState
        val registration = state.registration ?: return
        if (state.periodKey.isBlank()) return vm.info("Select an obligation period first.")
        val sink = vm.fileSink ?: return vm.fail("This installation cannot save files.")
        if (state.exporting) return
        val periodKey = state.periodKey

        vm.update { copy(returnState = returnState.copy(exporting = true)) }
        vm.request({ vm.repository.ledgerLines(registration.id, periodKey) }, { rows ->
            if (rows.isEmpty()) {
                vm.update { copy(returnState = returnState.copy(exporting = false)) }
                return@request vm.info("No ledger rows to export for this period.")
            }
            vm.work {
                val name = ledgerFileName(periodKey, vm.exportStamp())
                val saved = sink.save(name, ledgerWorkbook(rows), open = true)
                vm.update { copy(returnState = returnState.copy(exporting = false)) }
                when (saved) {
                    is ZillitResult.Success -> vm.toast("Exported ledger to .xlsx")
                    is ZillitResult.Failure -> vm.fail(saved.error)
                }
            }
        }, { error ->
            vm.update { copy(returnState = returnState.copy(exporting = false)) }
            vm.fail(error)
        })
    }
}
