package com.zillit.desktop.feature.addashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.SideNavItem
import com.zillit.desktop.core.designsystem.component.SideNavSection
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdPhase
import com.zillit.desktop.feature.addashboard.domain.PhaseState
import com.zillit.desktop.feature.addashboard.ui.pages.AddExtrasPage
import com.zillit.desktop.feature.addashboard.ui.pages.AdditionalsPage
import com.zillit.desktop.feature.addashboard.ui.pages.AgencyPage
import com.zillit.desktop.feature.addashboard.ui.pages.ArtisteFormPage
import com.zillit.desktop.feature.addashboard.ui.pages.ArtisteViewPage
import com.zillit.desktop.feature.addashboard.ui.pages.AttendancePage
import com.zillit.desktop.feature.addashboard.ui.pages.BudgetPage
import com.zillit.desktop.feature.addashboard.ui.pages.ConfigPage
import com.zillit.desktop.feature.addashboard.ui.pages.ExhibitGPage
import com.zillit.desktop.feature.addashboard.ui.pages.OnboardingPage
import com.zillit.desktop.feature.addashboard.ui.pages.PoQueuePage
import com.zillit.desktop.feature.addashboard.ui.pages.QueriesPage
import com.zillit.desktop.feature.addashboard.ui.pages.RegisterPage
import com.zillit.desktop.feature.addashboard.ui.pages.ReportEditorPage
import com.zillit.desktop.feature.addashboard.ui.pages.ReportsHubPage
import com.zillit.desktop.feature.addashboard.ui.pages.RunnerPage
import com.zillit.desktop.feature.addashboard.ui.pages.SchedulePage
import com.zillit.desktop.feature.addashboard.ui.pages.SubmitDayPage
import com.zillit.desktop.feature.addashboard.ui.pages.TemplatesPage
import com.zillit.desktop.feature.addashboard.ui.pages.TodaysListPage
import com.zillit.desktop.feature.addashboard.ui.pages.UnionConfigPage

/**
 * The AD dashboard: the production's side of supporting artistes.
 *
 * Sixteen pages in four groups, so the navigation is a sidebar rather than a
 * tab strip — the web ships one for the same reason. The artiste's own view of
 * the same days, their vouchers and their pay, is a separate tool on a
 * separate service (`feature:saportal`).
 */
@Composable
fun AdScreen(state: AdUiState, onEvent: (AdEvent) -> Unit, modifier: Modifier = Modifier) {
    if (state.viewer.isBlocked) {
        ZillitEmptyState(
            title = str(S.dd_publish_no_access_badge),
            message = str(S.desktop_ad_not_given_dashboard),
            icon = ZillitIcons.Shield,
        )
        return
    }

    Box(modifier = modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        Row(modifier = Modifier.fillMaxSize()) {
            ZillitSideNav(
                sections = state.navSections(),
                activeId = state.destination.slug,
                onSelect = { slug ->
                    AdDestination.fromSlug(slug)?.let { onEvent(AdEvent.Open(it)) }
                },
                header = { WorkspaceSwitcher(state, onEvent) },
            )
            Column(modifier = Modifier.fillMaxSize()) {
                AdHeader(state, onEvent)
                ZillitDivider()
                AdBody(state, onEvent)
            }
        }

        ZillitToast(
            message = state.notice,
            onDismiss = { onEvent(AdEvent.ClearNotice) },
            tone = ZillitToastTone.Success,
        )
    }
}

/**
 * The groups of the half of the sidebar now showing.
 *
 * Thirteen rows at once is a list rather than a navigation, so the web splits
 * them in two and switches; this does the same. A page hidden from a
 * non-posting viewer is left out, and a group that empties is dropped with it.
 */
internal fun AdUiState.navSections(): List<SideNavSection> = workspace.sections.mapNotNull { section ->
    val items = sectionsFor(section).map { destination ->
        SideNavItem(
            id = destination.slug,
            label = destination.label,
            icon = destination.icon,
            count = destination.pendingCount(this),
            unread = destination.badgeKeys.sumOf { unread[it] ?: 0 },
        )
    }
    items.takeIf { it.isNotEmpty() }?.let { SideNavSection(title = section.label, items = it) }
}

