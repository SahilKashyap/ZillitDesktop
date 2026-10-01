package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormCell
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch

/**
 * Dropdowns that pick a scene, character, vendor or actor, each opening with
 * "+ New …" so a missing one is created on the spot and selected without
 * leaving the form that needed it — the web's `QuickSelects` / `ActorSelect`.
 * Only people who can post see the option.
 *
 * Each takes the list from its screen when it has one ([rows]), or loads it
 * itself; whatever it creates is added locally so it shows at once, and
 * `onCreated` lets the screen refresh its own list. The empty value is "none".
 */
private const val NEW = "__new__"
private const val DIALOG_WIDTH = 520
private val Half = Modifier.width(220.dp)
private val Full = Modifier.width(472.dp)

/** Rows of [path], loaded once unless the caller already has them ([given]); plus what this field created. */
@Composable
private fun rememberPickRows(path: String, given: List<Rec>?): Pair<List<Rec>, (Rec) -> Unit> {
    val ctx = LocalSync.current
    var own by remember { mutableStateOf(emptyList<Rec>()) }
    var created by remember { mutableStateOf(emptyList<Rec>()) }
    LaunchedEffect(path, given == null) {
        if (given == null) own = (ctx.api.get(path).mapRows() as? ZillitResult.Success)?.data.orEmpty()
    }
    val base = given ?: own
    val seen = base.map { it.id }.toSet()
    return (base + created.filter { it.id !in seen }) to { rec: Rec -> created = created + rec }
}

@Composable
private fun QuickPick(
    value: String,
    options: List<Pair<String, String>>,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier,
    newLabel: String,
    placeholder: String,
    onNew: () -> Unit,
) {
    val ctx = LocalSync.current
    val all = (if (ctx.canPost) listOf(NEW to "+ $newLabel") else emptyList()) + listOf("" to "—") + options
    PickInput(value, all, { if (it == NEW) onNew() else onChange(it) }, label, modifier, placeholder)
}

/** "SC 12 · Kitchen" — a scene's option text. */
internal fun sceneOptionLabel(scene: Rec): String =
    "${t("csync_sc")} ${scene.str("number")}" + scene.str("name").let { if (it.isEmpty()) "" else " · $it" }

@Composable
fun SceneSelect(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = FormCell,
    onCreated: (Rec) -> Unit = {},
    rows: List<Rec>? = null,
    filter: (Rec) -> Boolean = { true },
) {
    val (list, addCreated) = rememberPickRows("/scenes", rows)
    var open by remember { mutableStateOf(false) }
    QuickPick(value, list.filter(filter).map { it.id to sceneOptionLabel(it) }, onChange, label, modifier, t("csync_new_scene"), "—") { open = true }
    NewSceneDialog(open, { open = false }) { scene ->
        addCreated(scene)
        onChange(scene.id)
        onCreated(scene)
    }
}

/**
 * Just enough to put a missing scene in the breakdown; the rest is filled in
 * on the scene itself. `pages` is a STRING on a scene, so it is not sent here.
 */
@Composable
fun NewSceneDialog(open: Boolean, onClose: () -> Unit, onCreated: (Rec) -> Unit) {
    val ctx = LocalSync.current
    var number by remember(open) { mutableStateOf("") }
    var intExt by remember(open) { mutableStateOf("") }
    var location by remember(open) { mutableStateOf("") }
    var timeOfDay by remember(open) { mutableStateOf("") }
    var synopsis by remember(open) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_new_scene"),
        onDismiss = onClose,
        confirmLabel = t("csync_add"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val answer = ctx.write {
                    ctx.api.post(
                        "/scenes",
                        body(
                            "number" to number.trim(),
                            "int_ext" to intExt,
                            "location" to location.trim(),
                            "time_of_day" to timeOfDay,
                            "synopsis" to synopsis.trim(),
                        ),
                    )
                }
                saving = false
                if (answer != null) {
                    answer.rec?.takeIf { it.id.isNotEmpty() }?.let(onCreated)
                    onClose()
                }
            }
        },
        confirmEnabled = number.isNotBlank(),
        busy = saving,
        width = DIALOG_WIDTH.dp,
    ) {
        FormGrid {
            TextInput(number, { number = it }, t("csync_field_scene_hash"), Half)
            EnumInput(intExt, ctx.metaList("int_ext").ifEmpty { listOf("INT", "EXT", "INT/EXT") }, { intExt = it }, t("csync_field_int_ext"), Half, "—")
            TextInput(location, { location = it }, t("csync_field_location"), Half)
            EnumInput(timeOfDay, ctx.metaList("times_of_day"), { timeOfDay = it }, t("csync_field_time_of_day"), Half, "—")
            TextInput(synopsis, { synopsis = it }, t("csync_field_description"), Full, multiline = true)
        }
    }
}

