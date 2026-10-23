package com.zillit.desktop.feature.scriptsupervision.ui.screens.looks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.NumberChip
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Wardrobe, make-up and prop continuity per scene — the web's
 * `PropsWardrobeScreen`.
 *
 * One entry per scene and character: what they were wearing, how they were
 * made up, and which props they handled and in what state. This is the
 * fastest thing on set to write and the first thing anybody asks for on a
 * reshoot, which is why it is a flat list and not a form per department.
 *
 * ⚠ `character` here is a NAME, not an id — the service stores the text, so a
 * character who has no record yet can still be written down mid-take. That is
 * why the picker offers the production's characters but the field keeps
 * whatever is in it.
 */
@Suppress("LongMethod") // Header, the scene filter, the grid and two dialogs.
@Composable
internal fun PropsWardrobeScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var sceneId by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "props-wardrobe") }
    val rows = rememberRowsFor(crud, sceneId)

    SsPageHeader(
        title = t("props_wardrobe"),
        actions = {
            ZillitButton(
                text = t("new_item"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val options = sceneState.options
        ZillitSearchSelect(
            value = options.firstOrNull { it.first == sceneId },
            options = options,
            onSelect = { sceneId = it.first },
            label = { it.second },
            placeholder = t("all_scenes"),
            onClear = { sceneId = "" }.takeIf { sceneId.isNotBlank() },
            modifier = Modifier.width(FILTER_WIDTH),
        )
    }
    Await(rows) { entries ->
        if (entries.isEmpty()) {
            ZillitEmptyState(
                title = t("no_items_matching"),
                message = "",
                action = {
                    ZillitButton(
                        text = t("new_item"),
                        onClick = { ctx.whenPost { creating = true } },
                        variant = ButtonVariant.Secondary,
                    )
                },
            )
        } else {
            CardGrid(entries, minWidth = ENTRY_CARD) { entry ->
                EntryCard(entry, onEdit = { editing = entry }, onDelete = { deleting = entry })
            }
        }
    }

    if (creating || editing != null) {
        EntryForm(
            entry = editing,
            presetScene = sceneId,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { entry ->
        DeleteDialog(
            title = "${t("delete")} ${entry.str("character").ifBlank { t("this_entry") }}",
            name = "",
            onClose = { deleting = null },
            onDone = { rows.reload(silent = true) },
            run = { crud.remove(entry.id) },
        )
    }
}

/** The entries, filtered by scene on the SERVICE rather than here. */
@Composable
private fun rememberRowsFor(crud: ContinuityCrud, sceneId: String) =
    com.zillit.desktop.feature.scriptsupervision.ui.rememberRows(sceneId) {
        crud.list(if (sceneId.isBlank()) emptyMap() else mapOf("scene_id" to sceneId))
    }

/** One scene-and-character entry. */
@Composable
private fun EntryCard(entry: Rec, onEdit: () -> Unit, onDelete: () -> Unit) {
    val sceneState = scenes()
    val scene = sceneState.scene(entry.str("scene_id"))
    val props = entry.recs("props")
    val blank = entry.str("wardrobe").isBlank() && entry.str("makeup").isBlank() && props.isEmpty()
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberChip(caption = t("scene"), value = scene?.str("scene_number") ?: "—")
            ZillitText(
                text = entry.str("character").ifBlank { t("unnamed") },
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        scene?.str("scene_name")?.takeIf { it.isNotBlank() }?.let { MutedText(it) }
        Field(t("wardrobe"), entry.str("wardrobe"))
        Field(t("makeup"), entry.str("makeup"))
        if (props.isNotEmpty()) {
            MutedText(t("props"))
            props.forEach { prop ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = "• ${prop.str("name")}",
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    prop.str("state").takeIf { it.isNotBlank() }?.let { SsTag(it) }
                }
                prop.str("notes").takeIf { it.isNotBlank() }?.let { MutedText(it) }
            }
        }
        if (blank) MutedText(t("no_actor_details"))
    }
}

/** The entry form: the scene, who, and the three departments. */
@Suppress("LongMethod") // The entry's four fields and its prop list.
@Composable
private fun EntryForm(entry: Rec?, presetScene: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    val castState = cast()
    var sceneId by remember(entry) {
        mutableStateOf(entry?.str("scene_id").orEmpty().ifBlank { presetScene })
    }
    var character by remember(entry) { mutableStateOf(entry?.str("character").orEmpty()) }
    var wardrobe by remember(entry) { mutableStateOf(entry?.str("wardrobe").orEmpty()) }
    var makeup by remember(entry) { mutableStateOf(entry?.str("makeup").orEmpty()) }
    var props by remember(entry) {
        mutableStateOf(
            entry?.recs("props").orEmpty().map { Triple(it.str("name"), it.str("state"), it.str("notes")) },
        )
    }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (entry == null) "new_item" else "edit"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    "scene_id" to sceneId,
                    "character" to character.trim(),
                    "wardrobe" to wardrobe,
                    "makeup" to makeup,
                    "props" to propsJson(props),
                )
                val crud = ContinuityCrud(ctx.api, "props-wardrobe")
                val done = ctx.write {
                    if (entry == null) crud.create(payload.dropEmpty()) else crud.update(entry.id, payload)
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = sceneId.isNotBlank(),
        busy = saving,
        width = FORM_WIDTH,
    ) {
        FormGrid {
            PickInput(
                value = sceneId,
                options = sceneState.options,
                onChange = { sceneId = it },
                label = t("scene"),
            )
            // A NAME, so somebody with no character record can be written
            // down; the production's characters are offered as a convenience.
            PickInput(
                value = character,
                options = castState.characters.map { it.str("name") to it.str("name") },
                onChange = { character = it },
                label = t("character"),
                clearable = true,
            )
            TextInput(wardrobe, { wardrobe = it }, t("wardrobe"), FormWide, multiline = true)
            TextInput(makeup, { makeup = it }, t("makeup"), FormWide, multiline = true)
        }
        ZillitSectionLabel(t("props"))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            props.forEachIndexed { index, (name, state, notes) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextInput(
                        value = name,
                        onChange = { props = props.at(index, Triple(it, state, notes)) },
                        label = null,
                        modifier = Modifier.weight(1f),
                        placeholder = t("prop_name"),
                    )
                    TextInput(
                        value = state,
                        onChange = { props = props.at(index, Triple(name, it, notes)) },
                        label = null,
                        modifier = Modifier.weight(1f),
                        placeholder = t("state"),
                    )
                    TextInput(
                        value = notes,
                        onChange = { props = props.at(index, Triple(name, state, it)) },
                        label = null,
                        modifier = Modifier.weight(1f),
                        placeholder = t("notes"),
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = t("remove"),
                        onClick = { props = props.filterIndexed { at, _ -> at != index } },
                    )
                }
            }
            ZillitButton(
                text = t("add"),
                onClick = { props = props + Triple("", "", "") },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

private fun <T> List<T>.at(index: Int, value: T): List<T> =
    mapIndexed { at, old -> if (at == index) value else old }

/** The props, dropping the rows with no name — the web's own `required` on that field. */
private fun propsJson(rows: List<Triple<String, String, String>>) = buildJsonArray {
    rows.filter { (name, _, _) -> name.isNotBlank() }.forEach { (name, state, notes) ->
        add(
            buildJsonObject {
                put("name", name.trim())
                put("state", state.trim())
                put("notes", notes.trim())
            },
        )
    }
}

private val FILTER_WIDTH = 280.dp
private val ENTRY_CARD = 340.dp
private val FORM_WIDTH = 760.dp
