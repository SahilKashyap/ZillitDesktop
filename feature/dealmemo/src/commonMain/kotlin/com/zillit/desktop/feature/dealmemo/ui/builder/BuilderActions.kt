package com.zillit.desktop.feature.dealmemo.ui.builder

import com.zillit.desktop.feature.dealmemo.ui.BuilderEvent
import com.zillit.desktop.feature.dealmemo.ui.DealMemoRoute
import com.zillit.desktop.feature.dealmemo.ui.DealMemoViewModel
import com.zillit.desktop.feature.dealmemo.ui.DealTab
import com.zillit.desktop.feature.dealmemo.ui.RulesEvent
import com.zillit.desktop.feature.dealmemo.ui.SetupGroup

/**
 * The one-page builder's pages (`DMTemplateBuilderPage.jsx`): which one a
 * route opens, the visit that owns it, and the events it answers.
 *
 * Each visit is its own [BuilderSession] — its minted ids, its seeds and its
 * autosave — so a save still landing from the page just left can never write
 * into the one just opened.
 */
internal class BuilderActions(private val vm: DealMemoViewModel) {

    val references = BuilderReferences(vm, this)

    private var session: BuilderSession? = null
    private var visits = 0L

    /** The route opens a builder page: a new visit unless it is the page already open. */
    fun enter(route: DealMemoRoute) {
        val mode = modeOf(route) ?: return
        enter(mode)
    }

    /** The Setup Hub's empty tab shows a new setup of [group] inline. */
    fun embed(group: SetupGroup) = enter(
        BuilderMode(deal = false, lockedGroup = group, embedded = true, exitTo = DealMemoRoute.SetupHub(group)),
    )

    private fun enter(mode: BuilderMode) {
        val current = session
        if (current != null && current.mode == mode && vm.ui.builder?.visit == current.id) {
            current.reconcile()
            return
        }
        leave()
        visits += 1
        references.reset()
        val next = BuilderSession(vm, this, visits, mode)
        session = next
        next.start()
    }

    /** Away from the builder: one last autosave for the page being left, then it's gone. */
    fun leave() {
        val current = session ?: return
        session = null
        current.stop(flush = true)
        vm.update { if (builder?.visit == current.id) copy(builder = null) else this }
    }

    /** A production switch: nothing in flight is saved into the next production. */
    fun reset() {
        session?.stop(flush = false)
        session = null
        references.clear()
        vm.update { copy(builder = null) }
    }

    fun onEvent(event: BuilderEvent) {
        session?.onEvent(event)
    }

    /** The rules grid, while a builder page has it open. */
    fun onRules(event: RulesEvent) {
        session?.onRules(event)
    }

    /** Re-runs the page's effects after something it reads arrived — settings, setups, the directory. */
    fun reconcile() {
        session?.reconcile()
    }

    private fun modeOf(route: DealMemoRoute): BuilderMode? = when (route) {
        is DealMemoRoute.QuickDeal -> BuilderMode(
            deal = true,
            dealGroup = route.group,
            useTemplateId = route.templateId,
            exitTo = route.exitTo,
            backTo = route.exitTo,
        )
        is DealMemoRoute.EditDeal ->
            BuilderMode(deal = true, dealId = route.dealId, exitTo = route.exitTo, backTo = route.exitTo)
        is DealMemoRoute.TemplateBuilder -> BuilderMode(
            deal = false,
            templateId = route.templateId,
            lockedGroup = route.group.takeIf { route.templateId == null },
            backTo = route.from,
        )
        is DealMemoRoute.SetupHub -> when {
            route.isNewSetup -> BuilderMode(
                deal = false,
                lockedGroup = route.group,
                exitTo = DealMemoRoute.SetupHub(route.group),
                backTo = DealMemoRoute.SetupHub(route.group),
            )
            route.editsSetup -> BuilderMode(
                deal = false,
                templateId = route.setupId,
                exitTo = DealMemoRoute.SetupHub(route.group),
                backTo = DealMemoRoute.SetupHub(route.group),
            )
            else -> null
        }
        else -> null
    }

    companion object {
        /** Where Back lands with nowhere recorded: All Deals for a deal, the hub for a setup. */
        fun fallbackExit(mode: BuilderMode): DealMemoRoute =
            if (mode.deal) DealMemoRoute.Tab(DealTab.Deals) else DealMemoRoute.SetupHub()
    }
}
