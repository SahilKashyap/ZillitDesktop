package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.dateTimeMs
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.MediaPicker
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.attachMedia
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private val Half = Modifier.width(290.dp)

/** A short yes/no question in a dialog. */
@Composable
internal fun CastConfirm(open: Boolean, title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    FormDialog(open, title, onDismiss, confirmLabel, onConfirm, danger = true, width = 440.dp) { MutedText(text, maxLines = 5) }
}

/**
 * Edit character: the fields that apply to every scene (the web's edit modal). Clearing the actor, age or
 * cast number sends an explicit null, and the text fields go as typed so an emptied description is cleared.
 */
@Composable
internal fun EditCharacterDialog(open: Boolean, onClose: () -> Unit, character: Rec, actors: List<Rec>, onSaved: () -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf(character.str("name")) }
    var type by remember(open) { mutableStateOf(character.str("type")) }
    var age by remember(open) { mutableStateOf(character.str("age")) }
    var cast by remember(open) { mutableStateOf(character.str("cast_number")) }
    var actorId by remember(open) { mutableStateOf(character.str("actor_id")) }
    var description by remember(open) { mutableStateOf(character.str("description")) }
    var notes by remember(open) { mutableStateOf(character.str("notes")) }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_edit_character"),
        subtitle = t("csync_applies_all_scenes"),
        onDismiss = onClose,
        confirmLabel = t("csync_save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val answer = ctx.write {
                    ctx.api.patch(
                        "/characters/${character.id}",
                        castBody(
                            "name" to name.trim(),
                            "type" to type,
                            "description" to description,
                            "notes" to notes,
                            "actor_id" to actorId.ifEmpty { null },
                            "age" to age.trim().toDoubleOrNull(),
                            "cast_number" to cast.trim().toDoubleOrNull(),
                        ),
                    )
                }
                saving = false
                if (answer != null) {
                    onSaved()
                    onClose()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), FormWide)
            EnumInput(type, ctx.metaList("character_types"), { type = it }, t("csync_field_type"), Half)
            TextInput(age, { age = it }, t("csync_field_age"), Half, number = true)
            TextInput(cast, { cast = it }, t("csync_field_cast_number"), Half, number = true)
            ActorSelect(actorId, { actorId = it }, t("csync_field_actor"), actors.map { it.id to it.str("name") }, FormWide)
            TextInput(description, { description = it }, t("csync_field_description"), FormWide, multiline = true)
            TextInput(notes, { notes = it }, t("csync_field_notes"), FormWide, multiline = true)
        }
    }
}

/** One "More details" row: a title and free text. [index] −1 adds; otherwise the row being edited. */
@Composable
internal fun DetailRowDialog(open: Boolean, onClose: () -> Unit, initial: Rec?, onSave: (label: String, value: String) -> Unit, busy: Boolean) {
    var label by remember(open) { mutableStateOf(initial?.str("label").orEmpty()) }
    var value by remember(open) { mutableStateOf(initial?.str("value").orEmpty()) }
    FormDialog(
        open = open,
        title = if (initial == null) t("csync_add_detail") else t("csync_edit_detail"),
        onDismiss = onClose,
        confirmLabel = t("csync_save"),
        onConfirm = { onSave(label.trim(), value.trim()) },
        confirmEnabled = label.isNotBlank() && value.isNotBlank(),
        busy = busy,
    ) {
        TextInput(label, { label = it }, t("csync_field_title"), FormWide, help = t("csync_detail_title_hint"))
        TextInput(value, { value = it }, t("csync_field_description"), FormWide, multiline = true)
    }
}

/**
 * Schedule a fitting for this character (the web's schedule modal on the character page). The fitting is
 * created once, so a failed upload or a refused piece is retried from the same open dialog without booking
 * a second one; what did not attach stays listed. Closing drops the pick and the half-made record.
 */
@Composable
internal fun CharacterFittingDialog(open: Boolean, onClose: () -> Unit, character: Rec, onDone: () -> Unit) {
    val ctx = LocalSync.current
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val pieces = remember { mutableStateListOf<Rec>() }
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var createdId by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var stuck by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            media = emptyList()
            createdId = ""
            stuck = false
        }
    }
    val submit = {
        saving = true
        ctx.scope.launch {
            var id = createdId
            if (id.isEmpty()) {
                val answer = ctx.write {
                    ctx.api.post(
                        "/fittings",
                        body(
                            "character_id" to character.id,
                            "actor_id" to character.str("actor_id"),
                            "scheduled_at" to (dateTimeMs(date, time) ?: ctx.now()),
                            "location" to location.trim(),
                            "notes" to notes.trim(),
                        ),
                    )
                }
                id = answer?.rec?.id.orEmpty()
                if (id.isEmpty()) {
                    saving = false
                    return@launch
                }
                createdId = id
            }
            val left = pieces.toList().filterNot { c -> ctx.quietWrite { ctx.api.post("/fittings/$id/items", body("costume_id" to c.id)) } }
            pieces.clear()
            pieces.addAll(left)
            val failed = ctx.attachMedia(media, "FITTING", id, "REFERENCE", keep = { media = it })
            saving = false
            stuck = left.isNotEmpty()
            onDone()
            if (failed > 0) ctx.toast(t("csync_fitting_media_failed", "n" to failed), false)
            if (failed > 0 || left.isNotEmpty()) return@launch
            createdId = ""
            date = ""
            time = ""
            location = ""
            notes = ""
            onClose()
        }
        Unit
    }
    FormDialog(
        open = open,
        title = t("csync_schedule_fitting_for", "name" to character.str("name")),
        onDismiss = onClose,
        confirmLabel = t("csync_schedule"),
        onConfirm = { submit() },
        busy = saving,
    ) {
        FormGrid {
            WfDateTimeInput(date, time, { date = it }, { time = it }, t("csync_when"))
            TextInput(location, { location = it }, t("csync_where"), FormWide)
            Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(t("csync_pieces_to_try"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                pieces.toList().forEach { c -> CostumeRow(c, onClick = { pieces.remove(c) }, end = { MutedText(t("csync_remove_lower")) }) }
                if (stuck && pieces.isNotEmpty()) MutedText(t("csync_fitting_pieces_failed", "n" to pieces.size), maxLines = 2)
                Row {
                    ZillitButton(t("csync_add_piece"), onClick = { picking = true }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
                }
            }
            TextInput(notes, { notes = it }, t("csync_field_notes"), FormWide, multiline = true)
            Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(t("csync_photos_and_video"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                MediaPicker(media, { media = it }, enabled = !saving)
            }
        }
    }
    CostumePickerDialog(
        open = open && picking,
        onClose = { picking = false },
        onPick = { c -> if (pieces.none { it.id == c.id }) pieces.add(c) },
        characterId = character.id,
        exclude = { c -> pieces.any { it.id == c.id } },
    )
}
