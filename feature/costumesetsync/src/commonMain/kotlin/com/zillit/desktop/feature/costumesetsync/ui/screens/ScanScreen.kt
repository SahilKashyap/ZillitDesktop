package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SyncHost
import com.zillit.desktop.feature.costumesetsync.domain.decodeQrText
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.IMAGE_EXTENSIONS
import com.zillit.desktop.feature.costumesetsync.ui.InkButton
import com.zillit.desktop.feature.costumesetsync.ui.LinkText
import com.zillit.desktop.feature.costumesetsync.ui.Load
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.Notice
import com.zillit.desktop.feature.costumesetsync.ui.NoticeTone
import com.zillit.desktop.feature.costumesetsync.ui.Page
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.rememberRows
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val RECENT_LIMIT = 8
private val LEFT_WIDTH = 360.dp
private val LABEL_WIDTH = 110.dp
private val FILL = Modifier.fillMaxWidth()

/** Pictures a label can be read from; the phone's HEIC is left out as nothing here decodes it. */
private val LABEL_PICTURES = IMAGE_EXTENSIONS - setOf("heic", "heif")

/** What the screen is showing: the typed number, the piece found, the recent ones and the context for actions. */
@Stable
private class ScanState {
    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
    var note by mutableStateOf("")
    var costume by mutableStateOf<Rec?>(null)
    var sceneId by mutableStateOf("")
    var take by mutableStateOf("")
    val recent = mutableStateListOf<Rec>()

    /** `GET /costumes/lookup/{asset}`; a miss clears the card and says why. */
    suspend fun lookup(ctx: SyncCtx, raw: String) {
        val asset = raw.trim().uppercase()
        if (asset.isEmpty()) return
        busy = true
        note = ""
        when (val answer = ctx.api.get("/costumes/lookup/$asset")) {
            is ZillitResult.Success -> answer.data.rec?.let(::found)
            is ZillitResult.Failure -> {
                costume = null
                note = t("csync_scan_not_found_title")
            }
        }
        busy = false
    }

    private fun found(piece: Rec) {
        costume = piece
        recent.removeAll { it.id == piece.id }
        recent.add(0, piece)
        while (recent.size > RECENT_LIMIT) recent.removeAt(recent.lastIndex)
        input = ""
    }

    /** A picture (from the camera window or a file) → the label's text → the lookup. */
    suspend fun readLabel(ctx: SyncCtx, bytes: ByteArray) {
        val text = withContext(Dispatchers.Default) { decodeQrText(bytes) }
        if (text == null) note = t("csync_scan_no_code") else lookup(ctx, text)
    }
}

/**
 * Scan costume (the web's `ScanScreen`): look a piece up by its asset number and act on it on the spot.
 * "Context for actions" (scene + take) prefills every action's form; it opens on the scene being shot today.
 * `?emergency=1` (the Dashboard's Emergency) says what to do. Switched off while `SCAN_ENABLED` is false, as the web
 * switches off its own (see `domain/Scan.kt`): nothing links here, but the route stands.
 *
 * The web's Camera runs a live `BarcodeDetector` over a video; the desktop has none, so Camera takes one picture
 * through the host's camera window and reads the QR from it, and "Read from a picture" does the same from a file.
 * Typing the number printed under the code always works.
 */
