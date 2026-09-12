package com.zillit.desktop.feature.costreport.domain

import com.zillit.desktop.core.common.ZillitError
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.permissions.ProjectPermissions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonObject

/**
 * What a socket announcement means to an open cost report.
 *
 * [Report] is the document itself changing — locked or posted elsewhere — and
 * is worth a silent re-pull. [Source] is a feeder tool's approve/post landing
 * (a PO, an invoice, a timecard…): the figures *may* be stale, but the web
 * deliberately does not auto-refresh on it — recomputing the worksheet is
 * heavy — and surfaces a manual Refresh pill instead
 * (`CostReportWorksheetModule.jsx:5606-5615`). The same split here.
 */
enum class CostReportSync { Report, Source }

/** The two faces of the crew-facing tool: the live worksheet and the posted timeline. */
enum class CostReportTab(val id: String, val label: String) {
    Current("current", "Current CR"),
    Posted("posted", "Posted CRs"),
}

/**
 * The COA row's depth in the cost-report tree. The wire names are the
 * accountant's (`header` is the *top*), so the tree names them by what they
 * become — see [buildSections].
 */
enum class CoaLevel(val wire: String) {
    /** `header` — the top band: ABOVE THE LINE, PRODUCTION… */
    Section("header"),

    /** `section` — the bold header row under a band. */
    Header("section"),

    /** `category` — the account nominal a line item is coded to. */
    Nominal("category"),

    /** `sub_category` — a set under a nominal; anything deeper folds in here. */
    Set("sub_category"),
    ;

    companion object {
        fun from(wire: String?): CoaLevel = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() } ?: Set
    }
}

/** One flat row of `GET account-hub/chart-of-accounts?active_only=false`. */
data class CoaRow(
    val id: String,
    val code: String,
    val name: String,
    val level: CoaLevel,
    val headId: String? = null,
    val secId: String? = null,
    val catId: String? = null,
    val sortOrder: Int? = null,
    val isActive: Boolean = true,
) {
    /** The parent's id, by depth: a header hangs off a head, a nominal off a section, a set off a category. */
    val parentId: String?
        get() = when (level) {
            CoaLevel.Section -> null
            CoaLevel.Header -> headId
            CoaLevel.Nominal -> secId
            CoaLevel.Set -> catId ?: secId
        }
}

/** A versioned budget header from `GET account-hub/budgets`. */
data class BudgetVersion(
    val id: String,
    val version: String,
    val label: String,
    /** `DRAFT` | `APPROVED` | `LIVE` | `ARCHIVED`. */
    val status: String,
) {
    val display: String get() = label.ifBlank { version.ifBlank { id } }
}

/** A production company (`project-settings/companies`). */
data class CrCompany(val id: String, val name: String, val country: String = "") {
    /** "Name (Country)", as the analytics entity filter names a company. */
    val labelWithCountry: String get() = if (country.isBlank()) name else "$name ($country)"
}

/** A currency the production runs in, or one from the preset catalogue. */
data class CrCurrency(
    val code: String,
    val name: String = "",
    val symbol: String = "",
    /** Units of this currency per one of the project default; null when the project list has none. */
    val exr: Double? = null,
)

/** `project-settings/project-currencies`: the selected currencies and the default. */
data class CurrencyOptions(val currencies: List<CrCurrency> = emptyList(), val defaultCode: String? = null) {
    /** Each currency's `exr`, with the project default forced to 1. */
    val rates: CurrencyRates
        get() = CurrencyRates(
            currencies.mapNotNull { c -> c.exr?.let { c.code to it } }.toMap() +
                listOfNotNull(defaultCode?.let { it to 1.0 }),
        )
}

/**
 * One aggregated line of `/live` or of a snapshot — one per (account, department)
 * on the wire; the tree sums them by account.
 */
data class CostLine(
    /** COA code, a `__sentinel__`, or null for a name-only bucket. */
    val account: String?,
    val name: String? = null,
    val department: String? = null,
    val budget: Double = 0.0,
    val atp: Double = 0.0,
    val atd: Double = 0.0,
    val po: Double = 0.0,
    val card: Double = 0.0,
    val cash: Double = 0.0,
    val pr: Double = 0.0,
    /** Frozen server figures — carried, not used by the tree (see spec §4.5 trap). */
    val etc: Double? = null,
    val efc: Double? = null,
    val variance: Double? = null,
    val level: String? = null,
    /** The budget line's own id — what tells two contractual rows of one name apart. */
    val id: String? = null,
    /** `__contractual__` on a budget line with no COA code; null on everything else. */
    val sectionId: String? = null,
)

