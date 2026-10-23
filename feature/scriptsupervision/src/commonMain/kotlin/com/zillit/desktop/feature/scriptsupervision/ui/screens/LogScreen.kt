package com.zillit.desktop.feature.scriptsupervision.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.textColumn
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RecordCount
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SwitchInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.sceneText
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * The twelve continuity-log screens, as one screen driven by a spec.
 *
 * ## Why one screen
 *
 * `shoot/` and `takeLogs/` are twelve pages that are the same page: a scene
 * filter, a table, and a dialog over `crud(folder)` — which is exactly how the
 * web builds them (one `crud()` factory and a shared `LogParts`). Twelve
 * hand-written copies here would be twelve places for the empty state, the
 * delete confirm, the scene column and the create/edit split to drift apart,
 * and the web has already shown which details are easy to get wrong:
 *
 *  - a CREATE drops the fields nobody filled ([dropEmpty]) because the
 *    service's validator refuses `""` for a pick-list, while an EDIT must send
 *    `""` to clear what is stored;
 *  - the service keeps `0` for a number nobody entered, which reads blank;
 *  - the scene filter is the one thing every list is narrowed by.
 *
 * So the *fields* are data here, and a screen is its spec. A page that needs
 * more than this (the quick log, a detail page) is written by hand.
 */
internal class LogField(
    val key: String,
    val labelKey: String,
    val kind: FieldKind = FieldKind.Text,
    val width: Modifier = Modifier.width(240.dp),
    val required: Boolean = false,
    val placeholder: String? = null,
    /** For [FieldKind.Pick]: `stored value to label`. */
    val options: List<Pair<String, String>> = emptyList(),
)

internal enum class FieldKind { Text, LongText, Number, Pick, Switch, Scene }

/** One column of a log's table. */
internal class LogColumn(
    val key: String,
    val labelKey: String,
    val width: ColumnWidth = ColumnWidth.Weight(1f),
    val kind: CellKind = CellKind.Text,
    /** For [CellKind.Pick], so a stored code reads as its word. */
    val options: List<Pair<String, String>> = emptyList(),
)

internal enum class CellKind {
    Text,

    /** Blank when the service stored 0, right-aligned. */
    Number,

    /** The scene the record is filed under. */
    Scene,

    /** A boolean: a tag when true, nothing when false. */
    Flag,

    /** A stored code shown as its translated word. */
    Pick,
}

/** What a log screen is: its folder on the service, its words, its table and its form. */
internal class LogSpec(
    val folder: String,
    val titleKey: String,
    val addKey: String,
    val editKey: String,
    val emptyKey: String,
    val deleteTitleKey: String,
    val columns: List<LogColumn>,
    val fields: List<LogField>,
    /** The record's own line in the delete confirm, under the question. */
    val describe: (Rec) -> String = { "" },
)

/**
 * A continuity log: the scene filter, the records, and the form over them.
 *
 * The list is the whole project's records (the service answers a plain array,
 * newest first) narrowed by the picked scene, which is also what a link from
 * another screen sets.
 */