@Composable
fun CharacterSelect(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = FormCell,
    onCreated: (Rec) -> Unit = {},
    rows: List<Rec>? = null,
    filter: (Rec) -> Boolean = { true },
) {
    val (list, addCreated) = rememberPickRows("/characters", rows)
    var open by remember { mutableStateOf(false) }
    val options = list.filter(filter).map { c -> c.id to (if (c.has("cast_number")) "${c.str("cast_number")}. " else "") + c.str("name") }
    QuickPick(value, options, onChange, label, modifier, t("csync_new_character"), "—") { open = true }
    NewCharacterDialog(open, { open = false }) { character ->
        if (character.id.isEmpty()) return@NewCharacterDialog
        addCreated(character)
        onChange(character.id)
        onCreated(character)
    }
}

@Composable
fun VendorSelect(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = FormCell,
    onCreated: (Rec) -> Unit = {},
    rows: List<Rec>? = null,
) {
    val (list, addCreated) = rememberPickRows("/vendors", rows)
    var open by remember { mutableStateOf(false) }
    QuickPick(value, list.map { it.id to it.str("name") }, onChange, label, modifier, t("csync_new_vendor"), "—") { open = true }
    NewVendorDialog(open, { open = false }) { vendor ->
        addCreated(vendor)
        onChange(vendor.id)
        onCreated(vendor)
    }
}

/** A vendor with a name, and optionally who to call. An empty field is sent as not set. */
@Composable
fun NewVendorDialog(open: Boolean, onClose: () -> Unit, onCreated: (Rec) -> Unit) {
    val ctx = LocalSync.current
    var name by remember(open) { mutableStateOf("") }
    var contact by remember(open) { mutableStateOf("") }
    var phone by remember(open) { mutableStateOf("") }
    var email by remember(open) { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    FormDialog(
        open = open,
        title = t("csync_new_vendor"),
        onDismiss = onClose,
        confirmLabel = t("csync_add"),
        onConfirm = {
            saving = true
            ctx.scope.launch {
                val answer = ctx.write {
                    ctx.api.post("/vendors", body("name" to name.trim(), "contact_name" to contact.trim(), "phone" to phone.trim(), "email" to email.trim()))
                }
                saving = false
                if (answer != null) {
                    answer.rec?.takeIf { it.id.isNotEmpty() }?.let(onCreated)
                    onClose()
                }
            }
        },
        confirmEnabled = name.isNotBlank(),
        busy = saving,
        width = DIALOG_WIDTH.dp,
    ) {
        FormGrid {
            TextInput(name, { name = it }, t("csync_field_name"), Full)
            TextInput(contact, { contact = it }, t("csync_field_contact"), Half)
            TextInput(phone, { phone = it }, t("csync_field_phone"), Half)
            TextInput(email, { email = it }, t("csync_field_email"), Full)
        }
    }
}

/**
 * Actor dropdown with "+ New actor" first. It opens the same Create Actor form
 * the Actors page uses, and the actor it creates is picked straight back into
 * this field. [options] is (id, name); [quick] (the breakdown's Cast name
 * pickers) asks only for the name. Clearing means no actor; the empty box
 * reads "— unassigned —" unless [placeholder] says else.
 */
@Composable
fun ActorSelect(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = FormCell,
    onCreated: (Rec) -> Unit = {},
    quick: Boolean = false,
    placeholder: String = t("csync_unassigned_dash"),
) {
    var open by remember { mutableStateOf(false) }
    var created by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    val seen = options.map { it.first }.toSet()
    QuickPick(value, options + created.filter { it.first !in seen }, onChange, label, modifier, t("csync_new_actor"), placeholder) { open = true }
    ActorFormDialog(
        open = open,
        onClose = { open = false },
        saveLabel = t("csync_create_and_assign"),
        allowAddAnother = false,
        quick = quick,
        onSaved = { actor ->
            if (actor.id.isNotEmpty()) {
                created = created + (actor.id to actor.str("name"))
                onChange(actor.id)
                onCreated(actor)
            }
        },
    )
}
