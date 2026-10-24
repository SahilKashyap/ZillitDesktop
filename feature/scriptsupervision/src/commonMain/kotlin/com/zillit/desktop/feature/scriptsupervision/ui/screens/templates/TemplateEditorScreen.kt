package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitSectionLabel
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.Answer
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.ConfirmDialog
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.TagInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * Designing a template — the web's `TemplateEditorScreen`.
 *
 * The page it will become on the left, the section being edited on the right.
 * A click on a section opens it; a new one goes in at any "+" between them.
 *
 * ## Reordering is by BUTTON, not by drag
 *
 * The web uses dnd-kit AND gives every section and field Move up / Move down
 * buttons beside the drag handle. Those buttons are the whole behaviour, so
 * this ports them and leaves the drag out: a keyboard path that already
 * exists beats a gesture that would need writing from scratch, and nothing is
 * reachable on the web that is not reachable here.
 *
 * ## Saving
 *
 * ⚠ The three checks before a save are the service's, made here because its
 * refusal names neither the section nor the field at fault: at least one
 * section on, every section titled, every field named. See
 * [TemplateDraft.problem].
 *
 * ⚠ A PUT that carries `sections` snapshots the old ones as a VERSION. That
 * is the service's behaviour, not a choice here, and it is why the version
 * history fills up by itself and why a restore cannot lose anything.
 */
@Composable
internal fun TemplateEditorScreen(templateId: String, onDone: () -> Unit) {
    val ctx = LocalSs.current
    val isNew = templateId.isBlank()
    val loaded = rememberResource(templateId) {
        if (isNew) ZillitResult.Success(Answer(null, null))
        else api.get("/continuity/templates/detail/$templateId")
    }
    val label = rememberTemplateLabels()

    Await(loaded) { answer ->
        val record = answer.rec
        if (!isNew && record == null) {
            MutedText(t("template_not_found"))
            return@Await
        }
        val draft = remember(templateId, record) { TemplateDraft(record, label) }
        EditorBody(draft, templateId, label, onDone)
    }
}

/** The whole editor once the template is in hand. */
@Composable
private fun EditorBody(draft: TemplateDraft, templateId: String, label: (String) -> String, onDone: () -> Unit) {
    val ctx = LocalSs.current
    var saving by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    val problemWords = rememberProblemWords()

    SsPageHeader(title = draft.name.ifBlank { t(if (draft.isNew) "new_template" else "template") })
    EditorToolbar(
        draft = draft,
        saving = saving,
        onBack = { if (draft.dirty) leaving = true else onDone() },
        onHistory = { panel = "history" },
        onTest = { panel = "test" },
        onSave = {
            ctx.trySave(draft, templateId, problemWords, onBusy = { saving = it }, onSaved = onDone)
        },
    )

    DetailsCard(draft)

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm)) {
            TemplatePreviewPane(draft, label)
        }
        draft.editing?.let { section ->
            Column(Modifier.width(PANEL_WIDTH)) {
                SectionEditorPanel(
                    draft = draft,
                    section = section,
                    label = label,
                    onClose = { draft.editingKey = null },
                )
            }
        }
    }

    when (panel) {
        "test" -> TestDriveDialog(draft.enabled) { panel = "" }
        "history" -> VersionHistoryDialog(
            templateId = templateId,
            history = draft.history,
            onClose = { panel = "" },
            onRestored = { draft.applyVersion(it); panel = "" },
        )
    }

    if (leaving) {
        ConfirmDialog(
            title = t("unsaved_title"),
            message = t("unsaved_hint"),
            confirmLabel = t("discard"),
            danger = true,
            onClose = { leaving = false },
            onConfirm = { leaving = false; onDone() },
        )
    }
}

/** Back, the version, the test drive, and save. */
@Composable
private fun EditorToolbar(
    draft: TemplateDraft,
    saving: Boolean,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onTest: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitButton(
            text = t("back"),
            onClick = onBack,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.ArrowLeft,
        )
        if (!draft.isNew) {
            SsTag("v${draft.version}", StatusTone.Progress)
            ZillitButton(
                text = t("version_history"),
                onClick = onHistory,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Clock,
                enabled = draft.history.isNotEmpty(),
            )
        }
        ZillitButton(
            text = t("test_drive"),
            onClick = onTest,
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Eye,
        )
        ZillitButton(
            text = t(if (draft.isNew) "save_template" else "update"),
            onClick = onSave,
            leadingIcon = ZillitIcons.Save,
            loading = saving,
        )
    }
}

/** The template's own name, description and tags. */
@Composable
private fun DetailsCard(draft: TemplateDraft) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        FormGrid {
            TextInput(draft.name, draft::rename, t("template_name"))
            TextInput(
                draft.description,
                draft::describe,
                t("description"),
                placeholder = t("description_optional"),
            )
            TagInput(draft.tags, draft::retag, t("tags"), placeholder = t("add_tags"))
        }
    }
}

/**
 * The template as the page it will be, with a "+" between sections.
 *
 * Every section is shown as the log will show it, which is what makes this a
 * preview rather than a list: a reader sees the form, not its description.
 */
