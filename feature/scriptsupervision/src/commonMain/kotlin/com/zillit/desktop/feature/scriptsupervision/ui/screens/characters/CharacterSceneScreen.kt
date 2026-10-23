package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.CharacterSection
import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.lookStatusTone
import com.zillit.desktop.feature.scriptsupervision.ui.AddPhotoRow
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoStrip
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty

/**
 * One scene from a character's side — the web's `CharacterSceneDetailScreen`.
 *
 * The scene itself, the previous and next scene in SCRIPT order (which is why
 * the whole project's scenes are sorted here, not just this character's), and
 * four cards: the character's looks in this scene, the continuity photos taken
 * of it, the colour-coded notes, and the props, vehicles and animals in it.
 */
@Composable
internal fun CharacterSceneScreen(id: String, sceneId: String) {
    val navState = nav()
    val castState = cast()
    val sceneState = scenes()
    val character = castState.character(id)
    val name = character?.str("name").orEmpty()
    // Script order, across every scene: "next" means the next scene of the
    // film, not the character's next one.
    val ordered = remember(sceneState.scenes) { sceneState.scenes.sortedWith(Characters.byNumber) }
    val at = ordered.indexOfFirst { it.id == sceneId }
    val scene = ordered.getOrNull(at)

    SsPageHeader(
        title = scene?.let { "${t("scene")} ${it.str("scene_number")}" } ?: t("scene"),
        sub = name,
        actions = {
            ZillitButton(
                text = t(CharacterSection.Scenes.labelKey),
                onClick = { navState.back() },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
        },
    )
    if (scene == null) {
        ZillitEmptyState(title = t("scene_not_found"), message = "")
        return
    }

    SceneStepper(
        previous = ordered.getOrNull(at - 1),
        next = ordered.getOrNull(at + 1),
        onGo = { navState.swap(SsView.CharacterScene(id, it.id)) },
    )
    SceneCard(scene = scene, onOpen = {})
    SceneLooksCard(id, scene)
    ScenePhotosCard(name, scene)
    SceneNotesCard(character, scene.id)
    SceneAssetsCard(scene.id)
}

/** Previous and next scene, each labelled with its own number — the web's `NavBar`. */
@Composable
private fun SceneStepper(previous: Rec?, next: Rec?, onGo: (Rec) -> Unit) {
    if (previous == null && next == null) {
        MutedText(t("navigate_scenes"))
        return
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(previous, ZillitIcons.ArrowLeft, onGo)
        StepButton(next, ZillitIcons.ArrowRight, onGo)
    }
}

@Composable
private fun StepButton(
    scene: Rec?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onGo: (Rec) -> Unit,
) {
    ZillitButton(
        text = scene?.let { "${t("scene")} ${it.str("scene_number")}" } ?: "—",
        onClick = { scene?.let(onGo) },
        variant = ButtonVariant.Secondary,
        leadingIcon = icon,
        enabled = scene != null,
    )
}

/** The character's looks that apply to this scene. */
@Composable
private fun SceneLooksCard(characterId: String, scene: Rec) {
    val ctx = LocalSs.current
    val looks = rememberRows(characterId) {
        ContinuityCrud(ctx.api, "looks").list(mapOf("character_id" to characterId))
    }
    ZillitSectionLabel(t("look_scenes"))
    Await(looks) { rows ->
        val here = rows.filter { scene.id in it.strings("scene_ids") }
        if (here.isEmpty()) {
            MutedText(t("no_scene_looks"))
        } else {
            here.forEach { look ->
                val status = look.str("status").ifBlank { "planned" }
                ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SsTag(t("dept_${look.str("department")}"))
                        ZillitText(
                            text = look.str("name").ifBlank { t("unnamed") },
                            style = ZillitTheme.typography.titleSmall,
                            modifier = Modifier.weight(1f),
                        )
                        SsTag(t("look_status_$status"), lookStatusTone(status))
                    }
                    Field(t("description"), look.str("description"))
                }
            }
        }
    }
}

/**
 * The continuity photos taken of this scene.
 *
 * ⚠ These are NOT under `/continuity/…` like everything else on this screen:
 * they are their own resource, `/continuity-photos/{projectId}`, listed by
 * `scene_id`. Shown here, and added by address; the camera upload needs a
 * file picker this module has no seam for yet.
 */
