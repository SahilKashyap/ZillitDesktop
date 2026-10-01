package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.Clear
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.MediaPicker
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.RecInput
import com.zillit.desktop.feature.costumesetsync.ui.StackedPick
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.attachMedia
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.numOrNull
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

/** The fields of the costume form, as text, the way the web's `f` state holds them. */
private data class CostumeDraft(
    val assetNumber: String = "",
    val name: String = "",
    val category: String = "CLOTHING",
    val type: String = "",
    val color: String = "",
    val brand: String = "",
    val size: String = "",
    val fabric: String = "",
    val quantity: String = "1",
    val source: String = "PURCHASED",
    val purchaseCost: String = "",
    val rentalPerDay: String = "",
    val vendorId: String = "",
    val characterId: String = "",
    val location: String = "Warehouse",
    val care: String = "",
    val notes: String = "",
)

private fun Rec.toDraft() = CostumeDraft(
    assetNumber = str("asset_number"),
    name = str("name"),
    category = str("category").ifBlank { "CLOTHING" },
    type = str("type"),
    color = str("color"),
    brand = str("brand"),
    size = str("size"),
    fabric = str("fabric"),
    quantity = (long("quantity").takeIf { it > 0 } ?: 1L).toString(),
    source = str("source").ifBlank { "PURCHASED" },
    purchaseCost = if (has("purchase_cost")) str("purchase_cost") else "",
    rentalPerDay = if (has("rental_cost_per_day")) str("rental_cost_per_day") else "",
    vendorId = str("vendor_id"),
    characterId = str("character_id"),
    location = str("location").ifBlank { "Warehouse" },
    care = str("care_instructions"),
    notes = str("notes"),
)

/** A blank optional text is "not set": an explicit null so clearing it on Edit clears it. */
private fun String.orClear(): Any = ifBlank { null } ?: Clear

/** A money field: finance roles send the number (or a clear), everyone else sends nothing. */
private fun moneyBody(isFinance: Boolean, text: String): Any? = if (isFinance) numOrNull(text) ?: Clear else null

/**
 * The request body, as the web builds it: a blank optional field is "not set", sent as an
 * explicit null so clearing one on Edit clears it rather than storing an empty string. Money
 * is for finance roles only (the service redacts it for everyone else).
 */
private fun CostumeDraft.toBody(isFinance: Boolean) = body(
    "asset_number" to assetNumber.trim(),
    "name" to name,
    "category" to category,
    "type" to type.trim().orClear(),
    "color" to color.trim().orClear(),
    "brand" to brand.trim().orClear(),
    "size" to size.trim().orClear(),
    "fabric" to fabric.trim().orClear(),
    "quantity" to (numOrNull(quantity)?.toLong()?.takeIf { it != 0L } ?: 1L),
    "source" to source,
    "purchase_cost" to moneyBody(isFinance, purchaseCost),
    "rental_cost_per_day" to moneyBody(isFinance, rentalPerDay),
    "vendor_id" to vendorId.orClear(),
    "character_id" to characterId.orClear(),
    "location" to location,
    "care_instructions" to care.trim().orClear(),
    "notes" to notes.trim().orClear(),
)

/**
 * "New costume piece" / "Edit CST-000123" — the reference app's one costume
 * form, opened from the Costumes list, the Dashboard's + Costume, a
 * character's "Add piece" and a costume's Edit.
 *
 * Type and Location each offer the service's list plus a box to type anything else.
 * Money fields show for finance roles only. Photos picked here are attached once the piece
 * exists; if one fails, pressing Save again retries the failed files against the SAME piece
 * rather than adding a second one. The web's "+ New character / vendor" inside the pickers is
 * not ported: pick from the existing ones.
 */
@Composable
fun CostumeFormDialog(
    open: Boolean,
    onClose: () -> Unit,
    initial: Rec? = null,
    defaultCharacterId: String = "",
    onSaved: () -> Unit = {},
    onCreated: (Rec) -> Unit = {},
) {
    // Mounted only while open: each opening starts from a clean draft and loads its pick lists then.
    if (open) CostumeFormContent(onClose, initial, defaultCharacterId, onSaved, onCreated)
}

@Composable
private fun CostumeFormContent(
    onClose: () -> Unit,
    initial: Rec?,
    defaultCharacterId: String,
    onSaved: () -> Unit,
    onCreated: (Rec) -> Unit,
) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf(initial?.toDraft() ?: CostumeDraft(characterId = defaultCharacterId)) }
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var saving by remember { mutableStateOf(false) }
    var createdId by remember { mutableStateOf("") }
    val characters = rememberResource { api.get("/characters").mapRows() }
    val vendors = rememberResource { api.get("/vendors").mapRows() }

    val save: () -> Unit = {
        scope.launch {
            saving = true
            val id = initial?.id ?: createdId
            val request = draft.toBody(ctx.isFinance)
            val answer = ctx.write {
                if (id.isNotBlank()) ctx.api.patch("/costumes/$id", request) else ctx.api.post("/costumes", request)
            }
            if (answer == null) {
                saving = false
                return@launch
            }
            val costume = answer.rec ?: Rec.Empty
            if (initial == null && costume.id.isNotBlank()) createdId = costume.id
            val failed = ctx.attachMedia(media, "COSTUME", costume.id.ifBlank { id }, keep = { media = it })
            saving = false
            if (failed > 0) {
                // The form stays open with the files that failed, so Save retries just those.
                ctx.toast(t("csync_costume_media_failed", "n" to failed), false)
                return@launch
            }
            onSaved()
            onCreated(costume)
            onClose()
        }
        Unit
    }

    FormDialog(
        open = true,
        title =
            if (initial != null) "${t("csync_edit")} ${initial.str("asset_number")}" else t("csync_new_costume_piece"),
        onDismiss = onClose,
        confirmLabel = if (initial != null) t("csync_save") else t("csync_add_to_inventory"),
        onConfirm = save,
        confirmEnabled = draft.name.isNotBlank(),
        busy = saving,
        width = FORM_WIDTH.dp,
    ) {
        CostumeFields(draft, { draft = it }, initial != null, characters.value.orEmpty(), vendors.value.orEmpty())
        ZillitText(
            t("csync_photos_and_video"),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        MediaPicker(media, { media = it }, enabled = !saving)
    }
}

