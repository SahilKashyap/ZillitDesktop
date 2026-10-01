package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.designsystem.ZillitTheme
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.icon.AhIcons
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.ui.InkButton
import com.zillit.desktop.feature.costumesetsync.ui.StackedPick
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fmtTime
import com.zillit.desktop.feature.costumesetsync.ui.ChipRow
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.numOrNull
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch

/** Which of a costume's forms is open. */
private enum class ActionForm { Move, Cleaning, Emergency, EmergencyResult, Damage, Alteration, Missing }

/** The movement a form confirms: the service's action name and where the piece goes by default. */
private class Movement(val action: String, val location: String)

private val ACTIVE = setOf("AVAILABLE", "ISSUED", "ON_SET")

/** What an emergency raised: the ticket, the alternatives on offer, and the replacement if one was already issued. */
private class EmergencyOutcome(val request: Rec, val alternatives: List<Rec>, val replacement: Rec?)

/**
 * Everything that can happen to one costume, as the reference app's `CostumeActions`: the buttons
 * offered depend on its status (an AVAILABLE piece can be issued or sent to set; an ISSUED one goes
 * on set or comes back; a MISSING one can be found…), and each opens its own short form.
 *
 * Shared by the costume page and Scan. [sceneId] / [takeNumber] prefill every form (Scan's "Context
 * for actions"). [go] navigates relative to the tool root. `only = "emergency"` draws no buttons and
 * opens straight on the emergency form (the Dashboard and the Sink use it once a piece is picked);
 * [onClosed] then tells them it has gone.
 */
@Composable
fun CostumeActions(
    costume: Rec,
    onChanged: () -> Unit,
    go: (String) -> Unit,
    modifier: Modifier = Modifier,
    sceneId: String = "",
    takeNumber: String = "",
    openCleaningId: String = "",
    openAlterationId: String = "",
    only: String? = null,
    onClosed: () -> Unit = {},
) {
    var form by remember { mutableStateOf(if (only == "emergency") ActionForm.Emergency else null) }
    var movement by remember { mutableStateOf(Movement("ISSUE", "")) }
    var outcome by remember { mutableStateOf<EmergencyOutcome?>(null) }
    val close = {
        form = null
        if (only != null) onClosed()
    }
    val done = {
        onChanged()
        close()
    }
    val move = { action: String, location: String ->
        movement = Movement(action, location)
        form = ActionForm.Move
    }
    if (only == null) {
        ActionButtons(costume, modifier, openCleaningId, openAlterationId, go, move) { form = it }
    }
    when (form) {
        ActionForm.Move -> MovementDialog(costume, movement, sceneId, takeNumber, close, done)
        ActionForm.Cleaning, ActionForm.Emergency -> CleaningDialog(
            costume = costume,
            emergency = form == ActionForm.Emergency,
            sceneId = sceneId,
            takeNumber = takeNumber,
            onClose = close,
            onFiled = { filedId, created ->
                if (form == ActionForm.Emergency) {
                    val raised = created?.rec ?: Rec.Empty
                    outcome = EmergencyOutcome(raised.rec("request") ?: raised, raised.recs("alternatives"), raised.rec("replacement"))
                    onChanged()
                    form = ActionForm.EmergencyResult
                } else {
                    done()
                    if (filedId.isNotBlank()) go("cleaning/$filedId")
                }
            },
        )
        ActionForm.EmergencyResult -> outcome?.let { EmergencyResultDialog(costume, it, close, go) { next -> outcome = next } }
        ActionForm.Damage -> DamageDialog(costume, sceneId, takeNumber, close, done)
        ActionForm.Alteration -> AlterationDialog(costume, close, done)
        ActionForm.Missing -> MissingDialog(costume, close, done)
        null -> Unit
    }
}

