package com.zillit.desktop.feature.scriptsupervision.ui

import androidx.compose.runtime.Composable
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.feature.scriptsupervision.ui.screens.ActionContinuityScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.CameraDetailsScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.CoverageScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.DialogueNotesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.EditorDailyLogScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.EditorNotesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.FacingPagesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.MatchingNotesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.ScenesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.SoundReportsScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.StuntSafetyScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.TakesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.VfxPlatesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.WeatherLightingScreen

/**
 * Which screen each page of the menu opens — the web's inner `<Routes>`.
 *
 * The pages NOT yet ported say so by name rather than opening blank: this
 * module arrived on the web on 2026-10-23 as forty screens at once, and the
 * ones listed in [NotPorted] are the heavy half (the quick-log form, the
 * character and look books, the lined script and the breakdown, the dashboard
 * and the template editor). Naming each one is the point — a reader who opens
 * Characters learns that it is coming, not that the tool is broken.
 */
@Composable
internal fun SsRoutes(page: SsPage) {
    when (page) {
        SsPage.Scenes -> ScenesScreen()
        SsPage.Takes -> TakesScreen()
        else -> SsLogRoutes(page)
    }
}

/**
 * The twelve continuity logs. One function of their own, not for tidiness: a
 * single `when` over every page of the menu is one branch too complex for the
 * gate, and these twelve are one screen behind a spec anyway.
 */
@Composable
private fun SsLogRoutes(page: SsPage) {
    when (page) {
        SsPage.Camera -> CameraDetailsScreen()
        SsPage.Dialogue -> DialogueNotesScreen()
        SsPage.ActionContinuity -> ActionContinuityScreen()
        SsPage.SoundReports -> SoundReportsScreen()
        SsPage.Matching -> MatchingNotesScreen()
        SsPage.StuntSafety -> StuntSafetyScreen()
        SsPage.WeatherLighting -> WeatherLightingScreen()
        SsPage.VfxPlates -> VfxPlatesScreen()
        SsPage.EditorNotes -> EditorNotesScreen()
        SsPage.EditorDailyLog -> EditorDailyLogScreen()
        SsPage.FacingPages -> FacingPagesScreen()
        SsPage.Coverage -> CoverageScreen()
        else -> NotPorted(page)
    }
}

/**
 * A page whose port has not landed: it names itself and says so, rather than
 * opening blank.
 *
 * The same marker Costumes & Set Sync carried while it was being built.
 */
@Composable
private fun NotPorted(page: SsPage) {
    ZillitEmptyState(title = t(page.labelKey), message = t("not_ported_yet"))
}
