package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSegmented
import com.zillit.desktop.core.designsystem.component.ZillitStatusPill
import com.zillit.desktop.core.designsystem.component.ZillitTab
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.INT_EXT_FALLBACK
import com.zillit.desktop.feature.costumesetsync.domain.ImportRow
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.aliveRows
import com.zillit.desktop.feature.costumesetsync.domain.buildCharacterImport
import com.zillit.desktop.feature.costumesetsync.domain.changedFields
import com.zillit.desktop.feature.costumesetsync.domain.slugOf
import com.zillit.desktop.feature.costumesetsync.ui.FormCell
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.MonoText
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.enumOptions
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

private object ScriptCol {
    val scene = 70.dp
    val day = 130.dp
    val slug = 330.dp
    val pages = 70.dp
    val characters = 230.dp
    val status = 140.dp
    val action = 250.dp
}

@Composable
private fun Cell(width: Dp, content: @Composable () -> Unit) {
    Column(Modifier.width(width).padding(horizontal = 4.dp, vertical = 4.dp)) { content() }
}

/** The scenes the script reader found, with what each would become and what it says now. */
@Composable
internal fun ScriptScenesTable(upload: ScriptUpload, meta: Rec?) {
    val plan = buildCharacterImport(upload.rows, upload.existing).characterMap
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Row {
            listOf(
                ScriptCol.scene to "csync_col_scene", ScriptCol.day to "csync_bd_col_script_day", ScriptCol.slug to "csync_col_slugline",
                ScriptCol.pages to "csync_field_pages", ScriptCol.characters to "csync_col_characters", ScriptCol.status to "csync_col_status",
                ScriptCol.action to "csync_col_action",
            ).forEach { (w, key) ->
                Cell(w) { ZillitText(t(key).uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted, maxLines = 1) }
            }
        }
        ZillitDivider()
        upload.scenes.forEach { s ->
            ScriptSceneRow(upload, s, plan)
            ZillitDivider()
        }
    }
    upload.scenes.filter { upload.actionOf(it) == "edit" }.forEach { s -> SceneCorrection(upload, s, meta) }
}

@Composable
private fun ScriptSceneRow(upload: ScriptUpload, s: Rec, characterMap: Map<String, String?>) {
    val action = upload.actionOf(s)
    val v = upload.importedScene(s)
    val diff = changedFields(s, if (action == "edit") v else null)
    val prev = s.rec("previous")
    val show = { keys: List<String> -> keys.any { it in diff } && action != "keep" }
    Row(Modifier.alpha(if (action == "keep") KEPT_ALPHA else 1f)) {
        Cell(ScriptCol.scene) { MonoText(s.str("number")) }
        Cell(ScriptCol.day) {
            DiffText(action, s, show(listOf("script_day")), v.str("script_day"), prev?.str("script_day"))
            DiffText(action, s, show(listOf("time_of_day")), tEnum(v.str("time_of_day")), tEnum(prev?.str("time_of_day")))
        }
        Cell(ScriptCol.slug) {
            DiffText(action, s, show(listOf("int_ext", "location")), slugOf(v).ifEmpty { v.str("name") }, if (prev != null) slugOf(prev) else "", bold = true)
            if (s.str("status") == "OMITTED") MutedText(t("csync_omitted"))
            DiffText(action, s, show(listOf("synopsis")), v.str("synopsis"), prev?.str("synopsis"), maxLines = 2)
        }
        Cell(ScriptCol.pages) { DiffText(action, s, show(listOf("pages")), v.str("pages"), prev?.str("pages")) }
        Cell(ScriptCol.characters) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                s.strings("characters").forEach { c ->
                    // The name the import will file them under, after any merge.
                    val dropped = characterMap.containsKey(c) && characterMap[c] == null
                    ZillitText(characterMap[c] ?: c, Modifier.alpha(if (dropped) DROPPED_ALPHA else 1f), style = ZillitTheme.typography.bodySmall)
                }
            }
        }
        Cell(ScriptCol.status) { StatusCell(s, diff.isEmpty()) }
        Cell(ScriptCol.action) {
            ZillitSegmented(
                listOf(
                    ZillitTab("replace", t(if (s.str("change") == "new") "csync_scene_action_add" else "csync_scene_action_replace")),
                    ZillitTab("keep", t("csync_scene_action_keep")),
                    ZillitTab("edit", t("csync_scene_action_edit")),
                ),
                action, { upload.setAction(s, it) },
            )
        }
    }
}

