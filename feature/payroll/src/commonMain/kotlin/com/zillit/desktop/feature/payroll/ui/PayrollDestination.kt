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
    History("accountant-payroll", S.desktop_payroll_history),
    ;

    val label: String get() = str(labelKey)

    val path: String get() = if (segment.isEmpty()) PAYROLL_PATH else "$PAYROLL_PATH/$segment"

    /**
     * The landing is everyone's; the three accountant screens are the
     * accountant's. The web offers their tiles only on the accountant's grid
     * (`PayrollLandingPage.jsx` 150-166), so a deep link from anyone else lands
     * on the landing rather than on a screen they were never offered.
     */
    fun visibleTo(viewer: PayrollViewer): Boolean = this == Landing || viewer.seesAccountantViews

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
            path.substringAfter('?', "").split('&').any { it == "entry=tool" }
    }
}

/**
 * The landing's tiles, in the web's order (`PayrollLandingPage.jsx` 30-104).
 *
 * The producer tiles — Producer Board and Production Report Payroll — are not
 * part of this port; a viewer who would only be offered those sees the
 * landing say so rather than a grid of screens they were never given.
 */
enum class PayrollTile(
    val slug: String,
    private val titleKey: String,
    private val descriptionKey: String,
    private val tagKeys: List<String>,
    /** The web's accent: `#fc9404`, `#6366f1`, `#c084fc`, `#14a394`. */
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
            History -> PayrollDestination.History
            EntrySetup -> null
        }

    companion object {
        /**
         * Who is offered which tile — the web's filter: an accountant arriving
         * from the Account Hub gets every accountant tile; the tool-tile entry,
         * and anyone else with view access, gets the producer tiles, which this
         * port does not have.
         */
        fun visibleTo(viewer: PayrollViewer, enteredAsTool: Boolean): List<PayrollTile> =
            if (viewer.seesAccountantViews && !enteredAsTool) entries else emptyList()
    }
}

/**
 * Production Setup's payroll section — the Entry Setup tile's destination.
 * The hub reads the `setup` query and opens the section.
 */
const val PAYROLL_ENTRY_SETUP_ROUTE = "/film-tools/account-hub/production-setup?setup=payroll"

const val PAYROLL_PATH = "/film-tools/payroll"
