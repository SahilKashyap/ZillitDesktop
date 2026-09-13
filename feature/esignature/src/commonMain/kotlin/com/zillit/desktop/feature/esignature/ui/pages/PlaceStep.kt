@file:Suppress("LongMethod", "TooManyFunctions", "CyclomaticComplexMethod", "ComplexCondition")

package com.zillit.desktop.feature.esignature.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonSize
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitCheckbox
import com.zillit.desktop.core.designsystem.component.ZillitDialogShell
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitSelect
import com.zillit.desktop.core.designsystem.component.ZillitSpinner
import com.zillit.desktop.core.designsystem.component.ZillitSwitch
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTextField
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.feature.esignature.domain.EnvelopeField
import com.zillit.desktop.feature.esignature.domain.FieldOption
import com.zillit.desktop.feature.esignature.domain.FieldType
import com.zillit.desktop.feature.esignature.ui.EditorRules
import com.zillit.desktop.feature.esignature.ui.EditorState
import com.zillit.desktop.feature.esignature.ui.EsignEvent
import com.zillit.desktop.feature.esignature.ui.PlacementMode
import com.zillit.desktop.feature.esignature.ui.components.BlockTitle
import com.zillit.desktop.feature.esignature.ui.components.FieldRect
import com.zillit.desktop.feature.esignature.ui.components.Hairline
import com.zillit.desktop.feature.esignature.ui.components.PageCanvas
import com.zillit.desktop.feature.esignature.ui.components.PageScale
import com.zillit.desktop.feature.esignature.ui.components.color
import com.zillit.desktop.feature.esignature.ui.components.signerColor

/**
 * Field placement — the web's `DocumentFieldPlacer`: the toolbar of types
 * on the left, the pages in the middle, the signers and the selected
 * field's properties on the right.
 */
