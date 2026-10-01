package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.castLabel
import com.zillit.desktop.feature.costumesetsync.ui.Clear
import com.zillit.desktop.feature.costumesetsync.ui.EmptyState
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MonoText
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.ReadinessDot
import com.zillit.desktop.feature.costumesetsync.ui.RecInput
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.SectionCard
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum

/** The option that stands for "make a new look right here". */
private const val NEW_CHANGE = "__new__"
private val CHANGE_SELECT_W = 240.dp

/** A character's readiness chip text: "Ready" in the user's words, everything else the service's. */
private fun levelLabel(level: String) = if (level == "READY") t("csync_ready") else tEnum(level)

/** Readiness character by character, each with the look they wear assigned right here. */
@Composable
internal fun ReadinessCard(scene: Rec, readiness: Rec?, characters: List<Rec>, reload: () -> Unit) {
    val ctx = LocalSync.current
    var addOpen by remember { mutableStateOf(false) }
    val rows = readiness?.recs("characters").orEmpty()
    SectionCard(
        title = t("csync_scene_readiness"),
        actions = {
            if (ctx.canPost) ZillitButton("+ ${t("csync_character")}", onClick = { addOpen = true }, size = ButtonSize.Small, variant = ButtonVariant.Secondary)
        },
    ) {
        if (rows.isEmpty()) EmptyState(t("csync_no_characters_in_scene"))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { r -> CharacterReadiness(scene, r, reload) }
        }
    }
    AddCharacterDialog(addOpen, scene, characters, { addOpen = false }, reload)
}

@Composable
private fun CharacterReadiness(scene: Rec, r: Rec, reload: () -> Unit) {
    val ctx = LocalSync.current
    val character = r.rec("character") ?: Rec.Empty
    val sc = scene.recs("characters").firstOrNull { it.str("character_id") == character.id }
    val change = r.rec("change")
    // `.csync-scenecard`: 12dp padding, 1dp border, 10dp radius.
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ZillitTheme.colors.surface)
            .border(1.dp, ZillitTheme.colors.border, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            Column(Modifier.weight(1f)) {
                ZillitText(
                    character.str("name"),
                    Modifier.clickable { ctx.nav.go("characters/${character.id}") },
                    style = ZillitTheme.typography.titleSmall,
                    color = ZillitTheme.colors.info,
                    maxLines = 1,
                )
                MutedText(character.rec("actor")?.str("name")?.ifEmpty { null } ?: t("csync_no_actor_short"))
            }
            StatusBadge(r.str("level"), levelLabel(r.str("level")))
            // Straight away, as the reference: the character is put back with "+ Character", and nothing else is lost.
            if (ctx.canPost) {
                ZillitButton(
                    "", onClick = { ctx.launchWrite({ ctx.api.delete("/scenes/${scene.id}/characters/${character.id}") }) { reload() } },
                    variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Trash,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            MutedText("${t("csync_change")}:")
            if (ctx.canPost) {
                ChangeSelect(
                    value = sc?.str("change_id").orEmpty(),
                    characterId = character.id,
                    changes = sc?.rec("character")?.recs("changes").orEmpty(),
                    placeholder = t("csync_not_assigned"),
                    onChange = { id ->
                        ctx.launchWrite({ ctx.api.put("/scenes/${scene.id}/characters/${character.id}", body("change_id" to (id.ifEmpty { null } ?: Clear))) }) { reload() }
                    },
                )
            } else if (change != null) {
                ZillitText(
                    "#${change.str("change_number")} ${change.str("name")}",
                    Modifier.clickable { ctx.nav.go("changes/${change.id}") },
                    color = ZillitTheme.colors.info,
                )
            } else {
                ZillitText(t("csync_not_assigned"))
            }
            if (change != null) {
                ZillitButton(t("csync_open_look"), onClick = { ctx.nav.go("changes/${change.id}") }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small)
            }
        }
        val items = r.recs("items")
        if (items.isNotEmpty()) {
            items.forEach { PieceLine(it) }
        } else {
            val blockers = r.strings("blockers")
            if (blockers.isNotEmpty()) MutedText(blockers.joinToString("; "), maxLines = 3)
        }
    }
}

