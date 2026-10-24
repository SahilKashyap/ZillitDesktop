package com.zillit.desktop.feature.scriptsupervision.ui.screens.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zillit.desktop.core.common.ZillitResult
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitSectionCard
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.scriptsupervision.data.Answer
import com.zillit.desktop.feature.scriptsupervision.data.ContinuityCrud
import com.zillit.desktop.feature.scriptsupervision.domain.SectionValues
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateField
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateForm
import com.zillit.desktop.feature.scriptsupervision.domain.TemplateSection
import com.zillit.desktop.feature.scriptsupervision.domain.Templates
import com.zillit.desktop.feature.scriptsupervision.ui.FormGrid
import com.zillit.desktop.feature.scriptsupervision.ui.FormHalf
import com.zillit.desktop.feature.scriptsupervision.ui.FormWide
import com.zillit.desktop.feature.scriptsupervision.ui.LocalCast
import com.zillit.desktop.feature.scriptsupervision.ui.LocalSs
import com.zillit.desktop.feature.scriptsupervision.ui.MutedText
import com.zillit.desktop.feature.scriptsupervision.ui.PickInput
import com.zillit.desktop.feature.scriptsupervision.ui.SwitchInput
import com.zillit.desktop.feature.scriptsupervision.ui.TextInput
import com.zillit.desktop.feature.scriptsupervision.ui.pickTemplateImage
import com.zillit.desktop.feature.scriptsupervision.ui.rememberRows
import com.zillit.desktop.feature.scriptsupervision.ui.sceneText
import com.zillit.desktop.feature.scriptsupervision.ui.scenes
import com.zillit.desktop.feature.scriptsupervision.ui.t
import kotlinx.coroutines.launch

/**
 * One section of a template as a card of controls — the web's
 * `TemplateSection`.
 *
 * Used by all three places a template is shown: the log, the editor's
 * preview, and its test drive. They differ only in [layout] and [enabled],
 * which is why this is one composable rather than three that drift apart.
 *
 * ## The layout rules it keeps
 *
 * - A **divider** breaks the fields into runs and titles the run under it.
 * - A run of **take flags** is a row of toggles, not a column of switches —
 *   a script supervisor taps those while watching a monitor.
 * - **Wide types** (a text area, a multiselect, an image) take a whole row.
 * - A field with **show-when rules** appears only while they hold, EXCEPT in
 *   [layout], where the editor is showing what the template contains rather
 *   than what a particular take would show.
 */
@Composable
@Suppress("LongParameterList") // Three callers, each needing a different subset.
internal fun TemplateSectionCard(
    section: TemplateSection,
    values: SectionValues,
    onValue: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    errors: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
    layout: Boolean = false,
    sceneId: String = "",
    accent: String? = null,
    onClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val stripe = accent?.let(::dataColour)
    Column(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        // ⚠ The section's colour edge is DRAWN, not laid out. A
        // `Spacer(…fillMaxHeight())` beside the card looks like the obvious
        // way to do it and is a trap: inside a scrolling column the cross
        // axis is unbounded, so the spacer took the whole page's height,
        // pushed every other section off it and drew one stripe the length
        // of the render. Looking at the PNG is the only reason that was
        // caught. Drawing stays inside the card's own measured height.
        ZillitSectionCard(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (stripe == null) {
                        Modifier
                    } else {
                        // ⚠ drawWithContent, not drawBehind: the card paints
                        // its own surface inside its modifier chain, so
                        // anything drawn BEHIND it is covered and only the
                        // rounded corners show — which is exactly what the
                        // render showed, two small ticks instead of an edge.
                        Modifier.drawWithContent {
                            drawContent()
                            drawRect(color = stripe, size = Size(ACCENT_WIDTH.toPx(), size.height))
                        }
                    },
                ),
        ) {
            SectionHeader(section, stripe, actions)
            if (section.fields.isEmpty()) {
                MutedText(t(if (layout) "no_fields_click" else "no_fields"))
                return@ZillitSectionCard
            }
            TemplateForm.fieldRuns(section).forEach { run ->
                run.divider?.let { divider ->
                    MutedText(divider.label)
                    Spacer(Modifier.height(ZillitTheme.spacing.xxs))
                }
                if (run.flags) {
                    FlagRow(run.fields, values, onValue, enabled)
                } else {
                    FieldRun(run.fields, values, onValue, errors, enabled, layout, sceneId)
                }
            }
        }
    }
}

