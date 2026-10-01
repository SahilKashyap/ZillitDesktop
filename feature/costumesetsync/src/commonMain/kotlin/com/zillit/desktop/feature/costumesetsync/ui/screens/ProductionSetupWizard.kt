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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitChoiceChip
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.costumesetsync.domain.Rec
import com.zillit.desktop.feature.costumesetsync.domain.SetupRules
import com.zillit.desktop.feature.costumesetsync.domain.docName
import com.zillit.desktop.feature.costumesetsync.domain.docSourceKey
import com.zillit.desktop.feature.costumesetsync.domain.fmtDateTime
import com.zillit.desktop.feature.costumesetsync.domain.latestOf
import com.zillit.desktop.feature.costumesetsync.ui.DateInput
import com.zillit.desktop.feature.costumesetsync.ui.LocalSync
import com.zillit.desktop.feature.costumesetsync.ui.MutedText
import com.zillit.desktop.feature.costumesetsync.ui.body
import com.zillit.desktop.feature.costumesetsync.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

private val DIALOG_WIDTH = 620.dp
private val DATE_WIDTH = 270.dp

/**
 * "Create a production" inside Zillit (the web's `ProductionSetupWizard`, the reference's ZILLIT_FIRST_RUN_CREATE
 * spec). It SETS UP the production this Zillit project already is — the service has no create; the record exists
 * with the project, and the wizard only PATCHes it (`type`, then the six dates; 0 = not set). Steps:
 *
 * - Select (Feature / TV Series) — only when the project's `type` is unknown; saved at once.
 * - Estimated shoot dates — Continue saves all six, Skip saves none. No Back on the first step.
 * - Upload script for breakdown — choosing a file opens the usual script review (read → confirm → import) and
 *   importing finishes the setup; Continue without one finishes as is. (The web's "draft name" box is not
 *   here: the review names the first draft "White" itself.)
 *
 * `edit = true` is the Setup tab: the dates and script steps only (the type comes from Zillit), filled with what
 * the production already holds, Skip leaves the saved dates, and the last button is Done.
 * [onChanged] is told after a write so the shell can re-read the production record; [onDone] after the last step.
 */
@Composable
fun ProductionSetupWizard(open: Boolean, project: Rec?, edit: Boolean, onClose: () -> Unit, onChanged: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalSync.current
    var steps by remember { mutableStateOf(SetupRules.steps(project?.str("type"))) }
    var at by remember { mutableStateOf(0) }
    var type by remember { mutableStateOf("") }
    var dates by remember { mutableStateOf(mapOf<String, String>()) }
    var withPrep by remember { mutableStateOf(false) }
    var withWrap by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var scriptOpen by remember { mutableStateOf(false) }
    val docs = rememberProjectDocuments("SCRIPT", enabled = open && edit)
    val sceneCount = project?.rec("counts")?.long("scenes")?.toInt() ?: 0

    // Fresh every opening; the steps are fixed for the whole run, so saving the type mid-way never renumbers them.
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        at = 0
        type = ""
        if (edit) {
            val saved = SetupRules.savedDates(project)
            steps = listOf("dates", "script")
            dates = saved
            withPrep = listOf("prep_start_date", "prep_end_date", "prep_wrap_date").any { saved[it].orEmpty().isNotEmpty() }
            withWrap = listOf("wrap_date", "prep_wrap_date").any { saved[it].orEmpty().isNotEmpty() }
        } else {
            steps = SetupRules.steps(project?.str("type"))
            dates = emptyMap()
            withPrep = false
            withWrap = false
        }
    }
    val upload = rememberScriptUpload(
        open = scriptOpen,
        docs = docs,
        existingScenes = sceneCount,
        onImported = { scriptOpen = false; onChanged(); onDone() },
        onClose = { scriptOpen = false },
    )
    LaunchedEffect(scriptOpen) { if (scriptOpen) upload.chooseFile() }

    val step = steps.getOrElse(at) { "dates" }
    fun next() { at = minOf(at + 1, steps.lastIndex) }
    fun patch(payload: kotlinx.serialization.json.JsonObject) {
        saving = true
        ctx.scope.launch {
            val done = ctx.write { ctx.api.patch("", payload) }
            saving = false
            if (done != null) {
                onChanged()
                next()
            }
        }
    }
    fun setDate(key: String, value: String) { dates = dates + (key to value) }
    fun toggle(on: Boolean, keys: List<String>, set: (Boolean) -> Unit) {
        set(on)
        // Unticking a section drops what was typed in it, as the web does.
        if (!on) dates = dates + keys.associateWith { "" }
    }

    SyncDialogShell(
        title = "${t(if (edit) "csync_setup_edit_title" else "csync_setup_title")} · ${t("csync_setup_step_of", "n" to (at + 1), "total" to steps.size)}",
        onDismiss = onClose,
        visible = open && !scriptOpen,
        width = DIALOG_WIDTH,
        actions = {
            if (at > 0) ZillitButton(t("csync_back"), onClick = { at = maxOf(at - 1, 0) }, variant = ButtonVariant.Secondary)
            ZillitButton(t("csync_cancel"), onClick = onClose, variant = ButtonVariant.Secondary)
            when (step) {
                "type" -> ZillitButton(t("csync_continue"), onClick = {
                    // Nothing to write when the type is the one already saved.
                    if (type == project?.str("type")) next() else patch(body("type" to type))
                }, enabled = type.isNotEmpty(), loading = saving)
                "dates" -> {
                    // Skip moves on with no dates at all, clearing half-entered ones (editing: the saved dates stay).
                    ZillitButton(t("csync_skip"), onClick = {
                        if (!edit) {
                            dates = emptyMap()
                            withPrep = false
                            withWrap = false
                        }
                        next()
                    }, variant = ButtonVariant.Tertiary)
                    ZillitButton(t("csync_continue"), onClick = { patch(body(*SetupRules.datesBody(dates).map { (k, v) -> k to JsonPrimitive(v) }.toTypedArray())) }, loading = saving)
                }
                else -> ZillitButton(t(if (edit) "csync_done" else "csync_continue"), onClick = onDone)
            }
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            when (step) {
                "type" -> TypeStep(type) { type = it }
                "dates" -> DatesStep(dates, withPrep, withWrap, ::setDate, { on -> toggle(on, listOf("prep_start_date", "prep_end_date", "prep_wrap_date")) { withPrep = it } }, { on -> toggle(on, listOf("wrap_date", "prep_wrap_date")) { withWrap = it } })
                else -> ScriptStep(edit, latestOf(docs.docs), sceneCount) { scriptOpen = true }
            }
        }
    }
    ScriptUploadDialog(scriptOpen, upload, docs) { scriptOpen = false }
}

