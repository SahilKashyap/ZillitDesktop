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
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Looks
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MultiPickInput
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
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
 * The look form — the web's `LookModal`.
 *
 * The department is NOT a field: a look belongs to the board it was created
 * on, and the web sends `department: dept` from the screen. Hair and make-up
 * looks carry products and application notes; costume looks carry aging and a
 * wear state instead.
 *
 * ⚠ `character_id` is sent as NULL when nobody picked one, not as "". The web
 * does this explicitly (`values.character_id || null`), and it is the one
 * field on this form where "" and null differ to the service.
 */
@Suppress("LongMethod") // One look's fields, then the two shapes its department takes.
@Composable
internal fun LookForm(
    look: Rec?,
    department: String,
    presetCharacter: String,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSs.current
    val castState = cast()
    val sceneState = scenes()
    val costume = department == "costume"
    var number by remember(look) { mutableStateOf(look?.str("look_number").orEmpty()) }
    var name by remember(look) { mutableStateOf(look?.str("name").orEmpty()) }
    // A look created from a filtered board starts on that character.
    var characterId by remember(look) {
        mutableStateOf(look?.str("character_id").orEmpty().ifBlank { presetCharacter })
    }
    var status by remember(look) { mutableStateOf(look?.str("status").orEmpty().ifBlank { "planned" }) }
    var description by remember(look) { mutableStateOf(look?.str("description").orEmpty()) }
    var dayIds by remember(look) { mutableStateOf(look?.strings("story_day_ids").orEmpty()) }
    var sceneIds by remember(look) { mutableStateOf(look?.strings("scene_ids").orEmpty()) }
    var photos by remember(look) { mutableStateOf(look?.strings("reference_image_urls").orEmpty()) }
    var products by remember(look) {
        mutableStateOf(
            look?.recs("products").orEmpty().map {
                Triple(it.str("name"), it.str("brand"), it.str("shade"))
            },
        )
    }
    var applicationNotes by remember(look) { mutableStateOf(look?.str("application_notes").orEmpty()) }
    var aging by remember(look) { mutableStateOf(look?.str("aging_distressing").orEmpty()) }
    var wearState by remember(look) { mutableStateOf(look?.str("wear_state").orEmpty()) }
    var continuityNotes by remember(look) { mutableStateOf(look?.str("continuity_notes").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (look == null) "add_${department}_look" else "edit_look"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    listOf(
                        "look_number" to number.trim(),
                        "name" to name.trim(),
                        "department" to department,
                        // null, not "": the one field where the two differ here.
                        "character_id" to characterId.takeIf { it.isNotBlank() },
                        "character_name" to castState.characterName(characterId),
                        "status" to status,
                        "description" to description,
                        "story_day_ids" to dayIds,
                        "scene_ids" to sceneIds,
                        "reference_image_urls" to photos,
                        "continuity_notes" to continuityNotes,
                    ) + if (costume) {
                        listOf("aging_distressing" to aging, "wear_state" to wearState)
                    } else {
                        listOf("products" to productsJson(products), "application_notes" to applicationNotes)
                    },
                )
                val crud = ContinuityCrud(ctx.api, "looks")
                val done = ctx.write {
                    if (look == null) crud.create(payload.dropEmpty()) else crud.update(look.id, payload)
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
            TextInput(number, { number = it }, t("look_number"), FormHalf)
            TextInput(name, { name = it }, t("name"))
            PickInput(
                value = characterId,
                options = castState.options,
                onChange = { characterId = it },
                label = t("character"),
                clearable = true,
            )
            PickInput(
                value = status,
                options = Looks.STATUSES.map { it to t("look_status_$it") },
                onChange = { status = it },
                label = t("status"),
            )
            TextInput(description, { description = it }, t("description"), FormWide, multiline = true)
            MultiPickInput(
                values = dayIds,
                options = castState.daysInOrder.map { it.id to it.str("label") },
                onChange = { dayIds = it },
                label = t("story_days"),
            )
            MultiPickInput(
                values = sceneIds,
                options = sceneState.options,
                onChange = { sceneIds = it },
                label = t("scenes"),
            )
            TagInput(
                tokens = photos,
                onChange = { photos = it },
                label = t("reference_photos"),
                modifier = FormWide,
                help = t("image_url"),
            )
        }
        if (costume) {
            FormGrid {
                TextInput(aging, { aging = it }, t("aging_distressing"), FormWide, multiline = true)
                TextInput(wearState, { wearState = it }, t("wear_state"), FormWide, multiline = true)
            }
        } else {
            ZillitSectionLabel(t("products"))
            ProductList(products, onChange = { products = it })
            FormGrid {
                TextInput(
                    applicationNotes,
                    { applicationNotes = it },
                    t("application_notes"),
                    FormWide,
                    multiline = true,
                )
            }
        }
        FormGrid {
            TextInput(continuityNotes, { continuityNotes = it }, t("continuity_notes"), FormWide, multiline = true)
        }
    }
}

/** A make-up or hair product: what it is, whose it is, and which shade. */
@Composable
private fun ProductList(
    rows: List<Triple<String, String, String>>,
    onChange: (List<Triple<String, String, String>>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        rows.forEachIndexed { index, (name, brand, shade) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextInput(
                    value = name,
                    onChange = { onChange(rows.at(index, Triple(it, brand, shade))) },
                    label = null,
                    modifier = Modifier.weight(1f),
                    placeholder = t("product"),
                )
                TextInput(
                    value = brand,
                    onChange = { onChange(rows.at(index, Triple(name, it, shade))) },
                    label = null,
                    modifier = Modifier.weight(1f),
                    placeholder = t("brand"),
                )
                TextInput(
                    value = shade,
                    onChange = { onChange(rows.at(index, Triple(name, brand, it))) },
                    label = null,
                    modifier = Modifier.weight(1f),
                    placeholder = t("shade"),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = t("delete"),
                    onClick = { onChange(rows.filterIndexed { at, _ -> at != index }) },
                )
            }
        }
        ZillitButton(
            text = t("add_product"),
            onClick = { onChange(rows + Triple("", "", "")) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

private fun <T> List<T>.at(index: Int, value: T): List<T> =
    mapIndexed { at, old -> if (at == index) value else old }

/** The products, dropping the rows nobody filled in — as the web's marks are dropped. */
private fun productsJson(rows: List<Triple<String, String, String>>) = buildJsonArray {
    rows.filter { (name, brand, shade) -> name.isNotBlank() || brand.isNotBlank() || shade.isNotBlank() }
        .forEach { (name, brand, shade) ->
            add(
                buildJsonObject {
                    put("name", name.trim())
                    put("brand", brand.trim())
                    put("shade", shade.trim())
                },
            )
        }
}

private val FORM_WIDTH = 760.dp
