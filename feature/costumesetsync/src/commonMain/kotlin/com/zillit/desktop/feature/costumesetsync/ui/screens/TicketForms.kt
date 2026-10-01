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
import com.zillit.desktop.feature.costumesetsync.domain.TicketBoard
import com.zillit.desktop.feature.costumesetsync.domain.TicketFormValues
import com.zillit.desktop.feature.costumesetsync.domain.dateTimeMs
import com.zillit.desktop.feature.costumesetsync.domain.isoInstant
import com.zillit.desktop.feature.costumesetsync.domain.ticketSendDraft
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
import kotlinx.coroutines.launch

/** Where each report's photos go, and under which kind — as the reference files them. */
private fun mediaKind(board: TicketBoard): String = if (board == TicketBoard.Missing) "REFERENCE" else "DETAIL"

/** A piece already in one of these states cannot sensibly enter it again. */
private fun excluded(board: TicketBoard, c: Rec): Boolean = when (board) {
    TicketBoard.Alterations -> c.str("status") in setOf("ALTERATION", "CLEANING", "MISSING")
    TicketBoard.Damages -> false
    TicketBoard.Missing -> c.str("status") == "MISSING"
}

private const val DEFAULT_PRIORITY = "NORMAL"
private const val DEFAULT_RESPONSIBLE = "PRODUCTION"

/** What a ticket form holds between keystrokes; one shape for all three boards, as the web's one component. */
private data class TicketDraftState(
    val issue: String = "",
    val required: String = "",
    val tailor: String = "",
    val priority: String = DEFAULT_PRIORITY,
    val date: String = "",
    val time: String = "",
    val description: String = "",
    val sceneId: String = "",
    val take: String = "",
    val cost: String = "",
    val responsible: String = DEFAULT_RESPONSIBLE,
    val lastSeen: String = "",
    val lastAssigned: String = "",
    val notes: String = "",
) {
    fun filled(board: TicketBoard): Boolean = when (board) {
        TicketBoard.Alterations -> issue.isNotBlank() && required.isNotBlank()
        TicketBoard.Damages -> description.isNotBlank()
        TicketBoard.Missing -> true
    }

    fun values(): TicketFormValues = TicketFormValues(
        issue = issue,
        requiredWork = required,
        tailor = tailor,
        deadlineMs = dateTimeMs(date, time),
        description = description,
        estimatedCost = cost,
        lastSeen = lastSeen,
        lastAssignedTo = lastAssigned,
        notes = notes,
    )
}

/**
 * Raise an alteration, a damage report or a missing report (the web's `TicketFormModal`).
 *
 * One component for the three because they are the same shape — pick a costume, describe the problem,
 * save — and the reference app's three pages differ only in their fields. The report is filed once — a
 * failed upload can be retried from the same open form without filing a second one — and what did not
 * attach stays in the picker. "… & send" files it, then hands [onDone] the request to open about it.
 * The fields stay across a Cancel and clear once the report is filed.
 */
