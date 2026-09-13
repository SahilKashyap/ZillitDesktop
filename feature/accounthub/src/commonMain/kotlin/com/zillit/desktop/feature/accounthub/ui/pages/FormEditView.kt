package com.zillit.desktop.feature.accounthub.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zillit.desktop.core.designsystem.ZillitTheme
import com.zillit.desktop.core.designsystem.component.ButtonVariant
import com.zillit.desktop.core.designsystem.component.StatusTone
import com.zillit.desktop.core.designsystem.component.ZillitButton
import com.zillit.desktop.core.designsystem.component.ZillitIcon
import com.zillit.desktop.core.designsystem.component.ZillitIconButton
import com.zillit.desktop.core.designsystem.component.ZillitScrollColumn
import com.zillit.desktop.core.designsystem.component.ZillitText
import com.zillit.desktop.core.designsystem.component.ZillitTooltip
import com.zillit.desktop.core.designsystem.icon.ZillitIcons
import com.zillit.desktop.core.forms.FormSection
import com.zillit.desktop.feature.accounthub.ui.AccountHubEvent
import com.zillit.desktop.feature.accounthub.ui.AccountHubUiState
import com.zillit.desktop.feature.accounthub.ui.FormConfigState
import com.zillit.desktop.feature.accounthub.ui.SectionRename
import com.zillit.desktop.feature.accounthub.ui.components.DashedInsertRail
import com.zillit.desktop.feature.accounthub.ui.components.FieldHint
import com.zillit.desktop.feature.accounthub.ui.components.MonoLabel
import com.zillit.desktop.feature.accounthub.ui.components.Pill

/**
 * Edit mode — the web's full-width editor: a sticky bar, the tips, every
 * section with its fields clickable and an insert rail above, between and
 * below, and the property or rearrange panel sliding in on the right.
 */
@Composable
internal fun FormEditView(state: AccountHubUiState, onEvent: (AccountHubEvent) -> Unit) {
    val config = state.formConfig
    val panelOpen = config.rearrange || config.focus != null
    // The panel keeps drawing what it last showed while it slides away.
    val panelState = rememberLatestNonNull(config.takeIf { panelOpen })
    Column(Modifier.fillMaxSize()) {
        EditTopBar(config, onEvent)
        Row(Modifier.fillMaxWidth().weight(1f)) {
            ZillitScrollColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                contentPadding = PaddingValues(
                    start = ZillitTheme.spacing.xl,
                    end = ZillitTheme.spacing.xl,
                    top = ZillitTheme.spacing.xl,
                    bottom = ZillitTheme.spacing.xxl,
                ),
            ) {
                EditTips()
                Box(Modifier.height(ZillitTheme.spacing.sm))
                DashedInsertRail("Insert section here") { onEvent(AccountHubEvent.ComposeFormSection(null)) }
                config.template.configurable.forEach { section ->
                    EditableSection(section, config, onEvent)
                    DashedInsertRail("Insert section here") {
                        onEvent(AccountHubEvent.ComposeFormSection(section.key))
                    }
                }
            }
            AnimatedVisibility(
                visible = panelOpen,
                enter = slideInHorizontally(tween(PANEL_MS)) { it / PANEL_SLIDE_SHARE } + fadeIn(tween(PANEL_MS)),
                exit = slideOutHorizontally(tween(PANEL_MS)) { it / PANEL_SLIDE_SHARE } + fadeOut(tween(PANEL_MS)),
            ) {
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(vertical = ZillitTheme.spacing.md)
                        .padding(end = ZillitTheme.spacing.md),
                ) {
                    val shown = panelState ?: return@BoxWithConstraints
                    val panelModifier = Modifier.heightIn(max = maxHeight)
                    if (shown.rearrange) {
                        FormRearrangePanel(shown, onEvent, panelModifier)
                    } else {
                        FormFieldInspector(state, shown, onEvent, panelModifier)
                    }
                }
            }
        }
    }
}

/** Back, "FORMS / Purchase Orders • Edit Fields", then Rearrange, Reset to defaults and Save. */
@Composable
private fun EditTopBar(config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val back = { onEvent(AccountHubEvent.EditForm(false)) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            .padding(horizontal = ZillitTheme.spacing.xl, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        BackChip(onClick = back)
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.sm),
        ) {
            MonoLabel("Forms", modifier = Modifier.clickable(onClick = back), color = colors.accentText)
            ZillitText(text = "/", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            ZillitText(
                text = config.module.label,
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.textSecondary,
                maxLines = 1,
            )
            ZillitText(text = "•", style = ZillitTheme.typography.bodyMedium, color = colors.textMuted)
            ZillitText(
                text = "Edit Fields",
                style = ZillitTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
            )
            if (config.dirty) Pill("Unsaved", tone = StatusTone.Pending, dot = true)
        }
        ToggleButton(
            text = "Rearrange",
            icon = SwapIcon,
            active = config.rearrange,
            onClick = { onEvent(AccountHubEvent.ToggleRearrange(!config.rearrange)) },
        )
        ZillitButton(
            text = if (config.resetting) "Resetting…" else "Reset to defaults",
            onClick = { onEvent(AccountHubEvent.AskResetFormTemplate) },
            variant = ButtonVariant.Secondary,
            loading = config.resetting,
            enabled = !config.busy,
        )
        ZillitButton(
            text = if (config.saving) "Saving…" else "Save",
            onClick = { onEvent(AccountHubEvent.SaveFormTemplate) },
            leadingIcon = ZillitIcons.Check,
            loading = config.saving,
            enabled = !config.busy,
        )
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(colors.border))
}