private const val KEPT_ALPHA = 0.5f
private const val DROPPED_ALPHA = 0.4f

@Composable
private fun StatusCell(s: Rec, noFieldChange: Boolean) {
    if (s.str("status") == "OMITTED") {
        ZillitStatusPill(t("csync_omitted"), tone = StatusTone.Neutral)
    } else {
        val tone = when (s.str("change")) {
            "new" -> StatusTone.Ready
            "updated" -> StatusTone.Pending
            else -> StatusTone.Neutral
        }
        ZillitStatusPill(t("csync_change_${s.str("change")}"), tone = tone)
    }
    s.str("previous_revision").takeIf { it.isNotEmpty() }?.let { MutedText(t("csync_was_revision", "rev" to it)) }
    // The headings can match while the dialogue underneath has moved; say so rather than show nothing.
    if (s.str("change") == "updated" && noFieldChange) MutedText(t("csync_change_text_only"), maxLines = 2)
}

/**
 * What this draft makes of a field, with what the scene says now beneath it. A kept
 * scene is not changing, so it shows what it says now.
 */
@Composable
private fun DiffText(action: String, s: Rec, changed: Boolean, now: String, before: String?, bold: Boolean = false, maxLines: Int = 1) {
    val shown = if (action == "keep" && s.rec("previous") != null) before.orEmpty() else now
    ZillitText(
        shown.ifEmpty { "—" },
        style = if (bold) ZillitTheme.typography.titleSmall else ZillitTheme.typography.bodySmall,
        color = if (changed) ZillitTheme.colors.success else ZillitTheme.colors.textPrimary,
        maxLines = maxLines,
    )
    if (changed) {
        ZillitText(
            "${t("csync_was")} ${before.orEmpty().ifEmpty { "—" }}",
            style = ZillitTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough),
            color = ZillitTheme.colors.textMuted,
            maxLines = 1,
        )
    }
}

/** The hand-correction fields a scene set to Edit opens, starting from what the script says. */
@Composable
private fun SceneCorrection(upload: ScriptUpload, s: Rec, meta: Rec?) {
    val number = s.str("number")
    val edit = upload.edits[number] ?: return
    ZillitText(t("csync_correct_scene", "n" to number), style = ZillitTheme.typography.titleSmall)
    FormGrid {
        PickInput(
            edit["int_ext"].orEmpty(), listOf("" to "—") + (meta?.strings("int_ext")?.ifEmpty { null } ?: INT_EXT_FALLBACK).map { it to it },
            { upload.setEdit(number, "int_ext", it) }, t("csync_field_int_ext"), FormCell,
        )
        TextInput(edit["location"].orEmpty(), { upload.setEdit(number, "location", it) }, t("csync_field_location"), FormCell)
        PickInput(
            edit["time_of_day"].orEmpty(), listOf("" to "—") + enumOptions(meta?.strings("times_of_day").orEmpty()),
            { upload.setEdit(number, "time_of_day", it) }, t("csync_field_time_of_day"), FormCell,
        )
        TextInput(edit["script_day"].orEmpty(), { upload.setEdit(number, "script_day", it) }, t("csync_field_script_day"), FormCell)
        TextInput(edit["pages"].orEmpty(), { upload.setEdit(number, "pages", it) }, t("csync_field_pages"), FormCell, placeholder = t("csync_field_pages_hint"))
        TextInput(edit["synopsis"].orEmpty(), { upload.setEdit(number, "synopsis", it) }, t("csync_field_synopsis"), FormWide, multiline = true)
    }
    ZillitDivider()
}