@Composable
private fun ActionButtons(
    costume: Rec,
    modifier: Modifier,
    openCleaningId: String,
    openAlterationId: String,
    go: (String) -> Unit,
    move: (String, String) -> Unit,
    open: (ActionForm) -> Unit,
) {
    val ctx = LocalSync.current
    val status = costume.str("status")
    // `.csync-buttonrow`: 6dp between buttons. Ink-filled ones are the status's main action.
    ChipRow(modifier, gap = BUTTON_GAP) {
        if (status == "CLEANING") {
            InkButton(t("csync_in_cleaning_view"), onClick = { go(if (openCleaningId.isNotBlank()) "cleaning/$openCleaningId" else "cleaning") }, icon = AhIcons.Refresh)
        }
        if (status == "ALTERATION") {
            InkButton(t("csync_with_tailor_view"), onClick = { go("alterations") }, icon = AhIcons.Cut)
        }
        if (ctx.canPost) {
            @Composable fun button(label: String, icon: ImageVector, ink: Boolean = false, onClick: () -> Unit) =
                if (ink) InkButton(label, onClick = onClick, icon = icon) else ZillitButton(label, onClick = onClick, variant = ButtonVariant.Secondary, leadingIcon = icon)
            // The movements this status allows, in the reference's order.
            when (status) {
                "AVAILABLE" -> {
                    button(t("csync_act_issue"), ZillitIcons.Inbox, ink = true) { move("ISSUE", "Actor") }
                    button(t("csync_act_send_to_set"), AhIcons.Video) { move("TO_SET", "Set") }
                }
                "ISSUED" -> {
                    button(t("csync_act_on_set"), AhIcons.Video, ink = true) { move("TO_SET", "Set") }
                    button(t("csync_act_return"), ZillitIcons.Inbox) { move("RETURN", "Wardrobe Truck") }
                }
                "ON_SET" -> button(t("csync_act_return_to_wardrobe"), ZillitIcons.Inbox, ink = true) { move("RETURN", "Wardrobe Truck") }
                "MISSING" -> button(t("csync_act_found"), AhIcons.MapPin, ink = true) { move("FOUND", "Wardrobe Truck") }
                "DAMAGED" -> button(t("csync_act_repaired"), ZillitIcons.Inbox, ink = true) { move("REPAIRED", "Wardrobe Truck") }
                "RETURNED_TO_VENDOR", "RETIRED" -> button(t("csync_act_receive_back"), ZillitIcons.Inbox, ink = true) { move("RECEIVED", "Warehouse") }
            }
            if (status in ACTIVE) {
                ZillitButton(t("csync_act_emergency_clean"), onClick = { open(ActionForm.Emergency) }, variant = ButtonVariant.Danger, leadingIcon = ZillitIcons.Siren)
                button(t("csync_act_move"), ZillitIcons.Forward) { move("MOVE", "") }
            }
            if (status in ACTIVE || status == "DAMAGED") button(t("csync_act_report_damage"), AhIcons.AlertTriangle) { open(ActionForm.Damage) }
            if (status != "MISSING" && status != "RETIRED") button(t("csync_act_mark_missing"), AhIcons.AlertCircle) { open(ActionForm.Missing) }
            if (status in ACTIVE) {
                button(t("csync_act_request_cleaning"), AhIcons.Refresh) { open(ActionForm.Cleaning) }
                button(t("csync_act_alteration"), AhIcons.Cut) { open(ActionForm.Alteration) }
            }
            // Managers only, as the reference (its FINANCE_ROLES are its manager roles).
            if (ctx.isFinance && status in setOf("AVAILABLE", "DAMAGED")) button(t("csync_act_retire"), AhIcons.Archive) { move("RETIRE", "") }
        }
    }
}

private val BUTTON_GAP = 6.dp

