package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitDivider
import com.zillit.desktop.core.designsystem.component.ZillitSearchField
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costumesetsync.data.DraftOutcome
import com.zillit.desktop.feature.costumesetsync.data.SceneWrites
import com.zillit.desktop.feature.costumesetsync.data.persistDraft
import com.zillit.desktop.feature.costumesetsync.domain.CastEdit
import com.zillit.desktop.feature.costumesetsync.domain.CastProblem
import com.zillit.desktop.feature.costumesetsync.domain.DAY_PREFIXES
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SceneDraft
import com.zillit.desktop.feature.costumesetsync.domain.actorIdOf
import com.zillit.desktop.feature.costumesetsync.domain.castLabel
import com.zillit.desktop.feature.costumesetsync.domain.castNumberProblem
import com.zillit.desktop.feature.costumesetsync.domain.castNumberText
import com.zillit.desktop.feature.costumesetsync.domain.emptyDraft
import com.zillit.desktop.feature.costumesetsync.domain.initials
import com.zillit.desktop.feature.costumesetsync.domain.sortByCast
import com.zillit.desktop.feature.costumesetsync.domain.toDraft
import com.zillit.desktop.feature.costumesetsync.ui.ChipRow
import com.zillit.desktop.feature.costumesetsync.ui.Clear
import com.zillit.desktop.feature.costumesetsync.ui.FormCell
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.RecInput
import com.zillit.desktop.feature.costumesetsync.ui.RowTitle
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

private val PRINCIPALS_WIDTH = 560.dp
private val ROW_DIALOG_WIDTH = 760.dp
private val ADD_DIALOG_WIDTH = 520.dp
private const val PICKER_SHOWN = 100

/**
 * "Principals in this scene": the chip-style character picker the editor's
 * Add/Remove button opens. [onChange] gets the new list on Save, so cancelling
 * leaves the draft untouched. The working selection resets each time it opens.
 */
