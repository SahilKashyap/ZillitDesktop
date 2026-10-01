package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.humanize
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

private val Cell = Modifier.width(408.dp)
private val Wide = Modifier.width(832.dp)
private val GenderFallback = listOf("FEMALE", "MALE", "NON_BINARY", "OTHER")
private const val ACTOR_DIALOG_WIDTH = 880
private const val QUICK_DIALOG_WIDTH = 520
private const val AGE_WIDTH = 90

/**
 * The one Create / Edit Actor form (the web's `ActorModal`), with every field
 * the Actors page collects.
 *
 * Opened for one character ([forCharacter]) the actor is FOR that character:
 * it is the only one listed, ticked and locked. Otherwise every character in
 * the production is listed to tick, with a way to add a missing one without
 * leaving the form — ticked at once, because it exists for this actor.
 *
 * "Create +" saves and clears the form for the next actor; callers that cast
 * the new actor straight back turn it off with [allowAddAnother] = false.
 * [quick] (the breakdown's Cast name pickers) asks only for the name.
 */
@Composable
fun ActorFormDialog(
    open: Boolean,
    onClose: () -> Unit,
    editing: Rec? = null,
    onSaved: (Rec) -> Unit = {},
    forCharacter: Rec? = null,
    saveLabel: String? = null,
    quick: Boolean = false,
    allowAddAnother: Boolean = true,
) {
    val ctx = LocalSync.current
    var form by remember(open, editing) { mutableStateOf(if (editing != null) toActorForm(editing) else newActorForm(forCharacter?.id)) }
    var characters by remember { mutableStateOf(emptyList<Rec>()) }
    var saving by remember { mutableStateOf(false) }
    var addCharacterOpen by remember { mutableStateOf(false) }
    val quickOnly = quick && editing == null
    val loadCharacters = suspend { characters = (ctx.api.get("/characters").mapRows() as? ZillitResult.Success)?.data.orEmpty() }
    LaunchedEffect(open) { if (open && forCharacter == null && !quickOnly) loadCharacters() }

    val canSave = form.first.isNotBlank() && !saving
    fun save(another: Boolean) {
        if (!canSave) return
        saving = true
        ctx.scope.launch {
            val answer = ctx.write { if (editing != null) ctx.api.patch("/actors/${editing.id}", toActorBody(form)) else ctx.api.post("/actors", toActorBody(form)) }
            saving = false
            if (answer == null) return@launch
            answer.rec?.let(onSaved)
            if (!another || editing != null) {
                onClose()
            } else {
                form = newActorForm(forCharacter?.id)
                if (forCharacter == null) loadCharacters()
            }
        }
    }

    if (quickOnly) {
        FormDialog(
            open = open,
            title = t("csync_create_actor"),
            onDismiss = onClose,
            confirmLabel = saveLabel ?: t("csync_create"),
            onConfirm = { save(false) },
            confirmEnabled = canSave,
            busy = saving,
            width = QUICK_DIALOG_WIDTH.dp,
        ) {
            FormGrid {
                TextInput(form.first, { form = form.copy(first = it) }, t("csync_first_name"), Modifier.width(220.dp))
                TextInput(form.last, { form = form.copy(last = it) }, t("csync_last_name"), Modifier.width(220.dp))
            }
        }
        return
    }
    SyncDialogShell(
        title = if (editing != null) t("csync_edit_actor") else t("csync_create_actor"),
        visible = open,
        onDismiss = onClose,
        width = ACTOR_DIALOG_WIDTH.dp,
        actions = {
            ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary, enabled = !saving)
            if (editing == null && allowAddAnother) {
                ZillitButton(t("csync_create_plus"), onClick = { save(true) }, variant = ButtonVariant.Secondary, enabled = canSave)
            }
            ZillitButton(
                if (editing != null) t("csync_save") else saveLabel ?: t("csync_create"),
                onClick = { save(false) },
                enabled = canSave,
                loading = saving,
            )
        },
    ) {
        ActorFormBody(
            form = form,
            onChange = { form = it },
            characters = characters,
            editingId = editing?.id,
            forCharacter = forCharacter,
            onAddCharacter = { addCharacterOpen = true },
        )
    }
    AddCharacterForActor(
        open = addCharacterOpen,
        onClose = { addCharacterOpen = false },
        onCreated = { created ->
            ctx.scope.launch {
                loadCharacters()
                if (created.id.isNotEmpty()) form = form.copy(characterIds = form.characterIds + created.id)
            }
        },
    )
}