/** `GET /live`. */
data class LiveReport(
    val lines: List<CostLine>,
    val currency: String? = null,
    val defaultCurrency: String? = null,
    val budgetVersionId: String? = null,
    val generatedAtMs: Long? = null,
) {
    val displayCurrency: String? get() = currency ?: defaultCurrency
}

enum class SnapshotCadence(val wire: String, val label: String) {
    Daily("daily", "Daily"),
    Weekly("weekly", "Weekly"),
    Adhoc("adhoc", "Ad-hoc"),
    ;

    companion object {
        fun from(wire: String?): SnapshotCadence? = entries.firstOrNull { it.wire == wire?.trim()?.lowercase() }
    }
}

/** How a post is drawn on the timeline (spec §2.3). */
enum class PostKind(val chip: String) {
    Weekly("WK"),
    Daily("Daily"),
    Lock("Lock"),
    Initial("Initial"),
}

/** The Posted CRs filter pills. */
enum class PostedFilter(val label: String) {
    All("All"),
    Daily("Daily"),
    WeekEnd("Wk-end"),
    ;

    fun admits(kind: PostKind): Boolean = when (this) {
        All -> true
        Daily -> kind == PostKind.Daily
        WeekEnd -> kind == PostKind.Weekly || kind == PostKind.Initial || kind == PostKind.Lock
    }
}

/** A slim snapshot header (`GET /snapshots`, and the top of `GET /snapshots/{id}`). */
data class SnapshotHeader(
    val id: String,
    val cadence: SnapshotCadence? = null,
    val reference: String = "",
    val name: String = "",
    val period: String = "",
    val postNote: String = "",
    val periodStartMs: Long? = null,
    val periodEndMs: Long? = null,
    val status: String = "",
    /** Null means "posted in the project default". */
    val currency: String? = null,
    val budgetVersionId: String? = null,
    val companyId: String? = null,
    val totalBudget: Double? = null,
    val totalActual: Double? = null,
    val totalCommitted: Double? = null,
    val totalVariance: Double? = null,
    val generatedAtMs: Long? = null,
    val generatedBy: String? = null,
    val publishedAtMs: Long? = null,
    val publishedBy: String? = null,
) {
    val title: String get() = name.ifBlank { reference.ifBlank { "—" } }
    val postedAtMs: Long? get() = publishedAtMs ?: generatedAtMs
    val postedBy: String? get() = publishedBy ?: generatedBy

    /** `name` starting "period lock" → lock; else by cadence: daily, weekly (wk), anything else initial. */
    val kind: PostKind
        get() = when {
            name.trim().lowercase().startsWith("period lock") -> PostKind.Lock
            cadence == SnapshotCadence.Daily -> PostKind.Daily
            cadence == SnapshotCadence.Weekly -> PostKind.Weekly
            else -> PostKind.Initial
        }
}

/** `totals` on a snapshot, zero-defaulted. */
data class SnapshotTotals(
    val budget: Double = 0.0,
    val atp: Double = 0.0,
    val atd: Double = 0.0,
    val po: Double = 0.0,
    val card: Double = 0.0,
    val cash: Double = 0.0,
    val pr: Double = 0.0,
    val etc: Double = 0.0,
    val efc: Double = 0.0,
    val variance: Double? = null,
) {
    val commits: Double get() = po + card + cash + pr
}

/** `GET /snapshots/{id}`. */
data class SnapshotDetail(
    val header: SnapshotHeader,
    val totals: SnapshotTotals,
    val lines: List<CostLine>,
)

/** The KPI strip of a posted snapshot (spec §4.5). */
data class SnapshotKpis(
    val budget: Double,
    val actualsToDate: Double,
    val commitments: Double,
    val estimatedFinalCost: Double,
    /** `header.total_variance`, not `totals.variance`. */
    val postedVariance: Double,
)

fun SnapshotDetail.kpis(): SnapshotKpis {
    val efc = totals.efc.takeIf { it != 0.0 } ?: (totals.atd + totals.commits)
    return SnapshotKpis(
        budget = totals.budget,
        actualsToDate = totals.atd,
        commitments = totals.commits,
        estimatedFinalCost = efc,
        postedVariance = header.totalVariance ?: totals.variance ?: (totals.budget - efc),
    )
}