/** The section's name, unless it is set to carry none, and its controls. */
@Composable
private fun SectionHeader(section: TemplateSection, stripe: Color?, actions: (@Composable RowScope.() -> Unit)?) {
    if (section.hideTitle && actions == null) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!section.hideTitle) {
            ZillitText(
                text = section.label,
                style = ZillitTheme.typography.titleSmall,
                color = stripe ?: ZillitTheme.colors.textPrimary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.weight(1f))
        actions?.invoke(this)
    }
}

/** A run of ordinary fields, wrapping side by side. */
@Composable
@Suppress("LongParameterList") // The field renderer's whole context, passed on.
private fun FieldRun(
    fields: List<TemplateField>,
    values: SectionValues,
    onValue: (String, String) -> Unit,
    errors: Map<String, String>,
    enabled: Boolean,
    layout: Boolean,
    sceneId: String,
) {
    FormGrid {
        fields.forEach { field ->
            // The editor's layout preview shows every field, whatever its
            // rules: it is showing the template, not one take's form.
            if (!layout && !TemplateForm.conditionsHold(field.conditions, values)) return@forEach
            TemplateFieldControl(
                field = field,
                values = values,
                onValue = onValue,
                error = errors[field.key],
                enabled = enabled,
                sceneId = sceneId,
            )
        }
    }
}

/**
 * The take flags as a row of toggles.
 *
 * One tap each, filled in the flag's own colour when on — the same colours
 * the standalone uses, which a script supervisor reads at a glance without
 * reading the words.
 */
@Composable
private fun FlagRow(
    fields: List<TemplateField>,
    values: SectionValues,
    onValue: (String, String) -> Unit,
    enabled: Boolean,
) {
    FormGrid {
        fields.forEach { field ->
            val on = values[field.key] == "true"
            val colour = TemplateForm.TAKE_FLAG_COLORS[field.key]?.let(::dataColour)
            ZillitButton(
                text = field.label,
                onClick = { onValue(field.key, (!on).toString()) },
                variant = if (on) ButtonVariant.Primary else ButtonVariant.Secondary,
                enabled = enabled,
                leadingIcon = if (on) ZillitIcons.Check else null,
                modifier = Modifier.then(
                    if (on && colour != null) Modifier.background(colour, ZillitTheme.shapes.pill) else Modifier,
                ),
            )
        }
    }
}

/** One field of a template, as the control its type asks for. */
@Composable
@Suppress("LongParameterList", "CyclomaticComplexMethod") // One branch per field type, which is the point.
private fun TemplateFieldControl(
    field: TemplateField,
    values: SectionValues,
    onValue: (String, String) -> Unit,
    error: String?,
    enabled: Boolean,
    sceneId: String,
) {
    val value = values[field.key].orEmpty()
    val width = if (field.type in TemplateForm.WIDE_TYPES) FormWide else FormHalf
    val label = field.label + if (field.required) " *" else ""

    when {
        // Before the type switch: a text field that holds a duration takes
        // the mask too, which is what `isTimeLike` is for.
        TemplateForm.isTimeLike(field) -> TextInput(
            value = value,
            onChange = { onValue(field.key, TemplateForm.maskTime(it)) },
            label = label,
            modifier = width,
            placeholder = if (field.type == "time") "H:MM" else "MM:SS",
            error = error,
            enabled = enabled,
        )

        field.type == "textarea" -> TextInput(
            value, { onValue(field.key, it) }, label,
            modifier = width, multiline = true, error = error, enabled = enabled,
        )

        field.type == "number" -> TextInput(
            value, { onValue(field.key, it) }, label,
            modifier = width, number = true, error = error, enabled = enabled,
        )

        field.isChoice -> PickInput(
            value = value,
            options = field.options.map { it to it },
            onChange = { onValue(field.key, it) },
            label = label,
            modifier = width,
            clearable = !field.required,
            enabled = enabled,
        )

        field.type == "switch" || field.type == "checkbox" -> SwitchInput(
            value = value == "true",
            onChange = { onValue(field.key, it.toString()) },
            label = field.label,
            modifier = width,
        )

        field.type == "lookup" -> LookupField(field, value, { onValue(field.key, it) }, width, enabled)

        field.type == "image" -> ImageField(field, value, { onValue(field.key, it) }, width, enabled, sceneId)

        field.type == "calculated" -> CalculatedField(field, values, onValue, width)

        else -> TextInput(
            value, { onValue(field.key, it) }, label,
            modifier = width, error = error, enabled = enabled,
        )
    }
}