@Composable
fun ScanScreen() {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val state = remember { ScanState() }
    val scenes = rememberRows { api.get("/scenes") }
    val sceneRows = (scenes.state as? Load.Ready)?.value.orEmpty()

    // Opens on the scene being shot now, or today's.
    LaunchedEffect(sceneRows) {
        if (state.sceneId.isNotBlank() || sceneRows.isEmpty()) return@LaunchedEffect
        val today = todayParam(ctx.now())
        val shooting = sceneRows.firstOrNull { it.str("status") == "SHOOTING" }
            ?: sceneRows.firstOrNull { it.str("shoot_date").startsWith(today) }
        state.sceneId = shooting?.id.orEmpty()
    }

    Page {
        PageHead(
            title = t("csync_scan_costume"),
            sub = t("csync_scan_costume_sub"),
            actions = { ReadActions(ctx.host) { bytes -> scope.launch { state.readLabel(ctx, bytes) } } },
            bottomPadding = 0.dp,
        )
        Row(
            FILL,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.width(LEFT_WIDTH), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FindCard(state) { scope.launch { state.lookup(ctx, state.input) } }
                ContextCard(state, sceneRows)
                if (state.recent.isNotEmpty()) RecentScans(state.recent) { state.costume = it }
            }
            Column(Modifier.weight(1f)) {
                val shown = state.costume
                if (shown == null) {
                    NothingScanned(ctx.nav.current.arg("emergency") == "1")
                } else {
                    ScannedCostume(shown, state.sceneId, state.take) {
                        scope.launch { state.lookup(ctx, shown.str("asset_number")) }
                    }
                }
            }
        }
    }
}

/** Camera (when the host has one) and Read from a picture: the two ways to get a label's text without typing. */
@Composable
private fun ReadActions(host: SyncHost, onPicture: (ByteArray) -> Unit) {
    val scope = rememberCoroutineScope()
    if (host.hasCamera) {
        ZillitButton(
            t("csync_camera"),
            onClick = { scope.launch { host.capturePhoto()?.let { onPicture(it.bytes) } } },
            variant = ButtonVariant.Secondary,
            leadingIcon = AhIcons.Camera,
        )
    }
    ZillitButton(
        t("csync_scan_from_picture"),
        onClick = { scope.launch { host.pick(LABEL_PICTURES, false).firstOrNull()?.let { onPicture(it.bytes) } } },
        variant = ButtonVariant.Secondary,
        leadingIcon = ZillitIcons.Photo,
    )
}

