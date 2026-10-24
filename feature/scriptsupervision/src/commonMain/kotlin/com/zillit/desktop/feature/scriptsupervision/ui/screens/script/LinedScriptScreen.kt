package com.zillit.desktop.feature.scriptsupervision.ui.screens.script

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.feature.scriptsupervision.data.Answer
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Coverage
import com.zillit.desktop.feature.scriptsupervision.domain.LinedScript
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.asRows
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.Resource
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The lined script — the web's `LinedScriptScreen`.
 *
 * A scene's text with a tram line per camera setup beside it: see
 * [TramLinesPage], which is the page itself and explains why it is a grid
 * rather than a Canvas overlay. This file is the page's frame — picking the
 * scene, checking coverage for gaps, and adding or editing a line.
 *
 * ## The gap check is NOT AI, despite its path
 *
 * `GET /ai/coverage-gaps/{projectId}/{sceneId}` sits under `/ai/` but the web
 * is explicit in its own comment that the service works the gaps out from the
 * coverage lines arithmetically. So it is ported here and is not gated on an
 * AI key, while the web's "Plan coverage with AI" button beside it genuinely
 * is AI and belongs with the unported AI drawer. That button is left out, and
 * so is the one the web puts inside its empty state.
 *
 * ⚠ The empty state's own words still offer AI ("…or let AI plan coverage for
 * this scene"), because they are the web's and the web shows them whether or
 * not the service has an AI key — so a reader there meets the same mismatch.
 * Kept for parity rather than quietly reworded; it comes true when the AI
 * drawer is ported, and until then it is the one sentence on this page that
 * promises something the desktop cannot do.
 *
 * ## Rights
 *
 * Adding, editing and deleting a line are writes, so they sit behind the
 * viewer's posting right. Reading the page and checking its gaps do not.
 */
@Composable
internal fun LinedScriptScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var sceneId by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Rec?>(null) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val gapCheck = remember { GapCheck() }

    // A new scene is a new page: last scene's gaps and selection mean nothing
    // on it, and leaving them would caption the wrong scene.
    LaunchedEffect(sceneId) {
        gapCheck.clear()
        selected = null
    }

    val rows = rememberCoverage(sceneId)

    SsPageHeader(title = t("lined_script"))
    SceneToolbar(
        sceneId = sceneId,
        onScene = { sceneId = it },
        checking = gapCheck.checking,
        onCheck = {
            gapCheck.checking = true
            ctx.scope.launch {
                ctx.coverageGaps(sceneId)?.let(gapCheck::found)
                gapCheck.checking = false
            }
        },
        onAdd = { ctx.whenPost { editing = Rec.Empty } },
    )

    if (sceneId.isBlank()) {
        ZillitEmptyState(title = t("lined_script"), message = t("pick_scene_lined"))
        return
    }

    GapBanner(check = gapCheck, onDismiss = gapCheck::clear)

    Await(rows) { lines ->
        LinedBody(
            scene = sceneState.scene(sceneId),
            lines = lines,
            gaps = gapCheck.gaps,
            selected = selected,
            onSelect = { selected = it },
            onEdit = { ctx.whenPost { editing = it } },
            onDelete = { ctx.whenPost { deleting = it } },
        )
    }

    LineDialogs(
        editing = editing,
        deleting = deleting,
        sceneId = sceneId,
        onClose = {
            editing = null
            deleting = null
        },
        onChanged = {
            selected = null
            rows.reload(silent = true)
        },
    )
}

/** The add/edit form and the delete confirmation, neither open by default. */
@Composable
private fun LineDialogs(
    editing: Rec?,
    deleting: Rec?,
    sceneId: String,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalSs.current
    editing?.let { line ->
        CoverageLineDialog(line = line, sceneId = sceneId, onClose = onClose, onSaved = onChanged)
    }
    deleting?.let { line ->
        DeleteDialog(
            title = t("delete_line_title"),
            name = "${t("shot")} ${line.str("shot_number")}",
            onClose = onClose,
            onDone = onChanged,
            run = { ContinuityCrud(ctx.api, "lined-script").remove(line.id) },
        )
    }
}

