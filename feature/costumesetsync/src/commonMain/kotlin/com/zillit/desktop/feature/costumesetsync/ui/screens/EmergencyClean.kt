package com.zillit.desktop.feature.costumesetsync.ui.screens

import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.fill
import com.zillit.desktop.feature.costumesetsync.domain.fmtTime
import com.zillit.desktop.feature.costumesetsync.domain.isSameDay
import com.zillit.desktop.feature.costumesetsync.domain.todayParam
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.ui.CostumeRow
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormCell
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.MediaPicker
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.attachMedia
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.numOrNull
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch

/** Only a piece in use can go for an emergency clean — the same rule the costume page applies. */
private val IN_USE = setOf("AVAILABLE", "ISSUED", "ON_SET")
private const val DEFAULT_CLEANING_TYPE = "SPOT_CLEANING"

/** What `POST /cleaning/emergency` answered: the ticket, the pieces that could stand in, and the one already issued. */
private class Raised(val request: Rec, val alternatives: List<Rec>, val replacement: Rec?)

/**
 * The Emergency flow (the web's `EmergencyClean`, which mounts `CostumeActions only="emergency"`): pick the
 * stained piece from the inventory, then the emergency-clean dialog (problem, stain photos, find a
 * replacement), then what the service did about it. Shared by the Dashboard and the Sink screen.
 *
 * The desktop runs the flow here against `POST /cleaning/emergency` — the same fields the costume page's
 * emergency dialog sends. [onChanged] fires once a ticket is raised (and again when a replacement is
 * assigned) so the caller reloads its board.
 */
@Composable
fun EmergencyCleanDialog(open: Boolean, onClose: () -> Unit, onChanged: () -> Unit) {
    var costume by remember { mutableStateOf<Rec?>(null) }
    // The scene the stain happened in, as Scan's emergency mode assumes it: the one shooting now, else
    // today's first. The dialog still lets it be changed.
    var sceneId by remember { mutableStateOf("") }
    var raised by remember { mutableStateOf<Raised?>(null) }
    // The picker closes itself right after a pick; that close must not end the flow.
    val picked = remember { mutableStateOf(false) }
    val ctx = LocalSync.current

    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        val scenes = (ctx.api.get("/scenes") as? ZillitResult.Success)?.data?.rows.orEmpty()
        val today = todayParam(ctx.now())
        val now = scenes.firstOrNull { it.str("status") == "SHOOTING" } ?: scenes.firstOrNull {
            sameDay(it, ctx.now(), today)
        }
        sceneId = now?.id.orEmpty()
    }
    val done = {
        picked.value = false
        costume = null
        raised = null
        onClose()
    }

    WfCostumePicker(
        open = open && costume == null,
        onClose = { if (!picked.value) done() },
        title = t("csync_emergency_pick_piece"),
        exclude = { it.str("status") !in IN_USE },
        onPick = { picked.value = true; costume = it },
    )
    val chosen = costume
    if (open && chosen != null) {
        val result = raised
        if (result == null) {
            EmergencyForm(chosen, sceneId, onClose = done, onRaised = { raised = it; onChanged() })
        } else {
            EmergencyResult(chosen, result, onClose = done, onChanged = onChanged)
        }
    }
}

private fun sameDay(scene: Rec, now: Long, today: String): Boolean {
    val ms = scene.long("shoot_date")
    return if (ms != 0L) isSameDay(ms, now) else scene.str("shoot_date").startsWith(today)
}

/** POSTs the emergency (`ticket` = problem, cleaning type, scene id); null when the server refused. */
private suspend fun raiseEmergency(
    ctx: SyncCtx,
    costume: Rec,
    ticket: List<String>,
    take: Long?,
    auto: Boolean,
): Raised? {
    val answer = ctx.write {
        ctx.api.post(
            "/cleaning/emergency",
            body(
                "costume_id" to costume.id,
                "problem" to ticket[0],
                "cleaning_type" to ticket[1],
                "scene_id" to ticket[2],
                "take_number" to take,
                "auto_assign_replacement" to auto,
            ),
        )
    }
    val data = answer?.rec ?: return null
    return Raised(data.rec("request") ?: data, data.recs("alternatives"), data.rec("replacement"))
}

/** A form column: the field's label over its control. */
@Composable
private fun LabelledField(label: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitText(label, style = ZillitTheme.typography.label, color = ZillitTheme.colors.textSecondary)
        content()
    }
}

@Composable
private fun EmergencyButtons(onClose: () -> Unit, busy: Boolean, canRaise: Boolean, onRaise: () -> Unit) {
    ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary, enabled = !busy)
    ZillitButton(
        t("csync_raise_emergency"),
        onClick = onRaise,
        variant = ButtonVariant.Danger,
        enabled = canRaise && !busy,
        loading = busy,
    )
}

