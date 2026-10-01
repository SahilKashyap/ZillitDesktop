package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.FlowRow
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitNotice
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.localization.localised
import com.zillit.desktop.core.localization.localisedMessage
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.ContinuityModel
import com.zillit.desktop.feature.costumesetsync.domain.DayKeys
import com.zillit.desktop.feature.costumesetsync.domain.Readiness
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.ButtonRow
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.MediaPicker
import com.zillit.desktop.feature.costumesetsync.ui.LoadingView
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.rememberRows
import com.zillit.desktop.feature.costumesetsync.ui.attachMedia
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** The take form's draft: one per open scene + character, so switching away starts a fresh one. */
@Stable
internal class TakeDraft {
    var takeNumber by mutableStateOf("1")
    var details by mutableStateOf(listOf<Pair<String, String>>())
    var accessories by mutableStateOf(listOf<Pair<String, Boolean>>())
    var notes by mutableStateOf("")
    var media by mutableStateOf(listOf<MediaEntry>())

    /** Edited by hand: a fresh read of the last take no longer overwrites it. */
    var touched by mutableStateOf(false)
    var saving by mutableStateOf(false)

    fun apply(fill: ContinuityModel.TakeFill) {
        takeNumber = fill.takeNumber
        details = fill.details
        accessories = fill.accessories
        notes = ""
    }

    inline fun edit(block: TakeDraft.() -> Unit) {
        touched = true
        block()
    }

    /** The `POST /continuity` body for this draft. */
    fun toBody(sceneId: String, characterId: String, changeId: String): JsonObject = body(
        "scene_id" to sceneId,
        "character_id" to characterId,
        "change_id" to changeId,
        "take_number" to (takeNumber.trim().toDoubleOrNull()?.toLong() ?: 1L),
        "notes" to JsonPrimitive(notes),
        "details" to buildJsonObject { details.filter { it.first.isNotBlank() }.forEach { (k, v) -> put(k.trim(), JsonPrimitive(v)) } },
        "accessories" to JsonArray(
            accessories.filter { it.first.isNotBlank() }.map { (name, present) ->
                buildJsonObject {
                    put("name", JsonPrimitive(name))
                    put("present", JsonPrimitive(present))
                }
            },
        ),
    )
}

/**
 * On set: the shooting day as wardrobe sees it — the scenes a call sheet put on
 * the date, who is in them and whether their pieces are ready — with the
 * record-take form under whichever scene is open.
 *
 * The open scene and character live in the route's query (`?sceneId=`,
 * `?characterId=`) as on the web, so the book and the scene pages can link
 * straight into a take form.
 *
 * "Scan a label" is not ported: the web itself hides it (`SCAN_ON_WEB`).
 */