@Composable
private fun ScenePhotosCard(characterName: String, scene: Rec) {
    val ctx = LocalSs.current
    val project = ctx.api.project().orEmpty()
    val photos = rememberRows(scene.id, project) {
        api.get("/continuity-photos/$project", mapOf("scene_id" to scene.id))
    }
    ZillitSectionLabel(t("photos"))
    Await(photos) { rows ->
        if (rows.isEmpty()) {
            MutedText(t("no_scene_photos"))
        } else {
            PhotoStrip(rows.map { it.str("url") })
        }
        // Straight from a camera roll, or by address. Either way the record
        // is created here with this scene and this character on it.
        AddPhotoRow(
            folder = "continuity-photos",
            onAdded = { value -> saveScenePhoto(ctx, scene, characterName, value) { photos.reload(silent = true) } },
        )
    }
}

/**
 * Files one photo against this scene, with the character whose page it was
 * added from.
 *
 * [value] is a storage key when it was uploaded and an address when it was
 * pasted; the service takes either in `url`.
 */
private fun saveScenePhoto(
    ctx: SsCtx,
    scene: Rec,
    characterName: String,
    value: String,
    onSaved: () -> Unit,
) {
    ctx.launchWrite(
        {
            ctx.api.post(
                "/continuity-photos/${ctx.api.project().orEmpty()}",
                body(
                    "url" to value,
                    "scene_id" to scene.id,
                    // The number travels with the id so a photo list can name
                    // its scene without the scene record.
                    "scene_number" to scene.str("scene_number"),
                    "characters" to listOfNotNull(characterName.takeIf { it.isNotBlank() }),
                    "category" to "reference",
                ),
            )
        },
    ) { onSaved() }
}

/**
 * The scene's colour-coded continuity notes.
 *
 * The colour is the point: a script supervisor codes notes by what they are
 * about, and the ten swatches are the standalone product's own palette, which
 * the web transcribed rather than re-picked. A new note is signed with the
 * character whose page it was written from.
 */
@Composable
private fun SceneNotesCard(character: Rec?, sceneId: String) {
    val ctx = LocalSs.current
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "scene-notes") }
    val notes = rememberRows(sceneId) { crud.list(mapOf("scene_id" to sceneId)) }

    ZillitSectionLabel(t("notes"))
    Await(notes) { rows ->
        if (rows.isEmpty()) {
            MutedText(t("no_scene_notes"))
        } else {
            rows.forEach { note ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Swatch(note.str("color"), selected = false, onClick = null)
                    Column(Modifier.weight(1f)) {
                        ZillitText(note.str("text"), style = ZillitTheme.typography.bodySmall)
                        note.str("character_name").takeIf { it.isNotBlank() }?.let { MutedText(it) }
                    }
                    ZillitIconButton(
                        icon = ZillitIcons.Trash,
                        contentDescription = t("delete"),
                        onClick = { ctx.whenPost { deleting = note } },
                        tint = ZillitTheme.colors.danger,
                    )
                }
            }
        }
        ZillitButton(
            text = t("add_note"),
            onClick = { ctx.whenPost { adding = true } },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
    }
    if (adding) {
        AddNoteDialog(
            character = character,
            sceneId = sceneId,
            onClose = { adding = false },
            onSaved = { notes.reload(silent = true) },
        )
    }
    deleting?.let { note ->
        DeleteDialog(
            title = t("delete_note_title"),
            name = note.str("text"),
            onClose = { deleting = null },
            onDone = { notes.reload(silent = true) },
            run = { crud.remove(note.id) },
        )
    }
}

