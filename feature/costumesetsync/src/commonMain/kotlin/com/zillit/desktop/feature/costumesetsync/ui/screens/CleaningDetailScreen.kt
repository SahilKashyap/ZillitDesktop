package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.DEFAULT_CLEANING_PIPELINE
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.clipTitle
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.nextStage
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldGrid
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.Load
import com.zillit.desktop.feature.costumesetsync.ui.LoadingView
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReferenceGrid
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.Timeline
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * One cleaning ticket: the pipeline, the work buttons (advance, fast-track, QC pass/fail, cancel), its
 * details, stain photos, the replacement and the history (the web's `CleaningDetailScreen`).
 * Each button says what it sends, as the reference: Advance carries the note, Mark ready and Cancel fall
 * back to "Fast-tracked" / "Cancelled", and the QC buttons send only the QC result and notes.
 */
@Composable
fun CleaningDetailScreen(cleaningId: String) {
    val ctx = LocalSync.current
    val ticket = rememberResource(cleaningId) { api.get("/cleaning/$cleaningId") }
    // A ticket someone else is working moves stage by stage; this keeps the pipeline and the action
    // buttons honest without a manual reload.
    SocketRefresh(SyncEvents.Cleaning, predicate = { it.str("entity_id") == cleaningId }) {
        ticket.reload(silent = true)
    }

    when (val state = ticket.state) {
        Load.Loading -> LoadingView()
        is Load.Failed -> CleaningNotFound { ctx.nav.back() }
        is Load.Ready -> {
            val rec = state.value.rec
            if (rec == null) CleaningNotFound { ctx.nav.back() } else CleaningBody(rec, cleaningId) {
                ticket.reload(silent = true)
            }
        }
    }
}

@Composable
private fun CleaningNotFound(onBack: () -> Unit) {
    EmptyState(
        title = t("csync_cleaning_not_found_title"),
        hint = t("csync_cleaning_not_found_hint"),
        action = { ZillitButton(t("csync_cleaning_back"), onClick = onBack, variant = ButtonVariant.Secondary) },
    )
}

@Suppress("CyclomaticComplexMethod", "LongMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CleaningBody(ticket: Rec, cleaningId: String, reload: () -> Unit) {
    val ctx = LocalSync.current
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var cancelAsk by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<RequestDraft?>(null) }

    val send = { call: suspend () -> ZillitResult<Answer>, clearNote: Boolean ->
        busy = true
        ctx.scope.launch {
            val answer = ctx.write(call)
            busy = false
            if (answer != null) {
                if (clearNote) note = ""
                reload()
            }
        }
        Unit
    }
    val advance = { patch: JsonObject -> send({ ctx.api.post("/cleaning/$cleaningId/advance", patch) }, true) }

    val status = ticket.str("status")
    val pipeline = ticket.strings("pipeline").ifEmpty { DEFAULT_CLEANING_PIPELINE }
    val next = nextStage(pipeline, status)
    val closed = status == "READY" || status == "CANCELLED"
    val costume = ticket.rec("costume")
    val character = costume?.rec("character")?.str("name").orEmpty()
    val scene = ticket.rec("scene")
    val sub = listOfNotNull(
        character.ifBlank { null },
        scene?.let { "${t("csync_sc")} ${it.str("number")}" + (
            if (ticket.long("take_number") > 0) " ${t("csync_take")} ${ticket.long("take_number")}" else ""
        ) },
    ).joinToString(" · ")

    PageHead(
        title = ticket.str("problem"),
        sub = null,
        crumbs = "${t("csync_cleaning")} / ${t("csync_ticket")}",
        titleContent = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                itemVerticalAlignment = Alignment.CenterVertically
            ) {
                if (ticket.bool("is_emergency")) StatusBadge(
                    "URGENT",
                    label = "🚨 ${t("csync_emergency")}",
                    large = true
                )
                ZillitText(
                    ticket.str("problem"),
                    style = ZillitTheme.typography.titleLarge.copy(
                        fontSize = 24.sp,
                        lineHeight = 30.sp,
                        fontWeight = FontWeight.Bold
                    ),
                    maxLines = 2
                )
                StatusBadge(status, large = true)
            }
        },
        actions = {
            if (ctx.canPost) {
                ZillitButton(
                    t("csync_send_request_btn"),
                    onClick = { request = cleaningTicketDraft(ticket) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Send
                )
            }
        },
    )
    Row(
        Modifier.padding(bottom = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.clickable { costume?.id?.takeIf { it.isNotBlank() }?.let { ctx.nav.go("costumes/$it") } },
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)
        ) {
            ZillitText(
                costume?.str("asset_number").orEmpty(),
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            )
            ZillitText(
                costume?.str("name").orEmpty(),
                style = ZillitTheme.typography.bodyMedium,
                color = ZillitTheme.colors.info
            )
        }
        if (sub.isNotBlank()) MutedText(sub)
    }

    SectionCard(modifier = Modifier.fillMaxWidth()) {
        WfPipeline(pipeline, if (status == "CANCELLED") "" else status)
        if (status == "CANCELLED") WfNotice(t("csync_cleaning_cancelled"))
    }
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (ctx.canPost && !closed) {
                WorkCard(ticket, status, next, note, { note = it }, busy, advance, onAssignMe = {
                    send({ ctx.api.patch("/cleaning/$cleaningId", body("assigned_to" to ctx.currentUserId)) }, false)
                }, onCancel = { cancelAsk = true })
            }
            DetailsCard(ticket)
            ReferenceGrid(
                entityType = "CLEANING",
                entityId = cleaningId,
                title = t("csync_stain_photos"),
                kinds = listOf("STAIN", "DETAIL", "OTHER")
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ReplacementCard(ticket, closed, busy) { id ->
                send({ ctx.api.post("/cleaning/$cleaningId/replacement", body("costume_id" to id)) }, false)
            }
            SectionCard(title = t("csync_history")) {
                val logs = ticket.recs("logs").reversed().map { historyEntry(it) }
                if (logs.isEmpty()) MutedText(t("csync_no_history")) else Timeline(logs)
            }
        }
    }

    WfConfirm(
        open = cancelAsk,
        title = t("csync_cleaning_cancel"),
        body = t("csync_cleaning_cancel_confirm"),
        confirmLabel = t("csync_cleaning_cancel"),
        onConfirm = {
            cancelAsk = false
            advance(body("to_status" to "CANCELLED", "note" to note.ifBlank { t("csync_cancelled_note") }))
        },
        onDismiss = { cancelAsk = false },
    )
    WfDraftRequestDialog(request, "CLEANING", t("csync_send_request_this_ticket")) { request = null }
}

