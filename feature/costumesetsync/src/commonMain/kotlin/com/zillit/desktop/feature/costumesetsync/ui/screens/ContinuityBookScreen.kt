package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.ContinuityModel
import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.PrintHtml
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private val SIDE_WIDTH = 280.dp

/** Both lists the book reads: every scene and every continuity take. */
private class BookData(val scenes: List<Rec>, val records: List<Rec>)

/**
 * The continuity book: the day being prepared ("Continuity of prep") and the
 * days already shot ("History of shoot"), both driven by the schedule. Print /
 * PDF saves whichever of the two is open, for the day it shows, as an HTML file
 * (the web prints a hidden document; see [prepHtml] / [bookHtml]). The take
 * photos of the web's book are not shown (media seam).
 */
@Composable
fun ContinuityBookScreen() {
    val ctx = LocalSync.current
    val route = ctx.nav.current
    val tab = if (route.arg("tab") == "shot") "shot" else "prep"
    val data = rememberResource {
        val scenes = api.get("/scenes")
        val records = api.get("/continuity")
        when {
            scenes is ZillitResult.Failure -> scenes
            else -> ZillitResult.Success(
                BookData(
                    (scenes as ZillitResult.Success).data.rows,
                    (records as? ZillitResult.Success)?.data?.rows.orEmpty(),
                ),
            )
        }
    }
    SocketRefresh(SyncEvents.Scene + SyncEvents.Continuity) { data.reload(silent = true) }
    var pickedPrep by remember { mutableStateOf("") }
    val today = todayParam(ctx.now())

    Await(data) { book ->
        val nextPrep = ContinuityModel.nextPrepDay(book.scenes, today)
        val prepDay = pickedPrep.ifEmpty { nextPrep }
        val days = ContinuityModel.shootDays(book.scenes, book.records, today)
        val asked = route.arg("day")
        val shotDay = (days.firstOrNull { it.day == asked } ?: days.firstOrNull())?.day ?: asked.ifEmpty { today }
        val day = if (tab == "prep") prepDay else shotDay
        PageHead(
            title = t("csync_continuity_book"),
            sub = t("csync_continuity_book_sub"),
            actions = {
                ZillitButton(t("csync_print_pdf"), onClick = { ctx.whenDownload { printBook(ctx, tab, day, book) } }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Print)
                if (ctx.canPost) ZillitButton(t("csync_record_take"), onClick = { ctx.nav.go("continuity") }, leadingIcon = ZillitIcons.Add)
            },
        )
        KitTabs(
            listOf("prep" to t("csync_continuity_of_prep"), "shot" to t("csync_history_of_shoot")),
            tab,
            { ctx.nav.setQuery("tab", it) },
        )
        Column(Modifier.padding(top = ZillitTheme.spacing.md)) {
            if (tab == "prep") {
                PrepTab(ctx, book, prepDay, onDay = { pickedPrep = it.ifEmpty { nextPrep } })
            } else if (days.isEmpty()) {
                SectionCard { EmptyState(t("csync_no_shoot_days"), t("csync_no_shoot_days_hint")) }
            } else {
                ShotTab(ctx, book, days, shotDay) { data.reload(silent = true) }
            }
        }
    }
}

/** Saves the open tab's document; a cancelled dialog does nothing. */
private fun printBook(ctx: SyncCtx, tab: String, day: String, book: BookData) {
    val project = ctx.project.rec
    val html = if (tab == "prep") prepHtml(day, book.scenes, project) else bookHtml(day, book.records, book.scenes, project)
    val title = t(if (tab == "prep") "csync_continuity_of_prep" else "csync_continuity_book")
    ctx.scope.launch {
        ctx.host.save("${PrintHtml.slug("$title $day")}.html", PrintHtml.page("$title · $day", html).encodeToByteArray())
    }
}