/** The amber count beside a row: work this page is actually waiting on. */
private fun AdDestination.pendingCount(state: AdUiState): Int = when (this) {
    AdDestination.TodaysList -> state.onToday
    AdDestination.Attendance -> state.dayList.count { !it.isHandled }
    AdDestination.SubmitDay -> if (state.dayIsOpen) state.presentToday else 0
    AdDestination.Queries -> state.queries.rows.count { !it.resolved }
    AdDestination.PoQueue -> state.poQueue.pending.size
    else -> 0
}

/**
 * The page's own header — one line, as the web's breadcrumb bar is.
 *
 * The group in accent caps, then the page's name, then the day it is on. The
 * day pill, the counts and the phase strip are hidden on the pages that are
 * not about one shoot day: the register and the rates are not a day.
 */
@Composable
private fun AdHeader(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(
                text = state.destination.section.label.uppercase(),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.accent,
                maxLines = 1,
            )
            ZillitText(
                text = BREADCRUMB_SEPARATOR,
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
            ZillitText(
                text = state.destination.label,
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
            )
            if (state.destination.hasPhases) {
                state.dayNumber?.let { number ->
                    ZillitStatusPill(
                        label = str(S.desktop_ad_day_number, number),
                        tone = StatusTone.Progress,
                    )
                }
            }
            // Only the live day carries the counts — it is the page you stand
            // on while the unit arrives, and the one the web counts on.
            if (state.destination == AdDestination.LiveDay) {
                ZillitText(
                    text = str(S.desktop_ad_on_call_present, state.onToday, state.presentToday),
                    style = ZillitTheme.typography.labelSmall,
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.weight(1f))
            HeaderActions(state, onEvent)
        }
        if (state.destination.hasPhases) PhaseTracker(state)
    }
}

/**
 * The two halves of the sidebar.
 *
 * Each button carries the unread of the half behind it, which is the only way
 * to see a report notification land while standing in Artistes.
 */
@Composable
private fun WorkspaceSwitcher(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surfaceSunken)
            .padding(SWITCHER_INSET),
        horizontalArrangement = Arrangement.spacedBy(SWITCHER_INSET),
    ) {
        AdWorkspace.entries.filter { state.firstIn(it) != null }.forEach { workspace ->
            val active = workspace == state.workspace
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(ZillitTheme.shapes.medium)
                    .background(if (active) colors.surface else Color.Transparent)
                    .clickable { onEvent(AdEvent.OpenWorkspace(workspace)) }
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(
                    ZillitTheme.spacing.xxs,
                    Alignment.CenterHorizontally,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = workspace.label,
                    style = ZillitTheme.typography.label,
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                )
                val unread = state.unreadIn(workspace)
                if (unread > 0) UnreadChip(unread)
            }
        }
    }
}