@Composable
private fun ActorFormBody(
    form: ActorFormState,
    onChange: (ActorFormState) -> Unit,
    characters: List<Rec>,
    editingId: String?,
    forCharacter: Rec?,
    onAddCharacter: () -> Unit,
) {
    val ctx = LocalSync.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        FormGrid {
            Row(Cell, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                TextInput(form.first, { onChange(form.copy(first = it)) }, t("csync_first_name"), Modifier.width(200.dp))
                TextInput(form.last, { onChange(form.copy(last = it)) }, t("csync_last_name"), Modifier.width(200.dp))
            }
            TextInput(form.phone, { onChange(form.copy(phone = it)) }, t("csync_field_phone"), Cell)
            Row(Cell, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
                EnumInput(
                    form.gender,
                    ctx.metaList("genders").ifEmpty { GenderFallback },
                    { onChange(form.copy(gender = it)) },
                    t("csync_field_gender_age"),
                    Modifier.width(300.dp),
                    t("csync_select"),
                )
                TextInput(form.age, { onChange(form.copy(age = it)) }, t("csync_field_age"), Modifier.width(AGE_WIDTH.dp), number = true)
            }
            TextInput(form.phone2, { onChange(form.copy(phone2 = it)) }, t("csync_field_phone_2"), Cell)
            CharacterPicks(form, onChange, characters, editingId, forCharacter, onAddCharacter)
            Column(Cell, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
                TextInput(form.email, { onChange(form.copy(email = it)) }, t("csync_field_email"), Modifier.fillMaxWidth())
                TextInput(form.email2, { onChange(form.copy(email2 = it)) }, t("csync_field_email_2"), Modifier.fillMaxWidth())
            }
            TextInput(form.notes, { onChange(form.copy(notes = it)) }, t("csync_field_notes"), Cell, multiline = true)
            NextFitting(form, onChange)
            DateInput(
                actorDateText(form.startWorkDate),
                { onChange(form.copy(startWorkDate = actorDateMs(it))) },
                t("csync_field_start_work_date"),
                Cell,
            )
            TextInput(form.fittingComment, { onChange(form.copy(fittingComment = it)) }, t("csync_field_comment"), Cell)
            TextInput(form.agency, { onChange(form.copy(agency = it)) }, t("csync_field_agency"), Cell)
        }
        MeasurementsAndRep(form, onChange)
    }
}

@Composable
private fun NextFitting(form: ActorFormState, onChange: (ActorFormState) -> Unit) {
    var time by remember(form.nextFittingAt == 0L) { mutableStateOf(actorTimeText(form.nextFittingAt)) }
    val date = actorDateText(form.nextFittingAt)
    com.zillit.desktop.feature.costumesetsync.ui.DateTimeInput(
        date,
        time,
        { onChange(form.copy(nextFittingAt = actorDateMs(it, time))) },
        { time = it; onChange(form.copy(nextFittingAt = actorDateMs(date, it))) },
        t("csync_field_next_fitting"),
        Cell,
    )
}