// -- characters -----------------------------------------------------------------------

/**
 * The character half of a script import: every speaking role the script named, with
 * its cast number, scene and line counts, Delete per row and Delete All — plus a row
 * you can type in for anyone the script never speaks (extras, doubles, background).
 *
 * Deleting only flags the row, so Delete All is undone one Restore at a time. There
 * is no merge control: a row whose name already exists in the production resolves to
 * that character on import (`buildCharacterImport`), so a revised draft merges.
 */
@Composable
internal fun CharacterConfirm(upload: ScriptUpload) {
    val rows = upload.rows
    val stats = upload.detected.associateBy { it.str("name") }
    val existingByName = upload.existing.associateBy { it.str("name").lowercase() }
    val alive = aliveRows(rows)
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        ZillitButton(
            "+ ${t("csync_char_add")}", onClick = { upload.rows = rows + ImportRow("", manual = true) },
            size = ButtonSize.Small, variant = ButtonVariant.Secondary,
        )
        MutedText(plural("csync_char_will_be_created", alive.size, "n" to alive.size))
        ZillitButton(
            t("csync_char_delete_all"), onClick = { upload.rows = rows.map { it.copy(deleted = true) } },
            size = ButtonSize.Small, variant = ButtonVariant.Danger, enabled = alive.isNotEmpty(),
        )
    }
    Row {
        Cell(CHAR_NUM) { HeadText(t("csync_char_number")) }
        Cell(CHAR_NAME) { HeadText(t("csync_field_name")) }
        Cell(CHAR_COUNT) { HeadText(t("csync_char_scenes_col")) }
        Cell(CHAR_COUNT) { HeadText(t("csync_char_lines_col")) }
    }
    ZillitDivider()
    if (rows.isEmpty()) MutedText(t("csync_char_none_found"))
    rows.forEachIndexed { i, r -> CharacterRow(upload, i, r, stats[r.name], existingByName[r.name.trim().lowercase()]) }
}

private val CHAR_NUM = 110.dp
private val CHAR_NAME = 300.dp
private val CHAR_COUNT = 90.dp

@Composable
private fun HeadText(text: String) = ZillitText(text.uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted, maxLines = 1)

@Composable
private fun CharacterRow(upload: ScriptUpload, i: Int, r: ImportRow, stat: Rec?, existing: Rec?) {
    Row(Modifier.alpha(if (r.deleted) DELETED_ALPHA else 1f), verticalAlignment = Alignment.CenterVertically) {
        Cell(CHAR_NUM) {
            if (r.deleted) MutedText("—") else CellField(r.castNumber, { upload.setRow(i, r.copy(castNumber = it)) }, CHAR_NUM - 8.dp)
        }
        Cell(CHAR_NAME) {
            if (r.manual && !r.deleted) {
                CellField(r.name, { upload.setRow(i, r.copy(name = it)) }, CHAR_NAME - 8.dp)
            } else {
                ZillitText(
                    r.name.uppercase().ifEmpty { "—" },
                    style = ZillitTheme.typography.titleSmall.copy(textDecoration = if (r.deleted) TextDecoration.LineThrough else null),
                    maxLines = 1,
                )
            }
            if (existing != null && !r.deleted) {
                MutedText(t("csync_char_exists") + if (existing.has("cast_number")) " #${existing.str("cast_number")}" else "")
            }
        }
        Cell(CHAR_COUNT) { MutedText(stat?.str("scenes").orEmpty()) }
        Cell(CHAR_COUNT) { MutedText(stat?.str("lines").orEmpty()) }
        if (r.deleted) {
            ZillitButton(t("csync_char_restore"), onClick = { upload.setRow(i, r.copy(deleted = false)) }, size = ButtonSize.Small, variant = ButtonVariant.Secondary)
        } else {
            ZillitButton(t("csync_delete"), onClick = { upload.setRow(i, r.copy(deleted = true)) }, size = ButtonSize.Small, variant = ButtonVariant.Danger)
        }
    }
}

private const val DELETED_ALPHA = 0.45f