@Composable
internal fun PrincipalsDialog(
    open: Boolean,
    onClose: () -> Unit,
    characters: List<Rec>,
    value: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var sel by remember(open) { mutableStateOf(value) }
    var q by remember(open) { mutableStateOf("") }
    val byId = remember(characters) { characters.associateBy { it.id } }
    val selected = sortByCast(sel.mapNotNull { byId[it] })
    val needle = q.trim().lowercase()
    val available = sortByCast(characters.filter { it.id !in sel && matchesNeedle(it, needle) })
    FormDialog(
        open = open,
        title = t("csync_principals_title"),
        onDismiss = onClose,
        confirmLabel = t("csync_save"),
        onConfirm = {
            onChange(sel)
            onClose()
        },
        width = PRINCIPALS_WIDTH,
    ) {
        ZillitText(
            t("csync_principals_field"),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        if (selected.isEmpty()) {
            MutedText(t("csync_principals_none"))
        } else {
            ChipRow {
                selected.forEach { c -> ZillitChoiceChip(
                    "${castLabel(c)}  ×",
                    selected = true,
                    onClick = { sel = sel - c.id },
                ) }
            }
        }
        ZillitSearchField(
            value = q,
            onValueChange = { q = it },
            placeholder = t("csync_principals_search"),
            modifier = Modifier.fillMaxWidth(),
        )
        if (available.isEmpty()) {
            MutedText(
                when {
                    characters.isEmpty() -> t("csync_principals_no_characters")
                    q.isNotEmpty() -> t("csync_principals_no_match")
                    else -> t("csync_principals_all_in")
                },
            )
        }
        Column(Modifier.fillMaxWidth()) {
            available.take(PICKER_SHOWN).forEach { c ->
                PrincipalPickRow(c) { sel = sel + c.id }
                ZillitDivider()
            }
        }
    }
}

/** A character whose cast label or actor's name holds what was typed (everyone when nothing was). */
private fun matchesNeedle(c: Rec, needle: String): Boolean =
    needle.isEmpty() ||
        castLabel(c).lowercase().contains(needle) ||
        c.rec("actor")?.str("name").orEmpty().lowercase().contains(needle)

/** One character that can still be added: cast number or initials, name, type and actor, and a "+". */
@Composable
private fun PrincipalPickRow(c: Rec, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(vertical = ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        ZillitText(
            c.str("cast_number").ifEmpty { initials(c.str("name")) },
            Modifier.width(AVATAR),
            style = ZillitTheme.typography.titleSmall,
            color = ZillitTheme.colors.accent,
        )
        Column(Modifier.weight(1f)) {
            RowTitle(castLabel(c))
            MutedText(
                listOf(tEnum(c.str("type")), c.rec("actor")?.str("name").orEmpty())
                    .filter { it.isNotEmpty() }
                    .joinToString(" · "),
            )
        }
        ZillitText("+", color = ZillitTheme.colors.textMuted)
    }
}

private val AVATAR = 36.dp

/** Which breakdown row the Edit dialog is on. */
internal data class RowTarget(val sceneId: String, val characterId: String)

/** What the row dialog needs besides its target. */
internal class RowDialogData(
    val scenes: List<Rec>,
    val characters: List<Rec>,
    val actors: List<Rec>,
    val episodes: Boolean,
    val intExt: List<String>,
)

/** The scene+character+cast fields a row dialog edits. */
private data class AddForm(
    val sceneId: String = "",
    val characterId: String = "",
    val castNumber: String = "",
    val actorId: String = "",
)

/**
 * Two jobs, one dialog, exactly as the reference has it. With no [target] it is
 * **Add to breakdown**: the script reader misses people, so a character is added
 * to a scene by hand. With a target it edits that one breakdown row — every scene
 * column the table shows, plus the character's cast number and cast name.
 *
 * Cast number and cast name belong to the CHARACTER, so a change here follows
 * them into every scene they are in. The look worn is not set here.
 */
@Composable
internal fun BreakdownRowDialog(
    open: Boolean,
    target: RowTarget?,
    data: RowDialogData,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalSync.current
    val writes = rememberSceneWrites()
    val locked = target != null
    val charById = remember(data.characters) { data.characters.associateBy { it.id } }
    val sceneById = remember(data.scenes) { data.scenes.associateBy { it.id } }
    fun castOf(id: String) = castFormOf(charById[id])

    // Re-seeding on anything but a fresh open would throw away typing.
    var form by remember(open, target) { mutableStateOf(
        AddForm(target?.sceneId.orEmpty(), target?.characterId.orEmpty()).let { it.copy(
            castNumber = castOf(it.characterId).castNumber,
            actorId = castOf(it.characterId).actorId,
        ) },
    ) }
    var draft by remember(open, target) { mutableStateOf(
        target?.let { sceneById[it.sceneId] }?.let(::toDraft) ?: emptyDraft(),
    ) }
    var saving by remember(open, target) { mutableStateOf(false) }

    val scene = sceneById[form.sceneId]
    val character = charById[form.characterId]
    val problem = castNumberProblem(form.castNumber)

    val submit = {
        if (problem == null) {
            saving = true
            ctx.scope.launch {
                val outcome = saveRow(writes, if (locked) scene else null, draft, form, castOf(form.characterId))
                saving = false
                // A row saved with nothing changed writes nothing, so there is no server message to show — closing is
                // still right.
                ctx.report(outcome.result)
                if (outcome.ok) {
                    onSaved()
                    onClose()
                }
            }
        }
    }

    FormDialog(
        open = open,
        title = rowDialogTitle(locked, character, scene),
        onDismiss = onClose,
        confirmLabel = if (locked) t("csync_save") else t("csync_add"),
        onConfirm = submit,
        confirmEnabled = form.sceneId.isNotEmpty() && form.characterId.isNotEmpty() && problem == null,
        busy = saving,
        width = if (locked) ROW_DIALOG_WIDTH else ADD_DIALOG_WIDTH,
    ) {
        if (locked) {
            LockedRowFields(draft, { draft = it }, data, form.characterId, form.sceneId, character)
        } else {
            AddRowFields(form, { form = it }, data, ::castOf)
        }
        if (form.characterId.isNotEmpty()) CastFields(form, { form = it }, data, problem)
    }
}

/** The cast number and actor a character has now, as the form's starting values. */
private fun castFormOf(c: Rec?): AddForm =
    if (c == null) AddForm() else AddForm(castNumber = castNumberText(c), actorId = actorIdOf(c))

private fun rowDialogTitle(locked: Boolean, character: Rec?, scene: Rec?): String {
    if (!locked) return t("csync_add_to_breakdown")
    val name = character?.str("name")?.ifEmpty { null } ?: t("csync_edit_row_fallback")
    return t("csync_edit_row_title", "name" to name, "n" to scene?.str("number").orEmpty())
}

/** The scene and character pickers of Add to breakdown; picking a character brings their cast number and actor. */
@Composable
private fun AddRowFields(form: AddForm, onForm: (AddForm) -> Unit, data: RowDialogData, castOf: (String) -> AddForm) {
    val sceneLabel = { s: Rec -> listOf(s.str("number"), s.str("name")).filter { it.isNotEmpty() }.joinToString(" · ") }
    RecInput(
        form.sceneId,
        data.scenes,
        { onForm(form.copy(sceneId = it)) },
        t("csync_field_scene"),
        FormWide,
        t("csync_select_scene"),
        sceneLabel,
    )
    MutedText(t("csync_add_row_scene_hint"))
    RecInput(
        form.characterId,
        data.characters,
        { onForm(form.copy(characterId = it, castNumber = castOf(it).castNumber, actorId = castOf(it).actorId)) },
        t("csync_field_character"),
        FormWide,
        t("csync_select_character"),
        ::castLabel,
    )
}

/** The cast number and the actor playing the character, which belong to the character in every scene. */
@Composable
private fun CastFields(form: AddForm, onForm: (AddForm) -> Unit, data: RowDialogData, problem: CastProblem?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        TextInput(
            form.castNumber,
            { onForm(form.copy(castNumber = it)) },
            t("csync_field_cast_number"),
            Modifier.width(CAST_W),
            error = problem?.let(::castProblemText),
        )
        val actors = listOf("" to t("csync_no_actor_assigned")) + data.actors.map { it.id to it.str("name") }
        PickInput(
            form.actorId,
            actors,
            { onForm(form.copy(actorId = it)) },
            t("csync_field_cast_name"),
            Modifier.width(ACTOR_W),
            t("csync_no_actor_assigned"),
            t("csync_cast_follows_character"),
        )
    }
}

