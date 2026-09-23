package com.zillit.desktop.feature.accounthub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.accounthub.domain.HubArea
import com.zillit.desktop.feature.accounthub.domain.HubBadges
import com.zillit.desktop.feature.accounthub.domain.HubItem
import com.zillit.desktop.feature.accounthub.domain.HubNavigation
import com.zillit.desktop.feature.accounthub.domain.HubSection
import com.zillit.desktop.feature.accounthub.domain.HubTarget
import com.zillit.desktop.feature.accounthub.domain.SetupTourIntro
import com.zillit.desktop.feature.accounthub.domain.SetupTourTarget
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.HubSideRail
import com.zillit.desktop.feature.accounthub.ui.components.RailRow
import com.zillit.desktop.feature.accounthub.ui.components.RailSection
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.pages.ApproversPage
import com.zillit.desktop.feature.accounthub.ui.pages.BibleReportPage
import com.zillit.desktop.feature.accounthub.ui.pages.BudgetPage
import com.zillit.desktop.feature.accounthub.ui.pages.ChartOfAccountsPage
import com.zillit.desktop.feature.accounthub.ui.pages.FormConfigPage
import com.zillit.desktop.feature.accounthub.ui.pages.PeriodClosePage
import com.zillit.desktop.feature.accounthub.ui.pages.ProductionSetupPage
import com.zillit.desktop.feature.accounthub.ui.pages.TrialBalancePage
import com.zillit.desktop.feature.accounthub.ui.pages.VendorsPage

/**
 * The Account Hub console — the web's `AccountHubShell` and `AccountHubSidebar`.
 *
 * ## A sidebar, not tabs
 *
 * The hub reaches a dozen places across five groups. As a tab strip they
 * neither fit nor group, which is why the web ships a sidebar here and tabs
 * everywhere else — and why the groups are rendered as separate cards, so the
 * eye lands on a group of three rather than a list of twelve. Above the
 * groups sit the web's two header cards: the back arrow with "Account Hub",
 * and the theme toggle.
 *
 * ## Half the rows leave
 *
 * Purchase Orders, Payroll, Timecard and the rest are separate tools with their
 * own windows. Selecting one is a hand-off, not navigation: it raises an effect
 * and the console stays where it was, so coming back does not mean re-finding
 * your place.
 */
