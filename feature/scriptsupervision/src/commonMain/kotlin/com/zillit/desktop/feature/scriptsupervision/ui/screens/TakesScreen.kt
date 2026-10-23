package com.zillit.desktop.feature.scriptsupervision.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.ScriptSupervision
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.sceneText
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * Every take of the production — the web's `TakesScreen`.
 *
 * A take's flags are seven booleans on the record rather than one status,
 * because a take can be several at once: circled AND a pickup. They are edited
 * as chips and shown as tags, in the service's own order.
 */
@Suppress("LongMethod") // Header, the scene filter, the table and two dialogs.
@Composable
internal fun TakesScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var sceneFilter by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val project = ctx.api.project()
    val rows = rememberRows(sceneFilter, project) {
        api.get(
            "/takes/${project.orEmpty()}",
            mapOf("limit" to PAGE_SIZE, "scene_id" to sceneFilter),
        )
    }

    SsPageHeader(
        title = t("takes"),
        actions = {
            ZillitButton(
                text = t("add_take"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
                enabled = sceneState.scenes.isNotEmpty(),
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
            value = options.firstOrNull { it.first == sceneFilter },
            options = options,
            onSelect = { sceneFilter = it.first },
            label = { it.second },
            placeholder = t("filter_by_scene"),
            onClear = { sceneFilter = "" }.takeIf { sceneFilter.isNotBlank() },
            modifier = Modifier.width(SCENE_WIDTH),
        )
    }
    Await(rows) { records ->
        ZillitDataTable(
            rows = records,
            columns = takeColumns(onEdit = { editing = it }, onDelete = { deleting = it }),
            key = { it.id },
            emptyTitle = t("no_takes"),
            virtualised = false,
        )
    }

    if (creating || editing != null) {
        TakeForm(
            take = editing,
            sceneId = sceneFilter,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { take ->
        DeleteTakeDialog(take, onClose = { deleting = null }) { rows.reload(silent = true) }
    }
}

@Composable
private fun takeColumns(onEdit: (Rec) -> Unit, onDelete: (Rec) -> Unit): List<TableColumn<Rec>> {
    val sceneState = scenes()
    return listOf(
        textColumn(t("scene"), ColumnWidth.Fixed(110.dp)) { sceneText(sceneState.scene(it.str("scene_id"))) },
        textColumn(t("take"), ColumnWidth.Fixed(80.dp), numeric = true) {
            it.long("take_number").takeIf { n -> n > 0 }?.toString().orEmpty()
        },
        textColumn(t("slate"), ColumnWidth.Fixed(100.dp)) { it.str("slate_number") },
        textColumn(t("camera_roll"), ColumnWidth.Fixed(120.dp)) { it.str("camera_roll") },
        textColumn(t("sound_roll"), ColumnWidth.Fixed(120.dp)) { it.str("sound_roll") },
        TableColumn(t("flags"), ColumnWidth.Weight(1.4f)) { take ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ScriptSupervision.TAKE_FLAGS.filter { take.bool(it) }.forEach { flag ->
                    SsTag(label = t("flag_$flag"), tone = StatusTone.Progress)
                }
            }
        },
        textColumn(t("notes")) { it.str("notes") },
        TableColumn(header = "", width = ColumnWidth.Fixed(64.dp)) { take ->
            RowActions(onEdit = { onEdit(take) }, onDelete = { onDelete(take) })
        },
    )
}

@Suppress("LongMethod") // A take's fields, then its seven flags as chips.
@Composable
private fun TakeForm(take: Rec?, sceneId: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var scene by remember(take) { mutableStateOf(take?.str("scene_id").orEmpty().ifBlank { sceneId }) }
    var number by remember(take) {
        mutableStateOf(take?.long("take_number")?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var slate by remember(take) { mutableStateOf(take?.str("slate_number").orEmpty()) }
    var cameraRoll by remember(take) { mutableStateOf(take?.str("camera_roll").orEmpty()) }
    var soundRoll by remember(take) { mutableStateOf(take?.str("sound_roll").orEmpty()) }
    var tcIn by remember(take) { mutableStateOf(take?.str("timecode_in").orEmpty()) }
    var tcOut by remember(take) { mutableStateOf(take?.str("timecode_out").orEmpty()) }
    var notes by remember(take) { mutableStateOf(take?.str("notes").orEmpty()) }
    val flags = remember(take) {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            ScriptSupervision.TAKE_FLAGS.forEach { put(it, take?.bool(it) == true) }
        }
    }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (take == null) "add_take" else "edit_take"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    listOf(
                        "scene_id" to scene,
                        "take_number" to (number.trim().toLongOrNull() ?: 0L),
                        "slate_number" to slate.trim(),
                        "camera_roll" to cameraRoll.trim(),
                        "sound_roll" to soundRoll.trim(),
                        "timecode_in" to tcIn.trim(),
                        "timecode_out" to tcOut.trim(),
                        "notes" to notes,
                    ) +
                        // Each flag is sent either way: a flag turned OFF has
                        // to reach the service as false, not a missing key.
                        ScriptSupervision.TAKE_FLAGS.map { it to (flags[it] == true) },
                )
                val project = ctx.api.project().orEmpty()
                val done = ctx.write {
                    if (take == null) {
                        ctx.api.post("/takes/$project", payload)
                    } else {
                        ctx.api.put("/takes/${take.id}", payload)
                    }
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = scene.isNotBlank() && number.isNotBlank(),
        busy = saving,
    ) {
        FormGrid {
            PickInput(
                value = scene,
                options = sceneState.options,
                onChange = { scene = it },
                label = t("scene"),
                placeholder = t("select_scene"),
            )
            TextInput(number, { number = it }, t("take"), FormHalf, number = true)
            TextInput(slate, { slate = it }, t("slate"), FormHalf)
            TextInput(cameraRoll, { cameraRoll = it }, t("camera_roll"))
            TextInput(soundRoll, { soundRoll = it }, t("sound_roll"))
            TextInput(tcIn, { tcIn = it }, t("tc_in"), FormHalf)
            TextInput(tcOut, { tcOut = it }, t("tc_out"), FormHalf)
        }
        ZillitText(t("flags"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            ScriptSupervision.TAKE_FLAGS.forEach { flag ->
                ZillitChoiceChip(
                    label = t("flag_$flag"),
                    selected = flags[flag] == true,
                    onClick = { flags[flag] = flags[flag] != true },
                )
            }
        }
        TextInput(notes, { notes = it }, t("notes"), FormWide, multiline = true)
    }
}

@Composable
private fun DeleteTakeDialog(take: Rec, onClose: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = t("delete_take_title"),
        onDismiss = onClose,
        confirmLabel = t("delete"),
        onConfirm = {
            busy = true
            ctx.scope.launch {
                val done = ctx.write { ctx.api.delete("/takes/${take.id}") }
                busy = false
                if (done != null) {
                    onClose()
                    onDeleted()
                }
            }
        },
        busy = busy,
        danger = true,
        width = 440.dp,
    ) {
        ZillitText("${t("take")} ${take.long("take_number")}")
    }
}

private const val PAGE_SIZE = 200
private val SCENE_WIDTH = 280.dp
