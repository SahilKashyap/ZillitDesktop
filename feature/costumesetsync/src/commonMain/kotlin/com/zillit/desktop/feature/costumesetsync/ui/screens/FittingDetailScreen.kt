package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.ui.DateTimeInput
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
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
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.dateTimeMs
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.fittedCount
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTimeLong
import com.zillit.desktop.feature.costumesetsync.domain.humanize
import com.zillit.desktop.feature.costumesetsync.domain.isoInstant
import com.zillit.desktop.feature.costumesetsync.domain.measurementsOf
import com.zillit.desktop.feature.costumesetsync.domain.recordRequestDraft
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.Load
import com.zillit.desktop.feature.costumesetsync.ui.LoadingView
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReferenceGrid
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val FITTING_CLOSED = setOf("COMPLETED", "CANCELLED")

/** The "Alteration required" form of one piece. */
private data class AlterationDraft(
    val costumeId: String,
    val issue: String = "",
    val required: String = "",
    val date: String = "",
    val time: String = "",
) {
    val valid: Boolean get() = issue.isNotBlank() && required.isNotBlank()
}

/** The piece note being typed on the checklist, until saved. */
private data class NoteEdit(val costumeId: String, val text: String)

/**
 * One fitting: start it, tick each piece off (fitted, pending, alteration, reject), note what was found,
 * and close it (the web's `FittingDetailScreen`). "Alteration" asks for the issue and the work and raises
 * the tailoring ticket in the same call — the service sends the piece to the tailor and marks it
 * unavailable.
 */
@Composable
fun FittingDetailScreen(fittingId: String) {
    val ctx = LocalSync.current
    val fitting = rememberResource(fittingId) { api.get("/fittings/$fittingId") }
    SocketRefresh(SyncEvents.Fitting, predicate = { it.str("entity_id") == fittingId }) {
        fitting.reload(silent = true)
    }

    when (val state = fitting.state) {
        Load.Loading -> LoadingView()
        is Load.Failed -> FittingNotFound { ctx.nav.back() }
        is Load.Ready -> {
            val rec = state.value.rec
            if (rec == null) FittingNotFound { ctx.nav.back() } else FittingBody(rec, fittingId) {
                fitting.reload(silent = true)
            }
        }
    }
}

@Composable
private fun FittingNotFound(onBack: () -> Unit) {
    EmptyState(
        title = t("csync_fitting_not_found_title"),
        hint = t("csync_fitting_not_found_hint"),
        action = { ZillitButton(t("csync_fitting_back"), onClick = onBack, variant = ButtonVariant.Secondary) },
    )
}

/** The editable state of the open fitting, held in one place so the pieces of the screen can share it. */
private class FittingDetailState {
    var busy by mutableStateOf(false)
    var pickerOpen by mutableStateOf(false)
    var alt by mutableStateOf<AlterationDraft?>(null)
    var note by mutableStateOf<NoteEdit?>(null)
    var discard by mutableStateOf(false)
    var request by mutableStateOf<RequestDraft?>(null)
}

/** The writes the fitting screen makes; each reloads on success and clears `busy` either way. */
private class FittingWrites(
    val ctx: SyncCtx,
    val fittingId: String,
    val ui: FittingDetailState,
    val reload: () -> Unit,
) {
    /** A write that reloads on success and clears busy either way; [after] runs on success. */
    fun run(call: suspend () -> ZillitResult<Answer>, after: () -> Unit) {
        ui.busy = true
        ctx.scope.launch {
            val answer = ctx.write(call)
            ui.busy = false
            if (answer != null) {
                after()
                reload()
            }
        }
    }

    fun setItem(costumeId: String, patch: JsonObject, after: () -> Unit) {
        run({ ctx.api.patch("/fittings/$fittingId/items/$costumeId", patch) }, after)
    }

    fun setStatus(status: String) {
        run({ ctx.api.patch("/fittings/$fittingId", body("status" to status)) }, {})
    }
}