@Composable
private fun EmergencyForm(costume: Rec, initialScene: String, onClose: () -> Unit, onRaised: (Raised) -> Unit) {
    val ctx = LocalSync.current
    var problem by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(DEFAULT_CLEANING_TYPE) }
    var sceneId by remember(initialScene) { mutableStateOf(initialScene) }
    var take by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(true) }
    var media by remember { mutableStateOf(emptyList<MediaEntry>()) }
    var busy by remember { mutableStateOf(false) }
    // The ticket is raised once; a failed upload retried from the same form only re-sends the photos.
    var filed by remember { mutableStateOf<Raised?>(null) }

    val submit = {
        busy = true
        ctx.scope.launch {
            var result = filed
            if (result == null) {
                val ticket = listOf(problem.trim(), type, sceneId)
                result = raiseEmergency(ctx, costume, ticket, numOrNull(take)?.toLong(), auto)
                if (result == null) {
                    busy = false
                    return@launch
                }
                filed = result
            }
            val failed = ctx.attachMedia(media, "CLEANING", result.request.id, "STAIN", keep = { media = it })
            busy = false
            if (failed > 0) ctx.toast(t("csync_saved_media_failed", "n" to failed), false) else onRaised(result)
        }
    }

    WfFormDialog(
        open = true,
        title = "${t("csync_emergency_cleaning")} · ${costume.str("asset_number")}",
        icon = ZillitIcons.Siren,
        onDismiss = onClose,
        actions = { EmergencyButtons(onClose, busy, problem.isNotBlank()) { submit() } },
    ) {
        WfNotice(t("csync_emergency_explainer"))
        FormGrid {
            TextInput(problem, { problem = it }, t("csync_field_problem"), FormWide)
            EnumInput(type, ctx.metaList("cleaning_types"), { type = it }, t("csync_field_cleaning_type"))
            LabelledField(t("csync_field_priority"), FormCell) {
                StatusBadge("URGENT", label = tEnum("URGENT"), large = true)
            }
            SceneSelect(sceneId, { sceneId = it }, t("csync_field_scene"))
            TextInput(take, { take = it.filter(Char::isDigit) }, t("csync_field_take"), number = true)
            LabelledField(t("csync_photos_and_video"), FormWide) {
                MediaPicker(media, { media = it }, enabled = !busy, help = t("csync_shoot_stain_hint"))
            }
            ZillitCheckbox(auto, { auto = it }, FormWide, label = t("csync_auto_assign_replacement"))
        }
    }
}

/** What happened to the replacement: issued already, or none on the shelf and when the piece is back. */
@Composable
private fun ReplacementNotice(issued: Rec?, result: Raised) {
    when {
        issued != null -> WfNotice(
            fill(
                t("csync_replacement_issued"),
                "asset" to issued.str("asset_number"),
                "name" to issued.str("name"),
            ),
            info = true,
        )
        result.alternatives.isEmpty() -> WfNotice(
            fill(
                t("csync_no_replacement"),
                "time" to fmtTime(result.request.long("expected_ready_at")).ifBlank { "—" },
            ),
        )
    }
}

@Composable
private fun EmergencyResult(costume: Rec, result: Raised, onClose: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalSync.current
    var replacement by remember { mutableStateOf(result.replacement) }
    var busy by remember { mutableStateOf(false) }
    WfFormDialog(
        open = true,
        title = t("csync_emergency_raised"),
        onDismiss = onClose,
        actions = {
            ZillitButton(t("csync_close"), onClick = onClose, variant = ButtonVariant.Secondary)
            if (result.request.id.isNotBlank()) {
                ZillitButton(
                    t("csync_open_ticket"),
                    onClick = { onClose(); ctx.nav.go("cleaning/${result.request.id}") },
                    variant = ButtonVariant.Secondary,
                )
            }
        },
    ) {
        WfNotice(fill(t("csync_emergency_now_cleaning"), "asset" to costume.str("asset_number")), ok = true)
        val issued = replacement
        ReplacementNotice(issued, result)
        if (result.alternatives.isNotEmpty()) {
            ZillitText(
                t("csync_available_alternatives"),
                style = ZillitTheme.typography.titleSmall.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                ),
            )
            result.alternatives.forEach { a ->
                AlternativeRow(a, issued, busy) {
                    busy = true
                    ctx.scope.launch {
                        val answer = ctx.write {
                            ctx.api.post("/cleaning/${result.request.id}/replacement", body("costume_id" to a.id))
                        }
                        busy = false
                        if (answer != null) {
                            replacement = answer.rec ?: a
                            onChanged()
                        }
                    }
                }
            }
        }
    }
}

/** One suggested replacement with its match score, and the button that assigns it (or the badge once it is). */
@Composable
private fun AlternativeRow(a: Rec, issued: Rec?, busy: Boolean, onAssign: () -> Unit) {
    CostumeRow(
        a,
        extra = if (a.has("match_score")) {
            " · ${fill(t("csync_match_n"), "n" to a.str("match_score"))}"
        } else {
            ""
        },
        end = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (issued?.id == a.id) {
                    StatusBadge("READY", label = t("csync_assigned"))
                } else {
                    WfInkButton(t("csync_assign"), on = true, onClick = onAssign, enabled = !busy && issued == null)
                }
            }
        },
    )
}
