package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.ui.FormCell
import com.zillit.desktop.feature.costumesetsync.ui.Notice
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.feature.costumesetsync.data.Answer
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.EnumInput
import com.zillit.desktop.feature.costumesetsync.ui.FormDialog
import com.zillit.desktop.feature.costumesetsync.ui.FormGrid
import com.zillit.desktop.feature.costumesetsync.ui.FormWide
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MediaEntry
import com.zillit.desktop.feature.costumesetsync.ui.MediaPicker
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.PickInput
import com.zillit.desktop.feature.costumesetsync.ui.StatusBadge
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.TextInput
import com.zillit.desktop.feature.costumesetsync.ui.attachMedia
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.mapRows
import com.zillit.desktop.feature.costumesetsync.ui.numOrNull
import com.zillit.desktop.feature.costumesetsync.ui.rememberResource
import com.zillit.desktop.feature.costumesetsync.ui.t
import com.zillit.desktop.feature.costumesetsync.ui.tEnum
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonObject

/**
 * The report forms behind a costume's action buttons: cleaning request, emergency, damage and
 * alteration (the web's `CostumeActions` modals). Each is filed ONCE: if its photos then fail,
 * pressing the button again retries only the photos ([ReportFiling]).
 */

@Composable
private fun SceneTakeFields(sceneId: String, onScene: (String) -> Unit, take: String, onTake: (String) -> Unit) {
    val scenes = rememberResource { api.get("/scenes").mapRows() }
    val options = scenes.value.orEmpty().map { scene ->
        val suffix = scene.str("name").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        scene.id to "${t("csync_sc")} ${scene.str("number")}$suffix"
    }
    PickInput(sceneId, options, onScene, t("csync_field_scene"), placeholder = "—")
    TextInput(take, onTake, t("csync_field_take"), number = true)
}

@Composable
private fun ReportMedia(filing: ReportFiling, help: String) {
    ZillitText(
        t("csync_photos_and_video"),
        style = ZillitTheme.typography.label,
        color = ZillitTheme.colors.textSecondary,
    )
    MediaPicker(filing.media, { filing.media = it }, enabled = !filing.busy, help = help)
}

private fun takeOf(text: String): Long? = numOrNull(text)?.toLong()

@Composable
private fun EmergencyNotice() {
    Notice {
        ZillitText(
            t("csync_emergency_explainer"),
            style = ZillitTheme.typography.bodyLarge.copy(fontSize = 14.sp),
        )
    }
}

/** An emergency is always urgent, so it shows the badge; a normal request picks a priority. */
@Composable
private fun CleaningPriority(emergency: Boolean, priority: String, onPriority: (String) -> Unit) {
    if (emergency) {
        Column(FormCell, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(
                t("csync_field_priority"),
                style = ZillitTheme.typography.label,
                color = ZillitTheme.colors.textSecondary,
            )
            StatusBadge("URGENT", tEnum("URGENT"), large = true)
        }
    } else {
        EnumInput(priority, LocalSync.current.metaList("priorities"), onPriority, t("csync_field_priority"))
    }
}

/** "Request cleaning" (priority chosen) and "Emergency cleaning" (always URGENT, offers a replacement). */
@Composable
internal fun CleaningDialog(
    costume: Rec,
    emergency: Boolean,
    sceneId: String,
    takeNumber: String,
    onClose: () -> Unit,
    onFiled: (filedId: String, created: Answer?) -> Unit,
) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val filing = remember { ReportFiling(ctx, "CLEANING", "STAIN") }
    var problem by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("SPOT_CLEANING") }
    var priority by remember { mutableStateOf("NORMAL") }
    var scene by remember { mutableStateOf(sceneId) }
    var take by remember { mutableStateOf(takeNumber) }
    var autoAssign by remember { mutableStateOf(true) }
    val asset = costume.str("asset_number")
    FormDialog(
        open = true,
        title = "${t(if (emergency) "csync_emergency_cleaning" else "csync_act_request_cleaning")} · $asset",
        onDismiss = onClose,
        confirmLabel = if (emergency) t("csync_raise_emergency") else t("csync_request"),
        onConfirm = {
            scope.launch {
                val request = body(
                    "costume_id" to costume.id,
                    "problem" to problem,
                    "cleaning_type" to type,
                    "priority" to if (emergency) null else priority,
                    "scene_id" to scene,
                    "take_number" to takeOf(take),
                    "auto_assign_replacement" to if (emergency) autoAssign else null,
                )
                if (filing.file(if (emergency) "/cleaning/emergency" else "/cleaning", request)) onFiled(
                    filing.filedId,
                    filing.created,
                )
            }
        },
        confirmEnabled = problem.isNotBlank(),
        busy = filing.busy,
        danger = emergency,
        ink = !emergency,
        icon = if (emergency) ZillitIcons.Siren else null,
    ) {
        if (emergency) EmergencyNotice()
        FormGrid {
            TextInput(problem, { problem = it }, t("csync_field_problem"), FormWide)
            EnumInput(
                type,
                ctx.metaList("cleaning_types"),
                { type = it.ifBlank { type } },
                t("csync_field_cleaning_type"),
            )
            CleaningPriority(emergency, priority) { priority = it.ifBlank { priority } }
            SceneTakeFields(scene, { scene = it }, take, { take = it })
        }
        ReportMedia(filing, t("csync_shoot_stain_hint"))
        if (emergency) ZillitCheckbox(autoAssign, { autoAssign = it }, label = t("csync_auto_assign_replacement"))
    }
}