@Composable
private fun FittingBody(fitting: Rec, fittingId: String, reload: () -> Unit) {
    val ctx = LocalSync.current
    val ui = remember { FittingDetailState() }
    val writes = FittingWrites(ctx, fittingId, ui, reload)
    val character = fitting.rec("character") ?: Rec.Empty
    FittingHeader(fitting, writes)
    fitting.str("notes").takeIf { it.isNotBlank() }?.let {
        WfNotice(it, Modifier.padding(bottom = ZillitTheme.spacing.md), info = true)
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        FittingChecklist(fitting, writes, Modifier.weight(1.4f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            val actor = character.rec("actor") ?: fitting.rec("actor")
            MeasurementsCard(character.rec("actor"), actor?.str("name").orEmpty())
            ReferenceGrid(
                entityType = "FITTING",
                entityId = fittingId,
                title = t("csync_fitting_photos"),
                kinds = listOf("FRONT", "SIDE", "BACK", "DETAIL"),
            )
        }
    }
    FittingDialogs(fitting, writes)
}

@Composable
private fun FittingHeader(fitting: Rec, writes: FittingWrites) {
    val character = fitting.rec("character") ?: Rec.Empty
    val who = fittingWho(fitting)
    val sub = listOf(
        fmtDateTimeLong(fitting.long("scheduled_at")),
        fitting.str("location"),
        fittedLine(fittedCount(fitting), fitting.recs("items").size),
    ).filter { it.isNotBlank() }.joinToString(" · ")
    PageHead(
        title = who,
        sub = sub,
        crumbs = "${t("csync_fittings_title")} / ${character.str("name")}",
        titleContent = { FittingTitle(who, character.str("name"), fitting.str("status")) },
        actions = { FittingHeaderActions(fitting, sub, writes) },
    )
}

@Composable
private fun FittingTitle(who: String, characterName: String, status: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WfLargeAvatar(wfInitials(characterName))
        ZillitText(
            who,
            Modifier.weight(1f, fill = false),
            style = ZillitTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 2,
        )
        StatusBadge(status, large = true)
    }
}

@Composable
private fun FittingHeaderActions(fitting: Rec, sub: String, writes: FittingWrites) {
    val ctx = writes.ctx
    val fittingId = writes.fittingId
    val characterName = fitting.rec("character")?.str("name").orEmpty()
    val summary = "${t("csync_fitting")}: $characterName" +
        (fitting.rec("actor")?.str("name")?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()) + "\n$sub"
    RecordActions(
        "FITTING",
        fittingId,
        "${t("csync_fitting")} · $characterName",
        summary,
        count = rememberCommentCounts("FITTING")[fittingId] ?: 0,
    )
    if (!ctx.canPost) return
    WfSendRequestButton {
        writes.ui.request = recordRequestDraft(
            fittingId,
            "${t("csync_fitting")} · $characterName",
            summary,
            t("csync_ask_fitting"),
            ctx.project.name.ifBlank { t("csync_production") },
        )
    }
    FittingStatusButtons(fitting.str("status"), writes)
}

@Composable
private fun FittingStatusButtons(status: String, writes: FittingWrites) {
    val busy = writes.ui.busy
    if (status == "SCHEDULED") {
        ZillitButton(
            t("csync_start_fitting"),
            onClick = { writes.setStatus("IN_PROGRESS") },
            variant = ButtonVariant.Secondary,
            enabled = !busy,
        )
    }
    if (status !in FITTING_CLOSED) {
        ZillitButton(
            t("csync_complete"),
            onClick = { writes.setStatus("COMPLETED") },
            leadingIcon = ZillitIcons.Check,
            enabled = !busy,
        )
        ZillitButton(
            t("csync_cancel"),
            onClick = { writes.setStatus("CANCELLED") },
            variant = ButtonVariant.Tertiary,
            enabled = !busy,
        )
    }
}

