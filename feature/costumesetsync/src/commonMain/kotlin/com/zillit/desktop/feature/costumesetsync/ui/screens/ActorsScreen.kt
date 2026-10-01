package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ColumnWidth
import com.zillit.desktop.core.designsystem.component.TableColumn
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDataTable
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

private const val SEARCH_WIDTH = 320
private const val NAME_COL = 150
private const val CHAR_COL = 170
private const val SHORT_COL = 80
private const val FITTING_COL = 150
private const val DATE_COL = 110
private const val REP_COL = 200
private const val NOTES_COL = 220
private const val MENU_COL = 56

/** The actor's talent representative: name, how to reach them, then any extra rows. */
@Composable
private fun TalentRep(a: Rec) {
    val extra = a.recs("talent_rep_details")
    if (a.str("talent_rep").isEmpty() && a.str("talent_rep_email").isEmpty() && a.str("talent_rep_phone").isEmpty() && extra.isEmpty()) {
        MutedText("—")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        if (a.str("talent_rep").isNotEmpty()) ZillitText(a.str("talent_rep"), style = ZillitTheme.typography.labelSmall, maxLines = 1)
        if (a.str("talent_rep_email").isNotEmpty()) MutedText(a.str("talent_rep_email"))
        if (a.str("talent_rep_phone").isNotEmpty()) MutedText(a.str("talent_rep_phone"))
        extra.forEach { MutedText("${it.str("label")}: ${it.str("value")}") }
    }
}

/** `dd/mm/yyyy`, as the reference's Start Work column. */
internal fun startWorkText(ms: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    if (ms <= 0) return ""
    val d = Instant.fromEpochMilliseconds(ms).toLocalDateTime(zone)
    return "${d.dayOfMonth.toString().padStart(2, '0')}/${d.monthNumber.toString().padStart(2, '0')}/${d.year}"
}

/** Whether the search text is in any of the actor's searchable fields (the web's filter). */
internal fun actorMatches(a: Rec, needle: String): Boolean {
    val n = needle.trim().lowercase()
    if (n.isEmpty()) return true
    val fields = listOf(a.str("name"), a.str("notes"), a.str("talent_rep"), a.str("talent_rep_email"), a.str("talent_rep_phone")) +
        a.recs("characters").map { it.str("name") } + a.recs("talent_rep_details").map { "${it.str("label")} ${it.str("value")}" }
    return fields.joinToString(" ").lowercase().contains(n)
}

/**
 * The Actors page (the web's `ActorsScreen`), reached from Character
 * Breakdown → Actors and from the "★ Actors" button on the characters list: a
 * table of the cast with their characters, contact details and next fitting,
 * plus the full actor form.
 */
@Composable
fun ActorsScreen() {
    val ctx = LocalSync.current
    val actors = rememberResource { api.get("/actors").mapRows() }
    SocketRefresh(SyncEvents.Actor + SyncEvents.Character) { actors.reload(silent = true) }
    var q by remember { mutableStateOf("") }
    var formOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var deleting by remember { mutableStateOf<Rec?>(null) }

    PageHead(
        title = "★ ${t("csync_nav_actors")}",
        sub = t("csync_actors_page_sub"),
        crumbs = "${t("csync_nav_characters")} / ${t("csync_nav_actors")}",
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ZillitText("★", Modifier.padding(end = 8.dp), style = ZillitTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold), color = ZillitTheme.colors.accent)
                ZillitText(t("csync_nav_actors"), style = ZillitTheme.typography.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold))
            }
        },
        modifier = Modifier.padding(top = 12.dp),
    )
    SectionCard(
        title = t("csync_all_actors"),
        actions = {
            if (ctx.canPost) ZillitButton(t("csync_add"), onClick = { editing = null; formOpen = true }, leadingIcon = ZillitIcons.Add)
        },
    ) {
        Await(actors) { all ->
            val list = all.filter { actorMatches(it, q) }
            SearchWithButton(q, { q = it }, t("csync_search_plain"), Modifier.widthIn(max = 320.dp).fillMaxWidth())
            if (list.isEmpty()) {
                EmptyState(t("csync_nothing_to_display"), if (ctx.canPost) t("csync_press_add_first_actor") else null)
            } else {
                ActorTable(list, onEdit = { editing = it; formOpen = true }, onDelete = { deleting = it })
            }
        }
    }
    ActorFormDialog(open = formOpen, onClose = { formOpen = false }, editing = editing, onSaved = { actors.reload(silent = true) })
    val doomed = deleting
    FormDialog(
        open = doomed != null,
        title = t("csync_delete_actor_confirm").replace("{name}", doomed?.str("name").orEmpty()),
        onDismiss = { deleting = null },
        confirmLabel = t("csync_delete"),
        onConfirm = {
            val id = doomed?.id.orEmpty()
            deleting = null
            ctx.launchWrite({ ctx.api.delete("/actors/$id") }) { actors.reload(silent = true) }
        },
        danger = true,
        width = 480.dp,
    ) { MutedText(t("csync_delete_actor_hint"), maxLines = 3) }
}

