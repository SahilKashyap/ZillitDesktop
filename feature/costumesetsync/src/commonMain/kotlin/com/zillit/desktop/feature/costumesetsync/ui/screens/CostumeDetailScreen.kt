package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
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
import com.zillit.desktop.feature.costumesetsync.ui.KvList
import com.zillit.desktop.feature.costumesetsync.ui.LinkText
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.Notice
import com.zillit.desktop.feature.costumesetsync.ui.NoticeTone
import com.zillit.desktop.feature.costumesetsync.ui.Page
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
    SocketRefresh(
        SyncEvents.Costume,
        predicate = { it.str("entity_id").let { entity -> entity.isBlank() || entity == id } },
    ) {
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
    val traits = listOfNotNull(
        c.str("category").takeIf { it.isNotBlank() }?.let(::tEnum),
        c.str("type").ifBlank { null },
        c.str("color").ifBlank { null },
        c.str("size").ifBlank { null }?.let { "${t("csync_field_size")} $it" },
        c.str("brand").ifBlank { null },
    ).joinToString(" · ")
    val character = c.rec("character")

    Page {
        PageHead(
            title = "",
            titleContent = { CostumeTitle(c, traits, character) },
            actions = {
                if (ctx.canPost) ZillitButton(
                    t("csync_edit"),
                    onClick = { edit = true },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Edit,
                )
                ZillitButton(
                    t("csync_label"),
                    onClick = { ctx.nav.go("labels?ids=${c.id}") },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Print,
                )
            },
            bottomPadding = 0.dp,
        )
        SectionCard(Modifier.fillMaxWidth()) {
            CostumeActions(
                c,
                onChanged = onChanged,
                go = ctx.nav::go,
                openCleaningId = openCleaning?.id.orEmpty(),
                openAlterationId = openAlteration?.id.orEmpty(),
            )
        }
        openCleaning?.let { OpenCleaningNotice(it) { ctx.nav.go("cleaning/${it.id}") } }
        openAlteration?.let { AlterationNotice(it) }
        CostumeBody(c)
    }
    CostumeFormDialog(open = edit, onClose = { edit = false }, initial = c, onSaved = onChanged)
}

private const val TIMELINE_WEIGHT = 1.2f

@Composable
private fun CostumeBody(c: Rec) {
    // `.csync-take-grid--even`: 1fr beside 1.2fr, 12 apart.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            DetailsCard(c)
            UsedInCard(c)
            ReferenceGrid("COSTUME", c.id, title = t("csync_photos_documents"), kinds = PHOTO_KINDS)
            RecordsCard(c)
        }
        SectionCard(Modifier.weight(TIMELINE_WEIGHT), title = t("csync_costume_timeline")) {
            val timeline = c.recs("timeline")
            if (timeline.isEmpty()) {
                MutedText(t("csync_costume_timeline_empty"), maxLines = 2)
            } else {
                Timeline(timeline)
            }
        }
    }
}

/** `crumbs`: "Costumes / CST-000001", the first a link. */
@Composable
private fun CostumeCrumbs(assetNumber: String) {
    val ctx = LocalSync.current
    val small = ZillitTheme.typography.bodyMedium.copy(fontSize = 12.sp)
    Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        LinkText(t("csync_nav_costumes"), { ctx.nav.go("costumes") }, style = small)
        ZillitText(" / $assetNumber", style = small, color = ZillitTheme.colors.textMuted, maxLines = 1)
    }
}

@Composable
private fun CostumeTitle(c: Rec, traits: String, character: Rec?) {
    val ctx = LocalSync.current
    val colors = ZillitTheme.colors
    val subStyle = ZillitTheme.typography.bodyMedium
    Column {
        CostumeCrumbs(c.str("asset_number"))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val title = ZillitTheme.typography.titleLarge.copy(
                fontSize = 24.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.Bold,
            )
            ZillitText(
                c.str("asset_number"),
                style = title.copy(fontFamily = FontFamily.Monospace, fontSize = 22.sp),
                maxLines = 1,
            )
            ZillitText(c.str("name"), style = title, maxLines = 2)
            StatusBadge(c.str("status"), large = true)
        }
        FlowRow(
            Modifier.padding(top = 4.dp),
            verticalArrangement = Arrangement.Center,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            val sub = subStyle.copy(fontSize = 13.sp)
            ZillitText(if (traits.isBlank()) "" else "$traits · ", style = sub, color = colors.textMuted)
            ZillitText("${t("csync_at")} ", style = sub, color = colors.textMuted)
            ZillitText(
                c.str("location"),
                style = sub.copy(fontWeight = FontWeight.Bold),
                color = colors.textMuted,
            )
            if (character != null) {
                ZillitText(" · ${t("csync_for")} ", style = sub, color = colors.textMuted)
                LinkText(
                    character.str("name"),
                    { ctx.nav.go("characters/${character.id}") },
                    bold = true,
                    style = sub,
                )
            }
        }
    }
}

@Composable
private fun AlterationNotice(alteration: Rec) {
    val due = fmtDateTime(alteration.long("deadline"))
        .takeIf { it.isNotBlank() }
        ?.let { " · ${t("csync_due")} $it" }
        .orEmpty()
    Notice(Modifier.padding(top = 10.dp)) {
        ZillitText(
            buildAnnotatedString {
                append("${t("csync_with_tailor")}: ")
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(alteration.str("issue")) }
                append(" → ${alteration.str("required_work")} · ${tEnum(alteration.str("status"))}$due")
            },
            style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp),
        )
    }
}