/**
 * The lined page, the line a reader has clicked, and the coverage count.
 *
 * Separate from the screen because the screen is state and this is layout —
 * and because a scene with no coverage yet is a different page, not a page
 * with nothing on it.
 */
@Composable
private fun LinedBody(
    scene: Rec?,
    lines: List<Rec>,
    gaps: List<Rec>,
    selected: Rec?,
    onSelect: (Rec) -> Unit,
    onEdit: (Rec) -> Unit,
    onDelete: (Rec) -> Unit,
) {
    if (lines.isEmpty()) {
        ZillitEmptyState(title = t("no_lines"), message = t("no_lines_text"))
        return
    }
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        TramLinesPage(
            scene = scene,
            lines = lines,
            gaps = gaps,
            selectedId = selected?.id,
            onSelect = onSelect,
        )
    }
    SelectedLine(
        line = selected,
        colour = selected?.let { LinedScript.shotColours(lines)[it.str("shot_number")] },
        onEdit = onEdit,
        onDelete = onDelete,
        onClose = { selected?.let { onSelect(it) } },
    )
    CoverageCard(LinedScript.coverage(lines, gaps))
}

/**
 * One scene's coverage lines.
 *
 * ⚠ With no scene picked it answers EMPTY rather than calling: the service
 * reads `/continuity/lined-script/{project}` with no `scene_id` as every line
 * in the production, which is a page nobody asked for and a big read.
 */
@Composable
private fun rememberCoverage(sceneId: String): Resource<List<Rec>> = rememberRows(sceneId) {
    if (sceneId.isBlank()) ZillitResult.Success(Answer(null, null))
    else ContinuityCrud(api, "lined-script").list(mapOf("scene_id" to sceneId))
}

/**
 * The gap check's three pieces of state, which only ever change together.
 *
 * ⚠ [answered] is the point of this existing. An empty [gaps] list means two
 * entirely different things — "nobody has looked" and "there are none" — and
 * the second is a far more reassuring claim than the first. Only [checked]
 * tells them apart, so the two are kept in one place where a reader cannot
 * use one without the other.
 */
private class GapCheck {
    var gaps by mutableStateOf<List<Rec>>(emptyList())
        private set
    var checked by mutableStateOf(false)
        private set
    var checking by mutableStateOf(false)

    /** A check has run and this is what it found, even if that is nothing. */
    fun found(result: List<Rec>) {
        gaps = result
        checked = true
    }

    fun clear() {
        gaps = emptyList()
        checked = false
    }

    /** Whether a check has run — see the note above. */
    val answered: Boolean get() = checked || gaps.isNotEmpty()
}

/**
 * The scene's coverage gaps, or null if the service refused.
 *
 * ⚠ `/ai/coverage-gaps` is NOT AI despite the path — the web says so in its
 * own comment, and the service works the gaps out from the coverage lines. So
 * it is not gated on an AI key.
 */
private suspend fun SsCtx.coverageGaps(sceneId: String): List<Rec>? =
    when (val answer = api.get("/ai/coverage-gaps/${api.project().orEmpty()}/$sceneId")) {
        is ZillitResult.Failure -> {
            toast(answer.error.localised(), false)
            null
        }
        is ZillitResult.Success -> answer.data.data.asRows()
    }

/**
 * Picking the scene, checking its coverage, and adding a line.
 *
 * The gap check and the add button appear only once a scene is picked,
 * because neither means anything without one.
 */
@Composable
private fun SceneToolbar(
    sceneId: String,
    onScene: (String) -> Unit,
    checking: Boolean,
    onCheck: () -> Unit,
    onAdd: () -> Unit,
) {
    val sceneState = scenes()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSearchSelect(
            value = sceneState.options.firstOrNull { it.first == sceneId },
            options = sceneState.options,
            onSelect = { onScene(it.first) },
            label = { it.second },
            placeholder = t("pick_scene"),
            onClear = { onScene("") }.takeIf { sceneId.isNotBlank() },
            modifier = Modifier.width(SCENE_WIDTH),
        )
        if (sceneId.isBlank()) return@Row
        ZillitButton(
            text = t("check_gaps"),
            onClick = onCheck,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Warning,
            loading = checking,
        )
        ZillitButton(text = t("add_line"), onClick = onAdd, leadingIcon = ZillitIcons.Add)
    }
}