@Composable
internal fun PlaceStep(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Row(Modifier.fillMaxSize()) {
        Toolbar(editor, onEvent)
        Box(Modifier.weight(1f).fillMaxHeight().background(colors.surfaceSunken)) {
            if (editor.loadingDoc || editor.pages.isEmpty()) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (editor.loadingDoc) ZillitSpinner() else ZillitText("No document yet", color = colors.textMuted)
                    if (!editor.loadingDoc) ZillitButton(
                        "Choose a PDF",
                        onClick = { onEvent(EsignEvent.PickDocument) },
                        size = ButtonSize.Small,
                    )
                }
            } else {
                ZillitScrollColumn(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    editor.pages.forEach { page ->
                        PageCanvas(
                            page = page,
                            zoom = editor.zoom,
                            crosshair = editor.selectedField == null,
                            onTap = { x, y -> onEvent(EsignEvent.PageClicked(page.page, x, y)) },
                        ) { scale ->
                            editor.fields.forEachIndexed { index, field ->
                                if (field.page != page.page) return@forEachIndexed
                                FieldOverlay(editor, index, field, scale, onEvent)
                            }
                            editor.pending?.takeIf { it.page == page.page }?.let { pending ->
                                Box(
                                    Modifier.offset(
                                        x = (pending.xPt * scale.dpPerPoint).dp - 6.dp,
                                        y = (pending.yPt * scale.dpPerPoint).dp - 6.dp,
                                    )
                                        .size(12.dp).clip(CircleShape).background(colors.accent).border(
                                            2.dp,
                                            Color.White,
                                            CircleShape,
                                        ),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
                ZoomBar(editor, onEvent, Modifier.align(Alignment.BottomCenter).padding(12.dp))
            }
        }
        RightRail(editor, onEvent)
    }
    ModePickerDialog(editor, onEvent)
    PendingDialog(editor, onEvent)
}

// ---------------------------------------------------------------- toolbar

@Composable
private fun Toolbar(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.width(196.dp).fillMaxHeight().background(colors.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
            ZillitText("FIELDS", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            Spacer(Modifier.height(2.dp))
            ZillitText(
                if (editor.placementMode == PlacementMode.FastPlace) {
                    "Pick a type, then click the page."
                } else {
                    "Click the page, then pick who signs."
                },
                style = ZillitTheme.typography.labelSmall,
                color = colors.textSecondary,
            )
        }
        Hairline()
        ZillitScrollColumn(Modifier.weight(1f).padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FieldType.toolbar.forEach { type ->
                val armed = editor.armedType == type
                Row(
                    Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
                        .background(if (armed) colors.accentSoft else Color.Transparent)
                        .clickable { onEvent(EsignEvent.ArmType(type)) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(type.color()))
                    ZillitText(
                        type.label,
                        style = ZillitTheme.typography.bodySmall.copy(
                            fontWeight = if (armed) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (armed) colors.accentText else colors.textPrimary,
                    )
                }
            }
        }
        Hairline()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitText("MODE", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            ZillitText(editor.placementMode.label, style = ZillitTheme.typography.bodySmall)
            ZillitButton(
                "Change",
                onClick = { onEvent(EsignEvent.EditCompose { copy(modeAsked = false) }) },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
            )
        }
    }
}

@Composable
private fun ZoomBar(editor: EditorState, onEvent: (EsignEvent) -> Unit, modifier: Modifier) {
    val colors = ZillitTheme.colors
    Row(
        modifier.shadow(6.dp, ZillitTheme.shapes.pill).clip(ZillitTheme.shapes.pill).background(colors.surface)
            .border(1.dp, colors.border, ZillitTheme.shapes.pill).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZillitIconButton(
            ZillitIcons.Minimize,
            "Zoom out",
            onClick = { onEvent(EsignEvent.SetZoom(editor.zoom - ZOOM_STEP)) },
            size = 24.dp,
        )
        ZillitText(
            "${(editor.zoom * 100).toInt()}%",
            style = ZillitTheme.typography.labelSmall,
            modifier = Modifier.width(40.dp),
        )
        ZillitIconButton(
            ZillitIcons.Add,
            "Zoom in",
            onClick = { onEvent(EsignEvent.SetZoom(editor.zoom + ZOOM_STEP)) },
            size = 24.dp,
        )
    }
}

// ---------------------------------------------------------------- overlay

@Composable
private fun BoxScope.FieldOverlay(
    editor: EditorState,
    index: Int,
    field: EnvelopeField,
    scale: PageScale,
    onEvent: (EsignEvent) -> Unit,
) {
    val colors = ZillitTheme.colors
    val density = LocalDensity.current.density
    val selected = editor.selectedField == index
    val invalid = index in editor.invalidFields
    val signerPosition = editor.signerIndexes.indexOf(field.recipientIndex)
    val hue = signerColor(signerPosition)
    val owner = editor.recipients.getOrNull(field.recipientIndex)
    val ptPerPx = 1f / (density * scale.dpPerPoint)
    FieldRect(
        field = field,
        scale = scale,
        modifier = Modifier
            .then(if (selected) Modifier.shadow(4.dp, RoundedCornerShape(3.dp)) else Modifier)
            .background(
                if (invalid) colors.danger.copy(alpha = 0.18f) else hue.copy(alpha = if (selected) 0.28f else 0.16f),
                RoundedCornerShape(3.dp),
            )
            .border(if (selected) 2.dp else 1.dp, if (invalid) colors.danger else hue, RoundedCornerShape(3.dp))
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(index) {
                detectTapGestures { onEvent(EsignEvent.SelectField(index)) }
            }
            .pointerInput(index, scale) {
                detectDragGestures(
                    onDragStart = { onEvent(EsignEvent.SelectField(index)) },
                    onDrag = { change, drag ->
                        change.consume()
                        onEvent(
                            EsignEvent.MoveField(index, (drag.x * ptPerPx).toDouble(), (drag.y * ptPerPx).toDouble()),
                        )
                    },
                )
            },
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(hue), contentAlignment = Alignment.Center) {
                ZillitText(
                    (owner?.name?.trim()?.take(1) ?: "?").uppercase(),
                    style = ZillitTheme.typography.labelSmall.copy(fontSize = 8.sp),
                    color = Color.White,
                )
            }
            ZillitText(
                text = field.label.ifBlank { field.type.label } + if (!field.required) " · opt" else "",
                style = ZillitTheme.typography.labelSmall.copy(fontSize = (9 * editor.zoom).coerceIn(7f, 12f).sp),
                color = hue.darken(),
                maxLines = 1,
            )
        }
        if (selected) {
            Box(
                Modifier.align(Alignment.BottomEnd).size(10.dp).background(hue).border(1.dp, Color.White)
                    .pointerHoverIcon(PointerIcon.Crosshair)
                    .pointerInput(index, scale) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            onEvent(
                                EsignEvent.ResizeField(
                                    index,
                                    (drag.x * ptPerPx).toDouble(),
                                    (drag.y * ptPerPx).toDouble(),
                                ),
                            )
                        }
                    },
            )
        }
    }
}

