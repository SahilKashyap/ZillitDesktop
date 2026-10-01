package com.zillit.desktop.feature.costumesetsync.ui.screens

import androidx.compose.foundation.layout.Arrangement
import com.zillit.desktop.feature.costumesetsync.ui.SyncCtx
import com.zillit.desktop.feature.costumesetsync.ui.SyncDialogShell
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

private val DIALOG_WIDTH = 620.dp
private val DATE_WIDTH = 270.dp

/** Where the wizard is and what it holds; a fresh run each time it opens. */
private class WizardState(project: Rec?) {
    var steps by mutableStateOf(SetupRules.steps(project?.str("type")))
    var at by mutableStateOf(0)
    var type by mutableStateOf("")
    var dates by mutableStateOf(mapOf<String, String>())
    var withPrep by mutableStateOf(false)
    var withWrap by mutableStateOf(false)
    var saving by mutableStateOf(false)
    var scriptOpen by mutableStateOf(false)

    /** The steps are fixed for the whole run, so saving the type mid-way never renumbers them. */
    fun reset(edit: Boolean, project: Rec?) {
        at = 0
        type = ""
        if (edit) {
            val saved = SetupRules.savedDates(project)
            steps = listOf("dates", "script")
            dates = saved
            withPrep = PREP_KEYS.any { saved[it].orEmpty().isNotEmpty() }
            withWrap = WRAP_KEYS.any { saved[it].orEmpty().isNotEmpty() }
        } else {
            steps = SetupRules.steps(project?.str("type"))
            clearDates()
        }
    }

    fun clearDates() {
        dates = emptyMap()
        withPrep = false
        withWrap = false
    }

    val step: String get() = steps.getOrElse(at) { "dates" }

    fun next() { at = minOf(at + 1, steps.lastIndex) }

    fun setDate(key: String, value: String) { dates = dates + (key to value) }

    /** Unticking a section drops what was typed in it, as the web does. */
    fun toggle(on: Boolean, keys: List<String>, set: (Boolean) -> Unit) {
        set(on)
        if (!on) dates = dates + keys.associateWith { "" }
    }

    /** Nothing to write when the type is the one already saved. */
    fun continueType(ctx: SyncCtx, project: Rec?, onChanged: () -> Unit) {
        if (type == project?.str("type")) next() else patch(ctx, body("type" to type), onChanged)
    }

    fun datesPayload(): JsonObject = buildJsonObject {
        SetupRules.datesBody(dates).forEach { (k, v) -> put(k, JsonPrimitive(v)) }
    }

