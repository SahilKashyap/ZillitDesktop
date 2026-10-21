package com.zillit.desktop.feature.addashboard.ui

import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdCrewMember
import com.zillit.desktop.feature.addashboard.domain.AdCurrencies
import com.zillit.desktop.feature.addashboard.domain.AdDates
import com.zillit.desktop.feature.addashboard.domain.AdPhase
import com.zillit.desktop.feature.addashboard.domain.AdPhases
import com.zillit.desktop.feature.addashboard.domain.AdRefresh
import com.zillit.desktop.feature.addashboard.domain.AdReport
import com.zillit.desktop.feature.addashboard.domain.AdShootDay
import com.zillit.desktop.feature.addashboard.domain.AdViewer
import com.zillit.desktop.feature.addashboard.domain.Agency
import com.zillit.desktop.feature.addashboard.domain.AgencyDraft
import com.zillit.desktop.feature.addashboard.domain.AgencyHistoryEntry
import com.zillit.desktop.feature.addashboard.domain.AgencyGroup
import com.zillit.desktop.feature.addashboard.domain.Artiste
import com.zillit.desktop.feature.addashboard.domain.ArtisteCategory
import com.zillit.desktop.feature.addashboard.domain.ArtisteDraft
import com.zillit.desktop.feature.addashboard.domain.ArtisteQuery
import com.zillit.desktop.feature.addashboard.domain.ArtisteStatus
import com.zillit.desktop.feature.addashboard.domain.EngagementType
import com.zillit.desktop.feature.addashboard.domain.ImportPreview
import com.zillit.desktop.feature.addashboard.domain.MealBreak
import com.zillit.desktop.feature.addashboard.domain.NominalCode
import com.zillit.desktop.feature.addashboard.domain.NonUnionRates
import com.zillit.desktop.feature.addashboard.domain.PhaseState
import com.zillit.desktop.feature.addashboard.domain.PoQueue
import com.zillit.desktop.feature.addashboard.domain.QueryDayContext
import com.zillit.desktop.feature.addashboard.domain.RateConfig
import com.zillit.desktop.feature.addashboard.domain.ReportKind
import com.zillit.desktop.feature.addashboard.domain.ReportStatus
import com.zillit.desktop.feature.addashboard.domain.ReportSummary
import com.zillit.desktop.feature.addashboard.domain.ReportTemplate
import com.zillit.desktop.feature.addashboard.domain.SavedSignature
import com.zillit.desktop.feature.addashboard.domain.SigningRow
import com.zillit.desktop.feature.addashboard.domain.SubmitRules
import com.zillit.desktop.feature.addashboard.domain.SupportingArtistDay
import com.zillit.desktop.feature.addashboard.domain.UnionAgreementRef
import com.zillit.desktop.feature.addashboard.domain.UnionPayBreakdown
import com.zillit.desktop.feature.addashboard.domain.isDateEditable
import com.zillit.desktop.feature.addashboard.domain.isDayReadOnly

/** The sidebar's groups, in the order they are shown. */
enum class AdSection(private val labelKey: String) {
    Reports(S.reports),
    Schedule(S.schedule),
    Artistes(S.desktop_ad_artistes),
    ShootDay(S.desktop_ad_section_shoot_day),
    ;

    val label: String get() = str(labelKey)
}

/**
 * The dashboard's pages.
 *
 * The same set the web's sidebar holds, in the same groups. The web wraps them
 * in a two-way section switcher ("Reports" / "Artistes") because its sidebar
 * is narrow; the desktop's side nav draws every group as its own card, so all
 * four are reachable without switching.
 */