/** The web's square back button. */
@Composable
internal fun BackChip(onClick: () -> Unit, description: String = "Back") {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(BACK_CHIP)
            .clip(shape)
            .background(if (hovered) colors.surfaceHover else colors.surface)
            .border(1.dp, colors.border, shape)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ZillitIcon(
            icon = ZillitIcons.ChevronLeft,
            contentDescription = description,
            tint = colors.textSecondary,
            size = 15.dp,
        )
    }
}

/** A bar button that stays lit while its mode is on — Rearrange. */
@Composable
private fun ToggleButton(text: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = ZillitTheme.shapes.medium
    val content = if (active) colors.accentText else colors.textSecondary
    Row(
        modifier = Modifier
            .height(com.zillit.desktop.core.designsystem.ZillitDimens.controlHeight)
            .clip(shape)
            .background(
                when {
                    active -> colors.accentSoft
                    hovered -> colors.surfaceHover
                    else -> colors.surface
                },
            )
            .border(1.dp, if (active) colors.accent.copy(alpha = APPROVAL_RING_ALPHA) else colors.border, shape)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs + 2.dp),
    ) {
        ZillitIcon(icon = icon, tint = content, size = 13.dp)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            color = content,
        )
    }
}

/** The web's tip strip: a bulb in its own tile, then the five things a person can do here. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditTips() {
    val colors = ZillitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.accentSoft)
            .border(1.dp, colors.accent.copy(alpha = TIP_RING_ALPHA), shape)
            .padding(horizontal = ZillitTheme.spacing.md + 2.dp, vertical = ZillitTheme.spacing.md),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.md),
    ) {
        Box(
            modifier = Modifier
                .size(TIP_TILE)
                .clip(RoundedCornerShape(7.dp))
                .background(colors.surface)
                .border(1.dp, colors.accent.copy(alpha = TIP_RING_ALPHA), RoundedCornerShape(7.dp)),
            contentAlignment = Alignment.Center,
        ) {
            ZillitIcon(icon = BulbIcon, tint = colors.accentText, size = 14.dp)
        }
        FlowRow(
            modifier = Modifier.weight(1f).padding(top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.lg),
            verticalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xxs),
        ) {
            EDIT_TIPS.forEach { (lead, rest) ->
                ZillitText(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = colors.textPrimary)) { append(lead) }
                        append(" ")
                        append(rest)
                    },
                    style = ZillitTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/**
 * One section in the editor: its name (renamed in place when it is the
 * production's own), Remove on hover for those, how much of it is on the
 * form, and Add Custom Field — then its fields, each one a click from the
 * property panel.
 */
@Composable
private fun EditableSection(section: FormSection, config: FormConfigState, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val reveal by animateFloatAsState(if (hovered) 1f else 0f, tween(REVEAL_MS), label = "sectionActions")
    val focusedId = config.focus?.takeIf { it.sectionKey == section.key }?.fieldId
    val visible = section.visible
    FormSectionCard(
        modifier = Modifier.hoverable(interaction),
        header = {
            val rename = config.rename
            if (rename != null && rename.key == section.key) {
                RenameInput(rename, onEvent)
            } else {
                SectionTitle(section, reveal, onEvent)
            }
            Box(Modifier.weight(1f))
            if (!section.systemDefault) {
                // Composed always and revealed on hover: a control composed only
                // while hovered loses the press that reaches it.
                RemoveChip(
                    text = "Remove",
                    description = "Remove ${section.label}",
                    modifier = Modifier.alpha(reveal),
                ) { onEvent(AccountHubEvent.AskRemoveFormSection(section)) }
            }
            FieldHint("${visible.size}/${section.fields.size} visible")
            AccentLink(
                text = if (section.isLineItems) "Add Custom Column" else "Add Custom Field",
                onClick = { onEvent(AccountHubEvent.FocusFormField(section.key, null)) },
            )
        },
    ) {
        val onField = { field: com.zillit.desktop.core.forms.FormField ->
            onEvent(AccountHubEvent.FocusFormField(section.key, field.id))
        }
        when {
            visible.isEmpty() -> FieldHint(
                text = "Nothing in this section is on the form. Add a custom field, or bring a system field back " +
                    "from Add Custom Field.",
                modifier = Modifier.padding(ZillitTheme.spacing.lg + ZillitTheme.spacing.xs),
            )
            section.isLineItems -> LineItemsTable(visible, focusedId = focusedId, onField = onField)
            else -> FieldGrid(visible, config.module, focusedId = focusedId, onField = onField)
        }
    }
}

