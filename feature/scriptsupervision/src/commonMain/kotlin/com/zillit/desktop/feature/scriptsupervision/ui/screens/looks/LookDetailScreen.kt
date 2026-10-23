package com.zillit.desktop.feature.scriptsupervision.ui.screens.looks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Looks
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.lookStatusTone
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MultiPickInput
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.NumberChip
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoStrip
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.Resource
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One look, in full — the web's `LookDetailScreen`.
 *
 * The screen a continuity person works from on the day: what the look is,
 * which scenes it covers, the photos that prove it, and the detail lines
 * (colours, products, notes) that let somebody rebuild it. Costume looks also
 * carry their garments.
 *
 * ## Why almost every write here is a whole-array PUT
 *
 * The photos, the products and the detail lines are FIELDS on the look record,
 * not records of their own — the service has no endpoint for one of them. So
 * adding a product means sending the whole `products` array back, and removing
 * one means sending the array without it. The web does the same, and the
 * consequence is worth knowing: two people editing one look's details at once
 * will have the last write win.
 */
@Suppress("LongMethod") // The look's header, its stepper and its five sections.
@Composable
internal fun LookDetailScreen(lookId: String, department: String) {
    val ctx = LocalSs.current
    val navState = nav()
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "looks") }
    // The whole department's looks: this one, and the previous/next stepper.
    val siblings = rememberRows(department) { crud.list(mapOf("department" to department)) }
    var editing by remember(lookId) { mutableStateOf(false) }
    var addingProduct by remember(lookId) { mutableStateOf(false) }
    var addingDetail by remember(lookId) { mutableStateOf(false) }
    var pickingScenes by remember(lookId) { mutableStateOf(false) }
    var clearingPhotos by remember(lookId) { mutableStateOf(false) }

    Await(siblings) { looks ->
        val ordered = looks.sortedWith(Looks.byNatural { it.str("look_number") })
        val at = ordered.indexOfFirst { it.id == lookId }
        val look = ordered.getOrNull(at)
        if (look == null) {
            SsPageHeader(title = t("looks"), actions = { BackButton(navState::back) })
            ZillitEmptyState(title = t("look_not_found"), message = "")
            return@Await
        }
        val reload = { siblings.reload(silent = true) }
        val patch = { fields: List<Pair<String, Any?>> ->
            ctx.launchWrite({ crud.update(lookId, body(fields)) }) { reload() }
        }

        SsPageHeader(
            title = Looks.code(look).ifBlank { look.str("name") },
            sub = look.str("character_name"),
            actions = {
                BackButton(navState::back)
                ZillitButton(
                    text = t("edit"),
                    onClick = { ctx.whenPost { editing = true } },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Edit,
                )
            },
        )
        LookStepper(
            previous = ordered.getOrNull(at - 1),
            next = ordered.getOrNull(at + 1),
            onGo = { navState.swap(SsView.Look(it.id, it.str("character_id"), department)) },
        )

        ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NumberChip(
                    caption = t("dept_$department"),
                    value = look.str("look_number").takeIf { it.isNotBlank() }?.let { "#$it" } ?: "—",
                )
                ZillitText(
                    text = look.str("name"),
                    style = ZillitTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                val status = look.str("status").ifBlank { "planned" }
                SsTag(t("look_status_$status"), lookStatusTone(status))
            }
            Field(t("description"), look.str("description"))
            Field(t("continuity_notes"), look.str("continuity_notes"))
            if (department == "costume") {
                Field(t("aging_distressing"), look.str("aging_distressing"))
                Field(t("wear_state"), look.str("wear_state"))
            } else {
                Field(t("application_notes"), look.str("application_notes"))
            }
        }

        PhotosSection(
            photos = look.strings("reference_image_urls"),
            onChange = { patch(listOf("reference_image_urls" to it)) },
            onClear = { ctx.whenPost { clearingPhotos = true } },
        )
        if (department != "costume") {
            ProductsSection(
                products = look.recs("products"),
                onAdd = { ctx.whenPost { addingProduct = true } },
                onRemove = { index -> patch(listOf("products" to without(look.recs("products"), index))) },
            )
        }
        DetailsSection(
            details = look.recs("details"),
            onAdd = { ctx.whenPost { addingDetail = true } },
            onRemove = { index -> patch(listOf("details" to without(look.recs("details"), index))) },
        )
        ScenesSection(
            sceneIds = look.strings("scene_ids"),
            onPick = { ctx.whenPost { pickingScenes = true } },
        )
        if (department == "costume") {
            GarmentsSection(look)
        }

        if (editing) {
            LookForm(
                look = look,
                department = department,
                presetCharacter = look.str("character_id"),
                onClose = { editing = false },
                onSaved = reload,
            )
        }
        if (addingProduct) {
            AddProductDialog(
                onClose = { addingProduct = false },
                onAdd = { product -> patch(listOf("products" to plus(look.recs("products"), product))) },
            )
        }
        if (addingDetail) {
            AddDetailDialog(
                onClose = { addingDetail = false },
                onAdd = { detail -> patch(listOf("details" to plus(look.recs("details"), detail))) },
            )
        }
        if (pickingScenes) {
            PickScenesDialog(
                sceneIds = look.strings("scene_ids"),
                onClose = { pickingScenes = false },
                onSave = { ids -> patch(listOf("scene_ids" to ids)) },
            )
        }
        if (clearingPhotos) {
            DeleteDialog(
                title = t("clear_photos_title"),
                name = "",
                onClose = { clearingPhotos = false },
                onDone = reload,
                run = { crud.update(lookId, body("reference_image_urls" to emptyList<String>())) },
            )
        }
    }
}