@Suppress("LongParameterList")
enum class AdDestination(
    val slug: String,
    private val labelKey: String,
    val section: AdSection,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    /**
     * Hidden from a viewer without posting rights, and their URL redirected.
     *
     * A hide-list, not a whitelist: every other page stays, read-only. The web
     * hides exactly these four.
     */
    val postingOnly: Boolean = false,
    /** Reachable, but with no row of its own — the web's `/templates`. */
    val inNav: Boolean = true,
) {
    WrapReport("wrap-report", S.desktop_wrap_report, AdSection.Reports, ZillitIcons.File),
    ExhibitG("exhibit-g", S.desktop_ad_exhibit_g, AdSection.Reports, ZillitIcons.Signature),
    Templates(
        slug = "templates",
        labelKey = S.templates,
        section = AdSection.Reports,
        icon = ZillitIcons.Grid,
        postingOnly = true,
        inNav = false,
    ),

    LiveDay(
        slug = "live-day",
        labelKey = S.desktop_ad_run_current_day,
        section = AdSection.Schedule,
        icon = ZillitIcons.Play,
        postingOnly = true,
    ),
    Schedule("schedule", S.desktop_ad_shoot_schedules, AdSection.Schedule, ZillitIcons.Calendar),

    Register("register", S.desktop_ad_artiste_register, AdSection.Artistes, ZillitIcons.Users),
    Onboarding(
        slug = "onboarding",
        labelKey = S.desktop_ad_artistes_onboarding,
        section = AdSection.Artistes,
        icon = ZillitIcons.UserPlus,
        postingOnly = true,
    ),
    Agency("agency", S.desktop_dm_agency, AdSection.Artistes, ZillitIcons.Building),
    Queries("queries", S.desktop_ad_artiste_queries, AdSection.Artistes, ZillitIcons.Chat),
    Config(
        slug = "config",
        labelKey = S.desktop_ad_rates_config,
        section = AdSection.Artistes,
        icon = ZillitIcons.Settings,
        postingOnly = true,
    ),
    PoQueue("po-queue", S.desktop_ad_po_payroll_queue, AdSection.Artistes, ZillitIcons.Receipt),

    TodaysList("todays-list", S.desktop_ad_todays_list, AdSection.ShootDay, ZillitIcons.Inbox),
    AddExtras("add-extras", S.desktop_ad_add_extras, AdSection.ShootDay, ZillitIcons.UserPlus),
    Attendance("attendance", S.desktop_ad_attendance, AdSection.ShootDay, ZillitIcons.Tick),
    Additionals("additionals", S.desktop_ad_additionals, AdSection.ShootDay, ZillitIcons.Wallet),
    SubmitDay("submit-day", S.desktop_ad_submit_day, AdSection.ShootDay, ZillitIcons.Send),
    Budget("budget", S.budget_text, AdSection.ShootDay, ZillitIcons.Calculator),
    ;

    val label: String get() = str(labelKey)

    /** Which socket refresh kinds reload this page. */
    val refresh: Set<AdRefresh>
        get() = when (this) {
            TodaysList, AddExtras, Attendance, Additionals, LiveDay, SubmitDay ->
                setOf(AdRefresh.Today)

            Schedule -> setOf(AdRefresh.Days, AdRefresh.Today)
            Register, Onboarding -> setOf(AdRefresh.Register)
            Agency, Config -> setOf(AdRefresh.Rates)
            Queries -> setOf(AdRefresh.Queries)
            PoQueue -> setOf(AdRefresh.Queue, AdRefresh.Days)
            WrapReport, Templates -> setOf(AdRefresh.Reports)
            ExhibitG -> setOf(AdRefresh.Reports, AdRefresh.Signatures)
            Budget -> emptySet()
        }

    /**
     * The notification units filed under this page — the web's
     * `AD_UNIT_BY_NAV`. Exhibit G has none.
     */
    val badgeKeys: List<String>
        get() = when (this) {
            Register -> listOf(AdBadges.REGISTER)
            Queries -> listOf(AdBadges.QUERY)
            TodaysList -> listOf(AdBadges.TODAY_LIST)
            AddExtras -> listOf(AdBadges.ADD_EXTRAS)
            WrapReport -> listOf(AdBadges.REPORTS)
            Schedule -> listOf(AdBadges.SHOOT_SCHEDULE)
            else -> emptyList()
        }

    /**
     * The units this page reads whole when it is shown.
     *
     * Everything but the register and the queries, which are read per row:
     * opening either tab used to clear every dot at once.
     */
    val pageReads: List<String>
        get() = badgeKeys - AdBadges.REGISTER - AdBadges.QUERY

    /** The level marker the backend stores a tab-level read under. */
    val readLevel: Pair<String, String>?
        get() = when (this) {
            TodaysList -> "level_3" to "today"
            AddExtras -> "level_3" to "tomorrow"
            WrapReport -> "level_2" to "report"
            else -> null
        }

    companion object {
        /**
         * The page the dashboard opens on.
         *
         * Wrap Report, as the web's bare path has landed since 2026-07-08 —
         * the reports are what an AD comes back to after wrap.
         */
        val landing: AdDestination = WrapReport

        fun fromSlug(slug: String?): AdDestination? = entries.firstOrNull { it.slug == slug }
    }
}

