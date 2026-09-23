package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRights

/**
 * The tabs of the deal memo console, in the web's order (`DealMemoModule.jsx`
 * `ALL_TABS`).
 *
 * Overview, All Deals and Notices are the management surfaces — posting users
 * only. My Deal is everyone's. Approval Queue appears only when the metadata
 * says this person approves.
 */
enum class DealTab(
    val slug: String,
    private val labelKey: String,
    val requiresPost: Boolean = false,
    val approversOnly: Boolean = false,
) {
    Overview("overview", S.dm_tab_overview, requiresPost = true),
    Deals("deals", S.dm_tab_deals, requiresPost = true),
    MyDeal("my-deal", S.dm_tab_my_deal),
    ApprovalQueue("approval-queue", S.dm_tab_approval_queue, approversOnly = true),
    Notices("notices", S.dm_tab_notices, requiresPost = true),
    ;

    val label: String get() = str(labelKey)

    /**
     * Whether this tab is offered — the web's `visibleTabs` filter, condition
     * for condition. A pending member sees My Deal and nothing else, checked
     * first because Approval Queue rides a metadata flag the server can set on
     * a member before they join.
     */
    fun visibleTo(rights: DealMemoRights, metadata: DealMemoMetadata): Boolean = when {
        rights.isPending && this != MyDeal -> false
        requiresPost && !rights.canPost -> false
        approversOnly && !metadata.isApprover -> false
        else -> true
    }

    companion object {
        fun fromSlug(slug: String): DealTab? = entries.firstOrNull { it.slug == slug }

        /** Posting users land on All Deals, everyone else on their own deal. */
        fun defaultFor(rights: DealMemoRights): DealTab = if (rights.canPost) Deals else MyDeal

        fun visible(rights: DealMemoRights, metadata: DealMemoMetadata): List<DealTab> =
            entries.filter { it.visibleTo(rights, metadata) }
    }
}

/** Which kind of deal-memo setup — the Create menu's two items and the Setup Hub's two lists. */
enum class SetupGroup(val wire: String, val slug: String, private val labelKey: String) {
    Union("union", "union", S.dm_label_union),
    NonUnion("non_union", "non-union", S.dm_create_non_union),
    ;

    val label: String get() = str(labelKey)