@Composable
private fun BackButton(onBack: () -> Unit) {
    ZillitButton(
        text = t("looks"),
        onClick = onBack,
        variant = ButtonVariant.Secondary,
        leadingIcon = ZillitIcons.ArrowLeft,
    )
}

/** The previous and next look in the department, by look number. */
@Composable
private fun LookStepper(previous: Rec?, next: Rec?, onGo: (Rec) -> Unit) {
    if (previous == null && next == null) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = previous?.let { Looks.code(it) } ?: "—",
            onClick = { previous?.let(onGo) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.ArrowLeft,
            enabled = previous != null,
        )
        ZillitButton(
            text = next?.let { Looks.code(it) } ?: "—",
            onClick = { next?.let(onGo) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.ArrowRight,
            enabled = next != null,
        )
    }
}

/** A section of the look with its own heading and one action. */
@Composable
private fun LookSection(
    title: String,
    action: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth(), title = title, action = action?.let { { it() } }) {
        content()
    }
}

@Composable
private fun PhotosSection(photos: List<String>, onChange: (List<String>) -> Unit, onClear: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    LookSection(
        title = t("photos"),
        action = {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitIconButton(
                    icon = ZillitIcons.Add,
                    contentDescription = t("add_photo_by_url"),
                    onClick = { adding = true },
                )
                if (photos.isNotEmpty()) {
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = t("delete"),
                        onClick = onClear,
                        tint = ZillitTheme.colors.danger,
                    )
                }
            }
        },
    ) {
        if (photos.isEmpty()) MutedText(t("no_photo_loaded")) else PhotoStrip(photos)
    }
    if (adding) {
        PhotoUrlsDialog(photos, onClose = { adding = false }, onSave = onChange)
    }
}

/**
 * The look's photo list, edited as addresses.
 *
 * The web uploads here (`PhotoField`, folder `looks`); this edits the list of
 * addresses instead, because the camera upload wants a file picker this module
 * has no seam for yet. A look photographed on the web shows here either way —
 * the field is the same list of storage keys.
 */
@Composable
private fun PhotoUrlsDialog(photos: List<String>, onClose: () -> Unit, onSave: (List<String>) -> Unit) {
    var values by remember { mutableStateOf(photos) }
    FormDialog(
        open = true,
        title = t("reference_photos"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            onSave(values)
            onClose()
        },
        width = DIALOG_WIDTH,
    ) {
        FormGrid {
            TagInput(
                tokens = values,
                onChange = { values = it },
                label = t("image_url"),
                modifier = FormWide,
                help = t("add_photo_by_url"),
            )
        }
    }
}

@Composable
private fun ProductsSection(products: List<Rec>, onAdd: () -> Unit, onRemove: (Int) -> Unit) {
    LookSection(
        title = t("products"),
        action = {
            ZillitIconButton(icon = ZillitIcons.Add, contentDescription = t("add_product"), onClick = onAdd)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            if (products.isEmpty()) MutedText(t("no_products"))
            products.forEachIndexed { index, product ->
                RemovableRow(
                    text = listOf(product.str("name"), product.str("brand"), product.str("shade"))
                        .filter { it.isNotBlank() }
                        .joinToString(" — "),
                    note = product.str("notes"),
                    onRemove = { onRemove(index) },
                )
            }
        }
    }
}

/**
 * The look's detail lines.
 *
 * A line is a TYPE and a value: a colour, a product, a note. The type is what
 * lets a make-up artist read the sheet in a hurry, which is why it is a
 * pick-list from the service's own enum and not free text.
 */