@Composable
private fun CharacterPicks(
    form: ActorFormState,
    onChange: (ActorFormState) -> Unit,
    characters: List<Rec>,
    editingId: String?,
    forCharacter: Rec?,
    onAddCharacter: () -> Unit,
) {
    val ctx = LocalSync.current
    Column(Cell, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            if (forCharacter != null) t("csync_field_character") else t("csync_field_characters"),
            style = ZillitTheme.typography.label,
            color = ZillitTheme.colors.textSecondary,
        )
        if (forCharacter != null) {
            ZillitCheckbox(checked = true, onCheckedChange = {}, label = charLabel(forCharacter), enabled = false)
            MutedText(t("csync_actor_for_character_hint"), maxLines = 2)
            return@Column
        }
        if (characters.isEmpty()) MutedText(t("csync_no_characters_yet"))
        characters.forEach { c ->
            // Ticking moves the part to this actor, so say who has it now.
            val holder = c.rec("actor")?.takeIf { it.id.isNotEmpty() && it.id != editingId }
            val label = charLabel(c) + (holder?.let { " · ${t("csync_currently")} ${it.str("name")}" } ?: "")
            ZillitCheckbox(
                checked = c.id in form.characterIds,
                onCheckedChange = { on ->
                    onChange(form.copy(characterIds = if (on) form.characterIds + c.id else form.characterIds - c.id))
                },
                label = label,
            )
        }
        if (ctx.canPost) ZillitButton(t("csync_char_add"), onClick = onAddCharacter, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
        MutedText(t("csync_tick_every_character"), maxLines = 2)
    }
}

@Composable
private fun MeasurementsAndRep(form: ActorFormState, onChange: (ActorFormState) -> Unit) {
    SectionHeading(t("csync_field_measurements")) { onChange(form.copy(extraMeasures = form.extraMeasures + LabelValue())) }
    FormGrid {
        ActorMeasures.forEach { m ->
            TextInput(form.measurements[m].orEmpty(), { onChange(form.copy(measurements = form.measurements + (m to it))) }, humanize(m), Cell)
        }
    }
    // Any other measurement the costume team takes (thigh, neck to waist…), named by the user.
    LabelValueRows(form.extraMeasures, t("csync_measurement_name")) { onChange(form.copy(extraMeasures = it)) }
    SectionHeading(t("csync_talent_rep")) { onChange(form.copy(talentRepDetails = form.talentRepDetails + LabelValue())) }
    FormGrid {
        TextInput(form.talentRep, { onChange(form.copy(talentRep = it)) }, t("csync_field_name"), Cell)
        TextInput(form.talentRepEmail, { onChange(form.copy(talentRepEmail = it)) }, t("csync_field_email"), Cell)
        TextInput(form.talentRepPhone, { onChange(form.copy(talentRepPhone = it)) }, t("csync_field_phone"), Cell)
    }
    // Anything else about the rep (agency office, assistant), named by the user.
    LabelValueRows(form.talentRepDetails, t("csync_detail_name")) { onChange(form.copy(talentRepDetails = it)) }
}

@Composable
private fun SectionHeading(title: String, onAdd: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        ZillitText(title, style = ZillitTheme.typography.titleSmall)
        ZillitButton(t("csync_add_more"), onClick = onAdd, variant = ButtonVariant.Secondary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Add)
    }
}

@Composable
private fun LabelValueRows(rows: List<LabelValue>, labelHint: String, onChange: (List<LabelValue>) -> Unit) {
    rows.forEachIndexed { i, row ->
        Row(Wide, horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            TextInput(row.label, { v -> onChange(rows.mapIndexed { j, x -> if (j == i) x.copy(label = v) else x }) }, labelHint, Modifier.width(300.dp))
            TextInput(row.value, { v -> onChange(rows.mapIndexed { j, x -> if (j == i) x.copy(value = v) else x }) }, t("csync_value"), Modifier.width(380.dp))
            ZillitButton(t("csync_remove"), onClick = { onChange(rows.filterIndexed { j, _ -> j != i }) }, variant = ButtonVariant.Tertiary, size = ButtonSize.Small, leadingIcon = ZillitIcons.Trash)
        }
    }
}