/**
 * The section's eyebrow. A section this production added is renamed by
 * double-clicking its name, as on the web — and by the pencil beside it,
 * which shows on hover, because a double-click is not something a desktop
 * user goes looking for.
 */
@Composable
private fun SectionTitle(section: FormSection, reveal: Float, onEvent: (AccountHubEvent) -> Unit) {
    if (section.systemDefault) {
        SectionEyebrow(section.label)
        return
    }
    val rename = { onEvent(AccountHubEvent.RenameFormSection(section.key, section.label)) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitTooltip("Double-click to rename") {
            SectionEyebrow(
                text = section.label,
                modifier = Modifier.pointerInput(section.key, section.label) {
                    detectTapGestures(onDoubleTap = { rename() })
                },
            )
        }
        ZillitIconButton(
            icon = ZillitIcons.Edit,
            contentDescription = "Rename ${section.label}",
            onClick = rename,
            size = PENCIL,
            modifier = Modifier.alpha(reveal),
        )
    }
}

/**
 * The name, editable in place: Enter or leaving the field keeps it, Escape
 * puts the old one back — the web's input, which commits on blur.
 */
@Composable
private fun RenameInput(rename: SectionRename, onEvent: (AccountHubEvent) -> Unit) {
    val colors = ZillitTheme.colors
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(rename.key) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = rename.name,
        onValueChange = { onEvent(AccountHubEvent.EditFormSectionRename(it)) },
        singleLine = true,
        textStyle = formEyebrow().copy(color = colors.accentText),
        cursorBrush = SolidColor(colors.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier
            .width(RENAME_WIDTH)
            .focusRequester(focus)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) onEvent(AccountHubEvent.SaveFormSectionRename)
                focused = state.isFocused
            }
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter -> {
                        onEvent(AccountHubEvent.SaveFormSectionRename)
                        true
                    }
                    Key.Escape -> {
                        onEvent(AccountHubEvent.DismissFormSectionRename)
                        true
                    }
                    else -> false
                }
            },
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .clip(ZillitTheme.shapes.small)
                    .background(colors.surface)
                    .border(1.dp, colors.accent, ZillitTheme.shapes.small)
                    .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs + 1.dp),
            ) { inner() }
        },
    )
}

/** The web's small solid red "REMOVE". */
@Composable
internal fun RemoveChip(text: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val fill by animateColorAsState(
        if (hovered) colors.danger.copy(alpha = HOVER_DIM) else colors.danger,
        label = "removeChip",
    )
    Box(
        modifier = modifier
            .clip(ZillitTheme.shapes.small)
            .background(fill)
            .hoverable(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClickLabel = description,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { contentDescription = description }
            .padding(horizontal = ZillitTheme.spacing.sm, vertical = ZillitTheme.spacing.xxs),
    ) {
        ZillitText(
            text = text.uppercase(),
            style = ZillitTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp),
            color = Color.White,
        )
    }
}

/** "+ Add Custom Field" — accent words that act. */
@Composable
internal fun AccentLink(text: String, onClick: () -> Unit, icon: ImageVector = ZillitIcons.Add) {
    val colors = ZillitTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .clip(ZillitTheme.shapes.small)
            .background(if (hovered) colors.accentSoft else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = ZillitTheme.spacing.xs, vertical = ZillitTheme.spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ZillitTheme.spacing.xs),
    ) {
        ZillitIcon(icon = icon, tint = colors.accentText, size = 11.dp)
        ZillitText(
            text = text,
            style = ZillitTheme.typography.label.copy(fontWeight = FontWeight.Bold),
            color = colors.accentText,
        )
    }
}

/** The last non-null [value], kept past the moment it goes null so a closing panel does not blank. */
@Composable
internal fun <T : Any> rememberLatestNonNull(value: T?): T? {
    val holder = remember { LatestHolder<T>() }
    if (value != null) holder.value = value
    return value ?: holder.value
}

/** A plain holder: written during composition on purpose, and never read as state. */
private class LatestHolder<T : Any> {
    var value: T? = null
}

private val EDIT_TIPS = listOf(
    "Click a field" to "to edit its properties",
    "+ Add Custom Field" to "to create new fields",
    "+ between sections" to "to insert a new section",
    "Double-click" to "a section name to rename it",
    "Rearrange" to "to drag & drop sections and fields",
)

private const val PANEL_MS = 200
private const val PANEL_SLIDE_SHARE = 5
private const val REVEAL_MS = 120
private const val TIP_RING_ALPHA = 0.25f
private const val HOVER_DIM = 0.85f
private val BACK_CHIP = 34.dp
private val TIP_TILE = 24.dp
private val PENCIL = 22.dp
private val RENAME_WIDTH = 220.dp
