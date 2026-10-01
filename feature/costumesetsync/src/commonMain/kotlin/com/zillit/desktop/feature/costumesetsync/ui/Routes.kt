package com.zillit.desktop.feature.costumesetsync.ui

import androidx.compose.runtime.Composable
import com.zillit.desktop.feature.costumesetsync.ui.screens.ActorsScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.BudgetScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.ChangeDetailScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.CharacterDetailScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.CharactersScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.CleaningDetailScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.CleaningScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.ContinuityBookScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.ContinuityOnSetScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.CostumeDetailScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.CostumesScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.DashboardScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.FittingDetailScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.FittingsScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.GalleryScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.LabelsScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.NotificationsScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.ReportsScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.SceneDetailScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.ScenesScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.TicketKind
import com.zillit.desktop.feature.costumesetsync.ui.screens.TicketsScreen
import com.zillit.desktop.feature.costumesetsync.ui.screens.VendorsScreen

/**
 * The tool's inner `<Routes>` (the web's `SyncOnsetApp.jsx`). A screen reads
 * its own arguments from `LocalSync.current.nav.current`: `segments[1]` is the
 * record id on a detail route, the query carries the filters.
 */
@Composable
internal fun SyncRoutes(route: SyncRoute) {
    val id = route.segments.getOrNull(1).orEmpty()
    when (route.head) {
        "dashboard" -> DashboardScreen()
        "breakdown" -> ScenesScreen(initialView = "breakdown")
        "scenes" -> if (id.isEmpty()) ScenesScreen(initialView = "scenes") else SceneDetailScreen(id)
        "changes" -> ChangeDetailScreen(id)
        "characters" -> if (id.isEmpty()) CharactersScreen() else CharacterDetailScreen(id)
        "actors" -> ActorsScreen()
        "costumes" -> if (id.isEmpty()) CostumesScreen() else CostumeDetailScreen(id)
        else -> SyncWorkflowRoutes(route, id)
    }
}

@Composable
private fun SyncWorkflowRoutes(route: SyncRoute, id: String) {
    when (route.head) {
        "cleaning" -> if (id.isEmpty()) CleaningScreen() else CleaningDetailScreen(id)
        "fittings" -> if (id.isEmpty()) FittingsScreen() else FittingDetailScreen(id)
        "alterations" -> TicketsScreen(TicketKind.Alterations)
        "damages" -> TicketsScreen(TicketKind.Damages)
        "missing" -> TicketsScreen(TicketKind.Missing)
        "labels" -> LabelsScreen()
        "vendors" -> VendorsScreen()
        else -> SyncReportRoutes(route, id)
    }
}

@Composable
private fun SyncReportRoutes(route: SyncRoute, id: String) {
    when (route.head) {
        "continuity" -> if (id == "book") ContinuityBookScreen() else ContinuityOnSetScreen()
        "reports" -> ReportsScreen()
        "budget" -> BudgetScreen()
        "gallery" -> GalleryScreen()
        "notifications" -> NotificationsScreen()
        else -> DashboardScreen()
    }
}
