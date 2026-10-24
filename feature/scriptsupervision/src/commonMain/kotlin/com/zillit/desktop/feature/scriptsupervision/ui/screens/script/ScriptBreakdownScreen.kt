package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.Script
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * The script breakdown — the web's `ScriptBreakdownScreen`.
 *
 * The production's scenes, and one scene's breakdown when a scene is open.
 * The web keeps the open scene in `?scene=<id>` so a link from elsewhere
 * lands on it; here that is the page's inner position, which is the same
 * thing said differently.
 */
@Composable
internal fun ScriptBreakdownScreen() {
    when (val view = nav().current) {
        is SsView.BreakdownScene -> BreakdownSceneScreen(view.sceneId)
        else -> BreakdownSceneList()
    }
}

/**
 * Every scene, with how much of it has been broken down.
 *
 * The element count is the point of the list: it is how a script supervisor
 * sees at a glance which scenes still need work.
 */
@Composable
private fun BreakdownSceneList() {
    val navState = nav()
    val sceneState = scenes()

    SsPageHeader(title = t("script_breakdown"))
    ZillitDataTable(
        rows = sceneState.scenes,
        columns = breakdownColumns { navState.go(SsView.BreakdownScene(it.id)) },
        key = { it.id },
        emptyTitle = t("no_scenes"),
        virtualised = false,
    )
}

@Composable
private fun breakdownColumns(onOpen: (Rec) -> Unit): List<TableColumn<Rec>> = listOf(
    TableColumn(t("scene_no"), ColumnWidth.Fixed(NUMBER_COL)) { scene ->
        SsTag(scene.str("scene_number"), modifier = Modifier.clickable { onOpen(scene) })
    },
    textColumn(t("scene_setting")) { it.first("set_name", "scene_name") },
    textColumn(t("int_ext"), ColumnWidth.Fixed(INT_EXT_COL)) { it.str("int_ext") },
    textColumn(t("day_night"), ColumnWidth.Fixed(DAY_NIGHT_COL)) { it.str("day_night") },
    textColumn(t("shoot_location")) { it.str("location") },
    textColumn(t("pages"), ColumnWidth.Fixed(PAGES_COL), numeric = true) {
        Script.formatEighths(it.int("page_count_eighths"))
    },
    // The service counts the scene's tags on the scene record itself, so the
    // list needs no call of its own.
    textColumn(t("elements"), ColumnWidth.Fixed(ELEMENTS_COL), numeric = true) {
        it.int("element_count").takeIf { count -> count > 0 }?.toString().orEmpty()
    },
)

private val NUMBER_COL = 90.dp
private val INT_EXT_COL = 90.dp
private val DAY_NIGHT_COL = 110.dp
private val PAGES_COL = 90.dp
private val ELEMENTS_COL = 100.dp
