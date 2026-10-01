package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
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
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.TicketBoard
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.fmtMoney
import com.zillit.desktop.feature.costumesetsync.domain.isOverdue
import com.zillit.desktop.feature.costumesetsync.domain.matches
import com.zillit.desktop.feature.costumesetsync.domain.nextStage
import com.zillit.desktop.feature.costumesetsync.domain.recordRequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.ticketChase
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MonoText
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReferenceGrid
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch

/** Which of the three ticket boards: the web's `TicketsScreen kind`. */
enum class TicketKind { Alterations, Damages, Missing }

private fun TicketKind.board(): TicketBoard = when (this) {
    TicketKind.Alterations -> TicketBoard.Alterations
    TicketKind.Damages -> TicketBoard.Damages
    TicketKind.Missing -> TicketBoard.Missing
}

private val ALTERATION_PHOTO_KINDS = listOf("DETAIL", "FRONT", "BACK", "SIDE", "OTHER")
private val DAMAGE_PHOTO_KINDS = listOf("DETAIL", "OTHER")
private val MISSING_PHOTO_KINDS = listOf("REFERENCE", "OTHER")
private const val DEFAULT_FOUND_AT = "Wardrobe Truck"
private val FOUND_FIELD = 170.dp

/** A confirmation waiting on the user: what to ask and what to do on yes. */
private class Ask(val title: String, val body: String, val label: String, val action: () -> Unit)

/** Which missing piece is being marked found, and where. */
private class FoundAt(val id: String, val location: String)

/**
 * Alterations & tailoring, Damage reports and Missing items — one screen per route ([kind]), as the
 * reference gives each its own page under Costumes (the web's `TicketsScreen`).
 *
 * They are three services with three shapes — alterations answer `{ pipeline, items }` like cleaning
 * does, damage and missing a bare array. Each card carries Send a request and its photos; "Send reminder
 * request" chases everything still open in one message.
 */
@Composable
fun TicketsScreen(kind: TicketKind) {
    val ctx = LocalSync.current
    val board = kind.board()
    val tab = board.route
    val events = when (board) {
        TicketBoard.Alterations -> SyncEvents.Alteration
        TicketBoard.Damages -> SyncEvents.Damage
        TicketBoard.Missing -> SyncEvents.Missing
    }
    val data = rememberResource(board) { api.get("/$tab") }
    SocketRefresh(events) { data.reload(silent = true) }

    var q by remember { mutableStateOf("") }
    var onlyOpen by remember { mutableStateOf(true) }
    var formOpen by remember { mutableStateOf(false) }
    var chase by remember { mutableStateOf<RequestDraft?>(null) }
    var ask by remember { mutableStateOf<Ask?>(null) }
    // The row being written, so only its own buttons wait.
    var busyId by remember { mutableStateOf("") }
    var foundAt by remember { mutableStateOf<FoundAt?>(null) }

    val answer = data.value
    val rows = answer?.rows.orEmpty()
    val pipeline = answer?.rec?.strings("pipeline").orEmpty()
    val projectName = ctx.project.name.ifBlank { t("csync_production") }

    val act = { id: String, call: suspend () -> ZillitResult<Answer> ->
        busyId = id
        ctx.scope.launch {
            val done = ctx.write(call)
            busyId = ""
            if (done != null) {
                foundAt = null
                data.reload(silent = true)
            }
        }
        Unit
    }
    val actions = TicketActions(board, busyId, projectName, act, { ask = it }, { chase = it })

    PageHead(
        title = t("csync_page_title_$tab"),
        sub = if (board == TicketBoard.Damages) null else t("csync_page_sub_$tab"),
        actions = {
            if (ctx.canPost) {
                ZillitButton(
                    t("csync_send_reminder_request"),
                    onClick = {
                        val open = rows.filter { it.str("status") !in board.closed }
                        chase = ticketChase(board, open, projectName, wfSay, ::tEnum)
                    },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Send,
                )
                ZillitButton(t("csync_new_${board.kind}"), onClick = { formOpen = true }, leadingIcon = ZillitIcons.Add)
            }
        },
    )
    Row(Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        ZillitSearchField(q, { q = it }, Modifier.weight(1f), placeholder = t("csync_tickets_search_$tab"))
        // Open first, then All — the reference's two chips.
        ZillitChoiceChip(t("csync_filter_open"), selected = onlyOpen, onClick = { onlyOpen = true })
        ZillitChoiceChip(t("csync_filter_all"), selected = !onlyOpen, onClick = { onlyOpen = false })
    }

    Await(data) {
        val shown = rows.filter { (!onlyOpen || it.str("status") !in board.closed) && ticketMatches(board, q, it) }
        if (shown.isEmpty()) {
            SectionCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    if (q.isNotBlank()) t("csync_${tab}_no_match") else t("csync_${tab}_empty_title"),
                    hint = if (q.isBlank() && board == TicketBoard.Missing) t("csync_missing_empty_hint") else null,
                )
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                shown.forEach { r ->
                    when (board) {
                        TicketBoard.Alterations -> AlterationCard(r, pipeline, actions)
                        TicketBoard.Damages -> DamageCard(r, actions)
                        TicketBoard.Missing -> MissingCard(r, actions, foundAt, { foundAt = it })
                    }
                }
            }
        }
    }

    WfDraftRequestDialog(
        draft = chase,
        entityType = board.entity,
        title = if (chase?.entityId != null) t("csync_send_request_this_${board.kind}") else t("csync_send_reminder_title_$tab"),
        onClose = { chase = null },
    )
    TicketFormDialog(board, formOpen, { formOpen = false }) { sent ->
        data.reload(silent = true)
        if (sent != null) chase = sent
    }
    val pending = ask
    WfConfirm(
        open = pending != null,
        title = pending?.title.orEmpty(),
        body = pending?.body.orEmpty(),
        confirmLabel = pending?.label.orEmpty(),
        onConfirm = { pending?.action?.invoke(); ask = null },
        onDismiss = { ask = null },
    )
}