@Composable
private fun TemplatePreviewPane(draft: TemplateDraft, label: (String) -> String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ZillitSectionLabel(t("preview"))
        SectionsMenu(draft, label)
    }
    val enabled = draft.enabled
    if (enabled.isEmpty()) {
        MutedText(t("no_sections_hint"))
        return
    }
    InsertRow(draft, afterIndex = -1, label = label)
    enabled.forEachIndexed { index, section ->
        TemplateSectionCard(
            section = section,
            // A preview is looked at, not filled in.
            values = emptyMap(),
            onValue = { _, _ -> },
            enabled = false,
            layout = true,
            accent = Templates.sectionColour(section),
            onClick = { draft.editingKey = section.key },
            actions = {
                ZillitButton(
                    text = t("move_up"),
                    onClick = { draft.move(section.key, -1) },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.ChevronUp,
                    enabled = index > 0,
                )
                ZillitButton(
                    text = t("move_down"),
                    onClick = { draft.move(section.key, 1) },
                    variant = ButtonVariant.Tertiary,
                    leadingIcon = ZillitIcons.ChevronDown,
                    enabled = index < enabled.lastIndex,
                )
            },
        )
        InsertRow(draft, afterIndex = index, label = label)
    }
}

/** The "+" between two sections: a notes block, or a section of your own. */
@Composable
private fun InsertRow(draft: TemplateDraft, afterIndex: Int, label: (String) -> String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs)) {
        ZillitButton(
            text = t("notes"),
            onClick = { draft.insert("notes", afterIndex, label) },
            variant = ButtonVariant.Tertiary,
            leadingIcon = ZillitIcons.Add,
        )
        ZillitButton(
            text = t("section"),
            onClick = { draft.insert("section", afterIndex, label) },
            variant = ButtonVariant.Tertiary,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

/**
 * Every section with its on/off switch.
 *
 * ⚠ The scene and take sections carry no switch at all — they are required,
 * and the service writes a take against them whatever the template says.
 */
@Composable
private fun SectionsMenu(draft: TemplateDraft, label: (String) -> String) {
    var open by remember { mutableStateOf(false) }
    ZillitButton(
        text = t("sections"),
        onClick = { open = !open },
        variant = ButtonVariant.Secondary,
        leadingIcon = ZillitIcons.Grid,
    )
    if (!open) return
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        draft.sections.forEach { section ->
            SectionToggleRow(draft, section, label)
        }
        ZillitButton(
            text = t("custom"),
            onClick = { draft.insert("custom", Int.MAX_VALUE, label); open = false },
            variant = ButtonVariant.Secondary,
            leadingIcon = ZillitIcons.Add,
        )
    }
}

/** One section in the Sections menu. */
@Composable
private fun SectionToggleRow(draft: TemplateDraft, section: TemplateSection, label: (String) -> String) {
    val required = Templates.sectionDef(section.key)?.required == true
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MutedText(section.label, modifier = Modifier.weight(1f))
        if (required) {
            SsTag(t("required"))
        } else {
            ZillitButton(
                text = t(if (section.enabled) "remove" else "add_field"),
                onClick = { draft.toggle(section, !section.enabled, label) },
                variant = if (section.enabled) ButtonVariant.Secondary else ButtonVariant.Primary,
            )
        }
    }
}

/**
 * Checks the template, then writes it.
 *
 * ⚠ The check comes first and OPENS the section at fault. The service's own
 * refusal names neither the section nor the field, so a reader told only
 * "something is unnamed" would have to hunt through eleven sections for it.
 */
private fun SsCtx.trySave(
    draft: TemplateDraft,
    templateId: String,
    problemWords: (TemplateDraft.Problem) -> String,
    onBusy: (Boolean) -> Unit,
    onSaved: () -> Unit,
) {
    val problem = draft.problem()
    if (problem != null) {
        toast(problemWords(problem), false)
        problem.sectionKey?.let { draft.editingKey = it }
        return
    }
    onBusy(true)
    scope.launch {
        if (saveTemplate(draft, templateId)) {
            draft.saved()
            onSaved()
        }
        onBusy(false)
    }
}

/** Writes the template, new or changed. */
private suspend fun SsCtx.saveTemplate(draft: TemplateDraft, templateId: String): Boolean {
    val crud = ContinuityCrud(api, "templates")
    val fields = body(
        "name" to draft.name.trim(),
        "description" to draft.description.trim(),
        "tags" to buildJsonArray { draft.tags.forEach { add(JsonPrimitive(it)) } },
        "sections" to Templates.toSectionsBody(draft.sections),
    )
    val done = write { if (draft.isNew) crud.create(fields) else crud.update(templateId, fields) }
    return done != null
}

/** Why a template will not save, in the words for that reason. */
@Composable
private fun rememberProblemWords(): (TemplateDraft.Problem) -> String {
    val enableASection = t("enable_a_section")
    val titleRequired = t("section_title_required")
    val unnamed = t("unnamed_field", "section" to "{section}")
    return { problem ->
        when (problem.key) {
            "enable_a_section" -> enableASection
            "section_title_required" -> titleRequired
            else -> unnamed.replace("{section}", problem.arg)
        }
    }
}

/**
 * Every label a template's own definitions need, resolved once.
 *
 * ⚠ A resolver rather than `t` at the point of use, because the editor
 * builds sections from a CLICK — `t` is a composable and cannot be called
 * from a click handler. Resolving the whole set here, where composition is
 * allowed, is what lets [TemplateDraft] stay a plain object.
 */
@Composable
internal fun rememberTemplateLabels(): (String) -> String {
    val keys = buildSet {
        Templates.SECTION_DEFS.forEach { def ->
            add(Templates.sectionLabelKey(def.key))
            def.fields.forEach { add(Templates.fieldLabelKey(it.key)) }
        }
        Templates.FIELD_GROUP_PRESETS.forEach { preset ->
            preset.fields.forEach { add(Templates.fieldLabelKey(it.key)) }
        }
        addAll(listOf("notes", "new_section", "field_n", "section"))
    }
    val resolved = keys.associateWith { t(it) }
    return { key -> resolved[key] ?: key }
}

private val PANEL_WIDTH = 420.dp
