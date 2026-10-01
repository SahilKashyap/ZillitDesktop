package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
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
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.enumOptions
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

/** The review's columns: the slugline is the one that takes what is left of the dialog. */
private val SCRIPT_COLS: List<Dp?> = listOf(56.dp, 104.dp, null, 76.dp, 180.dp, 112.dp, 232.dp)

/** The scenes the script reader found, with what each would become and what it says now. */
@Composable
internal fun ScriptScenesTable(upload: ScriptUpload, meta: Rec?) {
    val plan = buildCharacterImport(upload.rows, upload.existing).characterMap
    val heads = listOf(
        "csync_col_scene",
        "csync_bd_col_script_day",
        "csync_col_slugline",
        "csync_field_pages",
        "csync_col_characters",
        "csync_col_status",
        "csync_col_action",
    )
    ReviewTable(SCRIPT_COLS, heads.map { key -> { ReviewHead(t(key)) } }) {
        upload.scenes.forEachIndexed { i, s ->
            val last = i == upload.scenes.lastIndex && upload.actionOf(s) != "edit"
            ScriptSceneRow(upload, s, plan, last)
            if (upload.actionOf(s) == "edit") wide { SceneCorrection(upload, s, meta) }
        }
    }
}

/** What the review shows of one scene: the action chosen, the scene as read, what it said before and what differs. */
private class SceneDiff(val action: String, val s: Rec, val v: Rec, val prev: Rec?, val diff: List<String>) {
    fun show(keys: List<String>): Boolean = keys.any { it in diff } && action != "keep"
}

@Composable
private fun ReviewBodyScope.ScriptSceneRow(
    upload: ScriptUpload,
    s: Rec,
    characterMap: Map<String, String?>,
    last: Boolean,
) {
    val action = upload.actionOf(s)
    val v = upload.importedScene(s)
    val diff = changedFields(s, if (action == "edit") v else null)
    val d = SceneDiff(action, s, v, s.rec("previous"), diff)
    // `.csync-row-kept td { opacity: .55 }`: a scene set to Keep is not changing, so it recedes.
    row(last, Modifier.alpha(if (action == "keep") KEPT_ALPHA else 1f)) {
        cell(0) { ZillitText(s.str("number"), style = ASSET, color = ZillitTheme.colors.textMuted, maxLines = 1) }
        cell(1) { DayCell(d) }
        cell(2) { SluglineCell(d) }
        cell(3) {
            DiffText(
                action,
                s,
                d.show(listOf("pages")),
                v.str("pages"),
                d.prev?.str("pages"),
                ASSET,
                ZillitTheme.colors.textMuted,
            )
        }
        cell(4) { CharactersCell(s, characterMap) }
        cell(5) { StatusCell(s, diff.isEmpty()) }
        cell(6) { ActionCell(s, action) { upload.setAction(s, it) } }
    }
}

@Composable
private fun DayCell(d: SceneDiff) {
    val muted = ZillitTheme.typography.bodySmall
    val prev = d.prev
    DiffText(d.action, d.s, d.show(listOf("script_day")), d.v.str("script_day"), prev?.str("script_day"), ReviewText)
    DiffText(
        d.action,
        d.s,
        d.show(listOf("time_of_day")),
        tEnum(d.v.str("time_of_day")),
        tEnum(prev?.str("time_of_day")),
        muted,
        ZillitTheme.colors.textMuted,
    )
}

@Composable
private fun SluglineCell(d: SceneDiff) {
    val muted = ZillitTheme.typography.bodySmall
    val colors = ZillitTheme.colors
    val prev = d.prev
    DiffText(
        d.action,
        d.s,
        d.show(listOf("int_ext", "location")),
        slugOf(d.v).ifEmpty { d.v.str("name") },
        if (prev != null) slugOf(prev) else "",
        ReviewText.copy(fontWeight = FontWeight.SemiBold),
    )
    if (d.s.str("status") == "OMITTED") {
        ZillitText("${t("csync_omitted")} ·", style = muted, color = colors.textMuted)
    }
    DiffText(
        d.action,
        d.s,
        d.show(listOf("synopsis")),
        d.v.str("synopsis"),
        prev?.str("synopsis"),
        muted,
        colors.textMuted,
        maxLines = 2,
    )
}