/** What a ticket card can do: write against its record, ask a confirmation, or start a request about it. */
private class TicketActions(
    val board: TicketBoard,
    val busyId: String,
    val projectName: String,
    val act: (String, suspend () -> ZillitResult<Answer>) -> Unit,
    val ask: (Ask) -> Unit,
    val request: (RequestDraft) -> Unit,
) {
    fun busy(id: String): Boolean = busyId == id

    /** The Send request of one card: the record's own summary, then what is being asked of whoever gets it. */
    fun send(r: Rec, title: String, summary: String, askKey: String) {
        request(recordRequestDraft(r.id, title, summary, t(askKey), projectName))
    }
}

private fun ticketMatches(board: TicketBoard, q: String, r: Rec): Boolean {
    val costume = r.rec("costume")
    val asset = costume?.str("asset_number")
    val name = costume?.str("name")
    return when (board) {
        TicketBoard.Alterations -> matches(
            q, asset, name, r.str("issue"), r.str("required_work"), r.str("tailor_name"), r.rec("character")?.str("name"),
            r.rec("character")?.rec("actor")?.str("name"), tEnum(r.str("status")), tEnum(r.str("priority")),
        )
        TicketBoard.Damages -> matches(q, asset, name, r.str("description"), r.rec("scene")?.str("number"), tEnum(r.str("responsible")), tEnum(r.str("status")))
        TicketBoard.Missing -> matches(q, asset, name, costume?.rec("character")?.str("name"), r.str("last_seen_location"), r.str("last_assigned_to"), r.str("notes"))
    }
}

/** The piece opens its own page, as the reference's card title does. */
@Composable
private fun CostumeLink(c: Rec?) {
    val ctx = LocalSync.current
    Row(Modifier.clickable { c?.id?.takeIf { it.isNotBlank() }?.let { ctx.nav.go("costumes/$it") } }, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        MonoText(c?.str("asset_number").orEmpty())
        ZillitText(c?.str("name").orEmpty(), style = ZillitTheme.typography.titleSmall, color = ZillitTheme.colors.accentText)
    }
}

