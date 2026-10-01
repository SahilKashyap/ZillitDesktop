package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitHorizontalScrollRail
import com.zillit.desktop.core.designsystem.icon.AhIcons
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

private val COLUMN_WIDTH = 250.dp
private val ITEMS_MAX_HEIGHT = 480.dp
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
    val readyToday = { i: Rec ->
        i.str("status") == "READY" && isSameDay(i.long("completed_at").takeIf { it != 0L } ?: i.long("created"), now)
    }
    // The header counts the whole sink, not just what the search is showing.
    val stillOpen = items.filter { it.str("status") !in CLEANING_CLOSED }
    val shown = items.filter { cleaningMatches(q, it) }
    val projectName = ctx.project.name.ifBlank { t("csync_production") }

    PageHead(
        title = t("csync_cleaning_title"),
        sub = fill(t("csync_cleaning_sub"), "open" to stillOpen.size, "done" to items.count(readyToday)),
        actions = {
            CleaningActions(
                view = view,
                onView = { view = it },
                onEmergency = { emergencyOpen = true },
                onChase = { chase = cleaningChase(stillOpen, projectName, wfSay, ::tEnum) },
                onRequest = { requestOpen = true },
            )
        },
    )
    SearchWithButton(
        q,
        { q = it },
        t("csync_cleaning_search"),
        Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md),
    )

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

/** The header's view toggle and, for those who may post, Emergency / Send reminder request / Request. */
@Composable
private fun CleaningActions(
    view: String,
    onView: (String) -> Unit,
    onEmergency: () -> Unit,
    onChase: () -> Unit,
    onRequest: () -> Unit,
) {
    val ctx = LocalSync.current
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        WfViewToggle(AhIcons.Grid, view == BOARD_VIEW) { onView(BOARD_VIEW) }
        WfViewToggle(AhIcons.List, view == LIST_VIEW) { onView(LIST_VIEW) }
    }
    if (ctx.canPost) {
        ZillitButton(
            t("csync_emergency"),
            onClick = onEmergency,
            variant = ButtonVariant.Danger,
            leadingIcon = ZillitIcons.Siren,
        )
        ZillitButton(
            t("csync_send_reminder_request"),
            onClick = onChase,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Send,
        )
        ZillitButton(t("csync_request"), onClick = onRequest, leadingIcon = ZillitIcons.Add)
    }
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

/** One column per stage; only the cards scroll, the stage heading and its count stay on top. */
@Composable
private fun CleaningBoard(pipeline: List<String>, shown: List<Rec>, readyToday: (Rec) -> Boolean) {
    val open = shown.filter { it.str("status") !in CLEANING_CLOSED }
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(scroll).padding(bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            pipeline.forEach { stage ->
                val stageItems = if (stage == "READY") shown.filter(readyToday) else open.filter {
                    it.str("status") == stage
                }
                StageColumn(stage, stageItems)
            }
        }
        ZillitHorizontalScrollRail(scroll, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

/** One stage's column: its heading and count over the scrolling cards. */
@Composable
private fun StageColumn(stage: String, stageItems: List<Rec>) {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .width(COLUMN_WIDTH)
            .heightIn(min = 120.dp)
            .clip(shape)
            .background(if (colors.isDark) Color(0xFF0F172A) else Color(0xFFE4E6EB))
            .border(1.dp, if (colors.isDark) Color(0x0AFFFFFF) else Color(0xFFF3F4F6), shape)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZillitText(
                tEnum(stage),
                Modifier.weight(1f),
                style = ZillitTheme.typography.bodyMedium.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
            )
            val pill = RoundedCornerShape(999.dp)
            ZillitText(
                stageItems.size.toString(),
                Modifier.widthIn(min = 22.dp)
                    .clip(pill)
                    .background(colors.surface)
                    .border(1.dp, colors.border, pill)
                    .padding(
                    horizontal = 8.dp,
                    vertical = 1.dp,
                ),
                style = ZillitTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.heightIn(max = ITEMS_MAX_HEIGHT).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            stageItems.forEach { CleaningCard(it) }
        }
    }
}

/** "Sc N T{take} · character" for the card, whichever of the two the ticket has. */
private fun cardWhere(i: Rec): String {
    val sceneTake = i.rec("scene")?.let {
        "${t("csync_sc")} ${it.str("number")}" + (if (i.long("take_number") > 0) " T${i.long("take_number")}" else "")
    }
    return listOfNotNull(sceneTake, i.rec("costume")?.rec("character")?.str("name")?.ifBlank { null })
        .joinToString(" · ")
}

/** "problem · type". */
private fun cardProblem(i: Rec): String =
    listOf(i.str("problem"), tEnum(i.str("cleaning_type"))).filter { it.isNotBlank() }.joinToString(" · ")

/** An emergency: an inset red bar and a faint tint fading out, so the text lines up with every other card. */
@Composable
private fun cardTint(emergency: Boolean): Brush {
    val colors = ZillitTheme.colors
    return if (emergency) {
        Brush.horizontalGradient(0f to colors.dangerSoft, 0.6f to colors.surface, 1f to colors.surface)
    } else {
        SolidColor(colors.surface)
    }
}

/**
 * The reference's card: asset and priority, the piece, "problem · type", "Sc N T{take} · character", then who has it
 * and when it is due.
 */
@Composable
private fun CleaningCard(i: Rec) {
    val ctx = LocalSync.current
    val costume = i.rec("costume")
    val emergency = i.bool("is_emergency")
    val priority = if (emergency) "URGENT" else i.str("priority").ifBlank { "NORMAL" }
    val where = cardWhere(i)
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(10.dp)
    val tint = cardTint(emergency)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tint)
            .border(1.dp, colors.border, shape)
            .then(
                if (emergency) {
                    Modifier.drawBehind { drawRect(colors.danger, size = Size(3.dp.toPx(), size.height)) }
                } else {
                    Modifier
                },
            )
            .clickable { ctx.nav.go("cleaning/${i.id}") }
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CardHead(costume?.str("asset_number").orEmpty(), priority, emergency)
        ZillitText(
            costume?.str("name").orEmpty(),
            style = ZillitTheme.typography.bodyMedium.copy(
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 18.sp,
            ),
            maxLines = 1,
        )
        val problem = cardProblem(i)
        if (problem.isNotBlank()) {
            ZillitText(
                problem,
                style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 17.5.sp),
                color = colors.textPrimary.copy(alpha = 0.85f),
                maxLines = 2,
            )
        }
        if (where.isNotBlank()) ZillitText(
            where,
            style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.textMuted,
            maxLines = 1,
        )
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(1.dp).background(colors.divider))
        CardFoot(i)
    }
}