@Composable
fun AccountHubScreen(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the host wired file storage; false leaves Agreements read-only. */
    canAttachAgreements: Boolean = false,
    /** Now, for the period close's dates. */
    nowMillis: Long = 0,
    /** Whether the host wired storage; false hides the budget import. */
    canImportBudget: Boolean = false,
    /** Whether the host wired the export seams; false hides the export menus. */
    canExport: Boolean = false,
    /** Whether stored documents can be opened in the OS. */
    canOpenDocuments: Boolean = false,
    /**
     * Renders another film tool inside the console, as the web's nested routes
     * do; null means the host opens tools in their own windows instead.
     */
    embed: (@Composable (EmbeddedTool) -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        if (state.viewer.isBlocked) {
            ZillitEmptyState(
                title = str(S.desktop_hub_no_access_to_the_account_hub),
                message = str(S.desktop_hub_an_administrator_has_not_granted_you_view_rights_for_the),
                icon = ZillitIcons.Shield,
            )
            return@Box
        }

        val fullBleed = HubNavigation.isFullBleed(state.area, state.embedded?.path, state.viewer)
        Row(modifier = Modifier.fillMaxSize()) {
            // No rule between them: the web floats the sidebar's cards on the
            // page itself. Withheld where the surface carries its own way
            // back, as the web's shell does — see `isFullBleed`.
            if (!fullBleed) HubSidebar(state, onEvent)

            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val shown = state.embedded
                if (shown != null && embed != null) {
                    embed(shown)
                } else {
                    AccountHubBody(
                        state,
                        onEvent,
                        canAttachAgreements,
                        nowMillis,
                        canImportBudget,
                        canExport,
                        canOpenDocuments,
                        canEmbed = embed != null,
                    )
                }
            }
        }

        // The tour points at the sidebar, so it waits while the sidebar is
        // away — the web's `showSetupTour` requires `showHubSidebar`.
        if (!fullBleed) SetupTourOverlay(state, onEvent)

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(AccountHubEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

@Composable
private fun AccountHubBody(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
    canAttachAgreements: Boolean,
    nowMillis: Long,
    canImportBudget: Boolean,
    canExport: Boolean,
    canOpenDocuments: Boolean,
    canEmbed: Boolean,
) {
    when (state.area) {
        HubArea.ProductionSetup -> ProductionSetupPage(state, onEvent, canAttachAgreements, canOpenDocuments)
        HubArea.ChartOfAccounts -> ChartOfAccountsPage(state, onEvent, canImportBudget = canImportBudget)
        HubArea.Vendors -> VendorsPage(state, onEvent)
        HubArea.Approvers -> ApproversPage(state, onEvent)
        HubArea.Budget -> BudgetPage(state, onEvent, canImport = canImportBudget, canOpenDocuments = canOpenDocuments)
        HubArea.TrialBalance -> TrialBalancePage(state, onEvent, canExport)
        HubArea.PeriodClose -> PeriodClosePage(state, onEvent, nowMillis)
        HubArea.BibleReport -> BibleReportPage(state, onEvent, canExport)
        HubArea.FormConfig -> FormConfigPage(state, onEvent)
        // Not a loading state — a department user reaching the console has the
        // spend tools and nothing the hub itself renders. Their first one has
        // already been opened in its own window (`HubNavigation.landingTool`),
        // so this says where that went rather than reading as a dead end.
        null -> ZillitEmptyState(
            title = if (canEmbed) str(S.desktop_pick_a_tool) else str(S.desktop_hub_your_tools_are_open),
            message = if (canEmbed) {
                str(S.desktop_hub_own_screens_are_the_accounts_departments_rows_on_left)
            } else {
                str(S.desktop_hub_own_screens_are_the_accounts_departments_own_windows)
            },
            icon = ZillitIcons.Ledger,
        )
    }
}

// -- sidebar ------------------------------------------------------------------

/**
 * The web's sidebar (`AccountHubSidebar.jsx`) on the hub's shared rail: the
 * header card, then one card per group, each row with its module's own glyph.
 *
 * No theme card, though the web has one: the app's own Settings already
 * switches the theme for every window, and a second switch in one tool's
 * sidebar was removed at the user's request (2026-09-13).
 */
@Composable
private fun HubSidebar(
    state: AccountHubUiState,
    onEvent: (AccountHubEvent) -> Unit,
) {
    HubSideRail(
        title = str(S.ah_account_hub),
        backLabel = str(S.desktop_hub_back_to_film_tools),
        onBack = { onEvent(AccountHubEvent.Back) },
        sections = state.sections.map { section ->
            RailSection(
                title = section.title,
                rows = section.items.map { item ->
                    RailRow(
                        id = navId(item),
                        label = item.label,
                        icon = iconFor(item),
                        active = isActive(item, state),
                        count = HubBadges.countFor(item.id, state.viewer.isAccountant, state.badges),
                    )
                },
            )
        },
        onSelect = { id -> onNavSelect(state, id, onEvent) },
        badgeCap = HubBadges.OVERFLOW,
    )
}

/**
 * Routes a sidebar click.
 *
 * Ids are matched against the hub's own areas first: an area's id and its slug
 * are the same string, and a hand-off row can never collide with one because
 * the tool rows are keyed by tool id.
 */
private fun onNavSelect(
    state: AccountHubUiState,
    id: String,
    onEvent: (AccountHubEvent) -> Unit,
) {
    val item = state.sections.flatMap { it.items }.firstOrNull { navId(it) == id } ?: return
    when (item.target) {
        is HubTarget.Page -> onEvent(AccountHubEvent.Open(item.target.area))
        is HubTarget.Tool -> onEvent(AccountHubEvent.OpenTool(item))
    }
}

/**
 * Which row is lit: the embedded tool's when one is on screen — including a
 * sub-route it navigated to within itself — and the open area's otherwise.
 */
private fun isActive(item: HubItem, state: AccountHubUiState): Boolean = when (val target = item.target) {
    is HubTarget.Tool -> state.embedded?.path?.startsWith(target.toolPath) == true
    is HubTarget.Page -> state.embedded == null && target.area == state.area
}

/** An area is addressed by its slug so the active row survives a reload. */
private fun navId(item: HubItem): String = when (item.target) {
    is HubTarget.Page -> item.target.area.slug
    is HubTarget.Tool -> item.id
}

/**
 * Each row's own glyph from the web's Account Hub set — the one
 * `AccountHubSidebar.jsx` draws for that module. The generic set this used
 * before gave five pairs of rows the same picture (Invoices and Bible Report,
 * Payroll and Bank Reconciliation, …).
 */
@Suppress("CyclomaticComplexMethod") // One glyph per sidebar row.
internal fun iconFor(item: HubItem): ImageVector = when (item.id) {
    "production-setup" -> AhIcons.Settings
    "purchase-orders" -> AhIcons.PurchaseOrder
    "invoices" -> AhIcons.Invoice
    "card-expenses" -> AhIcons.CardExpense
    "cash-expenses" -> AhIcons.CashExpense
    "payroll" -> AhIcons.Payroll
    "cost-report" -> AhIcons.CostReport
    "period-close" -> AhIcons.Lock
    "vendors" -> AhIcons.Vendor
    "trial-balance" -> AhIcons.Ledger
    "bible-report" -> AhIcons.Document
    "bank-reconciliation" -> AhIcons.Bank
    "tax-filing" -> AhIcons.Tax
    "approvers" -> AhIcons.Approver
    "budget" -> AhIcons.Coins
    "chart-of-accounts" -> AhIcons.ChartOfAccounts
    "form-config" -> AhIcons.Form
    else -> AhIcons.AccountHub
}

// -- the setup tour -------------------------------------------------------------

/**
 * The setup tour — the web's intro modal, then one step per gap.
 *
 * The web anchors each step to the sidebar row or the tab it points at; this
 * names the place instead, with the same copy, because a Compose overlay has
 * no DOM to anchor to. Every way out marks the tour seen.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod") // A screen, read top to bottom; the order is the reading order.
@Composable
private fun SetupTourOverlay(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val tour = state.tour
    val step = tour.current
    ZillitDialogShell(
        title = if (tour.intro) SetupTourIntro.TITLE else step?.title.orEmpty(),
        subtitle = if (tour.intro) null else str(S.dm_wizard_step_label, tour.index + 1, tour.steps.size),
        icon = ZillitIcons.Info,
        visible = tour.open && (tour.intro || step != null),
        onDismiss = { onEvent(AccountHubEvent.TourClose) },
        actions = {
            if (!tour.intro && tour.index > 0) {
                ZillitButton(
                    text = str(S.back),
                    onClick = { onEvent(AccountHubEvent.TourBack) },
                    variant = ButtonVariant.Tertiary,
                )
            }
            ZillitButton(
                text = str(S.skip),
                onClick = { onEvent(AccountHubEvent.TourClose) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = when {
                    tour.intro -> str(S.desktop_next_arrow)
                    tour.isLast -> str(S.ah_done)
                    else -> str(S.next)
                },
                onClick = { onEvent(AccountHubEvent.TourNext) },
            )
        },
    ) {
        if (tour.intro) {
            ZillitText(text = SetupTourIntro.BODY, style = ZillitTheme.typography.bodyMedium)
            FieldHint(
                if (tour.steps.size == 1) {
                    str(S.desktop_hub_one_thing_still_needs_setting_up)
                } else {
                    str(S.desktop_hub_n_things_still_need_setting_up, tour.steps.size)
                },
            )
        } else if (step != null) {
            ZillitText(text = step.body, style = ZillitTheme.typography.bodyMedium)
            step.notes.forEach { (label, body) ->
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    MonoLabel(label, modifier = Modifier.width(NOTE_LABEL))
                    ZillitText(text = body, style = ZillitTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
            }
            FieldHint(
                when (step.target) {
                    SetupTourTarget.Nav -> str(S.desktop_hub_find_it_under_setup_production_setup)
                    SetupTourTarget.DealTab -> str(S.desktop_hub_find_it_under_production_setup_deal_memo_setup)
                    SetupTourTarget.Chart -> str(S.desktop_hub_find_it_under_configuration_chart_of_accounts)
                },
            )
            // The segmented indicator the web's tour draws under each step.
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                tour.steps.forEachIndexed { index, _ ->
                    Box(
                        Modifier
                            .width(if (index == tour.index) INDICATOR_ACTIVE else INDICATOR)
                            .height(INDICATOR_HEIGHT)
                            .clip(CircleShape)
                            .background(
                                if (index == tour.index) ZillitTheme.colors.accent else ZillitTheme.colors.border,
                            ),
                    )
                }
            }
        }
    }
}

/**
 * The frame every hub screen sits in.
 *
 * A page that scrolls its own body owns the scrolling; this supplies only the
 * padded column, because a page hosting a data table must not be nested inside
 * a scrolling parent — the table is measured against an unbounded height and
 * Compose refuses outright.
 */
@Composable
internal fun HubPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ZillitTheme.colors.canvas)
            .padding(ZillitTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
        content = content,
    )
}

/** The section headings' exact case, for the render test that keys on the sidebar. */
internal fun HubSection.headingText(): String = title.uppercase()

/**
 * The web's own shell column (`gridTemplateColumns.shell: 220px 1fr`), plus
 * the room a desktop row needs for its icon — "Invoices / Accounts Payable"
 * has to fit, and a clipped navigation label reads as a different screen.
 */
private val NOTE_LABEL = 110.dp
private val INDICATOR = 8.dp
private val INDICATOR_ACTIVE = 20.dp
private val INDICATOR_HEIGHT = 4.dp