/**
 * What the gap check found, or that it found nothing.
 *
 * Only after a check has RUN: an empty gap list before one would otherwise
 * read as "no gaps", which is a different and much more reassuring claim
 * than "not looked at yet".
 */
@Composable
private fun GapBanner(check: GapCheck, onDismiss: () -> Unit) {
    if (!check.answered) return
    val gaps = check.gaps
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                if (gaps.isEmpty()) {
                    SsTag(t("full_coverage"), StatusTone.Ready)
                } else {
                    SsTag("${gaps.size} ${t("gaps_detected")}", StatusTone.Rejected)
                    // The web shows the first five and no more: a scene with
                    // thirty gaps needs the page, not a wall of text.
                    gaps.take(GAP_PREVIEW).forEach { gap ->
                        MutedText(
                            "${t("lines")} ${gap.int("from_line")}-${gap.int("to_line")}: " +
                                t("gap_${gap.str("gap_type")}"),
                        )
                    }
                }
            }
            ZillitButton(
                text = t("close"),
                onClick = onDismiss,
                variant = ButtonVariant.Tertiary,
                leadingIcon = ZillitIcons.Close,
            )
        }
    }
}

/**
 * The line a reader has clicked, in full.
 *
 * Its own card rather than a tooltip, because this is where editing and
 * deleting live and a tooltip cannot hold a button.
 */
@Composable
private fun SelectedLine(
    line: Rec?,
    colour: String?,
    onEdit: (Rec) -> Unit,
    onDelete: (Rec) -> Unit,
    onClose: () -> Unit,
) {
    if (line == null) {
        MutedText(t("click_line_for_details"))
        return
    }
    ZillitSectionLabel("${t("shot")} ${line.str("shot_number")}")
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
                Field(t("shot_size"), line.str("shot_size").ifBlank { "—" })
                Field(t("character"), line.str("character_focus").ifBlank { "—" })
                Field(t("coverage"), "${t("lines")} ${line.int("line_start")}–${line.int("line_end")}")
                Field(t("total_takes"), line.int("total_takes").toString())
                Field(
                    t("circled_takes"),
                    line.strings("circled_takes").joinToString(", ").ifBlank { t("none") },
                )
                Field(
                    t("preferred_take"),
                    line.int("preferred_take").takeIf { it > 0 }?.toString() ?: "—",
                )
                line.str("notes").takeIf { it.isNotBlank() }?.let { Field(t("notes"), it) }
            }
            Column(horizontalAlignment = Alignment.End) {
                SsTag(
                    if (line.str("line_type") == "straight") t("on_screen") else t("off_screen"),
                    if (line.str("line_type") == "straight") StatusTone.Ready else StatusTone.Pending,
                )
                RowActions(onEdit = { onEdit(line) }, onDelete = { onDelete(line) })
                ZillitButton(
                    text = t("close"),
                    onClick = onClose,
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.Close,
                )
            }
        }
    }
    // The colour is the line's identity on the page, so the panel carries it
    // too — otherwise a reader has to remember which line they clicked.
    colour?.let { MutedText(it) }
}

/** The scene's coverage, counted — the web's own four figures plus gaps. */
@Composable
private fun CoverageCard(coverage: Coverage) {
    ZillitSectionLabel(t("coverage_summary"))
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
            Field(t("setups"), coverage.setups.toString())
            Field(t("lines"), coverage.lines.toString())
            Field(t("on_screen"), coverage.onScreen.toString())
            Field(t("off_screen"), coverage.offScreen.toString())
            if (coverage.gaps > 0) Field(t("gaps"), coverage.gaps.toString())
        }
    }
}

private val SCENE_WIDTH = 300.dp
private const val GAP_PREVIEW = 5
