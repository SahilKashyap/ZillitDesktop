package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.data.SyncEvents
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtDate
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.humanize
import com.zillit.desktop.feature.costumesetsync.ui.Await
import com.zillit.desktop.feature.costumesetsync.ui.ChipRow
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FieldGrid
import com.zillit.desktop.feature.costumesetsync.ui.FieldRow
import com.zillit.desktop.feature.costumesetsync.ui.ListRow
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PageHead
import com.zillit.desktop.feature.costumesetsync.ui.ReferenceGrid
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.SocketRefresh
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val ReferenceKinds = listOf("REFERENCE", "FRONT", "SIDE", "BACK", "DETAIL", "DOCUMENT", "OTHER")
private const val SCENE_PANE_WEIGHT = 1f
private const val MAIN_PANE_WEIGHT = 1.6f

/** Where in the character's address the screen is: `characters/<id>`, `…/scenes/<sceneId>` or `…/all`. */
internal data class CharacterView(val sceneId: String, val all: Boolean, val viaRow: Boolean)

/** One scene opens the page on details, several open on "Pick a scene" — unless `…/all` or a scene was chosen. */
internal fun shouldPickScene(view: CharacterView, sceneCount: Int): Boolean = view.sceneId.isEmpty() && !view.all && sceneCount > 1

/** Arriving from a breakdown row, that one scene is the point of the visit, so the others are left out. */
internal fun hidesOtherScenes(view: CharacterView, sceneFound: Boolean): Boolean = sceneFound && view.viaRow

/**
 * One character (the web's `CharacterDetailScreen`). In several scenes it opens on "Pick a scene" — which
 * scene are you dressing? `characters/<id>/scenes/<sceneId>` is the character in that scene;
 * `characters/<id>/all` goes past the picker to the whole character; `?via=row` hides the other scenes.
 */
@Composable
fun CharacterDetailScreen(id: String) {
    val ctx = LocalSync.current
    val route = ctx.nav.current
    val view = CharacterView(
        sceneId = if (route.segments.getOrNull(2) == "scenes") route.segments.getOrNull(3).orEmpty() else "",
        all = route.segments.getOrNull(2) == "all",
        viaRow = route.arg("via") == "row",
    )
    val character = rememberResource(id) { api.get("/characters/$id") }
    val actors = rememberResource { api.get("/actors").mapRows() }
    SocketRefresh(SyncEvents.Character + SyncEvents.Change + SyncEvents.Costume + SyncEvents.Fitting) { character.reload(silent = true) }
    Await(character) { answer ->
        val ch = answer.rec ?: return@Await EmptyState(t("csync_character_not_found"))
        if (shouldPickScene(view, ch.recs("scenes").size)) {
            PickScene(ch, view)
        } else {
            CharacterBody(ch, view, actors.value.orEmpty(), reload = { character.reload(silent = true) })
        }
    }
}

@Composable
private fun PlayedBy(ch: Rec): String {
    val actor = ch.rec("actor")?.str("name")
    val age = ch.str("age").takeIf { it.isNotEmpty() && it != "0" }?.let { " · ${t("csync_age_lower")} $it" }.orEmpty()
    return (if (actor.isNullOrEmpty()) t("csync_no_actor_assigned") else "${t("csync_played_by")} $actor") + age
}

private fun titleOf(ch: Rec): String = (if (ch.has("cast_number")) "${ch.str("cast_number")}. " else "") + ch.str("name")

@Composable
private fun Crumbs(ch: Rec, tail: String? = null, onName: (() -> Unit)? = null) {
    val ctx = LocalSync.current
    Row(Modifier.padding(bottom = ZillitTheme.spacing.sm), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
        CastLink(t("csync_nav_characters"), { ctx.nav.go("characters") })
        ZillitText("/", color = ZillitTheme.colors.textMuted)
        if (onName != null) CastLink(ch.str("name"), onName) else ZillitText(ch.str("name"), maxLines = 1)
        if (tail != null) {
            ZillitText("/", color = ZillitTheme.colors.textMuted)
            ZillitText(tail, maxLines = 1)
        }
    }
}

@Composable
private fun SceneRow(entry: Rec, selected: Boolean, characterId: String) {
    val ctx = LocalSync.current
    val scene = entry.rec("scene")
    val change = entry.rec("change")
    ListRow(
        onClick = { ctx.nav.go("characters/$characterId/scenes/${scene?.id.orEmpty()}") },
        modifier = if (selected) Modifier.background(ZillitTheme.colors.surfaceSelected) else Modifier,
        leading = { RowBadge(scene?.str("number").orEmpty()) },
        end = { MutedText(fmtDate(scene?.long("shoot_date"))) },
    ) {
        RowTitle(scene?.str("name").orEmpty().ifEmpty { "${t("csync_scene")} ${scene?.str("number").orEmpty()}" })
        MutedText(
            if (change != null) "${t("csync_change")} #${change.str("change_number")} ${change.str("name")}" else t("csync_no_change_assigned"),
        )
    }
}