@Composable
private fun ActorTable(list: List<Rec>, onEdit: (Rec) -> Unit, onDelete: (Rec) -> Unit) {
    val ctx = LocalSync.current
    val columns = listOf(
        TableColumn<Rec>(t("csync_field_name"), ColumnWidth.Fixed(NAME_COL.dp)) { a ->
            if (ctx.canPost) CastLink(a.str("name"), { onEdit(a) }, bold = true) else ZillitText(a.str("name"), maxLines = 1)
        },
        TableColumn(t("csync_field_characters_plural"), ColumnWidth.Fixed(CHAR_COL.dp)) { a ->
            val chars = a.recs("characters")
            if (chars.isEmpty()) {
                MutedText("—")
            } else {
                Column { chars.forEach { c -> CastLink(charLabel(c), { ctx.nav.go("characters/${c.id}") }) } }
            }
        },
        TableColumn(t("csync_field_gender"), ColumnWidth.Fixed(SHORT_COL.dp)) { a -> ZillitText(tEnum(a.str("gender")), maxLines = 1) },
        TableColumn(t("csync_field_age"), ColumnWidth.Fixed((SHORT_COL / 2).dp)) { a -> ZillitText(a.str("age"), maxLines = 1) },
        TableColumn(t("csync_field_next_fitting_col"), ColumnWidth.Fixed(FITTING_COL.dp)) { a -> NextFittingCell(a) },
        TableColumn(t("csync_field_start_work"), ColumnWidth.Fixed(DATE_COL.dp)) { a -> ZillitText(startWorkText(a.long("start_work_date")), maxLines = 1) },
        TableColumn(t("csync_talent_rep_col"), ColumnWidth.Fixed(REP_COL.dp)) { a -> TalentRep(a) },
        TableColumn(t("csync_field_notes"), ColumnWidth.Fixed(NOTES_COL.dp)) { a -> MutedText(a.str("notes"), maxLines = 2) },
        TableColumn("", ColumnWidth.Fixed(MENU_COL.dp)) { a ->
            if (ctx.canPost) {
                RowMenu(listOf(menuAction(t("csync_edit")) { onEdit(a) }, menuAction(t("csync_delete"), danger = true) { onDelete(a) }))
            }
        },
    )
    ZillitDataTable(
        rows = list,
        columns = columns,
        modifier = Modifier.fillMaxWidth().padding(top = ZillitTheme.spacing.md),
        key = { it.id },
        virtualised = false,
    )
}

/** The fitting the service found wins (and links to it); the date typed on the form is only a note until one is booked. */
@Composable
private fun NextFittingCell(a: Rec) {
    val ctx = LocalSync.current
    val booked = a.long("next_fitting")
    val ms = if (booked > 0) booked else a.long("next_fitting_at")
    if (ms <= 0) return
    val id = a.str("next_fitting_id")
    if (booked > 0 && id.isNotEmpty()) {
        CastLink(fmtDateTime(ms), { ctx.nav.go("fittings/$id") })
    } else {
        ZillitText(fmtDateTime(ms), maxLines = 1)
    }
}