@Composable
private fun FittingChecklist(fitting: Rec, writes: FittingWrites, modifier: Modifier) {
    val ctx = writes.ctx
    val ui = writes.ui
    val items = fitting.recs("items")
    SectionCard(
        modifier = modifier,
        title = t("csync_fitting_checklist"),
        actions = {
            if (ctx.canPost) ZillitButton(
                t("csync_piece"),
                onClick = { ui.pickerOpen = true },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        },
    ) {
        if (items.isEmpty()) MutedText(t("csync_fitting_no_items"), maxLines = 2)
        items.forEachIndexed { index, item ->
            val costumeId = item.str("costume_id").ifBlank { item.rec("costume")?.id.orEmpty() }
            FittingItem(
                item = item,
                costumeId = costumeId,
                busy = ui.busy,
                note = ui.note?.takeIf { it.costumeId == costumeId },
                onNote = { ui.note = it },
                onSaveNote = { text -> writes.setItem(costumeId, body("notes" to text)) { ui.note = null } },
                onCancelNote = { savedNote -> if (ui.note?.text == savedNote) ui.note = null else ui.discard = true },
                onStatus = { s -> writes.setItem(costumeId, body("status" to s)) {} },
                onAlteration = { ui.alt = AlterationDraft(costumeId) },
                onRemove = { writes.run({ ctx.api.delete("/fittings/${writes.fittingId}/items/$costumeId") }, {}) },
                first = index == 0,
            )
        }
    }
}

@Composable
private fun FittingDialogs(fitting: Rec, writes: FittingWrites) {
    val ctx = writes.ctx
    val ui = writes.ui
    val items = fitting.recs("items")
    WfCostumePicker(
        open = ui.pickerOpen,
        onClose = { ui.pickerOpen = false },
        characterId = fitting.str("character_id"),
        exclude = { c -> items.any { (it.str("costume_id").ifBlank { it.rec("costume")?.id.orEmpty() }) == c.id } },
        onPick = { c ->
            writes.run({ ctx.api.post("/fittings/${writes.fittingId}/items", body("costume_id" to c.id)) }, {})
        },
    )
    AlterationDialog(ui.alt, { ui.alt = it }, ui.busy) { draft ->
        writes.setItem(draft.costumeId, alterationPatch(draft)) { ui.alt = null }
    }
    WfConfirm(
        open = ui.discard,
        title = t("csync_discard_changes_title"),
        body = t("csync_discard_changes_body"),
        confirmLabel = t("csync_discard"),
        onConfirm = { ui.discard = false; ui.note = null },
        onDismiss = { ui.discard = false },
    )
    WfDraftRequestDialog(ui.request, "FITTING", t("csync_send_request_this_fitting")) { ui.request = null }
}

/** The patch the web sends: the item goes to ALTERATION_REQUIRED and carries the ticket to raise. */
private fun alterationPatch(draft: AlterationDraft): JsonObject {
    val deadline = dateTimeMs(draft.date, draft.time)
    return buildJsonObject {
        put("status", JsonPrimitive("ALTERATION_REQUIRED"))
        put("notes", JsonPrimitive(draft.issue.trim()))
        put(
            "alteration",
            buildJsonObject {
                put("issue", JsonPrimitive(draft.issue.trim()))
                put("required_work", JsonPrimitive(draft.required.trim()))
                put("deadline", deadline?.let { JsonPrimitive(isoInstant(it)) } ?: JsonNull)
            },
        )
    }
}

@Composable
private fun FittingItem(
    item: Rec,
    costumeId: String,
    busy: Boolean,
    note: NoteEdit?,
    onNote: (NoteEdit?) -> Unit,
    onSaveNote: (String) -> Unit,
    onCancelNote: (String) -> Unit,
    onStatus: (String) -> Unit,
    onAlteration: () -> Unit,
    onRemove: () -> Unit,
    first: Boolean = false,
) {
    val ctx = LocalSync.current
    val c = item.rec("costume") ?: Rec.Empty
    val itemStatus = item.str("status")
    val colors = ZillitTheme.colors
    if (!first) Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FittingItemHead(c, itemStatus) { ctx.nav.go("costumes/$costumeId") }
        FittingItemNote(item, costumeId, busy, note, onNote, onSaveNote, onCancelNote)
        if (ctx.canPost) {
            FittingItemButtons(itemStatus, busy, onStatus, onAlteration, onRemove)
        }
    }
}

@Composable
private fun FittingItemHead(c: Rec, itemStatus: String, onOpen: () -> Unit) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.clickable(onClick = onOpen),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ZillitText(
                c.str("asset_number"),
                style = ZillitTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
                color = colors.textMuted,
            )
            ZillitText(c.str("name"), style = ZillitTheme.typography.bodyMedium, color = colors.info)
        }
        StatusBadge(itemStatus)
    }
    ZillitText(
        listOfNotNull(
            c.str("size").ifBlank { null }?.let { "${t("csync_size")} $it" },
            tEnum(c.str("status")).ifBlank { null },
        ).joinToString(" · "),
        style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
        color = colors.textMuted,
    )
}

