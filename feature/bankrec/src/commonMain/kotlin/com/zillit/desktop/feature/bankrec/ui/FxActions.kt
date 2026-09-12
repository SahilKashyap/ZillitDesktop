package com.zillit.desktop.feature.bankrec.ui

import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.bankrec.domain.FxPosting
import com.zillit.desktop.feature.bankrec.domain.FxRates
import com.zillit.desktop.feature.bankrec.domain.FxVariance

/**
 * What a foreign payment cost against what it was budgeted at.
 *
 * ## Posting all goes row by row, on purpose
 *
 * The service has a `post-all` route, and the web stopped using it: it takes
 * only a period, so it cannot know a budget rate the screen resolved from
 * Production Setup — or one somebody typed — and it posts its own figures
 * while the table advertises a variance nobody stored. One post per row keeps
 * "what you see is what posts"; the price is partial failure, which is
 * reported by count rather than hidden.
 */
internal class FxActions(private val vm: BankRecViewModel) {

    private val page: FxPageState get() = vm.ui.fxPage

    private fun edit(reducer: FxPageState.() -> FxPageState) = vm.update { copy(fxPage = fxPage.reducer()) }

    fun onEvent(event: BankRecEvent): Boolean {
        when (event) {
            is BankRecEvent.SetFxPeriod -> edit { copy(periodChoice = event.choice, postAllMessage = null) }
            is BankRecEvent.OpenFxPost -> open(event.varianceId)
            is BankRecEvent.EditFxPost -> edit {
                copy(
                    post = post?.copy(
                        nominalCode = event.nominalCode,
                        costCentre = event.costCentre,
                        budgetRate = event.budgetRate,
                        bankRate = event.bankRate,
                    ),
                )
            }

            BankRecEvent.CloseFxPost -> if (page.post?.posting != true) edit { copy(post = null) }
            BankRecEvent.ConfirmFxPost -> post()
            BankRecEvent.PostAllFx -> postAll()
            else -> return false
        }
        return true
    }

    /** The rows the tab shows: the chosen open period's, or every one. */
    fun visibleRows(): List<FxVariance> {
        val state = vm.ui
        val periodId = resolvePeriodChoice(page.periodChoice, state.openPeriods)
        return if (periodId == ALL_PERIODS) state.fxVariances else state.fxVariances.filter { it.periodId == periodId }
    }

    /**
     * Seeds the dialog from the resolution — Production Setup, the stored rate,
     * or blank — so an already-rated row is one click to post and an unrated one
     * lands in an empty field instead of silently posting at one to one.
     */
    private fun open(id: String) {
        val row = vm.ui.fxVariances.firstOrNull { it.id == id } ?: return
        val resolved = FxRates.resolve(row, vm.ui.rates)
        edit {
            copy(
                post = FxPostState(
                    varianceId = id,
                    nominalCode = row.nominalCode.ifBlank { FxPosting.DEFAULT_NOMINAL },
                    costCentre = row.costCentre,
                    budgetRate = resolved.budget?.toString().orEmpty(),
                    bankRate = resolved.bank?.toString().orEmpty(),
                ),
            )
        }
    }

    private fun post() {
        val dialog = page.post ?: return
        if (dialog.posting || dialog.posted) return
        if (!dialog.ready) return vm.refuse("Enter both rates to post.")
        edit { copy(post = dialog.copy(posting = true)) }
        val posting = FxPosting(
            nominalCode = dialog.nominalCode.trim(),
            costCentre = dialog.costCentre.trim(),
            budgetRate = dialog.budgetRateValue,
            bankRate = dialog.bankRateValue,
        )
        vm.runResult({ vm.repo.postFxVariance(dialog.varianceId, posting) }, {
            edit { copy(post = post?.copy(posting = false, posted = true)) }
            vm.loadFxVariances()
            vm.loadPeriods()
            // Shown as posted for a moment, then closed — and only closed if
            // it is still this dialog: the list re-reading underneath must
            // not take an open dialog with it.
            vm.after(POSTED_MILLIS) {
                if (page.post?.varianceId == dialog.varianceId) edit { copy(post = null) }
            }
        }, { error ->
            edit { copy(post = post?.copy(posting = false)) }
            vm.report(error)
        })
    }

    private fun postAll() {
        if (page.postingAll) return
        val rates = vm.ui.rates
        val unposted = visibleRows().filterNot { it.isPosted }
        if (unposted.isEmpty()) return
        val blocking = unposted.filterNot { FxRates.resolve(it, rates).ok }
        if (blocking.isNotEmpty()) {
            return vm.refuse("Some rows are missing a budget or bank rate.")
        }
        edit { copy(postingAll = true, postAllMessage = null) }
        vm.launchWork {
            var posted = 0
            var lastFailure: ZillitResult.Failure? = null
            unposted.forEach { row ->
                val resolved = FxRates.resolve(row, rates)
                val outcome = vm.repo.postFxVariance(
                    row.id,
                    FxPosting(
                        nominalCode = row.nominalCode.ifBlank { FxPosting.DEFAULT_NOMINAL },
                        costCentre = row.costCentre,
                        budgetRate = resolved.budget,
                        bankRate = resolved.bank,
                    ),
                )
                if (outcome is ZillitResult.Failure) lastFailure = outcome else posted++
            }
            val failed = unposted.size - posted
            lastFailure?.let { vm.report(it.error) }
            edit {
                copy(
                    postingAll = false,
                    postAllMessage = if (failed > 0) {
                        "Posted $posted of ${unposted.size} — $failed failed"
                    } else {
                        "Posted $posted of ${unposted.size}"
                    },
                )
            }
            vm.loadFxVariances()
            vm.loadPeriods()
        }
    }

    private companion object {
        const val POSTED_MILLIS = 1_200L
    }
}