@Composable
private fun CharactersCell(s: Rec, characterMap: Map<String, String?>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        s.strings("characters").forEach { c ->
            // The name the import will file them under, after any merge.
            val dropped = characterMap.containsKey(c) && characterMap[c] == null
            ChipPill(characterMap[c] ?: c, Modifier.alpha(if (dropped) DROPPED_ALPHA else 1f))
        }
    }
}

@Composable
private fun ActionCell(s: Rec, action: String, onAction: (String) -> Unit) {
    ZillitSegmented(
        listOf(
            ZillitTab(
                "replace",
                t(if (s.str("change") == "new") "csync_scene_action_add" else "csync_scene_action_replace"),
            ),
            ZillitTab("keep", t("csync_scene_action_keep")),
            ZillitTab("edit", t("csync_scene_action_edit")),
        ),
        action,
        onAction,
    )
}

/** `.csync-asset`: 12px monospace, muted. */
private val ASSET: TextStyle @Composable get() = ZillitTheme.typography.bodySmall.copy(
    fontFamily = FontFamily.Monospace,
)

private const val KEPT_ALPHA = 0.55f
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
    s.str("previous_revision")
        .takeIf { it.isNotEmpty() }
        ?.let {
            ZillitText(
                t("csync_was_revision", "rev" to it),
                style = ZillitTheme.typography.labelSmall,
                color = ZillitTheme.colors.textMuted,
            )
        }
    // The headings can match while the dialogue underneath has moved; say so rather than show nothing.
    if (s.str("change") == "updated" && noFieldChange) MutedText(t("csync_change_text_only"), maxLines = 2)
}

/**
 * What this draft makes of a field, with what the scene says now beneath it (struck through, 11px). A kept
 * scene is not changing, so it shows what it says now. A changed field is the accent-dark, 600 weight `diff-new`.
 */
@Composable
private fun DiffText(
    action: String, s: Rec, changed: Boolean, now: String, before: String?, base: TextStyle,
    color: androidx.compose.ui.graphics.Color = ZillitTheme.colors.textPrimary, maxLines: Int = 1,
) {
    val shown = if (action == "keep" && s.rec("previous") != null) before.orEmpty() else now
    ZillitText(
        shown.ifEmpty { "—" },
        style = if (changed) base.copy(fontWeight = FontWeight.SemiBold) else base,
        color = if (changed) ZillitTheme.colors.accentText else color,
        maxLines = maxLines,
    )
    if (changed) {
        ZillitText(
            "${t("csync_was")} ${before.orEmpty().ifEmpty { "—" }}",
            style = ZillitTheme.typography.labelSmall.copy(
                fontWeight = FontWeight.Normal,
                textDecoration = TextDecoration.LineThrough,
            ),
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
    ZillitText(t("csync_correct_scene", "n" to number), style = ReviewText.copy(fontWeight = FontWeight.SemiBold))
    // `.csync-scene-edit`: an auto-fill grid of 150px+ columns with 8px gaps; the synopsis spans the row.
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val cell = Modifier.width(FIELD_W)
        PickInput(
            edit["int_ext"].orEmpty(),
            listOf("" to "—") + (meta?.strings("int_ext")?.ifEmpty { null } ?: INT_EXT_FALLBACK)
                .map { it to it },
            { upload.setEdit(number, "int_ext", it) }, t("csync_field_int_ext"), cell,
        )
        TextInput(
            edit["location"].orEmpty(),
            { upload.setEdit(number, "location", it) },
            t("csync_field_location"),
            cell,
        )
        PickInput(
            edit["time_of_day"].orEmpty(), listOf("" to "—") + enumOptions(meta?.strings("times_of_day").orEmpty()),
            { upload.setEdit(number, "time_of_day", it) }, t("csync_field_time_of_day"), cell,
        )
        TextInput(
            edit["script_day"].orEmpty(),
            { upload.setEdit(number, "script_day", it) },
            t("csync_field_script_day"),
            cell,
        )
        TextInput(
            edit["pages"].orEmpty(),
            { upload.setEdit(number, "pages", it) },
            t("csync_field_pages"),
            cell,
            placeholder = t("csync_field_pages_hint"),
        )
        TextInput(
            edit["synopsis"].orEmpty(),
            { upload.setEdit(number, "synopsis", it) },
            t("csync_field_synopsis"),
            Modifier.fillMaxWidth(),
            multiline = true,
        )
    }
}

private val FIELD_W = 168.dp

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
    // `.csync-charconfirm__head`: the add button at one end, the count at the other.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            "+ ${t("csync_char_add")}", onClick = { upload.rows = rows + ImportRow("", manual = true) },
            size = ButtonSize.Small, variant = ButtonVariant.Secondary,
        )
        ZillitText(
            plural("csync_char_will_be_created", alive.size, "n" to alive.size),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textMuted,
        )
    }
    val heads: List<@Composable () -> Unit> = listOf(
        { ReviewHead(t("csync_char_number"), PlainTableStyle) },
        { ReviewHead(t("csync_field_name"), PlainTableStyle) },
        { ReviewHead(t("csync_char_scenes_col"), PlainTableStyle) },
        { ReviewHead(t("csync_char_lines_col"), PlainTableStyle) },
        {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                OutlineDangerButton(
                    t("csync_char_delete_all"),
                    { upload.rows = rows.map { it.copy(deleted = true) } },
                    alive.isNotEmpty(),
                )
            }
        },
    )
    ReviewTable(CHAR_COLS, heads, style = PlainTableStyle) {
        if (rows.isEmpty()) row(last = true) { cell(1) { MutedText(t("csync_char_none_found")) } }
        rows.forEachIndexed { i, r ->
            CharacterRow(upload, i, r, stats[r.name], existingByName[r.name.trim().lowercase()], i == rows.lastIndex)
        }
    }
}

