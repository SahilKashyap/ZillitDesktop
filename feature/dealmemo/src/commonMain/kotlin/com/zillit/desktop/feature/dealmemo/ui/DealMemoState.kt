package com.zillit.desktop.feature.dealmemo.ui

import com.zillit.desktop.feature.dealmemo.domain.DealCrewLabels
import com.zillit.desktop.feature.dealmemo.domain.DealCrewUser
import com.zillit.desktop.feature.dealmemo.domain.DealDoc
import com.zillit.desktop.feature.dealmemo.domain.DealExport
import com.zillit.desktop.feature.dealmemo.domain.DealHistoryEntry
import com.zillit.desktop.feature.dealmemo.domain.DealMemoMetadata
import com.zillit.desktop.feature.dealmemo.domain.DealMemoRights
import com.zillit.desktop.feature.dealmemo.domain.DealOverview
import com.zillit.desktop.feature.dealmemo.domain.DealPerson
import com.zillit.desktop.feature.dealmemo.domain.DealQuickFilter
import com.zillit.desktop.feature.dealmemo.domain.DealSort
import com.zillit.desktop.feature.dealmemo.domain.DealTemplate
import com.zillit.desktop.feature.dealmemo.domain.DepartmentCatalogue
import com.zillit.desktop.feature.dealmemo.domain.NoticeGroupKind
import com.zillit.desktop.feature.dealmemo.domain.NoticeRules
import com.zillit.desktop.feature.dealmemo.domain.authoring.ProjectSettingsView
import com.zillit.desktop.feature.dealmemo.ui.builder.BuilderState
import com.zillit.desktop.feature.dealmemo.ui.preview.CoaState
import com.zillit.desktop.feature.dealmemo.ui.preview.DealPreviewState
import com.zillit.desktop.feature.dealmemo.ui.preview.ProductionRefs

/** Who is looking, as the host resolved them. */
data class DealMemoViewer(
    val userId: String = "",
    val departmentIdentifier: String? = null,
    val hasPostingAccess: Boolean = false,
    val hasViewAccess: Boolean = false,
    /** Their standing on the production; only an explicit `pending` restricts. */
    val memberStatus: String? = null,
    /** The tool grid has answered — until then nothing is refused. */
    val rightsLoaded: Boolean = false,
) {
    val rights: DealMemoRights
        get() = DealMemoRights.resolve(
            rightsLoaded,
            hasPostingAccess,
            hasViewAccess,
            departmentIdentifier,
            memberStatus,
        )

    /** An accounts-department member — the nominal-coding banner's audience. */
    val isAccountant: Boolean get() = departmentIdentifier?.contains("accounts", ignoreCase = true) == true
}

/** The four badge units of the tool (`tool: "deal_memo_label"`). */
enum class DealBadgeUnit(val wire: String) {
    AllDeals("all_deals_label"),
    MyDeal("my_deal_label"),
    ApprovalQueue("approval_queue_label"),
    Notices("notices_label"),
}

/** Unread counts: per tab, and per deal inside a tab for the rows' red pills. */
data class DealBadgeCounts(
    val tabs: Map<DealBadgeUnit, Int> = emptyMap(),
    val perDeal: Map<DealBadgeUnit, Map<String, Int>> = emptyMap(),
) {
    fun tab(unit: DealBadgeUnit): Int = tabs[unit] ?: 0

    fun deal(unit: DealBadgeUnit, dealId: String): Int = perDeal[unit]?.get(dealId) ?: 0
}

/** A toast, as the web's antd message shows one. */
data class DealToast(val message: String, val tone: DealToastTone, val id: Long)

enum class DealToastTone { Success, Error, Loading }