/**
 * The request the header's "Send request" starts from (the web leaves a link to the ticket; the desktop has no web
 * address).
 */
private fun cleaningTicketDraft(ticket: Rec): RequestDraft {
    val costume = ticket.rec("costume")
    val asset = costume?.str("asset_number").orEmpty()
    val character = costume?.rec("character")?.str("name")?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
    val emergency = if (ticket.bool("is_emergency")) " · ${t("csync_emergency").lowercase()}" else ""
    val with = ticket
        .str("assigned_to_name").takeIf { it.isNotBlank() }?.let { " · ${fill(t("csync_with_n"), "n" to it)}" }
        .orEmpty()
    val by = ticket
        .long("expected_ready_at").takeIf { it != 0L }?.let { " · ${t("csync_needed_by_lower")} ${fmtDateTime(it)}" }
        .orEmpty()
    val lines = listOf(
        "$asset ${costume?.str("name").orEmpty()}$character",
        "${t("csync_field_problem")}: ${ticket.str("problem")} · ${tEnum(ticket.str("cleaning_type"))}$emergency",
        "${t("csync_now")}: ${tEnum(ticket.str("status"))}$with$by",
        "",
        t("csync_cleaning_ticket_ask"),
    )
    return RequestDraft(
        clipTitle("${t("csync_cleaning")} · $asset ${ticket.str("problem")}"),
        lines.joinToString("\n"),
        ticket.id
    )
}

/** A log row as the [Timeline] reads it: when, who, the stage it moved to, and the note. */
private fun historyEntry(log: Rec): Rec = Rec(
    buildJsonObject {
        put("at", JsonPrimitive(log.long("created")))
        put("by", JsonPrimitive(log.str("by_user_name")))
        put("title", JsonPrimitive(tEnum(log.str("to_status"))))
        put("detail", JsonPrimitive(log.str("note")))
    },
)

