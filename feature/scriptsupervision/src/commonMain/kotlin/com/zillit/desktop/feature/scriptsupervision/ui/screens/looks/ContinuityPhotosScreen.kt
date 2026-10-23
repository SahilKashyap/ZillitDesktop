package com.zillit.desktop.feature.scriptsupervision.ui.screens.looks

import androidx.compose.foundation.layout.Arrangement
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
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Looks
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.AddPhotoRow
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MultiPickInput
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.isPhotoLink
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoThumb
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * The production's continuity photos — the web's `ContinuityPhotosScreen`.
 *
 * ⚠ These are NOT a `/continuity/<folder>` resource like everything else in
 * this group: they are their own, `/continuity-photos/{projectId}`, and the
 * service FILTERS them itself by scene, character and category rather than
 * answering the whole production. So the three filters here are query
 * parameters, not a filter over what came back.
 *
 * The web uploads from the camera here (`uploadFile` to the
 * `continuity-photos` folder, then a record holding the storage key). This
 * screen lists, edits and files them, and takes a photo by ADDRESS — the
 * camera upload needs a file picker this module has no seam for yet.
 */
@Suppress("LongMethod") // Header, three filters, the grid and two dialogs.
@Composable
internal fun ContinuityPhotosScreen() {
    val ctx = LocalSs.current
    val sceneState = scenes()
    val castState = cast()
    val project = ctx.api.project().orEmpty()
    var sceneId by remember { mutableStateOf("") }
    var character by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val rows = rememberRows(project, sceneId, character, category) {
        api.get(
            "/continuity-photos/$project",
            mapOf("scene_id" to sceneId, "character" to character, "category" to category),
        )
    }

    SsPageHeader(
        title = t("continuity_photos"),
        actions = {
            ZillitButton(
                text = t("add_photo_by_url"),
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
        val sceneOptions = sceneState.options
        ZillitSearchSelect(
            value = sceneOptions.firstOrNull { it.first == sceneId },
            options = sceneOptions,
            onSelect = { sceneId = it.first },
            label = { it.second },
            placeholder = t("all_scenes"),
            onClear = { sceneId = "" }.takeIf { sceneId.isNotBlank() },
            modifier = Modifier.width(FILTER_WIDTH),
        )
        // A NAME here, as the record stores it, not a character id.
        val names = castState.characters.map { it.str("name") to it.str("name") }
        ZillitSearchSelect(
            value = names.firstOrNull { it.first == character },
            options = names,
            onSelect = { character = it.first },
            label = { it.second },
            placeholder = t("all_characters"),
            onClear = { character = "" }.takeIf { character.isNotBlank() },
            modifier = Modifier.width(NARROW_FILTER),
        )
        val categories = Looks.PHOTO_CATEGORIES.map { it to t("photo_category_$it") }
        ZillitSearchSelect(
            value = categories.firstOrNull { it.first == category },
            options = categories,
            onSelect = { category = it.first },
            label = { it.second },
            placeholder = t("category"),
            onClear = { category = "" }.takeIf { category.isNotBlank() },
            modifier = Modifier.width(NARROW_FILTER),
        )
    }
    Await(rows) { photos ->
        if (photos.isEmpty()) {
            ZillitEmptyState(title = t("no_scene_photos"), message = "")
        } else {
            MutedText("${photos.size}")
            CardGrid(photos, minWidth = PHOTO_CARD) { photo ->
                PhotoCard(photo, onEdit = { editing = photo }, onDelete = { deleting = photo })
            }
        }
    }

    if (creating || editing != null) {
        PhotoForm(
            photo = editing,
            presetScene = sceneId,
            onClose = { creating = false; editing = null },
            onSaved = { rows.reload(silent = true) },
        )
    }
    deleting?.let { photo ->
        DeleteDialog(
            title = t("delete_photo_title"),
            name = photo.str("caption"),
            onClose = { deleting = null },
            onDone = { rows.reload(silent = true) },
            run = { ctx.api.delete("/continuity-photos/${photo.id}") },
        )
    }
}

@Composable
private fun PhotoCard(photo: Rec, onEdit: () -> Unit, onDelete: () -> Unit) {
    val category = photo.str("category").ifBlank { "general" }
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth(),
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        PhotoThumb(photo.str("url"), width = CARD_PHOTO_W, height = CARD_PHOTO_H)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The number travels on the record, so a photo names its scene
            // even when that scene has been deleted.
            photo.str("scene_number").takeIf { it.isNotBlank() }?.let { SsTag("${t("sc")} $it") }
            photo.str("take_number").takeIf { it.isNotBlank() }?.let { SsTag("${t("take")} $it") }
            SsTag(t("photo_category_$category"))
        }
        photo.str("caption").takeIf { it.isNotBlank() }?.let {
            ZillitText(it, style = ZillitTheme.typography.bodySmall)
        }
        Field(t("characters"), photo.strings("characters").joinToString(", "))
        Field(t("notes"), photo.str("notes"))
    }
}