// The web's modal is 900 wide with two columns: 16 of padding each side leaves 868, so two 428 cells and the 12
// between.
private val CELL = Modifier.width(428.dp)
private val WIDE = Modifier.width(868.dp)
private const val FORM_WIDTH = 900

@Composable
private fun CostumeFields(
    draft: CostumeDraft,
    onChange: (CostumeDraft) -> Unit,
    editing: Boolean,
    characters: List<Rec>,
    vendors: List<Rec>,
) {
    FormGrid {
        CostumeIdentityFields(draft, onChange, editing)
        CostumeStockFields(draft, onChange, characters, vendors)
        CostumeMoneyFields(draft, onChange)
    }
}

@Composable
private fun CostumeIdentityFields(draft: CostumeDraft, onChange: (CostumeDraft) -> Unit, editing: Boolean) {
    val ctx = LocalSync.current
    val types = ctx.meta?.rec("costume_types")?.strings(draft.category).orEmpty()
    TextInput(
        draft.assetNumber,
        { onChange(draft.copy(assetNumber = it.uppercase())) },
        t("csync_asset_number"),
        CELL,
        help = if (editing) null else t("csync_asset_number_hint"),
    )
    TextInput(draft.name, { onChange(draft.copy(name = it)) }, t("csync_field_name"), CELL)
    EnumInput(
        draft.category,
        ctx.metaList("costume_categories"),
        { onChange(draft.copy(category = it.ifBlank { draft.category }, type = "")) },
        t("csync_field_category"),
        CELL,
    )
    // Type offers the service's list; anything not on it is typed in the box beneath, inside the same field.
    StackedPick(
        draft.type.takeIf { it in types }.orEmpty(), types.map { it to it }, { onChange(draft.copy(type = it)) },
        draft.type, { onChange(draft.copy(type = it)) }, t("csync_field_type"), CELL, placeholder = "—",
    )
    TextInput(draft.color, { onChange(draft.copy(color = it)) }, t("csync_field_colour"), CELL)
    TextInput(draft.size, { onChange(draft.copy(size = it)) }, t("csync_field_size"), CELL)
    TextInput(draft.brand, { onChange(draft.copy(brand = it)) }, t("csync_field_brand"), CELL)
    TextInput(draft.fabric, { onChange(draft.copy(fabric = it)) }, t("csync_field_fabric"), CELL)
}

@Composable
private fun CostumeStockFields(
    draft: CostumeDraft,
    onChange: (CostumeDraft) -> Unit,
    characters: List<Rec>,
    vendors: List<Rec>,
) {
    val ctx = LocalSync.current
    val locations = ctx.metaList("standard_locations")
    RecInput(
        draft.characterId,
        characters,
        { onChange(draft.copy(characterId = it)) },
        t("csync_field_character"),
        CELL,
        placeholder = t("csync_unassigned_dash"),
    )
    StackedPick(
        draft.location.takeIf { it in locations }.orEmpty(),
        locations.map { it to it },
        { onChange(draft.copy(location = it.ifBlank { draft.location })) },
        draft.location, { onChange(draft.copy(location = it)) }, t("csync_field_location"), CELL,
    )
    EnumInput(
        draft.source,
        ctx.metaList("costume_sources"),
        { onChange(draft.copy(source = it.ifBlank { draft.source })) },
        t("csync_field_source"),
        CELL,
    )
    RecInput(
        draft.vendorId,
        vendors,
        { onChange(draft.copy(vendorId = it)) },
        t("csync_field_vendor"),
        CELL,
        placeholder = "—",
    )
}

@Composable
private fun CostumeMoneyFields(draft: CostumeDraft, onChange: (CostumeDraft) -> Unit) {
    val ctx = LocalSync.current
    val inCurrency = ctx.currency.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    if (ctx.isFinance) {
        TextInput(
            draft.purchaseCost,
            { onChange(draft.copy(purchaseCost = it)) },
            "${t("csync_field_purchase_cost")}$inCurrency",
            CELL,
            number = true,
        )
        TextInput(
            draft.rentalPerDay,
            { onChange(draft.copy(rentalPerDay = it)) },
            "${t("csync_rental_per_day")}$inCurrency",
            CELL,
            number = true,
        )
    }
    TextInput(
        draft.quantity,
        { onChange(draft.copy(quantity = it)) },
        t("csync_field_quantity"),
        CELL,
        number = true,
    )
    TextInput(draft.care, { onChange(draft.copy(care = it)) }, t("csync_field_care"), CELL)
    TextInput(draft.notes, { onChange(draft.copy(notes = it)) }, t("csync_field_notes"), WIDE, multiline = true)
}