@Composable
private fun PrepTab(ctx: SyncCtx, book: BookData, prepDay: String, onDay: (String) -> Unit) {
    val prepScenes = ContinuityModel.onDay(book.scenes, prepDay)
    val rows = ContinuityModel.rowsOf(prepScenes)
    val notReady = rows.count { it.second.level != "READY" }
    SectionCard(
        title = "${t("csync_prep")} · ${DayKeys.medium(prepDay)}",
        actions = { com.zillit.desktop.core.designsystem.component.ZillitDateField(prepDay, onDay, Modifier.width(KIT_DATE_WIDTH)) },
    ) {
        if (prepScenes.isEmpty()) {
            EmptyState(t("csync_nothing_scheduled_day"), t("csync_nothing_scheduled_prep_hint"))
            return@SectionCard
        }
        DayStats(prepScenes)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            prepScenes.forEach { PrepScene(ctx, it, book.records) }
        }
    }
}

@Composable
private fun PrepScene(ctx: SyncCtx, s: Rec, records: List<Rec>) {
    // The web's `.csync-scenecard`: a bordered 10dp box.
    val box = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth().background(ZillitTheme.colors.surface, box).border(1.dp, ZillitTheme.colors.border, box).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitButton("${t("csync_sc")} ${s.str("number")}" + s.str("name").let { if (it.isBlank()) "" else " · $it" }, onClick = { ctx.nav.go("scenes/${s.id}") }, variant = ButtonVariant.Tertiary)
            MutedText(sceneLine(s))
        }
        val chars = s.recs("characters")
        if (chars.isEmpty()) MutedText(t("csync_nobody_tagged_scene"))
        chars.forEach { c ->
            val taken = records.count { it.str("scene_id") == s.id && it.str("character_id") == c.str("character_id") }
            PrepCharacter(ctx, s, c, taken)
        }
    }
}

@Composable
private fun PrepCharacter(ctx: SyncCtx, s: Rec, c: Rec, taken: Int) {
    val r = ContinuityModel.readiness(c)
    val change = c.rec("change")
    Column(Modifier.fillMaxWidth().padding(start = ZillitTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ReadinessDot(r.level)
            RowTitle(c.rec("character")?.str("name").orEmpty())
            c.rec("character")?.rec("actor")?.str("name")?.takeIf { it.isNotBlank() }?.let { MutedText(it) }
            if (change != null) ChangeBadge(ctx, change) else StatusBadge("WARNING", t("csync_no_change_assigned"))
            if (r.total > 0) MutedText(t("csync_pieces_ready_n", "r" to r.ready, "t" to r.total))
            if (taken > 0) StatusBadge("OK", t(if (taken == 1) "csync_n_take_one" else "csync_n_takes", "n" to taken))
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            if (ctx.canPost) {
                ZillitButton(
                    t("csync_record_take"),
                    onClick = { ctx.nav.go("continuity?sceneId=${s.id}&characterId=${c.str("character_id")}") },
                    variant = ButtonVariant.Secondary,
                    size = ButtonSize.Small,
                )
            }
        }
        val items = change?.recs("items").orEmpty()
        if (items.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                items.forEach { i ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                        ReadinessDot(ContinuityModel.itemLevel(i.rec("costume")?.str("status")))
                        MutedText(i.rec("costume")?.str("name").orEmpty() + i.str("wear_notes").let { if (it.isBlank()) "" else " · $it" })
                    }
                }
            }
        }
        if (change != null && r.blockers.isNotEmpty()) {
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth()) { MutedText(blockerLine(r), maxLines = 3) }
        }
    }
}

@Composable
private fun ChangeBadge(ctx: SyncCtx, change: Rec) {
    ZillitButton(
        "${t("csync_change")} #${change.str("change_number")} ${change.str("name")}",
        onClick = { ctx.nav.go("changes/${change.id}") },
        variant = ButtonVariant.Tertiary,
        size = ButtonSize.Small,
    )
}

