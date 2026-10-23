package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
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
import com.zillit.desktop.feature.scriptsupervision.domain.SectionGroup
import com.zillit.desktop.feature.scriptsupervision.ui.AddPhotoRow
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoAvatar
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoThumb
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A character's hub — the web's `CharacterDetailScreen`.
 *
 * One fetch, not several: `GET /dashboard/character/{projectId}/{characterId}`
 * answers the character AND the counts behind every tile, which is what keeps
 * a tile's number and the list it opens in step (the service counts a
 * character's scenes the same way [Characters.scenesFor] does).
 */
@Composable
internal fun CharacterDetailScreen(id: String, onOpen: (SsPage) -> Unit) {
    val ctx = LocalSs.current
    val navState = nav()
    val castState = cast()
    val project = ctx.api.project().orEmpty()
    val summary = rememberResource(id, project) {
        api.get("/dashboard/character/$project/$id")
    }
    val changed = {
        summary.reload(silent = true)
        // The switcher's photos and every picker read the shared list.
        castState.reload()
    }

    Await(summary) { answer ->
        val character = answer.rec?.rec("character")
        val counts = answer.rec?.rec("counts")
        if (character == null) {
            SsPageHeader(
                title = t("characters"),
                actions = { BackToCharacters(navState::root) },
            )
            ZillitEmptyState(title = t("character_not_found"), message = "")
            return@Await
        }
        SsPageHeader(
            title = character.str("name"),
            sub = t("characters"),
            actions = { BackToCharacters(navState::root) },
        )
        val others = castState.characters.filter { it.id != id }
        IdentityCard(character, others = others, onChanged = changed, onOpen = onOpen)

        // Where the character appears, then their looks, then the warehouse —
        // the web's three bands, in that order.
        SectionGroup.entries.forEach { group ->
            ZillitSectionLabel(t(group.labelKey))
            CardGrid(CharacterSection.entries.filter { it.group == group }, minWidth = TILE_MIN) { section ->
                CountTile(
                    section = section,
                    count = counts?.int(section.count) ?: 0,
                    onClick = { navState.go(SsView.Section(id, section)) },
                )
            }
        }

        ZillitSectionLabel(t("relationships"))
        Relationships(character, others = others, onChanged = changed)
    }
}

/** The band headings the character page uses over its tiles. */
private val SectionGroup.labelKey: String
    get() = when (this) {
        SectionGroup.Appearances -> "character_scenes"
        SectionGroup.Looks -> "department_looks_continuities"
        SectionGroup.Warehouse -> "warehouse_departments"
    }

@Composable
private fun BackToCharacters(onBack: () -> Unit) {
    ZillitButton(
        text = t("all_characters"),
        onClick = onBack,
        variant = ButtonVariant.Secondary,
        leadingIcon = ZillitIcons.ArrowLeft,
    )
}

/** A section's tile: its count, its name, and a line on what it holds. */
@Composable
private fun CountTile(section: CharacterSection, count: Int, onClick: () -> Unit) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ZillitText(
            text = "$count ${t(section.tileKey)}",
            style = ZillitTheme.typography.titleMedium,
        )
        MutedText(t("${section.tileKey}_help"), maxLines = TILE_HELP_LINES)
    }
}

/**
 * Who the character is, and their reference photos — the web's
 * `CharacterIdentityCard`.
 *
 * The photos are ONE FIELD on the character record, not records of their own,
 * so choosing a primary or removing one is a write of the whole array. The
 * first photo added is the primary one until another is picked, which is what
 * makes a character's avatar appear without anybody choosing anything.
 *
 * What the web's card has and this does not: the upload button and the
 * brightness/contrast/vignette photo editor. Both need a file picker and a
 * canvas, and this module has neither seam yet — adding a photo by URL, which
 * is a plain write, is here.
 */