@Composable
private fun PickScene(ch: Rec, view: CharacterView) {
    val scenes = ch.recs("scenes")
    Crumbs(ch)
    PageHead(
        title = titleOf(ch),
        sub = "${PlayedBy(ch)} · ${t("csync_in_n_scenes", "n" to scenes.size)}",
        actions = { StatusBadge(ch.str("type"), tEnum(ch.str("type"))) },
    )
    SectionCard(title = t("csync_pick_a_scene"), flush = true) {
        scenes.forEach { s -> SceneRow(s, s.rec("scene")?.id == view.sceneId, ch.id) }
    }
}

@Composable
private fun CharacterBody(ch: Rec, view: CharacterView, actors: List<Rec>, reload: () -> Unit) {
    val ctx = LocalSync.current
    var editOpen by remember { mutableStateOf(false) }
    val scenes = ch.recs("scenes")
    val viaScene = scenes.firstOrNull { it.rec("scene")?.id == view.sceneId }
    PageHeader(ch, scenes, viaScene) { editOpen = true }
    viaScene?.let { SceneNote(it) }
    val hideScenes = hidesOtherScenes(view, viaScene != null)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg), verticalAlignment = Alignment.Top) {
        if (!hideScenes) {
            Column(Modifier.weight(SCENE_PANE_WEIGHT)) {
                SectionCard(title = t("csync_list_of_scenes"), flush = true) {
                    if (scenes.isEmpty()) {
                        EmptyState(t("csync_not_in_any_scene_yet"))
                    } else {
                        scenes.forEach { s -> SceneRow(s, s.rec("scene")?.id == view.sceneId, ch.id) }
                    }
                }
            }
        }
        Column(Modifier.weight(MAIN_PANE_WEIGHT), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
            ActorCard(ch, reload)
            ReferenceGrid(entityType = "CHARACTER", entityId = ch.id, title = t("csync_references"), kinds = ReferenceKinds)
            FittingsCard(ch, reload)
            ChangesCard(ch, reload)
            PiecesCard(ch, reload)
            MoreDetailsCard(ch, reload)
            if (ch.str("notes").isNotEmpty()) SectionCard(title = t("csync_field_notes")) { ZillitText(ch.str("notes")) }
        }
    }
    if (ctx.canPost) EditCharacterDialog(editOpen, { editOpen = false }, ch, actors, reload)
}

@Composable
private fun PageHeader(ch: Rec, scenes: List<Rec>, viaScene: Rec?, onEdit: () -> Unit) {
    val ctx = LocalSync.current
    if (scenes.size > 1) {
        Crumbs(
            ch,
            tail = viaScene?.rec("scene")?.let { "${t("csync_sc")} ${it.str("number")}" } ?: t("csync_all_scenes"),
            onName = { ctx.nav.go("characters/${ch.id}") },
        )
    } else {
        Crumbs(ch)
    }
    PageHead(
        title = titleOf(ch),
        sub = PlayedBy(ch) + ch.str("description").let { if (it.isEmpty()) "" else " · $it" },
        actions = {
            StatusBadge(ch.str("type"), tEnum(ch.str("type")))
            if (ctx.canPost) ZillitButton(t("csync_edit"), onClick = onEdit, variant = ButtonVariant.Secondary, leadingIcon = ZillitIcons.Edit)
        },
    )
    if (ctx.canPost) MutedText(t("csync_edit_character_hint"), Modifier.padding(bottom = ZillitTheme.spacing.sm), maxLines = 2)
}

@Composable
private fun SceneNote(entry: Rec) {
    val scene = entry.rec("scene")
    val change = entry.rec("change")
    SectionCard(modifier = Modifier.padding(bottom = ZillitTheme.spacing.md)) {
        ZillitText("${t("csync_scene")} ${scene?.str("number").orEmpty()} · ${scene?.str("name").orEmpty().ifEmpty { t("csync_untitled") }}")
        MutedText(
            if (change != null) {
                t("csync_wears_change", "n" to change.str("change_number"), "name" to change.str("name"))
            } else {
                t("csync_no_change_for_scene")
            },
        )
    }
}

