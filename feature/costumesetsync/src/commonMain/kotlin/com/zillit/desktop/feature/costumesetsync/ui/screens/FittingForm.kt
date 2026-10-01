package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.ui.DateTimeInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.dateTimeMs
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.fittingSendDraft
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
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

/**
 * Schedule a fitting (the web's `ScheduleFittingModal`). The fitting is created once — a failed upload
 * can be retried from the same open form without booking a second one — and what did not attach stays in
 * the picker. "Schedule & send" books it, then hands [onDone] the request to open about it.
 *
 * Closing keeps the fields (a Cancel loses nothing); they clear once the fitting is booked. The web's
 * inline character panel (cast, references, tagged pieces) is not part of this form on the desktop —
 * the character's own page carries those.
 */
@Composable
fun ScheduleFittingDialog(
    open: Boolean,
    onClose: () -> Unit,
    characters: List<Rec>,
    onCharacterAdded: () -> Unit,
    onDone: (RequestDraft?) -> Unit,
) {
    val ctx = LocalSync.current
    var characterId by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var location by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val costumes = remember { mutableStateListOf<Rec>() }
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var saving by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var createdId by remember { mutableStateOf<String?>(null) }
    // The booking's answer, so a retry that only re-sends photos still confirms.
    var booked by remember { mutableStateOf(false) }

    // Closing drops what was picked, so it can never ride along to the next fitting.
    LaunchedEffect(open) {
        if (open) {
            media = emptyList()
            createdId = null
        }
    }
    val character = characters.firstOrNull { it.id == characterId }
    val canSave = characterId.isNotBlank() && !saving

    val submit = { send: Boolean ->
        saving = true
        ctx.scope.launch {
            val whenMs = dateTimeMs(date, time) ?: ctx.now()
            var id = createdId
            if (id == null) {
                val actorId = character?.str("actor_id").orEmpty().ifBlank { character?.rec("actor")?.id.orEmpty() }
                val answer = ctx.write {
                    ctx.api.post(
                        "/fittings",
                        body("character_id" to characterId, "actor_id" to actorId, "scheduled_at" to whenMs, "location" to location.trim(), "notes" to notes.trim()),
                    )
                }
                id = answer?.rec?.id?.takeIf { it.isNotBlank() }
                if (id == null) {
                    saving = false
                    return@launch
                }
                createdId = id
                booked = true
                // The pieces go on one at a time; a piece that is refused is reported, the fitting stays booked.
                for (c in costumes.toList()) {
                    ctx.quietWrite { ctx.api.post("/fittings/$id/items", body("costume_id" to c.id)) }
                }
            }
            val failed = ctx.attachMedia(media, "FITTING", id, "REFERENCE", keep = { media = it })
            saving = false
            if (failed > 0) {
                ctx.toast(t("csync_fitting_media_failed", "n" to failed), false)
                return@launch
            }
            val draft = if (send) {
                fittingSendDraft(
                    id,
                    character?.str("name").orEmpty(),
                    character?.rec("actor")?.str("name").orEmpty(),
                    whenMs,
                    location.trim(),
                    costumes.map { "${it.str("asset_number")} ${it.str("name")}" },
                    notes,
                    wfSay,
                )
            } else {
                null
            }
            createdId = null
            booked = false
            characterId = ""
            date = ""
            time = ""
            location = ""
            notes = ""
            costumes.clear()
            media = emptyList()
            onDone(draft)
            onClose()
        }
    }

    WfFormDialog(
        open = open,
        title = t("csync_schedule_fitting"),
        onDismiss = onClose,
        actions = {
            WfSaveActions(
                onCancel = onClose,
                sendLabel = t("csync_schedule_and_send"),
                onSend = { submit(true) },
                saveLabel = t("csync_schedule"),
                onSave = { submit(false) },
                canSave = canSave,
                busy = saving,
            )
        },
    ) {
        FormGrid {
            CharacterSelect(
                value = characterId,
                onChange = { characterId = it; costumes.clear() },
                label = t("csync_field_character"),
                modifier = FormWide,
                onCreated = { onCharacterAdded() },
                rows = characters,
            )
            DateTimeInput(date, time, { date = it }, { time = it }, t("csync_field_when"))
            TextInput(location, { location = it }, t("csync_field_where"))
            Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(t("csync_pieces_to_try"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                costumes.toList().forEach { c ->
                    CostumeRow(c, onClick = { costumes.remove(c) }, end = { MutedText(t("csync_remove_lower")) })
                }
                Row {
                    ZillitButton(
                        t("csync_add_piece"),
                        onClick = { pick = true },
                        variant = ButtonVariant.Secondary,
                        size = ButtonSize.Small,
                        leadingIcon = ZillitIcons.Add,
                        enabled = !booked,
                    )
                }
            }
            TextInput(notes, { notes = it }, t("csync_field_notes"), FormWide, multiline = true)
            Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(t("csync_photos_and_video"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                MediaPicker(media, { media = it }, enabled = !saving)
            }
        }
    }

    WfCostumePicker(
        open = open && pick,
        onClose = { pick = false },
        characterId = characterId,
        onPick = { c -> if (costumes.none { it.id == c.id }) costumes.add(c) },
    )
}

/** The count line "n of m fitted" the fitting rows and page share. */
internal fun fittedLine(fitted: Int, total: Int): String = fill(t("csync_n_of_m_fitted"), "n" to fitted, "m" to total)