@Suppress("LongMethod") // The character's heading, their tags, and the photo strip.
@Composable
private fun IdentityCard(character: Rec, others: List<Rec>, onChanged: () -> Unit, onOpen: (SsPage) -> Unit) {
    val ctx = LocalSs.current
    val navState = nav()
    val photos = character.recs("photos")
    val type = Characters.typeOf(character)
    var removing by remember { mutableStateOf(-1) }
    val write = { next: JsonArray ->
        ctx.launchWrite(
            { ContinuityCrud(ctx.api, "characters").update(character.id, body("photos" to next)) },
        ) { onChanged() }
    }

    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            PhotoAvatar(
                name = character.str("name"),
                photo = Characters.primaryPhoto(character),
                size = BIG_AVATAR,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(character.str("name"), style = ZillitTheme.typography.titleLarge)
                    character.str("number").takeIf { it.isNotBlank() }?.let { SsTag("#$it") }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                ) {
                    SsTag(
                        t("type_$type"),
                        if (type == "background") StatusTone.Neutral else StatusTone.Progress,
                    )
                    character.str("gender").takeIf { it.isNotBlank() }?.let { SsTag(t("gender_$it")) }
                    character.str("age").takeIf { it.isNotBlank() }?.let { SsTag("${t("age")}: $it") }
                    // The actor's own page, where their measurements are.
                    character.str("actor_name").takeIf { it.isNotBlank() }?.let { name ->
                        SsTag(
                            label = name,
                            tone = StatusTone.Done,
                            modifier = Modifier.clickable { onOpen(SsPage.Actors) },
                        )
                    }
                }
                Field(t("description"), character.str("description"))
            }
            // The next few characters, to move between them without going back.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            ) {
                others.take(SWITCHER).forEach { other ->
                    PhotoAvatar(
                        name = other.str("name"),
                        photo = Characters.primaryPhoto(other),
                        size = SWITCH_AVATAR,
                        modifier = Modifier.clickable { navState.swap(SsView.Character(other.id)) },
                    )
                }
                if (others.size > SWITCHER) SsTag("+${others.size - SWITCHER}")
            }
        }

        ZillitSectionLabel(t("reference_photos"))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            photos.forEachIndexed { index, photo ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PhotoThumb(photo.str("url"), width = REF_PHOTO, height = REF_PHOTO)
                    Row {
                        // The primary one needs no button to become primary.
                        if (!photo.bool("is_primary")) {
                            ZillitIconButton(
                                icon = ZillitIcons.StarOutline,
                                contentDescription = t("set_primary"),
                                onClick = { ctx.whenPost { write(photosWithPrimary(photos, index)) } },
                            )
                        }
                        ZillitIconButton(
                            icon = ZillitIcons.Trash,
                            contentDescription = t("delete"),
                            onClick = { ctx.whenPost { removing = index } },
                            tint = ZillitTheme.colors.danger,
                        )
                    }
                }
            }
        }
        // Upload a picture, or paste the address of one that already lives
        // somewhere. The FIRST photo a character gets becomes its primary.
        AddPhotoRow(folder = "characters", onAdded = { write(photosPlus(photos, it)) })
    }

    if (removing >= 0) {
        DeleteDialog(
            title = t("remove_photo_title"),
            name = "",
            onClose = { removing = -1 },
            run = {
                ContinuityCrud(ctx.api, "characters")
                    .update(character.id, body("photos" to photosWithout(photos, removing)))
            },
            onDone = onChanged,
        )
    }
}

/** The photo array with [at] as the primary one and the rest not. */
private fun photosWithPrimary(photos: List<Rec>, at: Int): JsonArray = buildJsonArray {
    photos.forEachIndexed { index, photo ->
        add(
            buildJsonObject {
                put("url", photo.str("url"))
                put("is_primary", index == at)
            },
        )
    }
}

private fun photosWithout(photos: List<Rec>, at: Int): JsonArray = buildJsonArray {
    photos.filterIndexed { index, _ -> index != at }.forEach { photo ->
        add(
            buildJsonObject {
                put("url", photo.str("url"))
                put("is_primary", photo.bool("is_primary"))
            },
        )
    }
}

/** [url] appended; the FIRST photo a character gets is its primary one. */
private fun photosPlus(photos: List<Rec>, url: String): JsonArray = buildJsonArray {
    photos.forEach { photo ->
        add(
            buildJsonObject {
                put("url", photo.str("url"))
                put("is_primary", photo.bool("is_primary"))
            },
        )
    }
    add(
        buildJsonObject {
            put("url", url.trim())
            put("is_primary", photos.isEmpty())
        },
    )
}