@Composable
private fun FittingItemNote(
    item: Rec,
    costumeId: String,
    busy: Boolean,
    note: NoteEdit?,
    onNote: (NoteEdit?) -> Unit,
    onSaveNote: (String) -> Unit,
    onCancelNote: (String) -> Unit,
) {
    val ctx = LocalSync.current
    when {
        note != null -> Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextInput(
                note.text,
                { onNote(note.copy(text = it)) },
                t("csync_field_notes"),
                Modifier.weight(1f),
                placeholder = t("csync_fitting_note_placeholder"),
            )
            ZillitButton(
                t("csync_save"),
                onClick = { onSaveNote(note.text) },
                size = ButtonSize.Small,
                enabled = !busy,
                loading = busy,
            )
            ZillitButton(
                t("csync_cancel"),
                onClick = { onCancelNote(item.str("notes")) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
        }
        ctx.canPost -> ZillitText(
            if (item.str("notes").isNotBlank()) "✎ ${item.str("notes")}" else "+ ${t("csync_notes_lower")}",
            Modifier.clickable { onNote(NoteEdit(costumeId, item.str("notes"))) },
            style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = ZillitTheme.colors.textMuted,
        )
        item.str("notes").isNotBlank() -> MutedText(item.str("notes"), maxLines = 3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FittingItemButtons(
    itemStatus: String,
    busy: Boolean,
    onStatus: (String) -> Unit,
    onAlteration: () -> Unit,
    onRemove: () -> Unit,
) {
    FlowRow(
        Modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        WfInkButton(
            t("csync_fitted"),
            { onStatus("FITTED") },
            on = itemStatus == "FITTED",
            icon = ZillitIcons.Check,
            enabled = !busy,
        )
        WfInkButton(
            tEnum("PENDING"),
            { onStatus("PENDING") },
            on = itemStatus == "PENDING",
            icon = ZillitIcons.Clock,
            enabled = !busy,
        )
        ZillitButton(
            t("csync_alteration"),
            onAlteration,
            variant = ButtonVariant.Secondary,
            size = ButtonSize.Small,
            enabled = !busy,
        )
        ZillitButton(
            t("csync_reject"),
            { onStatus("REJECTED") },
            variant = if (itemStatus == "REJECTED") ButtonVariant.Danger else ButtonVariant.Secondary,
            size = ButtonSize.Small,
            leadingIcon = ZillitIcons.Close,
            enabled = !busy,
        )
        ZillitButton(
            t("csync_remove_lower"),
            onRemove,
            variant = ButtonVariant.Tertiary,
            size = ButtonSize.Small,
            enabled = !busy,
        )
    }
}

@Composable
private fun MeasurementsCard(actor: Rec?, actorName: String) {
    val measures = measurementsOf(actor)
    SectionCard(title = t("csync_field_measurements")) {
        if (measures.isEmpty()) {
            MutedText(
                fill(t("csync_no_measurements_for"), "name" to actorName.ifBlank { t("csync_this_actor") }),
                maxLines = 3,
            )
        } else {
            measures.forEach { (k, v) -> FieldRow(humanize(k), v) }
        }
        actor?.str("notes")?.takeIf { it.isNotBlank() }?.let { WfNotice(it) }
    }
}

@Composable
private fun AlterationDialog(
    alt: AlterationDraft?,
    onChange: (AlterationDraft?) -> Unit,
    busy: Boolean,
    onRaise: (AlterationDraft) -> Unit,
) {
    var last by remember { mutableStateOf(alt) }
    if (alt != null) last = alt
    val draft = alt ?: last ?: return
    WfFormDialog(
        open = alt != null,
        title = t("csync_alteration_required"),
        onDismiss = { onChange(null) },
        actions = {
            ZillitButton(t("csync_cancel"), onClick = { onChange(null) }, variant = ButtonVariant.Secondary)
            ZillitButton(
                t("csync_raise_alteration"),
                onClick = { onRaise(draft) },
                enabled = draft.valid && !busy,
                loading = busy,
            )
        },
    ) {
        FormGrid {
            TextInput(draft.issue, { onChange(draft.copy(issue = it)) }, t("csync_field_issue"), FormWide)
            TextInput(draft.required, { onChange(draft.copy(required = it)) }, t("csync_field_required"), FormWide)
            DateTimeInput(
                draft.date,
                draft.time,
                { onChange(draft.copy(date = it)) },
                { onChange(draft.copy(time = it)) },
                t("csync_field_deadline"),
                FormWide,
            )
        }
        MutedText(t("csync_alteration_sends_to_tailor"), maxLines = 3)
    }
}