/** The AD dashboard's notification units under `ad_dashboard_label`. */
object AdBadges {
    const val REGISTER = "artiste_register_label"
    const val QUERY = "artiste_query_label"
    const val REPORTS = "ad_reports_label"
    const val TODAY_LIST = "today_list_label"
    const val ADD_EXTRAS = "add_extras_label"
    const val SHOOT_SCHEDULE = "shoot_schedule_label"

    /**
     * Notification-only units — no tab and no chip on any client — that still
     * count on the tile. Neither the web nor iOS ever reads them, so here the
     * dashboard being looked at reads them.
     */
    val NOTICE_ONLY: List<String> = listOf("rates_config_label")
}

// -- the register ------------------------------------------------------------

/** The register's filters and search. */
data class RegisterState(
    val search: String = "",
    val category: ArtisteCategory? = null,
    val status: ArtisteStatus? = null,
    val engagement: EngagementType? = null,
    val unionOnly: Boolean = false,
    val confirmDelete: Artiste? = null,
    val deleting: Boolean = false,
)

/** The artiste form — create, or edit one that already exists. */
data class ArtisteFormState(
    val artisteId: String? = null,
    val draft: ArtisteDraft = ArtisteDraft(),
    val saving: Boolean = false,
    val error: String? = null,
    /** The crew the internal picker offers, filtered to the three artiste groups. */
    val eligible: List<AdCrewMember> = emptyList(),
    val agencies: List<Agency> = emptyList(),
    val unions: List<UnionAgreementRef> = emptyList(),
    val crewSearch: String = "",
) {
    val isEdit: Boolean get() = artisteId != null

    /** A draft artiste's **Add artiste** promotes them to pending. */
    val canPromote: Boolean get() = isEdit && draft.status == ArtisteStatus.Draft
}

/** One artiste's own page: verify, block, the portal link. */
data class ArtisteDetailState(
    val artiste: Artiste,
    val busy: Boolean = false,
    val portalLink: String = "",
    val confirmBlock: Boolean = false,
    val blockReason: String = "",
)

/** An uploaded agency list, between the preview and the commit. */
data class ImportState(
    val fileName: String = "",
    val uploading: Boolean = false,
    val preview: ImportPreview? = null,
    val committing: Boolean = false,
    val error: String? = null,
) {
    val rows: List<com.zillit.desktop.feature.addashboard.domain.ImportRow>
        get() = preview?.rows.orEmpty()

    val readyCount: Int get() = preview?.ready?.size ?: 0
}

// -- the shoot day -----------------------------------------------------------

/** Today's List: its search, its open row, its export. */
data class TodayListState(
    val search: String = "",
    val openVoucher: SupportingArtistDay? = null,
    val voucherLoading: Boolean = false,
    val openArtiste: SupportingArtistDay? = null,
    val summaryOpen: Boolean = false,
    val exporting: Boolean = false,
    val sendingLink: String? = null,
)

/** Add Extras: today or tomorrow, an existing artiste or a new external one. */
data class AddExtrasState(
    /** 0 for today, 1 for tomorrow — the web's two-way toggle. */
    val dayOffset: Int = 0,
    val search: String = "",
    val chosen: Set<String> = emptySet(),
    val callTime: String = "",
    val newArtiste: ArtisteDraft? = null,
    val saving: Boolean = false,
    val error: String? = null,
) {
    val ready: Boolean get() = chosen.isNotEmpty() || newArtiste?.identityComplete == true
}

/** The Attendance tab: time in and day type, never time out. */
data class AttendanceState(
    val search: String = "",
    val busy: Set<String> = emptySet(),
    val timeDraft: Map<String, String> = emptyMap(),
)

