package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.common.ZillitResult
import kotlinx.coroutines.Job

/**
 * The three read-only tabs: Overview, Approval Queue and My Deal.
 *
 * Each loads on entry with a loader the first time and silently after — a
 * socket refresh never flashes a spinner over what is already on screen.
 */
internal class TabPageActions(private val vm: DealMemoViewModel) {

    private var overviewJob: Job? = null
    private var queueJob: Job? = null
    private var myDealJob: Job? = null

    fun onEvent(event: DealMemoEvent) {
        when (event) {
            OverviewEvent.Retry -> loadOverview(silent = false)
            MyDealEvent.Retry -> loadMyDeal(silent = false)
            else -> Unit
        }
    }

    fun enterOverview() = loadOverview(silent = vm.ui.overview.data != null)

    fun reloadOverview() = loadOverview(silent = true)

    fun enterQueue() = loadQueue(silent = vm.ui.queue.loaded)

    fun reloadQueue() = loadQueue(silent = true)

    /** My Deal reads its whole tab's badge on entry, and the deal's own once it is known. */
    fun enterMyDeal() {
        vm.badges?.readTab(DealBadgeUnit.MyDeal)
        loadMyDeal(silent = vm.ui.myDeal.loaded && !vm.ui.myDeal.failed)
    }

    fun reloadMyDeal() = loadMyDeal(silent = true)

    /** `/my-deal/complete`: My Deal's deal, then the form over it — or back to My Deal when it is locked. */
    fun enterCompleteDetails() {
        val deal = vm.ui.myDeal.deal
        if (deal != null && vm.ui.preview?.dealId == deal.id) {
            vm.preview.crew.attachRoute()
        } else {
            loadMyDeal(silent = vm.ui.myDeal.loaded && !vm.ui.myDeal.failed, thenOpenForm = true)
        }
    }

    /** Every failure toasts; a failure with data already on screen keeps it. */
    private fun loadOverview(silent: Boolean) {
        overviewJob?.cancel()
        if (!silent) vm.update { copy(overview = overview.copy(loading = true, failed = false)) }
        overviewJob = vm.work {
            when (val result = vm.repository.overview()) {
                is ZillitResult.Success -> vm.update {
                    copy(overview = OverviewState(data = result.data))
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "failed_to_load_overview")
                    vm.update { copy(overview = overview.copy(loading = false, failed = true)) }
                }
            }
        }
    }

    /** A failed load toasts and leaves the list as it was. */
    private fun loadQueue(silent: Boolean) {
        queueJob?.cancel()
        if (!silent) vm.update { copy(queue = queue.copy(loading = true)) }
        queueJob = vm.work {
            when (val result = vm.repository.approvalQueue()) {
                is ZillitResult.Success -> vm.update { copy(queue = QueueState(rows = result.data, loaded = true)) }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "failed_to_load_approval_queue")
                    vm.update { copy(queue = queue.copy(loading = false, loaded = true)) }
                }
            }
        }
    }

    /**
     * The viewer's own deal. A failed first load shows the retry screen; a
     * failed background refresh toasts but keeps the memo that is already
     * open, rather than replacing it with an error as the web does.
     */
    private fun loadMyDeal(silent: Boolean, thenOpenForm: Boolean = false) {
        myDealJob?.cancel()
        if (!silent) vm.update { copy(myDeal = myDeal.copy(loading = true, failed = false)) }
        myDealJob = vm.work {
            when (val result = vm.repository.myDeal()) {
                is ZillitResult.Success -> {
                    val deal = result.data
                    vm.update { copy(myDeal = MyDealState(deal = deal, loaded = true)) }
                    deal?.id?.takeIf { it.isNotBlank() }?.let { vm.badges?.readDeal(DealBadgeUnit.MyDeal, it) }
                    if (deal != null) vm.preview.embed(deal)
                    if (thenOpenForm) {
                        if (deal != null) {
                            vm.preview.crew.attachRoute()
                        } else {
                            vm.navigate(DealMemoRoute.Tab(DealTab.MyDeal))
                        }
                    }
                }
                is ZillitResult.Failure -> {
                    vm.toastError(result.error, "failed_to_load_your_deal_memo")
                    vm.update {
                        val keep = silent && myDeal.deal != null
                        copy(myDeal = myDeal.copy(loading = false, loaded = true, failed = !keep))
                    }
                }
            }
        }
    }
}