@Composable
private fun DetailsSection(details: List<Rec>, onAdd: () -> Unit, onRemove: (Int) -> Unit) {
    LookSection(
        title = t("details"),
        action = {
            ZillitIconButton(icon = ZillitIcons.Add, contentDescription = t("add"), onClick = onAdd)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            if (details.isEmpty()) MutedText(t("no_details"))
            details.forEachIndexed { index, detail ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SsTag(t("detail_type_${detail.str("type").ifBlank { "other" }}"))
                    ZillitText(
                        text = detail.str("text"),
                        style = ZillitTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    detail.str("color").takeIf { it.isNotBlank() }?.let { SsTag(it) }
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = t("delete"),
                        onClick = { onRemove(index) },
                        tint = ZillitTheme.colors.danger,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScenesSection(sceneIds: List<String>, onPick: () -> Unit) {
    val sceneState = scenes()
    LookSection(
        title = t("continuity_scenes"),
        action = {
            ZillitIconButton(icon = ZillitIcons.Edit, contentDescription = t("manage_scenes"), onClick = onPick)
        },
    ) {
        // Only the scenes the production still has: a look keeps the id of a
        // deleted scene, and a row for it would name nothing.
        val here = sceneIds.mapNotNull { id -> sceneState.scenes.firstOrNull { it.id == id } }
        if (here.isEmpty()) {
            MutedText(t("no_scene_looks"))
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                here.forEach { SsTag("${t("sc")} ${it.str("scene_number")}") }
            }
        }
    }
}

/** The garments that make up a costume look. */
@Composable
private fun GarmentsSection(look: Rec) {
    val ctx = LocalSs.current
    val garments: Resource<List<Rec>> = rememberRows(look.id) {
        ContinuityCrud(ctx.api, "garments").list(mapOf("look_id" to look.id))
    }
    LookSection(title = t("garments")) {
        Await(garments) { rows ->
            if (rows.isEmpty()) {
                MutedText(t("no_items_matching"))
            } else {
                rows.forEach { garment ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ZillitText(
                            text = garment.str("name"),
                            style = ZillitTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        garment.str("size").takeIf { it.isNotBlank() }?.let { SsTag(it) }
                        garment.str("condition").takeIf { it.isNotBlank() && it != "new" }
                            ?.let { SsTag(t("garment_condition_$it")) }
                    }
                }
            }
        }
    }
}

/** A list row with its own remove button. */
@Composable
private fun RemovableRow(text: String, note: String, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(text, style = ZillitTheme.typography.bodySmall)
            if (note.isNotBlank()) MutedText(note)
        }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = t("delete"),
            onClick = onRemove,
            tint = ZillitTheme.colors.danger,
        )
    }
}

@Composable
private fun AddProductDialog(onClose: () -> Unit, onAdd: (Map<String, String>) -> Unit) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var shade by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    FormDialog(
        open = true,
        title = t("add_product"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            onAdd(mapOf("name" to name.trim(), "brand" to brand.trim(), "shade" to shade.trim(), "notes" to notes))
            onClose()
        },
        confirmEnabled = name.isNotBlank(),
        width = DIALOG_WIDTH,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("product"))
            TextInput(brand, { brand = it }, t("brand"))
            TextInput(shade, { shade = it }, t("shade"))
            TextInput(notes, { notes = it }, t("notes"), FormWide, multiline = true)
        }
    }
}

@Composable
private fun AddDetailDialog(onClose: () -> Unit, onAdd: (Map<String, String>) -> Unit) {
    var type by remember { mutableStateOf(Looks.DETAIL_TYPES.first()) }
    var colour by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    FormDialog(
        open = true,
        title = t("details"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            onAdd(mapOf("type" to type, "color" to colour.trim(), "text" to text.trim()))
            onClose()
        },
        confirmEnabled = text.isNotBlank(),
        width = DIALOG_WIDTH,
    ) {
        FormGrid {
            PickInput(
                value = type,
                options = Looks.DETAIL_TYPES.map { it to t("detail_type_$it") },
                onChange = { type = it },
                label = t("type"),
            )
            TextInput(colour, { colour = it }, t("color"))
            TextInput(text, { text = it }, t("type_your_text"), FormWide, multiline = true)
        }
    }
}

@Composable
private fun PickScenesDialog(sceneIds: List<String>, onClose: () -> Unit, onSave: (List<String>) -> Unit) {
    val sceneState = scenes()
    var picked by remember { mutableStateOf(sceneIds) }
    FormDialog(
        open = true,
        title = t("continuity_scenes"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            onSave(picked)
            onClose()
        },
        width = DIALOG_WIDTH,
    ) {
        FormGrid {
            MultiPickInput(
                values = picked,
                options = sceneState.options,
                onChange = { picked = it },
                label = t("scenes"),
                modifier = FormWide,
            )
        }
    }
}

/** The array without the row at [index] — a whole-field write, as the service needs. */
private fun without(rows: List<Rec>, index: Int): JsonArray = buildJsonArray {
    rows.filterIndexed { at, _ -> at != index }.forEach { add(it.json) }
}

/** The array with one more row on the end. */
private fun plus(rows: List<Rec>, fields: Map<String, String>): JsonArray = buildJsonArray {
    rows.forEach { add(it.json) }
    add(buildJsonObject { fields.forEach { (key, value) -> put(key, value) } })
}

private val DIALOG_WIDTH = 520.dp