@Composable
fun ContinuityOnSetScreen() {
    val ctx = LocalSync.current
    val route = ctx.nav.current
    val sceneId = route.arg("sceneId")
    val characterId = route.arg("characterId")
    val project = ctx.project.rec

    val scenes = rememberRows { api.get("/scenes") }
    val scene = rememberResource(sceneId) {
        if (sceneId.isEmpty()) ZillitResult.Success(Answer(null, null)) else api.get("/scenes/$sceneId")
    }
    val compare = rememberResource(sceneId, characterId) {
        if (sceneId.isEmpty() || characterId.isEmpty()) {
            ZillitResult.Success(Answer(null, null))
        } else {
            api.get("/continuity/compare", mapOf("sceneId" to sceneId, "characterId" to characterId))
        }
    }
    val refresh = {
        scenes.reload(silent = true)
        scene.reload(silent = true)
        compare.reload(silent = true)
    }
    SocketRefresh(SyncEvents.Scene + SyncEvents.Continuity) { refresh() }

    val sheetDay = DayKeys.of(project?.long("callsheet_date"))
    var picked by remember { mutableStateOf<String?>(null) }
    val today = todayParam(ctx.now())
    val day = picked ?: sheetDay.ifEmpty { today }
    val sceneRec = scene.value?.rec

    // A scene opened by a link may sit on another day: the board follows it in.
    LaunchedEffect(sceneRec?.id) {
        DayKeys.of(sceneRec?.long("shoot_date")).takeIf { it.isNotEmpty() }?.let { picked = it }
    }
    // A scene opened with nobody chosen yet: the first character in it.
    LaunchedEffect(sceneRec?.id, characterId) {
        if (sceneRec != null && characterId.isEmpty()) {
            sceneRec.recs("characters").firstOrNull()?.str("character_id")?.takeIf { it.isNotEmpty() }?.let { ctx.nav.setQuery("characterId", it) }
        }
    }

    val draft = remember(sceneId, characterId) { TakeDraft() }
    var discardOpen by remember { mutableStateOf(false) }
    fun closeScene() {
        ctx.nav.setQuery("sceneId", null)
        ctx.nav.setQuery("characterId", null)
    }
    fun openScene(s: Rec) {
        ctx.nav.setQuery("sceneId", s.id)
        ctx.nav.setQuery("characterId", s.recs("characters").firstOrNull()?.str("character_id"))
    }

    // The call sheet the day works from: View (once one exists) and Upload, ahead of the book link, as the web orders them.
    val sheetDocs = rememberProjectDocuments("CALLSHEET")
    var sheetUpload by remember { mutableStateOf(false) }
    var sheetView by remember { mutableStateOf(false) }
    var sheetRead by remember { mutableStateOf<Rec?>(null) }
    PageHead(
        title = t("csync_on_set"),
        sub = t("csync_on_set_sub"),
        actions = {
            if (sheetDocs.docs.isNotEmpty() || project?.rec("callsheet_document") != null) {
                ZillitButton(t("csync_view_callsheet"), onClick = { sheetView = true }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Eye)
            }
            if (ctx.canPost) {
                ZillitButton(t("csync_upload_callsheet"), onClick = { sheetUpload = true }, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.File)
            }
            ZillitButton("${t("csync_continuity_book")} →", onClick = { ctx.nav.go("continuity/book") }, variant = ButtonVariant.Secondary)
        },
    )
    ZillitText(
        t("csync_on_set_hint"),
        style = ZillitTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
        color = ZillitTheme.colors.danger,
        modifier = Modifier.padding(bottom = ZillitTheme.spacing.md),
    )

    Await(scenes) { all ->
        val sheetImport = rememberScheduleUpload(
            open = sheetUpload,
            kind = "CALLSHEET",
            docs = sheetDocs,
            breakdown = all,
            onApplied = { d ->
                d?.takeIf { it.isNotEmpty() }?.let { picked = it }
                refresh()
                sheetDocs.reload()
                ctx.changed()
            },
            onClose = { sheetUpload = false; sheetRead = null },
        )
        // Opened from the viewer's "Read this": start straight on that document, once per opening.
        LaunchedEffect(sheetUpload, sheetRead?.id) { sheetRead?.takeIf { sheetUpload }?.let { sheetImport.pickDoc(it) } }
        ScheduleUploadDialog(sheetUpload, sheetImport, sheetDocs) { sheetUpload = false; sheetRead = null }
        if (sheetView) {
            DocumentViewerDialog(
                open = true,
                kind = "CALLSHEET",
                docs = sheetDocs,
                scenes = all,
                onClose = { sheetView = false },
                onRead = { d -> sheetView = false; sheetRead = d; sheetUpload = true },
            )
        }
        val dayScenes = ContinuityModel.onDay(all, day)
        val onBoard = sceneRec != null && DayKeys.of(sceneRec.long("shoot_date")) == day && sceneRec.str("status") != "OMITTED"
        SectionCard(
            title = "${t("csync_on_set")} · ${DayKeys.short(day)}" + (project?.long("shooting_day")?.takeIf { it > 0 }?.let { " · " + t("csync_day_n", "n" to it) } ?: ""),
            // No visible label, as the web's DatePicker (its label is the aria-label only).
            actions = { com.zillit.desktop.core.designsystem.component.ZillitDateField(day, { picked = it.ifEmpty { today } }, Modifier.width(KIT_DAY_FIELD)) },
        ) {
            if (project?.long("callsheet_at")?.let { it > 0 } == true) CallSheetLine(project, sheetDay, day) { picked = sheetDay }
            if (dayScenes.isEmpty()) {
                EmptyState(t("csync_nothing_scheduled_day"), t("csync_nothing_scheduled_day_hint"))
            } else {
                DayStats(dayScenes)
                Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                    dayScenes.forEach { s ->
                        val open = sceneId == s.id
                        SceneBoardCard(s, open, characterId, onOpen = { cid -> ctx.nav.setQuery("sceneId", s.id); ctx.nav.setQuery("characterId", cid) }, onOpenFirst = { openScene(s) }) {
                            if (draft.touched) discardOpen = true else closeScene()
                        }
                        if (open) OpenScene(ctx, sceneRec, characterId, compare.value?.rec, draft, refresh)
                    }
                }
            }
        }
        if (sceneId.isEmpty()) {
            SectionCard(modifier = Modifier.padding(top = ZillitTheme.spacing.md)) {
                EmptyState(t("csync_click_scene_above"), t("csync_click_scene_above_hint"))
            }
        }
        // A scene with no shoot date never appears on a day board, so its form opens on its own.
        if (sceneId.isNotEmpty() && !onBoard) {
            Column(Modifier.padding(top = ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                ZillitButton(t("csync_close"), onClick = { if (draft.touched) discardOpen = true else closeScene() }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                OpenScene(ctx, sceneRec, characterId, compare.value?.rec, draft, refresh)
            }
        }
    }
    KitConfirm(
        open = discardOpen,
        title = t("csync_discard_changes_title"),
        body = t("csync_take_discard_body"),
        confirmLabel = t("csync_discard"),
        onConfirm = { discardOpen = false; closeScene() },
        onDismiss = { discardOpen = false },
    )
}

private val KIT_DAY_FIELD = KIT_DATE_WIDTH

@Composable
private fun CallSheetLine(project: Rec, sheetDay: String, day: String, onShow: () -> Unit) {
    val file = project.str("callsheet_file")
    Row(Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md), verticalAlignment = Alignment.CenterVertically) {
        MutedText(
            t("csync_callsheet") + (if (file.isNotBlank()) " $file" else "") + (if (sheetDay.isNotEmpty()) " · ${t("csync_callsheet_for")} ${DayKeys.short(sheetDay)}" else "") +
                " · " + t("csync_callsheet_uploaded_ago", "when" to fmtDateTime(project.long("callsheet_at"))) + " · " + t("csync_callsheet_stays"),
            maxLines = 2,
        )
        if (sheetDay.isNotEmpty() && sheetDay != day) {
            ZillitButton(t("csync_show_callsheet_day"), onClick = onShow, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        }
    }
}

/** "**3** scenes  **5** characters  **4** ready  (! 1 not ready)  Kitchen · Garden": the web's `.csync-daystats`. */
@Composable
internal fun DayStats(dayScenes: List<Rec>) {
    val rows = ContinuityModel.rowsOf(dayScenes)
    val notReady = rows.count { it.second.level != "READY" }
    val colors = ZillitTheme.colors
    val base = ZillitTheme.typography.bodyMedium
    @Composable
    fun stat(n: Int, label: String) {
        ZillitText(
            androidx.compose.ui.text.buildAnnotatedString {
                pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = colors.textPrimary))
                append(n.toString())
                pop()
                append(" $label")
            },
            style = base,
            color = colors.textSecondary,
        )
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = ZillitTheme.spacing.md),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        stat(dayScenes.size, t(if (dayScenes.size == 1) "csync_count_scene_one" else "csync_count_scenes"))
        stat(rows.size, t(if (rows.size == 1) "csync_count_character_one" else "csync_count_characters"))
        stat(rows.size - notReady, t("csync_count_ready"))
        if (notReady > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ZillitIcon(ZillitIcons.Warning, tint = colors.danger, size = 14.dp)
                ZillitText("$notReady " + t("csync_count_not_ready"), style = base, color = colors.danger)
            }
        }
        ContinuityModel.locations(dayScenes).takeIf { it.isNotEmpty() }?.let { ZillitText(it.joinToString(" · "), style = base, color = colors.textSecondary) }
    }
}