private val CHAR_COLS: List<Dp?> = listOf(120.dp, null, 90.dp, 90.dp, 140.dp)

@Composable
private fun ReviewBodyScope.CharacterRow(
    upload: ScriptUpload,
    i: Int,
    r: ImportRow,
    stat: Rec?,
    existing: Rec?,
    last: Boolean,
) {
    row(last, Modifier.alpha(if (r.deleted) DELETED_ALPHA else 1f)) {
        cell(0) {
            if (r.deleted) MutedText("—") else CellField(
                r.castNumber,
                { upload.setRow(i, r.copy(castNumber = it)) },
                CAST_NO_FIELD,
                numeric = true,
            )
        }
        cell(1) { CharacterNameCell(upload, i, r, existing) }
        cell(2) { MutedText(stat?.str("scenes").orEmpty()) }
        cell(3) { MutedText(stat?.str("lines").orEmpty()) }
        cell(4) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                if (r.deleted) {
                    ZillitButton(
                        t("csync_char_restore"),
                        onClick = { upload.setRow(i, r.copy(deleted = false)) },
                        size = ButtonSize.Small,
                        variant = ButtonVariant.Secondary,
                    )
                } else {
                    OutlineDangerButton(t("csync_delete"), { upload.setRow(i, r.copy(deleted = true)) })
                }
            }
        }
    }
}

@Composable
private fun CharacterNameCell(upload: ScriptUpload, i: Int, r: ImportRow, existing: Rec?) {
    if (r.manual && !r.deleted) {
        CellField(
            r.name,
            { upload.setRow(i, r.copy(name = it)) },
            Dp.Unspecified,
            autoFocus = true,
            placeholder = t("csync_field_name"),
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                r.name.uppercase().ifEmpty { "—" },
                Modifier.weight(1f, fill = false),
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = if (r.deleted) TextDecoration.LineThrough else null,
                ),
                maxLines = 1,
            )
            if (existing != null && !r.deleted) {
                ZillitText(
                    " · " + t("csync_char_exists") +
                        if (existing.has("cast_number")) " #${existing.str("cast_number")}" else "",
                    style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Normal),
                    color = ZillitTheme.colors.textMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

private val CAST_NO_FIELD = 84.dp
private const val DELETED_ALPHA = 0.45f