private fun Color.darken(): Color = Color(red * DARKEN, green * DARKEN, blue * DARKEN, alpha)

private const val DARKEN = 0.7f

// ---------------------------------------------------------------- right rail

@Composable
private fun RightRail(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    Column(Modifier.width(300.dp).fillMaxHeight().background(colors.surface)) {
        val selected = editor.selectedField?.let { editor.fields.getOrNull(it) }
        if (selected != null && editor.selectedField != null) {
            PropertyPanel(editor, editor.selectedField, selected, onEvent)
        } else {
            SignersRail(editor, onEvent)
        }
    }
}

@Composable
private fun SignersRail(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitScrollColumn(
        Modifier.fillMaxSize().padding(RAIL_PADDING),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        BlockTitle(if (editor.isTemplate) "Roles" else "Signers")
        if (editor.signers.isEmpty()) {
            ZillitText(
                if (editor.isTemplate) {
                    "Click the page to create Signer 1."
                } else {
                    "Add a signer in the prepare step to place fields."
                },
                style = ZillitTheme.typography.bodySmall,
                color = colors.textMuted,
            )
        }
        editor.signerIndexes.forEachIndexed { position, recipientIndex ->
            val signer = editor.recipients[recipientIndex]
            val armed = editor.armedSignerIndex == recipientIndex
            val fast = editor.placementMode == PlacementMode.FastPlace
            Row(
                Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
                    .background(if (armed && fast) signerColor(position).copy(alpha = 0.12f) else Color.Transparent)
                    .border(
                        1.dp,
                        if (armed && fast) signerColor(position) else colors.border,
                        ZillitTheme.shapes.medium,
                    )
                    .clickable { onEvent(EsignEvent.ArmSigner(recipientIndex)) }
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(signerColor(position)))
                Column(Modifier.weight(1f)) {
                    ZillitText(
                        signer.name.ifBlank { signer.email.ifBlank { "Signer ${position + 1}" } },
                        style = ZillitTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                        maxLines = 1,
                    )
                    ZillitText(
                        "${editor.fieldsOf(recipientIndex).size} field(s)",
                        style = ZillitTheme.typography.labelSmall,
                        color = colors.textMuted,
                    )
                }
                if (armed && fast) ZillitText(
                    "placing",
                    style = ZillitTheme.typography.labelSmall,
                    color = signerColor(position),
                )
            }
        }
        if (editor.isTemplate) {
            ZillitButton(
                "Add role slot",
                onClick = {
                    onEvent(
                        EsignEvent.EditCompose {
                            copy(recipients = recipients + EditorRules.placeholder(signers.size))
                        },
                    )
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        Hairline()
        ZillitSwitch(
            checked = editor.settings.initialsOnAllPages,
            onCheckedChange = { onEvent(EsignEvent.SetInitialsOnAllPages(it)) },
            label = "Initials on every page",
        )
        Hairline()
        ZillitText("TIPS", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
        listOf(
            "Click a field to edit its label, whether it is required, and its options.",
            "Drag a field to move it; drag the corner handle to resize.",
            "Every signer needs at least one field before the envelope can be sent.",
        ).forEach { tip ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ZillitText("•", style = ZillitTheme.typography.bodySmall, color = colors.textMuted)
                ZillitText(tip, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

// ---------------------------------------------------------------- properties

@Composable
private fun PropertyPanel(editor: EditorState, index: Int, field: EnvelopeField, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    fun edit(transform: (EnvelopeField) -> EnvelopeField) = onEvent(EsignEvent.UpdateField(index, transform))
    val owner = editor.recipients.getOrNull(field.recipientIndex)
    ZillitScrollColumn(
        Modifier.fillMaxSize().padding(RAIL_PADDING),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(field.type.color().copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(field.type.color()))
            }
            Column(Modifier.weight(1f)) {
                ZillitText(field.type.label, style = ZillitTheme.typography.titleSmall)
                ZillitText(
                    "Page ${field.page} · ${owner?.name?.ifBlank { owner.email } ?: "Signer"}",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                    maxLines = 1,
                )
            }
            ZillitIconButton(
                ZillitIcons.Close,
                "Done",
                onClick = { onEvent(EsignEvent.SelectField(null)) },
                size = 24.dp,
            )
        }
        Hairline()
        if (editor.signers.size > 1) {
            ZillitText("SIGNED BY", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            ZillitSelect(
                value = field.recipientIndex,
                options = editor.signerIndexes,
                onSelect = { ri -> edit { it.copy(recipientIndex = ri) } },
                label = { ri -> editor.recipients.getOrNull(ri)?.let { it.name.ifBlank { it.email } } ?: "Signer" },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (field.type.hasLabel) {
            ZillitTextField(
                value = field.label,
                onValueChange = { v -> edit { it.copy(label = v) } },
                label = "Label",
                placeholder = "e.g. Full legal name",
                helperText = "What the signer sees they must fill in.",
            )
        }
        if (!field.type.isAutoStamped) {
            ZillitSwitch(
                checked = field.required,
                onCheckedChange = { on -> edit { it.copy(required = on) } },
                label = if (field.required) "Required" else "Optional",
            )
        }
        if (field.type.hasOptions) {
            ZillitText("OPTIONS", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            field.options.forEachIndexed { oi, option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ZillitTextField(
                        value = option.label,
                        onValueChange = { v ->
                            edit { f ->
                                f.copy(options = f.options.toMutableList().also { it[oi] = option.copy(label = v) })
                            }
                        },
                        placeholder = "Option ${oi + 1}",
                        modifier = Modifier.weight(1f),
                        errorText = if (option.label.isBlank() && index in editor.invalidFields) {
                            "Needs a label"
                        } else {
                            null
                        },
                    )
                    ZillitIconButton(
                        ZillitIcons.Close,
                        "Remove option",
                        onClick = { edit { f -> f.copy(options = f.options.filterIndexed { i, _ -> i != oi }) } },
                        enabled = field.options.size > 1,
                        size = 22.dp,
                    )
                }
            }
            ZillitButton(
                "Add option",
                onClick = {
                    edit { f ->
                        f.copy(options = f.options + FieldOption("opt${f.options.size + 1}_${newOptionSalt()}", ""))
                    }
                },
                variant = ButtonVariant.Tertiary,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Add,
            )
        }
        if (field.type.supportsDefault || field.type.isTyped || field.type == FieldType.Date) {
            Hairline()
            ZillitText("ADVANCED", style = ZillitTheme.typography.labelSmall, color = colors.textMuted)
            when {
                field.type == FieldType.Checkbox -> ZillitCheckbox(
                    checked = field.defaultValue == "true",
                    onCheckedChange = { on ->
                        edit { it.copy(defaultValue = if (on) "true" else "", locked = it.locked && on) }
                    },
                    label = "Checked by default",
                )
                field.type.hasOptions -> ZillitSelect(
                    value = field.options.firstOrNull { it.id == field.defaultValue },
                    options = listOf<FieldOption?>(null) + field.options,
                    onSelect = { opt ->
                        edit { it.copy(defaultValue = opt?.id.orEmpty(), locked = it.locked && opt != null) }
                    },
                    label = { opt -> opt?.label?.ifBlank { "(unlabelled)" } ?: "No default" },
                    modifier = Modifier.fillMaxWidth(),
                )
                else -> ZillitTextField(
                    value = field.defaultValue,
                    onValueChange = { v -> edit { it.copy(defaultValue = v, locked = it.locked && v.isNotBlank()) } },
                    label = "Default value",
                    placeholder = if (field.type == FieldType.Date) "YYYY-MM-DD" else "Pre-filled for the signer",
                )
            }
            ZillitSwitch(
                checked = field.locked,
                onCheckedChange = { on -> edit { it.copy(locked = on) } },
                enabled = field.defaultValue.isNotBlank(),
                label = "Locked — read-only to the signer",
            )
            if (field.defaultValue.isBlank()) {
                ZillitText(
                    "Set a default value to lock the field.",
                    style = ZillitTheme.typography.labelSmall,
                    color = colors.textMuted,
                )
            }
        }
        Hairline()
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ZillitButton(
                "Duplicate",
                onClick = { onEvent(EsignEvent.DuplicateField(index)) },
                variant = ButtonVariant.Secondary,
                size = ButtonSize.Small,
            )
            ZillitButton(
                "Delete field",
                onClick = { onEvent(EsignEvent.DeleteField(index)) },
                variant = ButtonVariant.Danger,
                size = ButtonSize.Small,
                leadingIcon = ZillitIcons.Trash,
            )
        }
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
private fun ModePickerDialog(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    ZillitDialogShell(
        title = "How is your document set up?",
        subtitle = "This decides what a click on the page does. You can change it any time.",
        visible = !editor.modeAsked && editor.pages.isNotEmpty(),
        onDismiss = { onEvent(EsignEvent.ChoosePlacementMode(editor.placementMode)) },
        scrollable = false,
        icon = ZillitIcons.Edit,
        width = 560.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            PlacementMode.entries.forEach { mode ->
                val active = editor.placementMode == mode
                Column(
                    Modifier.fillMaxWidth().clip(ZillitTheme.shapes.medium)
                        .border(
                            if (active) 2.dp else 1.dp,
                            if (active) colors.accent else colors.border,
                            ZillitTheme.shapes.medium,
                        )
                        .clickable { onEvent(EsignEvent.ChoosePlacementMode(mode)) }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ZillitText(mode.label, style = ZillitTheme.typography.titleSmall)
                    ZillitText(mode.help, style = ZillitTheme.typography.bodySmall, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun PendingDialog(editor: EditorState, onEvent: (EsignEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val pending = editor.pending
    ZillitDialogShell(
        title = "Who fills this field?",
        subtitle = pending?.let { "Page ${it.page} — pick the signer, then the type" },
        visible = pending != null,
        onDismiss = { onEvent(EsignEvent.CancelPending) },
        scrollable = false,
        icon = ZillitIcons.Users,
        width = 560.dp,
    ) {
        if (pending == null) return@ZillitDialogShell
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                editor.signerIndexes.forEachIndexed { position, ri ->
                    val signer = editor.recipients[ri]
                    val on = ri in pending.signerIndexes
                    Row(
                        Modifier.clip(ZillitTheme.shapes.pill)
                            .background(if (on) signerColor(position) else Color.Transparent)
                            .border(1.dp, if (on) signerColor(position) else colors.border, ZillitTheme.shapes.pill)
                            .clickable { onEvent(EsignEvent.TogglePendingSigner(ri)) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!on) Box(Modifier.size(8.dp).clip(CircleShape).background(signerColor(position)))
                        ZillitText(
                            signer.name.ifBlank { signer.email.ifBlank { "Signer ${position + 1}" } },
                            style = ZillitTheme.typography.label,
                            color = if (on) Color.White else colors.textPrimary,
                        )
                    }
                }
            }
            ZillitText(
                "Several signers place one field each, side by side.",
                style = ZillitTheme.typography.labelSmall,
                color = colors.textMuted,
            )
            Hairline()
            val types = FieldType.toolbar
            types.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { type ->
                        Row(
                            Modifier.weight(1f).clip(ZillitTheme.shapes.medium).border(
                                1.dp,
                                colors.border,
                                ZillitTheme.shapes.medium,
                            )
                                .clickable(enabled = pending.signerIndexes.isNotEmpty()) {
                                    onEvent(EsignEvent.PlacePending(type))
                                }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(type.color()))
                            ZillitText(type.label, style = ZillitTheme.typography.bodySmall)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun newOptionSalt(): Int = (0..OPTION_SALT_RANGE).random()

private const val OPTION_SALT_RANGE = 99_999
/** Room on the right for the scroll rail, so nothing sits under it. */
private val RAIL_PADDING = PaddingValues(start = 14.dp, top = 14.dp, end = 24.dp, bottom = 14.dp)
private const val ZOOM_STEP = 0.1f