    companion object {
        fun fromSlug(slug: String?): SetupGroup? = entries.firstOrNull { it.slug == slug }

        fun fromWire(wire: String?): SetupGroup? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Where the deal memo tool is — one value per page the web's module router
 * dispatches on (`DealMemoModule.jsx` `DealMemoModule`).
 *
 * What the web carries in router state (the setup group a Create menu item
 * picked, the template a "Use" prefills from, the page to return to) travels
 * on the route itself here, so a route is enough to rebuild a page.
 */
sealed interface DealMemoRoute {

    /** The tab shell: Overview, All Deals, My Deal, Approval Queue, Notices. */
    data class Tab(val tab: DealTab) : DealMemoRoute

    /**
     * A deal's full preview — `/deals/:id`. [from] is the list it was opened
     * from, whose badge the preview reads (the web's `fromUnit` router state).
     */
    data class Deal(val dealId: String, val from: DealBadgeUnit? = null) : DealMemoRoute

    /** The step-by-step wizard for a new deal — `/new`, optionally prefilled from a template. */
    data class NewDeal(val templateId: String? = null) : DealMemoRoute

    /** The wizard authoring a template — `/templates/new` and `/templates/:id/edit`. */
    data class TemplateWizard(val templateId: String? = null) : DealMemoRoute

    /**
     * The one-page builder creating a deal from a setup — `/quick`, entered
     * from the Create menu with the group picked there.
     */
    data class QuickDeal(
        val group: SetupGroup? = null,
        val templateId: String? = null,
        val exitTo: DealMemoRoute? = null,
    ) : DealMemoRoute

    /** The one-page builder editing a saved deal — `/edit`. */
    data class EditDeal(val dealId: String, val exitTo: DealMemoRoute? = null) : DealMemoRoute

    /**
     * The one-page template builder — `/templates/build` and `/templates/:id/build`.
     * [from] is the page Back returns to (the web's history entry).
     */
    data class TemplateBuilder(
        val templateId: String? = null,
        val group: SetupGroup? = null,
        val from: DealMemoRoute? = null,
    ) : DealMemoRoute

    /** First-run Deal Memo Setup, reached only from the "Set up Deal Memo first" prompt — `/first-setup`. */
    data object FirstSetup : DealMemoRoute

    /**
     * The Deal Memo Setup hub and the setups inside it —
     * `/setup-hub[/(union|non-union)[/(new|:id)]]`.
     *
     * [setupId] "new" is a new setup of [group]; any other id is that setup
     * open for editing. Back from a setup returns to its own group's list.
     */
    data class SetupHub(val group: SetupGroup? = null, val setupId: String? = null) : DealMemoRoute {
        val isNewSetup: Boolean get() = setupId == NEW
        val editsSetup: Boolean get() = setupId != null && setupId != NEW
    }

    /** Global Production Rates — unions, branches, agreements and designation rates. */
    data object GlobalRates : DealMemoRoute

    /** The notice template editor — `/notice-template`. */
    data object NoticeTemplate : DealMemoRoute

    /** "Complete your details" for the viewer's own deal — `/my-deal/complete`. */
    data object CompleteDetails : DealMemoRoute

    /**
     * Whether only posting users may be here — the web bounces everyone else
     * to My Deal (`DealMemoModule.jsx` `isPostOnlyPath`). The deal preview is
     * not on that list: a crew member opens their own deal by id.
     */
    val postOnly: Boolean
        get() = when (this) {
            is NewDeal, is TemplateWizard, is QuickDeal, is EditDeal, is TemplateBuilder,
            FirstSetup, is SetupHub, GlobalRates, NoticeTemplate,
            -> true

            is Tab -> tab.requiresPost
            is Deal, CompleteDetails -> false
        }

    /** A page of the one-page builder: a deal, or one setup (not the hub's lists). */
    val isBuilder: Boolean
        get() = this is QuickDeal || this is EditDeal || this is TemplateBuilder ||
            (this is SetupHub && setupId != null)

    /** The route's path under the tool, for the window's address. */
    val tail: String
        get() = when (this) {
            is Tab -> "/${tab.slug}"
            is Deal -> "/deals/$dealId"
            is NewDeal -> "/new"
            is TemplateWizard -> templateId?.let { "/templates/$it/edit" } ?: "/templates/new"
            is QuickDeal -> "/quick"
            is EditDeal -> "/edit"
            is TemplateBuilder -> templateId?.let { "/templates/$it/build" } ?: "/templates/build"
            FirstSetup -> "/first-setup"
            is SetupHub -> buildString {
                append("/setup-hub")
                if (group != null) append("/${group.slug}")
                if (group != null && setupId != null) append("/$setupId")
            }
            GlobalRates -> "/global-production-rates"
            NoticeTemplate -> "/notice-template"
            CompleteDetails -> "/my-deal/complete"
        }

    companion object {
        const val NEW = "new"

        private val SETUP_HUB = Regex("^/setup-hub(?:/(union|non-union)(?:/([^/]+))?)?$")
        private val TEMPLATE_EDIT = Regex("^/templates/([^/]+)/edit$")
        private val TEMPLATE_BUILD = Regex("^/templates/([^/]+)/build$")
        private val DEAL = Regex("^/deals/([^/]+)$")

        /**
         * The page a path under the tool names, or null for the bare tool —
         * which lands on the viewer's default tab.
         *
         * An unknown tail is the tab shell too, as it is on the web: its router
         * falls through to the shell, which then redirects to the default tab.
         */
        @Suppress("CyclomaticComplexMethod", "ReturnCount") // One return per route shape.
        fun parse(tail: String): DealMemoRoute? {
            val path = tail.trim().trimEnd('/').substringBefore('?')
            if (path.isEmpty()) return null
            DealTab.fromSlug(path.removePrefix("/"))?.let { return Tab(it) }
            SETUP_HUB.matchEntire(path)?.let { match ->
                val group = SetupGroup.fromSlug(match.groupValues[1].ifEmpty { null })
                return SetupHub(group, match.groupValues[2].ifEmpty { null })
            }
            TEMPLATE_EDIT.matchEntire(path)?.let { return TemplateWizard(it.groupValues[1]) }
            TEMPLATE_BUILD.matchEntire(path)?.let { return TemplateBuilder(it.groupValues[1]) }
            DEAL.matchEntire(path)?.let { return Deal(it.groupValues[1]) }
            return when (path) {
                "/new" -> NewDeal()
                "/templates/new" -> TemplateWizard()
                "/templates/build" -> TemplateBuilder()
                "/quick" -> QuickDeal()
                "/first-setup" -> FirstSetup
                "/global-production-rates" -> GlobalRates
                "/notice-template" -> NoticeTemplate
                "/my-deal/complete" -> CompleteDetails
                else -> null
            }
        }

        /**
         * Where [route] actually lands for this viewer — the web's two bounce
         * effects. A viewer who may not post is sent from a posting page to My
         * Deal; a hidden tab goes to the default tab; the bare tool goes to the
         * default tab too. Nothing is bounced before the rights have loaded,
         * so an accountant is not flashed My Deal while the grid is on its way.
         */
        fun resolve(route: DealMemoRoute?, rights: DealMemoRights, metadata: DealMemoMetadata): DealMemoRoute {
            val default = Tab(DealTab.defaultFor(rights))
            return when {
                route == null -> default
                !rights.rightsLoaded -> route
                route is Tab && !route.tab.visibleTo(rights, metadata) -> default
                route !is Tab && route.postOnly && !rights.canPost -> Tab(DealTab.MyDeal)
                rights.isPending && route !is Tab && route != CompleteDetails && route !is Deal -> Tab(DealTab.MyDeal)
                else -> route
            }
        }
    }
}