@Composable
private fun AlterationCard(a: Rec, pipeline: List<String>, actions: TicketActions) {
    val ctx = LocalSync.current
    val status = a.str("status")
    val next = nextStage(pipeline, status)
    val late = isOverdue(a.long("deadline"), status, TicketBoard.Alterations.closed, ctx.now())
    val character = a.rec("character")
    val who = character?.let { it.str("name") + (it.rec("actor")?.str("name")?.takeIf { n -> n.isNotBlank() }?.let { n -> " ($n)" }.orEmpty()) }.orEmpty()
    val due = if (a.long("deadline") != 0L) fill(t("csync_due_n"), "date" to fmtDateTime(a.long("deadline"))) else t("csync_no_deadline")
    val costume = a.rec("costume")
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    CostumeLink(costume)
                    StatusBadge(a.str("priority"))
                    StatusBadge(status)
                    if (late) StatusBadge("OVERDUE", label = t("csync_overdue"))
                }
                ZillitText("${a.str("issue")} → ${a.str("required_work")}", style = ZillitTheme.typography.titleSmall)
                MutedText(
                    listOfNotNull(
                        who.ifBlank { null },
                        a.str("tailor_name").takeIf { it.isNotBlank() }?.let { fill(t("csync_tailor_n"), "x" to it) },
                        due,
                    ).joinToString(" · "),
                    maxLines = 2,
                )
                if (a.str("notes").isNotBlank()) MutedText(a.str("notes"), maxLines = 4)
            }
            AlterationButtons(a, status, next, actions)
        }
        if (status !in TicketBoard.Alterations.closed) WfPipeline(pipeline, status)
        ReferenceGrid(entityType = "ALTERATION", entityId = a.id, kinds = ALTERATION_PHOTO_KINDS, compact = true, bare = true, attachments = false)
    }
}

@Composable
private fun AlterationButtons(a: Rec, status: String, next: String?, actions: TicketActions) {
    val ctx = LocalSync.current
    val costume = a.rec("costume")
    val piece = "${costume?.str("asset_number").orEmpty()} ${costume?.str("name").orEmpty()}"
    val due = if (a.long("deadline") != 0L) " · ${fill(t("csync_due_n"), "date" to fmtDateTime(a.long("deadline")))}" else ""
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        WfSendRequestButton {
            val summary = "${t("csync_share_alteration")}: $piece\n${a.str("issue")} → ${a.str("required_work")}\n" +
                fill(t("csync_status_n"), "s" to tEnum(status)) + due
            actions.send(a, piece.trim(), summary, "csync_ask_alteration")
        }
        if (ctx.canPost && next != null) {
            ZillitButton(
                tEnum(next),
                onClick = { actions.act(a.id) { ctx.api.post("/alterations/${a.id}/advance", body()) } },
                size = ButtonSize.Small,
                trailingIcon = ZillitIcons.ChevronRight,
                enabled = !actions.busy(a.id),
            )
            ZillitButton(
                t("csync_cancel"),
                onClick = {
                    actions.ask(
                        Ask(t("csync_cancel_q"), t("csync_cancel_q"), t("csync_cancel")) {
                            actions.act(a.id) { ctx.api.post("/alterations/${a.id}/advance", body("to_status" to "CANCELLED")) }
                        },
                    )
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                enabled = !actions.busy(a.id),
            )
        }
    }
}

@Composable
private fun DamageCard(d: Rec, actions: TicketActions) {
    val ctx = LocalSync.current
    val status = d.str("status")
    val costume = d.rec("costume")
    val piece = "${costume?.str("asset_number").orEmpty()} ${costume?.str("name").orEmpty()}"
    val scene = d.rec("scene")
    val detail = listOfNotNull(
        fmtDateTime(d.long("created")).ifBlank { null },
        scene?.let { "${t("csync_sc")} ${it.str("number")}" + (if (d.long("take_number") > 0) " T${d.long("take_number")}" else "") },
        d.str("responsible").takeIf { it.isNotBlank() }?.let { fill(t("csync_responsible_n"), "x" to tEnum(it)) },
        if (ctx.isFinance && d.has("estimated_repair_cost")) fill(t("csync_est_repair_n"), "x" to fmtMoney(d.double("estimated_repair_cost"), ctx.currency)) else null,
    ).joinToString(" · ")
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    CostumeLink(costume)
                    StatusBadge(status)
                }
                ZillitText(d.str("description"), style = ZillitTheme.typography.titleSmall)
                MutedText(detail, maxLines = 2)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                WfSendRequestButton {
                    val summary = "${t("csync_share_damage")}: $piece\n${d.str("description")}\n" +
                        fill(t("csync_status_n"), "s" to tEnum(status)) + " · " + fill(t("csync_reported_lower_n"), "date" to fmtDateTime(d.long("created")))
                    actions.send(d, piece.trim(), summary, "csync_ask_damage")
                }
                if (ctx.canPost && status !in TicketBoard.Damages.closed) DamageButtons(d, status, actions)
            }
        }
        ReferenceGrid(entityType = "DAMAGE", entityId = d.id, kinds = DAMAGE_PHOTO_KINDS, compact = true, bare = true)
    }
}