/** One scene on the board: its heading, its characters as readiness chips. */
@Composable
private fun SceneBoardCard(s: Rec, open: Boolean, characterId: String, onOpen: (String) -> Unit, onOpenFirst: () -> Unit, onClose: () -> Unit) {
    // The web's `.csync-scenecard`: a 10dp-radius box with a 12dp pad; the open scene's edge turns ink.
    val box = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(ZillitTheme.colors.surface, box)
            .border(1.dp, if (open) ZillitTheme.colors.textPrimary else ZillitTheme.colors.border, box)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            ZillitButton(
                "${t("csync_sc")} ${s.str("number")}" + s.str("name").takeIf { it.isNotBlank() }.let { if (it == null) "" else " · $it" },
                onClick = onOpenFirst,
                variant = ButtonVariant.Tertiary,
            )
            MutedText(listOf(s.str("int_ext"), s.str("location"), tEnum(s.str("time_of_day"))).filter { it.isNotBlank() }.joinToString(" · "), Modifier.weight(1f))
            if (open) ZillitButton(t("csync_close"), onClick = onClose, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
        }
        val chars = s.recs("characters")
        if (chars.isEmpty()) {
            MutedText(t("csync_no_characters_tagged"))
        } else {
            ChipRowOf(chars, open, characterId, onOpen)
        }
    }
}

