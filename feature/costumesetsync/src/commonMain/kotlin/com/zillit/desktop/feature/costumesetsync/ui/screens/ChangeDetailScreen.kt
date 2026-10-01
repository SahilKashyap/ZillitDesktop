package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MonoText
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReferenceGrid
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The look photos' kinds, as the reference offers them. */
private val LOOK_PHOTO_KINDS = listOf("FRONT", "SIDE", "BACK", "CLOSEUP", "DETAIL", "REFERENCE", "DOCUMENT")

/** Wear notes being typed for one piece: the piece's costume id and the text so far. */
private data class WearEdit(val costumeId: String, val text: String)

/**
 * One look (a "change"): the pieces a character wears in it — each with the wear
 * notes continuity reads ("sleeves rolled, top button open") — its photos and
 * references, and the scenes it is worn in (the web's `ChangeDetailScreen`).
 */
@Composable
fun ChangeDetailScreen(id: String) {
    val data = rememberResource(id) {
        when (val res = api.get("/changes/$id")) {
            is ZillitResult.Success -> ZillitResult.Success(res.data.rec)
            is ZillitResult.Failure -> ZillitResult.Success<Rec?>(null)
        }
    }
    SocketRefresh(SyncEvents.Change, predicate = { it.str("entity_id") == id }) { data.reload(silent = true) }
    Await(data) { change ->
        if (change == null) EmptyState(t("csync_change_not_found")) else ChangeBody(change) { data.reload(silent = true) }
    }
}

@Composable
private fun ChangeBody(ch: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    var pickOpen by remember { mutableStateOf(false) }
    var editOpen by remember { mutableStateOf(false) }
    var wear by remember { mutableStateOf<WearEdit?>(null) }
    var discard by remember { mutableStateOf<Confirm?>(null) }
    val items = ch.recs("items")
    val character = ch.rec("character")

    // Wear notes typed and not saved hold a move to another page, as the reference: the guard asks, discarding lets the next click through.
    val unsaved = wear?.let { w -> w.text != items.firstOrNull { it.str("costume_id") == w.costumeId }?.str("wear_notes").orEmpty() } == true
    var leaveAsk by remember { mutableStateOf(false) }
    DisposableEffect(unsaved) {
        if (unsaved) {
            ctx.nav.leaveGuard = {
                leaveAsk = true
                false
            }
        }
        onDispose { ctx.nav.leaveGuard = null }
    }

    PageHead(
        title = "${t("csync_change")} #${ch.str("change_number")} · ${ch.str("name")}",
        crumbs = "${t("csync_nav_characters")} / ${character?.str("name").orEmpty()} / ${t("csync_change")} #${ch.str("change_number")}",
        sub = listOf(
            character?.str("name").orEmpty() + character?.rec("actor")?.str("name")?.takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty(),
            ch.str("description"),
        ).filter { it.isNotEmpty() }.joinToString(" · "),
        actions = {
            if (ctx.canPost) ZillitButton(t("csync_edit"), onClick = { editOpen = true }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Edit)
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1.4f)) {
            PiecesCard(ch, items, wear, { wear = it }, { discard = it }, { pickOpen = true }, reload)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReferenceGrid(entityType = "CHANGE", entityId = ch.id, title = t("csync_look_photos"), kinds = LOOK_PHOTO_KINDS)
            UsedInScenes(ch)
            ch.str("notes").takeIf { it.isNotEmpty() }?.let { SectionCard(title = t("csync_field_notes")) { ZillitText(it) } }
        }
    }
    // Opens on this character's own pieces, as the reference.
    CostumePickerDialog(
        open = pickOpen,
        onClose = { pickOpen = false },
        characterId = ch.str("character_id"),
        exclude = { c -> items.any { it.str("costume_id") == c.id } },
        onPick = { c -> ctx.launchWrite({ ctx.api.post("/changes/${ch.id}/items", body("costume_id" to c.id)) }) { reload() } },
    )
    ChangeEditDialog(editOpen, ch, ctx, { editOpen = false }, reload)
    ConfirmDialog(discard) { discard = null }
    ConfirmDialog(
        if (leaveAsk) Confirm(t("csync_discard_changes_title"), t("csync_leave_unsaved_body"), t("csync_discard"), danger = true) { wear = null } else null,
    ) { leaveAsk = false }
}

@Composable
private fun PiecesCard(
    ch: Rec,
    items: List<Rec>,
    wear: WearEdit?,
    setWear: (WearEdit?) -> Unit,
    ask: (Confirm?) -> Unit,
    openPicker: () -> Unit,
    reload: () -> Unit,
) {
    val ctx = LocalSync.current
    SectionCard(
        title = "${t("csync_pieces")} (${items.size})",
        flush = items.isNotEmpty(),
        actions = {
            if (ctx.canPost) ZillitButton("+ ${t("csync_add_piece")}", onClick = openPicker, size = ButtonSize.Small, variant = ButtonVariant.Secondary)
        },
    ) {
        if (items.isEmpty()) EmptyState(t("csync_no_pieces_yet"), t("csync_no_pieces_yet_hint"))
        items.forEach { PieceRow(ch, it, wear, setWear, ask, reload) }
    }
}

