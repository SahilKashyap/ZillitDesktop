package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.CLEANING_CLOSED
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.RequestDraft
import com.zillit.desktop.feature.costumesetsync.domain.Tone
import com.zillit.desktop.feature.costumesetsync.domain.cleaningChase
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.fmtTime
import com.zillit.desktop.feature.costumesetsync.domain.isSameDay
import com.zillit.desktop.feature.costumesetsync.domain.matches
import com.zillit.desktop.feature.costumesetsync.domain.relativeTime
import com.zillit.desktop.feature.costumesetsync.domain.statusTone
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MonoText
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

private val COLUMN_WIDTH = 236.dp
private const val BOARD_VIEW = "board"
private const val LIST_VIEW = "list"

/**
 * Sink / Cleaning: every ticket on a board by stage (or as a list), a request form, Emergency (pick the
 * piece, clean it now, get a replacement on set — see [EmergencyCleanDialog]) and "Send reminder request",
 * which chases the whole sink, emergencies first (the web's `CleaningScreen`).
 */
@Composable
fun CleaningScreen() {
    val ctx = LocalSync.current
    val data = rememberResource { api.get("/cleaning") }
    SocketRefresh(SyncEvents.Cleaning) { data.reload(silent = true) }

    var q by remember { mutableStateOf("") }
    var view by remember { mutableStateOf(BOARD_VIEW) }
    var requestOpen by remember { mutableStateOf(false) }
    var emergencyOpen by remember { mutableStateOf(false) }
    // The message a chase starts from, taken when the button is pressed, so a ticket moving on behind the
    // dialog never rewrites what is being typed.
    var chase by remember { mutableStateOf<RequestDraft?>(null) }

    val answer = data.value
    val items = answer?.rows.orEmpty()
    val pipeline = answer?.rec?.strings("pipeline").orEmpty()
    val now = ctx.now()
    val readyToday = { i: Rec -> i.str("status") == "READY" && isSameDay(i.long("completed_at").takeIf { it != 0L } ?: i.long("created"), now) }
    // The header counts the whole sink, not just what the search is showing.
    val stillOpen = items.filter { it.str("status") !in CLEANING_CLOSED }
    val shown = items.filter { cleaningMatches(q, it) }
    val projectName = ctx.project.name.ifBlank { t("csync_production") }

    PageHead(
        title = t("csync_cleaning_title"),
        sub = fill(t("csync_cleaning_sub"), "open" to stillOpen.size, "done" to items.count(readyToday)),
        actions = {
            ZillitChoiceChip(t("csync_cleaning_board"), selected = view == BOARD_VIEW, onClick = { view = BOARD_VIEW })
            ZillitChoiceChip(t("csync_cleaning_list"), selected = view == LIST_VIEW, onClick = { view = LIST_VIEW })
            if (ctx.canPost) {
                ZillitButton(t("csync_emergency"), onClick = { emergencyOpen = true }, variant = ButtonVariant.Danger, leadingIcon = ZillitIcons.Siren)
                ZillitButton(
                    t("csync_send_reminder_request"),
                    onClick = { chase = cleaningChase(stillOpen, projectName, wfSay, ::tEnum) },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Send,
                )
                ZillitButton(t("csync_request"), onClick = { requestOpen = true }, leadingIcon = ZillitIcons.Add)
            }
        },
    )
    ZillitSearchField(q, { q = it }, Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md), placeholder = t("csync_cleaning_search"))

    Await(data) {
        if (view == BOARD_VIEW) {
            CleaningBoard(pipeline, shown, readyToday)
        } else {
            CleaningList(shown, q)
        }
    }

    CleaningRequestDialog(requestOpen, { requestOpen = false }) { sent ->
        data.reload(silent = true)
        if (sent != null) chase = sent
    }
    EmergencyCleanDialog(emergencyOpen, { emergencyOpen = false }, { data.reload(silent = true) })
    WfDraftRequestDialog(
        draft = chase,
        entityType = "CLEANING",
        title = if (chase?.entityId != null) t("csync_send_request_this_ticket") else t("csync_send_reminder_cleaning"),
        onClose = { chase = null },
    )
}

private fun cleaningMatches(q: String, i: Rec): Boolean {
    val costume = i.rec("costume")
    return matches(
        q,
        costume?.str("asset_number"),
        costume?.str("name"),
        costume?.rec("character")?.str("name"),
        i.str("problem"),
        tEnum(i.str("cleaning_type")),
        i.rec("scene")?.str("number"),
        i.str("assigned_to_name"),
    )
}

