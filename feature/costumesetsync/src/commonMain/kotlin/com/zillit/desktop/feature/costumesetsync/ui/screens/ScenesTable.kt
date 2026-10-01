package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.NEW_KEY
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SceneDraft
import com.zillit.desktop.feature.costumesetsync.domain.SceneLine
import com.zillit.desktop.feature.costumesetsync.domain.castMembersOf
import com.zillit.desktop.feature.costumesetsync.domain.castNumbersOf
import com.zillit.desktop.feature.costumesetsync.domain.changeLabel
import com.zillit.desktop.feature.costumesetsync.domain.changesOf
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.namesOf
import com.zillit.desktop.feature.costumesetsync.domain.readinessOf
import com.zillit.desktop.feature.costumesetsync.domain.resolveCast
import com.zillit.desktop.feature.costumesetsync.domain.scriptLoc
import com.zillit.desktop.feature.costumesetsync.domain.sortByCast
import com.zillit.desktop.feature.costumesetsync.domain.truncate
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

/** The breakdown table's column widths (dp). One set serves the read rows and the editor row, so cells line up. */
internal object Col {
    val actions = 84.dp
    val dot = 28.dp
    val episode = 80.dp
    val scene = 110.dp
    val day = 180.dp
    val location = 270.dp
    val description = 360.dp
    val character = 190.dp
    val castNumber = 120.dp
    val castName = 200.dp
    val change = 170.dp
    val shootDate = 160.dp
    val tail = 48.dp

    /**
     * Width of the Save + Cancel pair an edited row carries in its edge column (the web's table widens to its widest
     * cell).
     */
    val saveCancel = 136.dp
}

/** What the table shows. */
@Suppress("LongParameterList")
internal class TableInput(
    val view: String,
    val episodes: Boolean,
    val scenes: List<Rec>,
    val lines: Map<String, List<SceneLine?>>,
    val charById: Map<String, Rec>,
    val characters: List<Rec>,
    val actors: List<Rec>,
    val intExtOptions: List<String>,
    val problems: Map<String, String>,
    val canPost: Boolean,
) {
    val expanded: Boolean get() = view == "breakdown"
}

/** What the table can ask for. */
internal class TableActions(
    val openScene: (Rec) -> Unit,
    val editLine: (sceneId: String, characterId: String) -> Unit,
    val removeLine: (sceneId: String, characterId: String) -> Unit,
    val principals: (key: String) -> Unit,
    val save: (key: String) -> Unit,
    val cancel: (key: String) -> Unit,
)

/** How much wider than its natural width the table is drawn: 1 until the window is wider than the columns. */
private val LocalColScale = compositionLocalOf { 1f }

@Composable
internal fun TCell(width: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.width(width * LocalColScale.current).padding(horizontal = CELL_PAD_X, vertical = CELL_PAD_Y),
        content = content
    )
}

/** The body text of the web's `.csync-table`: 13px; `.csync-muted` cells stay 12px (MutedText). */
private val CellStyle @Composable get() = ZillitTheme.typography.bodyMedium

@Composable
private fun CellText(text: String, modifier: Modifier = Modifier, maxLines: Int = 1, bold: Boolean = false) {
    ZillitText(
        text,
        modifier,
        style = if (bold) CellStyle.copy(fontWeight = FontWeight.Bold) else CellStyle,
        maxLines = maxLines
    )
}

/**
 * True while an edited row carries its own Save / Cancel (Edit single, or a new row): the edge column then widens for
 * the pair.
 */
internal val LocalRowButtons = compositionLocalOf { false }

private fun actionsWidthOf(
    expanded: Boolean,
    rowButtons: Boolean
): Dp = if (rowButtons && expanded) Col.saveCancel else Col.actions

private fun tailWidthOf(
    expanded: Boolean,
    rowButtons: Boolean
): Dp = if (rowButtons && !expanded) Col.saveCancel else Col.tail

@Composable
internal fun actionsWidth(expanded: Boolean): Dp = actionsWidthOf(expanded, LocalRowButtons.current)

@Composable
internal fun tailWidth(expanded: Boolean): Dp = tailWidthOf(expanded, LocalRowButtons.current)

/** Width of every column the table shows, for stretching it to the window like the web's `width: 100%` table. */
private fun naturalWidth(input: TableInput, rowButtons: Boolean): Dp {
    var total = Col.dot + Col.scene + Col.day + Col.location + Col.description + Col.character +
        Col.castNumber + Col.castName + Col.change + Col.shootDate + tailWidthOf(input.expanded, rowButtons)
    if (input.expanded) total += actionsWidthOf(true, rowButtons)
    if (input.episodes) total += Col.episode
    return total
}

private val CELL_PAD_X = 10.dp
private val CELL_PAD_Y = 8.dp