@Composable
private fun ActorCard(ch: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    var actorOpen by remember { mutableStateOf(false) }
    val actor = ch.rec("actor")
    SectionCard(title = t("csync_actor_measurements")) {
        if (actor != null) {
            ZillitText(actor.str("name"), style = ZillitTheme.typography.titleSmall)
            val contact = listOf(actor.str("phone"), actor.str("agency")).filter { it.isNotEmpty() }.joinToString(" · ")
            if (contact.isNotEmpty()) MutedText(contact)
            val m = actor.rec("measurements")
            if (m != null && m.keys.isNotEmpty()) {
                FieldGrid { m.keys.forEach { FieldRow(humanize(it), m.str(it)) } }
            } else {
                MutedText(t("csync_no_measurements"))
            }
            // Amber, as the reference's notice: the thing to remember at the fitting.
            if (actor.str("notes").isNotEmpty()) {
                ZillitText(
                    actor.str("notes"),
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(ZillitTheme.colors.warningSoft).padding(ZillitTheme.spacing.sm),
                )
            }
        } else {
            MutedText(t("csync_no_actor_assigned"))
            if (ctx.canPost) {
                ZillitButton(t("csync_add_actor"), onClick = { actorOpen = true }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
            }
        }
    }
    // An actor created from this card is cast in the part on the spot — that is why it was opened.
    ActorFormDialog(
        open = actorOpen,
        onClose = { actorOpen = false },
        forCharacter = ch,
        saveLabel = t("csync_create_and_cast"),
        allowAddAnother = false,
        onSaved = { created ->
            ctx.scope.launch {
                if (created.id.isNotEmpty() && created.id != ch.str("actor_id")) {
                    ctx.write { ctx.api.patch("/characters/${ch.id}", castBody("actor_id" to created.id)) }
                }
                reload()
            }
        },
    )
}

@Composable
private fun FittingsCard(ch: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    var open by remember { mutableStateOf(false) }
    val fittings = ch.recs("fittings")
    SectionCard(title = t("csync_nav_fittings"), flush = true) {
        if (fittings.isEmpty()) {
            Column(Modifier.padding(ZillitTheme.spacing.lg), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                MutedText(t("csync_no_fittings"))
                if (ctx.canPost) {
                    ZillitButton(t("csync_schedule_fitting"), onClick = { open = true }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
                }
            }
        } else {
            fittings.forEach { f ->
                val count = f.recs("items").size
                ListRow(onClick = { ctx.nav.go("fittings/${f.id}") }) {
                    RowTitle(fmtDateTime(f.long("scheduled_at")))
                    MutedText(
                        t(if (count == 1) "csync_n_pieces_one" else "csync_n_pieces", "n" to count) +
                            f.str("location").let { if (it.isEmpty()) "" else " · $it" },
                    )
                }
            }
        }
    }
    if (ctx.canPost) CharacterFittingDialog(open, { open = false }, ch, reload)
}

@Composable
private fun ChangesCard(ch: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    var open by remember { mutableStateOf(false) }
    val changes = ch.recs("changes")
    val costumes = ch.recs("costumes")
    SectionCard(
        title = "${t("csync_costume_changes")} (${changes.size})",
        actions = {
            if (ctx.canPost) {
                ZillitButton(t("csync_add_change"), onClick = { open = true }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
            }
        },
        flush = changes.isNotEmpty(),
    ) {
        if (changes.isEmpty()) {
            EmptyState(t("csync_no_changes_yet"), t("csync_no_changes_yet_hint"))
        }
        changes.forEach { c ->
            val scenes = c.rec("counts")?.long("scene_characters") ?: 0L
            val items = c.recs("items")
            ListRow(onClick = { ctx.nav.go("changes/${c.id}") }, end = { MutedText(t(if (scenes == 1L) "csync_n_scenes_one" else "csync_n_scenes", "n" to scenes)) }) {
                RowTitle("${t("csync_change")} #${c.str("change_number")} · ${c.str("name")}")
                if (c.str("description").isNotEmpty()) MutedText(c.str("description"), maxLines = 2)
                ChipRow {
                    items.forEach { it ->
                        val costume = it.rec("costume")
                        val name = costume?.str("name").orEmpty().ifEmpty { costumes.firstOrNull { x -> x.id == it.str("costume_id") }?.str("name").orEmpty() }
                        StatusBadge(costume?.str("status"), "${costume?.str("asset_number").orEmpty()} $name".trim())
                    }
                    if (items.isEmpty()) MutedText(t("csync_no_pieces_attached"))
                }
            }
        }
    }
    if (ctx.canPost) NewChangeDialog(open, { open = false }, ch, reload)
}

@Composable
private fun PiecesCard(ch: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    var picking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Rec?>(null) }
    val costumes = ch.recs("costumes")
    val tag = { c: Rec ->
        val owner = c.rec("character")?.takeIf { it.id.isNotEmpty() && it.id != ch.id }?.str("name")
        if (owner == null) {
            ctx.launchWrite({ ctx.api.patch("/costumes/${c.id}", castBody("character_id" to ch.id)) }) { reload() }
        } else {
            moving = c
        }
    }
    SectionCard(
        title = "${t("csync_all_pieces")} (${costumes.size})",
        actions = {
            if (ctx.canPost) {
                ZillitButton(t("csync_pick_existing"), onClick = { picking = true }, variant = ButtonVariant.Secondary, size = ButtonSize.Small)
                ZillitButton(t("csync_add_piece"), onClick = { creating = true }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
            }
        },
        flush = true,
    ) {
        if (costumes.isEmpty()) EmptyState(t("csync_no_costumes_for_character"))
        costumes.forEach { c -> CostumeRow(c, noStatus = true, onClick = { ctx.nav.go("costumes/${c.id}") }) }
    }
    if (ctx.canPost) {
        // Tagging offers pieces not yet this character's.
        CostumePickerDialog(
            open = picking,
            onClose = { picking = false },
            onPick = tag,
            title = t("csync_pick_piece_for", "name" to ch.str("name")),
            exclude = { it.str("character_id") == ch.id },
        )
        CostumeFormDialog(open = creating, onClose = { creating = false }, defaultCharacterId = ch.id, onCreated = { reload() })
    }
    val piece = moving
    CastConfirm(
        open = piece != null,
        title = t("csync_move_piece_title"),
        text = t("csync_move_piece_body")
            .replace("{piece}", "${piece?.str("asset_number").orEmpty()} ${piece?.str("name").orEmpty()}")
            .replace("{owner}", piece?.rec("character")?.str("name").orEmpty())
            .replace("{name}", ch.str("name")),
        confirmLabel = t("csync_move_piece"),
        onConfirm = {
            val id = piece?.id.orEmpty()
            moving = null
            ctx.launchWrite({ ctx.api.patch("/costumes/$id", castBody("character_id" to ch.id)) }) { reload() }
        },
        onDismiss = { moving = null },
    )
}

@Composable
private fun MoreDetailsCard(ch: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    // -2 closed, -1 adding, otherwise the index being edited.
    var at by remember { mutableStateOf(NO_DETAIL) }
    var deleting by remember { mutableStateOf(NO_DETAIL) }
    var busy by remember { mutableStateOf(false) }
    val details = ch.recs("details")
    val save = { next: List<Pair<String, String>>, done: () -> Unit ->
        busy = true
        ctx.scope.launch {
            val list = JsonArray(next.map { (l, v) -> buildJsonObject { put("label", JsonPrimitive(l)); put("value", JsonPrimitive(v)) } })
            val answer = ctx.write { ctx.api.patch("/characters/${ch.id}", buildJsonObject { put("details", list) }) }
            busy = false
            if (answer != null) {
                done()
                reload()
            }
        }
        Unit
    }
    val pairs = details.map { it.str("label") to it.str("value") }
    SectionCard(
        title = t("csync_more_details"),
        actions = {
            if (ctx.canPost) {
                ZillitButton(t("csync_add_more"), onClick = { at = ADD_DETAIL }, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
            }
        },
        flush = true,
    ) {
        if (details.isEmpty()) {
            MutedText(t("csync_more_details_empty"), Modifier.padding(ZillitTheme.spacing.lg), maxLines = 2)
        }
        details.forEachIndexed { i, d ->
            ListRow(
                onClick = null,
                end = {
                    if (ctx.canPost) {
                        ZillitButton(t("csync_edit"), onClick = { at = i }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Edit)
                        ZillitButton(t("csync_delete"), onClick = { deleting = i }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Trash)
                    }
                },
            ) {
                RowTitle(d.str("label"))
                MutedText(d.str("value"), maxLines = DETAIL_LINES)
            }
        }
    }
    DetailRowDialog(
        open = at != NO_DETAIL,
        onClose = { at = NO_DETAIL },
        initial = details.getOrNull(at),
        busy = busy,
        onSave = { label, value ->
            val row = label to value
            save(if (at == ADD_DETAIL) pairs + row else pairs.mapIndexed { i, p -> if (i == at) row else p }) { at = NO_DETAIL }
        },
    )
    CastConfirm(
        open = deleting != NO_DETAIL,
        title = t("csync_delete_detail_confirm"),
        text = details.getOrNull(deleting)?.str("label").orEmpty(),
        confirmLabel = t("csync_delete"),
        onConfirm = { val gone = deleting; deleting = NO_DETAIL; save(pairs.filterIndexed { i, _ -> i != gone }) {} },
        onDismiss = { deleting = NO_DETAIL },
    )
}

private const val NO_DETAIL = -2
private const val ADD_DETAIL = -1
private const val DETAIL_LINES = 6