/** Issue / return / move / to-set / found / repaired / receive / retire: `POST /costumes/{id}/actions`. */
@Composable
private fun MovementDialog(costume: Rec, movement: Movement, sceneId: String, takeNumber: String, onClose: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    var location by remember { mutableStateOf(movement.location) }
    var scene by remember { mutableStateOf(sceneId) }
    var take by remember { mutableStateOf(takeNumber) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val action = movement.action
    val standard = ctx.metaList("standard_locations")
    val options = (standard + listOfNotNull(location.takeIf { it.isNotBlank() && it !in standard })).map { it to it }
    val scenes = rememberResource { api.get("/scenes").mapRows() }
    FormDialog(
        open = true,
        title = "${tEnum(action)} · ${costume.str("asset_number")}",
        onDismiss = onClose,
        confirmLabel = t("csync_confirm"),
        onConfirm = {
            scope.launch {
                busy = true
                val answer = ctx.write {
                    ctx.api.post(
                        "/costumes/${costume.id}/actions",
                        body("action" to action, "to_location" to location, "scene_id" to scene, "take_number" to numOrNull(take)?.toLong(), "note" to note),
                    )
                }
                busy = false
                if (answer != null) onDone()
            }
        },
        confirmEnabled = !(action == "MOVE" && location.isBlank()),
        busy = busy,
        ink = true,
    ) {
        FormGrid {
            if (action != "RETIRE") {
                StackedPick(location, options, { location = it }, location, { location = it }, t("csync_to_location"), FormWide, placeholder = t("csync_choose_dash"))
            }
            if (action in setOf("ISSUE", "TO_SET", "RETURN")) {
                val sceneOptions = scenes.value.orEmpty().map { s ->
                    s.id to "${t("csync_sc")} ${s.str("number")}${s.str("name").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()}"
                }
                PickInput(scene, sceneOptions, { scene = it }, t("csync_field_scene"), placeholder = "—")
                TextInput(take, { take = it }, t("csync_field_take"), number = true)
            }
            TextInput(note, { note = it }, t("csync_note"), FormWide)
        }
    }
}

/** The emergency's outcome: the piece is now in cleaning, a replacement if one was issued, and the alternatives to assign. */
@Composable
private fun EmergencyResultDialog(costume: Rec, outcome: EmergencyOutcome, onClose: () -> Unit, go: (String) -> Unit, onOutcome: (EmergencyOutcome) -> Unit) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    FormDialog(
        open = true,
        title = t("csync_emergency_raised"),
        onDismiss = onClose,
        confirmLabel = t("csync_open_ticket"),
        onConfirm = {
            onClose()
            go("cleaning/${outcome.request.id}")
        },
        confirmEnabled = outcome.request.id.isNotBlank(),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            ZillitText(t("csync_emergency_now_cleaning", "asset" to costume.str("asset_number")))
            val replacement = outcome.replacement
            if (replacement != null) {
                MutedText(
                    t("csync_replacement_issued", "asset" to replacement.str("asset_number"), "name" to replacement.str("name")),
                    maxLines = 3,
                )
            } else if (outcome.alternatives.isEmpty()) {
                MutedText(t("csync_no_replacement", "time" to fmtTime(outcome.request.long("expected_ready_at")).ifBlank { "—" }), maxLines = 3)
            }
            if (outcome.alternatives.isNotEmpty()) {
                ZillitText(t("csync_available_alternatives"), style = ZillitTheme.typography.titleSmall)
                outcome.alternatives.forEach { alt ->
                    CostumeRow(
                        alt,
                        extra = alt.doubleOrNull("match_score")?.let { " · " + t("csync_match_n", "n" to it.toLong()) }.orEmpty(),
                        end = {
                            if (replacement?.id == alt.id) {
                                StatusBadge("READY", t("csync_assigned"))
                            } else {
                                ZillitButton(
                                    t("csync_assign"),
                                    onClick = {
                                        scope.launch {
                                            busy = true
                                            val answer = ctx.write { ctx.api.post("/cleaning/${outcome.request.id}/replacement", body("costume_id" to alt.id)) }
                                            busy = false
                                            if (answer != null) onOutcome(EmergencyOutcome(outcome.request, outcome.alternatives, answer.rec ?: alt))
                                        }
                                    },
                                    size = ButtonSize.Small,
                                    enabled = !busy && replacement == null,
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}
