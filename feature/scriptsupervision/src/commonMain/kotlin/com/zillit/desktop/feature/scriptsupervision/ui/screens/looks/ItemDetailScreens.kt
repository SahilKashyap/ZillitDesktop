package com.zillit.desktop.feature.scriptsupervision.ui.screens.looks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
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
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Looks
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.AddPhotoRow
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoStrip
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
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
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * One wardrobe item and one prop, in full — the web's
 * `WardrobeItemDetailScreen` and `PropDetailScreen`.
 *
 * Two screens over one shape, because the records are nearly the same thing:
 * a named item that belongs to a character, has a photo or several, and costs
 * money. What differs is the folder (`garments` / `props-items`) and which of
 * the extra fields the form shows.
 *
 * ⚠ Both carry TWO photo fields that must be written together: `photo_urls`
 * is the list, `image_url` is the cover, and [Looks.coverOf] decides what the
 * cover becomes when the list changes. Writing the list alone leaves the cover
 * pointing at a photo the record no longer has.
 */
@Composable
internal fun WardrobeItemDetailScreen(itemId: String) {
    ItemDetail(
        itemId = itemId,
        folder = "garments",
        titleKey = "wardrobe_item",
        notFoundKey = "wardrobe_item_not_found",
        deleteTitleKey = "delete_wardrobe_item_title",
        backKey = "wardrobe",
    )
}

@Composable
internal fun PropDetailScreen(itemId: String) {
    ItemDetail(
        itemId = itemId,
        folder = "props-items",
        titleKey = "props",
        notFoundKey = "item_not_found",
        deleteTitleKey = "delete_item_title",
        backKey = "props",
    )
}

@Suppress("LongMethod") // The item's header, its stepper, its fields and its photos.
@Composable
private fun ItemDetail(
    itemId: String,
    folder: String,
    titleKey: String,
    notFoundKey: String,
    deleteTitleKey: String,
    backKey: String,
) {
    val ctx = LocalSs.current
    val navState = nav()
    val castState = cast()
    val crud = remember(ctx, folder) { ContinuityCrud(ctx.api, folder) }
    // The whole folder, so the stepper has its neighbours.
    val siblings = rememberRows(folder) { crud.list() }
    var editing by remember(itemId) { mutableStateOf(false) }
    var deleting by remember(itemId) { mutableStateOf(false) }

    Await(siblings) { items ->
        val ordered = items.sortedWith(Looks.byNatural { it.str("name") })
        val at = ordered.indexOfFirst { it.id == itemId }
        val item = ordered.getOrNull(at)
        if (item == null) {
            SsPageHeader(title = t(titleKey), actions = { Back(backKey, navState::back) })
            ZillitEmptyState(title = t(notFoundKey), message = "")
            return@Await
        }
        val reload = { siblings.reload(silent = true) }
        val photos = Looks.photosOf(item)

        SsPageHeader(
            title = t(titleKey),
            sub = item.str("character_name"),
            actions = {
                Back(backKey, navState::back)
                RowActions(
                    onEdit = { ctx.whenPost { editing = true } },
                    onDelete = { ctx.whenPost { deleting = true } },
                )
            },
        )
        ItemStepper(
            previous = ordered.getOrNull(at - 1),
            next = ordered.getOrNull(at + 1),
            onGo = { navState.swap(viewFor(folder, it.id)) },
            onlyText = t(if (folder == "garments") "only_wardrobe_item" else "only_item"),
        )

        ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ZillitText(
                    text = item.str("name").ifBlank { t("item") },
                    style = ZillitTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                item.str("category").takeIf { it.isNotBlank() }?.let { category ->
                    SsTag(
                        t(
                            if (folder == "garments") {
                                "garment_category_$category"
                            } else {
                                "asset_noun_$category"
                            },
                        ),
                    )
                }
            }
            Field(t("notes"), item.str("notes"))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                item.str("character_name").takeIf { it.isNotBlank() }?.let { SsTag(it) }
                // The service stores 0 for a cost nobody entered, which reads
                // blank — so these two always show a number, as the web does.
                MutedText("${t("unit_cost")}: ${item.double("cost")}")
                MutedText("${t("quantity")}: ${item.int("quantity").coerceAtLeast(1)}")
            }
            if (folder == "garments") {
                Field(t("size"), item.str("size"))
                item.str("condition").takeIf { it.isNotBlank() }
                    ?.let { Field(t("condition"), t("garment_condition_$it")) }
                item.str("source").takeIf { it.isNotBlank() }
                    ?.let { Field(t("source"), t("garment_source_$it")) }
            }
        }

        ZillitSectionLabel(t("photos"))
        ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
            if (photos.isEmpty()) MutedText(t("no_photo_loaded")) else PhotoStrip(photos)
            // ⚠ BOTH photo fields are written together: `photo_urls` is the
            // list and `image_url` is the cover, and [Looks.coverOf] decides
            // what the cover becomes. Writing the list alone would leave the
            // cover pointing at a photo the record no longer has.
            AddPhotoRow(
                folder = if (folder == "garments") "garments" else "props",
                onAdded = { key -> savePhotos(ctx, crud, itemId, item, photos + key, reload) },
            )
        }

        if (editing) {
            ItemForm(
                item = item,
                folder = folder,
                characters = castState.options,
                characterName = castState::characterName,
                onClose = { editing = false },
                onSaved = reload,
            )
        }
        if (deleting) {
            DeleteDialog(
                title = t(deleteTitleKey),
                name = item.str("name"),
                onClose = { deleting = false },
                // The item is gone, so the screen showing it has to go too.
                onDone = { navState.back() },
                run = { crud.remove(itemId) },
            )
        }
    }
}