@Composable
fun TicketFormDialog(board: TicketBoard, open: Boolean, onClose: () -> Unit, onDone: (RequestDraft?) -> Unit) {
    val ctx = LocalSync.current
    if (!ctx.canPost) return
    var costume by remember { mutableStateOf<Rec?>(null) }
    var f by remember { mutableStateOf(TicketDraftState()) }
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
    val ready = chosen != null && f.filled(board) && !saving
    val verb = if (board == TicketBoard.Alterations) "csync_request" else "csync_report"

    val submit = { send: Boolean ->
        saving = true
        ctx.scope.launch {
            var id = createdId
            if (id == null && chosen != null) {
                id = ctx.write { ctx.api.post("/${board.route}", createBody(board, chosen, f, ctx.isFinance)) }
                    ?.rec?.id?.takeIf { it.isNotBlank() }
                createdId = id
            }
            if (id == null || chosen == null) {
                saving = false
                return@launch
            }
            val failed = ctx.attachMedia(media, board.entity, id, mediaKind(board), keep = { media = it })
            saving = false
            if (failed > 0) {
                ctx.toast(t("csync_saved_media_failed", "n" to failed), false)
                return@launch
            }
            createdId = null
            val draft = if (send) ticketSendDraft(board, id, "${chosen.str("asset_number")} ${chosen.str("name")}", f.values(), wfSay) else null
            f = TicketDraftState()
            costume = null
            onDone(draft)
            onClose()
        }
    }

    WfFormDialog(
        open = open,
        title = t(
            when (board) {
                TicketBoard.Alterations -> "csync_alteration_request_title"
                TicketBoard.Damages -> "csync_new_damage"
                TicketBoard.Missing -> "csync_new_missing"
            },
        ),
        onDismiss = onClose,
        actions = {
            WfSaveActions(
                onCancel = onClose,
                sendLabel = t("${verb}_and_send"),
                onSend = { submit(true) },
                saveLabel = t(verb),
                onSave = { submit(false) },
                canSave = ready,
                busy = saving,
                danger = board != TicketBoard.Alterations,
            )
        },
    ) {
        FormGrid {
            WfCostumeField(costume, onOpen = { pick = true }, locked = createdId != null)
            when (board) {
                TicketBoard.Alterations -> AlterationFields(f) { f = it }
                TicketBoard.Damages -> DamageFields(f) { f = it }
                TicketBoard.Missing -> MissingFields(f) { f = it }
            }
            Column(FormWide, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(t("csync_photos_and_video"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                MediaPicker(media, { media = it }, enabled = !saving)
            }
        }
    }
    WfCostumePicker(
        open = open && pick,
        onClose = { pick = false },
        exclude = { excluded(board, it) },
        onPick = { c ->
            costume = c
            // Reporting something missing, its current location is the best guess at where it was last seen.
            if (board == TicketBoard.Missing) f = f.copy(lastSeen = c.str("location"))
        },
    )
}

/** The body each board's create call takes; alterations carry the deadline as an ISO instant, not epoch ms. */
private fun createBody(board: TicketBoard, costume: Rec, f: TicketDraftState, finance: Boolean) = when (board) {
    TicketBoard.Alterations -> body(
        "costume_id" to costume.id,
        // The piece's character, as the costume page sends it.
        "character_id" to costume.str("character_id").ifBlank { costume.rec("character")?.id.orEmpty() },
        "issue" to f.issue.trim(),
        "required_work" to f.required.trim(),
        "tailor_name" to f.tailor.trim(),
        "priority" to f.priority,
        "deadline" to dateTimeMs(f.date, f.time)?.let(::isoInstant),
    )
    TicketBoard.Damages -> body(
        "costume_id" to costume.id,
        "description" to f.description.trim(),
        "scene_id" to f.sceneId,
        "take_number" to numOrNull(f.take)?.toLong(),
        // Money is for finance roles only — the service redacts it for everyone else.
        "estimated_repair_cost" to if (finance) numOrNull(f.cost) else null,
        "responsible" to f.responsible,
    )
    TicketBoard.Missing -> body(
        "costume_id" to costume.id,
        "last_seen_location" to f.lastSeen.trim(),
        "last_assigned_to" to f.lastAssigned.trim(),
        "notes" to f.notes.trim(),
    )
}

@Composable
private fun AlterationFields(f: TicketDraftState, onChange: (TicketDraftState) -> Unit) {
    val ctx = LocalSync.current
    TextInput(f.issue, { onChange(f.copy(issue = it)) }, t("csync_field_issue"), FormWide)
    TextInput(f.required, { onChange(f.copy(required = it)) }, t("csync_field_required"), FormWide)
    TextInput(f.tailor, { onChange(f.copy(tailor = it)) }, t("csync_field_tailor"))
    EnumInput(f.priority, ctx.metaList("priorities"), { onChange(f.copy(priority = it)) }, t("csync_field_priority"))
    WfDateTimeInput(f.date, f.time, { onChange(f.copy(date = it)) }, { onChange(f.copy(time = it)) }, t("csync_field_deadline"))
}

@Composable
private fun DamageFields(f: TicketDraftState, onChange: (TicketDraftState) -> Unit) {
    val ctx = LocalSync.current
    TextInput(f.description, { onChange(f.copy(description = it)) }, t("csync_field_damage"), FormWide)
    SceneSelect(f.sceneId, { onChange(f.copy(sceneId = it)) }, t("csync_field_scene"))
    TextInput(f.take, { onChange(f.copy(take = it.filter(Char::isDigit))) }, t("csync_field_take"), number = true)
    if (ctx.isFinance) {
        val currency = ctx.currency.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
        TextInput(f.cost, { onChange(f.copy(cost = it)) }, t("csync_field_est_repair_short") + currency, number = true)
    }
    EnumInput(f.responsible, ctx.metaList("damage_responsible"), { onChange(f.copy(responsible = it)) }, t("csync_field_responsible"))
}

@Composable
private fun MissingFields(f: TicketDraftState, onChange: (TicketDraftState) -> Unit) {
    TextInput(f.lastSeen, { onChange(f.copy(lastSeen = it)) }, t("csync_field_last_seen_location"))
    TextInput(f.lastAssigned, { onChange(f.copy(lastAssigned = it)) }, t("csync_field_last_assigned"))
    TextInput(f.notes, { onChange(f.copy(notes = it)) }, t("csync_field_notes"), FormWide, multiline = true)
}