/** Everything the deal memo tool is showing. */
data class DealMemoUiState(
    /** The page asked for — null until the default landing is resolved. */
    val route: DealMemoRoute? = null,
    val viewer: DealMemoViewer = DealMemoViewer(),
    val metadata: DealMemoMetadata = DealMemoMetadata.Unknown,
    val badges: DealBadgeCounts = DealBadgeCounts(),
    val catalogue: DepartmentCatalogue = DepartmentCatalogue(),
    val people: Map<String, DealPerson> = emptyMap(),
    /** The production's default contract currency — the KPI total's symbol. */
    val projectCurrency: String = "GBP",
    val toast: DealToast? = null,
    /** A pinned toast that stays until its work finishes — the start-forms export. */
    val progressToast: String? = null,
    val templates: TemplatesState = TemplatesState(),
    val deals: DealsState = DealsState(),
    val overview: OverviewState = OverviewState(),
    val queue: QueueState = QueueState(),
    val myDeal: MyDealState = MyDealState(),
    val notices: NoticesState = NoticesState(),
    val noticeTemplate: NoticeTemplateState = NoticeTemplateState(),
    val rates: GlobalRatesState = GlobalRatesState(),
    val history: HistoryState? = null,
    /** The deal open on its own page, or embedded in My Deal. */
    val preview: DealPreviewState? = null,
    val production: ProductionRefs = ProductionRefs(),
    val coa: CoaState = CoaState(),
    /** Production Setup's document, read once per entry and refreshed silently after. */
    val projectSettings: ProjectSettingsState = ProjectSettingsState(),
    /** Every production member with the ids a deal is scoped by; null until read. */
    val crewDirectory: List<DealCrewUser>? = null,
    /** The departments master has answered, one way or the other — edit loads wait for it. */
    val directoryReady: Boolean = false,
    /** The one-page builder open — a deal memo or a Deal Memo Setup. */
    val builder: BuilderState? = null,
    val hub: SetupHubState = SetupHubState(),
) {
    val rights: DealMemoRights get() = viewer.rights

    /** The page on screen: the route after every bounce. */
    val page: DealMemoRoute get() = DealMemoRoute.resolve(route, rights, metadata)

    val visibleTabs: List<DealTab> get() = DealTab.visible(rights, metadata)

    val labels: DealCrewLabels get() = DealCrewLabels(people, catalogue)
}

/** `useProjectSettings`: [loading] is the first load only — a refresh never shows a skeleton. */
data class ProjectSettingsState(
    val view: ProjectSettingsView = ProjectSettingsView(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
)

/** The project's setups, swept once per entry (`templatesStore.jsx`). */
data class TemplatesState(
    /** Null until the sweep answers. */
    val rows: List<DealTemplate>? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
) {
    /** Which kinds exist — null while unknown, so the Create gate lets people through. */
    val presence: SetupPresence?
        get() = rows?.let { list ->
            SetupPresence(union = list.any { !it.nonUnion }, nonUnion = list.any { it.nonUnion })
        }
}

data class SetupPresence(val union: Boolean, val nonUnion: Boolean) {
    fun has(group: SetupGroup?): Boolean = when (group) {
        null -> union || nonUnion
        SetupGroup.Union -> union
        SetupGroup.NonUnion -> nonUnion
    }
}

data class DealsState(
    val rows: List<DealDoc> = emptyList(),
    /** When the rows were read — what "overdue" is judged against. */
    val loadedAt: Long = 0L,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val filter: DealQuickFilter = DealQuickFilter.All,
    val departmentId: String? = null,
    val sort: DealSort = DealSort.DateDesc,
    val search: String = "",
    /** Activate and Chase are single-flight across the whole list. */
    val activatingId: String? = null,
    val chasingId: String? = null,
    val pendingDelete: DealDoc? = null,
    val deleting: Boolean = false,
    val exporting: DealExport? = null,
    val checkingSetups: Boolean = false,
    val createMenuOpen: Boolean = false,
    /** "Set up Deal Memo first" — opened from the button (null group) or a menu item. */
    val setupGate: SetupGateState? = null,
)

data class SetupGateState(val group: SetupGroup?)

data class OverviewState(
    val data: DealOverview? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
)

data class QueueState(
    val rows: List<DealDoc> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
)

data class MyDealState(
    val deal: DealDoc? = null,
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val failed: Boolean = false,
)

data class NoticesState(
    val rows: List<DealDoc> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val search: String = "",
    val template: String = NoticeRules.DEFAULT_TEMPLATE,
    /** Deactivated from this screen this session — the list rows never carry the stamp. */
    val deactivatedHere: Set<String> = emptySet(),
    val send: SendNoticeDraft? = null,
    val sendAll: NoticeGroupKind? = null,
    val sendingAll: Boolean = false,
    val deactivate: DeactivateDraft? = null,
)

/** The Send Notice modal: the body refills from the date until someone types in it. */
data class SendNoticeDraft(
    val deal: DealDoc,
    val date: String,
    val body: String,
    val edited: Boolean = false,
    val sending: Boolean = false,
)

data class DeactivateDraft(val deal: DealDoc, val date: String, val busy: Boolean = false)

enum class NoticeTemplateMode { Edit, Preview }

data class NoticeTemplateState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val text: String = NoticeRules.DEFAULT_TEMPLATE,
    val savedText: String = NoticeRules.DEFAULT_TEMPLATE,
    val saving: Boolean = false,
    /** The green check after a save, for a moment. */
    val justSaved: Boolean = false,
    val mode: NoticeTemplateMode = NoticeTemplateMode.Edit,
    val confirmReset: Boolean = false,
) {
    val dirty: Boolean get() = text != savedText
}

data class HistoryState(
    val dealId: String,
    val subtitle: String,
    val loading: Boolean = true,
    val entries: List<DealHistoryEntry> = emptyList(),
    val error: String? = null,
)