@Composable
private fun TypeStep(type: String, onType: (String) -> Unit) {
    ZillitText(t("csync_setup_select"), style = ZillitTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
        listOf("FEATURE" to "csync_setup_feature", "EPISODIC" to "csync_setup_series").forEach { (value, label) ->
            ZillitChoiceChip(t(label), type == value, onClick = { onType(value) })
        }
    }
}

@Composable
private fun DatesStep(
    dates: Map<String, String>,
    withPrep: Boolean,
    withWrap: Boolean,
    onDate: (String, String) -> Unit,
    onPrep: (Boolean) -> Unit,
    onWrap: (Boolean) -> Unit,
) {
    fun field(key: String, label: String) = @Composable { DateInput(dates[key].orEmpty(), { onDate(key, it) }, label, Modifier.width(DATE_WIDTH)) }
    @Composable
    fun range(title: String, from: String, to: String) {
        ZillitText(title, style = ZillitTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            field(from, t("csync_field_start_date"))()
            field(to, t("csync_field_end_date"))()
        }
    }
    ZillitText(t("csync_setup_dates_q"), style = ZillitTheme.typography.titleSmall)
    range(t("csync_setup_shoot_dates"), "start_date", "end_date")
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg)) {
        ZillitCheckbox(withPrep, onPrep, label = t("csync_setup_add_prep"))
        ZillitCheckbox(withWrap, onWrap, label = t("csync_setup_add_wrap"))
    }
    if (withPrep) range(t("csync_setup_prep_dates"), "prep_start_date", "prep_end_date")
    if (withWrap) {
        ZillitText(t("csync_setup_wrap_dates"), style = ZillitTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            field("wrap_date", t("csync_field_shoot_wrap"))()
            if (withPrep) field("prep_wrap_date", t("csync_field_prep_wrap"))()
        }
    }
}

@Composable
private fun ScriptStep(edit: Boolean, current: Rec?, sceneCount: Int, onChoose: () -> Unit) {
    ZillitText(t("csync_setup_script_q"), style = ZillitTheme.typography.titleSmall)
    if (edit && (current != null || sceneCount > 0)) {
        Column(verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
            ZillitText(t("csync_setup_current_script").uppercase(), style = ZillitTheme.typography.labelSmall, color = ZillitTheme.colors.textMuted)
            if (current != null) {
                ZillitText(docName(current), style = ZillitTheme.typography.titleSmall)
                MutedText(listOf(t(docSourceKey(current)), current.str("revision"), fmtDateTime(current.long("created"))).filter { it.isNotBlank() }.joinToString(" · "))
            }
            MutedText(t(if (sceneCount == 1) "csync_setup_scenes_in_breakdown_one" else "csync_setup_scenes_in_breakdown", "n" to sceneCount))
            MutedText(t("csync_setup_new_draft_hint"), maxLines = 2)
        }
    }
    ZillitButton(t("csync_setup_drop"), onClick = onChoose, variant = ButtonVariant.Secondary, size = ButtonSize.Medium, leadingIcon = ZillitIcons.Upload)
    MutedText(t("csync_setup_drop_hint"), maxLines = 2)
}