/** The card's top line: the asset number and the priority pill. */
@Composable
private fun CardHead(asset: String, priority: String, emergency: Boolean) {
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitText(
            asset,
            Modifier.weight(1f),
            style = ZillitTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                letterSpacing = 0.02.em,
            ),
            color = colors.textMuted,
            maxLines = 1,
        )
        WfPriorityPill(priority, if (emergency) t("csync_emergency") else tEnum(priority), emergency)
    }
}

/** The card's bottom line: who has it, and when it is due or done. */
@Composable
private fun CardFoot(i: Rec) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val who = i.str("assigned_to_name")
        ZillitText(
            if (who.isNotBlank()) "\uD83D\uDC64 $who" else t("csync_unassigned_lower"),
            style = ZillitTheme.typography.bodySmall.copy(
                fontSize = 12.sp,
                fontStyle = if (who.isBlank()) FontStyle.Italic else FontStyle.Normal,
            ),
            color = if (who.isBlank()) colors.textMuted else colors.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f, fill = false),
        )
        Box(Modifier.weight(1f))
        ZillitText(
            whenText(i, ctx.now()),
            style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.sp),
            color = colors.textMuted,
            maxLines = 1,
        )
    }
}

private fun whenText(i: Rec, now: Long): String = when {
    i.str("status") == "READY" -> "${t("csync_done_at")} ${fmtTime(i.long("completed_at"))}"
    i.long("expected_ready_at") != 0L -> "${t("csync_eta")} ${fmtTime(i.long("expected_ready_at"))}"
    else -> relativeTime(i.long("created"), now, wfSay)
}

/** The list view: open tickets first, then the closed ones. */
@Composable
internal fun CleaningList(shown: List<Rec>, q: String) {
    val ctx = LocalSync.current
    SectionCard(flush = true, modifier = Modifier.fillMaxWidth()) {
        if (shown.isEmpty()) {
            EmptyState(if (q.isNotBlank()) t("csync_cleaning_none_match") else t("csync_cleaning_empty_title"))
            return@SectionCard
        }
        val ordered = shown.filter { it.str("status") !in CLEANING_CLOSED } + shown.filter {
            it.str("status") in CLEANING_CLOSED
        }
        ordered.forEach { i ->
            val costume = i.rec("costume")
            val tone = statusTone(if (i.bool("is_emergency")) "URGENT" else i.str("priority"))
            val end = if (i.str("status") == "READY") fmtTime(i.long("completed_at")) else i.long("expected_ready_at")
                .takeIf { it != 0L }
                ?.let { "${t("csync_eta")} ${fmtTime(it)}" }
                .orEmpty()
            ListRow(
                onClick = { ctx.nav.go("cleaning/${i.id}") },
                leading = { ReadinessDot(toneLevel(tone)) },
                end = {
                    if (end.isNotBlank()) MutedText(end)
                    StatusBadge(i.str("status"))
                },
            ) {
                // `.csync-asset` (12px mono, muted) then the name, one space apart.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                    com.zillit.desktop.core.designsystem.component.ZillitText(
                        costume?.str("asset_number").orEmpty(),
                        style = ZillitTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                        color = ZillitTheme.colors.textMuted,
                        maxLines = 1,
                    )
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
