package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitQrCode
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.fmtMoney
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.ChipRow
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldGrid
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReferenceGrid
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.Timeline
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

private val PHOTO_KINDS = listOf("FRONT", "SIDE", "BACK", "CLOSEUP", "DETAIL", "DOCUMENT", "OTHER")
private val QR_SIZE = 110.dp
private val SIDE_COLUMN = 420.dp

/**
 * One costume, as the reference app lays it out: every action it can take (by status), where it is
 * and who it is for, the looks and scenes it is used in, its photos and documents, its records
 * (rentals, damage, missing, fittings) and the full timeline. The QR is drawn on this machine from
 * the asset number, which is what the service's own label encodes (the scan lookup takes it).
 */
@Composable
fun CostumeDetailScreen(id: String) {
    val resource = rememberResource(id) { api.get("/costumes/$id") }
    // The frame's `entity_id` narrows a refresh to this piece (a frame without one refreshes everything).
    SocketRefresh(SyncEvents.Costume, predicate = { it.str("entity_id").let { entity -> entity.isBlank() || entity == id } }) {
        resource.reload(silent = true)
    }
    Await(resource) { answer ->
        val costume = answer.rec
        if (costume == null) {
            EmptyState(t("csync_costume_not_found_title"), t("csync_costume_not_found_hint"))
        } else {
            CostumeDetail(costume, onChanged = { resource.reload(silent = true) })
        }
    }
}