@Composable
private fun ChipRowOf(chars: List<Rec>, open: Boolean, characterId: String, onOpen: (String) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        chars.forEach { c ->
            val r = ContinuityModel.readiness(c)
            val on = open && characterId == c.str("character_id")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ReadinessDot(r.level)
                ZillitChoiceChip(
                    label = c.rec("character")?.str("name").orEmpty() + " " + (if (r.total > 0) "${r.ready}/${r.total}" else t("csync_no_change_short")),
                    selected = on,
                    onClick = { onOpen(c.str("character_id")) },
                )
            }
        }
    }
}

/** The blockers as one line: "Red saree: cleaning · No change assigned". */
internal fun blockerLine(r: Readiness): String = r.blockers.joinToString(" · ") { b ->
    when (b.key) {
        "no_change" -> t("csync_no_change_assigned")
        "no_pieces" -> t("csync_change_no_pieces")
        else -> "${b.name.ifBlank { t("csync_piece") }}: ${tEnum(b.status).lowercase()}"
    }
}

/** What opens under a scene: who is selected, the continuity flags, the take form and the takes so far. */
@Composable
private fun OpenScene(ctx: SyncCtx, scene: Rec?, characterId: String, compare: Rec?, draft: TakeDraft, refresh: () -> Unit) {
    if (scene == null) {
        LoadingView()
        return
    }
    val sc = scene.recs("characters").firstOrNull { it.str("character_id") == characterId }
    val records = compare?.recs("records").orEmpty()
    val flags = compare?.recs("flags").orEmpty()
    Column(Modifier.fillMaxWidth().padding(vertical = ZillitTheme.spacing.md), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        if (sc != null) SelectedNotice(ctx, scene, sc)
        if (flags.isNotEmpty()) {
            ZillitNotice(
                text = t("csync_continuity_flags") + "\n" + flags.joinToString("\n") { "${t("csync_take")} ${it.str("take")}: ${it.str("message")}" },
                tone = StatusTone.Pending,
            )
        }
        when {
            characterId.isEmpty() -> NobodyInScene(ctx, scene)
            !ctx.canPost -> SectionCard { EmptyState(t("csync_view_only_book"), t("csync_view_only_book_hint")) }
            else -> {
                val last = records.lastOrNull()
                val fill = remember(last?.id, last?.long("take_number"), sc?.id) { ContinuityModel.fill(last, sc) }
                LaunchedEffect(fill) { if (!draft.touched) draft.apply(fill) }
                TakeForm(ctx, scene, sc, last, draft, refresh)
                TakesSoFar(ctx, scene, records, flags)
            }
        }
    }
}