@Composable
private fun DamageButtons(d: Rec, status: String, actions: TicketActions) {
    val ctx = LocalSync.current
    val set = { to: String -> actions.act(d.id) { ctx.api.patch("/damages/${d.id}", body("status" to to)) } }
    val busy = actions.busy(d.id)
    if (status == "OPEN") ZillitButton(t("csync_repairing"), onClick = { set("REPAIRING") }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, enabled = !busy)
    ZillitButton(t("csync_repaired"), onClick = { set("REPAIRED") }, size = ButtonSize.Small, enabled = !busy)
    ZillitButton(t("csync_write_off"), onClick = { set("WRITTEN_OFF") }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, enabled = !busy)
}

@Composable
private fun MissingCard(m: Rec, actions: TicketActions, foundAt: FoundAt?, onFoundAt: (FoundAt?) -> Unit) {
    val ctx = LocalSync.current
    val costume = m.rec("costume")
    val piece = "${costume?.str("asset_number").orEmpty()} ${costume?.str("name").orEmpty()}"
    val status = m.str("status")
    SectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    CostumeLink(costume)
                    StatusBadge(status)
                    costume?.rec("character")?.str("name")?.takeIf { it.isNotBlank() }?.let { MutedText(it) }
                }
                // Label beside value, "—" when unknown — as the reference's list.
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) {
                    FieldRow(t("csync_field_last_seen"), m.str("last_seen_location"))
                    FieldRow(t("csync_field_last_assigned_short"), m.str("last_assigned_to"))
                    FieldRow(t("csync_field_last_scan"), fmtDateTime(m.long("last_scan_at")))
                    FieldRow(t("csync_field_reported"), fmtDateTime(m.long("created")))
                    if (m.long("resolved_at") != 0L) FieldRow(t("csync_field_resolved"), fmtDateTime(m.long("resolved_at")))
                }
                if (m.str("notes").isNotBlank()) MutedText(m.str("notes"), maxLines = 4)
                ReferenceGrid(entityType = "MISSING", entityId = m.id, kinds = MISSING_PHOTO_KINDS, compact = true, bare = true, attachments = false)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                WfSendRequestButton {
                    val summary = "${t("csync_share_missing")}: $piece\n" +
                        fill(t("csync_last_seen_n"), "x" to m.str("last_seen_location").ifBlank { "—" }) + " · " +
                        fill(t("csync_last_assigned_lower_n"), "x" to m.str("last_assigned_to").ifBlank { "—" }) + "\n" +
                        fill(t("csync_reported_n"), "date" to fmtDateTime(m.long("created")))
                    actions.send(m, piece.trim(), summary, "csync_ask_missing")
                }
                if (ctx.canPost && status == "OPEN") MissingButtons(m, actions, foundAt, onFoundAt)
            }
        }
    }
}

@Composable
private fun MissingButtons(m: Rec, actions: TicketActions, foundAt: FoundAt?, onFoundAt: (FoundAt?) -> Unit) {
    val ctx = LocalSync.current
    val busy = actions.busy(m.id)
    val here = foundAt?.takeIf { it.id == m.id }
    if (here != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TextInput(here.location, { onFoundAt(FoundAt(m.id, it)) }, t("csync_field_found_at"), Modifier.width(FOUND_FIELD), placeholder = t("csync_field_found_at"))
            ZillitButton(
                t("csync_save"),
                onClick = {
                    actions.act(m.id) {
                        ctx.api.patch("/missing/${m.id}", body("status" to "FOUND", "found_location" to here.location.ifBlank { DEFAULT_FOUND_AT }))
                    }
                },
                size = ButtonSize.Small,
                enabled = !busy,
            )
        }
    } else {
        ZillitButton(t("csync_found"), onClick = { onFoundAt(FoundAt(m.id, DEFAULT_FOUND_AT)) }, size = ButtonSize.Small, leadingIcon = ZillitIcons.Check)
    }
    ZillitButton(
        t("csync_write_off"),
        onClick = {
            actions.ask(
                Ask(t("csync_write_off"), t("csync_write_off_confirm"), t("csync_write_off")) {
                    actions.act(m.id) { ctx.api.patch("/missing/${m.id}", body("status" to "WRITTEN_OFF")) }
                },
            )
        },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
        enabled = !busy,
    )
}