/** "Report damage". The repair estimate is for finance roles only. */
@Composable
internal fun DamageDialog(costume: Rec, sceneId: String, takeNumber: String, onClose: () -> Unit, onFiled: () -> Unit) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val filing = remember { ReportFiling(ctx, "DAMAGE", "DETAIL") }
    var description by remember { mutableStateOf("") }
    var scene by remember { mutableStateOf(sceneId) }
    var take by remember { mutableStateOf(takeNumber) }
    var repair by remember { mutableStateOf("") }
    var responsible by remember { mutableStateOf("PRODUCTION") }
    FormDialog(
        open = true,
        title = "${t("csync_act_report_damage")} · ${costume.str("asset_number")}",
        onDismiss = onClose,
        confirmLabel = t("csync_report"),
        onConfirm = {
            scope.launch {
                val request = body(
                    "costume_id" to costume.id,
                    "description" to description,
                    "estimated_repair_cost" to if (ctx.isFinance) numOrNull(repair) else null,
                    "responsible" to responsible,
                    "scene_id" to scene,
                    "take_number" to takeOf(take),
                )
                if (filing.file("/damages", request)) onFiled()
            }
        },
        confirmEnabled = description.isNotBlank(),
        busy = filing.busy,
        danger = true,
    ) {
        FormGrid {
            TextInput(description, { description = it }, t("csync_damage"), FormWide)
            SceneTakeFields(scene, { scene = it }, take, { take = it })
            if (ctx.isFinance) TextInput(repair, { repair = it }, t("csync_field_est_repair"), number = true)
            EnumInput(
                responsible,
                ctx.metaList("damage_responsible"),
                { responsible = it.ifBlank { responsible } },
                t("csync_field_responsible"),
            )
        }
        ReportMedia(filing, t("csync_shoot_pick_scan"))
    }
}

/** "Alteration": what is wrong, what work is needed, who tailors it and by when. */
@Composable
internal fun AlterationDialog(costume: Rec, onClose: () -> Unit, onFiled: () -> Unit) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val filing = remember { ReportFiling(ctx, "ALTERATION", "DETAIL") }
    var issue by remember { mutableStateOf("") }
    var work by remember { mutableStateOf("") }
    var tailor by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf("HIGH") }
    var date by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("") }
    FormDialog(
        open = true,
        title = "${t("csync_act_alteration")} · ${costume.str("asset_number")}",
        onDismiss = onClose,
        confirmLabel = t("csync_request"),
        onConfirm = {
            scope.launch {
                val request = body(
                    "costume_id" to costume.id,
                    "character_id" to costume.str("character_id"),
                    "issue" to issue,
                    "required_work" to work,
                    "tailor_name" to tailor,
                    "priority" to priority,
                    "deadline" to deadlineIso(date, time),
                )
                if (filing.file("/alterations", request)) onFiled()
            }
        },
        confirmEnabled = issue.isNotBlank() && work.isNotBlank(),
        busy = filing.busy,
        ink = true,
    ) {
        FormGrid {
            TextInput(issue, { issue = it }, t("csync_field_issue"), FormWide)
            TextInput(work, { work = it }, t("csync_field_required"), FormWide)
            TextInput(tailor, { tailor = it }, t("csync_field_tailor"))
            EnumInput(
                priority,
                ctx.metaList("priorities"),
                { priority = it.ifBlank { priority } },
                t("csync_field_priority"),
            )
            com.zillit.desktop.feature.costumesetsync.ui.DateTimeInput(
                date,
                time,
                { date = it },
                { time = it },
                t("csync_field_deadline"),
            )
        }
        ReportMedia(filing, t("csync_shoot_pick_scan"))
    }
}

/** A deadline's local `YYYY-MM-DD` and `HH:mm` as the UTC instant the service stores; null when there is no date. */
internal fun deadlineIso(date: String, time: String): String? {
    if (date.isBlank()) return null
    val clock = time.trim().ifBlank { "00:00" }
    return runCatching {
        LocalDateTime.parse("${date.trim()}T$clock").toInstant(TimeZone.currentSystemDefault()).toString()
    }
        .getOrNull()
}

/** "Mark missing": where it was last seen and a note. */
@Composable
internal fun MissingDialog(costume: Rec, onClose: () -> Unit, onFiled: () -> Unit) {
    val ctx = LocalSync.current
    val scope = rememberCoroutineScope()
    val filing = remember { ReportFiling(ctx, "MISSING", "REFERENCE") }
    var lastSeen by remember { mutableStateOf(costume.str("location")) }
    var notes by remember { mutableStateOf("") }
    FormDialog(
        open = true,
        title = "${t("csync_act_mark_missing")} · ${costume.str("asset_number")}",
        onDismiss = onClose,
        confirmLabel = t("csync_act_mark_missing"),
        onConfirm = {
            scope.launch {
                val request = body("costume_id" to costume.id, "last_seen_location" to lastSeen, "notes" to notes)
                if (filing.file("/missing", request)) onFiled()
            }
        },
        busy = filing.busy,
        danger = true,
    ) {
        TextInput(lastSeen, { lastSeen = it }, t("csync_field_last_seen_location"), FormWide)
        TextInput(notes, { notes = it }, t("csync_field_notes"), FormWide, multiline = true)
        ReportMedia(filing, t("csync_shoot_pick_scan"))
    }
}