/** One line per piece: dot, name over where it is, and its level. */
@Composable
private fun PieceLine(it: Rec) {
    val ctx = LocalSync.current
    Row(
        Modifier.fillMaxWidth().clickable { ctx.nav.go("costumes/${it.str("costume_id")}") }.padding(vertical = ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
    ) {
        ReadinessDot(it.str("level"))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                MonoText(it.str("asset_number"))
                RowTitle(it.str("name"), Modifier.weight(1f, fill = false))
            }
            val where = listOf(it.str("location"), it.str("wear_notes")).filter { s -> s.isNotEmpty() }.joinToString(" · ")
            if (where.isNotEmpty()) MutedText(where)
        }
        StatusBadge(it.str("level"), levelLabel(it.str("level")))
    }
}

/**
 * The look picker for one character in a scene: their changes, plus "+ New change"
 * to make one on the spot. An empty pick is "not assigned".
 */
@Composable
internal fun ChangeSelect(
    value: String,
    characterId: String,
    changes: List<Rec>,
    placeholder: String,
    onChange: (String) -> Unit,
    enabled: Boolean = true,
    onCreated: () -> Unit = {},
) {
    val ctx = LocalSync.current
    var newOpen by remember { mutableStateOf(false) }
    var name by remember(newOpen) { mutableStateOf("") }
    var saving by remember(newOpen) { mutableStateOf(false) }
    val options = listOf("" to placeholder) +
        (if (ctx.canPost && characterId.isNotEmpty()) listOf(NEW_CHANGE to "+ ${t("csync_new_change")}") else emptyList()) +
        changes.map { it.id to "#${it.str("change_number")} ${it.str("name")}" }
    CellPick(value, options, { if (it == NEW_CHANGE) newOpen = true else onChange(it) }, CHANGE_SELECT_W, placeholder = placeholder, enabled = enabled)
    FormDialog(
        open = newOpen,
        title = t("csync_new_change"),
        onDismiss = { newOpen = false },
        confirmLabel = t("csync_add"),
        confirmEnabled = name.isNotBlank(),
        busy = saving,
        onConfirm = {
            saving = true
            ctx.launchWrite({ ctx.api.post("/changes", body("character_id" to characterId, "name" to name.trim())) }) { answer ->
                saving = false
                newOpen = false
                onCreated()
                answer.rec?.id?.takeIf { it.isNotEmpty() }?.let(onChange)
            }
        },
    ) { TextInput(name, { name = it }, t("csync_field_name"), Modifier.fillMaxWidth(), help = t("csync_change_name_hint")) }
}

/** "+ Character": put somebody in this scene, optionally with the look they wear. */
@Composable
private fun AddCharacterDialog(open: Boolean, scene: Rec, characters: List<Rec>, onClose: () -> Unit, reload: () -> Unit) {
    val ctx = LocalSync.current
    var characterId by remember(open) { mutableStateOf("") }
    var changeId by remember(open) { mutableStateOf("") }
    var saving by remember(open) { mutableStateOf(false) }
    val inScene = scene.recs("characters").map { it.str("character_id") }.toSet()
    val candidates = characters.filter { it.id !in inScene }
    // The character list carries counts, not looks: fetch the chosen one's.
    val changes = rememberResource(characterId) {
        if (characterId.isEmpty()) ZillitResult.Success(emptyList<Rec>()) else api.get("/changes", mapOf("characterId" to characterId)).mapRows()
    }
    FormDialog(
        open = open,
        title = t("csync_add_character_to_scene"),
        onDismiss = onClose,
        confirmLabel = t("csync_add"),
        confirmEnabled = characterId.isNotEmpty(),
        busy = saving,
        onConfirm = {
            saving = true
            ctx.launchWrite({ ctx.api.put("/scenes/${scene.id}/characters/$characterId", body("change_id" to (changeId.ifEmpty { null } ?: Clear))) }) {
                saving = false
                onClose()
                reload()
            }
        },
    ) {
        RecInput(characterId, candidates, { characterId = it; changeId = "" }, t("csync_field_character"), Modifier.fillMaxWidth(), t("csync_select_ellipsis"), ::castLabel)
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(t("csync_change_look"), style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
            ChangeSelect(changeId, characterId, changes.value.orEmpty(), t("csync_assign_later"), { changeId = it }, enabled = characterId.isNotEmpty(), onCreated = { changes.reload(silent = true) })
        }
    }
}