/** Which side of the ledger a drill-down asks for. */
enum class LedgerType(val wire: String, val label: String) {
    Actuals("actuals", "Actuals"),
    Commits("commits", "Commits"),
}

/** One row of `GET /account-line-items`. */
data class LedgerItem(
    /** `PO` | `INV` | `CRED` | `CARD` | `CASH` | `PR`. */
    val src: String,
    val type: LedgerType? = null,
    val account: String? = null,
    val department: String? = null,
    val effDateMs: Long? = null,
    val invoiceNumber: String = "",
    val poNumber: String = "",
    val vendor: String = "",
    val description: String = "",
    val currency: String? = null,
    /** Negative = credit. */
    val amount: Double = 0.0,
    val compCode: String = "",
) {
    /** Older servers omit `type`: invoices and credits are actuals, everything else a commitment. */
    val effectiveType: LedgerType
        get() = type ?: if (src.uppercase() in ACTUAL_SOURCES) LedgerType.Actuals else LedgerType.Commits

    val reference: String get() = invoiceNumber.ifBlank { poNumber.ifBlank { "—" } }

    private companion object {
        val ACTUAL_SOURCES = setOf("INV", "CRED")
    }

    val typeLabel: String
        get() = when (src.uppercase()) {
            "PO" -> "PO"
            "INV" -> "Invoice"
            "CRED" -> "Credit"
            "CARD" -> "Card"
            "CASH" -> "Cash"
            "PR" -> "Payroll"
            else -> src
        }
}

/** `GET /account-line-items` — the drill-down for one account. */
data class LedgerResult(
    val code: String,
    val name: String? = null,
    val currency: String? = null,
    val defaultCurrency: String? = null,
    val total: Double = 0.0,
    val count: Int = 0,
    val actualsTotal: Double? = null,
    val actualsCount: Int? = null,
    val commitsTotal: Double? = null,
    val commitsCount: Int? = null,
    val items: List<LedgerItem> = emptyList(),
) {
    val actuals: List<LedgerItem> get() = items.filter { it.effectiveType == LedgerType.Actuals }
    val commits: List<LedgerItem> get() = items.filter { it.effectiveType == LedgerType.Commits }

    /** `by_type.actuals.total`, or the client sum when the server left it out. */
    val actualsToDate: Double get() = actualsTotal ?: actuals.sumOf { it.amount }
    val commitments: Double get() = commitsTotal ?: commits.sumOf { it.amount }
}

/** `POST /snapshots/{id}/export/{format}`. */
enum class ExportFormat(val wire: String, val label: String) {
    Pdf("pdf", "PDF"),
    Xlsx("xlsx", "Excel"),
    Csv("csv", "CSV"),
}

/** Who is looking, from the production's rights on `cost_report_tool`. */
data class CostReportViewer(
    val userId: String = "",
    val canView: Boolean = true,
    val canDownload: Boolean = false,
    val isAdmin: Boolean = false,
    val ready: Boolean = false,
) {
    val isBlocked: Boolean get() = ready && !canView && !isAdmin

    companion object {
        const val TOOL_IDENTIFIER = "cost_report_tool"

        /**
         * The web reaches the report through the Account Hub sidebar, which has
         * no gate of its own — the `cost_report_tool` tile is only issued on
         * some productions. So an identifier the grid never mentions leaves the
         * tool open; only a row that is present and switched off blocks it.
         */
        fun from(permissions: ProjectPermissions, userId: String): CostReportViewer {
            val access = permissions.access(TOOL_IDENTIFIER)
            if (!access.enabled && permissions.visibleTools.isEmpty()) {
                return CostReportViewer(userId = userId, ready = false)
            }
            val issued = permissions.tools.any { it.identifier == TOOL_IDENTIFIER }
            return CostReportViewer(
                userId = userId,
                canView = if (issued) permissions.canView(TOOL_IDENTIFIER) else true,
                canDownload = if (issued) permissions.canDownload(TOOL_IDENTIFIER) else true,
                isAdmin = permissions.isAdmin,
                ready = true,
            )
        }
    }
}

