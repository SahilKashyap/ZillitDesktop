package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.fittedCount
import com.zillit.desktop.feature.costumesetsync.domain.fittingChase
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.matches
import com.zillit.desktop.feature.costumesetsync.domain.recordRequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.startOfDay
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

/**
 * Fittings: schedule one against a character with the pieces to try, tick each piece off on its page,
 * raise alterations on the spot (the web's `FittingsScreen`).
 *
 * "Send reminder request" covers every fitting still open — not what the filters show. What is still to
 * come leads (that is what people are asked to confirm); a slot already gone by follows, marked missed.
 */
@Suppress("LongMethod")
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FittingsScreen() {
    val ctx = LocalSync.current
    val fittings = rememberResource { api.get("/fittings").mapRows() }
    val characters = rememberResource { api.get("/characters").mapRows() }
    SocketRefresh(SyncEvents.Fitting) { fittings.reload(silent = true) }

    var q by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var formOpen by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<RequestDraft?>(null) }
    val projectName = ctx.project.name.ifBlank { t("csync_production") }
    val all = fittings.value.orEmpty()

    PageHead(
        title = t("csync_fittings_title"),
        sub = t("csync_fittings_sub"),
        actions = {
            if (ctx.canPost) {
                ZillitButton(
                    t("csync_send_reminder_request"),
                    onClick = { request = fittingChase(all, startOfDay(ctx.now()), projectName, wfSay) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Send,
                )
                ZillitButton(t("csync_fitting"), onClick = { formOpen = true }, leadingIcon = ZillitIcons.Add)
            }
        },
    )
    // One toolbar row, as the web: the search (260-340 wide, magnifier on the right) then the status chips.
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        SearchWithButton(q, { q = it }, t("csync_fittings_search"), Modifier.width(340.dp))
        InkChip(t("csync_filter_all"), active = status.isEmpty(), onClick = { status = "" })
        // Lower-case, as the reference's chips read ("in progress").
        ctx.metaList("fitting_statuses").forEach { s ->
            InkChip(tEnum(s).lowercase(), active = status == s, onClick = { status = s })
        }
    }
    SectionCard(flush = true, modifier = Modifier.fillMaxWidth()) {
        Await(fittings) { rows ->
            val shown = rows.filter { x ->
                (status.isEmpty() || x.str("status") == status) &&
                    matches(q, x.rec("character")?.str("name"), x.rec("actor")?.str("name"), x.str("location"))
            }
            if (shown.isEmpty()) {
                EmptyState(if (q.isNotBlank()) t("csync_fittings_none_match") else t("csync_fittings_empty_title"))
            } else {
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalRecordCounts provides rememberCommentCounts("FITTING")
                ) {
                    shown.forEach { x ->
                        FittingRow(x, projectName, onRequest = { request = it })
                    }
                }
            }
        }
    }

    ScheduleFittingDialog(
        open = formOpen,
        onClose = { formOpen = false },
        characters = characters.value.orEmpty(),
        onCharacterAdded = { characters.reload(silent = true) },
        onDone = { sent ->
            fittings.reload(silent = true)
            if (sent != null) request = sent
        },
    )
    WfDraftRequestDialog(
        draft = request,
        entityType = "FITTING",
        title = if (
            request?.entityId != null
        ) t("csync_send_request_this_fitting") else t("csync_send_reminder_fittings"),
        onClose = { request = null },
    )
}

@Composable
private fun FittingRow(x: Rec, projectName: String, onRequest: (RequestDraft) -> Unit) {
    val ctx = LocalSync.current
    val items = x.recs("items")
    val character = x.rec("character")?.str("name").orEmpty()
    val actor = x.rec("actor")?.str("name").orEmpty()
    val alteration = items.any { it.str("status") == "ALTERATION_REQUIRED" }
    val who = character + if (actor.isNotBlank()) " · $actor" else ""
    val detail = listOfNotNull(
        fmtDateTime(x.long("scheduled_at")).ifBlank { null },
        x.str("location").ifBlank { null },
        fittedLine(fittedCount(x), items.size),
        if (alteration) t("csync_alteration_needed") else null,
    ).joinToString(" · ")
    ListRow(
        onClick = { ctx.nav.go("fittings/${x.id}") },
        leading = { SquareAvatar(wfInitials(character)) },
        end = {
            val summary = "${t("csync_fitting")}: $character" + (if (actor.isNotBlank()) " ($actor)" else "") +
                "\n${fmtDateTime(x.long("scheduled_at"))}" + (
                    if (x.str("location").isNotBlank()) " · ${x.str("location")}" else ""
                )
            WfSendRequestButton {
                onRequest(
                    recordRequestDraft(
                        x.id,
                        "${t("csync_fitting")} · $character",
                        summary,
                        t("csync_ask_fitting"),
                        projectName
                    ),
                )
            }
            RecordActions("FITTING", x.id, "${t("csync_fitting")} · $character", summary)
            StatusBadge(x.str("status"))
        },
    ) {
        RowTitle(who)
        MutedText(detail)
    }
}

/** The fitting's whole title line ("Character · Actor") — the page and its rows write it the same way. */
internal fun fittingWho(fitting: Rec): String {
    val actor = fitting.rec("actor")?.str("name").orEmpty()
    return fitting.rec("character")?.str("name").orEmpty() + if (actor.isNotBlank()) " · $actor" else ""
}