/**
 * The characters linked to this one — the web's `CharacterRelationships`.
 *
 * A stunt performer, a body double and a stand-in are each their OWN
 * character record, so each can be scheduled, costumed and continuity-matched
 * on its own; this list is the link between them. Like the photos, the links
 * are one field on the record, so adding or removing one writes the array.
 */
@Composable
private fun Relationships(character: Rec, others: List<Rec>, onChanged: () -> Unit) {
    val ctx = LocalSs.current
    val links = character.recs("relationships")
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(-1) }

    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        if (links.isEmpty()) {
            MutedText(t("no_relationships"))
        } else {
            links.forEachIndexed { index, link ->
                RelationshipRow(
                    link = link,
                    linked = others.firstOrNull { it.id == link.str("character_id") },
                    onRemove = { ctx.whenPost { removing = index } },
                )
            }
        }
        ZillitButton(
            text = t("character_relationships"),
            onClick = { ctx.whenPost { adding = true } },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
    }

    if (adding) {
        AddRelationshipDialog(
            character = character,
            others = others,
            onClose = { adding = false },
            onSaved = onChanged,
        )
    }
    if (removing >= 0) {
        DeleteDialog(
            title = t("remove_relationship_title"),
            name = links.getOrNull(removing)?.str("character_name").orEmpty(),
            onClose = { removing = -1 },
            onDone = onChanged,
            run = {
                ContinuityCrud(ctx.api, "characters").update(
                    character.id,
                    body("relationships" to relationshipsWithout(links, removing)),
                )
            },
        )
    }
}

/** One linked character: who they are, what they are to this character, and the way to unlink them. */
@Composable
private fun RelationshipRow(link: Rec, linked: Rec?, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhotoAvatar(
            name = link.str("character_name"),
            photo = Characters.primaryPhoto(linked),
            size = SWITCH_AVATAR,
        )
        ZillitText(link.str("character_name"), modifier = Modifier.weight(1f))
        link.str("type").takeIf { it.isNotBlank() }?.let { SsTag(it) }
        ZillitIconButton(
            icon = ZillitIcons.Trash,
            contentDescription = t("delete"),
            onClick = onRemove,
            tint = ZillitTheme.colors.danger,
        )
    }
}

@Composable
private fun AddRelationshipDialog(character: Rec, others: List<Rec>, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    var linkedId by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val links = character.recs("relationships")

    FormDialog(
        open = true,
        title = t("character_relationships"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val next = buildJsonArray {
                    links.forEach { add(it.json) }
                    add(
                        buildJsonObject {
                            put("character_id", linkedId)
                            // Stored beside the id, so the row needs no join.
                            put("character_name", others.firstOrNull { it.id == linkedId }?.str("name").orEmpty())
                            put("type", kind)
                            put("description", "")
                        },
                    )
                }
                val done = ctx.write {
                    ContinuityCrud(ctx.api, "characters")
                        .update(character.id, body("relationships" to next))
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = linkedId.isNotBlank(),
        busy = saving,
    ) {
        FormGrid {
            PickInput(
                value = linkedId,
                options = others.map { it.id to it.str("name") },
                onChange = { linkedId = it },
                label = t("choose_character"),
                placeholder = t("link_to_character"),
            )
            // The three the web suggests, and anything else typed: the service
            // stores the words, so the presets are the translated labels.
            PickInput(
                value = kind,
                options = RELATION_PRESETS.map { t("relation_$it") to t("relation_$it") },
                onChange = { kind = it },
                label = t("link_type"),
                placeholder = t("link_type_placeholder"),
                clearable = true,
            )
        }
    }
}

private fun relationshipsWithout(links: List<Rec>, at: Int): JsonArray = buildJsonArray {
    links.filterIndexed { index, _ -> index != at }.forEach { add(it.json) }
}

/** What a linked character usually is, in the web's order. */
private val RELATION_PRESETS = listOf("stunt_performer", "body_double", "stand_in")

/** How many other characters the switcher shows before "+n". */
private const val SWITCHER = 3

private const val TILE_HELP_LINES = 2

private val BIG_AVATAR = 96.dp
private val SWITCH_AVATAR = 40.dp
private val REF_PHOTO = 72.dp
private val TILE_MIN = 240.dp