/** Every call the tool makes, across three hosts. */
interface CostReportRepository {
    /**
     * Socket announcements about the report — see [CostReportSync] for the
     * two meanings. Defaulted empty for tests and hosts without a socket.
     */
    val syncs: Flow<CostReportSync> get() = emptyFlow()

    /** `account-hub/chart-of-accounts?active_only=false` — inactive rows keep their place. */
    suspend fun chartOfAccounts(): ZillitResult<List<CoaRow>>

    suspend fun budgets(): ZillitResult<List<BudgetVersion>>

    suspend fun companies(): ZillitResult<List<CrCompany>>

    suspend fun currencies(): ZillitResult<CurrencyOptions>

    /** The core `preset/currencies` catalogue, for symbols the project list lacks. */
    suspend fun currencyCatalogue(): ZillitResult<List<CrCurrency>>

    /** `/live` for `[periodStartMs, periodEndMs]`; null budget/company/currency are omitted from the query. */
    suspend fun live(
        periodStartMs: Long,
        periodEndMs: Long,
        budgetVersionId: String?,
        companyId: String?,
        currency: String?,
    ): ZillitResult<LiveReport>

    /** `/snapshots`, optionally one cadence. Newest first. */
    suspend fun snapshots(cadence: SnapshotCadence?): ZillitResult<List<SnapshotHeader>>

    suspend fun snapshot(id: String): ZillitResult<SnapshotDetail>

    /** `/account-line-items` for a COA code (`.direct` stripped) or a bucket's own key. */
    suspend fun accountLineItems(
        code: String,
        type: LedgerType?,
        source: String?,
        currency: String?,
    ): ZillitResult<LedgerResult>

    // -- the accountant's worksheet ---------------------------------------------
    //
    // Defaulted so a read-only host (and the read-only tests) need not implement
    // writes it never makes; the accountant's host overrides every one.

    /** `GET /weekly-etc/versions?week_ending=YYYY-MM-DD`. */
    suspend fun etcVersions(weekEnding: String): ZillitResult<List<EtcVersion>> = unsupported()

    /** `GET /weekly-etc/versions/{id}` — the version's override rows. */
    suspend fun etcVersion(versionId: String): ZillitResult<List<EtcVersionLine>> = unsupported()

    /** `POST /weekly-etc/versions`; answers the new version's id when the server sends it. */
    suspend fun createEtcVersion(
        weekEnding: String,
        label: String,
        lines: List<EtcVersionLine>,
        currency: String?,
    ): ZillitResult<CrWrite<String?>> = unsupported()

    /** `PATCH /weekly-etc/versions/{id}` — replaces the version's lines in place. */
    suspend fun updateEtcVersion(
        versionId: String,
        lines: List<EtcVersionLine>,
        currency: String?,
    ): ZillitResult<CrWrite<Unit>> = unsupported()

    /** `GET /lock-period`. */
    suspend fun lockState(): ZillitResult<CrLockState> = unsupported()

    /** `POST /lock-period { as_of }` — forward only; the server refuses a backwards move with 409. */
    suspend fun lockPeriod(asOfMs: Long): ZillitResult<CrWrite<Unit>> = unsupported()

    /** `POST /snapshots` — a daily, weekly or custom post of the report. */
    suspend fun postSnapshot(post: SnapshotPost): ZillitResult<CrWrite<SnapshotHeader?>> = unsupported()
}

private fun <T> unsupported(): ZillitResult<T> =
    ZillitResult.Failure(ZillitError.Unknown("Not available from this host."))

/**
 * The host's binary POST: `snapshots/{id}/export/{format}` answers a raw
 * stream, and a JSON envelope on the wire means "no file" — surface its
 * `message` as the failure.
 */
interface CostReportExporter {
    suspend fun export(snapshotId: String, format: ExportFormat, body: JsonObject): ZillitResult<ByteArray>

    /**
     * `POST /export/{format}` — the worksheet as it stands, typed overrides
     * included, rather than a posted snapshot. Defaulted for hosts that only
     * export snapshots.
     */
    suspend fun exportReport(format: ExportFormat, body: JsonObject): ZillitResult<ByteArray> =
        ZillitResult.Failure(ZillitError.Unknown("Not available from this host."))
}

/** The host's Downloads seam. */
interface CostReportFiles {
    suspend fun saveAndOpen(fileName: String, bytes: ByteArray): ZillitResult<Unit>
}