@Composable
private fun AddNoteDialog(character: Rec?, sceneId: String, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    var text by remember { mutableStateOf("") }
    var colour by remember { mutableStateOf(NOTE_COLOURS.first()) }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = t("add_note"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val done = ctx.write {
                    ContinuityCrud(ctx.api, "scene-notes").create(
                        body(
                            "scene_id" to sceneId,
                            "character_id" to character?.id.orEmpty(),
                            "character_name" to character?.str("name").orEmpty(),
                            "color" to colour,
                            "text" to text.trim(),
                        ).dropEmpty(),
                    )
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = text.isNotBlank(),
        busy = saving,
        width = SMALL_DIALOG,
    ) {
        FormGrid {
            TextInput(text, { text = it }, t("type_your_text"), FormWide, multiline = true)
        }
        ZillitText(t("color"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            NOTE_COLOURS.forEach { swatch ->
                Swatch(swatch, selected = swatch == colour, onClick = { colour = swatch })
            }
        }
    }
}

/** One of the ten note colours; [onClick] null for a note's own colour, which is not a control. */
@Composable
private fun Swatch(colour: String, selected: Boolean, onClick: (() -> Unit)?) {
    val shape = RoundedCornerShape(SWATCH_RADIUS)
    val fill = parseHex(colour) ?: ZillitTheme.colors.border
    Box(
        Modifier
            .size(if (onClick == null) DOT else SWATCH)
            .clip(shape)
            .background(fill)
            .let { if (selected) it.border(2.dp, ZillitTheme.colors.textPrimary, shape) else it }
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    )
}

/**
 * `#rrggbb` as a colour.
 *
 * The service stores the swatch as the standalone product wrote it, so this
 * reads hex rather than a palette name; anything else reads as null and the
 * swatch falls back to the theme's border, never to a crash.
 */
private fun parseHex(value: String): Color? {
    val hex = value.removePrefix("#").takeIf { it.length == HEX_LENGTH } ?: return null
    val rgb = hex.toLongOrNull(radix = HEX_RADIX) ?: return null
    return Color(rgb or OPAQUE)
}

/** The props, vehicles and animals in this scene. */
@Composable
private fun SceneAssetsCard(sceneId: String) {
    val ctx = LocalSs.current
    val castState = cast()
    var adding by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "scene-assets") }
    val assets = rememberRows(sceneId) { crud.list(mapOf("scene_id" to sceneId)) }

    ZillitSectionLabel(t("assets"))
    Await(assets) { rows ->
        if (rows.isEmpty()) MutedText(t("no_items_matching"))
        ASSET_CATEGORIES.forEach { category ->
            val mine = rows.filter { it.str("category") == category }
            if (mine.isNotEmpty()) {
                MutedText(t("asset_tab_$category"))
                mine.forEach { asset ->
                    AssetRow(asset, onDelete = { ctx.whenPost { deleting = asset } })
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ASSET_CATEGORIES.forEach { category ->
                ZillitButton(
                    text = t("create_noun_item", "noun" to t("asset_noun_$category")),
                    onClick = { ctx.whenPost { adding = category } },
                    variant = ButtonVariant.Secondary,
                    leadingIcon = ZillitIcons.Add,
                )
            }
        }
    }
    adding?.let { category ->
        AddAssetDialog(
            sceneId = sceneId,
            category = category,
            characters = castState.options,
            characterName = castState::characterName,
            onClose = { adding = null },
            onSaved = { assets.reload(silent = true) },
        )
    }
    deleting?.let { asset ->
        DeleteDialog(
            title = t("delete_item_title"),
            name = asset.str("name"),
            onClose = { deleting = null },
            onDone = { assets.reload(silent = true) },
            run = { crud.remove(asset.id) },
        )
    }
}

/** One prop, vehicle or animal in the scene. */
@Composable
private fun AssetRow(asset: Rec, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            ZillitText(asset.str("name"), style = ZillitTheme.typography.bodySmall)
            Field(t("description"), asset.str("description"))
        }
        asset.str("character_name").takeIf { it.isNotBlank() }?.let { SsTag(it) }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = t("delete"),
            onClick = onDelete,
            tint = ZillitTheme.colors.danger,
        )
    }
}

@Composable
private fun AddAssetDialog(
    sceneId: String,
    category: String,
    characters: List<Pair<String, String>>,
    characterName: (String) -> String,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSs.current
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var characterId by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = t("create_noun_item", "noun" to t("asset_noun_$category")),
        onDismiss = onClose,
        confirmLabel = t("create"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val done = ctx.write {
                    ContinuityCrud(ctx.api, "scene-assets").create(
                        body(
                            "scene_id" to sceneId,
                            "category" to category,
                            "name" to name.trim(),
                            "description" to description.trim(),
                            "character_id" to characterId,
                            "character_name" to characterName(characterId),
                        ).dropEmpty(),
                    )
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
        width = SMALL_DIALOG,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("name"), FormWide)
            PickInput(
                value = characterId,
                options = characters,
                onChange = { characterId = it },
                label = t("choose_character"),
                clearable = true,
            )
            TextInput(description, { description = it }, t("description"), FormWide, multiline = true)
        }
    }
}

/** The three kinds of thing a scene carries, in the web's order. */
private val ASSET_CATEGORIES = listOf("prop", "vehicle", "animal")

/**
 * The standalone product's ten note colours, transcribed.
 *
 * Not the theme's palette: a note's colour is DATA the service stores, and a
 * note written on the web has to read the same colour here.
 */
private val NOTE_COLOURS = listOf(
    "#64748b",
    "#2563eb",
    "#38bdf8",
    "#a855f7",
    "#ec4899",
    "#ef4444",
    "#f97316",
    "#f59e0b",
    "#84cc16",
    "#14b8a6",
)

private const val HEX_LENGTH = 6
private const val HEX_RADIX = 16

/** Full alpha, for a colour the service stores without one. */
private const val OPAQUE = 0xFF000000L

private val SWATCH = 32.dp
private val DOT = 12.dp
private val SWATCH_RADIUS = 8.dp
private val SMALL_DIALOG = 480.dp