    fun patch(ctx: SyncCtx, payload: JsonObject, onChanged: () -> Unit) {
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
}

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
fun ProductionSetupWizard(
    open: Boolean,
    project: Rec?,
    edit: Boolean,
    onClose: () -> Unit,
    onChanged: () -> Unit,
    onDone: () -> Unit,
) {
    val ctx = LocalSync.current
    val w = remember { WizardState(project) }
    val docs = rememberProjectDocuments("SCRIPT", enabled = open && edit)
    val sceneCount = project?.rec("counts")?.long("scenes")?.toInt() ?: 0

    // Fresh every opening.
    LaunchedEffect(open) { if (open) w.reset(edit, project) }
    val upload = rememberScriptUpload(
        open = w.scriptOpen,
        docs = docs,
        existingScenes = sceneCount,
        onImported = { w.scriptOpen = false; onChanged(); onDone() },
        onClose = { w.scriptOpen = false },
    )
    LaunchedEffect(w.scriptOpen) { if (w.scriptOpen) upload.chooseFile() }

    SyncDialogShell(
        title = "${t(if (edit) "csync_setup_edit_title" else "csync_setup_title")} · " +
            t("csync_setup_step_of", "n" to (w.at + 1), "total" to w.steps.size),
        onDismiss = onClose,
        visible = open && !w.scriptOpen,
        width = DIALOG_WIDTH,
        actions = {
            SetupActions(
                step = w.step,
                canGoBack = w.at > 0,
                edit = edit,
                saving = w.saving,
                typeChosen = w.type.isNotEmpty(),
                onBack = { w.at = maxOf(w.at - 1, 0) },
                onCancel = onClose,
                onTypeContinue = { w.continueType(ctx, project, onChanged) },
                onSkipDates = {
                    if (!edit) w.clearDates()
                    w.next()
                },
                onDatesContinue = { w.patch(ctx, w.datesPayload(), onChanged) },
                onDone = onDone,
            )
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            when (w.step) {
                "type" -> TypeStep(w.type) { w.type = it }
                "dates" -> DatesStep(
                    w.dates,
                    w.withPrep,
                    w.withWrap,
                    w::setDate,
                    { on -> w.toggle(on, PREP_KEYS) { w.withPrep = it } },
                    { on -> w.toggle(on, WRAP_KEYS) { w.withWrap = it } },
                )
                else -> ScriptStep(edit, latestOf(docs.docs), sceneCount) { w.scriptOpen = true }
            }
        }
    }
    ScriptUploadDialog(w.scriptOpen, upload, docs) { w.scriptOpen = false }
}

private val PREP_KEYS = listOf("prep_start_date", "prep_end_date", "prep_wrap_date")
private val WRAP_KEYS = listOf("wrap_date", "prep_wrap_date")

/** The footer buttons of the step being shown: Back and Cancel, then what moves the step on. */
@Composable
private fun SetupActions(
    step: String,
    canGoBack: Boolean,
    edit: Boolean,
    saving: Boolean,
    typeChosen: Boolean,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onTypeContinue: () -> Unit,
    onSkipDates: () -> Unit,
    onDatesContinue: () -> Unit,
    onDone: () -> Unit,
) {
    if (canGoBack) ZillitButton(t("csync_back"), onClick = onBack, variant = ButtonVariant.Secondary)
    ZillitButton(t("csync_cancel"), onClick = onCancel, variant = ButtonVariant.Secondary)
    when (step) {
        "type" -> ZillitButton(t("csync_continue"), onClick = onTypeContinue, enabled = typeChosen, loading = saving)
        "dates" -> {
            // Skip moves on with no dates at all, clearing half-entered ones (editing: the saved dates stay).
            ZillitButton(t("csync_skip"), onClick = onSkipDates, variant = ButtonVariant.Tertiary)
            ZillitButton(t("csync_continue"), onClick = onDatesContinue, loading = saving)
        }
        else -> ZillitButton(t(if (edit) "csync_done" else "csync_continue"), onClick = onDone)
    }
}

/** The web's `.csync-setup__q`: the step's question, centred and bold. */
@Composable
private fun Question(text: String) {
    ZillitText(
        text,
        Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        style = ZillitTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun TypeStep(type: String, onType: (String) -> Unit) {
    val colors = ZillitTheme.colors
    Question(t("csync_setup_select"))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Triple("FEATURE", "csync_setup_feature", ZillitIcons.Camera),
            Triple("EPISODIC", "csync_setup_series", ZillitIcons.Grid),
        ).forEach { (value, label, icon) ->
            val on = type == value
            val shape = RoundedCornerShape(10.dp)
            val ink = if (on) colors.accentText else colors.textPrimary
            Column(
                Modifier.weight(1f)
                    .background(if (on) colors.accentSoft else colors.surface, shape)
                    .border(1.dp, if (on) colors.accent else colors.border, shape)
                    .clip(shape)
                    .clickable { onType(value) }
                    .padding(horizontal = 12.dp, vertical = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZillitIcon(icon, tint = ink, size = 28.dp)
                ZillitText(t(label), color = ink)
            }
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
    fun field(key: String, label: String) = @Composable { DateInput(
        dates[key].orEmpty(),
        { onDate(key, it) },
        label,
        Modifier.width(DATE_WIDTH),
    ) }
    @Composable
    fun range(title: String, from: String, to: String) {
        ZillitText(title, style = ZillitTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md)) {
            field(from, t("csync_field_start_date"))()
            field(to, t("csync_field_end_date"))()
        }
    }
    Question(t("csync_setup_dates_q"))
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
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitIcon(ZillitIcons.File, tint = ZillitTheme.colors.textPrimary, size = 20.dp)
        ZillitText(
            t("csync_setup_script_q"),
            style = ZillitTheme.typography.bodyMedium.copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        )
    }
    if (edit && (current != null || sceneCount > 0)) CurrentScriptCard(current, sceneCount)
    ScriptDropZone(onChoose)
}

/** What the production holds now: the latest script and how many scenes are in the breakdown. */
@Composable
private fun CurrentScriptCard(current: Rec?, sceneCount: Int) {
    val colors = ZillitTheme.colors
    val card = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceSunken, card).border(1.dp, colors.border, card).padding(
            horizontal = 14.dp,
            vertical = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ZillitText(
            t("csync_setup_current_script").uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.72.sp,
            ),
            color = colors.textMuted,
        )
        if (current != null) {
            ZillitText(
                docName(current),
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            MutedText(
                listOf(
                    t(docSourceKey(current)),
                    current.str("revision"),
                    fmtDateTime(current.long("created")),
                ).filter { it.isNotBlank() }.joinToString(" · "),
            )
        }
        MutedText(
            t(
                if (sceneCount == 1) "csync_setup_scenes_in_breakdown_one" else "csync_setup_scenes_in_breakdown",
                "n" to sceneCount,
            ),
        )
        MutedText(t("csync_setup_new_draft_hint"), maxLines = 2)
    }
}

/** The drop zone: a dashed tile with the upload mark, a bold line and a hint (a click opens the file picker). */
@Composable
private fun ScriptDropZone(onChoose: () -> Unit) {
    val colors = ZillitTheme.colors
    val zone = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth()
            .background(colors.surface, zone)
            .drawBehind {
                drawRoundRect(
                    colors.border,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        1.dp.toPx(),
                        pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                            floatArrayOf(6.dp.toPx(), 4.dp.toPx()),
                        ),
                    ),
                )
            }
            .clip(zone)
            .clickable(onClick = onChoose)
            .padding(30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIcon(ZillitIcons.Upload, tint = colors.info, size = 34.dp)
        ZillitText(
            t("csync_setup_drop"),
            style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        MutedText(t("csync_setup_drop_hint"), maxLines = 2)
    }
}