@Composable
private fun FindCard(state: ScanState, onFind: () -> Unit) {
    SectionCard {
        Row(
            FILL,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextInput(
                state.input,
                { state.input = it },
                t("csync_asset_number"),
                Modifier.weight(1f),
                placeholder = "CST-000245",
            )
            InkButton(t("csync_scan_find"), onClick = onFind, enabled = state.input.isNotBlank() && !state.busy)
        }
        if (state.note.isNotBlank()) {
            Notice(Modifier.padding(top = 10.dp), tone = NoticeTone.Warn) {
                ZillitText(state.note, style = ZillitTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun ContextCard(state: ScanState, scenes: List<Rec>) {
    val options = listOf("" to "—") + scenes.map { scene ->
        val name = scene.str("name").let { if (it.isBlank()) "" else " · $it" }
        scene.id to "${t("csync_sc")} ${scene.str("number")}$name"
    }
    SectionCard(title = t("csync_context_for_actions")) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PickInput(state.sceneId, options, { state.sceneId = it }, t("csync_field_scene"), FILL)
            TextInput(
                state.take,
                { state.take = it.filter(Char::isDigit) },
                t("csync_field_take"),
                FILL,
                number = true,
            )
        }
    }
}

@Composable
private fun NothingScanned(emergencyMode: Boolean) {
    SectionCard {
        EmptyState(t("csync_scan_empty_title"), t("csync_scan_empty_hint")) {
            if (emergencyMode) {
                Notice(tone = NoticeTone.Info) {
                    ZillitText(t("csync_emergency_mode_hint"), style = ZillitTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun RecentScans(recent: List<Rec>, onOpen: (Rec) -> Unit) {
    SectionCard(title = t("csync_scan_recent"), flush = true) {
        recent.forEach { piece ->
            Row(
                FILL.padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LinkText(piece.str("asset_number"), { onOpen(piece) }, bold = true)
                ZillitText(
                    piece.str("name"),
                    Modifier.weight(1f),
                    style = ZillitTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                StatusBadge(piece.str("status"))
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ScannedCostume(c: Rec, sceneId: String, take: String, onChanged: () -> Unit) {
    val ctx = LocalSync.current
    val openCleaning = c.recs("cleaning").firstOrNull()
    val openAlteration = c.recs("alterations").firstOrNull()
    SectionCard {
        ScannedHead(c)
        ScannedFacts(c, openCleaning, openAlteration)
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CostumeActions(
                c,
                onChanged = onChanged,
                go = ctx.nav::go,
                sceneId = sceneId,
                takeNumber = take,
                openCleaningId = openCleaning?.id.orEmpty(),
                openAlterationId = openAlteration?.id.orEmpty(),
            )
            LinkText("${t("csync_full_details_timeline")} →", { ctx.nav.go("costumes/${c.id}") })
        }
    }
}

@Composable
private fun ScannedHead(c: Rec) {
    val traits = listOfNotNull(
        c.str("type").ifBlank { null },
        c.str("color").ifBlank { null },
        c.str("size").ifBlank { null }?.let { "${t("csync_field_size")} $it" },
        c.str("brand").ifBlank { null },
    ).joinToString(" · ")
    val bigAsset = ZillitTheme.typography.titleLarge.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    )
    Row(FILL, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            ZillitText(c.str("asset_number"), style = bigAsset)
            ZillitText(
                c.str("name"),
                style = ZillitTheme.typography.titleMedium.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
            )
            MutedText(traits, maxLines = 2)
        }
        StatusBadge(c.str("status"), large = true)
    }
}

/** Where it is, who it is for, the changes and scenes it is in, and any open cleaning or alteration. */
@Composable
private fun ScannedFacts(c: Rec, cleaning: Rec?, alteration: Rec?) {
    val ctx = LocalSync.current
    val body = ZillitTheme.typography.bodyLarge
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Fact(t("csync_field_location")) {
            ZillitText(c.str("location").ifBlank { "—" }, style = body.copy(fontWeight = FontWeight.Bold))
        }
        Fact(t("csync_field_character")) {
            val character = c.rec("character")
            if (character == null) {
                ZillitText("—")
            } else {
                LinkText(character.str("name"), { ctx.nav.go("characters/${character.id}") })
            }
        }
        Fact(t("csync_character_changes")) {
            val changes = c.recs("change_items").mapNotNull { it.rec("change") }
            if (changes.isEmpty()) ZillitText("—")
            changes.forEach { change ->
                val label = "#${change.str("change_number")} ${change.str("name")}"
                LinkText(label, { ctx.nav.go("changes/${change.id}") })
            }
        }
        Fact(t("csync_character_scenes")) {
            val scenes = c.recs("scenes")
            if (scenes.isEmpty()) ZillitText("—")
            scenes.forEach { scene ->
                val day = scene.long("shoot_date").takeIf { it != 0L }?.let { " · ${fmtDate(it)}" }.orEmpty()
                LinkText("${t("csync_sc")} ${scene.str("number")}$day", { ctx.nav.go("scenes/${scene.id}") })
            }
        }
        cleaning?.let { ticket ->
            Fact(t("csync_nav_cleaning")) {
                val label = "${ticket.str("problem")} · ${tEnum(ticket.str("status"))}"
                LinkText(label, { ctx.nav.go("cleaning/${ticket.id}") })
            }
        }
        alteration?.let { ticket ->
            Fact(t("csync_act_alteration")) {
                val work = "${ticket.str("issue")} → ${ticket.str("required_work")} · ${tEnum(ticket.str("status"))}"
                ZillitText(work, style = body)
            }
        }
    }
}

@Composable
private fun Fact(label: String, content: @Composable () -> Unit) {
    Row(FILL, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        ZillitText(
            label,
            Modifier.width(LABEL_WIDTH),
            style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp),
            color = ZillitTheme.colors.textMuted,
        )
        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}