/**
 * Writes the photo list AND its cover, which have to travel together.
 *
 * `photo_urls` is the list, `image_url` the cover, and [Looks.coverOf] keeps
 * the old cover while it is still in the list. Writing the list alone leaves
 * the cover naming a photo that is gone.
 */
private fun savePhotos(
    ctx: SsCtx,
    crud: ContinuityCrud,
    itemId: String,
    item: Rec,
    photos: List<String>,
    reload: () -> Unit,
) {
    ctx.launchWrite(
        { crud.update(itemId, body("photo_urls" to photos, "image_url" to Looks.coverOf(item, photos))) },
    ) { reload() }
}

private fun viewFor(folder: String, id: String): SsView =
    if (folder == "garments") SsView.WardrobeItem(id) else SsView.Prop(id)

@Composable
private fun Back(labelKey: String, onBack: () -> Unit) {
    ZillitButton(
        text = t(labelKey),
        onClick = onBack,
        variant = ButtonVariant.Secondary,
        leadingIcon = ZillitIcons.ArrowLeft,
    )
}

/** The previous and next item by name; a lone item says so instead. */
@Composable
private fun ItemStepper(previous: Rec?, next: Rec?, onlyText: String, onGo: (Rec) -> Unit) {
    if (previous == null && next == null) {
        MutedText(onlyText)
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = previous?.str("name") ?: "—",
            onClick = { previous?.let(onGo) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.ArrowLeft,
            enabled = previous != null,
        )
        ZillitButton(
            text = next?.str("name") ?: "—",
            onClick = { next?.let(onGo) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.ArrowRight,
            enabled = next != null,
        )
    }
}

/** The item's own form — the web's `GarmentModal`, in its two shapes. */
@Suppress("LongMethod") // The item's fields, and the four a garment adds.
@Composable
private fun ItemForm(
    item: Rec?,
    folder: String,
    characters: List<Pair<String, String>>,
    characterName: (String) -> String,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSs.current
    val garment = folder == "garments"
    var name by remember(item) { mutableStateOf(item?.str("name").orEmpty()) }
    var category by remember(item) { mutableStateOf(item?.str("category").orEmpty()) }
    var characterId by remember(item) { mutableStateOf(item?.str("character_id").orEmpty()) }
    var quantity by remember(item) {
        mutableStateOf(item?.int("quantity")?.coerceAtLeast(1)?.toString() ?: "1")
    }
    var cost by remember(item) {
        mutableStateOf(item?.doubleOrNull("cost")?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var size by remember(item) { mutableStateOf(item?.str("size").orEmpty()) }
    var condition by remember(item) { mutableStateOf(item?.str("condition").orEmpty()) }
    var source by remember(item) { mutableStateOf(item?.str("source").orEmpty()) }
    var notes by remember(item) { mutableStateOf(item?.str("notes").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (item == null) "new_item" else "edit"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    listOf(
                        "name" to name.trim(),
                        "category" to category,
                        "character_id" to characterId,
                        "character_name" to characterName(characterId),
                        "quantity" to (quantity.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1),
                        // 0 is what the service stores for "nobody said".
                        "cost" to (cost.trim().toDoubleOrNull() ?: 0.0),
                        "notes" to notes,
                    ) + if (garment) {
                        listOf("size" to size.trim(), "condition" to condition, "source" to source)
                    } else {
                        emptyList()
                    },
                )
                val crud = ContinuityCrud(ctx.api, folder)
                val done = ctx.write {
                    if (item == null) crud.create(payload.dropEmpty()) else crud.update(item.id, payload)
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
        width = FORM_WIDTH,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("name"))
            PickInput(
                value = category,
                options = if (garment) {
                    Looks.GARMENT_CATEGORIES.map { it to t("garment_category_$it") }
                } else {
                    PROP_CATEGORIES.map { it to t("asset_noun_$it") }
                },
                onChange = { category = it },
                label = t("category"),
                clearable = true,
            )
            PickInput(
                value = characterId,
                options = characters,
                onChange = { characterId = it },
                label = t("character"),
                clearable = true,
            )
            TextInput(quantity, { quantity = it }, t("quantity"), FormHalf, number = true)
            TextInput(cost, { cost = it }, t("unit_cost"), FormHalf, number = true)
            if (garment) {
                TextInput(size, { size = it }, t("size"), FormHalf, placeholder = t("size_placeholder"))
                PickInput(
                    value = condition,
                    options = Looks.GARMENT_CONDITIONS.map { it to t("garment_condition_$it") },
                    onChange = { condition = it },
                    label = t("condition"),
                    clearable = true,
                )
                PickInput(
                    value = source,
                    options = Looks.GARMENT_SOURCES.map { it to t("garment_source_$it") },
                    onChange = { source = it },
                    label = t("source"),
                    clearable = true,
                )
            }
            TextInput(notes, { notes = it }, t("notes"), FormWide, multiline = true)
        }
    }
}

/** A prop's kind, as the scene-assets card names them. */
private val PROP_CATEGORIES = listOf("prop", "vehicle", "animal")

private val FORM_WIDTH = 680.dp