@Composable
internal fun ContinuityLogScreen(spec: LogSpec) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    var sceneFilter by remember(spec.folder) { mutableStateOf("") }
    var editing by remember(spec.folder) { mutableStateOf<Rec?>(null) }
    var creating by remember(spec.folder) { mutableStateOf(false) }
    var deleting by remember(spec.folder) { mutableStateOf<Rec?>(null) }
    val crud = remember(spec.folder, ctx) { ContinuityCrud(ctx.api, spec.folder) }
    val rows = rememberRows(spec.folder, sceneFilter) {
        crud.list(if (sceneFilter.isBlank()) emptyMap() else mapOf("scene_id" to sceneFilter))
    }

    SsPageHeader(
        title = t(spec.titleKey),
        actions = {
            ZillitButton(
                text = t(spec.addKey),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
                // Nothing can be filed before the project has a scene to file
                // it under, which is the web's own `disabled={!scenes.length}`.
                enabled = sceneState.scenes.isNotEmpty(),
            )
        },
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SceneFilter(value = sceneFilter, onChange = { sceneFilter = it })
        RecordCount(rows.value?.size ?: 0)
    }
    Await(rows) { records ->
        ZillitDataTable(
            rows = records,
            columns = tableColumns(spec, onEdit = { editing = it }, onDelete = { deleting = it }),
            key = { it.id },
            emptyTitle = t(spec.emptyKey),
            // Inside a page that already scrolls — see ZillitDataTable.
            virtualised = false,
        )
    }

    if (creating || editing != null) {
        LogForm(
            spec = spec,
            record = editing,
            sceneId = sceneFilter,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { record ->
        DeleteLogDialog(spec, record, crud, onClose = { deleting = null }) { rows.reload(silent = true) }
    }
}

/** The scene filter of a list's toolbar — every scene, searchable. */
@Composable
private fun SceneFilter(value: String, onChange: (String) -> Unit) {
    val options = scenes().options
    ZillitSearchSelect(
        value = options.firstOrNull { it.first == value },
        options = options,
        onSelect = { onChange(it.first) },
        label = { it.second },
        placeholder = t("filter_by_scene"),
        onClear = { onChange("") }.takeIf { value.isNotBlank() },
        modifier = Modifier.width(SCENE_FILTER_WIDTH),
    )
}

@Composable
private fun tableColumns(
    spec: LogSpec,
    onEdit: (Rec) -> Unit,
    onDelete: (Rec) -> Unit,
): List<TableColumn<Rec>> {
    val sceneState = scenes()
    val columns = spec.columns.map { column ->
        when (column.kind) {
            CellKind.Text -> textColumn<Rec>(t(column.labelKey), column.width) { it.str(column.key) }
            CellKind.Number -> textColumn<Rec>(t(column.labelKey), column.width, numeric = true) {
                val n = it.long(column.key)
                if (n > 0) n.toString() else ""
            }
            CellKind.Scene -> textColumn<Rec>(t(column.labelKey), column.width) {
                sceneText(sceneState.scene(it.str(column.key)))
            }
            CellKind.Flag -> TableColumn<Rec>(t(column.labelKey), column.width) { row ->
                if (row.bool(column.key)) SsTag(t(column.labelKey), StatusTone.Progress)
            }
            CellKind.Pick -> textColumn<Rec>(t(column.labelKey), column.width) { row ->
                val stored = row.str(column.key)
                // A value the list does not hold shows AS STORED: an older
                // client, or the service's AI, may have written one.
                column.options.firstOrNull { it.first == stored }?.second ?: stored
            }
        }
    }
    return columns + TableColumn(header = "", width = ColumnWidth.Fixed(ACTIONS_WIDTH)) { row ->
        RowActions(onEdit = { onEdit(row) }, onDelete = { onDelete(row) })
    }
}

/**
 * The add/edit dialog.
 *
 * Open on a record it edits; open with none it creates one, with the scene
 * filter already chosen — which is what the web's `{ scene_id: sceneId }` does.
 */
@Composable
private fun LogForm(
    spec: LogSpec,
    record: Rec?,
    sceneId: String,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    val values = remember(record, spec.folder) {
        mutableStateMapOf<String, String>().apply { putAll(spec.initialValues(record, sceneId)) }
    }
    var saving by remember { mutableStateOf(false) }
    val complete = spec.fields.none { it.required && values[it.key].orEmpty().isBlank() }

    FormDialog(
        open = true,
        title = t(if (record == null) spec.addKey else spec.editKey),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = spec.payload(values)
                val done = ctx.write {
                    // A create drops the TEXT and pick-list fields nobody
                    // filled, which is what the service's validator refuses;
                    // an edit must send the empty value, which is how a field
                    // is cleared. A blank number rides along as 0 either way —
                    // 0 IS the service's own "nobody entered one".
                    if (record == null) {
                        ContinuityCrud(ctx.api, spec.folder).create(payload.dropEmpty())
                    } else {
                        ContinuityCrud(ctx.api, spec.folder).update(record.id, payload)
                    }
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = complete,
        busy = saving,
    ) {
        FormGrid {
            spec.fields.forEach { field ->
                LogFieldInput(
                    field = field,
                    value = values[field.key].orEmpty(),
                    sceneOptions = sceneState.options,
                    onChange = { values[field.key] = it },
                )
            }
        }
    }
}

/**
 * What the form opens on: the record's own values, or an empty form with the
 * scene filter already chosen — which is what the web's `{ scene_id: sceneId }`
 * does when the add button is pressed with a scene picked.
 *
 * Every value is TEXT, including a switch and a number, so one map carries a
 * whole form whatever the spec says. A number the service stored as 0 opens
 * BLANK: 0 is its own "nobody entered one", and showing it would make the
 * reader delete a digit they never typed.
 */
private fun LogSpec.initialValues(record: Rec?, sceneId: String): Map<String, String> =
    fields.associate { field ->
        field.key to when {
            record == null && field.kind == FieldKind.Scene -> sceneId
            record == null -> ""
            field.kind == FieldKind.Switch -> record.bool(field.key).toString()
            field.kind == FieldKind.Number -> record.long(field.key).takeIf { it > 0 }?.toString().orEmpty()
            else -> record.str(field.key)
        }
    }

/** The form's values back onto the wire, each read by its field's kind. */
private fun LogSpec.payload(values: Map<String, String>): JsonObject = body(
    fields.map { field ->
        field.key to when (field.kind) {
            FieldKind.Switch -> values[field.key] == "true"
            FieldKind.Number -> values[field.key].orEmpty().trim().toLongOrNull() ?: 0L
            else -> values[field.key].orEmpty().trim()
        }
    },
)

/**
 * One field of a log's form, as its [LogField] describes it.
 *
 * Everything is held as TEXT, including a switch and a number, so one map
 * carries a whole form whatever its spec says; the save reads each back by
 * kind. A free-text field over several lines takes the whole row, since a
 * two-line note in a 240dp box is unreadable.
 */
@Composable
private fun LogFieldInput(
    field: LogField,
    value: String,
    sceneOptions: List<Pair<String, String>>,
    onChange: (String) -> Unit,
) {
    when (field.kind) {
        FieldKind.Scene -> PickInput(
            value = value,
            options = sceneOptions,
            onChange = onChange,
            label = t(field.labelKey),
            modifier = field.width,
            placeholder = t("select_scene"),
        )
        FieldKind.Pick -> PickInput(
            value = value,
            options = field.options,
            onChange = onChange,
            label = t(field.labelKey),
            modifier = field.width,
            placeholder = field.placeholder?.let { t(it) }.orEmpty(),
            // Required pickers cannot be cleared: the service refuses "".
            clearable = !field.required,
        )
        FieldKind.Switch -> SwitchInput(
            value = value == "true",
            onChange = { onChange(it.toString()) },
            label = t(field.labelKey),
            modifier = field.width,
        )
        else -> TextInput(
            value = value,
            onChange = onChange,
            label = t(field.labelKey),
            modifier = if (field.kind == FieldKind.LongText) FormWide else field.width,
            multiline = field.kind == FieldKind.LongText,
            number = field.kind == FieldKind.Number,
            placeholder = field.placeholder?.let { t(it) },
        )
    }
}

@Composable
private fun DeleteLogDialog(
    spec: LogSpec,
    record: Rec,
    crud: ContinuityCrud,
    onClose: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = t(spec.deleteTitleKey),
        onDismiss = onClose,
        confirmLabel = t("delete"),
        onConfirm = {
            busy = true
            ctx.scope.launch {
                val done = ctx.write { crud.remove(record.id) }
                // Either way: a refused delete toasts and leaves the confirm
                // usable, rather than spinning on a button nobody can press.
                busy = false
                if (done != null) {
                    onClose()
                    onDeleted()
                }
            }
        },
        busy = busy,
        danger = true,
        width = CONFIRM_WIDTH,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(t("delete_confirm"))
            spec.describe(record).takeIf { it.isNotBlank() }?.let {
                ZillitText(it, style = ZillitTheme.typography.bodySmall, color = ZillitTheme.colors.textSecondary)
            }
        }
    }
}

private val SCENE_FILTER_WIDTH = 280.dp
private val ACTIONS_WIDTH = 64.dp
private val CONFIRM_WIDTH = 440.dp