/** The Scene Breakdown table: header, the add row, then a read row or an editor row per scene. */
@Composable
internal fun ScenesTable(input: TableInput, editor: SceneEditor, actions: TableActions) {
    val scroll = rememberScrollState()
    val rowButtons = !editor.editAll && editor.drafts.isNotEmpty()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val natural = naturalWidth(input, rowButtons)
        // A wide window stretches the columns (header band included) instead of leaving the table short of the card's
        // edge.
        val scale = if (maxWidth > natural) maxWidth / natural else 1f
        CompositionLocalProvider(LocalColScale provides scale, LocalRowButtons provides rowButtons) {
            Column(Modifier.horizontalScroll(scroll)) {
                TableHeader(input)
                ZillitDivider()
                editor.drafts[NEW_KEY]?.let { draft ->
                    SceneEditRow(draftRowOf(NEW_KEY, draft, null, input, editor, actions))
                    ZillitDivider()
                }
                input.scenes.forEach { scene ->
                    val draft = editor.drafts[scene.id]
                    if (draft != null) {
                        SceneEditRow(draftRowOf(scene.id, draft, scene, input, editor, actions))
                    } else {
                        SceneRows(scene, input, editor, actions)
                    }
                    ZillitDivider()
                }
            }
        }
    }
}

@Composable
private fun TableHeader(input: TableInput) {
    Row(Modifier.background(ZillitTheme.colors.surfaceSunken), verticalAlignment = Alignment.CenterVertically) {
        if (input.expanded) HeadCell(actionsWidth(true), t("csync_actions"), hidden = true)
        HeadCell(Col.dot, "", hidden = true)
        if (input.episodes) HeadCell(Col.episode, t("csync_col_ep"))
        HeadCell(Col.scene, t("csync_col_scene_no"))
        HeadCell(Col.day, t("csync_bd_col_script_day"))
        HeadCell(Col.location, t("csync_col_script_loc"))
        HeadCell(Col.description, t("csync_bd_col_description"))
        HeadCell(Col.character, t("csync_bd_col_character"))
        HeadCell(Col.castNumber, t("csync_field_cast_number"))
        HeadCell(Col.castName, t("csync_bd_col_cast_name"))
        HeadCell(Col.change, t("csync_field_change"))
        HeadCell(Col.shootDate, t("csync_bd_col_shoot_date"))
        HeadCell(tailWidth(input.expanded), "", hidden = true)
    }
}

@Composable
private fun HeadCell(width: Dp, label: String, hidden: Boolean = false) {
    TCell(width) {
        if (!hidden) {
            ZillitText(
                label.uppercase(),
                style = ZillitTheme.typography.labelSmall.copy(
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.44.sp
                ),
                color = ZillitTheme.colors.textMuted,
                maxLines = 1,
            )
        }
    }
}

// -- read rows ------------------------------------------------------------------------

@Composable
private fun SceneRows(scene: Rec, input: TableInput, editor: SceneEditor, actions: TableActions) {
    if (input.expanded) {
        input.lines[scene.id].orEmpty().forEach { line ->
            val key = line?.sc?.id ?: "scene:${scene.id}"
            ReadRow(scene, key, editor) {
                if (line == null) EmptyLineCells(
                    scene,
                    input,
                    actions,
                    key,
                    editor
                ) else LineCells(scene, line, input, actions, key, editor)
            }
        }
        return
    }
    ReadRow(scene, scene.id, editor) { CollapsedCells(scene, input, actions, editor) }
}

@Composable
private fun ReadRow(scene: Rec, key: String, editor: SceneEditor, content: @Composable () -> Unit) {
    val picked = editor.single == key
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val colors = ZillitTheme.colors
    val rowModifier = Modifier
        .then(if (scene.str("status") == "OMITTED") Modifier.alpha(OMITTED_ALPHA) else Modifier)
        .hoverable(hover)
        // `tbody tr:hover` is the soft band; the picked row of Edit single is the accent tint with a 2px edge.
        .background(if (picked) colors.accentSoft else if (hovered) colors.surfaceSunken else Color.Transparent)
        .then(if (picked) Modifier.drawBehind { drawRect(
            colors.accent,
            size = Size(2.dp.toPx(), size.height)
        ) } else Modifier)
        .then(if (editor.picking) Modifier.clickable { editor.single = key } else Modifier)
    Row(rowModifier, verticalAlignment = Alignment.Top) { content() }
}

private const val OMITTED_ALPHA = 0.55f

