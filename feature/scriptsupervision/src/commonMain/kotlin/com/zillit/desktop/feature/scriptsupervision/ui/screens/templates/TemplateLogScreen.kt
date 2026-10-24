package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import com.zillit.desktop.core.designsystem.component.ZillitEmptyState
import com.zillit.desktop.core.designsystem.component.ZillitSearchSelect
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.domain.Rec
import com.zillit.desktop.feature.scriptsupervision.domain.SectionValues
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateForm
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.domain.toLogBody
import com.zillit.desktop.feature.scriptsupervision.ui.Await
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.SsCtx
import com.zillit.desktop.feature.scriptsupervision.ui.SsPageHeader
import com.zillit.desktop.feature.scriptsupervision.ui.SsTag
import com.zillit.desktop.feature.scriptsupervision.ui.body
import com.zillit.desktop.feature.scriptsupervision.ui.rememberResource
import com.zillit.desktop.feature.scriptsupervision.ui.sceneText
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Logging takes through a template — the web's `TemplateLogScreen`.
 *
 * Each enabled section is a card of its fields, and one save writes the take
 * plus a record per section (a dialogue note, a sound report …) through the
 * service's own template-log route. This is the screen the QUICK LOG hands
 * over to when a production has a default template, which is the hole it was
 * built to close.
 *
 * ## What a save does to the form
 *
 * Saving does not clear it. The per-take fields reset — the flags, the
 * duration, the notes — and everything else CARRIES OVER, because the next
 * take of the same setup is the same setup: same lens, same camera roll,
 * same shot description. "New slate" additionally takes the take number back
 * to 1 and steps the slate. See [TemplateForm.PER_TAKE_RESET_KEYS], which is
 * the list of what clears and is tested.
 */
@Composable
internal fun TemplateLogScreen(templateId: String, onBack: (() -> Unit)? = null) {
    val ctx = LocalSs.current
    val sceneState = scenes()
    val template = rememberResource(templateId) { api.get("/continuity/templates/detail/$templateId") }
    val form = remember(templateId) { LogForm() }
    var sceneId by remember { mutableStateOf("") }

    // A scene picked on another day is no longer offered.
    LaunchedEffect(sceneState.day) {
        if (sceneId.isNotBlank() && sceneState.dayScenes.none { it.id == sceneId }) sceneId = ""
    }

    // Await rather than a hand-rolled loading flag: a template that will not
    // load says so in the service's own words, which is what the module's
    // other screens do.
    Await(template) { answer ->
        val record = answer.rec
        if (record == null) {
            ZillitEmptyState(title = t("template"), message = t("template_not_found"))
            return@Await
        }
        val sections = Templates.loadSections(record).filter { it.enabled }

        SsPageHeader(
            title = record.str("name").ifBlank { t("template") },
            sub = record.str("description").takeIf { it.isNotBlank() },
        )
        LogToolbar(
            sceneId = sceneId,
            onScene = { id ->
                sceneId = id
                form.start(sections)
                if (id.isNotBlank()) ctx.scope.launch { form.takeNumber(ctx.lastTakeOf(id) + 1) }
            },
            onBack = onBack,
        )

        if (sceneId.isBlank()) {
            ZillitEmptyState(title = t("template"), message = t("pick_scene_to_log"))
            return@Await
        }
        SceneStrip(sceneState.scene(sceneId), form.takesLogged)
        // The scene section describes the scene picked above, so the log does
        // not ask for it again — which is what the web leaves out too.
        sections.filter { it.key != "scene" }.forEach { section ->
            TemplateSectionCard(
                section = section,
                values = form.values(section.key),
                onValue = { key, value -> form.set(section.key, key, value) },
                errors = form.errorsOf(section.key),
                sceneId = sceneId,
                accent = Templates.sectionColour(section),
            )
        }
        SaveBar(form, sections, sceneId, templateId)
    }
}

/** Picking the scene to log against, and the way back to the templates. */
@Composable
private fun LogToolbar(sceneId: String, onScene: (String) -> Unit, onBack: (() -> Unit)?) {
    val sceneState = scenes()
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let {
            ZillitButton(
                text = t("back_to_templates"),
                onClick = it,
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.ArrowLeft,
            )
        }
        sceneState.day?.let { SsTag("${t("day")} $it") }
        ZillitSearchSelect(
            value = sceneState.options.firstOrNull { it.first == sceneId },
            options = sceneState.dayScenes.map { it.id to sceneText(it) },
            onSelect = { onScene(it.first) },
            label = { it.second },
            placeholder = t("pick_scene"),
            onClear = { onScene("") }.takeIf { sceneId.isNotBlank() },
            modifier = Modifier.width(SCENE_WIDTH),
        )
    }
}

/** Which scene is being logged, and how many takes have gone in. */
@Composable
private fun SceneStrip(scene: Rec?, takes: Int) {
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitText(scene?.str("scene_number").orEmpty(), style = ZillitTheme.typography.numeric)
            ZillitText(scene?.str("scene_name").orEmpty(), style = ZillitTheme.typography.titleSmall, maxLines = 1)
            Column(Modifier.weight(1f)) { }
            SsTag(takes.toString(), if (takes > 0) StatusTone.Progress else StatusTone.Neutral)
            MutedText(t("takes_logged_label"))
        }
    }
}

