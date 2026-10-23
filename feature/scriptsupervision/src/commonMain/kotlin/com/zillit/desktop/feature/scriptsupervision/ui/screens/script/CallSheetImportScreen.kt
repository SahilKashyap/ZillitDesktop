package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * Importing a call sheet — the web's `CallSheetImportScreen`.
 *
 * A PDF only, because a call sheet is a PDF. What it carries is one shooting
 * day's running order, so the import does something different from the
 * script import: it does not create a scene list, it MATCHES the sheet's
 * scenes against the ones already there and fills in that day's shooting
 * details. The result says how many it updated and how many it had to create
 * — a high "created" count usually means the sheet's scene numbers do not
 * match the script's, which is worth seeing before trusting the run.
 */
@Composable
internal fun CallSheetImportScreen() {
    val sceneState = scenes()
    FileImportScreen(
        title = t("callsheet_import"),
        subtitle = t("callsheet_import_subtitle"),
        formats = FORMATS,
        extensions = setOf("pdf"),
        previewPath = importPath(preview = true),
        importPath = importPath(preview = false),
        confirmTitle = t("import_callsheet_title"),
        confirmText = t("import_callsheet_text"),
        onImported = { sceneState.reload() },
        renderPreview = { CallSheetPreview(it) },
        renderResult = { CallSheetResult(it) },
    )
}

/** `/callsheet-import/{projectId}` and its dry run. */
@Composable
private fun importPath(preview: Boolean): String {
    val project = LocalSs.current.api.project().orEmpty()
    return "/callsheet-import/$project" + if (preview) "/preview" else ""
}

/** What the dry run read off the sheet: which day it is, and its running order. */
@Composable
private fun ColumnScope.CallSheetPreview(sheet: Rec) {
    val sheetScenes = sheet.recs("scenes")
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = t("preview"),
        meta = "${sheetScenes.size} ${t("scenes_detected")}",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            sheet.int("shooting_day").takeIf { it > 0 }?.let {
                SsTag("${t("day")} $it", StatusTone.Progress)
            }
            sheet.str("shoot_date").takeIf { it.isNotBlank() }?.let { MutedText(it) }
            MutedText("${t("total_scenes")}: ${sheetScenes.size}")
        }
        ZillitDataTable(
            rows = sheetScenes,
            columns = sheetColumns(),
            key = { it.str("scene_number") + it.str("page") },
            emptyTitle = t("no_scenes"),
            virtualised = false,
        )
    }
}

@Composable
private fun sheetColumns(): List<TableColumn<Rec>> = listOf(
    textColumn(t("scene"), ColumnWidth.Fixed(SCENE_COL)) { it.str("scene_number") },
    textColumn(t("set_synopsis")) { it.str("set_synopsis") },
    textColumn(t("day_night"), ColumnWidth.Fixed(DAY_NIGHT_COL)) { it.str("day_night") },
    textColumn(t("page"), ColumnWidth.Fixed(PAGE_COL)) { it.str("page") },
    textColumn(t("cast"), ColumnWidth.Fixed(CAST_COL)) { it.str("cast") },
    textColumn(t("location")) { it.str("location") },
)

/**
 * What the import did.
 *
 * Updated and created are shown apart on purpose: the sheet is meant to
 * MATCH scenes that already exist, so a created count is the signal that its
 * numbering and the script's disagree.
 */
@Composable
private fun ColumnScope.CallSheetResult(result: Rec) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = t("import_result")) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            result.int("shooting_day").takeIf { it > 0 }?.let {
                MutedText("${t("shooting_day")}: $it")
            }
            MutedText("${t("scenes_updated")}: ${result.int("updated")}")
            MutedText("${t("scenes_created")}: ${result.int("created")}")
        }
    }
}

/** A call sheet is a PDF; the web accepts nothing else here. */
private val FORMATS = listOf(".pdf" to "format_pdf")

private val SCENE_COL = 80.dp
private val DAY_NIGHT_COL = 120.dp
private val PAGE_COL = 96.dp
private val CAST_COL = 120.dp