/**
 * A calculated field: read-only, and recomputed as its sources change.
 *
 * ⚠ It writes its own value back through [onValue] so the SAVE carries it.
 * A read-only control that only displayed the figure would send nothing, and
 * the service would store a blank where the template promises a number.
 */
@Composable
private fun CalculatedField(
    field: TemplateField,
    values: SectionValues,
    onValue: (String, String) -> Unit,
    width: Modifier,
) {
    val sources = field.formula?.sourceFields.orEmpty().map { values[it].orEmpty() }
    val computed = TemplateForm.computeFormula(field.formula?.formulaType.orEmpty(), sources)
    if (computed != values[field.key].orEmpty()) onValue(field.key, computed)
    TextInput(
        value = computed,
        onChange = { },
        label = field.label,
        modifier = width,
        placeholder = t("calculated"),
        enabled = false,
    )
}

/** A pick from the production's own records. */
@Composable
private fun LookupField(
    field: TemplateField,
    value: String,
    onChange: (String) -> Unit,
    width: Modifier,
    enabled: Boolean,
) {
    val options = lookupOptions(field)
    PickInput(
        value = value,
        options = options,
        onChange = onChange,
        label = field.label,
        modifier = width,
        clearable = true,
        enabled = enabled,
    )
}

/**
 * An image field: the file goes up through the module's own photo seam and
 * the field keeps the storage key.
 *
 * The web uploads to S3 on a presigned URL and saves an attachment; the
 * desktop's `pickPhoto` is that whole dance already, so this is the one
 * place the two differ in plumbing and not at all in outcome.
 */
@Composable
private fun ImageField(
    field: TemplateField,
    value: String,
    onChange: (String) -> Unit,
    width: Modifier,
    enabled: Boolean,
    sceneId: String,
) {
    val ctx = LocalSs.current
    var busy by remember { mutableStateOf(false) }
    Column(width, verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs)) {
        MutedText(field.label)
        Row(
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZillitButton(
                text = t("upload_image"),
                onClick = {
                    busy = true
                    ctx.scope.launch {
                        ctx.pickTemplateImage(sceneId)?.let(onChange)
                        busy = false
                    }
                },
                variant = ButtonVariant.Secondary,
                leadingIcon = ZillitIcons.Upload,
                loading = busy,
                enabled = enabled,
            )
            if (value.isNotBlank()) MutedText(value.substringAfterLast('/'))
        }
    }
}

/**
 * A lookup field's choices, from the production's own records.
 *
 * Cast and scenes come from the state the module already holds, so they cost
 * nothing; the two breakdown lists are fetched, and only when a template
 * actually asks for one.
 *
 * ⚠ `displayField` is the template's choice of which field to SHOW, and a
 * scene falls back to the module's own scene label rather than to a blank —
 * a list of empty rows is worse than a list of the wrong field.
 */
@Composable
private fun lookupOptions(field: TemplateField): List<Pair<String, String>> {
    val source = field.lookup?.source.orEmpty()
    val display = field.lookup?.displayField.orEmpty().ifBlank { "name" }
    val cast = LocalCast.current
    val sceneState = scenes()
    val fetched = rememberRows(source) {
        when (source) {
            "breakdown_element" -> ContinuityCrud(api, "breakdown-elements").list()
            "breakdown_category" -> ContinuityCrud(api, "breakdown-categories").list()
            else -> ZillitResult.Success(Answer(null, null))
        }
    }
    return when (source) {
        "cast" -> cast.characters.map { it.id to it.str(display).ifBlank { it.str("name") } }
        "scene" -> sceneState.scenes.map { it.id to it.str(display).ifBlank { sceneText(it) } }
        else -> fetched.value.orEmpty().map { it.id to it.str(display).ifBlank { it.str("name") } }
    }
}

/** `#rrggbb` carries no alpha, so a data colour is forced opaque. */
private fun dataColour(hex: String): Color? =
    runCatching { Color(hex.removePrefix("#").toLong(HEX_RADIX) or OPAQUE) }.getOrNull()

private const val OPAQUE = 0xFF000000L
private const val HEX_RADIX = 16
private val ACCENT_WIDTH = 3.dp