/** The red count the nav rows wear, on a switcher button. */
@Composable
private fun UnreadChip(count: Int) {
    Box(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(ZillitTheme.colors.danger)
            .padding(horizontal = CHIP_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        ZillitText(
            text = if (count > MAX_CHIP_COUNT) "$MAX_CHIP_COUNT+" else count.toString(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textOnAccent,
            maxLines = 1,
        )
    }
}

/**
 * The page's own actions, as the web's `ACTIONS` map lists them.
 *
 * All of them write, so a viewer who may only look gets none — which is the
 * web's rule, not an extra guard: the pages behind them are still readable.
 */
@Composable
private fun RowScope.HeaderActions(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    if (!state.viewer.canPost) return
    when (state.destination) {
        AdDestination.TodaysList -> {
            // A locked day takes its two add paths away; the day's state has
            // to have arrived first, or they flash in and vanish.
            if (!state.statusPending && state.day?.status?.locked != true) {
                ZillitButton(
                    text = str(S.desktop_ad_add_direct),
                    onClick = { onEvent(FormEvent.Open(null)) },
                    variant = ButtonVariant.Tertiary,
                )
                ZillitButton(
                    text = str(S.desktop_ad_import_list),
                    onClick = { onEvent(AdEvent.Open(AdDestination.Onboarding)) },
                    variant = ButtonVariant.Tertiary,
                )
            }
            SubmitDayAction(state, onEvent)
        }

        AdDestination.LiveDay -> {
            ZillitButton(
                text = str(S.desktop_ad_todays_list),
                onClick = { onEvent(AdEvent.Open(AdDestination.TodaysList)) },
                variant = ButtonVariant.Tertiary,
            )
            SubmitDayAction(state, onEvent)
        }

        AdDestination.SubmitDay -> SubmitDayAction(state, onEvent)

        AdDestination.Register -> {
            ZillitButton(
                text = str(S.desktop_ad_upload_agency_list),
                onClick = { onEvent(AdEvent.Open(AdDestination.Onboarding)) },
                variant = ButtonVariant.Tertiary,
            )
            ZillitButton(
                text = str(S.desktop_ad_add_direct_extra),
                onClick = { onEvent(FormEvent.Open(null)) },
                variant = ButtonVariant.Primary,
            )
        }

        AdDestination.Onboarding -> ZillitButton(
            text = str(S.desktop_ad_manual_entry),
            onClick = { onEvent(FormEvent.Open(null)) },
            variant = ButtonVariant.Primary,
        )

        AdDestination.Config -> ZillitButton(
            text = str(S.txt_save_changes),
            onClick = { onEvent(RatesEvent.Save) },
            variant = ButtonVariant.Primary,
            enabled = state.rates.dirty && !state.rates.saving,
            loading = state.rates.saving,
        )

        else -> Unit
    }
}

/**
 * Submit Day — shown only once the day is ready for it.
 *
 * The web hangs it off the fourth phase being the live one, so it appears when
 * everybody is wrapped and disappears again once the day is in.
 */
@Composable
private fun RowScope.SubmitDayAction(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    val ready = state.phases.any { (phase, phaseState) ->
        phase == AdPhase.SubmitAndPo && phaseState == PhaseState.Active
    }
    if (!ready) return
    ZillitButton(
        text = str(S.desktop_ad_submit_day),
        onClick = { onEvent(AdEvent.Open(AdDestination.SubmitDay)) },
        variant = ButtonVariant.Primary,
    )
}

/**
 * How far through the day the unit is.
 *
 * Worked out from the rows, not from the day's status: the status comes from
 * the server and says nothing about whether everybody has been marked. Step 3
 * reads "Additionals" but means "attendance done, not everyone wrapped", which
 * is the web's own wording.
 */
@Composable
private fun PhaseTracker(state: AdUiState) {
    val phases = state.phases
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(ZillitTheme.colors.surfaceSunken)
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        phases.forEachIndexed { index, (phase, phaseState) ->
            if (index > 0) PhaseConnector(done = phases[index - 1].second == PhaseState.Done)
            PhaseStep(phase, phaseState)
        }
    }
}

/** One step of the strip: its dot, its name, and amber while it is the live one. */
@Composable
private fun PhaseStep(phase: AdPhase, phaseState: PhaseState) {
    val colors = ZillitTheme.colors
    val active = phaseState == PhaseState.Active
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.pill)
            .background(if (active) colors.accentSoft else Color.Transparent)
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (phaseState) {
            PhaseState.Done -> ZillitIcon(
                icon = ZillitIcons.Tick,
                tint = colors.success,
                size = PHASE_TICK,
            )

            PhaseState.Active -> Box(
                modifier = Modifier.size(PHASE_DOT).clip(CircleShape).background(colors.accent),
            )

            PhaseState.Todo -> Box(
                modifier = Modifier.size(PHASE_DOT).clip(CircleShape).background(colors.border),
            )
        }
        ZillitText(
            text = phase.label,
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = if (active) FontWeight.SemiBold else null,
            ),
            color = when (phaseState) {
                PhaseState.Done -> colors.success
                PhaseState.Active -> colors.accentText
                PhaseState.Todo -> colors.textMuted
            },
            maxLines = 1,
        )
    }
}

