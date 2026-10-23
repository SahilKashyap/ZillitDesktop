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
import com.zillit.desktop.feature.scriptsupervision.domain.Characters
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.CardGrid
import com.zillit.desktop.feature.scriptsupervision.ui.DeleteDialog
import com.zillit.desktop.feature.scriptsupervision.ui.Field
import com.zillit.desktop.feature.scriptsupervision.ui.FormDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PhotoAvatar
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.RowActions
import com.zillit.desktop.feature.scriptsupervision.ui.SsPage
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.zillit.desktop.feature.scriptsupervision.ui.dropEmpty

/**
 * The cast's actors — the web's `ActorsScreen`.
 *
 * This is wardrobe's and make-up's copy of the cast: contacts and agent,
 * colouring, the twelve measurements, and the marks (tattoos, piercings,
 * scars) that continuity has to match or cover. A card opens the character
 * that actor plays, when one is linked.
 */
@Suppress("LongMethod") // Header, the count, the grid and three dialogs.
@Composable
internal fun ActorsScreen(onOpen: (SsPage) -> Unit) {
    val ctx = LocalSs.current
    val castState = cast()
    val navState = nav()
    var editing by remember { mutableStateOf<Rec?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Rec?>(null) }
    val crud = remember(ctx) { ContinuityCrud(ctx.api, "actors") }
    val rows = rememberRows(ctx) { crud.list() }
    val changed = {
        rows.reload(silent = true)
        castState.reload()
    }

    SsPageHeader(
        title = t("actors"),
        sub = t("actors_subtitle"),
        actions = {
            ZillitButton(
                text = t("add_actor"),
                onClick = { ctx.whenPost { creating = true } },
                leadingIcon = ZillitIcons.Add,
            )
        },
    )
    Await(rows) { records ->
        MutedText(count("actors_count", records.size))
        if (records.isEmpty()) {
            ZillitEmptyState(
                title = t("no_actors"),
                message = t("no_actors_help"),
                action = {
                    ZillitButton(
                        text = t("add_actor"),
                        onClick = { ctx.whenPost { creating = true } },
                        leadingIcon = ZillitIcons.Add,
                    )
                },
            )
        } else {
            CardGrid(records, minWidth = ACTOR_CARD) { actor ->
                ActorCard(
                    actor = actor,
                    // Only an actor with a character has anywhere to go.
                    onOpen = actor.str("character_id").takeIf { it.isNotBlank() }?.let { id ->
                        { navState.goOn(SsPage.Characters, SsView.Character(id), onOpen) }
                    },
                    onEdit = { editing = actor },
                    onDelete = { deleting = actor },
                )
            }
        }
    }

    if (creating || editing != null) {
        ActorForm(editing, onClose = { creating = false; editing = null }, onSaved = changed)
    }
    deleting?.let { actor ->
        DeleteDialog(
            title = t("delete_actor_title"),
            name = actor.str("name"),
            onClose = { deleting = null },
            onDone = changed,
            run = { crud.remove(actor.id) },
        )
    }
}

/**
 * One actor's card.
 *
 * Nothing filled in yet says so in a line, as the web does: a card with a
 * heading and five blanks reads as broken, while "no details yet" reads as
 * work to do.
 */
/**
 * What an actor's card has to show, worked out once.
 *
 * A value rather than nine conditions inside the card, because [blank] is the
 * question the web asks at the end — is there anything here at all? — and it
 * can only be answered after every other field has been.
 */
private class ActorDetails(actor: Rec) {
    val contact = listOf(actor.str("phone"), actor.str("email")).filter { it.isNotBlank() }.joinToString(" · ")

    val agent = actor.str("agent_name").takeIf { it.isNotBlank() }?.let { name ->
        actor.str("agent_contact").takeIf { it.isNotBlank() }?.let { "$name ($it)" } ?: name
    }.orEmpty()

    val hairEyes = listOf(actor.str("hair_color"), actor.str("eye_color"))
        .filter { it.isNotBlank() }
        .joinToString(" / ")

    private val measurements = actor.rec("measurements")

    /** Only the measurements somebody filled in; an empty one is not a tag. */
    val measured = Characters.MEASUREMENTS.filter { measurements?.str(it)?.isNotBlank() == true }

    fun measurement(key: String): String = measurements?.str(key).orEmpty()

    val allergies = actor.strings("allergies")