/**
 * The two saves, and what the last one was.
 *
 * Both write the same record; "new slate" only changes what the form looks
 * like afterwards. They sit at the foot of the page because that is where a
 * reader's hand is after filling it in.
 */
@Composable
private fun SaveBar(form: LogForm, sections: List<TemplateSection>, sceneId: String, templateId: String) {
    val ctx = LocalSs.current
    ZillitSectionCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            ZillitButton(
                text = t("save_next_take"),
                onClick = { ctx.saveTake(form, sections, sceneId, templateId, newSlate = false) },
                leadingIcon = ZillitIcons.Save,
                loading = form.saving,
                modifier = Modifier.weight(1f),
            )
            ZillitButton(
                text = t("save_new_slate"),
                onClick = { ctx.saveTake(form, sections, sceneId, templateId, newSlate = true) },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Add,
                loading = form.saving,
                modifier = Modifier.weight(1f),
            )
        }
        form.lastSaved?.let { MutedText(t("last_saved_take", "take" to it)) }
    }
}

/**
 * Writes the take, then sets the form up for the next one.
 *
 * ⚠ Validated HERE rather than left to the service: the service stores a
 * record missing a required field and says nothing, so the first anyone
 * knows is a hole in the log.
 */
private fun SsCtx.saveTake(
    form: LogForm,
    sections: List<TemplateSection>,
    sceneId: String,
    templateId: String,
    newSlate: Boolean,
) {
    if (!form.validate(sections) { key, arg -> t(key, "field" to arg, "n" to arg) }) return
    form.saving = true
    scope.launch {
        val done = write {
            api.post(
                "/quick-log/template/${api.project().orEmpty()}",
                body(
                    "scene_id" to sceneId,
                    "template_id" to templateId,
                    "is_new_slate" to newSlate,
                    // The sections the template HAS, so the service knows
                    // which records to write even where nothing was typed.
                    "enabled_sections" to buildJsonArray {
                        sections.forEach { add(JsonPrimitive(it.key)) }
                    },
                    "sections" to form.all().toLogBody(sections),
                ),
            )
        }
        form.saving = false
        if (done != null) form.afterSave(sections, newSlate)
    }
}

/** The scene's highest take so far, or 0. */
private suspend fun SsCtx.lastTakeOf(sceneId: String): Int =
    when (val answer = api.get("/quick-log/last-take/${api.project().orEmpty()}/$sceneId")) {
        is ZillitResult.Failure -> 0
        is ZillitResult.Success -> answer.data.rec?.int("take_number") ?: 0
    }

/**
 * What the log form holds, across every section.
 *
 * One object rather than state per section: a save reads all of it, the
 * per-take reset writes part of it, and a calculated field reads its
 * neighbours — all three need the whole thing.
 */
@Stable
private class LogForm {
    private var fields by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
    private var errors by mutableStateOf<Map<String, Map<String, String>>>(emptyMap())
    var takesLogged by mutableStateOf(0)
    var lastSaved by mutableStateOf<String?>(null)
    var saving by mutableStateOf(false)

    fun values(section: String): SectionValues = fields[section].orEmpty()
    fun errorsOf(section: String): Map<String, String> = errors[section].orEmpty()
    fun all(): Map<String, SectionValues> = fields

    fun set(section: String, key: String, value: String) {
        fields = fields + (section to (fields[section].orEmpty() + (key to value)))
        // A field somebody has just corrected stops complaining at once.
        errors[section]?.get(key)?.let {
            errors = errors + (section to (errors[section].orEmpty() - key))
        }
    }

    /** A fresh form on the template's own defaults. */
    fun start(sections: List<TemplateSection>) {
        fields = TemplateForm.templateDefaults(sections)
        errors = emptyMap()
        lastSaved = null
    }

    fun takeNumber(next: Int) {
        takesLogged = next - 1
        set("take", "take_number", next.toString())
    }

    /** True when every field will do; fills [errorsOf] when not. */
    fun validate(sections: List<TemplateSection>, say: (String, String) -> String): Boolean {
        val found = mutableMapOf<String, Map<String, String>>()
        sections.forEach { section ->
            val values = values(section.key)
            val bad = section.fields
                .filter { it.type != "divider" }
                // A hidden field is not filled in, so it is not refused either.
                .filter { TemplateForm.conditionsHold(it.conditions, values) }
                .mapNotNull { field ->
                    TemplateForm.validate(field, values[field.key].orEmpty(), say)?.let { field.key to it }
                }
                .toMap()
            if (bad.isNotEmpty()) found[section.key] = bad
        }
        errors = found
        return found.isEmpty()
    }

    /** The next take's form: the per-take fields cleared, the rest carried. */
    fun afterSave(sections: List<TemplateSection>, newSlate: Boolean) {
        val take = sections.firstOrNull { it.key == "take" }
        val number = values("take")["take_number"]?.toIntOrNull() ?: 1
        lastSaved = number.toString()
        takesLogged = if (newSlate) 0 else takesLogged + 1
        val carried = values("take") + TemplateForm.resetAfterTake(take) + if (newSlate) {
            mapOf(
                "take_number" to "1",
                "slate_number" to TemplateForm.incrementSlate(values("take")["slate_number"].orEmpty()),
            )
        } else {
            mapOf("take_number" to (number + 1).toString())
        }
        fields = fields + ("take" to carried)
    }
}

private val SCENE_WIDTH = 320.dp
