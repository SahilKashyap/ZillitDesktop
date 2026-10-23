package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
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
import com.zillit.desktop.feature.scriptsupervision.domain.Script
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t

/**
 * Importing a script — the web's `ScriptImportScreen`.
 *
 * Four formats, best first: a Final Draft `.fdx` carries the structure the
 * parser wants, a `.pdf` has to be read off the page, and `.fountain` and
 * `.txt` are plain text. The preview is a dry run of the same multipart
 * call, so what the table shows is exactly what the import would write.
 *
 * A successful import changes the production's scene list, so the shell's own
 * scene list is reloaded — every other screen in this tool reads it.
 *
 * What the web has here and this does not: the AI scene analysis
 * (`/ai/analyze-script` and its `/apply`), which is offered only when the
 * service has a key. The AI drawer is unported across the whole module, so
 * leaving this one button out keeps that one decision in one place.
 */
@Composable
internal fun ScriptImportScreen() {
    val sceneState = scenes()
    FileImportScreen(
        title = t("script_import"),
        subtitle = t("script_import_subtitle"),
        formats = FORMATS,
        extensions = setOf("fdx", "pdf", "fountain", "txt"),
        previewPath = importPath(preview = true),
        importPath = importPath(preview = false),
        confirmTitle = t("import_scenes_title"),
        confirmText = t("import_scenes_text"),
        // The scenes are the one thing every other screen here reads.
        onImported = { sceneState.reload() },
        renderPreview = { ScriptPreview(it) },
        renderResult = { ScriptResult(it) },
    )
}

/** `/script-import/{projectId}` and its dry run. */
@Composable
private fun importPath(preview: Boolean): String {
    val project = LocalSs.current.api.project().orEmpty()
    return "/script-import/$project" + if (preview) "/preview" else ""
}

/** What the dry run parsed: the totals, then a row per scene. */
@Composable
private fun ColumnScope.ScriptPreview(parsed: Rec) {
    val parsedScenes = parsed.recs("scenes")
    val characters = parsedScenes.flatMap { it.strings("characters") }.toSet()
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        title = t("preview"),
        meta = "${parsedScenes.size} ${t("scenes_detected")}",
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MutedText("${t("total_scenes")}: ${parsedScenes.size}")
            // The LAST scene's start page is how far the script runs, which
            // is what the web shows as its page estimate.
            val lastPage = parsedScenes.lastOrNull()?.str("script_page_start").orEmpty()
            MutedText("${t("est_pages")}: ${lastPage.ifBlank { "-" }}")
            MutedText("${t("unique_characters")}: ${characters.size}")
        }
        ZillitDataTable(
            rows = parsedScenes,
            columns = previewColumns(),
            key = { it.str("scene_number") + it.str("scene_name") },
            emptyTitle = t("no_scenes"),
            virtualised = false,
        )
    }
}

@Composable
private fun previewColumns(): List<TableColumn<Rec>> = listOf(
    textColumn("#", ColumnWidth.Fixed(NUMBER_COL)) { it.str("scene_number") },
    textColumn(t("scene_name_slug")) { it.str("scene_name") },
    TableColumn(t("int_ext"), ColumnWidth.Fixed(INT_EXT_COL)) { scene ->
        scene.str("int_ext").takeIf { it.isNotBlank() }?.let {
            SsTag(it, if (it == "INT") StatusTone.Progress else StatusTone.Done)
        }
    },
    textColumn(t("day_night"), ColumnWidth.Fixed(DAY_NIGHT_COL)) { it.str("day_night") },
    textColumn(t("set_location")) { it.str("set_name") },
    textColumn(t("page_start"), ColumnWidth.Fixed(PAGE_COL)) { it.str("script_page_start") },
    textColumn(t("pages_eighths"), ColumnWidth.Fixed(EIGHTHS_COL), numeric = true) {
        Script.formatEighths(it.int("page_count_eighths"))
    },
    TableColumn(t("characters"), ColumnWidth.Fixed(CHARACTERS_COL)) { scene ->
        val names = scene.strings("characters")
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            // Three, as the web shows: the column is a hint, not the cast.
            names.take(NAMES_SHOWN).forEach { SsTag(it) }
            if (names.size > NAMES_SHOWN) SsTag("+${names.size - NAMES_SHOWN}")
        }
    },
)

/** What the import actually wrote. */
@Composable
private fun ColumnScope.ScriptResult(result: Rec) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = t("import_result")) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MutedText("${t("scenes")}: ${result.int("total_scenes")}")
            MutedText("${t("script_pages")}: ${result.int("total_script_pages")}")
            MutedText("${t("characters")}: ${result.int("total_characters")}")
        }
    }
}

/** The four formats the parser reads, best first — the web's own order and words. */
private val FORMATS = listOf(
    ".fdx" to "format_fdx",
    ".pdf" to "format_pdf",
    ".fountain" to "format_fountain",
    ".txt" to "format_txt",
)

private const val NAMES_SHOWN = 3

private val NUMBER_COL = 60.dp
private val INT_EXT_COL = 96.dp
private val DAY_NIGHT_COL = 120.dp
private val PAGE_COL = 96.dp
private val EIGHTHS_COL = 104.dp
private val CHARACTERS_COL = 220.dp