@Composable
private fun PieceRow(ch: Rec, piece: Rec, wear: WearEdit?, setWear: (WearEdit?) -> Unit, ask: (Confirm?) -> Unit, reload: () -> Unit) {
    val ctx = LocalSync.current
    val costume = piece.rec("costume") ?: Rec.Empty
    val costumeId = piece.str("costume_id")
    val editing = wear?.takeIf { w -> w.costumeId == costumeId }
    val detail = listOf(
        costume.str("type"), costume.str("color"),
        costume.str("size").takeIf { s -> s.isNotEmpty() }?.let { s -> "${t("csync_size")} $s" }.orEmpty(), costume.str("location"),
    ).filter { s -> s.isNotEmpty() }.joinToString(" · ")
    val saveWear = { text: String ->
        ctx.launchWrite({ ctx.api.post("/changes/${ch.id}/items", body("costume_id" to costumeId, "wear_notes" to text)) }) {
            setWear(null)
            reload()
        }
    }
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.lg, vertical = ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.clickable { ctx.nav.go("costumes/$costumeId") }, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    MonoText(costume.str("asset_number"))
                    RowTitle(costume.str("name"), Modifier.weight(1f, fill = false))
                }
                if (detail.isNotEmpty()) MutedText(detail)
                when {
                    editing != null -> WearEditor(editing, piece, setWear, ask, saveWear)
                    ctx.canPost -> ZillitText(
                        if (piece.str("wear_notes").isNotEmpty()) "✎ ${piece.str("wear_notes")}" else "+ ${t("csync_add_wear_notes")}",
                        Modifier.clickable { setWear(WearEdit(costumeId, piece.str("wear_notes"))) },
                        style = ZillitTheme.typography.bodySmall,
                        color = ZillitTheme.colors.textSecondary,
                    )
                    else -> MutedText(piece.str("wear_notes"))
                }
            }
            StatusBadge(costume.str("status"), tEnum(costume.str("status")))
            if (ctx.canPost) {
                ZillitButton(
                    "", onClick = { ctx.launchWrite({ ctx.api.delete("/changes/${ch.id}/items/$costumeId") }) { reload() } },
                    variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Close,
                )
            }
        }
        ZillitDivider()
    }
}


@Composable
private fun WearEditor(editing: WearEdit, piece: Rec, setWear: (WearEdit?) -> Unit, ask: (Confirm?) -> Unit, save: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        ZillitTextField(
            value = editing.text,
            onValueChange = { setWear(editing.copy(text = it)) },
            modifier = Modifier.weight(1f),
            placeholder = t("csync_wear_notes_placeholder"),
            onImeAction = { save(editing.text) },
        )
        ZillitButton(t("csync_save"), onClick = { save(editing.text) }, size = ButtonSize.Small)
        ZillitButton(
            t("csync_cancel"),
            onClick = {
                // Cancel keeps typed wear notes unless the user agrees to lose them.
                if (editing.text == piece.str("wear_notes")) {
                    setWear(null)
                } else {
                    ask(Confirm(t("csync_discard_changes_title"), t("csync_discard_changes_body"), t("csync_discard"), danger = true) { setWear(null) })
                }
            },
            size = ButtonSize.Small,
            variant = ButtonVariant.Secondary,
        )
    }
}

@Composable
private fun UsedInScenes(ch: Rec) {
    val ctx = LocalSync.current
    val scenes = ch.recs("scene_characters")
    SectionCard(title = t("csync_used_in_scenes"), flush = scenes.isNotEmpty()) {
        if (scenes.isEmpty()) MutedText(t("csync_not_in_any_scene"))
        scenes.forEachIndexed { index, sc ->
            val scene = sc.rec("scene")
            val sceneId = scene?.id?.ifEmpty { null } ?: sc.str("scene_id")
            CharListRow(
                onClick = { ctx.nav.go("scenes/$sceneId") },
                leading = { SquareAvatar(scene?.str("number").orEmpty()) },
                title = scene?.str("name")?.ifEmpty { null } ?: "${t("csync_scene")} ${scene?.str("number").orEmpty()}",
                sub = "",
                end = fmtDate(scene?.long("shoot_date")),
                last = index == scenes.lastIndex,
            )
        }
    }
}

@Composable
private fun ChangeEditDialog(open: Boolean, ch: Rec, ctx: SyncCtx, onClose: () -> Unit, reload: () -> Unit) {
    var name by remember(open) { mutableStateOf(ch.str("name")) }
    var description by remember(open) { mutableStateOf(ch.str("description")) }
    var notes by remember(open) { mutableStateOf(ch.str("notes")) }
    var saving by remember(open) { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_edit_change"),
        onDismiss = onClose,
        confirmLabel = t("csync_save"),
        busy = saving,
        onConfirm = {
            saving = true
            // The whole form is sent, blanks included: an emptied field clears it.
            val fields = buildJsonObject {
                put("name", JsonPrimitive(name))
                put("description", JsonPrimitive(description))
                put("notes", JsonPrimitive(notes))
            }
            ctx.launchWrite({ ctx.api.patch("/changes/${ch.id}", fields) }) {
                saving = false
                onClose()
                reload()
            }
        },
    ) {
        TextInput(name, { name = it }, t("csync_field_name"), FormWide)
        TextInput(description, { description = it }, t("csync_field_description"), FormWide, multiline = true)
        TextInput(notes, { notes = it }, t("csync_field_notes"), FormWide, multiline = true)
    }
}