    val tattoos = actor.recs("tattoos")

    /** Nothing filled in yet: the card says so in a line instead of showing five blanks. */
    val blank: Boolean = contact.isBlank() && agent.isBlank() && hairEyes.isBlank() &&
        measured.isEmpty() && allergies.isEmpty() && tattoos.isEmpty()
}

@Composable
private fun ActorCard(actor: Rec, onOpen: (() -> Unit)?, onEdit: () -> Unit, onDelete: () -> Unit) {
    val details = ActorDetails(actor)

    ZillitSectionCard(
        modifier = Modifier.fillMaxWidth().let { if (onOpen != null) it.clickable(onClick = onOpen) else it },
        action = { RowActions(onEdit = onEdit, onDelete = onDelete) },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhotoAvatar(name = actor.str("name"), photo = actor.str("headshot_url"), size = CARD_AVATAR)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ZillitText(
                    text = actor.str("name").ifBlank { t("unnamed") },
                    style = ZillitTheme.typography.titleSmall,
                    maxLines = 1,
                )
                actor.str("character_name").takeIf { it.isNotBlank() }?.let {
                    MutedText(t("plays_name", "name" to it))
                }
            }
        }
        Field(t("contact"), details.contact)
        Field(t("agent"), details.agent)
        Field(t("hair_eyes"), details.hairEyes)
        if (details.measured.isNotEmpty()) {
            TagBand(t("measurements")) {
                details.measured.forEach { SsTag("${t("measure_$it")}: ${details.measurement(it)}") }
            }
        }
        if (details.allergies.isNotEmpty()) {
            TagBand(t("allergies")) { details.allergies.forEach { SsTag(it, StatusTone.Escalated) } }
        }
        if (details.tattoos.isNotEmpty()) {
            TagBand(t("tattoos")) {
                details.tattoos.forEach { mark ->
                    SsTag(
                        listOf(mark.str("location"), mark.str("description"))
                            .filter { it.isNotBlank() }
                            .joinToString(": "),
                    )
                }
            }
        }
        if (details.blank) MutedText(t("no_actor_details"))
    }
}

/** A band of tags under a small heading — the web's `tags(label, list)`. */
@Composable
private fun TagBand(label: String, tags: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(
            text = label.uppercase(),
            style = ZillitTheme.typography.labelSmall,
            color = ZillitTheme.colors.textSecondary,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        ) { tags() }
    }
}

