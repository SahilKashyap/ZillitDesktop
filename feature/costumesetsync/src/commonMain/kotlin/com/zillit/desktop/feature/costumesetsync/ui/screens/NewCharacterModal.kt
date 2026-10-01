package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.Clear
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private const val DEFAULT_CHARACTER_TYPE = "SUPPORTING"
private val Half = Modifier.width(290.dp)
private val Full = Modifier.width(592.dp)

/** A cast number typed in a form: blank is "not set"; anything but whole digits is refused. */
internal fun castNumberOrNull(text: String): Long? = text.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()

internal fun castNumberInvalid(text: String): Boolean = text.isNotBlank() && !text.trim().all { it.isDigit() }

/**
 * Just enough to put a missing character on the list, from inside whatever
 * form needed them — the rest is filled in on their own page (the web's
 * `NewCharacterModal`).
 */
@Composable
fun NewCharacterDialog(open: Boolean, onClose: () -> Unit, onCreated: (Rec) -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf("") }
    var type by remember(open) { mutableStateOf(DEFAULT_CHARACTER_TYPE) }
    var cast by remember(open) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val bad = castNumberInvalid(cast)
    FormDialog(
        open = open,
        title = t("csync_new_character"),
        onDismiss = onClose,
        confirmLabel = t("csync_add"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val answer = ctx.write {
                    ctx.api.post(
                        "/characters",
                        body("name" to name.trim(), "type" to type, "cast_number" to (castNumberOrNull(cast) ?: Clear)),
                    )
                }
                saving = false
                if (answer != null) {
                    answer.rec?.let(onCreated)
                    onClose()
                }
            }
        },
        confirmEnabled = name.isNotBlank() && !bad,
        busy = saving,
        width = 520.dp,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), Full)
            EnumInput(type, ctx.metaList("character_types"), { type = it }, t("csync_field_type"), Half)
            TextInput(cast, { cast = it }, t("csync_field_cast_number"), Half, help = t("csync_field_cast_number_hint"), error = if (bad) t("csync_field_cast_number_hint") else null)
        }
    }
}

/** The actor form's "+ Add character": the full new-character fields, ticked for the actor on save. */
@Composable
fun AddCharacterForActor(open: Boolean, onClose: () -> Unit, onCreated: (Rec) -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf("") }
    var type by remember(open) { mutableStateOf(DEFAULT_CHARACTER_TYPE) }
    var age by remember(open) { mutableStateOf("") }
    var cast by remember(open) { mutableStateOf("") }
    var description by remember(open) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_new_character"),
        onDismiss = onClose,
        confirmLabel = t("csync_add_and_tick"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val answer = ctx.write {
                    ctx.api.post(
                        "/characters",
                        body(
                            "name" to name.trim(),
                            "type" to type,
                            "age" to (age.trim().toLongOrNull() ?: Clear),
                            "cast_number" to (castNumberOrNull(cast) ?: Clear),
                            "description" to description,
                        ),
                    )
                }
                saving = false
                if (answer != null) {
                    answer.rec?.let(onCreated)
                    onClose()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
        width = 520.dp,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), Full)
            EnumInput(type, ctx.metaList("character_types"), { type = it }, t("csync_field_type"), Half)
            TextInput(age, { age = it }, t("csync_field_age"), Half, number = true)
            TextInput(cast, { cast = it }, t("csync_field_cast_number"), Full, help = t("csync_field_cast_number_hint"))
            TextInput(description, { description = it }, t("csync_field_description"), Full, multiline = true)
        }
    }
}
