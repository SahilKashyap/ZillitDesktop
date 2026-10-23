package com.zillit.desktop.feature.scriptsupervision.ui.screens.characters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormCell
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoAvatar
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.SsView
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.cast
import com.zillit.desktop.feature.scriptsupervision.ui.count
import com.zillit.desktop.feature.scriptsupervision.ui.nav
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty

/**
 * The production's characters — the web's `CharactersScreen`.
 *
 * A card each, opening the character's own hub. Searched here rather than on
 * the service: the list is the whole production in one answer (the continuity
 * folders are not paged), so the filter is over what came back, as the web's
 * is.
 */
@Suppress("LongMethod") // Header, the search, the grid and three dialogs.
@Composable
internal fun CharactersScreen() {
    val ctx = LocalSs.current
    val castState = cast()
    val navState = nav()
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "characters") }
    val rows = rememberRows(ctx) { crud.list() }
    val changed = {
        rows.reload(silent = true)
        // The pickers in nine other forms read this list too.
        castState.reload()
    }

    SsPageHeader(
        title = t("characters"),
        sub = count("characters_in_production", rows.value?.size ?: 0),
        actions = {
            ZillitButton(
                text = t("add_character"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
    Await(rows) { records ->
        val shown = records.filter { it.matches(search) }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitSearchField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.width(SEARCH_WIDTH),
                placeholder = t("search_characters"),
            )
            MutedText(count("characters_count", shown.size))
        }
        if (records.isEmpty()) {
            ZillitEmptyState(
                title = t("no_characters"),
                message = t("no_characters_help"),
                action = {
                    ZillitButton(
                        text = t("add_character"),
                        onClick = { ctx.whenPost { creating = true } },
                        leadingIcon = ZillitIcons.Add,
                    )
                },
            )
        } else {
            CardGrid(shown) { character ->
                CharacterCard(
                    character = character,
                    onOpen = { navState.go(SsView.Character(character.id)) },
                    onEdit = { editing = character },
                    onDelete = { deleting = character },
                )
            }
        }
    }

    if (creating || editing != null) {
        CharacterForm(editing, onClose = { creating = false; editing = null }, onSaved = changed)
    }
    deleting?.let { character ->
        DeleteDialog(
            title = t("delete_character_title"),
            name = character.str("name"),
            onClose = { deleting = null },
            onDone = changed,
            run = { crud.remove(character.id) },
        )
    }
}

/** The search this screen runs: the name, the number, and every alias. */
private fun Rec.matches(search: String): Boolean {
    val needle = search.trim()
    if (needle.isBlank()) return true
    return (Characters.namesOf(this) + str("number")).any { it.contains(needle, ignoreCase = true) }
}

/** One character's card: who they are, their kind, and what the script calls them. */
@Composable
private fun CharacterCard(character: Rec, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val type = Characters.typeOf(character)
    val aliases = character.strings("aliases")
    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoAvatar(
                name = character.str("name"),
                photo = Characters.primaryPhoto(character),
                size = CARD_AVATAR,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZillitText(
                        text = character.str("name").ifBlank { t("unnamed") },
                        style = ZillitTheme.typography.titleSmall,
                        maxLines = 1,
                    )
                    character.str("number").takeIf { it.isNotBlank() }
                        ?.let { SsTag("#$it", StatusTone.Progress) }
                    // Written by the service's import, not by a person.
                    if (character.bool("ai_generated")) SsTag(t("ai"), StatusTone.Neutral)
                }
                MutedText(character.str("actor_name").ifBlank { t("type_$type") })
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) {
            SsTag(t("type_$type"), if (type == "background") StatusTone.Neutral else StatusTone.Progress)
            if (aliases.isNotEmpty()) SsTag(count("aliases_count", aliases.size))
            // A crowd of one needs no count; "×8" is what the card is for.
            character.int("count").takeIf { type == "background" && it > 1 }?.let { SsTag("×$it") }
        }
        Field(t("aliases"), aliases.joinToString(", "))
        Field(t("description"), character.str("description"))
    }
}