/**
 * The actor form.
 *
 * Long on purpose: it is the one place the production writes down what
 * wardrobe and make-up will need on the day, and the web's modal is the same
 * four bands — who they are, their measurements, their marks, their allergies.
 *
 * ⚠ The marks are LISTS OF OBJECTS (`{ location, description }`), and the web
 * drops the rows where both are blank before sending. An empty row that got
 * through would show as a blank tag on the card for the rest of the shoot.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod") // The actor's four bands, as one form.
@Composable
private fun ActorForm(actor: Rec?, onClose: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalSs.current
    val castState = cast()
    val fields = remember(actor) {
        mutableStateOf(
            ACTOR_FIELDS.associateWith { actor?.str(it).orEmpty() },
        )
    }
    var characterId by remember(actor) { mutableStateOf(actor?.str("character_id").orEmpty()) }
    var measurements by remember(actor) {
        mutableStateOf(Characters.MEASUREMENTS.associateWith { actor?.rec("measurements")?.str(it).orEmpty() })
    }
    var marks by remember(actor) {
        mutableStateOf(
            Characters.MARKS.associateWith { key ->
                actor?.recs(key).orEmpty().map { it.str("location") to it.str("description") }
            },
        )
    }
    var allergies by remember(actor) { mutableStateOf(actor?.strings("allergies").orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    val set = { key: String, value: String -> fields.value = fields.value + (key to value) }

    FormDialog(
        open = true,
        title = t(if (actor == null) "add_actor" else "edit_actor"),
        onDismiss = onClose,
        confirmLabel = t("save"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val payload = body(
                    ACTOR_FIELDS.map { it to fields.value[it].orEmpty().trim() } + listOf(
                        "character_id" to characterId,
                        // Written beside the id so a card needs no join.
                        "character_name" to castState.characterName(characterId),
                        "measurements" to measurementsJson(measurements),
                        "allergies" to allergies,
                    ) + Characters.MARKS.map { key -> key to marksJson(marks[key].orEmpty()) },
                )
                val crud = ContinuityCrud(ctx.api, "actors")
                val done = ctx.write {
                    if (actor == null) crud.create(payload.dropEmpty()) else crud.update(actor.id, payload)
                }
                saving = false
                if (done != null) {
                    onClose()
                    onSaved()
                }
            }
        },
        confirmEnabled = fields.value["name"].orEmpty().isNotBlank(),
        busy = saving,
        width = FORM_WIDTH,
    ) {
        FormGrid {
            TextInput(fields.value["name"].orEmpty(), { set("name", it) }, t("actor_name"))
            PickInput(
                value = characterId,
                options = castState.options,
                onChange = { characterId = it },
                label = t("plays_character"),
                placeholder = t("link_a_character"),
                clearable = true,
            )
            CONTACT_FIELDS.forEach { key ->
                TextInput(fields.value[key].orEmpty(), { set(key, it) }, t("actor_$key"))
            }
        }
        ZillitSectionLabel(t("measurements"))
        FormGrid {
            Characters.MEASUREMENTS.forEach { key ->
                TextInput(
                    measurements[key].orEmpty(),
                    { measurements = measurements + (key to it) },
                    t("measure_$key"),
                    FormHalf,
                )
            }
        }
        ZillitSectionLabel(t("distinguishing_marks"))
        Characters.MARKS.forEach { key ->
            MarkList(
                label = t(key),
                addLabel = t("add_$key"),
                rows = marks[key].orEmpty(),
                onChange = { marks = marks + (key to it) },
            )
        }
        ZillitSectionLabel(t("allergies"))
        FormGrid {
            TagInput(
                tokens = allergies,
                onChange = { allergies = it },
                label = null,
                modifier = FormWide,
                placeholder = t("allergies_help"),
            )
            TextInput(
                fields.value["notes"].orEmpty(),
                { set("notes", it) },
                t("notes"),
                FormWide,
                multiline = true,
            )
        }
    }
}

/** One kind of mark: a location and a description per row, added and dropped by hand. */
@Composable
private fun MarkList(
    label: String,
    addLabel: String,
    rows: List<Pair<String, String>>,
    onChange: (List<Pair<String, String>>) -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(label, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        rows.forEachIndexed { index, (location, description) ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextInput(
                    location,
                    { onChange(rows.set(index, it to description)) },
                    label = null,
                    modifier = Modifier.weight(1f),
                    placeholder = t("mark_location"),
                )
                TextInput(
                    description,
                    { onChange(rows.set(index, location to it)) },
                    label = null,
                    modifier = Modifier.weight(1f),
                    placeholder = t("description"),
                )
                ZillitIconButton(
                    icon = ZillitIcons.Close,
                    contentDescription = t("delete"),
                    onClick = { onChange(rows.filterIndexed { at, _ -> at != index }) },
                )
            }
        }
        ZillitButton(
            text = addLabel,
            onClick = { onChange(rows + ("" to "")) },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

private fun List<Pair<String, String>>.set(at: Int, value: Pair<String, String>): List<Pair<String, String>> =
    mapIndexed { index, old -> if (index == at) value else old }

private fun measurementsJson(values: Map<String, String>): JsonObject = buildJsonObject {
    values.forEach { (key, value) -> put(key, value.trim()) }
}

/** The web's filter: a row with neither a location nor a description is not a mark. */
private fun marksJson(rows: List<Pair<String, String>>) = buildJsonArray {
    rows.filter { (location, description) -> location.isNotBlank() || description.isNotBlank() }
        .forEach { (location, description) ->
            add(
                buildJsonObject {
                    put("location", location.trim())
                    put("description", description.trim())
                },
            )
        }
}

/** The plain text fields a create may send — the web's `FIELDS`, less the built ones. */
private val ACTOR_FIELDS = listOf(
    "name",
    "email",
    "phone",
    "agent_name",
    "agent_contact",
    "hair_color",
    "eye_color",
    "skin_tone",
    "notes",
)

/** The six the form puts in two rows of three, in the web's order. */
private val CONTACT_FIELDS = listOf(
    "phone",
    "email",
    "agent_name",
    "agent_contact",
    "hair_color",
    "eye_color",
    "skin_tone",
)

private val CARD_AVATAR = 38.dp
private val ACTOR_CARD = 340.dp
private val FORM_WIDTH = 760.dp
