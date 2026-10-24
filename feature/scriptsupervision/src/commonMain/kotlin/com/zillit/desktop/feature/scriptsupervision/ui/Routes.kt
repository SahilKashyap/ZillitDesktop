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
import com.zillit.desktop.feature.scriptsupervision.ui.screens.characters.ActorsScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.characters.CharactersRoutes
import com.zillit.desktop.feature.scriptsupervision.ui.screens.characters.StoryDaysScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.looks.BoxesScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.looks.ContinuityPhotosScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.looks.LooksRoutes
import com.zillit.desktop.feature.scriptsupervision.ui.screens.looks.PropsWardrobeScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.quicklog.QuickLogScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.CallSheetImportScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.ElementManagerScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.ProductionScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.ScriptBreakdownScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.ScriptImportScreen
import com.zillit.desktop.feature.scriptsupervision.ui.screens.script.ScriptRevisionsScreen

/**
 * Which screen each page of the menu opens — the web's inner `<Routes>`.
 *
 * A page whose inner position is not the page's own screen asks [SsNav] for
 * it, rather than taking an id in [SsPage]: see [SsView] for why the menu and
 * the detail screens are kept apart.
 *
 * The pages NOT yet ported say so by name rather than opening blank: this
 * module arrived on the web on 2026-10-23 as forty screens at once, and
 * [NotPorted] names the ones still coming. Naming each one is the point — a
 * reader who opens it learns that it is coming, not that the tool is broken.
 */
@Composable
internal fun SsRoutes(page: SsPage, onOpen: (SsPage) -> Unit) {
    when (page) {
        SsPage.QuickLog -> QuickLogScreen()
        SsPage.Production -> ProductionScreen(onOpen)
        SsPage.Scenes -> ScenesScreen()
        SsPage.Takes -> TakesScreen()
        else -> SsDepartmentRoutes(page, onOpen)
    }
}

/**
 * The department pages: the cast and the three look boards.
 *
 * Split from [SsRoutes] for the same reason [SsLogRoutes] is — one `when`
 * over every page of the menu is one branch too complex for the gate — and
 * along the menu's own group lines, so the split says something.
 */
@Composable
private fun SsDepartmentRoutes(page: SsPage, onOpen: (SsPage) -> Unit) {
    when (page) {
        SsPage.Characters -> CharactersRoutes(onOpen)
        SsPage.Actors -> ActorsScreen(onOpen)
        SsPage.StoryDays -> StoryDaysScreen()
        // Three menu rows, one screen: the departments are one folder on the
        // service, told apart by `department`.
        SsPage.CostumeLooks -> LooksRoutes("costume", onOpen)
        SsPage.HairLooks -> LooksRoutes("hair", onOpen)
        SsPage.MakeupLooks -> LooksRoutes("makeup", onOpen)
        SsPage.PropsWardrobe -> PropsWardrobeScreen()
        SsPage.Boxes -> BoxesScreen()
        SsPage.ContinuityPhotos -> ContinuityPhotosScreen()
        else -> SsScriptRoutes(page)
    }
}

/** The script group's pages. */
@Composable
private fun SsScriptRoutes(page: SsPage) {
    when (page) {
        SsPage.ScriptImport -> ScriptImportScreen()
        SsPage.CallSheetImport -> CallSheetImportScreen()
        SsPage.ScriptBreakdown -> ScriptBreakdownScreen()
        SsPage.ScriptRevisions -> ScriptRevisionsScreen()
        SsPage.ElementManager -> ElementManagerScreen()
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