/** Additionals: the ticked lines, the notes, and the one custom payment. */
data class AdditionalsState(
    val search: String = "",
    val expanded: Set<String> = emptySet(),
    val busy: Set<String> = emptySet(),
    /** Per row: the notes being typed, saved on blur when changed. */
    val noteDrafts: Map<String, String> = emptyMap(),
    /** Per row: the custom payment's description and amount, saved on its own Save. */
    val customDescription: Map<String, String> = emptyMap(),
    val customAmount: Map<String, String> = emptyMap(),
    val savingCustom: Set<String> = emptySet(),
)

/** The live runner's four steps. */
enum class RunnerStep(private val labelKey: String) {
    Attendance(S.desktop_ad_attendance),
    MidDay(S.desktop_ad_mid_day),
    Wrap(S.dm_ds_phase_wrap),
    Submit(S.submit),
    ;

    val label: String get() = str(labelKey)
}

/**
 * Run Current Day, and a day opened from the schedule.
 *
 * `gated` is the difference: the Run Current Day tab unlocks its steps in
 * order, while an opened day has every step open and an extra Additionals
 * section. The web keeps the same component for both.
 */
data class RunnerState(
    val step: RunnerStep = RunnerStep.Attendance,
    val gated: Boolean = true,
    /** Set when a day other than today is open — the schedule's own route. */
    val openedDate: Long? = null,
    val busy: Set<String> = emptySet(),
    val bulkBusy: Boolean = false,
    /**
     * The day meals being edited.
     *
     * A draft until **Save meals**: the editor owns them, and a silent save
     * never refetches the header, so this overlay is what keeps them on screen
     * across a step change.
     */
    val mealDraft: List<MealBreak>? = null,
    val mealsSaving: Boolean = false,
    /** Per-artiste meals, keyed by row, with the same draft rule. */
    val artisteMeals: Map<String, List<MealBreak>> = emptyMap(),
    val openArtisteMeals: String? = null,
    val timeDraft: Map<String, String> = emptyMap(),
)

/** The sign-off: the signature, the routing, and the POs it will raise. */
data class SubmitState(
    val signatureName: String = "",
    val signatureBytes: ByteArray? = null,
    val savedSignatures: List<SavedSignature> = emptyList(),
    val pickedSignature: String? = null,
    val signaturePadOpen: Boolean = false,
    /**
     * What is being drawn in the pad, one list of points per stroke.
     *
     * Kept here rather than inside the dialog so a half-drawn signature
     * survives the dialog being scrolled or re-composed.
     */
    val strokes: List<List<Pair<Float, Float>>> = emptyList(),
    val typedName: String = "",
    val rendering: Boolean = false,
    /** `standard` or `urgent`; urgent only flags the PO. */
    val postType: String = "standard",
    val submitting: Boolean = false,
    val postingPo: String? = null,
    val sendingLink: String? = null,
    /**
     * Purchase orders already raised this attempt, keyed agency and currency.
     *
     * A retry after a part-way failure reuses them rather than raising a
     * second — lost on a reload, as the web's own guard is.
     */
    val createdPos: Map<String, com.zillit.desktop.feature.addashboard.domain.DayPurchaseOrder> = emptyMap(),
) {
    val hasSignature: Boolean get() = signatureBytes != null || pickedSignature != null

    /** Whether there is anything to render — strokes drawn, or a name typed. */
    val padHasInk: Boolean get() = strokes.any { it.size > 1 } || typedName.isNotBlank()

    override fun equals(other: Any?): Boolean = other is SubmitState &&
        signatureName == other.signatureName &&
        signatureBytes.contentEqualsOrNull(other.signatureBytes) &&
        savedSignatures == other.savedSignatures &&
        pickedSignature == other.pickedSignature &&
        signaturePadOpen == other.signaturePadOpen &&
        strokes == other.strokes &&
        typedName == other.typedName &&
        rendering == other.rendering &&
        postType == other.postType &&
        submitting == other.submitting &&
        postingPo == other.postingPo &&
        sendingLink == other.sendingLink &&
        createdPos == other.createdPos

    override fun hashCode(): Int {
        var result = signatureName.hashCode()
        result = 31 * result + (signatureBytes?.contentHashCode() ?: 0)
        result = 31 * result + savedSignatures.hashCode()
        result = 31 * result + (pickedSignature?.hashCode() ?: 0)
        result = 31 * result + postType.hashCode()
        result = 31 * result + createdPos.hashCode()
        return result
    }

    private fun ByteArray?.contentEqualsOrNull(other: ByteArray?): Boolean = when {
        this == null -> other == null
        other == null -> false
        else -> contentEquals(other)
    }
}