/**
 * The character form — the web's modal.
 *
 * The kind of character decides the rest of it: a background player carries a
 * head count, and a principal an age, a gender and the actor playing them. The
 * body is built the same way, which is why the two halves are never both sent.
 */
@Suppress("LongMethod") // One character's fields, and the two shapes its type takes.
@Composable
private fun CharacterForm(character: Rec?, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val castState = cast()
    var name by remember(character) { mutableStateOf(character?.str("name").orEmpty()) }
    var number by remember(character) { mutableStateOf(character?.str("number").orEmpty()) }
    var aliases by remember(character) { mutableStateOf(character?.strings("aliases").orEmpty()) }
    var type by remember(character) { mutableStateOf(Characters.typeOf(character)) }
    var headCount by remember(character) {
        mutableStateOf(character?.int("count")?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var age by remember(character) { mutableStateOf(character?.str("age").orEmpty()) }
    var gender by remember(character) { mutableStateOf(character?.str("gender").orEmpty()) }
    var actorId by remember(character) { mutableStateOf(character?.str("actor_id").orEmpty()) }
    var description by remember(character) { mutableStateOf(character?.str("description").orEmpty()) }
    var saving by remember { mutableStateOf(false) }

    FormDialog(
        open = true,
        title = t(if (character == null) "add_character" else "edit_character"),
        onDismiss = onClose,
        confirmLabel = t(if (character == null) "create" else "save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val shared = listOf(
                    "name" to name.trim(),
                    "number" to number.trim(),
                    "aliases" to aliases,
                    "character_type" to type,
                    "description" to description,
                    // The service keeps the old field in step with the new one.
                    "importance" to if (type == "background") "background" else "lead",
                )
                val payload = body(
                    if (type == "background") {
                        // A crowd is at least one person, as the web's `min` is.
                        shared + ("count" to (headCount.trim().toIntOrNull()?.coerceAtLeast(1) ?: 1))
                    } else {
                        shared + listOf(
                            "age" to age.trim(),
                            "gender" to gender,
                            "actor_id" to actorId,
                            // Denormalised by the web so a card needs no join.
                            "actor_name" to castState.actorName(actorId),
                        )
                    },
                )
                val crud = ContinuityCrud(ctx.api, "characters")
                val done = ctx.write {
                    // A create must DROP what nobody filled (the validator
                    // refuses ""); an edit must SEND "" to clear it.
                    if (character == null) crud.create(payload.dropEmpty()) else crud.update(character.id, payload)
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
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("name"), placeholder = t("character_name"))
            TextInput(number, { number = it }, t("number"), FormHalf, placeholder = "#")
            TagInput(
                tokens = aliases,
                onChange = { aliases = it },
                label = t("aliases"),
                modifier = FormWide,
                placeholder = t("aliases_placeholder"),
                help = t("aliases_help"),
            )
            PickInput(
                value = type,
                options = Characters.TYPES.map { it to t("type_$it") },
                onChange = { type = it },
                label = t("type"),
            )
            if (type == "background") {
                TextInput(
                    headCount,
                    { headCount = it },
                    t("count"),
                    FormHalf,
                    placeholder = t("count_placeholder"),
                    number = true,
                )
            } else {
                TextInput(age, { age = it }, t("age"), FormHalf, placeholder = t("age_placeholder"))
                PickInput(
                    value = gender,
                    options = Characters.GENDERS.map { it to t("gender_$it") },
                    onChange = { gender = it },
                    label = t("gender"),
                    clearable = true,
                )
                PickInput(
                    value = actorId,
                    options = castState.actorOptions,
                    onChange = { actorId = it },
                    label = t("actor"),
                    modifier = FormCell,
                    placeholder = t("link_actor"),
                    clearable = true,
                )
            }
            TextInput(description, { description = it }, t("description"), FormWide, multiline = true)
        }
    }
}

private val SEARCH_WIDTH = 260.dp
private val CARD_AVATAR = 40.dp
