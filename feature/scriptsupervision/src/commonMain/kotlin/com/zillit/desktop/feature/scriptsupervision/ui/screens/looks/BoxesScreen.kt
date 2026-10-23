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
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Looks
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
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
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The department's boxes — the web's `BoxesScreen`.
 *
 * What is packed in which box, where that box is, and who has it. A box is the
 * unit a production actually moves between a truck, a trailer and a set, so
 * the fields are the ones written on the lid: a number, a label, a
 * department, a status, a location and who it is checked out to.
 */
@Suppress("LongMethod") // Header, two filters, the grid and two dialogs.
@Composable
internal fun BoxesScreen() {
    val ctx = LocalSs.current
    var department by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "boxes") }
    val rows = rememberRows(ctx) { crud.list() }

    SsPageHeader(
        title = t("boxes"),
        actions = {
            ZillitButton(
                text = t("add_box"),
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
        val departments = Looks.BOX_DEPARTMENTS.map { it to t("dept_$it") }
        ZillitSearchSelect(
            value = departments.firstOrNull { it.first == department },
            options = departments,
            onSelect = { department = it.first },
            label = { it.second },
            placeholder = t("department"),
            onClear = { department = "" }.takeIf { department.isNotBlank() },
            modifier = Modifier.width(FILTER_WIDTH),
        )
        val statuses = Looks.BOX_STATUSES.map { it to t("box_status_$it") }
        ZillitSearchSelect(
            value = statuses.firstOrNull { it.first == status },
            options = statuses,
            onSelect = { status = it.first },
            label = { it.second },
            placeholder = t("status"),
            onClear = { status = "" }.takeIf { status.isNotBlank() },
            modifier = Modifier.width(FILTER_WIDTH),
        )
    }
    Await(rows) { boxes ->
        // Filtered here, not on the service: the list is the whole production
        // in one answer, as every continuity folder is.
        val shown = boxes.filter { box ->
            (department.isBlank() || box.str("department") == department) &&
                (status.isBlank() || box.str("status") == status)
        }
        if (shown.isEmpty()) {
            ZillitEmptyState(
                title = t("no_boxes"),
                message = "",
                action = {
                    ZillitButton(
                        text = t("add_box"),
                        onClick = { ctx.whenPost { creating = true } },
                        variant = ButtonVariant.Secondary,
                    )
                },
            )
        } else {
            CardGrid(shown.sortedWith(Looks.byNatural { it.str("box_number") }), minWidth = BOX_CARD) { box ->
                BoxCard(box, onEdit = { editing = box }, onDelete = { deleting = box })
            }
        }
    }

    if (creating || editing != null) {
        BoxForm(
            box = editing,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { box ->
        DeleteDialog(
            title = t("delete_box_title"),
            name = box.str("label").ifBlank { box.str("box_number") },
            onClose = { deleting = null },
            onDone = { rows.reload(silent = true) },
            run = { crud.remove(box.id) },
        )
    }
}

/** How a box's status reads: lost shouts, in-use is in motion, returned is done. */
private fun boxStatusTone(status: String): StatusTone = when (status) {
    "in_use" -> StatusTone.Progress
    "returned" -> StatusTone.Done
    "lost" -> StatusTone.Escalated
    // `packed` and `wrapped` are both at rest.
    else -> StatusTone.Neutral
}

@Composable
private fun BoxCard(box: Rec, onEdit: () -> Unit, onDelete: () -> Unit) {
    val contents = box.recs("contents")
    val status = box.str("status").ifBlank { "packed" }
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NumberChip(caption = t("box_number"), value = box.str("box_number").ifBlank { "—" })
            ZillitText(
                text = box.str("label").ifBlank { t("unnamed") },
                style = ZillitTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            SsTag(t("box_status_$status"), boxStatusTone(status))
        }
        box.str("department").takeIf { it.isNotBlank() }?.let { SsTag(t("dept_$it")) }
        Field(t("location"), box.str("location"))
        // Who has it is the question the screen exists to answer.
        Field(t("checked_out_to"), box.str("checked_out_to"))
        Field(t("notes"), box.str("notes"))
        if (contents.isNotEmpty()) {
            MutedText("${t("contents")} · ${contents.size}")
            contents.forEach { item ->
                val quantity = item.int("quantity").takeIf { it > 1 }?.let { " ×$it" }.orEmpty()
                MutedText("• ${item.str("name")}$quantity")
            }
        }
    }
}

@Suppress("LongMethod") // The box's seven fields and its contents list.
@Composable
private fun BoxForm(box: Rec?, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    var number by remember(box) { mutableStateOf(box?.str("box_number").orEmpty()) }
    var label by remember(box) { mutableStateOf(box?.str("label").orEmpty()) }
    var department by remember(box) { mutableStateOf(box?.str("department").orEmpty()) }
    var status by remember(box) { mutableStateOf(box?.str("status").orEmpty().ifBlank { "packed" }) }
    var location by remember(box) { mutableStateOf(box?.str("location").orEmpty()) }
    var checkedOutTo by remember(box) { mutableStateOf(box?.str("checked_out_to").orEmpty()) }
    var notes by remember(box) { mutableStateOf(box?.str("notes").orEmpty()) }
    var contents by remember(box) {
        mutableStateOf(
            box?.recs("contents").orEmpty().map {
                it.str("name") to it.int("quantity").coerceAtLeast(1).toString()
            },
        )
    }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (box == null) "add_box" else "edit_box"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    "box_number" to number.trim(),
                    "label" to label.trim(),
                    "department" to department,
                    "status" to status,
                    "location" to location.trim(),
                    "checked_out_to" to checkedOutTo.trim(),
                    "notes" to notes,
                    "contents" to contentsJson(contents),
                )
                val crud = ContinuityCrud(ctx.api, "boxes")
                val done = ctx.write {
                    if (box == null) crud.create(payload.dropEmpty()) else crud.update(box.id, payload)
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = label.isNotBlank() || number.isNotBlank(),
        busy = saving,
        width = FORM_WIDTH,
    ) {
        FormGrid {
            TextInput(number, { number = it }, t("box_number"), FormHalf)
            TextInput(label, { label = it }, t("field_label"))
            PickInput(
                value = department,
                options = Looks.BOX_DEPARTMENTS.map { it to t("dept_$it") },
                onChange = { department = it },
                label = t("department"),
                clearable = true,
            )
            PickInput(
                value = status,
                options = Looks.BOX_STATUSES.map { it to t("box_status_$it") },
                onChange = { status = it },
                label = t("status"),
            )
            TextInput(location, { location = it }, t("location"))
            TextInput(checkedOutTo, { checkedOutTo = it }, t("checked_out_to"))
            TextInput(notes, { notes = it }, t("notes"), FormWide, multiline = true)
        }
        ZillitSectionLabel(t("contents"))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            contents.forEachIndexed { index, (name, quantity) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextInput(
                        value = name,
                        onChange = { contents = contents.at(index, it to quantity) },
                        label = null,
                        modifier = Modifier.weight(1f),
                        placeholder = t("item"),
                    )
                    TextInput(
                        value = quantity,
                        onChange = { contents = contents.at(index, name to it) },
                        label = null,
                        modifier = Modifier.width(QTY_WIDTH),
                        placeholder = t("qty"),
                        number = true,
                    )
                    ZillitIconButton(
                        icon = ZillitIcons.Close,
                        contentDescription = t("remove"),
                        onClick = { contents = contents.filterIndexed { at, _ -> at != index } },
                    )
                }
            }
            ZillitButton(
                text = t("add"),
                onClick = { contents = contents + ("" to "1") },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Add,
            )
        }
    }
}

private fun <T> List<T>.at(index: Int, value: T): List<T> =
    mapIndexed { at, old -> if (at == index) value else old }

/** The contents, dropping unnamed rows; a quantity is at least one, as the web's `min` is. */
private fun contentsJson(rows: List<Pair<String, String>>) = buildJsonArray {
    rows.filter { (name, _) -> name.isNotBlank() }.forEach { (name, quantity) ->
        add(
            buildJsonObject {
                put("name", name.trim())
                put("quantity", quantity.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1)
            },
        )
    }
}

private val FILTER_WIDTH = 200.dp
private val BOX_CARD = 320.dp
private val FORM_WIDTH = 720.dp
private val QTY_WIDTH = 90.dp