/** Saves the row: an edit through the inline editor's save (only what changed), an add by [addToBreakdown]. */
private suspend fun saveRow(
    writes: SceneWrites,
    scene: Rec?,
    draft: SceneDraft,
    form: AddForm,
    was: AddForm,
): DraftOutcome {
    val edit = CastEdit(
        castNumber = form.castNumber.takeIf { it.trim() != was.castNumber },
        actorId = form.actorId.takeIf { it != was.actorId },
    )
    if (scene == null) return addToBreakdown(writes, form, edit)
    // Scene fields go through the same save as the inline editor, so only what changed is sent.
    return persistDraft(
        writes,
        draft.copy(cast = if (edit.isEmpty) emptyMap() else mapOf(form.characterId to edit)),
        sceneId = scene.id,
        original = scene,
        problemText = ::castProblemText,
    )
}

private val CAST_W = 150.dp
private val ACTOR_W = 380.dp

/** Add to breakdown: put the character in the scene, then write their cast number / actor if they changed. */
private suspend fun addToBreakdown(writes: SceneWrites, form: AddForm, edit: CastEdit): DraftOutcome {
    val put = writes.setSceneCharacter(form.sceneId, form.characterId, JsonObject(emptyMap()))
    if (put !is ZillitResult.Success) return DraftOutcome(false, put, form.sceneId)
    val fields = body(
        "cast_number" to edit.castNumber?.trim()?.let { if (it.isEmpty()) Clear else it.toLong() },
        "actor_id" to edit.actorId?.let { if (it.isEmpty()) Clear else it },
    )
    if (fields.isEmpty()) return DraftOutcome(true, put, form.sceneId)
    val res = writes.updateCharacter(form.characterId, fields)
    return DraftOutcome(res is ZillitResult.Success, res, form.sceneId)
}

/** The scene's own fields in the Edit-row dialog, plus the character link. */
@Composable
private fun LockedRowFields(
    d: SceneDraft,
    onChange: (SceneDraft) -> Unit,
    data: RowDialogData,
    characterId: String,
    sceneId: String,
    character: Rec?,
) {
    val ctx = LocalSync.current
    val intExt = (data.intExt + listOfNotNull(d.intExt.takeIf { it.isNotEmpty() })).distinct()
    FormGrid {
        if (data.episodes) TextInput(d.episode, { onChange(d.copy(episode = it)) }, t("csync_field_episode"), FormCell)
        // A row edits the scene it sits in, never WHICH scene that is: the number is shown here, not changed.
        TextInput(
            d.number,
            {},
            t("csync_field_scene_hash"),
            FormCell,
            enabled = false,
            help = t("csync_scene_number_locked"),
        )
        PickInput(
            d.dayPrefix,
            listOf("" to t("csync_none")) + DAY_PREFIXES.map { it to it },
            { onChange(d.copy(dayPrefix = it)) },
            t("csync_field_script_day"), Modifier.width(DAY_PREFIX_W),
        )
        TextInput(d.dayN, { onChange(d.copy(dayN = it)) }, t("csync_day_number"), Modifier.width(DAY_NUMBER_W))
        PickInput(
            d.intExt,
            listOf("" to "—") + intExt.map { it to it },
            { onChange(d.copy(intExt = it)) },
            t("csync_field_script_location"),
            Modifier.width(DAY_PREFIX_W),
        )
        TextInput(
            d.location,
            { onChange(d.copy(location = it)) },
            t("csync_field_location"),
            Modifier.width(LOCATION_FIELD_W),
        )
        DateInput(d.shootDate, { onChange(d.copy(shootDate = it)) }, t("csync_field_shoot_date"), FormCell)
        TextInput(
            d.synopsis,
            { onChange(d.copy(synopsis = it)) },
            t("csync_field_scene_description"),
            FormWide,
            multiline = true,
        )
    }
    // The row IS this character in this scene, so the name opens their page rather than offering a picker that would
    // move the row.
    ZillitText(
        t("csync_field_character"),
        style = ZillitTheme.typography.label,
        color = ZillitTheme.colors.textSecondary,
    )
    Row(
        Modifier.fillMaxWidth().clickable { ctx.nav.go("characters/$characterId/scenes/$sceneId?via=row") }.padding(
            vertical = ZillitTheme.spacing.sm,
        ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ZillitText(if (character != null) castLabel(character) else "—")
        ZillitText("›", color = ZillitTheme.colors.textMuted)
    }
    MutedText(t("csync_character_opens_page"))
}

private val DAY_PREFIX_W = 130.dp
private val DAY_NUMBER_W = 150.dp
private val LOCATION_FIELD_W = 340.dp