@Composable
private fun WorkCard(
    ticket: Rec,
    status: String,
    next: String?,
    note: String,
    onNote: (String) -> Unit,
    busy: Boolean,
    advance: (JsonObject) -> Unit,
    onAssignMe: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard(title = t("csync_cleaning_work")) {
        if (ticket.str("assigned_to").isBlank()) {
            Row(Modifier.padding(bottom = 10.dp)) {
                ZillitButton(
                    t("csync_assign_to_me"),
                    onClick = onAssignMe,
                    variant = ButtonVariant.Secondary,
                    enabled = !busy
                )
            }
        }
        TextInput(note, onNote, t("csync_note_optional"), Modifier.fillMaxWidth())
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when {
                status == "QUALITY_CHECK" -> {
                    ZillitButton(
                        t("csync_cleaning_qc_pass"),
                        onClick = { advance(body("qc_result" to "PASS", "qc_notes" to note)) },
                        leadingIcon = ZillitIcons.Check,
                        enabled = !busy
                    )
                    ZillitButton(
                        t("csync_cleaning_qc_fail"),
                        onClick = { advance(body("qc_result" to "FAIL", "qc_notes" to note.ifBlank { "QC failed" })) },
                        variant = ButtonVariant.Danger,
                        leadingIcon = ZillitIcons.Close,
                        enabled = !busy,
                    )
                }
                next != null -> {
                    ZillitButton(
                        fill(t("csync_cleaning_advance_to"), "stage" to tEnum(next)),
                        onClick = { advance(body("note" to note)) },
                        trailingIcon = ZillitIcons.ChevronRight,
                        enabled = !busy,
                    )
                    if (next != "READY") {
                        ZillitButton(
                            t("csync_cleaning_mark_ready"),
                            onClick = { advance(body(
                                "to_status" to "READY",
                                "note" to note.ifBlank { t("csync_fast_tracked") },
                                "qc_result" to "PASS"
                            )) },
                            variant = ButtonVariant.Secondary,
                            enabled = !busy,
                        )
                    }
                }
            }
            WfTextButton(t("csync_cleaning_cancel"), ZillitTheme.colors.danger, onCancel, enabled = !busy)
        }
    }
}

@Composable
private fun DetailsCard(ticket: Rec) {
    val by = ticket
        .str("requested_by_name").takeIf { it.isNotBlank() }?.let { fill(t("csync_by_name"), "name" to it) }
        .orEmpty()
    SectionCard(title = t("csync_details")) {
        FieldGrid {
            FieldRow(t("csync_field_cleaning_type"), tEnum(ticket.str("cleaning_type")))
            FieldRow(t("csync_field_priority"), tEnum(ticket.str("priority")))
            FieldRow(t("csync_field_requested"), fmtDateTime(ticket.long("created")) + by)
            FieldRow(t("csync_field_assigned_to"), ticket.str("assigned_to_name"))
            FieldRow(t("csync_field_expected_ready"), fmtDateTime(ticket.long("expected_ready_at")))
            FieldRow(t("csync_field_started"), fmtDateTime(ticket.long("started_at")))
            FieldRow(t("csync_field_completed"), fmtDateTime(ticket.long("completed_at")))
            FieldRow(
                t("csync_field_quality_check"),
                listOf(
                    tEnum(ticket.str("qc_result")),
                    ticket.str("qc_notes")
                ).filter { it.isNotBlank() }.joinToString(" "),
            )
            FieldRow(t("csync_field_notes"), ticket.str("notes"))
        }
    }
}

@Composable
private fun ReplacementCard(ticket: Rec, closed: Boolean, busy: Boolean, onAssign: (String) -> Unit) {
    val ctx = LocalSync.current
    val replacement = ticket.rec("replacement")
    val alternatives = ticket.recs("alternatives")
    SectionCard(title = t("csync_cleaning_replacement")) {
        when {
            replacement != null -> WfNotice(
                fill(
                    t("csync_cleaning_replacement_set"),
                    "asset" to replacement.str("asset_number"),
                    "name" to replacement.str("name")
                ),
                ok = true,
                trailing = {
                    ZillitText(
                        t("csync_view"),
                        Modifier.clickable { ctx.nav.go("costumes/${replacement.id}") },
                        style = ZillitTheme.typography.bodyMedium.copy(
                            textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                        ),
                        color = ZillitTheme.colors.info,
                    )
                },
            )
            closed -> MutedText(t("csync_cleaning_replacement_closed"), maxLines = 3)
            alternatives.isEmpty() -> MutedText(t("csync_cleaning_replacement_none"), maxLines = 3)
            else -> alternatives.forEach { a ->
                CostumeRow(
                    a,
                    extra = if (
                        a.has("match_score")
                    ) " · ${fill(t("csync_match_n"), "n" to a.str("match_score"))}" else "",
                    end = {
                        if (ctx.canPost) ZillitButton(
                            t("csync_cleaning_assign"),
                            onClick = { onAssign(a.id) },
                            size = ButtonSize.Small,
                            enabled = !busy
                        )
                    },
                )
            }
        }
    }
}