@Composable
private fun OpenCleaningNotice(ticket: Rec, open: () -> Unit) {
    val ready = fmtDateTime(ticket.long("expected_ready_at")).ifBlank { "—" }
    val body = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp)
    Notice(Modifier.padding(top = 10.dp), tone = NoticeTone.Info) {
        FlowRow(itemVerticalAlignment = Alignment.CenterVertically) {
            ZillitText(
                buildAnnotatedString {
                    append("${t("csync_in_cleaning")}: ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(ticket.str("problem")) }
                    append(" · ${tEnum(ticket.str("status"))} · ${t("csync_expected_ready")} $ready · ")
                },
                style = body,
            )
            LinkText(t("csync_open_ticket_lower"), open, style = body.copy(textDecoration = TextDecoration.Underline))
        }
    }
}

@Composable
private fun DetailsCard(c: Rec) {
    val ctx = LocalSync.current
    SectionCard(Modifier.fillMaxWidth(), title = t("csync_costume_details")) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(QR_SIZE).clip(RoundedCornerShape(8.dp)).border(
                    1.dp,
                    ZillitTheme.colors.border,
                    RoundedCornerShape(8.dp),
                ),
            ) {
                ZillitQrCode(c.str("asset_number"), size = QR_SIZE)
            }
            val rows = buildList {
                add(
                    t("csync_field_source") to listOfNotNull(
                        tEnum(c.str("source")),
                        c.rec("vendor")?.str("name")?.ifBlank { null },
                    ).joinToString(" · "),
                )
                if (ctx.isFinance && c.has("purchase_cost")) add(
                    t("csync_field_purchase_cost") to fmtMoney(c.double("purchase_cost"), ctx.currency),
                )
                if (ctx.isFinance && c.has("rental_cost_per_day")) add(
                    t("csync_rental_per_day") to fmtMoney(c.double("rental_cost_per_day"), ctx.currency),
                )
                add(t("csync_field_quantity") to c.str("quantity"))
                if (c.str("fabric").isNotBlank()) add(t("csync_field_fabric") to c.str("fabric"))
                if (c.str("care_instructions").isNotBlank()) add(t("csync_care") to c.str("care_instructions"))
                add(t("csync_added_on") to fmtDate(c.long("created")))
            }
            KvList(rows, Modifier.weight(1f))
        }
        if (c.str("notes").isNotBlank()) Notice(Modifier.padding(top = 10.dp)) {
            ZillitText(c.str("notes"), style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp))
        }
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item ->
                val change = item.rec("change")
                val label =
                    "${change?.rec("character")?.str("name").orEmpty()} · ${t("csync_change")} " +
                    "#${change?.str("change_number").orEmpty()} ${change?.str("name").orEmpty()}"
                Column {
                    LinkText(label, { ctx.nav.go("changes/${item.str("change_id")}") }, bold = true)
                    if (item.str("wear_notes").isNotBlank()) MutedText("✎ ${item.str("wear_notes")}", maxLines = 3)
                }
            }
            if (scenes.isNotEmpty()) {
                ChipRow {
                    scenes.forEach { scene ->
                        val shoot = fmtDate(scene.long("shoot_date"))
                            .takeIf { it.isNotBlank() }
                            ?.let { " · $it" }
                            .orEmpty()
                        InkChip(
                            "${t("csync_sc")} ${scene.str("number")}$shoot",
                            active = false,
                            onClick = { ctx.nav.go("scenes/${scene.id}") },
                        )
                    }
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
    if (listOf(rentals, damages, missing, fittings).all { it.isEmpty() }) return
    SectionCard(Modifier.fillMaxWidth(), title = t("csync_records")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rentals.forEach { r ->
                RecordLine(
                    buildAnnotatedString {
                        append("🏷 ${t("csync_rental_from")} ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(r.rec("vendor")?.str("name").orEmpty())
                        }
                        append(" ${fmtDate(r.long("pickup_date"))} → ${fmtDate(r.long("return_date"))}")
                    },
                    r.str("status"),
                )
            }
            damages.forEach { d ->
                RecordLine(
                    AnnotatedString("⚠️ ${fmtDate(d.long("created"))} ${d.str("description")}"),
                    d.str("status"),
                )
            }
            missing.forEach { m ->
                RecordLine(
                    AnnotatedString(
                        "🔎 ${t("csync_missing_since")} ${fmtDateTime(m.long("created"))} · " +
                        "${t("csync_last_seen")} ${m.str("last_seen_location")}",
                    ),
                    m.str("status"),
                )
            }
            fittings.forEach { f ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LinkText(
                        "📏 ${t("csync_fitting")} ${fmtDate(f.rec("fitting")?.long("scheduled_at"))}",
                        { ctx.nav.go("fittings/${f.str("fitting_id")}") },
                        style = recordStyle(),
                    )
                    StatusBadge(f.str("status"))
                    if (f.str("notes").isNotBlank()) ZillitText(" · ${f.str("notes")}", style = recordStyle())
                }
            }
        }
    }
}

@Composable
private fun recordStyle() = ZillitTheme.typography.bodySmall.copy(fontSize = 11.sp)

@Composable
private fun RecordLine(text: AnnotatedString, status: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        ZillitText(text, style = recordStyle())
        StatusBadge(status)
    }
}
