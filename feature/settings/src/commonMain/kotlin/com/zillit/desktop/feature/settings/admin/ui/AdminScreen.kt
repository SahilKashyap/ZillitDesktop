package com.zillit.desktop.feature.settings.admin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.strings.S
import com.zillit.desktop.core.strings.str
import com.zillit.desktop.feature.settings.admin.ui.pages.AdminDialogs
import com.zillit.desktop.feature.settings.admin.ui.pages.CompanyDetailsPage
import com.zillit.desktop.feature.settings.admin.ui.pages.CrewOrderPage
import com.zillit.desktop.feature.settings.admin.ui.pages.CrewPage
import com.zillit.desktop.feature.settings.admin.ui.pages.DeleteProductionPage
import com.zillit.desktop.feature.settings.admin.ui.pages.DepartmentsPage
import com.zillit.desktop.feature.settings.admin.ui.pages.JobTitlesPage
import com.zillit.desktop.feature.settings.admin.ui.pages.PreApprovedPage
import com.zillit.desktop.feature.settings.admin.ui.pages.ProductionNamePage
import com.zillit.desktop.feature.settings.admin.ui.pages.RightsPage
import com.zillit.desktop.feature.settings.admin.ui.pages.SosPage
import com.zillit.desktop.feature.settings.admin.ui.pages.ToolAvailabilityPage
import com.zillit.desktop.feature.settings.admin.ui.pages.ToolGroupsPage
import com.zillit.desktop.feature.settings.admin.ui.pages.UnitsPage
import com.zillit.desktop.feature.settings.admin.ui.pages.WatermarkPage
import com.zillit.desktop.feature.settings.ui.ProductionFacts

/**
 * One administration page, whichever one the route names.
 *
 * The `when` is the whole screen: every page takes the same three arguments, so
 * routing is a lookup rather than a set of conditionals, and a destination
 * added to the enum without a page here is a compile error rather than a blank
 * window.
 *
 * @param production decides which pages this production has at all. A page it
 *   does not have is refused here as well as hidden on the listing — a deep
 *   link into the shooting units of a corporate production must not render.
 */
// One branch per page. A lookup table, not branching logic — and exhaustive,
// so a destination added without a page is a compile error.
@Suppress("CyclomaticComplexMethod")
@Composable
fun AdminScreen(
    destination: AdminDestination,
    state: AdminUiState,
    onEvent: (AdminEvent) -> Unit,
    onBack: () -> Unit,
    production: ProductionFacts,
    modifier: Modifier = Modifier,
) {
    // Loading happens on arrival rather than in the provider, so a page reached
    // by any route — rail, deep link, back button — reads the same list.
    LaunchedEffect(destination) { onEvent(AdminEvent.Opened(destination)) }

    if (!destination.availableTo(production)) {
        Unavailable(destination, production, onBack, modifier)
        return
    }

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        when (destination) {
            AdminDestination.Departments -> DepartmentsPage(state, onEvent, onBack)
            AdminDestination.JobTitles -> JobTitlesPage(state, onEvent, onBack)
            AdminDestination.CrewOrder ->
                CrewOrderPage(state, onEvent, onBack, isOtherType = production.isOtherType)

            AdminDestination.Crew -> CrewPage(state, onEvent, onBack)
            AdminDestination.Rights -> RightsPage(state, onEvent, onBack)
            AdminDestination.PreApproved -> PreApprovedPage(state, onEvent, onBack)
            AdminDestination.ToolAvailability -> ToolAvailabilityPage(state, onEvent, onBack)
            AdminDestination.ToolGroups -> ToolGroupsPage(state, onEvent, onBack)
            AdminDestination.ProductionName -> ProductionNamePage(state, onEvent, onBack)
            AdminDestination.CompanyDetails -> CompanyDetailsPage(state, onEvent, onBack)
            AdminDestination.Watermark -> WatermarkPage(state, onEvent, onBack)
            AdminDestination.Sos -> SosPage(state, onEvent, onBack)

            AdminDestination.HomeUnits,
            AdminDestination.RemoteUnits,
            AdminDestination.ShootingUnits,
            -> UnitsPage(destination, state, onEvent, onBack)

            AdminDestination.DeleteProduction -> DeleteProductionPage(state, onEvent, onBack)
        }

        AdminDialogs(state, onEvent)
    }
}

/**
 * A page this production does not have.
 *
 * Reachable only by a stale deep link or a restored window, and worth saying
 * plainly: an empty page here would read as a page whose contents failed to
 * load, and the reader would keep retrying it.
 */
@Composable
private fun Unavailable(
    destination: AdminDestination,
    production: ProductionFacts,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val reason = when {
        production.isPersonal -> str(S.desktop_personal_project_nothing_to_set)
        else -> str(S.desktop_no_second_or_splinter_units)
    }

    Box(modifier.fillMaxSize().background(ZillitTheme.colors.canvas)) {
        com.zillit.desktop.core.designsystem.component.ZillitEmptyState(
            title = str(S.desktop_not_part_of_this_project, destination.title),
            message = reason,
            icon = com.zillit.desktop.core.designsystem.icon.ZillitIcons.Info,
            action = {
                com.zillit.desktop.core.designsystem.component.ZillitButton(
                    text = str(S.desktop_back_to_admin_settings),
                    onClick = onBack,
                )
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