// -- agencies, queries and rates ---------------------------------------------

/** The Agency tab: the list, the form, the history panel. */
data class AgencyState(
    val rows: List<Agency> = emptyList(),
    val loading: Boolean = false,
    val search: String = "",
    val open: Agency? = null,
    val form: AgencyFormState? = null,
    val confirmDelete: Agency? = null,
    val historyFor: Agency? = null,
    val history: List<AgencyHistoryEntry> = emptyList(),
    val historyLoading: Boolean = false,
    val busy: Boolean = false,
)

data class AgencyFormState(
    val agencyId: String? = null,
    val draft: AgencyDraft = AgencyDraft(),
    val saving: Boolean = false,
    val error: String? = null,
    /** Whether Save should verify as well — the Agency tab's second button. */
    val alsoVerify: Boolean = false,
)

/** Artiste Queries: the threads, the open one, and the day behind it. */
data class QueriesState(
    val rows: List<ArtisteQuery> = emptyList(),
    val loading: Boolean = false,
    val search: String = "",
    val showResolved: Boolean = true,
    val openId: String? = null,
    val thread: ArtisteQuery? = null,
    val threadLoading: Boolean = false,
    val dayContext: QueryDayContext? = null,
    val reply: String = "",
    val busy: Boolean = false,
) {
    val open: ArtisteQuery? get() = thread ?: rows.firstOrNull { it.id == openId }

    val visible: List<ArtisteQuery>
        get() = rows
            .filter { showResolved || !it.resolved }
            .filter { query ->
                val needle = search.trim()
                needle.isBlank() ||
                    query.artisteName.contains(needle, ignoreCase = true) ||
                    query.title.contains(needle, ignoreCase = true) ||
                    query.lastMessage.contains(needle, ignoreCase = true)
            }
            .sortedByDescending { it.updated ?: 0L }
}

/**
 * Rates & Config, held as one document with one Save.
 *
 * [dirty] gates the live reload: a remote change is only applied while nothing
 * here is unsaved, so a concurrent edit cannot clobber an in-progress draft.
 */
data class RatesState(
    val loading: Boolean = false,
    val config: RateConfig = RateConfig(),
    val nonUnion: NonUnionRates = NonUnionRates(),
    val union: Map<String, UnionPayBreakdown> = emptyMap(),
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val editingRule: String? = null,
    val nominalCodes: List<NominalCode> = emptyList(),
    /** The union editor, open on one agreement. */
    val unionEditor: UnionEditorState? = null,
    val confirmDeleteUnion: UnionAgreementRef? = null,
    val confirmDeleteVendor: Agency? = null,
    val vendorForm: AgencyFormState? = null,
    val distributionPickerOpen: Boolean = false,
)

/** The Union Pay Breakdown page, which saves through the same one PATCH. */
data class UnionEditorState(
    val agreementId: String? = null,
    val territory: String = "",
    val catalogue: List<UnionAgreementRef> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
)

// -- reports -----------------------------------------------------------------

/** The merged Wrap + AD list, and the Exhibit G list. */
data class ReportsState(
    val loading: Boolean = false,
    val wrapRows: List<ReportSummary> = emptyList(),
    val adRows: List<ReportSummary> = emptyList(),
    val exhibitRows: List<ReportSummary> = emptyList(),
    val templates: List<ReportTemplate> = emptyList(),
    val kindFilter: ReportKind? = null,
    val statusFilter: ReportStatus? = null,
    val search: String = "",
    /** Set by a day link from the schedule; cleared once resolved. */
    val dayLink: Long? = null,
    val templatePickerOpen: Boolean = false,
    val confirmDelete: ReportSummary? = null,
    val busy: Boolean = false,
    /** The open editor, when a report is being filled in. */
    val editor: ReportEditorState? = null,
) {
    /**
     * Both merged kinds, each tagged by the endpoint that answered.
     *
     * Never one list filtered: the AD endpoint also returns untyped legacy
     * rows, so a row's own type cannot be trusted and the endpoint is what
     * decides which API every later call for it uses.
     */
    val merged: List<ReportSummary> get() = wrapRows + adRows
}