/** The scene's own cells: episode, number (a link to the scene), Day/Night, script location, synopsis. */
@Composable
private fun SceneCells(scene: Rec, input: TableInput, actions: TableActions) {
    if (input.episodes) TCell(Col.episode) { CellText(scene.str("episode")) }
    TCell(Col.scene) {
        // `.csync-scenelink`: bold ink, the number alone is the link.
        CellText(scene.str("number"), Modifier.clickable { actions.openScene(scene) }, bold = true)
        scene.str("status").takeIf { it.isNotEmpty() && it != "PLANNED" }?.let { StatusBadge(it, tEnum(it)) }
    }
    // Day or Night only, as the reference — the story day ("Day 3") is edited in the row and shown on the scene page.
    TCell(Col.day) { CellText(tEnum(scene.str("time_of_day"))) }
    TCell(Col.location) { CellText(scriptLoc(scene)) }
    TCell(Col.description) { CellText(truncate(scene.str("synopsis"))) }
}

@Composable
private fun ShootCell(scene: Rec) {
    TCell(Col.shootDate) { CellText(if (scene.long("shoot_date") != 0L) fmtDate(scene.long("shoot_date")) else "") }
}

@Composable
private fun PickCell(key: String, expanded: Boolean, editor: SceneEditor) {
    TCell(tailWidth(expanded)) { if (editor.picking) RadioDot(editor.single == key) { editor.single = key } }
}

@Composable
private fun EmptyLineCells(scene: Rec, input: TableInput, actions: TableActions, key: String, editor: SceneEditor) {
    TCell(actionsWidth(true)) {}
    TCell(Col.dot) { ReadinessDot("NOT_ASSIGNED") }
    SceneCells(scene, input, actions)
    TCell(Col.character) { MutedText(t("csync_nobody_yet")) }
    TCell(Col.castNumber) { MutedText("—") }
    TCell(Col.castName) { MutedText("—") }
    TCell(Col.change) { MutedText("—") }
    ShootCell(scene)
    PickCell(key, input.expanded, editor)
}

@Composable
private fun LineCells(
    scene: Rec,
    line: SceneLine,
    input: TableInput,
    actions: TableActions,
    key: String,
    editor: SceneEditor
) {
    val sc = line.sc
    val full = input.charById[sc.str("character_id")]
    TCell(actionsWidth(true)) {
        if (input.canPost) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ZillitButton(
                    "✎",
                    onClick = { actions.editLine(scene.id, sc.str("character_id")) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
                ZillitButton(
                    "×",
                    onClick = { actions.removeLine(scene.id, sc.str("character_id")) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                )
            }
        }
    }
    TCell(Col.dot) { ReadinessDot(readinessOf(sc)) }
    SceneCells(scene, input, actions)
    TCell(Col.character) { CellText(line.name) }
    TCell(Col.castNumber) { MutedText(line.castNumber?.toString() ?: "—") }
    TCell(Col.castName) { MutedText(full?.rec("actor")?.str("name")?.ifEmpty { null } ?: "—") }
    TCell(Col.change) {
        val change = sc.rec("change")
        if (change != null) CellText(changeLabel(change)) else MutedText(t("csync_no_change_assigned"))
    }
    ShootCell(scene)
    PickCell(key, input.expanded, editor)
}

@Composable
private fun CollapsedCells(scene: Rec, input: TableInput, actions: TableActions, editor: SceneEditor) {
    val rows = resolveCast(scene.recs("characters"), input.charById)
    TCell(Col.dot) { ReadinessDot(scene.str("readiness")) }
    SceneCells(scene, input, actions)
    TCell(Col.character) { CellText(namesOf(rows).text, maxLines = 2) }
    TCell(Col.castNumber) { MutedText(castNumbersOf(rows).text.ifEmpty { "—" }) }
    TCell(Col.castName) { MutedText(castMembersOf(rows).text.ifEmpty { "—" }, maxLines = 2) }
    TCell(Col.change) { MutedText(changesOf(rows).text.ifEmpty { "—" }, maxLines = 2) }
    ShootCell(scene)
    PickCell(scene.id, input.expanded, editor)
}

/** Builds the editor row's inputs for one draft. */
private fun draftRowOf(
    key: String,
    draft: SceneDraft,
    scene: Rec?,
    input: TableInput,
    editor: SceneEditor,
    actions: TableActions,
): EditRowInput {
    val people = sortByCast(draft.principals.mapNotNull { input.charById[it] })
    val sceneChars = scene?.recs("characters").orEmpty()
    return EditRowInput(
        key = key,
        draft = draft,
        isNew = key == NEW_KEY,
        people = people,
        changeOf = { cid -> changeLabel(sceneChars.firstOrNull { it.str("character_id") == cid }?.rec("change")) },
        changes = changesOf(resolveCast(sceneChars, input.charById)),
        input = input,
        busy = editor.savingAll || editor.savingKey == key,
        error = input.problems[key],
        showButtons = !editor.editAll,
        onChange = { editor.set(key, it) },
        actions = actions,
    )
}