@Composable
private fun ShotTab(ctx: SyncCtx, book: BookData, days: List<ContinuityModel.ShootDay>, shotDay: String, onChanged: () -> Unit) {
    val selected = days.firstOrNull { it.day == shotDay }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
        SectionCard(title = "${t("csync_shoot_days")} (${days.size})", flush = true, modifier = Modifier.width(SIDE_WIDTH)) {
            days.forEach { d ->
                ListRow(
                    onClick = { ctx.nav.setQuery("day", d.day) },
                    end = { if (d.takes == 0) StatusBadge("MUTED", t("csync_no_takes")) },
                ) {
                    RowTitle(DayKeys.medium(d.day) + if (d.day == shotDay) " ●" else "")
                    MutedText(
                        t(if (d.scenes.size == 1) "csync_n_scene_one" else "csync_n_scenes", "n" to d.scenes.size) + " · " +
                            t(if (d.takes == 1) "csync_n_take_one" else "csync_n_takes", "n" to d.takes),
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            selected?.scenes.orEmpty().forEach { s -> ShotScene(ctx, s, book.records, onChanged) }
        }
    }
}

@Composable
private fun ShotScene(ctx: SyncCtx, s: Rec, records: List<Rec>, onChanged: () -> Unit) {
    val rows = ContinuityModel.takesOf(records, s.id)
    val ids = rows.map { it.str("character_id") }.distinct()
    SectionCard(
        title = "${t("csync_sc")} ${s.str("number")}" + s.str("name").let { if (it.isBlank()) "" else " · $it" },
        actions = { ZillitButton(t("csync_open_scene"), onClick = { ctx.nav.go("scenes/${s.id}") }, variant = ButtonVariant.Secondary, size = ButtonSize.Small) },
    ) {
        MutedText(sceneLine(s).ifBlank { "—" })
        if (ids.isEmpty()) {
            EmptyState(t("csync_no_takes_recorded"), t("csync_no_takes_recorded_hint"))
            return@SectionCard
        }
        ids.forEach { cid ->
            val takes = rows.filter { it.str("character_id") == cid }
            val first = takes.first()
            val name = first.rec("character")?.str("name")?.ifBlank { null }
                ?: s.recs("characters").firstOrNull { it.str("character_id") == cid }?.rec("character")?.str("name")
                ?: t("csync_character")
            Column(Modifier.padding(top = ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                    RowTitle(name)
                    first.rec("character")?.rec("actor")?.str("name")?.takeIf { it.isNotBlank() }?.let { MutedText(it) }
                    first.rec("change")?.let { ChangeBadge(ctx, it) }
                }
                takes.forEach { TakeCard(ctx, it, onChanged) }
            }
        }
    }
}

@Composable
private fun TakeCard(ctx: SyncCtx, r: Rec, onChanged: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            RowTitle("${t("csync_take")} ${r.long("take_number")}")
            MutedText(fmtDateTime(r.long("created")) + r.str("recorded_by_name").let { if (it.isBlank()) "" else " · $it" }, Modifier.weight(1f))
            // Deleted at once, as the web's take card does.
            if (ctx.canPost) {
                ZillitButton(
                    t("csync_delete_take_title"),
                    onClick = { ctx.launchWrite({ ctx.api.delete("/continuity/${r.id}") }, onDone = { _: Answer -> onChanged() }) },
                    variant = ButtonVariant.Tertiary,
                    size = ButtonSize.Small,
                    leadingIcon = ZillitIcons.Trash,
                )
            }
        }
        r.rec("details")?.let { d ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xl)) { d.keys.forEach { k -> FieldRow(k, d.str(k)) } }
        }
        val acc = r.recs("accessories")
        if (acc.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                acc.forEach { a -> StatusBadge(if (a.bool("present")) "OK" else "FAIL", (if (a.bool("present")) "✓ " else "✗ ") + a.str("name")) }
            }
        }
        r.str("notes").takeIf { it.isNotBlank() }?.let { ZillitNotice(it, tone = StatusTone.Pending) }
    }
}