@Composable
private fun CostumeDetail(c: Rec, onChanged: () -> Unit) {
    val ctx = LocalSync.current
    var edit by remember { mutableStateOf(false) }
    val openCleaning = c.recs("cleaning").firstOrNull { it.str("status") !in setOf("READY", "CANCELLED") }
    val openAlteration = c.recs("alterations").firstOrNull { it.str("status") !in setOf("COMPLETED", "CANCELLED") }
    val subLine = listOfNotNull(
        c.str("category").takeIf { it.isNotBlank() }?.let(::tEnum),
        c.str("type").ifBlank { null },
        c.str("color").ifBlank { null },
        c.str("size").ifBlank { null }?.let { "${t("csync_field_size")} $it" },
        c.str("brand").ifBlank { null },
        "${t("csync_at")} ${c.str("location")}",
        c.rec("character")?.str("name")?.ifBlank { null }?.let { "${t("csync_for")} $it" },
    ).joinToString(" · ")

    PageHead(
        title = "${c.str("asset_number")}  ${c.str("name")}",
        sub = subLine,
        actions = {
            if (ctx.canPost) ZillitButton(t("csync_edit"), onClick = { edit = true }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Edit)
            ZillitButton(t("csync_label"), onClick = { ctx.nav.go("labels?ids=${c.id}") }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Print)
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        StatusBadge(c.str("status"))
        c.rec("character")?.let { character ->
            ZillitButton(character.str("name"), onClick = { ctx.nav.go("characters/${character.id}") }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
        }
    }
    SectionCard(Modifier.fillMaxWidth()) {
        CostumeActions(c, onChanged = onChanged, go = ctx.nav::go, openCleaningId = openCleaning?.id.orEmpty(), openAlterationId = openAlteration?.id.orEmpty())
    }
    openCleaning?.let { OpenCleaningNotice(it) { ctx.nav.go("cleaning/${it.id}") } }
    openAlteration?.let { alteration ->
        val due = fmtDateTime(alteration.long("deadline")).takeIf { it.isNotBlank() }?.let { " · ${t("csync_due")} $it" }.orEmpty()
        SectionCard(Modifier.fillMaxWidth()) {
            ZillitText("${t("csync_with_tailor")}: ${alteration.str("issue")} → ${alteration.str("required_work")} · ${tEnum(alteration.str("status"))}$due")
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            DetailsCard(c)
            UsedInCard(c)
            ReferenceGrid("COSTUME", c.id, title = t("csync_photos_documents"), kinds = PHOTO_KINDS)
            RecordsCard(c)
        }
        SectionCard(Modifier.width(SIDE_COLUMN), title = t("csync_costume_timeline")) {
            val timeline = c.recs("timeline")
            if (timeline.isEmpty()) MutedText(t("csync_costume_timeline_empty"), maxLines = 2) else Timeline(timeline)
        }
    }
    CostumeFormDialog(open = edit, onClose = { edit = false }, initial = c, onSaved = onChanged)
}

@Composable
private fun OpenCleaningNotice(ticket: Rec, open: () -> Unit) {
    SectionCard(Modifier.fillMaxWidth()) {
        val ready = fmtDateTime(ticket.long("expected_ready_at")).ifBlank { "—" }
        ZillitText("${t("csync_in_cleaning")}: ${ticket.str("problem")} · ${tEnum(ticket.str("status"))} · ${t("csync_expected_ready")} $ready")
        ZillitButton(t("csync_open_ticket_lower"), onClick = open, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
    }
}

@Composable
private fun DetailsCard(c: Rec) {
    val ctx = LocalSync.current
    SectionCard(Modifier.fillMaxWidth(), title = t("csync_costume_details")) {
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
            ZillitQrCode(c.str("asset_number"), size = QR_SIZE)
            FieldGrid(Modifier.weight(1f)) {
                FieldRow(t("csync_field_source"), listOfNotNull(tEnum(c.str("source")), c.rec("vendor")?.str("name")?.ifBlank { null }).joinToString(" · "))
                if (ctx.isFinance && c.has("purchase_cost")) FieldRow(t("csync_field_purchase_cost"), fmtMoney(c.double("purchase_cost"), ctx.currency))
                if (ctx.isFinance && c.has("rental_cost_per_day")) FieldRow(t("csync_rental_per_day"), fmtMoney(c.double("rental_cost_per_day"), ctx.currency))
                FieldRow(t("csync_field_quantity"), c.str("quantity"))
                if (c.str("fabric").isNotBlank()) FieldRow(t("csync_field_fabric"), c.str("fabric"))
                if (c.str("care_instructions").isNotBlank()) FieldRow(t("csync_care"), c.str("care_instructions"))
                FieldRow(t("csync_added_on"), fmtDate(c.long("created")))
            }
        }
        if (c.str("notes").isNotBlank()) MutedText(c.str("notes"), maxLines = 6)
    }
}

@Composable
private fun UsedInCard(c: Rec) {
    val ctx = LocalSync.current
    val items = c.recs("change_items")
    val scenes = items.flatMap { item -> item.rec("change")?.recs("scene_characters").orEmpty() }
        .mapNotNull { it.rec("scene") }.distinctBy { it.id }
        .sortedWith(compareBy({ it.str("number").toIntOrNull() ?: Int.MAX_VALUE }, { it.str("number") }))
    SectionCard(Modifier.fillMaxWidth(), title = t("csync_used_in")) {
        if (items.isEmpty()) {
            MutedText(t("csync_not_in_any_change"))
            return@SectionCard
        }
        items.forEach { item ->
            val change = item.rec("change")
            val label = "${change?.rec("character")?.str("name").orEmpty()} · ${t("csync_change")} #${change?.str("change_number").orEmpty()} ${change?.str("name").orEmpty()}"
            ZillitButton(label, onClick = { ctx.nav.go("changes/${item.str("change_id")}") }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            if (item.str("wear_notes").isNotBlank()) MutedText("✎ ${item.str("wear_notes")}", maxLines = 3)
        }
        if (scenes.isNotEmpty()) {
            ChipRow {
                scenes.forEach { scene ->
                    val shoot = fmtDate(scene.long("shoot_date")).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    ZillitButton("${t("csync_sc")} ${scene.str("number")}$shoot", onClick = { ctx.nav.go("scenes/${scene.id}") }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                }
            }
        }
    }
}

@Composable
private fun RecordsCard(c: Rec) {
    val ctx = LocalSync.current
    val rentals = c.recs("rentals")
    val damages = c.recs("damages")
    val missing = c.recs("missing")
    val fittings = c.recs("fitting_items")
    if (rentals.isEmpty() && damages.isEmpty() && missing.isEmpty() && fittings.isEmpty()) return
    SectionCard(Modifier.fillMaxWidth(), title = t("csync_records")) {
        rentals.forEach { r ->
            RecordLine("🏷 ${t("csync_rental_from")} ${r.rec("vendor")?.str("name").orEmpty()} ${fmtDate(r.long("pickup_date"))} → ${fmtDate(r.long("return_date"))}", r.str("status"))
        }
        damages.forEach { d -> RecordLine("⚠️ ${fmtDate(d.long("created"))} ${d.str("description")}", d.str("status")) }
        missing.forEach { m ->
            RecordLine("🔎 ${t("csync_missing_since")} ${fmtDateTime(m.long("created"))} · ${t("csync_last_seen")} ${m.str("last_seen_location")}", m.str("status"))
        }
        fittings.forEach { f ->
            val title = "📏 ${t("csync_fitting")} ${fmtDate(f.rec("fitting")?.long("scheduled_at"))}"
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                ZillitButton(title, onClick = { ctx.nav.go("fittings/${f.str("fitting_id")}") }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
                StatusBadge(f.str("status"))
                if (f.str("notes").isNotBlank()) MutedText("· ${f.str("notes")}")
            }
        }
    }
}

@Composable
private fun RecordLine(text: String, status: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text, style = ZillitTheme.typography.bodySmall)
        StatusBadge(status)
    }
}