/** The rule between two steps — green once the step behind it is done. */
@Composable
private fun PhaseConnector(done: Boolean) {
    Box(
        modifier = Modifier
            .padding(horizontal = ZillitTheme.spacing.xs)
            .width(PHASE_CONNECTOR_WIDTH)
            .height(PHASE_CONNECTOR_HEIGHT)
            .clip(ZillitTheme.shapes.pill)
            .background(if (done) ZillitTheme.colors.success else ZillitTheme.colors.border),
    )
}

@Composable
private fun ColumnScope.AdBody(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
        // A viewer who may look but not change is told once, rather than
        // discovering it button by button.
        if (state.viewer.isReadOnly) {
            ZillitNotice(
                text = str(S.desktop_ad_read_only_notice),
                tone = StatusTone.Neutral,
                icon = ZillitIcons.Info,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // A locked day says so once too: every control on it is gone, and
        // without this the page would simply look oddly bare.
        val lockedStatus = state.day?.status?.takeIf { state.destination.hasPhases && it.locked }
        if (lockedStatus != null) {
            ZillitNotice(
                // The notice names the status it is locked under — the text
                // carries a placeholder for it, and the header no longer
                // shows the status pill the web never had.
                text = str(S.desktop_ad_day_locked_notice, lockedStatus.label),
                tone = StatusTone.Done,
                icon = ZillitIcons.Lock,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        state.error?.let { message ->
            ZillitNotice(
                text = message,
                tone = StatusTone.Rejected,
                icon = ZillitIcons.Warning,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                // A form or a detail takes the whole column, as its own route
                // does on the web.
                state.artisteForm != null -> ArtisteFormPage(state, onEvent)
                state.artisteDetail != null -> ArtisteViewPage(state, onEvent)
                state.rates.unionEditor != null -> UnionConfigPage(state, onEvent)
                state.reports.editor != null -> ReportEditorPage(state, onEvent)
                else -> AdPage(state, onEvent)
            }
            if (state.loading && state.isEmptyEverywhere) {
                ZillitSpinner(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

@Suppress("CyclomaticComplexMethod") // A dispatch table; one line per page.
@Composable
private fun AdPage(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    when (state.destination) {
        AdDestination.WrapReport -> ReportsHubPage(state, onEvent)
        AdDestination.ExhibitG -> ExhibitGPage(state, onEvent)
        AdDestination.Templates -> TemplatesPage(state, onEvent)
        AdDestination.LiveDay -> RunnerPage(state, onEvent)
        AdDestination.Schedule -> SchedulePage(state, onEvent)
        AdDestination.Register -> RegisterPage(state, onEvent)
        AdDestination.Onboarding -> OnboardingPage(state, onEvent)
        AdDestination.Agency -> AgencyPage(state, onEvent)
        AdDestination.Queries -> QueriesPage(state, onEvent)
        AdDestination.Config -> ConfigPage(state, onEvent)
        AdDestination.PoQueue -> PoQueuePage(state)
        AdDestination.TodaysList -> TodaysListPage(state, onEvent)
        AdDestination.AddExtras -> AddExtrasPage(state, onEvent)
        AdDestination.Attendance -> AttendancePage(state, onEvent)
        AdDestination.Additionals -> AdditionalsPage(state, onEvent)
        AdDestination.SubmitDay -> SubmitDayPage(state, onEvent)
        AdDestination.Budget -> BudgetPage()
    }
}

/** Whether there is nothing on screen yet, which is when a spinner helps. */
private val AdUiState.isEmptyEverywhere: Boolean
    get() = artistes.isEmpty() && dayList.isEmpty() && shootDays.isEmpty() &&
        reports.merged.isEmpty() && queries.rows.isEmpty()

/** The "/" between the group and the page, as the web's breadcrumb draws it. */
private const val BREADCRUMB_SEPARATOR = "/"

/** Past this the chip reads "99+" rather than growing the sidebar. */
private const val MAX_CHIP_COUNT = 99

private val PHASE_DOT: Dp = 8.dp
private val PHASE_TICK: Dp = 14.dp
private val PHASE_CONNECTOR_WIDTH: Dp = 30.dp
private val PHASE_CONNECTOR_HEIGHT: Dp = 2.dp
private val SWITCHER_INSET: Dp = 3.dp
private val CHIP_PADDING: Dp = 6.dp