/** A report open in the editor. */
data class ReportEditorState(
    val kind: ReportKind,
    val report: AdReport,
    val fills: Map<String, String> = emptyMap(),
    val header: Map<String, String> = emptyMap(),
    val name: String = "",
    val pendingDate: Long? = null,
    val dayNumberOverride: String = "",
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val posting: Boolean = false,
    val publishOpen: Boolean = false,
    val signaturesPending: List<SigningRow> = emptyList(),
    val signingRow: SigningRow? = null,
    val signingInPerson: Boolean = true,
    /** What is being drawn for that row, one list of points per stroke. */
    val signStrokes: List<List<Pair<Float, Float>>> = emptyList(),
    val signTypedName: String = "",
    val confirmReset: Boolean = false,
    val selectedCell: String? = null,
    val rearrangeOpen: Boolean = false,
    val saveAsTemplateOpen: Boolean = false,
    val templateName: String = "",
    /** A never-saved draft, which the first save creates. */
    val isNew: Boolean = false,
    val clientId: String? = null,
) {
    /** The date a save sends: a pending pick wins over the committed one. */
    val effectiveShootDate: Long? get() = pendingDate ?: report.shootDate
}

// -- the whole screen --------------------------------------------------------

@Suppress("LongParameterList")
data class AdUiState(
    val viewer: AdViewer = AdViewer(),
    val destination: AdDestination = AdDestination.landing,
    /** Unread notifications per unit — the nav's red chips. */
    val unread: Map<String, Int> = emptyMap(),
    /** Unread per unit, then per record (`level_3`) — the per-row dots. */
    val entityUnread: Map<String, Map<String, Int>> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    /** UTC midnight — every date on this service is, deliberately. */
    val shootDate: Long = 0L,
    val today: Long = 0L,
    val day: AdShootDay? = null,
    val dayNumber: Int? = null,
    val dayList: List<SupportingArtistDay> = emptyList(),
    val artistes: List<Artiste> = emptyList(),
    val shootDays: List<AdShootDay> = emptyList(),
    val currencies: AdCurrencies = AdCurrencies(),
    val register: RegisterState = RegisterState(),
    val artisteForm: ArtisteFormState? = null,
    val artisteDetail: ArtisteDetailState? = null,
    val import: ImportState? = null,
    val todayList: TodayListState = TodayListState(),
    val addExtras: AddExtrasState = AddExtrasState(),
    val attendance: AttendanceState = AttendanceState(),
    val additionals: AdditionalsState = AdditionalsState(),
    val runner: RunnerState = RunnerState(),
    val submit: SubmitState = SubmitState(),
    val agency: AgencyState = AgencyState(),
    val queries: QueriesState = QueriesState(),
    val rates: RatesState = RatesState(),
    val poQueue: PoQueue = PoQueue(),
    val poQueueLoading: Boolean = false,
    val reports: ReportsState = ReportsState(),
) {

    /**
     * Whether the day on screen may be changed.
     *
     * Two gates and both must pass: the viewer's posting right, and the day's
     * own status. A future day is read-only too, which is how the schedule's
     * upcoming rows open.
     */
    val canEditDay: Boolean
        get() = !isDayReadOnly(
            status = day?.status ?: com.zillit.desktop.feature.addashboard.domain.AdDayStatus.Unknown,
            dateEditable = dateEditable,
            canPost = viewer.canPost,
        )

    /** Whether the open date is today or past; a future day is date-locked. */
    val dateEditable: Boolean get() = isDateEditable(shootDate, today)

    /**
     * Whether the day itself still accepts changes, posting rights aside.
     *
     * Separate from [canEditDay] because the two are answered differently: a
     * locked day's controls are gone, while a missing right leaves them on
     * screen and turns a press into a request.
     */
    val dayIsOpen: Boolean get() = day?.editable ?: false

    /**
     * Whether the day header has loaded.
     *
     * A null status reads as unlocked, so lock-dependent controls would flash
     * in and then vanish. A silent refetch keeps the old header, so this never
     * re-hides once it has resolved.
     */
    val statusPending: Boolean get() = loading && day == null

    /** The pages this viewer may see. */
    val visibleDestinations: List<AdDestination>
        get() = AdDestination.entries.filter { it.inNav && (viewer.canPost || !it.postingOnly) }

    fun sectionsFor(section: AdSection): List<AdDestination> =
        visibleDestinations.filter { it.section == section }

    /** One artiste's unread updates. */
    fun artisteUnread(id: String): Int = entityUnread[AdBadges.REGISTER]?.get(id) ?: 0

    /** One query's unread updates. */
    fun queryUnread(id: String): Int = entityUnread[AdBadges.QUERY]?.get(id) ?: 0

    // -- the day -----------------------------------------------------------

    val phases: List<Pair<AdPhase, PhaseState>> get() = AdPhases.of(dayList)

    val onToday: Int get() = dayList.size

    val presentToday: Int get() = dayList.count { it.isPresent }

    val wrappedToday: Int get() = dayList.count { it.isWrapped }

    val unsignedToday: Int get() = dayList.count { !it.signed }

    /** The day's money, by the currency each row carries. */
    val dayTotals: List<com.zillit.desktop.feature.addashboard.domain.CurrencyTotals>
        get() = SubmitRules.currencyTotals(dayList)

    val agencyGroups: List<AgencyGroup> get() = SubmitRules.agencyGroups(dayList)

    val poReference: String get() = SubmitRules.poReference(day, shootDate, dayNumber)

    /** Today's List, filtered by its search box. */
    val todayRows: List<SupportingArtistDay>
        get() = dayList.filterBySearch(todayList.search)

    val attendanceRows: List<SupportingArtistDay>
        get() = dayList.filterBySearch(attendance.search)

    val additionalsRows: List<SupportingArtistDay>
        get() = dayList.filterBySearch(additionals.search)

    /** The register, filtered the way its own controls say. */
    val registerRows: List<Artiste>
        get() = artistes
            .filter { artiste ->
                val needle = register.search.trim()
                needle.isBlank() ||
                    artiste.name.contains(needle, ignoreCase = true) ||
                    artiste.refNumber.contains(needle, ignoreCase = true) ||
                    artiste.email.contains(needle, ignoreCase = true) ||
                    artiste.agencyName.contains(needle, ignoreCase = true)
            }
            .filter { register.category == null || it.category == register.category }
            .filter { register.status == null || it.status == register.status }
            .filter { register.engagement == null || it.engagement == register.engagement }
            .filter { !register.unionOnly || it.union.isNotBlank() }

    /**
     * Artistes not already on the day — what Add Extras offers.
     *
     * A blocked artiste is never offered: blocking exists to keep them off the
     * call sheet, and the server refuses them anyway.
     */
    fun addable(search: String): List<Artiste> {
        val already = dayList.map { it.artisteId }.toSet()
        val needle = search.trim()
        return artistes
            .filterNot { it.id in already || it.blocked }
            .filter {
                needle.isBlank() ||
                    it.name.contains(needle, ignoreCase = true) ||
                    it.refNumber.contains(needle, ignoreCase = true)
            }
    }

    /** The schedule, split the way the web splits it. */
    val todayRow: AdShootDay? get() = shootDays.firstOrNull { AdDates.sameDay(it.shootDate, today) }

    val upcomingDays: List<AdShootDay>
        get() = shootDays
            .filter { (it.shootDate ?: 0L) > today }
            .sortedBy { it.shootDate ?: 0L }

    val pastDays: List<AdShootDay>
        get() = shootDays
            .filter { (it.shootDate ?: 0L) < today }
            .sortedByDescending { it.shootDate ?: 0L }

    /**
     * "SD n" on a schedule row: a client-side rank by date.
     *
     * Deliberately not the day's own `day_number`, which a report may have
     * overridden — the web ranks here and reads the server's number only for
     * the header pill.
     */
    fun scheduleRank(day: AdShootDay): Int {
        val ordered = shootDays.sortedBy { it.shootDate ?: 0L }
        return ordered.indexOfFirst { it.id == day.id } + 1
    }

    private fun List<SupportingArtistDay>.filterBySearch(search: String): List<SupportingArtistDay> {
        val needle = search.trim()
        if (needle.isBlank()) return this
        return filter {
            it.artisteName.contains(needle, ignoreCase = true) ||
                it.refNumber.contains(needle, ignoreCase = true) ||
                it.agencyName.contains(needle, ignoreCase = true)
        }
    }
}