/**
 * The photo form.
 *
 * ⚠ An EDIT may not change the image: the web sends only the metadata on an
 * update, because the stored key and the file behind it belong together. So
 * the address field is on a create only.
 */
@Suppress("LongMethod") // The photo's six metadata fields.
@Composable
private fun PhotoForm(photo: Rec?, presetScene: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    val castState = cast()
    var url by remember(photo) { mutableStateOf("") }
    var sceneId by remember(photo) {
        mutableStateOf(photo?.str("scene_id").orEmpty().ifBlank { presetScene })
    }
    var takeNumber by remember(photo) { mutableStateOf(photo?.str("take_number").orEmpty()) }
    var characters by remember(photo) { mutableStateOf(photo?.strings("characters").orEmpty()) }
    var category by remember(photo) { mutableStateOf(photo?.str("category").orEmpty().ifBlank { "reference" }) }
    var caption by remember(photo) { mutableStateOf(photo?.str("caption").orEmpty()) }
    var notes by remember(photo) { mutableStateOf(photo?.str("notes").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (photo == null) "add_reference_photo" else "edit_photo"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val scene = sceneState.scene(sceneId)
                val meta = listOf(
                    "scene_id" to sceneId,
                    "take_number" to takeNumber.trim(),
                    "characters" to characters,
                    "category" to category,
                    "caption" to caption.trim(),
                    "notes" to notes,
                    // Kept beside the id so a photo list needs no scene join.
                    "scene_number" to (scene?.str("scene_number") ?: photo?.str("scene_number").orEmpty()),
                )
                val done = ctx.write {
                    if (photo == null) {
                        ctx.api.post(
                            "/continuity-photos/${ctx.api.project().orEmpty()}",
                            body(meta + ("url" to url.trim())).dropEmpty(),
                        )
                    } else {
                        ctx.api.put("/continuity-photos/${photo.id}", body(meta))
                    }
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        // An uploaded photo leaves a storage KEY here, not an address, so the
        // gate is "something is set", not "it looks like a link".
        confirmEnabled = photo != null || url.isNotBlank(),
        busy = saving,
        width = FORM_WIDTH,
    ) {
        // A create only: the stored image and its record belong together, so
        // an EDIT sends metadata alone and never re-points the picture.
        if (photo == null) {
            // An upload leaves a storage key, which means nothing to a
            // reader — so it is shown as the PICTURE, the way the web's
            // picture-card shows it, and the address field is for the other
            // way in.
            if (url.isNotBlank() && !isPhotoLink(url)) {
                PhotoThumb(url, width = CHOSEN_W, height = CHOSEN_H)
            } else {
                FormGrid { TextInput(url, { url = it }, t("image_url"), FormWide) }
            }
            AddPhotoRow(folder = "continuity-photos", onAdded = { url = it })
        }
        FormGrid {
            PickInput(
                value = sceneId,
                options = sceneState.options,
                onChange = { sceneId = it },
                label = t("scene"),
                clearable = true,
            )
            TextInput(takeNumber, { takeNumber = it }, t("take_number"))
            MultiPickInput(
                values = characters,
                options = castState.characters.map { it.str("name") to it.str("name") },
                onChange = { characters = it },
                label = t("characters"),
            )
            PickInput(
                value = category,
                options = Looks.PHOTO_CATEGORIES.map { it to t("photo_category_$it") },
                onChange = { category = it },
                label = t("category"),
            )
            TextInput(caption, { caption = it }, t("caption"), FormWide)
            TextInput(notes, { notes = it }, t("notes"), FormWide, multiline = true)
        }
    }
}

private val FILTER_WIDTH = 240.dp
private val NARROW_FILTER = 180.dp
private val PHOTO_CARD = 280.dp
private val CARD_PHOTO_W = 240.dp
private val CARD_PHOTO_H = 160.dp
private val CHOSEN_W = 200.dp
private val CHOSEN_H = 140.dp
private val FORM_WIDTH = 640.dp
