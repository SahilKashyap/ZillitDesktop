package com.zillit.desktop.feature.costumesetsync.ui.screens

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
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
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
 * A new numbered change (look) for [character]: name, wear notes, its pieces, and photos or video of it
 * (the web's `NewChangeModal`). The change is created once: a piece or file that fails is kept in the open
 * dialog, and pressing Create again retries just those without a second change. Closing drops what was
 * picked so it can never ride along to the next change. [onCreated] fires after every attempt so the
 * caller's list catches up.
 */
@Suppress("LongMethod")
@Composable
fun NewChangeDialog(open: Boolean, onClose: () -> Unit, character: Rec, onCreated: () -> Unit = {}) {
    val ctx = LocalSync.current
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val pieces = remember { mutableStateListOf<Rec>() }
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var changeId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var stuck by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(open) {
        if (open) {
            media = emptyList()
            changeId = ""
            stuck = false
        }
    }
    val save = {
        busy = true
        ctx.scope.launch {
            var id = changeId
            if (id.isEmpty()) {
                val answer = ctx.write {
                    ctx.api.post(
                        "/changes",
                        body("character_id" to character.id, "name" to name.trim(), "description" to description)
                    )
                }
                id = answer?.rec?.id.orEmpty()
                if (id.isEmpty()) {
                    busy = false
                    return@launch
                }
                changeId = id
            }
            val left = pieces.toList().filterNot { c ->
                ctx.quietWrite { ctx.api.post("/changes/$id/items", body("costume_id" to c.id)) }
            }
            pieces.clear()
            pieces.addAll(left)
            val failed = ctx.attachMedia(media, "CHANGE", id, "REFERENCE", keep = { media = it })
            busy = false
            stuck = left.isNotEmpty()
            onCreated()
            if (failed > 0) ctx.toast(t("csync_change_media_failed", "n" to failed), false)
            if (failed > 0 || left.isNotEmpty()) return@launch
            changeId = ""
            name = ""
            description = ""
            onClose()
        }
        Unit
    }
    FormDialog(
        open = open,
        title = t("csync_new_change_for", "name" to character.str("name")),
        onDismiss = onClose,
        confirmLabel = t("csync_create"),
        onConfirm = { save() },
        confirmEnabled = name.isNotBlank(),
        busy = busy,
    ) {
        TextInput(name, { name = it }, t("csync_field_name"), FormWide, help = t("csync_change_name_hint"))
        TextInput(description, { description = it }, t("csync_change_description"), FormWide, multiline = true)
        Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                t("csync_pieces"),
                style = ZillitTheme.typography.label,
                color = ZillitTheme.colors.textSecondary
            )
            pieces.toList().forEach { c ->
                CostumeRow(c, onClick = { pieces.remove(c) }, end = { MutedText(t("csync_remove_lower")) })
            }
            if (stuck && pieces.isNotEmpty()) MutedText(
                t("csync_change_pieces_failed", "n" to pieces.size),
                maxLines = 2
            )
            Row {
                ZillitButton(
                    t("csync_add_piece"),
                    onClick = { picking = true },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Add
                )
            }
        }
        Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                t("csync_photos_and_video"),
                style = ZillitTheme.typography.label,
                color = ZillitTheme.colors.textSecondary
            )
            MediaPicker(media, { media = it }, enabled = !busy, help = t("csync_shoot_look_hint"))
        }
    }
    // A look opens the picker on this character's own pieces.
    CostumePickerDialog(
        open = open && picking,
        onClose = { picking = false },
        onPick = { c -> if (pieces.none { it.id == c.id }) pieces.add(c) },
        characterId = character.id,
        exclude = { c -> pieces.any { it.id == c.id } },
    )
}
