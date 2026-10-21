package com.zillit.desktop.feature.addashboard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
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
import com.zillit.desktop.core.designsystem.component.ZillitPageHeader
import com.zillit.desktop.core.designsystem.component.ZillitSideNav
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitToast
import com.zillit.desktop.core.designsystem.component.ZillitToastTone
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.addashboard.domain.AdDates
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
 * The sidebar's four groups.
 *
 * The web wraps the same set in a two-way switcher because its sidebar is
 * narrow; here every group is a card of its own and all four stay reachable.
 * A page hidden from a non-posting viewer is left out, and a group that empties
 * is dropped with it.
 */
internal fun AdUiState.navSections(): List<SideNavSection> = AdSection.entries.mapNotNull { section ->
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
 * The page's own header: its title, the day it is on, and its one action.
 *
 * The day pill and the phase stepper are hidden on the pages that are not
 * about one shoot day — the web's `NO_PHASE_TABS`, which exists because the
 * register and the rates are not a day.
 */
@Composable
private fun AdHeader(state: AdUiState, onEvent: (AdEvent) -> Unit) {
    Column {
        ZillitPageHeader(
            title = state.destination.label,
            eyebrow = str(S.desktop_ad_supporting_artistes),
            description = state.destination.headerDescription(),
            actions = {
                if (state.destination.aboutOneDay()) DayPill(state)
                ZillitButton(
                    text = str(S.refresh_text),
                    onClick = { onEvent(AdEvent.Refresh) },
                    variant = ButtonVariant.Tertiary,
                    loading = state.loading,
                )
            },
        )
        if (state.destination.aboutOneDay() && state.onToday > 0) PhaseTracker(state)
    }
}

/** Whether a page is about one shoot day, and so wears the day pill. */
private fun AdDestination.aboutOneDay(): Boolean = when (this) {
    AdDestination.TodaysList,
    AdDestination.AddExtras,
    AdDestination.Attendance,
    AdDestination.Additionals,
    AdDestination.LiveDay,
    AdDestination.SubmitDay,
    -> true

    else -> false
}

private fun AdDestination.headerDescription(): String? = when (this) {
    AdDestination.WrapReport -> str(S.desktop_ad_wrap_report_description)
    AdDestination.ExhibitG -> str(S.desktop_ad_exhibit_g_description)
    AdDestination.Queries -> str(S.desktop_ad_queries_description)
    AdDestination.PoQueue -> str(S.desktop_ad_po_queue_description)
    AdDestination.Config -> str(S.desktop_ad_rates_description)
    else -> null
}

/** Day N, its date, and its status — what every shoot-day page is showing. */
@Composable
private fun DayPill(state: AdUiState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.dayNumber?.let { number ->
            ZillitStatusPill(label = str(S.desktop_ad_day_number, number), tone = StatusTone.Progress)
        }
        ZillitText(
            text = AdDates.formatDayFull(state.shootDate),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
        state.day?.let { day ->
            ZillitStatusPill(
                label = day.status.label,
                tone = if (day.status.locked) StatusTone.Done else StatusTone.Pending,
            )
        }
    }
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.phases.forEach { (phase, phaseState) ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (phaseState) {
                    PhaseState.Done -> ZillitIcon(
                        icon = ZillitIcons.Tick,
                        tint = ZillitTheme.colors.success,
                        size = ZillitTheme.spacing.md,
                    )

                    PhaseState.Active -> Box(
                        modifier = Modifier
                            .size(ZillitTheme.spacing.xs)
                            .clip(CircleShape)
                            .background(ZillitTheme.colors.accent),
                    )

                    PhaseState.Todo -> Box(
                        modifier = Modifier
                            .size(ZillitTheme.spacing.xs)
                            .clip(CircleShape)
                            .background(ZillitTheme.colors.border),
                    )
                }
                ZillitText(
                    text = "${phase.number}. ${phase.label}",
                    style = ZillitTheme.typography.labelSmall.copy(
                        fontWeight = if (phaseState == PhaseState.Active) FontWeight.SemiBold else null,
                    ),
                    color = when (phaseState) {
                        PhaseState.Active -> ZillitTheme.colors.textPrimary
                        PhaseState.Done -> ZillitTheme.colors.textSecondary
                        PhaseState.Todo -> ZillitTheme.colors.textMuted
                    },
                )
            }
        }
    }
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
        if (state.destination.aboutOneDay() && state.day?.status?.locked == true) {
            ZillitNotice(
                text = str(S.desktop_ad_day_locked_notice),
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
