package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.cleaningSendDraft
import com.zillit.desktop.feature.costumesetsync.domain.dateTimeMs
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.MediaPicker
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.attachMedia
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.numOrNull
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch

private const val DEFAULT_CLEANING_TYPE = "SPOT_CLEANING"
private const val DEFAULT_PRIORITY = "NORMAL"

/**
 * Request cleaning (the web's `CleaningRequestModal`). The ticket is filed once — a failed upload can be
 * retried from the same open form without a second ticket — and what did not attach stays in the picker.
 * "Request & send" files it, then hands [onDone] the request to open about it.
 *
 * Closing keeps the fields (a Cancel loses nothing); after a save only the costume and problem clear for
 * the next piece.
 */
@Composable
fun CleaningRequestDialog(open: Boolean, onClose: () -> Unit, onDone: (RequestDraft?) -> Unit) {
    val ctx = LocalSync.current
    var costume by remember { mutableStateOf<Rec?>(null) }
    var problem by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(DEFAULT_CLEANING_TYPE) }
    var priority by remember { mutableStateOf(DEFAULT_PRIORITY) }
    var sceneId by remember { mutableStateOf("") }
    var take by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var saving by remember { mutableStateOf(false) }
    var pick by remember { mutableStateOf(false) }
    var createdId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(open) {
        if (open) {
            media = emptyList()
            createdId = null
        }
    }
    val chosen = costume
    val canSave = chosen != null && problem.isNotBlank() && !saving

    val submit = { send: Boolean ->
        saving = true
        ctx.scope.launch {
            var id = createdId
            val neededBy = dateTimeMs(date, time)
            if (id == null && chosen != null) {
                val answer = ctx.write {
                    ctx.api.post(
                        "/cleaning",
                        body(
                            "costume_id" to chosen.id,
                            "problem" to problem.trim(),
                            "cleaning_type" to type,
                            "priority" to priority,
                            "scene_id" to sceneId,
                            "take_number" to numOrNull(take)?.toLong(),
                            "expected_ready_at" to neededBy,
                            "notes" to notes.trim(),
                        ),
                    )
                }
                id = answer?.rec?.id?.takeIf { it.isNotBlank() }
                createdId = id
            }
            if (id == null || chosen == null) {
                saving = false
                return@launch
            }
            val failed = ctx.attachMedia(media, "CLEANING", id, "STAIN", keep = { media = it })
            saving = false
            if (failed > 0) {
                ctx.toast(t("csync_cleaning_media_failed", "n" to failed), false)
                return@launch
            }
            createdId = null
            val draft = if (send) {
                cleaningSendDraft(
                    id,
                    "${chosen.str("asset_number")} ${chosen.str("name")}",
                    problem.trim(),
                    type,
                    priority,
                    neededBy,
                    notes,
                    wfSay,
                    ::tEnum,
                )
            } else {
                null
            }
            costume = null
            problem = ""
            onDone(draft)
            onClose()
        }
    }

    WfFormDialog(
        open = open,
        title = t("csync_cleaning_request"),
        onDismiss = onClose,
        actions = {
            WfSaveActions(
                onCancel = onClose,
                sendLabel = t("csync_request_and_send"),
                onSend = { submit(true) },
                saveLabel = t("csync_request"),
                onSave = { submit(false) },
                canSave = canSave,
                busy = saving,
            )
        },
    ) {
        FormGrid {
            WfCostumeField(costume, onOpen = { pick = true }, locked = createdId != null)
            TextInput(problem, { problem = it }, t("csync_field_problem"), FormWide)
            EnumInput(type, ctx.metaList("cleaning_types"), { type = it }, t("csync_field_cleaning_type"))
            EnumInput(priority, ctx.metaList("priorities"), { priority = it }, t("csync_field_priority"))
            SceneSelect(sceneId, { sceneId = it }, t("csync_field_scene"))
            TextInput(take, { take = it.filter(Char::isDigit) }, t("csync_field_take"), number = true)
            WfDateTimeInput(date, time, { date = it }, { time = it }, t("csync_field_needed_by"))
            Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(t("csync_photos_and_video"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                MediaPicker(media, { media = it }, enabled = !saving, help = t("csync_shoot_stain_hint"))
            }
            TextInput(notes, { notes = it }, t("csync_field_notes"), FormWide, multiline = true)
        }
    }
    WfCostumePicker(
        open = open && pick,
        onClose = { pick = false },
        exclude = { it.str("status") == "CLEANING" },
        onPick = { costume = it },
    )
}