/** One column per stage; only the cards grow, the stage heading and its count stay on top. */
@Composable
private fun CleaningBoard(pipeline: List<String>, shown: List<Rec>, readyToday: (Rec) -> Boolean) {
    val open = shown.filter { it.str("status") !in CLEANING_CLOSED }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.Top) {
        pipeline.forEach { stage ->
            val stageItems = if (stage == "READY") shown.filter(readyToday) else open.filter { it.str("status") == stage }
            Column(
                Modifier.width(COLUMN_WIDTH).clip(ZillitTheme.shapes.medium).background(ZillitTheme.colors.surfaceSunken).padding(ZillitTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = ZillitTheme.spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
                    RowTitle(tEnum(stage))
                    MutedText(stageItems.size.toString())
                }
                stageItems.forEach { CleaningCard(it) }
            }
        }
    }
}

/** The reference's card: asset and priority, the piece, "problem · type", "Sc N T{take} · character", then who has it and when it is due. */
@Composable
private fun CleaningCard(i: Rec) {
    val ctx = LocalSync.current
    val costume = i.rec("costume")
    val emergency = i.bool("is_emergency")
    val priority = if (emergency) "URGENT" else i.str("priority")
    val where = listOfNotNull(
        i.rec("scene")?.let { "${t("csync_sc")} ${it.str("number")}" + (if (i.long("take_number") > 0) " T${i.long("take_number")}" else "") },
        costume?.rec("character")?.str("name")?.ifBlank { null },
    ).joinToString(" · ")
    val colors = ZillitTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clip(ZillitTheme.shapes.medium)
            .background(colors.surface)
            .border(BorderStroke(1.dp, if (emergency) colors.danger else colors.border), ZillitTheme.shapes.medium)
            .clickable { ctx.nav.go("cleaning/${i.id}") }
            .padding(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoText(costume?.str("asset_number").orEmpty())
            StatusBadge(priority, label = if (emergency) t("csync_emergency") else tEnum(priority))
        }
        RowTitle(costume?.str("name").orEmpty())
        MutedText(listOf(i.str("problem"), tEnum(i.str("cleaning_type"))).filter { it.isNotBlank() }.joinToString(" · "), maxLines = 2)
        if (where.isNotBlank()) MutedText(where)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val who = i.str("assigned_to_name")
            ZillitText(
                if (who.isNotBlank()) "👤 $who" else t("csync_unassigned_lower"),
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f, fill = false),
            )
            ZillitText(whenText(i, LocalSync.current.now()), style = ZillitTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
        }
    }
}

private fun whenText(i: Rec, now: Long): String = when {
    i.str("status") == "READY" -> "${t("csync_done_at")} ${fmtTime(i.long("completed_at"))}"
    i.long("expected_ready_at") != 0L -> "${t("csync_eta")} ${fmtTime(i.long("expected_ready_at"))}"
    else -> relativeTime(i.long("created"), now, wfSay)
}

/** The list view: open tickets first, then the closed ones. */
@Composable
private fun CleaningList(shown: List<Rec>, q: String) {
    val ctx = LocalSync.current
    SectionCard(flush = true, modifier = Modifier.fillMaxWidth()) {
        if (shown.isEmpty()) {
            EmptyState(if (q.isNotBlank()) t("csync_cleaning_none_match") else t("csync_cleaning_empty_title"))
            return@SectionCard
        }
        val ordered = shown.filter { it.str("status") !in CLEANING_CLOSED } + shown.filter { it.str("status") in CLEANING_CLOSED }
        ordered.forEach { i ->
            val costume = i.rec("costume")
            val tone = statusTone(if (i.bool("is_emergency")) "URGENT" else i.str("priority"))
            val end = if (i.str("status") == "READY") fmtTime(i.long("completed_at")) else i.long("expected_ready_at").takeIf { it != 0L }?.let { "${t("csync_eta")} ${fmtTime(it)}" }.orEmpty()
            ListRow(
                onClick = { ctx.nav.go("cleaning/${i.id}") },
                leading = { ReadinessDot(toneLevel(tone)) },
                end = {
                    if (end.isNotBlank()) MutedText(end)
                    StatusBadge(i.str("status"))
                },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    MonoText(costume?.str("asset_number").orEmpty())
                    RowTitle(costume?.str("name").orEmpty(), Modifier.weight(1f, fill = false))
                }
                MutedText(
                    listOfNotNull(
                        i.str("problem").ifBlank { null },
                        tEnum(i.str("cleaning_type")).ifBlank { null },
                        i.rec("scene")?.let { "${t("csync_sc")} ${it.str("number")}" },
                        i.str("assigned_to_name").ifBlank { null },
                    ).joinToString(" · "),
                )
            }
        }
    }
}

/** The dot level [ReadinessDot] draws for a status tone. */
private fun toneLevel(tone: Tone): String = when (tone) {
    Tone.Ok -> "OK"
    Tone.Warn -> "WARNING"
    Tone.Danger -> "CRITICAL"
    Tone.Info -> "INFO"
    else -> ""
}
