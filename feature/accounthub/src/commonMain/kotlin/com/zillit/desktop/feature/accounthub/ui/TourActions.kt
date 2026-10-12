package com.zillit.desktop.feature.accounthub.ui

import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.SetupTour
import kotlinx.coroutines.delay

/**
 * The setup tour — "what still needs configuring", shown once per production.
 *
 * The web's `SetupTour` on the hub shell: it waits for the settings to land,
 * resolves the gaps, and opens 800 ms later unless this production has seen
 * it. It is suppressed while Production Setup itself is on screen — the
 * person is already where the tour would send them — and any exit marks it
 * seen, so a dismissed tour does not come back on the next click. Only the
 * accounts team is offered it.
 */
internal class TourActions(
    private val vm: AccountHubViewModel,
    private val projectId: () -> String,
    private val tourSeen: suspend (String) -> Boolean,
    private val markTourSeen: suspend (String) -> Unit,
) {

    private var offered = false

    fun onEvent(event: AccountHubEvent): Boolean {
        when (event) {
            AccountHubEvent.TourNext -> next()
            AccountHubEvent.TourBack -> vm.update {
                copy(tour = tour.copy(index = (tour.index - 1).coerceAtLeast(0)))
            }
            AccountHubEvent.TourClose -> close()
            else -> return false
        }
        return true
    }

    /**
     * The settings have been asked for: once the slices the tour judges have
     * answered, decide whether there is anything to show.
     *
     * The web's `ready` is "not loading, no error, settings present", so a read
     * that is slow or failed means *unknown* and flags nothing. Waiting on the
     * slice record, not a timer, keeps a configured production from being told
     * that nothing is set up — and from marking that wrong tour as seen.
     */
    fun onSetupLoaded() {
        // The web shows it to the accounts team only (`isAccountant && showHubSidebar`); Admin Settings'
        // Companies page loads the same slices for any admin and must not arm it.
        if (offered || !vm.setupState.viewer.isAccountant) return
        offered = true
        vm.launchWork {
            if (!awaitTourSlices()) {
                offered = false
                return@launchWork
            }
            val state = vm.setupState
            val gaps = SetupTour.gaps(state.setup.snapshot(coaReady = state.chart.loaded, coaEmpty = state.chart.isEmpty))
            if (gaps.isEmpty()) return@launchWork
            val key = SetupTour.seenKey(projectId())
            if (tourSeen(key)) return@launchWork
            delay(SetupTour.OPEN_DELAY_MS)
            // Not over Production Setup: the web suppresses the tour there,
            // where the person is already doing what it would ask.
            if (vm.setupState.area == HubArea.ProductionSetup && vm.setupState.embedded == null) {
                pending = gaps
                return@launchWork
            }
            vm.update { copy(tour = TourState(open = true, intro = true, steps = gaps)) }
        }
    }

    /** True when every slice the tour reads has loaded; false when one failed or never answered. */
    private suspend fun awaitTourSlices(): Boolean {
        val deadline = SLICE_WAIT_MS / SLICE_POLL_MS
        repeat(deadline.toInt()) {
            val slices = vm.setupState.setup.slices
            if (TOUR_SLICES.all(slices::isLoaded)) return true
            if (TOUR_SLICES.any { slices.failure(it) != null }) return false
            delay(SLICE_POLL_MS)
        }
        return false
    }

    /** Forgets this production's offer, for the next one the console opens on. */
    fun reset() {
        offered = false
        pending = null
    }

    /** A tour held back on Production Setup opens when the person leaves it. */
    fun onAreaOpened(area: HubArea) {
        val steps = pending ?: return
        if (area == HubArea.ProductionSetup) return
        pending = null
        vm.update { copy(tour = TourState(open = true, intro = true, steps = steps)) }
    }

    /** A tool now covers Production Setup, which counts as leaving it. */
    fun onToolShown() {
        val steps = pending ?: return
        pending = null
        vm.update { copy(tour = TourState(open = true, intro = true, steps = steps)) }
    }

    private var pending: List<com.zillit.desktop.feature.accounthub.domain.SetupGap>? = null

    private fun next() {
        val tour = vm.setupState.tour
        when {
            tour.intro -> vm.update { copy(tour = tour.copy(intro = false, index = 0)) }
            tour.isLast -> close()
            else -> vm.update { copy(tour = tour.copy(index = tour.index + 1)) }
        }
    }

    /** Any exit marks the tour seen — the web's `markSetupTourSeen` on close. */
    private fun close() {
        vm.update { copy(tour = TourState()) }
        vm.launchWork { markTourSeen(SetupTour.seenKey(projectId())) }
    }

    private companion object {
        /** The sections the production gaps are judged from (banks and the chart are tracked on their own). */
        val TOUR_SLICES = listOf(
            SetupSection.Companies,
            SetupSection.Currencies,
            SetupSection.TaxTypes,
            SetupSection.AssetTags,
        )
        const val SLICE_WAIT_MS = 15_000L
        const val SLICE_POLL_MS = 200L
    }
}
