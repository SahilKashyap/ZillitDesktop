package com.zillit.desktop.feature.payroll.ui

import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.payroll.domain.PayrollViewer

/**
 * The payroll screens, addressed the way the web addresses them.
 *
 * The web mounts its tiles at `/film-tools/account-hub/payroll/:tile`
 * (`PayrollRouter.jsx` 26-32); here the tool owns `/film-tools/payroll` and
 * the same tile slugs follow it, so a route below the tool's own path stays
 * inside the tool — in its own window, or embedded in the Account Hub, whose
 * navigator keeps routes under the tool's path in the hub.
 */
enum class PayrollDestination(val segment: String, private val labelKey: String) {
    Landing("", S.dm_section_payroll),
    Processing("processing", S.desktop_payroll_processing),
    Run("run", S.desktop_payroll_run),
    ProducerBoard("producer-board", S.desktop_payroll_producer_board),
    ProductionReport("production-report", S.desktop_payroll_production_report),
    History("accountant-payroll", S.desktop_payroll_history),
    ;

    val label: String get() = str(labelKey)

    val path: String get() = if (segment.isEmpty()) PAYROLL_PATH else "$PAYROLL_PATH/$segment"

    /**
     * The route to this screen from an entry that carries the web's tool-tile
     * marker. The web threads `?entry=tool` through every tile link
     * (`PayrollLandingPage.jsx` 168-170) so a screen opened from the Film Tools
     * grid keeps the producer entry — and its Back returns to the producer
     * landing, not the accountant one.
     */
    fun path(enteredAsTool: Boolean): String = if (enteredAsTool) "$path?$ENTRY_TOOL" else path

    /**
     * The landing is everyone's; every other screen is offered only to a
     * viewer whose landing grid holds its tile, so a deep link to a screen
     * someone was never offered lands on the landing instead.
     */
    fun visibleTo(viewer: PayrollViewer, enteredAsTool: Boolean): Boolean =
        this == Landing || PayrollTile.visibleTo(viewer, enteredAsTool).any { it.destination == this }

    companion object {
        /**
         * The screen a route names. An unknown tile is the landing — the web's
         * `<Navigate to=".../payroll">` for a slug `TILES` does not know.
         */
        fun forRoute(path: String): PayrollDestination {
            val segment = path.substringBefore('?').removePrefix(PAYROLL_PATH).trim('/').substringBefore('/')
            return entries.firstOrNull { it.segment == segment } ?: Landing
        }

        /**
         * Whether the route carries the web's tool-tile marker, `?entry=tool`.
         * That entry is the producer's: an accountant arriving through it is
         * offered the producer tiles, not the accountant grid.
         */
        fun enteredAsTool(path: String): Boolean =
            path.substringAfter('?', "").split('&').any { it == ENTRY_TOOL }
    }
}

/**
 * The landing's tiles, in the web's order (`PayrollLandingPage.jsx` 30-104).
 */
enum class PayrollTile(
    val slug: String,
    private val titleKey: String,
    private val descriptionKey: String,
    private val tagKeys: List<String>,
    /** The web's accent: `#fc9404`, `#6366f1`, `#fb923c`, `#7a4cd6`, `#c084fc`, `#14a394`. */
    val accent: Long,
) {
    Processing(
        "processing",
        S.desktop_payroll_processing,
        S.desktop_payroll_tile_processing_description,
        listOf(S.desktop_payroll_day_view, S.desktop_payroll_week_to_date, S.desktop_payroll_weekly_total),
        accent = 0xFFFC9404,
    ),
    Run(
        "run",
        S.desktop_payroll_run,
        S.desktop_payroll_tile_run_description,
        listOf(S.desktop_payroll_pipeline, S.desktop_payroll_dept_totals, S.desktop_payroll_crew_approval),
        accent = 0xFF6366F1,
    ),
    ProducerBoard(
        "producer-board",
        S.desktop_payroll_producer_board,
        S.desktop_payroll_tile_producer_board_description,
        listOf(S.desktop_payroll_override_times, S.desktop_payroll_approve_days, S.desktop_payroll_full_crew_board),
        accent = 0xFFFB923C,
    ),
    ProductionReport(
        "production-report",
        S.desktop_payroll_production_report,
        S.desktop_payroll_tile_production_report_description,
        listOf(
            S.desktop_payroll_crew_with_deals,
            S.desktop_payroll_fill_from_report_tag,
            S.desktop_payroll_local_estimate,
        ),
        accent = 0xFF7A4CD6,
    ),
    History(
        "accountant-payroll",
        S.desktop_payroll_history,
        S.desktop_payroll_tile_history_description,
        listOf(S.ah_post_to_ledger, S.desktop_payroll_corrections),
        accent = 0xFFC084FC,
    ),
    EntrySetup(
        "config",
        S.desktop_payroll_entry_setup,
        S.desktop_payroll_tile_setup_description,
        listOf(S.desktop_approvers, S.desktop_payroll_pay_period, S.desktop_payroll_groups),
        accent = 0xFF14A394,
    ),
    ;

    val title: String get() = str(titleKey)
    val description: String get() = str(descriptionKey)
    val tags: List<String> get() = tagKeys.map { str(it) }

    /** The screen this tile opens, or null for Entry Setup, which is the Account Hub's. */
    val destination: PayrollDestination?
        get() = when (this) {
            Processing -> PayrollDestination.Processing
            Run -> PayrollDestination.Run
            ProducerBoard -> PayrollDestination.ProducerBoard
            ProductionReport -> PayrollDestination.ProductionReport
            History -> PayrollDestination.History
            EntrySetup -> null
        }

    companion object {
        /**
         * The producer's two tiles. The Payroll TOOL TILE on the Film Tools
         * grid is the producer entry point, so it offers these and nothing
         * else — to an accountant as much as to anyone; the accountant grid is
         * the Account Hub's side-nav, which offers everything but these.
         */
        val PRODUCER: Set<PayrollTile> = setOf(ProducerBoard, ProductionReport)

        /**
         * Who is offered which tile — the web's filter verbatim
         * (`PayrollLandingPage.jsx` 150-166):
         *
         *  - an accountant from the Account Hub gets every accountant tile;
         *  - an accountant from the tool tile gets the producer tiles;
         *  - anyone else with the payroll tool's view access gets the producer
         *    tiles, whichever way they came;
         *  - anyone else gets nothing, which the landing says.
         */
        fun visibleTo(viewer: PayrollViewer, enteredAsTool: Boolean): List<PayrollTile> = when {
            viewer.seesAccountantViews ->
                entries.filter { if (enteredAsTool) it in PRODUCER else it !in PRODUCER }
            viewer.canView -> entries.filter { it in PRODUCER }
            else -> emptyList()
        }
    }
}

/**
 * Production Setup's payroll section — the Entry Setup tile's destination.
 * The hub reads the `setup` query and opens the section.
 */
const val PAYROLL_ENTRY_SETUP_ROUTE = "/film-tools/account-hub/production-setup?setup=payroll"

const val PAYROLL_PATH = "/film-tools/payroll"

/** The web's tool-tile marker, as the Film Tools grid stamps it on the route. */
const val ENTRY_TOOL = "entry=tool"