@Composable
private fun SelectedNotice(ctx: SyncCtx, scene: Rec, sc: Rec) {
    val change = sc.rec("change")
    val pieces = change?.recs("items").orEmpty().joinToString(", ") { i ->
        i.rec("costume")?.str("name").orEmpty() + i.str("wear_notes").let { if (it.isBlank()) "" else " ($it)" }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        ZillitText("${sc.rec("character")?.str("name").orEmpty()} ${t("csync_in")} ${t("csync_sc")} ${scene.str("number")}:", style = ZillitTheme.typography.titleSmall)
        if (change != null) {
            ZillitButton(
                "${t("csync_change")} #${change.str("change_number")} ${change.str("name")}",
                onClick = { ctx.nav.go("changes/${change.id.ifBlank { sc.str("change_id") }}") },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        } else {
            MutedText(t("csync_no_change_assigned_lower"))
        }
        if (pieces.isNotEmpty()) MutedText("— $pieces", Modifier.weight(1f), maxLines = 2)
    }
}

@Composable
private fun NobodyInScene(ctx: SyncCtx, scene: Rec) {
    SectionCard(title = "${t("csync_sc")} ${scene.str("number")} · ${t("csync_nobody_in_scene")}") {
        MutedText(t("csync_nobody_in_scene_hint"), maxLines = 3)
        ZillitButton(t("csync_add_characters_to_scene"), onClick = { ctx.nav.go("scenes/${scene.id}") }, modifier = Modifier.padding(top = ZillitTheme.spacing.sm))
    }
}

/** The record-take form: take number, wear details, accessories, photos (seam), notes. */
@Composable
private fun TakeForm(ctx: SyncCtx, scene: Rec, sc: Rec?, last: Rec?, draft: TakeDraft, refresh: () -> Unit) {
    val characterName = sc?.rec("character")?.str("name").orEmpty()
    SectionCard(
        title = "${t("csync_record_take")} · $characterName · ${t("csync_sc")} ${scene.str("number")}",
        actions = {
            ZillitButton(
                if (draft.saving) t("csync_saving") else t("csync_save_take"),
                onClick = { saveTake(ctx, scene, sc, draft, refresh) },
                enabled = draft.takeNumber.isNotBlank() && !draft.saving,
                loading = draft.saving,
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            TextInput(draft.takeNumber, { v -> draft.edit { takeNumber = v } }, t("csync_take_number"), Modifier.width(KIT_FIELD_WIDTH), number = true)
            DetailRows(draft)
            if (last != null) MutedText(t("csync_prefilled_from_take", "n" to last.long("take_number")))
            AccessoryRows(draft)
            Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                ZillitText(t("csync_photos_videos"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
                MediaPicker(draft.media, { v -> draft.edit { media = v } }, enabled = !draft.saving, help = t("csync_photos_videos_hint"))
            }
            TextInput(draft.notes, { v -> draft.edit { notes = v } }, t("csync_field_notes"), Modifier.fillMaxWidth(), multiline = true)
        }
    }
}

@Composable
private fun DetailRows(draft: TakeDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(t("csync_wear_details"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        draft.details.forEachIndexed { i, (k, v) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitTextField(k, { n -> draft.edit { details = details.mapIndexed { j, p -> if (j == i) n to p.second else p } } }, Modifier.width(KIT_FIELD_WIDTH), placeholder = t("csync_detail"))
                ZillitTextField(v, { n -> draft.edit { details = details.mapIndexed { j, p -> if (j == i) p.first to n else p } } }, Modifier.weight(1f), placeholder = k.ifBlank { t("csync_value") })
                ZillitButton(t("csync_remove"), onClick = { draft.edit { details = details.filterIndexed { j, _ -> j != i } } }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            }
        }
        ButtonRow { ZillitButton(t("csync_detail"), onClick = { draft.edit { details = details + ("" to "") } }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add) }
    }
}

@Composable
private fun AccessoryRows(draft: TakeDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(t("csync_pieces_accessories"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        draft.accessories.forEachIndexed { i, (name, present) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                ZillitCheckbox(present, { on -> draft.edit { accessories = accessories.mapIndexed { j, p -> if (j == i) p.first to on else p } } })
                ZillitTextField(name, { n -> draft.edit { accessories = accessories.mapIndexed { j, p -> if (j == i) n to p.second else p } } }, Modifier.weight(1f))
                ZillitButton(t("csync_remove"), onClick = { draft.edit { accessories = accessories.filterIndexed { j, _ -> j != i } } }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            }
        }
        ButtonRow { ZillitButton(t("csync_accessory"), onClick = { draft.edit { accessories = accessories + ("" to true) } }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add) }
    }
}

private fun saveTake(ctx: SyncCtx, scene: Rec, sc: Rec?, draft: TakeDraft, refresh: () -> Unit) {
    draft.saving = true
    val payload = draft.toBody(scene.id, sc?.str("character_id").orEmpty(), sc?.str("change_id").orEmpty())
    ctx.scope.launch {
        // The take is saved first so its media has something to attach to; a failed upload never loses the take.
        when (val result = ctx.api.post("/continuity", payload)) {
            is ZillitResult.Failure -> ctx.toast(result.error.localised(), false)
            is ZillitResult.Success -> {
                val id = result.data.rec?.id.orEmpty()
                val failed = if (draft.media.isEmpty() || id.isEmpty()) 0 else ctx.attachMedia(draft.media, "CONTINUITY", id, kind = "OTHER")
                // One toast, as the web: the take is in, some of its media is not.
                if (failed > 0) {
                    ctx.changed()
                    ctx.toast(t(if (failed == 1) "csync_take_media_failed_one" else "csync_take_media_failed", "n" to failed), false)
                } else {
                    result.data.message?.takeIf { it.isNotBlank() }?.let { ctx.toast(it.localisedMessage(), true) }
                    ctx.changed()
                }
                draft.touched = false
                draft.media = emptyList()
                refresh()
            }
        }
        draft.saving = false
    }
}

/** Takes recorded for the open character, newest first; a row opens that day in the book. */
@Composable
private fun TakesSoFar(ctx: SyncCtx, scene: Rec, records: List<Rec>, flags: List<Rec>) {
    val shootDay = DayKeys.of(scene.long("shoot_date"))
    SectionCard(title = "${t("csync_takes_so_far")} (${records.size})", flush = true) {
        if (records.isEmpty()) {
            EmptyState(t("csync_no_takes_yet"), t("csync_no_takes_yet_hint"))
        } else {
            records.reversed().forEach { r ->
                val summary = r.rec("details")?.let { d -> d.keys.take(2).joinToString(" · ") { "$it: ${d.str(it)}" } }.orEmpty()
                ListRow(onClick = { ctx.nav.go("continuity/book?tab=shot" + if (shootDay.isNotEmpty()) "&day=$shootDay" else "") }, end = {
                    if (flags.any { it.long("take") == r.long("take_number") }) StatusBadge("WARNING", t("csync_flagged"))
                }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                        RowTitle("${t("csync_take")} ${r.long("take_number")}")
                        MutedText(summary.ifBlank { "—" })
                    }
                }
            }
        }
    }
}
